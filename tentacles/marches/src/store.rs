//! Base locale : suivis, scénarios, instantanés, propositions, alertes,
//! dénouements. Horodatages en secondes unix.

use std::path::Path;
use std::sync::Mutex;

use anyhow::Result;
use chrono::{DateTime, Datelike, Duration, Utc};
use rusqlite::{Connection, OptionalExtension, params};

use crate::api::clean;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS followed (
    market TEXT PRIMARY KEY, outcome TEXT NOT NULL, title TEXT NOT NULL,
    seq INTEGER NOT NULL DEFAULT 0, end_at INTEGER
);
CREATE TABLE IF NOT EXISTS scenario_links (
    name TEXT NOT NULL, market TEXT NOT NULL, outcome TEXT NOT NULL, sign INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS snapshots (
    market TEXT NOT NULL, outcome TEXT NOT NULL, at INTEGER NOT NULL,
    prob REAL NOT NULL, volume REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS snapshots_idx ON snapshots(market, outcome, at);
CREATE TABLE IF NOT EXISTS proposals (
    n INTEGER PRIMARY KEY, market TEXT NOT NULL, outcome TEXT NOT NULL, title TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS alerts (
    key TEXT PRIMARY KEY, at INTEGER NOT NULL, budgeted INTEGER NOT NULL,
    text TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS resolutions (
    id INTEGER PRIMARY KEY, summary TEXT NOT NULL, at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS projections (
    target TEXT PRIMARY KEY, up TEXT NOT NULL, down TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
";

/// Durée de conservation des instantanés.
const KEEP_DAYS: i64 = 30;

#[derive(Debug, Clone, PartialEq)]
pub struct Followed {
    pub market: String,
    pub outcome: String,
    pub title: String,
    /// Échéance annoncée par la source, connue après le premier relevé.
    pub end: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Link {
    pub market: String,
    pub outcome: String,
    pub sign: i8,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Scenario {
    pub name: String,
    pub links: Vec<Link>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Proposal {
    pub n: u32,
    pub market: String,
    pub outcome: String,
    pub title: String,
}

pub struct Store {
    conn: Mutex<Connection>,
}

fn ts(t: DateTime<Utc>) -> i64 {
    t.timestamp()
}

fn from_ts(s: i64) -> Option<DateTime<Utc>> {
    DateTime::from_timestamp(s, 0)
}

/// Semaine ISO, en `année * 100 + numéro`.
fn week(t: DateTime<Utc>) -> i64 {
    let w = t.iso_week();
    i64::from(w.year()) * 100 + i64::from(w.week())
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> Result<Store> {
        Self::init(Connection::open(path)?)
    }

    pub fn memory() -> Store {
        Self::init(Connection::open_in_memory().expect("sqlite en mémoire")).expect("schéma")
    }

    fn init(conn: Connection) -> Result<Store> {
        conn.execute_batch(SCHEMA)?;
        Ok(Store { conn: Mutex::new(conn) })
    }

    fn db(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn follow(&self, market: &str, outcome: &str, title: &str) -> Result<()> {
        let c = self.db();
        c.execute(
            "INSERT INTO followed(market, outcome, title, seq)
             VALUES(?1, ?2, ?3, (SELECT COALESCE(MAX(seq), 0) + 1 FROM followed))
             ON CONFLICT(market) DO UPDATE SET outcome = ?2, title = ?3",
            params![market, outcome, clean(title)],
        )?;
        Ok(())
    }

    pub fn unfollow(&self, market: &str) -> Result<bool> {
        let n = self.db().execute("DELETE FROM followed WHERE market = ?1", [market])?;
        Ok(n > 0)
    }

    pub fn followed(&self) -> Result<Vec<Followed>> {
        let c = self.db();
        let mut st = c.prepare("SELECT market, outcome, title, end_at FROM followed ORDER BY seq")?;
        let rows = st.query_map([], |r| {
            Ok(Followed {
                market: r.get(0)?,
                outcome: r.get(1)?,
                title: r.get(2)?,
                end: r.get::<_, Option<i64>>(3)?.and_then(from_ts),
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    pub fn set_end(&self, market: &str, end: Option<DateTime<Utc>>) -> Result<()> {
        self.db().execute(
            "UPDATE followed SET end_at = ?2 WHERE market = ?1",
            params![market, end.map(ts)],
        )?;
        Ok(())
    }

    pub fn set_scenario(&self, name: &str, links: &[(String, String, i8)]) -> Result<()> {
        let mut c = self.db();
        let tx = c.transaction()?;
        tx.execute("DELETE FROM scenario_links WHERE name = ?1", [name])?;
        for (m, o, s) in links {
            tx.execute(
                "INSERT INTO scenario_links(name, market, outcome, sign) VALUES(?1, ?2, ?3, ?4)",
                params![name, m, o, s],
            )?;
        }
        tx.commit()?;
        Ok(())
    }

    /// Un scénario dont tous les liens ont été dénoués disparaît.
    pub fn scenarios(&self) -> Result<Vec<Scenario>> {
        let c = self.db();
        let mut st = c.prepare(
            "SELECT name, market, outcome, sign FROM scenario_links ORDER BY name, rowid",
        )?;
        let rows = st.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                Link { market: r.get(1)?, outcome: r.get(2)?, sign: r.get(3)? },
            ))
        })?;
        let mut out: Vec<Scenario> = Vec::new();
        for row in rows {
            let (name, link) = row?;
            match out.last_mut() {
                Some(s) if s.name == name => s.links.push(link),
                _ => out.push(Scenario { name, links: vec![link] }),
            }
        }
        Ok(out)
    }

    pub fn snapshot(
        &self,
        market: &str,
        outcome: &str,
        at: DateTime<Utc>,
        prob: f64,
        volume: f64,
    ) -> Result<()> {
        let c = self.db();
        c.execute(
            "INSERT INTO snapshots(market, outcome, at, prob, volume) VALUES(?1, ?2, ?3, ?4, ?5)",
            params![market, outcome, ts(at), prob, volume],
        )?;
        c.execute("DELETE FROM snapshots WHERE at < ?1", [ts(at - Duration::days(KEEP_DAYS))])?;
        Ok(())
    }

    /// Dernière probabilité relevée dans `[at - slack, at]` : au-delà, trop ancienne pour comparer.
    pub fn prob_at(&self, market: &str, outcome: &str, at: DateTime<Utc>, slack: Duration) -> Result<Option<f64>> {
        Ok(self
            .db()
            .query_row(
                "SELECT prob FROM snapshots WHERE market = ?1 AND outcome = ?2 AND at <= ?3 AND at >= ?4
                 ORDER BY at DESC LIMIT 1",
                params![market, outcome, ts(at), ts(at - slack)],
                |r| r.get(0),
            )
            .optional()?)
    }

    /// Dernier relevé : probabilité, volume, horodatage.
    pub fn latest(&self, market: &str, outcome: &str) -> Result<Option<(f64, f64, DateTime<Utc>)>> {
        let row: Option<(f64, f64, i64)> = self
            .db()
            .query_row(
                "SELECT prob, volume, at FROM snapshots WHERE market = ?1 AND outcome = ?2
                 ORDER BY at DESC LIMIT 1",
                params![market, outcome],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        Ok(row.and_then(|(p, v, at)| Some((p, v, from_ts(at)?))))
    }

    pub fn last_snapshot(&self) -> Result<Option<DateTime<Utc>>> {
        let t: Option<i64> =
            self.db().query_row("SELECT MAX(at) FROM snapshots", [], |r| r.get(0))?;
        Ok(t.and_then(from_ts))
    }

    /// Remplace entièrement la liste précédente.
    pub fn set_proposals(&self, list: &[Proposal]) -> Result<()> {
        let mut c = self.db();
        let tx = c.transaction()?;
        tx.execute("DELETE FROM proposals", [])?;
        for p in list {
            tx.execute(
                "INSERT INTO proposals(n, market, outcome, title) VALUES(?1, ?2, ?3, ?4)",
                params![p.n, p.market, p.outcome, clean(&p.title)],
            )?;
        }
        tx.commit()?;
        Ok(())
    }

    /// Liste de la semaine ISO de `now`, vide si elle n'a pas encore été calculée.
    pub fn proposals_of_week(&self, now: DateTime<Utc>) -> Result<Vec<Proposal>> {
        if !self.proposed_in_week(now)? {
            return Ok(vec![]);
        }
        let c = self.db();
        let mut st = c.prepare("SELECT n, market, outcome, title FROM proposals ORDER BY n")?;
        let rows = st.query_map([], |r| Ok(Proposal { n: r.get(0)?, market: r.get(1)?, outcome: r.get(2)?, title: r.get(3)? }))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    pub fn proposed_in_week(&self, now: DateTime<Utc>) -> Result<bool> {
        let w: Option<i64> = self
            .db()
            .query_row("SELECT value FROM meta WHERE key = 'proposals_week'", [], |r| r.get(0))
            .optional()?;
        Ok(w == Some(week(now)))
    }

    pub fn mark_proposed(&self, now: DateTime<Utc>) -> Result<()> {
        self.db().execute("INSERT OR REPLACE INTO meta(key, value) VALUES('proposals_week', ?1)", [week(now)])?;
        Ok(())
    }

    pub fn proposal(&self, n: u32) -> Result<Option<Proposal>> {
        Ok(self
            .db()
            .query_row(
                "SELECT n, market, outcome, title FROM proposals WHERE n = ?1",
                [n],
                |r| Ok(Proposal { n: r.get(0)?, market: r.get(1)?, outcome: r.get(2)?, title: r.get(3)? }),
            )
            .optional()?)
    }

    pub fn record_alert(&self, key: &str, at: DateTime<Utc>, budgeted: bool, text: &str) -> Result<()> {
        self.db().execute(
            "INSERT OR REPLACE INTO alerts(key, at, budgeted, text) VALUES(?1, ?2, ?3, ?4)",
            params![key, ts(at), budgeted, text],
        )?;
        Ok(())
    }

    /// Textes des alertes enregistrées après `since`, émises ou non.
    // ponytail: résolution à la seconde ; une alerte enregistrée dans la seconde même du brief n'y figure pas.
    pub fn alerts_since(&self, since: DateTime<Utc>) -> Result<Vec<String>> {
        let c = self.db();
        let mut st = c.prepare("SELECT text FROM alerts WHERE at > ?1 ORDER BY at, rowid")?;
        let rows = st.query_map([ts(since)], |r| r.get(0))?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    pub fn alert_known(&self, key: &str) -> Result<bool> {
        Ok(self
            .db()
            .query_row("SELECT 1 FROM alerts WHERE key = ?1", [key], |_| Ok(()))
            .optional()?
            .is_some())
    }

    /// Une alerte de clé commençant par `prefix` enregistrée depuis `since`.
    pub fn alert_prefix_since(&self, prefix: &str, since: DateTime<Utc>) -> Result<bool> {
        // Comparaison de préfixe exacte : un nom de scénario peut contenir % ou _.
        Ok(self
            .db()
            .query_row(
                "SELECT 1 FROM alerts WHERE substr(key, 1, length(?1)) = ?1 AND at >= ?2",
                params![prefix, ts(since)],
                |_| Ok(()),
            )
            .optional()?
            .is_some())
    }

    pub fn budgeted_since(&self, since: DateTime<Utc>) -> Result<u32> {
        Ok(self.db().query_row(
            "SELECT COUNT(*) FROM alerts WHERE budgeted = 1 AND at >= ?1",
            [ts(since)],
            |r| r.get(0),
        )?)
    }

    /// Retire la question du suivi, des scénarios et des projections ; garde le dénouement.
    pub fn resolve(&self, market: &str, summary: &str, at: DateTime<Utc>) -> Result<()> {
        let mut c = self.db();
        let tx = c.transaction()?;
        tx.execute("DELETE FROM followed WHERE market = ?1", [market])?;
        tx.execute("DELETE FROM scenario_links WHERE market = ?1", [market])?;
        tx.execute("DELETE FROM projections WHERE target = ?1", [market])?;
        tx.execute(
            "INSERT INTO resolutions(summary, at) VALUES(?1, ?2)",
            params![clean(summary), ts(at)],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// Les dénouements en attente, rendus une seule fois.
    pub fn take_resolutions(&self) -> Result<Vec<String>> {
        let mut c = self.db();
        let tx = c.transaction()?;
        let out = {
            let mut st = tx.prepare("SELECT summary FROM resolutions ORDER BY id")?;
            st.query_map([], |r| r.get(0))?.collect::<Result<Vec<String>, _>>()?
        };
        tx.execute("DELETE FROM resolutions", [])?;
        tx.commit()?;
        Ok(out)
    }

    pub fn mark_briefed(&self, at: DateTime<Utc>) -> Result<()> {
        self.db().execute(
            "INSERT OR REPLACE INTO meta(key, value) VALUES('last_brief', ?1)",
            [ts(at)],
        )?;
        Ok(())
    }

    pub fn last_brief(&self) -> Result<Option<DateTime<Utc>>> {
        let t: Option<i64> = self
            .db()
            .query_row("SELECT value FROM meta WHERE key = 'last_brief'", [], |r| r.get(0))
            .optional()?;
        Ok(t.and_then(from_ts))
    }

    pub fn set_projection(&self, target: &str, up: &str, down: &str) -> Result<()> {
        self.db().execute(
            "INSERT OR REPLACE INTO projections(target, up, down) VALUES(?1, ?2, ?3)",
            params![target, clean(up), clean(down)],
        )?;
        Ok(())
    }

    pub fn projection(&self, target: &str) -> Result<Option<(String, String)>> {
        Ok(self
            .db()
            .query_row("SELECT up, down FROM projections WHERE target = ?1", [target], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .optional()?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Duration, Utc};

    #[test]
    fn projection_roundtrip_replace_and_resolve() {
        let s = Store::memory();
        s.follow("m1", "Oui", "Q").unwrap();
        s.set_projection("m1", "haut\n", "bas").unwrap();
        assert_eq!(s.projection("m1").unwrap(), Some(("haut ".into(), "bas".into())));
        s.set_projection("m1", "a", "b").unwrap();
        assert_eq!(s.projection("m1").unwrap(), Some(("a".into(), "b".into())));
        s.resolve("m1", "fini", Utc::now()).unwrap();
        assert_eq!(s.projection("m1").unwrap(), None);
    }

    #[test]
    fn follow_unfollow() {
        let s = Store::memory();
        s.follow("m1", "Oui", "Q").unwrap();
        assert_eq!(s.followed().unwrap().len(), 1);
        assert!(s.unfollow("m1").unwrap());
        assert!(!s.unfollow("m1").unwrap());
        assert!(s.followed().unwrap().is_empty());
    }

    #[test]
    fn prob_at_latest_before() {
        let s = Store::memory();
        let t = Utc::now();
        s.snapshot("m", "Oui", t - Duration::hours(3), 0.2, 1.0).unwrap();
        s.snapshot("m", "Oui", t - Duration::hours(1), 0.4, 1.0).unwrap();
        let slack = Duration::hours(6);
        assert_eq!(s.prob_at("m", "Oui", t - Duration::hours(2), slack).unwrap(), Some(0.2));
        assert_eq!(s.prob_at("m", "Oui", t, slack).unwrap(), Some(0.4));
        assert_eq!(s.prob_at("m", "Oui", t - Duration::hours(4), slack).unwrap(), None);
        // Seul relevé antérieur hors de la marge : aucune valeur.
        assert_eq!(s.prob_at("m", "Oui", t - Duration::hours(2), Duration::minutes(30)).unwrap(), None);
        assert!(s.last_snapshot().unwrap().is_some());
    }

    #[test]
    fn scenario_replaced_and_old_snapshots_purged() {
        let s = Store::memory();
        s.set_scenario("sc", &[("a".into(), "Oui".into(), 1)]).unwrap();
        s.set_scenario("sc", &[("b".into(), "Non".into(), -1)]).unwrap();
        let v = s.scenarios().unwrap();
        assert_eq!(v.len(), 1);
        assert_eq!(v[0].links, vec![Link { market: "b".into(), outcome: "Non".into(), sign: -1 }]);
        let t = Utc::now();
        s.snapshot("m", "Oui", t - Duration::days(31), 0.1, 1.0).unwrap();
        s.snapshot("m", "Oui", t, 0.5, 1.0).unwrap();
        assert_eq!(s.prob_at("m", "Oui", t - Duration::days(30), Duration::days(2)).unwrap(), None);
    }

    #[test]
    fn alerts_budget_and_resolutions() {
        let s = Store::memory();
        let t = Utc::now();
        s.record_alert("k1", t, true, "").unwrap();
        s.record_alert("k2", t - Duration::days(10), true, "").unwrap();
        s.record_alert("k3", t, false, "").unwrap();
        assert!(s.alert_known("k1").unwrap() && !s.alert_known("zz").unwrap());
        assert!(s.alert_prefix_since("k", t - Duration::hours(1)).unwrap());
        assert!(!s.alert_prefix_since("k2", t - Duration::days(1)).unwrap());
        assert!(!s.alert_prefix_since("k%", t - Duration::days(30)).unwrap());
        assert_eq!(s.budgeted_since(t - Duration::days(7)).unwrap(), 1);
        s.follow("m", "Oui", "Q").unwrap();
        s.set_scenario("sc", &[("m".into(), "Oui".into(), 1)]).unwrap();
        s.resolve("m", "résolu", t).unwrap();
        assert!(s.followed().unwrap().is_empty());
        assert!(s.scenarios().unwrap().is_empty());
        assert_eq!(s.take_resolutions().unwrap(), vec!["résolu".to_string()]);
        assert!(s.take_resolutions().unwrap().is_empty());
    }

    #[test]
    fn proposals_and_brief() {
        let s = Store::memory();
        let p = |n| Proposal { n, market: format!("m{n}"), outcome: "Oui".into(), title: "T".into() };
        s.set_proposals(&[p(1), p(2)]).unwrap();
        s.set_proposals(&[p(1)]).unwrap();
        assert_eq!(s.proposal(1).unwrap(), Some(p(1)));
        assert_eq!(s.proposal(2).unwrap(), None);
        assert_eq!(s.last_brief().unwrap(), None);
        let t = Utc::now();
        s.mark_briefed(t).unwrap();
        assert_eq!(s.last_brief().unwrap().unwrap().timestamp(), t.timestamp());
    }

    #[test]
    fn latest_end_and_alert_texts() {
        let s = Store::memory();
        let t = Utc::now();
        assert_eq!(s.latest("m", "Oui").unwrap(), None);
        s.snapshot("m", "Oui", t - Duration::hours(2), 0.2, 5.0).unwrap();
        s.snapshot("m", "Oui", t - Duration::hours(1), 0.4, 7.0).unwrap();
        let (p, v, at) = s.latest("m", "Oui").unwrap().unwrap();
        assert_eq!((p, v, at.timestamp()), (0.4, 7.0, (t - Duration::hours(1)).timestamp()));
        s.follow("m", "Oui", "Q").unwrap();
        assert_eq!(s.followed().unwrap()[0].end, None);
        s.set_end("m", Some(t)).unwrap();
        assert_eq!(s.followed().unwrap()[0].end.unwrap().timestamp(), t.timestamp());
        s.record_alert("old", t - Duration::hours(3), true, "ancienne").unwrap();
        s.record_alert("new", t, false, "récente").unwrap();
        assert_eq!(s.alerts_since(t - Duration::hours(1)).unwrap(), vec!["récente".to_string()]);
    }
}
