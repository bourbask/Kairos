//! Liens de partage et sessions : seules les empreintes SHA-256 des jetons et des
//! sessions sont stockées. Un lien s'échange une seule fois contre une session ;
//! sa révocation coupe la session qui en est issue.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

/// Instant en secondes Unix.
pub type Ts = i64;

/// Conservation des lignes après la fin de l'accès (expiration ou révocation).
pub const KEEP_SECS: Ts = 30 * 24 * 3600;

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// 32 octets aléatoires, en hexadécimal.
pub fn new_secret() -> String {
    let mut buf = [0u8; 32];
    getrandom::fill(&mut buf).expect("source d'aléa système indisponible");
    hex(&buf)
}

/// Empreinte SHA-256 (hex), seule forme stockée.
pub fn hash(secret: &str) -> String {
    hex(&Sha256::digest(secret.as_bytes()))
}

/// Forme d'un secret émis ici : 64 caractères hexadécimaux minuscules.
pub fn well_formed(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Share {
    pub id: i64,
    pub libelle: String,
    pub cree_le: Ts,
    /// Avant ouverture : échéance du lien ; après : fin de la session.
    pub expire_le: Ts,
    pub ouvert_le: Option<Ts>,
    pub revoque: bool,
}

impl Share {
    pub fn actif(&self, now: Ts) -> bool {
        !self.revoque && now < self.expire_le
    }
}

pub struct Store {
    db: Mutex<Connection>,
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> anyhow::Result<Store> {
        Self::init(Connection::open(path)?)
    }

    pub fn memory() -> anyhow::Result<Store> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(c: Connection) -> anyhow::Result<Store> {
        // L'administration depuis l'hôte peut écrire en même temps que le service.
        c.busy_timeout(std::time::Duration::from_secs(5))?;
        // Libellés (tiers) et empreintes purgés réellement effacés du fichier, pas seulement délistés.
        c.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA secure_delete=ON;
             CREATE TABLE IF NOT EXISTS liens (
                 id INTEGER PRIMARY KEY,
                 libelle TEXT NOT NULL,
                 jeton TEXT NOT NULL UNIQUE,
                 cree_le INTEGER NOT NULL,
                 expire_le INTEGER NOT NULL,
                 ouvert_le INTEGER,
                 session TEXT UNIQUE,
                 revoque_le INTEGER
             );",
        )?;
        Ok(Store { db: Mutex::new(c) })
    }

    fn db(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.db.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Crée un lien valable `ttl` secondes ; rend (id, jeton en clair, échéance).
    pub fn create(&self, libelle: &str, now: Ts, ttl: Ts) -> anyhow::Result<(i64, String, Ts)> {
        let token = new_secret();
        let expire = now + ttl;
        let db = self.db();
        db.execute(
            "INSERT INTO liens (libelle, jeton, cree_le, expire_le) VALUES (?1, ?2, ?3, ?4)",
            params![libelle, hash(&token), now, expire],
        )?;
        Ok((db.last_insert_rowid(), token, expire))
    }

    /// Échange atomique : un jeton valide, jamais utilisé, non révoqué et non expiré
    /// ouvre une session de `session_ttl` secondes. Rend la session en clair.
    pub fn redeem(&self, token: &str, now: Ts, session_ttl: Ts) -> anyhow::Result<Option<String>> {
        if !well_formed(token) {
            return Ok(None);
        }
        let session = new_secret();
        let n = self.db().execute(
            "UPDATE liens SET ouvert_le = ?1, session = ?2, expire_le = ?3
             WHERE jeton = ?4 AND ouvert_le IS NULL AND revoque_le IS NULL AND expire_le > ?1",
            params![now, hash(&session), now + session_ttl, hash(token)],
        )?;
        Ok((n == 1).then_some(session))
    }

    /// Vrai si la session existe, n'a pas expiré et que son lien n'est pas révoqué.
    pub fn verify(&self, session: &str, now: Ts) -> anyhow::Result<bool> {
        if !well_formed(session) {
            return Ok(false);
        }
        Ok(self
            .db()
            .query_row(
                "SELECT 1 FROM liens WHERE session = ?1 AND revoque_le IS NULL AND expire_le > ?2",
                params![hash(session), now],
                |_| Ok(()),
            )
            .optional()?
            .is_some())
    }

    /// Faux si le lien est inconnu ou déjà révoqué.
    pub fn revoke(&self, id: i64, now: Ts) -> anyhow::Result<bool> {
        Ok(self.db().execute("UPDATE liens SET revoque_le = ?1 WHERE id = ?2 AND revoque_le IS NULL", params![now, id])? == 1)
    }

    pub fn get(&self, id: i64) -> anyhow::Result<Option<Share>> {
        Ok(self.select("WHERE id = ?1", params![id])?.pop())
    }

    /// Plus récents d'abord.
    pub fn list(&self) -> anyhow::Result<Vec<Share>> {
        self.select("ORDER BY cree_le DESC, id DESC", [])
    }

    fn select(&self, tail: &str, p: impl rusqlite::Params) -> anyhow::Result<Vec<Share>> {
        let db = self.db();
        let mut st = db.prepare(&format!(
            "SELECT id, libelle, cree_le, expire_le, ouvert_le, revoque_le IS NOT NULL FROM liens {tail}"
        ))?;
        let rows = st.query_map(p, |r| {
            Ok(Share { id: r.get(0)?, libelle: r.get(1)?, cree_le: r.get(2)?, expire_le: r.get(3)?, ouvert_le: r.get(4)?, revoque: r.get(5)? })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    /// Supprime les lignes dont l'accès a pris fin depuis plus de [KEEP_SECS].
    pub fn purge(&self, now: Ts) -> anyhow::Result<usize> {
        Ok(self.db().execute(
            "DELETE FROM liens WHERE MIN(expire_le, COALESCE(revoque_le, expire_le)) < ?1",
            params![now - KEEP_SECS],
        )?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const H: Ts = 3600;

    #[test]
    fn redeem_once_then_refused() {
        let s = Store::memory().unwrap();
        let (_, tok, _) = s.create("a", 0, 72 * H).unwrap();
        let sess = s.redeem(&tok, 10, 30 * 24 * H).unwrap().expect("premier échange");
        assert!(s.verify(&sess, 20).unwrap());
        assert!(s.redeem(&tok, 11, 30 * 24 * H).unwrap().is_none());
    }

    #[test]
    fn only_hashes_are_stored() {
        let s = Store::memory().unwrap();
        let (_, tok, _) = s.create("a", 0, H).unwrap();
        let sess = s.redeem(&tok, 1, H).unwrap().unwrap();
        let (j, se): (String, String) = s.db().query_row("SELECT jeton, session FROM liens", [], |r| Ok((r.get(0)?, r.get(1)?))).unwrap();
        assert_eq!(j, hash(&tok));
        assert_eq!(se, hash(&sess));
        assert_ne!(j, tok);
    }

    #[test]
    fn expired_link_refused() {
        let s = Store::memory().unwrap();
        let (_, tok, exp) = s.create("a", 0, 72 * H).unwrap();
        assert!(s.redeem(&tok, exp, H).unwrap().is_none());
    }

    #[test]
    fn unknown_or_malformed_refused() {
        let s = Store::memory().unwrap();
        assert!(s.redeem(&new_secret(), 0, H).unwrap().is_none());
        assert!(s.redeem("x' OR 1=1 --", 0, H).unwrap().is_none());
        assert!(!s.verify("", 0).unwrap());
    }

    #[test]
    fn revoke_kills_link_and_session() {
        let s = Store::memory().unwrap();
        let (a, ta, _) = s.create("a", 0, H).unwrap();
        let (b, tb, _) = s.create("b", 0, H).unwrap();
        let sess = s.redeem(&ta, 1, 10 * H).unwrap().unwrap();
        assert!(s.revoke(a, 2).unwrap());
        assert!(!s.verify(&sess, 3).unwrap());
        assert!(s.revoke(b, 2).unwrap());
        assert!(s.redeem(&tb, 3, H).unwrap().is_none());
        assert!(!s.revoke(b, 4).unwrap(), "déjà révoqué");
        assert!(!s.revoke(999, 4).unwrap());
    }

    #[test]
    fn session_expires() {
        let s = Store::memory().unwrap();
        let (_, t, _) = s.create("a", 0, H).unwrap();
        let sess = s.redeem(&t, 0, 10).unwrap().unwrap();
        assert!(s.verify(&sess, 9).unwrap());
        assert!(!s.verify(&sess, 10).unwrap());
    }

    #[test]
    fn list_newest_first_with_state() {
        let s = Store::memory().unwrap();
        let (a, ta, _) = s.create("a", 0, H).unwrap();
        s.create("b", 5, H).unwrap();
        s.redeem(&ta, 10, 100 * H).unwrap();
        let l = s.list().unwrap();
        assert_eq!(l.iter().map(|x| x.libelle.as_str()).collect::<Vec<_>>(), ["b", "a"]);
        let first = s.get(a).unwrap().unwrap();
        assert_eq!(first.ouvert_le, Some(10));
        assert_eq!(first.expire_le, 10 + 100 * H);
        assert!(first.actif(11));
        assert!(!l[0].actif(5 + H));
    }

    #[test]
    fn secure_delete_is_on() {
        let s = Store::memory().unwrap();
        let on: i64 = s.db().query_row("PRAGMA secure_delete", [], |r| r.get(0)).unwrap();
        assert_eq!(on, 1);
    }

    #[test]
    fn purge_after_keep_period() {
        let s = Store::memory().unwrap();
        s.create("expiré", 0, H).unwrap();
        let (r, _, _) = s.create("révoqué tôt", 0, 1000 * H).unwrap();
        s.revoke(r, 2 * H).unwrap();
        s.create("frais", 0, 1000 * H).unwrap();
        assert_eq!(s.purge(H + KEEP_SECS).unwrap(), 0);
        assert_eq!(s.purge(2 * H + KEEP_SECS + 1).unwrap(), 2);
        assert_eq!(s.list().unwrap().len(), 1);
    }
}
