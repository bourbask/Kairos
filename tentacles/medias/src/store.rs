//! Demandes suivies jusqu'à leur aboutissement (SQLite local au tentacule).

use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, params};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    /// Demandée, rien de regardable encore.
    Pending,
    /// Fichier présent, sans VF ni sous-titres français.
    NoSubs,
    /// Sortie depuis plus que le délai, toujours pas regardable.
    Late,
    Done,
    Failed,
    /// Suivi arrêté (trop ancien).
    Dropped,
}

impl State {
    pub fn as_str(self) -> &'static str {
        match self {
            State::Pending => "pending",
            State::NoSubs => "nosubs",
            State::Late => "late",
            State::Done => "done",
            State::Failed => "failed",
            State::Dropped => "dropped",
        }
    }
    pub fn parse(s: &str) -> State {
        match s {
            "nosubs" => State::NoSubs,
            "late" => State::Late,
            "done" => State::Done,
            "failed" => State::Failed,
            "dropped" => State::Dropped,
            _ => State::Pending,
        }
    }
    pub fn open(self) -> bool {
        matches!(self, State::Pending | State::NoSubs | State::Late)
    }
    pub fn fr(self) -> &'static str {
        match self {
            State::Pending => "en attente",
            State::NoSubs => "présent, sans sous-titres FR",
            State::Late => "en retard",
            State::Done => "prêt",
            State::Failed => "échec",
            State::Dropped => "suivi arrêté",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Request {
    pub id: i64,
    /// « tv » ou « movie ».
    pub media_type: String,
    pub tmdb_id: i64,
    pub tvdb_id: Option<i64>,
    pub title: String,
    /// Saisons demandées (séries) ; vide = toutes.
    pub seasons: Vec<i64>,
    pub seerr_id: Option<i64>,
    pub state: State,
    pub detail: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub late_notified: bool,
}

pub struct Store {
    db: Mutex<Connection>,
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS requests (
    id INTEGER PRIMARY KEY,
    media_type TEXT NOT NULL,
    tmdb_id INTEGER NOT NULL,
    tvdb_id INTEGER,
    title TEXT NOT NULL,
    seasons TEXT NOT NULL DEFAULT '[]',
    seerr_id INTEGER,
    state TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    late_notified INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL);
";

fn row(r: &rusqlite::Row) -> rusqlite::Result<Request> {
    let seasons: String = r.get(5)?;
    let state: String = r.get(7)?;
    Ok(Request {
        id: r.get(0)?,
        media_type: r.get(1)?,
        tmdb_id: r.get(2)?,
        tvdb_id: r.get(3)?,
        title: r.get(4)?,
        seasons: serde_json::from_str(&seasons).unwrap_or_default(),
        seerr_id: r.get(6)?,
        state: State::parse(&state),
        detail: r.get(8)?,
        created_at: r.get(9)?,
        updated_at: r.get(10)?,
        late_notified: r.get::<_, i64>(11)? != 0,
    })
}

const COLS: &str =
    "id, media_type, tmdb_id, tvdb_id, title, seasons, seerr_id, state, detail, created_at, updated_at, late_notified";

impl Store {
    pub fn open(path: &str) -> anyhow::Result<Store> {
        let c = Connection::open(path)?;
        c.execute_batch(SCHEMA)?;
        Ok(Store { db: Mutex::new(c) })
    }

    pub fn memory() -> Store {
        let c = Connection::open_in_memory().expect("base en mémoire");
        c.execute_batch(SCHEMA).expect("schéma");
        Store { db: Mutex::new(c) }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn add(
        &self,
        media_type: &str,
        tmdb_id: i64,
        tvdb_id: Option<i64>,
        title: &str,
        seasons: &[i64],
        seerr_id: Option<i64>,
        now: i64,
    ) -> rusqlite::Result<i64> {
        let db = self.db.lock().unwrap();
        db.execute(
            "INSERT INTO requests (media_type, tmdb_id, tvdb_id, title, seasons, seerr_id, state, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'pending', ?7, ?7)",
            params![media_type, tmdb_id, tvdb_id, title, serde_json::to_string(seasons).unwrap(), seerr_id, now],
        )?;
        Ok(db.last_insert_rowid())
    }

    pub fn list(&self, only_open: bool) -> rusqlite::Result<Vec<Request>> {
        let db = self.db.lock().unwrap();
        let sql = format!("SELECT {COLS} FROM requests ORDER BY id DESC LIMIT 200");
        let mut st = db.prepare(&sql)?;
        let all = st.query_map([], row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(all.into_iter().filter(|r| !only_open || r.state.open()).collect())
    }

    /// Demande encore ouverte pour cette œuvre.
    pub fn open_for(&self, media_type: &str, tmdb_id: i64) -> rusqlite::Result<Option<Request>> {
        Ok(self.list(true)?.into_iter().find(|r| r.media_type == media_type && r.tmdb_id == tmdb_id))
    }

    pub fn update(&self, id: i64, state: State, detail: &str, late_notified: bool, now: i64) -> rusqlite::Result<()> {
        self.db.lock().unwrap().execute(
            "UPDATE requests SET state = ?2, detail = ?3, late_notified = ?4, updated_at = ?5 WHERE id = ?1",
            params![id, state.as_str(), detail, late_notified as i64, now],
        )?;
        Ok(())
    }

    pub fn set_tvdb(&self, id: i64, tvdb: i64) -> rusqlite::Result<()> {
        self.db.lock().unwrap().execute("UPDATE requests SET tvdb_id = ?2 WHERE id = ?1", params![id, tvdb])?;
        Ok(())
    }

    pub fn get_kv(&self, k: &str) -> rusqlite::Result<Option<String>> {
        self.db.lock().unwrap().query_row("SELECT v FROM kv WHERE k = ?1", [k], |r| r.get(0)).optional()
    }

    /// Clés commençant par `prefix`.
    pub fn kv_keys(&self, prefix: &str) -> rusqlite::Result<Vec<String>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare("SELECT k FROM kv WHERE substr(k, 1, length(?1)) = ?1")?;
        st.query_map([prefix], |r| r.get(0))?.collect()
    }

    pub fn del_kv(&self, k: &str) -> rusqlite::Result<()> {
        self.db.lock().unwrap().execute("DELETE FROM kv WHERE k = ?1", [k])?;
        Ok(())
    }

    pub fn set_kv(&self, k: &str, v: &str) -> rusqlite::Result<()> {
        self.db.lock().unwrap().execute(
            "INSERT INTO kv (k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            params![k, v],
        )?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_roundtrip_and_states() {
        let s = Store::memory();
        let id = s.add("tv", 10, None, "Série", &[1, 2], Some(5), 100).unwrap();
        let r = s.open_for("tv", 10).unwrap().unwrap();
        assert_eq!((r.id, r.seasons.clone(), r.state, r.seerr_id), (id, vec![1, 2], State::Pending, Some(5)));
        s.set_tvdb(id, 77).unwrap();
        s.update(id, State::Late, "x", true, 200).unwrap();
        let r = &s.list(true).unwrap()[0];
        assert_eq!((r.state, r.tvdb_id, r.late_notified, r.updated_at), (State::Late, Some(77), true, 200));
        s.update(id, State::Done, "ok", true, 300).unwrap();
        assert!(s.list(true).unwrap().is_empty());
        assert_eq!(s.list(false).unwrap().len(), 1);
        assert!(s.open_for("tv", 10).unwrap().is_none());
    }

    #[test]
    fn kv_upserts() {
        let s = Store::memory();
        assert_eq!(s.get_kv("a").unwrap(), None);
        s.set_kv("a", "1").unwrap();
        s.set_kv("a", "2").unwrap();
        assert_eq!(s.get_kv("a").unwrap().as_deref(), Some("2"));
        s.set_kv("cal:x", "1").unwrap();
        s.set_kv("cal:y", "1").unwrap();
        s.set_kv("calz", "1").unwrap();
        let mut k = s.kv_keys("cal:").unwrap();
        k.sort();
        assert_eq!(k, ["cal:x", "cal:y"]);
        s.del_kv("cal:x").unwrap();
        assert_eq!(s.kv_keys("cal:").unwrap(), ["cal:y"]);
    }
}
