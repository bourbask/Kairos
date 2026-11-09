//! État persistant de la tête : surcharges de rythme, sourdines, file de
//! messages en attente, budget d'interruptions, dernières exécutions,
//! alertes déjà remises.
//! Horodatages en heure murale locale naïve.

use crate::config::{WorkMode, WorkRhythm};
use anyhow::{Result, bail};
use chrono::{NaiveDate, NaiveDateTime, TimeDelta, Weekday};
use rusqlite::{Connection, OptionalExtension, params};
use std::path::Path;

const TS: &str = "%Y-%m-%d %H:%M:%S";
const DAY: &str = "%Y-%m-%d";

/// Portée d'une sourdine : tout, ou seulement les briefs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QuietScope {
    All,
    Briefs,
}

impl QuietScope {
    fn as_str(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Briefs => "briefs",
        }
    }
    fn parse(s: &str) -> Result<Self> {
        match s {
            "all" => Ok(Self::All),
            "briefs" => Ok(Self::Briefs),
            _ => bail!("portée de sourdine inconnue : {s}"),
        }
    }
}

fn mode_str(m: WorkMode) -> &'static str {
    match m {
        WorkMode::OnSite => "on_site",
        WorkMode::Remote => "remote",
        WorkMode::Off => "off",
    }
}

fn parse_mode(s: &str) -> Option<WorkMode> {
    match s {
        "on_site" => Some(WorkMode::OnSite),
        "remote" => Some(WorkMode::Remote),
        "off" => Some(WorkMode::Off),
        _ => None,
    }
}

pub struct State {
    conn: Connection,
}

