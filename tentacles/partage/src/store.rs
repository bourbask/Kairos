//! Liens de partage et sessions : seules les empreintes SHA-256 des jetons et des
//! sessions sont stockées. Pendant sa fenêtre de validité, un lien s'échange contre
//! une session par appareil, dans la limite d'un plafond ; sa révocation coupe toutes
//! les sessions qui en sont issues.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

/// Instant en secondes Unix.
pub type Ts = i64;

/// Conservation des lignes après la fin de l'accès (expiration ou révocation).
pub const KEEP_SECS: Ts = 30 * 24 * 3600;

/// Au-delà, un lien du propriétaire jamais ouvert n'est plus listé.
pub const OWNER_UNOPENED_SECS: Ts = 72 * 3600;

/// Fenêtre d'échange rendue aux liens ouverts avant l'existence des sessions multiples.
pub const MIGRATED_WINDOW_SECS: Ts = 72 * 3600;

/// Appareils qu'un même lien peut ouvrir, par défaut.
pub const MAX_DEVICES: i64 = 3;

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
    /// Fin de la fenêtre pendant laquelle le lien peut ouvrir de nouveaux appareils.
    pub expire_le: Ts,
    /// Premier échange.
    pub ouvert_le: Option<Ts>,
    pub revoque: bool,
    /// Lien demandé par l'application pour l'accès de son propriétaire.
    pub proprietaire: bool,
    /// Échéance de chaque session issue du lien.
    pub sessions: Vec<Ts>,
}

impl Share {
    pub fn actif(&self, now: Ts) -> bool {
        !self.revoque && (now < self.expire_le || self.appareils(now) > 0)
    }

    /// Sessions encore ouvertes.
    pub fn appareils(&self, now: Ts) -> i64 {
        if self.revoque { 0 } else { self.sessions.iter().filter(|&&e| e > now).count() as i64 }
    }

    /// Échéance de la session la plus tardive.
    pub fn acces_jusqu_au(&self) -> Option<Ts> {
        self.sessions.iter().copied().max()
    }

