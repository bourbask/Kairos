//! Base des signalements : aucune donnée identifiante du lecteur n'est conservée.
//! La clé d'idempotence ne vit qu'une heure, le temps d'absorber un renvoi.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, params};

use super::validation::{Signalement, Source};

const FENETRE_IDEM: i64 = 3600;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Etat {
    Nouveau,
    EnCours,
    Corrige,
    Rejete,
    Doublon,
}

impl Etat {
    pub fn texte(self) -> &'static str {
        match self {
            Etat::Nouveau => "nouveau",
            Etat::EnCours => "en_cours",
            Etat::Corrige => "corrige",
            Etat::Rejete => "rejete",
            Etat::Doublon => "doublon",
        }
    }

    pub fn depuis(s: &str) -> Option<Etat> {
        [Etat::Nouveau, Etat::EnCours, Etat::Corrige, Etat::Rejete, Etat::Doublon]
            .into_iter()
            .find(|e| e.texte() == s)
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct Filtre {
    pub etat: Option<Etat>,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Recu {
    pub reference: String,
    pub recu_a: i64,
    pub nouveau: bool,
}

#[derive(Debug)]
pub enum Depot {
    Plafond,
    Erreur(anyhow::Error),
}

impl<E: Into<anyhow::Error>> From<E> for Depot {
    fn from(e: E) -> Self {
        Depot::Erreur(e.into())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ligne {
    pub id: i64,
    pub reference: String,
    pub claim: String,
    pub kind: String,
    pub target: Option<String>,
    pub what: String,
    pub basis: String,
    pub url: Option<String>,
    pub ref_who: Option<String>,
    pub ref_when: Option<String>,
    pub ref_what: Option<String>,
    pub ref_in: Option<String>,
    pub fix: String,
    pub recu_a: i64,
    pub etat: String,
    pub note: String,
}

const COLONNES: &str = "id, reference, claim, kind, target, what, basis, url, ref_who, \
                        ref_when, ref_what, ref_in, fix, recu_a, etat, note";

fn ligne(r: &rusqlite::Row) -> rusqlite::Result<Ligne> {
    Ok(Ligne {
        id: r.get(0)?,
        reference: r.get(1)?,
        claim: r.get(2)?,
        kind: r.get(3)?,
        target: r.get(4)?,
        what: r.get(5)?,
        basis: r.get(6)?,
        url: r.get(7)?,
        ref_who: r.get(8)?,
        ref_when: r.get(9)?,
        ref_what: r.get(10)?,
        ref_in: r.get(11)?,
        fix: r.get(12)?,
        recu_a: r.get(13)?,
        etat: r.get(14)?,
        note: r.get(15)?,
    })
}

/// Jour UTC `AAMMJJ` d'un instant Unix (jours civils depuis 1970).
fn jour_utc(ts: i64) -> String {
    let z = ts.div_euclid(86_400) + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let j = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let a = yoe + era * 400 + i64::from(m <= 2);
    format!("{:02}{m:02}{j:02}", a.rem_euclid(100))
}

pub struct Store {
    c: Mutex<Connection>,
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> anyhow::Result<Store> {
        let c = Connection::open(path)?;
        c.busy_timeout(std::time::Duration::from_secs(5))?;
        c.execute_batch(
            "PRAGMA journal_mode=WAL;
             CREATE TABLE IF NOT EXISTS signalements(
               id INTEGER PRIMARY KEY AUTOINCREMENT,
               reference TEXT NOT NULL UNIQUE,
               claim TEXT NOT NULL, kind TEXT NOT NULL, target TEXT,
               what TEXT NOT NULL, basis TEXT NOT NULL, url TEXT,
               ref_who TEXT, ref_when TEXT, ref_what TEXT, ref_in TEXT,
               fix TEXT NOT NULL, recu_a INTEGER NOT NULL,
               etat TEXT NOT NULL DEFAULT 'nouveau', note TEXT NOT NULL DEFAULT ''
             );
             CREATE TABLE IF NOT EXISTS idempotence(
               cle TEXT PRIMARY KEY, reference TEXT NOT NULL, recu_a INTEGER NOT NULL
             );",
        )?;
        Ok(Store { c: Mutex::new(c) })
    }

    fn conn(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.c.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Reçu d'une clé déjà déposée dans l'heure, sans rien écrire.
    pub fn rejeu(&self, idem: &str, maintenant: i64) -> anyhow::Result<Option<Recu>> {
        Ok(self
            .conn()
            .query_row(
                "SELECT reference, recu_a FROM idempotence WHERE cle=?1 AND recu_a > ?2",
                params![idem, maintenant - FENETRE_IDEM],
                |r| Ok(Recu { reference: r.get(0)?, recu_a: r.get(1)?, nouveau: false }),
            )
            .optional()?)
    }

    pub fn deposer(
        &self,
        s: &Signalement,
        idem: &str,
        maintenant: i64,
        plafond_jour: u32,
    ) -> Result<Recu, Depot> {
        let mut c = self.conn();
        let tx = c.transaction()?;
        tx.execute("DELETE FROM idempotence WHERE recu_a <= ?1", [maintenant - FENETRE_IDEM])?;
        let deja: Option<(String, i64)> = tx
            .query_row("SELECT reference, recu_a FROM idempotence WHERE cle=?1", [idem], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .optional()?;
        if let Some((reference, recu_a)) = deja {
            return Ok(Recu { reference, recu_a, nouveau: false });
        }
        let jour = jour_utc(maintenant);
        let motif = format!("S-{jour}-%");
        let rang: i64 = tx.query_row(
            "SELECT COUNT(*) FROM signalements WHERE reference LIKE ?1",
            [&motif],
            |r| r.get(0),
        )?;
        if rang >= i64::from(plafond_jour) {
            return Err(Depot::Plafond);
        }
        let reference = format!("S-{jour}-{:03}", rang + 1);
        let (url, who, quand, quoi, dans) = match &s.source {
            Source::Lien(u) => (Some(u), None, None, None, None),
            Source::Ref { who, when, what } => (None, Some(who), Some(when), Some(what), None),
            Source::Carte(d) => (None, None, None, None, Some(d)),
        };
        tx.execute(
            "INSERT INTO signalements(reference, claim, kind, target, what, basis, url, ref_who, \
             ref_when, ref_what, ref_in, fix, recu_a) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13)",
            params![
                reference, s.claim, s.kind, s.target, s.what, s.basis, url, who, quand, quoi, dans,
                s.fix, maintenant
            ],
        )?;
        tx.execute(
            "INSERT INTO idempotence(cle, reference, recu_a) VALUES(?1,?2,?3)",
            params![idem, reference, maintenant],
        )?;
        tx.commit()?;
        Ok(Recu { reference, recu_a: maintenant, nouveau: true })
    }

    pub fn lister(&self, filtre: Filtre, limite: u32) -> Vec<Ligne> {
        let c = self.conn();
        let etat = filtre.etat.map(|e| e.texte());
        let sql = format!(
            "SELECT {COLONNES} FROM signalements WHERE (?1 IS NULL OR etat=?1) ORDER BY id DESC LIMIT ?2"
        );
        let Ok(mut st) = c.prepare(&sql) else { return vec![] };
        st.query_map(params![etat, limite], ligne)
            .map(|rows| rows.filter_map(Result::ok).collect())
            .unwrap_or_default()
    }

    pub fn lire(&self, id: i64) -> Option<Ligne> {
        let c = self.conn();
        c.query_row(&format!("SELECT {COLONNES} FROM signalements WHERE id=?1"), [id], ligne)
            .optional()
            .ok()
            .flatten()
    }

    /// Faux si le signalement n'existe pas.
    pub fn etat(&self, id: i64, etat: Etat, note: &str) -> bool {
        let c = self.conn();
        c.execute(
            "UPDATE signalements SET etat=?1, note=?2 WHERE id=?3",
            params![etat.texte(), note, id],
        )
        .is_ok_and(|n| n == 1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sig() -> Signalement {
        Signalement {
            claim: "a1".into(),
            kind: "verdict".into(),
            target: None,
            what: "x".repeat(30),
            basis: "lien".into(),
            source: Source::Lien("https://example.com/p".into()),
            fix: "correction proposée".into(),
        }
    }

    fn base() -> Store {
        Store::open(":memory:").unwrap()
    }

    const J: i64 = 1_795_000_000; // 2026-11-18 UTC

    #[test]
    fn jour_utc_connu() {
        assert_eq!(jour_utc(0), "700101");
        assert_eq!(jour_utc(951_782_400), "000229");
        assert_eq!(jour_utc(J), "261118");
    }

    #[test]
    fn reference_format_et_rang() {
        let b = base();
        assert_eq!(b.deposer(&sig(), "k1", J, 10).unwrap().reference, "S-261118-001");
        assert_eq!(b.deposer(&sig(), "k2", J + 5, 10).unwrap().reference, "S-261118-002");
        let r = b.deposer(&sig(), "k3", J + 86_400, 10).unwrap();
        assert_eq!(r.reference, "S-261119-001");
        assert!(r.nouveau);
    }

    #[test]
    fn idempotence_meme_reference() {
        let b = base();
        let a = b.deposer(&sig(), "k", J, 10).unwrap();
        let r = b.deposer(&sig(), "k", J + 3000, 10).unwrap();
        assert_eq!((r.reference.as_str(), r.nouveau, r.recu_a), (a.reference.as_str(), false, J));
        assert_eq!(b.lister(Filtre::default(), 10).len(), 1);
        // au-delà d'une heure, la clé est oubliée
        assert!(b.deposer(&sig(), "k", J + 3700, 10).unwrap().nouveau);
    }

    #[test]
    fn rejeu_lecture_seule() {
        let b = base();
        assert_eq!(b.rejeu("k", J).unwrap(), None);
        let a = b.deposer(&sig(), "k", J, 10).unwrap();
        let r = b.rejeu("k", J + 3000).unwrap().unwrap();
        assert_eq!((r.reference, r.recu_a, r.nouveau), (a.reference, J, false));
        assert_eq!(b.rejeu("k", J + 3600).unwrap(), None, "fenêtre d'une heure");
        assert_eq!(b.lister(Filtre::default(), 10).len(), 1);
    }

    #[test]
    fn plafond_jour() {
        let b = base();
        b.deposer(&sig(), "a", J, 2).unwrap();
        b.deposer(&sig(), "b", J, 2).unwrap();
        assert!(matches!(b.deposer(&sig(), "c", J, 2), Err(Depot::Plafond)));
        // un renvoi déjà accepté reste honoré
        assert!(!b.deposer(&sig(), "a", J, 2).unwrap().nouveau);
    }

    #[test]
    fn schema_sans_donnee_identifiante() {
        let b = base();
        let c = b.conn();
        let mut st = c.prepare("PRAGMA table_info(signalements)").unwrap();
        let noms: Vec<String> =
            st.query_map([], |r| r.get(1)).unwrap().map(Result::unwrap).collect();
        for interdit in ["ip", "agent", "session", "cle"] {
            assert!(!noms.iter().any(|n| n.contains(interdit)), "{interdit}");
        }
    }

    #[test]
    fn etat_et_note() {
        let b = base();
        b.deposer(&sig(), "k", J, 10).unwrap();
        let id = b.lister(Filtre::default(), 1)[0].id;
        assert!(b.etat(id, Etat::Corrige, "fait"));
        let l = b.lire(id).unwrap();
        assert_eq!((l.etat.as_str(), l.note.as_str()), ("corrige", "fait"));
        assert!(!b.etat(999, Etat::Rejete, ""));
        assert!(b.lire(999).is_none());
    }

    #[test]
    fn lister_filtre_etat() {
        let b = base();
        b.deposer(&sig(), "a", J, 10).unwrap();
        b.deposer(&sig(), "b", J, 10).unwrap();
        let id = b.lister(Filtre::default(), 10)[0].id;
        b.etat(id, Etat::Rejete, "");
        let f = |e| b.lister(Filtre { etat: Some(e) }, 10).len();
        assert_eq!((f(Etat::Rejete), f(Etat::Nouveau), f(Etat::Doublon)), (1, 1, 0));
    }

    #[test]
    fn lister_borne() {
        let b = base();
        for i in 0..5 {
            b.deposer(&sig(), &format!("k{i}"), J, 10).unwrap();
        }
        assert_eq!(b.lister(Filtre::default(), 3).len(), 3);
    }
}