impl State {
    pub fn open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> Result<Self> {
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS work_override (
                 day INTEGER PRIMARY KEY, mode TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS quiet (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 from_ts TEXT NOT NULL, until_ts TEXT NOT NULL, scope TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS pending (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 kind TEXT NOT NULL, body TEXT NOT NULL, at TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS interruption (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS last_run (
                 job TEXT PRIMARY KEY, day TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS seen_alerts (
                 id TEXT PRIMARY KEY, at TEXT NOT NULL);",
        )?;
        Ok(Self { conn })
    }

    pub fn set_work_day(&self, day: Weekday, mode: WorkMode) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO work_override (day, mode) VALUES (?1, ?2)",
            params![day.num_days_from_monday(), mode_str(mode)],
        )?;
        Ok(())
    }

    /// Surcharge persistée si présente, sinon configuration (jour absent = repos).
    pub fn work_mode(&self, day: Weekday, cfg: &WorkRhythm) -> WorkMode {
        let over = self
            .conn
            .query_row(
                "SELECT mode FROM work_override WHERE day = ?1",
                params![day.num_days_from_monday()],
                |r| r.get::<_, String>(0),
            )
            .optional()
            .unwrap_or_else(|e| {
                tracing::warn!("lecture de la surcharge de rythme impossible : {e}");
                None
            })
            .and_then(|s| {
                let m = parse_mode(&s);
                if m.is_none() {
                    tracing::warn!("mode de surcharge illisible : {s}");
                }
                m
            });
        over.or_else(|| cfg.days.get(&day).copied())
            .unwrap_or(WorkMode::Off)
    }

    pub fn add_quiet(
        &self,
        from: NaiveDateTime,
        until: NaiveDateTime,
        scope: QuietScope,
    ) -> Result<()> {
        self.conn.execute(
            "INSERT INTO quiet (from_ts, until_ts, scope) VALUES (?1, ?2, ?3)",
            params![
                from.format(TS).to_string(),
                until.format(TS).to_string(),
                scope.as_str()
            ],
        )?;
        Ok(())
    }

    /// Sourdine active à `at` (`All` l'emporte) ; purge les échues relativement
    /// à `at`, qui doit donc être l'instant courant (les appelants passent « maintenant »).
    pub fn quiet_at(&self, at: NaiveDateTime) -> Result<Option<QuietScope>> {
        let at = at.format(TS).to_string();
        self.conn
            .execute("DELETE FROM quiet WHERE until_ts <= ?1", params![at])?;
        let mut stmt = self
            .conn
            .prepare("SELECT scope FROM quiet WHERE from_ts <= ?1 AND ?1 < until_ts")?;
        let scopes = stmt
            .query_map(params![at], |r| r.get::<_, String>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let mut found = None;
        for s in scopes {
            match QuietScope::parse(&s)? {
                QuietScope::All => return Ok(Some(QuietScope::All)),
                q => found = Some(q),
            }
        }
        Ok(found)
    }

    pub fn enqueue(&self, kind: &str, body: &str, at: NaiveDateTime) -> Result<()> {
        self.conn.execute(
            "INSERT INTO pending (kind, body, at) VALUES (?1, ?2, ?3)",
            params![kind, body, at.format(TS).to_string()],
        )?;
        Ok(())
    }

    pub fn has_pending(&self) -> Result<bool> {
        Ok(self.conn.query_row("SELECT EXISTS (SELECT 1 FROM pending)", [], |r| r.get(0))?)
    }

    /// Vide la file et rend (genre, corps) dans l'ordre d'arrivée.
    pub fn take_pending(&self) -> Result<Vec<(String, String)>> {
        let tx = self.conn.unchecked_transaction()?;
        let rows = {
            let mut stmt = tx.prepare("SELECT kind, body FROM pending ORDER BY id")?;
            stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        tx.execute("DELETE FROM pending", [])?;
        tx.commit()?;
        Ok(rows)
    }

    pub fn interruptions_on(&self, day: NaiveDate) -> Result<u32> {
        Ok(self.conn.query_row(
            "SELECT COUNT(*) FROM interruption WHERE substr(at, 1, 10) = ?1",
            params![day.format(DAY).to_string()],
            |r| r.get(0),
        )?)
    }

    pub fn record_interruption(&self, at: NaiveDateTime) -> Result<()> {
        self.conn.execute(
            "INSERT INTO interruption (at) VALUES (?1)",
            params![at.format(TS).to_string()],
        )?;
        Ok(())
    }

    pub fn last_run(&self, job: &str) -> Result<Option<NaiveDate>> {
        let s: Option<String> = self
            .conn
            .query_row(
                "SELECT day FROM last_run WHERE job = ?1",
                params![job],
                |r| r.get(0),
            )
            .optional()?;
        Ok(s.map(|s| NaiveDate::parse_from_str(&s, DAY)).transpose()?)
    }

    pub fn set_last_run(&self, job: &str, day: NaiveDate) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO last_run (job, day) VALUES (?1, ?2)",
            params![job, day.format(DAY).to_string()],
        )?;
        Ok(())
    }

    pub fn alert_seen(&self, id: &str) -> Result<bool> {
        Ok(self.conn.query_row(
            "SELECT EXISTS (SELECT 1 FROM seen_alerts WHERE id = ?1)",
            params![id],
            |r| r.get(0),
        )?)
    }

    /// Marque une alerte remise ; oublie celles de plus de 90 jours avant `at`.
    pub fn mark_alert(&self, id: &str, at: NaiveDateTime) -> Result<()> {
        let cutoff = (at - TimeDelta::days(90)).format(TS).to_string();
        self.conn.execute("DELETE FROM seen_alerts WHERE at < ?1", params![cutoff])?;
        self.conn.execute(
            "INSERT OR REPLACE INTO seen_alerts (id, at) VALUES (?1, ?2)",
            params![id, at.format(TS).to_string()],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{WorkMode, WorkRhythm};
    use chrono::{NaiveDate, NaiveDateTime, NaiveTime, Weekday};
    use std::collections::HashMap;

    fn dt(s: &str) -> NaiveDateTime {
        NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S").unwrap()
    }

    fn rhythm() -> WorkRhythm {
        WorkRhythm {
            start: NaiveTime::from_hms_opt(9, 0, 0).unwrap(),
            end: NaiveTime::from_hms_opt(18, 0, 0).unwrap(),
            days: HashMap::from([(Weekday::Mon, WorkMode::OnSite)]),
        }
    }

    #[test]
    fn surcharge_de_rythme_prioritaire_et_persistante() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("tete.db");
        let s = State::open(&path).unwrap();
        assert_eq!(s.work_mode(Weekday::Mon, &rhythm()), WorkMode::OnSite);
        assert_eq!(s.work_mode(Weekday::Tue, &rhythm()), WorkMode::Off);
        s.set_work_day(Weekday::Mon, WorkMode::Remote).unwrap();
        s.set_work_day(Weekday::Tue, WorkMode::Remote).unwrap();
        drop(s);
        let s = State::open(&path).unwrap();
        assert_eq!(s.work_mode(Weekday::Mon, &rhythm()), WorkMode::Remote);
        assert_eq!(s.work_mode(Weekday::Tue, &rhythm()), WorkMode::Remote);
    }

    #[test]
    fn sourdine_dans_hors_plage_et_priorite() {
        let s = State::open_in_memory().unwrap();
        s.add_quiet(
            dt("2026-11-03 08:00:00"),
            dt("2026-11-03 12:00:00"),
            QuietScope::Briefs,
        )
        .unwrap();
        assert_eq!(s.quiet_at(dt("2026-11-03 07:59:59")).unwrap(), None);
        assert_eq!(
            s.quiet_at(dt("2026-11-03 09:00:00")).unwrap(),
            Some(QuietScope::Briefs)
        );
        s.add_quiet(
            dt("2026-11-03 10:00:00"),
            dt("2026-11-03 11:00:00"),
            QuietScope::All,
        )
        .unwrap();
        assert_eq!(
            s.quiet_at(dt("2026-11-03 10:30:00")).unwrap(),
            Some(QuietScope::All)
        );
        // échue : purgée, plus rien
        assert_eq!(s.quiet_at(dt("2026-11-03 12:00:00")).unwrap(), None);
        assert_eq!(s.quiet_at(dt("2026-11-03 09:00:00")).unwrap(), None);
    }

    #[test]
    fn file_fifo_videe_a_la_prise() {
        let s = State::open_in_memory().unwrap();
        s.enqueue("a", "un", dt("2026-11-03 08:00:00")).unwrap();
        s.enqueue("b", "deux", dt("2026-11-03 08:00:00")).unwrap();
        assert_eq!(
            s.take_pending().unwrap(),
            vec![("a".into(), "un".into()), ("b".into(), "deux".into())]
        );
        assert!(s.take_pending().unwrap().is_empty());
    }

    #[test]
    fn budget_compte_par_jour_local() {
        let s = State::open_in_memory().unwrap();
        s.record_interruption(dt("2026-11-03 00:00:00")).unwrap();
        s.record_interruption(dt("2026-11-03 23:59:59")).unwrap();
        s.record_interruption(dt("2026-11-04 00:00:00")).unwrap();
        let d = |n| NaiveDate::from_ymd_opt(2026, 11, n).unwrap();
        assert_eq!(s.interruptions_on(d(3)).unwrap(), 2);
        assert_eq!(s.interruptions_on(d(4)).unwrap(), 1);
        assert_eq!(s.interruptions_on(d(5)).unwrap(), 0);
    }

    #[test]
    fn alertes_vues_purgees_apres_90_jours() {
        let s = State::open_in_memory().unwrap();
        assert!(!s.alert_seen("t:a").unwrap());
        s.mark_alert("t:a", dt("2026-08-01 10:00:00")).unwrap();
        assert!(s.alert_seen("t:a").unwrap());
        assert!(!s.alert_seen("t:b").unwrap());
        // Rejouée : sans erreur.
        s.mark_alert("t:a", dt("2026-08-01 10:00:00")).unwrap();
        s.mark_alert("t:b", dt("2026-11-01 10:00:00")).unwrap();
        assert!(!s.alert_seen("t:a").unwrap());
        assert!(s.alert_seen("t:b").unwrap());
    }

    #[test]
    fn derniere_execution_aller_retour() {
        let s = State::open_in_memory().unwrap();
        let d = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap();
        assert_eq!(s.last_run("brief").unwrap(), None);
        s.set_last_run("brief", d).unwrap();
        assert_eq!(s.last_run("brief").unwrap(), Some(d));
        let d2 = d.succ_opt().unwrap();
        s.set_last_run("brief", d2).unwrap();
        assert_eq!(s.last_run("brief").unwrap(), Some(d2));
    }
}