    /// Un lien du propriétaire jamais ouvert, remplacé ou trop ancien, n'encombre pas la liste.
    pub fn visible(&self, now: Ts) -> bool {
        !(self.proprietaire && self.ouvert_le.is_none() && (self.revoque || now - self.cree_le > OWNER_UNOPENED_SECS))
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

    fn init(mut c: Connection) -> anyhow::Result<Store> {
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
                 revoque_le INTEGER,
                 proprietaire INTEGER NOT NULL DEFAULT 0
             );
             CREATE TABLE IF NOT EXISTS sessions (
                 id INTEGER PRIMARY KEY,
                 lien INTEGER NOT NULL REFERENCES liens(id),
                 empreinte TEXT NOT NULL UNIQUE,
                 cree_le INTEGER NOT NULL,
                 expire_le INTEGER NOT NULL
             );
             CREATE INDEX IF NOT EXISTS sessions_lien ON sessions(lien);",
        )?;
        // Bases créées avant le marqueur : le lien du propriétaire portait ce libellé.
        if !c.prepare("SELECT 1 FROM pragma_table_info('liens') WHERE name = 'proprietaire'")?.exists([])? {
            c.execute_batch(
                "ALTER TABLE liens ADD COLUMN proprietaire INTEGER NOT NULL DEFAULT 0;
                 UPDATE liens SET proprietaire = 1 WHERE libelle = 'moi';",
            )?;
        }
        // Bases à session unique : la session part dans `sessions` et le lien retrouve sa
        // fenêtre d'échange, que la fin de session avait remplacée. Colonne vidée : rejouable.
        if c.prepare("SELECT 1 FROM pragma_table_info('liens') WHERE name = 'session'")?.exists([])? {
            let tx = c.transaction()?;
            tx.execute_batch(
                "INSERT INTO sessions (lien, empreinte, cree_le, expire_le)
                     SELECT id, session, ouvert_le, expire_le FROM liens WHERE session IS NOT NULL;",
            )?;
            tx.execute(
                "UPDATE liens SET expire_le = cree_le + ?1, session = NULL WHERE session IS NOT NULL",
                params![MIGRATED_WINDOW_SECS],
            )?;
            tx.commit()?;
        }
        Ok(Store { db: Mutex::new(c) })
    }

    fn db(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.db.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Crée un lien valable `ttl` secondes ; rend (id, jeton en clair, échéance).
    pub fn create(&self, libelle: &str, now: Ts, ttl: Ts) -> anyhow::Result<(i64, String, Ts)> {
        self.insert(libelle, now, ttl, false)
    }

    /// Lien du propriétaire : remplace ses liens précédents jamais ouverts.
    pub fn create_owner(&self, libelle: &str, now: Ts, ttl: Ts) -> anyhow::Result<(i64, String, Ts)> {
        self.insert(libelle, now, ttl, true)
    }

    fn insert(&self, libelle: &str, now: Ts, ttl: Ts, owner: bool) -> anyhow::Result<(i64, String, Ts)> {
        let token = new_secret();
        let expire = now + ttl;
        let mut db = self.db();
        let tx = db.transaction()?;
        if owner {
            tx.execute(
                "UPDATE liens SET revoque_le = ?1 WHERE proprietaire = 1 AND ouvert_le IS NULL AND revoque_le IS NULL",
                params![now],
            )?;
        }
        tx.execute(
            "INSERT INTO liens (libelle, jeton, cree_le, expire_le, proprietaire) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![libelle, hash(&token), now, expire, owner],
        )?;
        let id = tx.last_insert_rowid();
        tx.commit()?;
        Ok((id, token, expire))
    }

    /// Échange atomique : un jeton valide, non révoqué, dans sa fenêtre et qui n'a pas
    /// encore ouvert `max_devices` sessions en ouvre une de `session_ttl` secondes.
    /// Rend la session en clair.
    pub fn redeem(&self, token: &str, now: Ts, session_ttl: Ts, max_devices: i64) -> anyhow::Result<Option<String>> {
        if !well_formed(token) {
            return Ok(None);
        }
        let session = new_secret();
        let mut db = self.db();
        // Verrou d'écriture pris d'emblée : le décompte et l'insertion ne peuvent pas être
        // entrelacés avec un autre échange, même depuis une autre connexion.
        let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let n = tx.execute(
            "INSERT INTO sessions (lien, empreinte, cree_le, expire_le)
             SELECT id, ?1, ?2, ?3 FROM liens
             WHERE jeton = ?4 AND revoque_le IS NULL AND expire_le > ?2
               AND (SELECT COUNT(*) FROM sessions WHERE lien = liens.id) < ?5",
            params![hash(&session), now, now + session_ttl, hash(token), max_devices],
        )?;
        if n != 1 {
            return Ok(None);
        }
        tx.execute("UPDATE liens SET ouvert_le = COALESCE(ouvert_le, ?1) WHERE jeton = ?2", params![now, hash(token)])?;
        tx.commit()?;
        Ok(Some(session))
    }

    /// Vrai si la session existe, n'a pas expiré et que son lien n'est pas révoqué.
    pub fn verify(&self, session: &str, now: Ts) -> anyhow::Result<bool> {
        if !well_formed(session) {
            return Ok(false);
        }
        Ok(self
            .db()
            .query_row(
                "SELECT 1 FROM sessions s JOIN liens l ON l.id = s.lien
                 WHERE s.empreinte = ?1 AND l.revoque_le IS NULL AND s.expire_le > ?2",
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
            "SELECT id, libelle, cree_le, expire_le, ouvert_le, revoque_le IS NOT NULL, proprietaire FROM liens {tail}"
        ))?;
        let rows = st.query_map(p, |r| {
            Ok(Share {
                id: r.get(0)?,
                libelle: r.get(1)?,
                cree_le: r.get(2)?,
                expire_le: r.get(3)?,
                ouvert_le: r.get(4)?,
                revoque: r.get(5)?,
                proprietaire: r.get(6)?,
                sessions: Vec::new(),
            })
        })?;
        let mut shares: Vec<Share> = rows.collect::<Result<_, _>>()?;
        let mut st = db.prepare("SELECT expire_le FROM sessions WHERE lien = ?1 ORDER BY id")?;
        for s in &mut shares {
            s.sessions = st.query_map([s.id], |r| r.get(0))?.collect::<Result<_, _>>()?;
        }
        Ok(shares)
    }

    /// Supprime les liens, et leurs sessions, dont l'accès a pris fin depuis plus de
    /// [KEEP_SECS] : révocation, ou fin de la fenêtre et de la dernière session.
    pub fn purge(&self, now: Ts) -> anyhow::Result<usize> {
        // Fin d'accès : la révocation si elle précède, sinon la plus tardive de la fenêtre et des sessions.
        const ENDED: &str = "(SELECT id FROM liens WHERE MIN(fin, COALESCE(revoque_le, fin)) < ?1)";
        let ended = ENDED.replace(
            "fin",
            "MAX(expire_le, COALESCE((SELECT MAX(s.expire_le) FROM sessions s WHERE s.lien = liens.id), expire_le))",
        );
        let mut db = self.db();
        let tx = db.transaction()?;
        // Sessions d'abord : leur retrait ne peut qu'avancer la fin d'accès, le lien reste donc visé.
        tx.execute(&format!("DELETE FROM sessions WHERE lien IN {ended}"), params![now - KEEP_SECS])?;
        let n = tx.execute(&format!("DELETE FROM liens WHERE id IN {ended}"), params![now - KEEP_SECS])?;
        tx.commit()?;
        Ok(n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const H: Ts = 3600;

    const M: i64 = MAX_DEVICES;
    const D: Ts = 30 * 24 * H;

    #[test]
    fn redeem_up_to_max_devices_then_refused() {
        let s = Store::memory().unwrap();
        let (_, tok, _) = s.create("a", 0, 72 * H).unwrap();
        let sess: Vec<String> = (0..M).map(|i| s.redeem(&tok, 10 + i, D, M).unwrap().expect("appareil admis")).collect();
        assert!(sess.iter().all(|x| s.verify(x, 20).unwrap()));
        assert!(s.redeem(&tok, 20, D, M).unwrap().is_none(), "appareil de trop");
        // Chaque session court depuis son propre échange.
        assert!(s.verify(&sess[0], 10 + D - 1).unwrap() && !s.verify(&sess[0], 10 + D).unwrap());
        assert!(s.verify(&sess[1], 10 + D).unwrap());
        let l = s.get(1).unwrap().unwrap();
        assert_eq!(l.ouvert_le, Some(10), "premier échange");
        assert_eq!(l.expire_le, 72 * H, "fenêtre d'échange inchangée");
        assert_eq!(l.appareils(20), M);
        assert_eq!(l.acces_jusqu_au(), Some(10 + M - 1 + D));
    }

    #[test]
    fn device_cap_holds_under_concurrency() {
        let dir = std::env::temp_dir().join(format!("partage-test-{}", new_secret()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("p.db");
        let (_, tok, _) = Store::open(&path).unwrap().create("a", 0, 72 * H).unwrap();
        // Connexions distinctes : le verrou applicatif ne protège rien ici, seule la base arbitre.
        let ok: usize = std::thread::scope(|sc| {
            let hs: Vec<_> = (0..12)
                .map(|_| {
                    let (path, tok) = (&path, &tok);
                    sc.spawn(move || Store::open(path).unwrap().redeem(tok, 1, D, M).unwrap().is_some() as usize)
                })
                .collect();
            hs.into_iter().map(|h| h.join().unwrap()).sum()
        });
        std::fs::remove_dir_all(&dir).ok();
        assert_eq!(ok as i64, M);
    }

    #[test]
    fn only_hashes_are_stored() {
        let s = Store::memory().unwrap();
        let (_, tok, _) = s.create("a", 0, H).unwrap();
        let sess = s.redeem(&tok, 1, H, M).unwrap().unwrap();
        let j: String = s.db().query_row("SELECT jeton FROM liens", [], |r| r.get(0)).unwrap();
        let se: String = s.db().query_row("SELECT empreinte FROM sessions", [], |r| r.get(0)).unwrap();
        assert_eq!(j, hash(&tok));
        assert_eq!(se, hash(&sess));
        assert_ne!(j, tok);
    }

    #[test]
    fn expired_link_refused() {
        let s = Store::memory().unwrap();
        let (_, tok, exp) = s.create("a", 0, 72 * H).unwrap();
        assert!(s.redeem(&tok, exp, H, M).unwrap().is_none());
    }

    #[test]
    fn unknown_or_malformed_refused() {
        let s = Store::memory().unwrap();
        assert!(s.redeem(&new_secret(), 0, H, M).unwrap().is_none());
        assert!(s.redeem("x' OR 1=1 --", 0, H, M).unwrap().is_none());
        assert!(!s.verify("", 0).unwrap());
    }

    #[test]
    fn revoke_kills_link_and_session() {
        let s = Store::memory().unwrap();
        let (a, ta, _) = s.create("a", 0, H).unwrap();
        let (b, tb, _) = s.create("b", 0, H).unwrap();
        let sess = s.redeem(&ta, 1, 10 * H, M).unwrap().unwrap();
        assert!(s.revoke(a, 2).unwrap());
        assert!(!s.verify(&sess, 3).unwrap());
        assert!(s.revoke(b, 2).unwrap());
        assert!(s.redeem(&tb, 3, H, M).unwrap().is_none());
        assert!(!s.revoke(b, 4).unwrap(), "déjà révoqué");
        assert!(!s.revoke(999, 4).unwrap());
    }

    #[test]
    fn session_expires() {
        let s = Store::memory().unwrap();
        let (_, t, _) = s.create("a", 0, H).unwrap();
        let sess = s.redeem(&t, 0, 10, M).unwrap().unwrap();
        assert!(s.verify(&sess, 9).unwrap());
        assert!(!s.verify(&sess, 10).unwrap());
    }

    #[test]
    fn list_newest_first_with_state() {
        let s = Store::memory().unwrap();
        let (a, ta, _) = s.create("a", 0, H).unwrap();
        s.create("b", 5, H).unwrap();
        s.redeem(&ta, 10, 100 * H, M).unwrap();
        let l = s.list().unwrap();
        assert_eq!(l.iter().map(|x| x.libelle.as_str()).collect::<Vec<_>>(), ["b", "a"]);
        let first = s.get(a).unwrap().unwrap();
        assert_eq!(first.ouvert_le, Some(10));
        assert_eq!(first.expire_le, H);
        assert_eq!(first.appareils(11), 1);
        // Fenêtre close, session vivante : toujours actif.
        assert!(first.actif(2 * H));
        assert!(!first.actif(10 + 100 * H));
        assert_eq!(first.appareils(10 + 100 * H), 0);
        assert!(!l[0].actif(5 + H));
    }

    #[test]
    fn owner_link_replaces_unopened_owner_links() {
        let s = Store::memory().unwrap();
        let (vieux, _, _) = s.create_owner("moi", 0, 72 * H).unwrap();
        let (ouvert, t, _) = s.create_owner("moi", 1, 72 * H).unwrap();
        s.redeem(&t, 2, 100 * H, M).unwrap().unwrap();
        let (manuel, _, _) = s.create("moi", 3, 72 * H).unwrap();
        let (neuf, _, _) = s.create_owner("moi", 4, 72 * H).unwrap();
        let get = |id| s.get(id).unwrap().unwrap();
        assert!(get(vieux).revoque && get(vieux).proprietaire);
        // Session ouverte et partage saisi à la main : intacts.
        assert!(!get(ouvert).revoque && !get(manuel).revoque && !get(manuel).proprietaire);
        assert!(get(neuf).actif(5));
    }

    #[test]
    fn unopened_owner_links_hidden_after_72h_or_replaced() {
        let s = Store::memory().unwrap();
        let (a, _, _) = s.create_owner("moi", 0, 72 * H).unwrap();
        let (m, _, _) = s.create("moi", 0, 72 * H).unwrap();
        let get = |id| s.get(id).unwrap().unwrap();
        assert!(get(a).visible(72 * H - 1));
        assert!(!get(a).visible(72 * H + 1));
        assert!(get(m).visible(72 * H + 1), "partage ordinaire toujours listé");
        let (b, tb, _) = s.create_owner("moi", 10, 72 * H).unwrap();
        assert!(!get(a).visible(11), "remplacé");
        s.redeem(&tb, 12, 100 * H, M).unwrap().unwrap();
        assert!(get(b).visible(100 * H), "ouvert : listé");
    }

    #[test]
    fn migration_flags_existing_owner_links() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(
            "CREATE TABLE liens (id INTEGER PRIMARY KEY, libelle TEXT NOT NULL, jeton TEXT NOT NULL UNIQUE,
                 cree_le INTEGER NOT NULL, expire_le INTEGER NOT NULL, ouvert_le INTEGER, session TEXT UNIQUE, revoque_le INTEGER);
             INSERT INTO liens (libelle, jeton, cree_le, expire_le) VALUES ('moi', 'j1', 0, 10), ('Exemple', 'j2', 0, 10);",
        )
        .unwrap();
        let s = Store::init(c).unwrap();
        assert!(s.get(1).unwrap().unwrap().proprietaire);
        assert!(!s.get(2).unwrap().unwrap().proprietaire);
        // Rouvrir ne refait rien.
        let s = Store::init(s.db.into_inner().unwrap()).unwrap();
        assert!(s.get(1).unwrap().unwrap().proprietaire);
    }

    #[test]
    fn migration_moves_opened_sessions_and_restores_window() {
        let c = Connection::open_in_memory().unwrap();
        let (vieille, tiers) = (new_secret(), new_secret());
        c.execute_batch(
            "CREATE TABLE liens (id INTEGER PRIMARY KEY, libelle TEXT NOT NULL, jeton TEXT NOT NULL UNIQUE,
                 cree_le INTEGER NOT NULL, expire_le INTEGER NOT NULL, ouvert_le INTEGER, session TEXT UNIQUE,
                 revoque_le INTEGER, proprietaire INTEGER NOT NULL DEFAULT 0);",
        )
        .unwrap();
        let tok = new_secret();
        // Ouvert à 10 : l'échéance du lien avait été remplacée par la fin de session.
        c.execute(
            "INSERT INTO liens (libelle, jeton, cree_le, expire_le, ouvert_le, session) VALUES ('a', ?1, 0, ?2, 10, ?3),
                 ('b', 'j2', 0, ?4, NULL, NULL), ('c', 'j3', 0, ?5, 5, ?6)",
            params![hash(&tok), 10 + D, hash(&vieille), 72 * H, 5 + D, hash(&tiers)],
        )
        .unwrap();
        let s = Store::init(c).unwrap();
        let a = s.get(1).unwrap().unwrap();
        assert_eq!((a.expire_le, a.ouvert_le, a.acces_jusqu_au()), (72 * H, Some(10), Some(10 + D)));
        assert_eq!(s.get(2).unwrap().unwrap().expire_le, 72 * H, "jamais ouvert : intact");
        assert!(s.verify(&vieille, 20).unwrap() && s.verify(&tiers, 20).unwrap());
        assert!(!s.verify(&vieille, 10 + D).unwrap());
        // Encore dans la fenêtre : deux appareils de plus, pas trois.
        assert!(s.redeem(&tok, 30, D, M).unwrap().is_some());
        assert!(s.redeem(&tok, 31, D, M).unwrap().is_some());
        assert!(s.redeem(&tok, 32, D, M).unwrap().is_none());
        // Rouvrir ne refait rien.
        let s = Store::init(s.db.into_inner().unwrap()).unwrap();
        let n: i64 = s.db().query_row("SELECT COUNT(*) FROM sessions", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 4);
        assert!(s.verify(&vieille, 20).unwrap());
        assert_eq!(s.get(1).unwrap().unwrap().expire_le, 72 * H);
    }

    #[test]
    fn migrated_link_past_window_refuses_new_devices() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(
            "CREATE TABLE liens (id INTEGER PRIMARY KEY, libelle TEXT NOT NULL, jeton TEXT NOT NULL UNIQUE,
                 cree_le INTEGER NOT NULL, expire_le INTEGER NOT NULL, ouvert_le INTEGER, session TEXT UNIQUE,
                 revoque_le INTEGER, proprietaire INTEGER NOT NULL DEFAULT 0);",
        )
        .unwrap();
        let (tok, sess) = (new_secret(), new_secret());
        c.execute("INSERT INTO liens (libelle, jeton, cree_le, expire_le, ouvert_le, session) VALUES ('a', ?1, 0, ?2, 10, ?3)", params![hash(&tok), 10 + D, hash(&sess)]).unwrap();
        let s = Store::init(c).unwrap();
        assert!(s.redeem(&tok, 72 * H, D, M).unwrap().is_none());
        assert!(s.verify(&sess, 72 * H).unwrap());
        assert!(s.get(1).unwrap().unwrap().actif(72 * H));
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
        let (o, t, _) = s.create("ouvert", 0, H).unwrap();
        s.redeem(&t, 0, 10 * H, M).unwrap().unwrap();
        let (_, t2, _) = s.create("ouvert puis révoqué", 0, H).unwrap();
        s.redeem(&t2, 0, 10 * H, M).unwrap().unwrap();
        s.revoke(s.list().unwrap()[0].id, 3 * H).unwrap();
        assert_eq!(s.purge(H + KEEP_SECS).unwrap(), 0);
        assert_eq!(s.purge(3 * H + KEEP_SECS + 1).unwrap(), 3);
        // Conservé jusqu'à 30 jours après la fin de sa dernière session.
        assert!(s.get(o).unwrap().is_some());
        assert_eq!(s.purge(10 * H + KEEP_SECS + 1).unwrap(), 1);
        assert_eq!(s.list().unwrap().len(), 1);
        let n: i64 = s.db().query_row("SELECT COUNT(*) FROM sessions", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 0, "sessions purgées avec leur lien");
    }
}
