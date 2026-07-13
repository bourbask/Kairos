use rusqlite::{params, Connection, Result as SqlResult};
use chrono::{NaiveDate, NaiveDateTime, Utc};
use crate::models::{JobOffer, Task, Routine, RoutineLogEntry, CalendarEvent};
use crate::calendar::USER_TZ;

pub struct Storage {
    conn: Connection,
}

/// Désérialise une ligne `calendar_events`. Un horodatage illisible → ligne **ignorée**
/// (warn), jamais remplacée par `now()` (ce qui fabriquerait un faux événement « maintenant »).
fn row_to_event(row: &rusqlite::Row) -> SqlResult<Option<CalendarEvent>> {
    let st: String = row.get(4)?;
    let et: String = row.get(5)?;
    let (Ok(start_time), Ok(end_time)) = (
        NaiveDateTime::parse_from_str(&st, "%Y-%m-%dT%H:%M:%S"),
        NaiveDateTime::parse_from_str(&et, "%Y-%m-%dT%H:%M:%S"),
    ) else {
        tracing::warn!("calendar_events: horodatage illisible (start={st:?}, end={et:?}), événement ignoré");
        return Ok(None);
    };
    Ok(Some(CalendarEvent {
        uid: row.get(0)?,
        summary: row.get(1)?,
        description: row.get(2)?,
        location: row.get(3)?,
        start_time,
        end_time,
        all_day: row.get::<_, i32>(6)? != 0,
        source: row.get(7)?,
        etag: row.get(8)?,
    }))
}

impl Storage {
    pub fn open(path: &str) -> SqlResult<Self> {
        let conn = Connection::open(path)?;
        // WAL : lecteurs concurrents + moins de « database is locked » ; busy_timeout en complément.
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        let _: String = conn.query_row("PRAGMA journal_mode=WAL", [], |row| row.get(0))?;
        let store = Self { conn };
        store.initialize()?;
        Ok(store)
    }

    fn initialize(&self) -> SqlResult<()> {
        self.conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS job_offers (
                id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                title TEXT NOT NULL,
                company TEXT NOT NULL,
                description TEXT NOT NULL,
                url TEXT NOT NULL,
                location TEXT,
                country TEXT,
                salary_min INTEGER,
                salary_max INTEGER,
                currency TEXT,
                remote INTEGER,
                published_at TEXT,
                collected_at TEXT NOT NULL,
                score REAL,
                presented INTEGER DEFAULT 0,
                presented_at TEXT
            );

            CREATE TABLE IF NOT EXISTS briefings (
                date TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                generated_at TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_offers_presented ON job_offers(presented);
            CREATE INDEX IF NOT EXISTS idx_offers_score ON job_offers(score DESC);

            CREATE TABLE IF NOT EXISTS tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                description TEXT,
                due_date TEXT,
                priority TEXT NOT NULL DEFAULT 'medium',
                completed INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                completed_at TEXT
            );

            CREATE TABLE IF NOT EXISTS routines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                step_name TEXT NOT NULL,
                step_order INTEGER NOT NULL,
                estimated_minutes INTEGER,
                enabled INTEGER NOT NULL DEFAULT 1
            );

            CREATE TABLE IF NOT EXISTS routine_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                routine_step_id INTEGER NOT NULL,
                completed INTEGER NOT NULL DEFAULT 0,
                completed_at TEXT,
                FOREIGN KEY (routine_step_id) REFERENCES routines(id)
            );

            CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(due_date);
            CREATE INDEX IF NOT EXISTS idx_routine_log_date ON routine_log(date);

            CREATE TABLE IF NOT EXISTS calendar_events (
                uid TEXT PRIMARY KEY,
                summary TEXT,
                description TEXT,
                location TEXT,
                start_time TEXT NOT NULL,
                end_time TEXT NOT NULL,
                all_day INTEGER DEFAULT 0,
                source TEXT DEFAULT 'calendar',
                etag TEXT,
                last_synced TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_calendar_start ON calendar_events(start_time);
            "
        )?;
        Ok(())
    }

    pub fn insert_offer(&self, offer: &JobOffer) -> SqlResult<()> {
        self.conn.execute(
            "INSERT OR IGNORE INTO job_offers
             (id, source, title, company, description, url, location, country,
              salary_min, salary_max, currency, remote, published_at, collected_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)",
            params![
                offer.id,
                offer.source,
                offer.title,
                offer.company,
                offer.description,
                offer.url,
                offer.location,
                offer.country,
                offer.salary_min,
                offer.salary_max,
                offer.currency,
                offer.remote.map(|r| r as i32),
                offer.published_at.map(|d| d.to_rfc3339()),
                offer.collected_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn insert_offers(&self, offers: &[JobOffer]) -> SqlResult<usize> {
        let mut count = 0;
        for offer in offers {
            match self.insert_offer(offer) {
                Ok(()) => count += 1,
                Err(e) => tracing::warn!("Failed to insert {}: {}", offer.id, e),
            }
        }
        Ok(count)
    }

    pub fn update_score(&self, id: &str, score: f64) -> SqlResult<()> {
        self.conn.execute(
            "UPDATE job_offers SET score = ?1 WHERE id = ?2",
            params![score, id],
        )?;
        Ok(())
    }

    pub fn mark_presented(&self, id: &str) -> SqlResult<()> {
        self.conn.execute(
            "UPDATE job_offers SET presented = 1, presented_at = ?1 WHERE id = ?2",
            params![Utc::now().to_rfc3339(), id],
        )?;
        Ok(())
    }

    pub fn get_unpresented_scored(&self, limit: u32, min_score: f64) -> SqlResult<Vec<(JobOffer, f64)>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, source, title, company, description, url, location, country,
                    salary_min, salary_max, currency, remote, published_at, collected_at, score
             FROM job_offers
             WHERE presented = 0 AND score IS NOT NULL AND score >= ?2
             ORDER BY score DESC
             LIMIT ?1"
        )?;

        let rows = stmt.query_map(params![limit, min_score], |row| {
            Ok((
                JobOffer {
                    id: row.get(0)?,
                    source: row.get(1)?,
                    title: row.get(2)?,
                    company: row.get(3)?,
                    description: row.get(4)?,
                    url: row.get(5)?,
                    location: row.get(6)?,
                    country: row.get(7)?,
                    salary_min: row.get(8)?,
                    salary_max: row.get(9)?,
                    currency: row.get(10)?,
                    remote: row.get::<_, Option<i32>>(11)?.map(|r| r != 0),
                    published_at: row.get::<_, Option<String>>(12)?
                        .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    collected_at: Utc::now(),
                },
                row.get::<_, f64>(14)?,
            ))
        })?;

        rows.collect()
    }

    pub fn save_briefing(&self, date: NaiveDate, content: &str) -> SqlResult<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO briefings (date, content, generated_at) VALUES (?1, ?2, ?3)",
            params![date.to_string(), content, Utc::now().to_rfc3339()],
        )?;
        Ok(())
    }

    pub fn get_stats(&self) -> SqlResult<StorageStats> {
        let total: u32 = self.conn.query_row(
            "SELECT COUNT(*) FROM job_offers", [], |row| row.get(0)
        )?;
        let presented: u32 = self.conn.query_row(
            "SELECT COUNT(*) FROM job_offers WHERE presented = 1", [], |row| row.get(0)
        )?;
        let unscored: u32 = self.conn.query_row(
            "SELECT COUNT(*) FROM job_offers WHERE score IS NULL", [], |row| row.get(0)
        )?;

        Ok(StorageStats { total, presented, unscored })
    }

    // --- Planning: Tasks ---

    pub fn insert_task(&self, title: &str, description: Option<&str>, due_date: Option<NaiveDate>, priority: &str) -> SqlResult<i64> {
        self.conn.execute(
            "INSERT INTO tasks (title, description, due_date, priority, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                title,
                description,
                due_date.map(|d| d.to_string()),
                priority,
                Utc::now().date_naive().to_string(),
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn get_tasks(&self, due_today: bool, all: bool) -> SqlResult<Vec<Task>> {
        let sql = if due_today {
            "SELECT id, title, description, due_date, priority, completed, created_at, completed_at
             FROM tasks
             WHERE due_date = ?1 AND completed = 0
             ORDER BY
               CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
               due_date ASC"
        } else if all {
            "SELECT id, title, description, due_date, priority, completed, created_at, completed_at
             FROM tasks
             ORDER BY completed ASC,
               CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
               due_date ASC"
        } else {
            "SELECT id, title, description, due_date, priority, completed, created_at, completed_at
             FROM tasks
             WHERE completed = 0
             ORDER BY
               CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
               due_date ASC"
        };

        let today = Utc::now().date_naive().to_string();
        let mut stmt = self.conn.prepare(sql)?;
        let rows = if due_today {
            stmt.query_map(params![today], Self::map_task_row)?
        } else {
            stmt.query_map([], Self::map_task_row)?
        };
        rows.collect()
    }

    fn map_task_row(row: &rusqlite::Row) -> SqlResult<Task> {
        let created_str: String = row.get(6)?;
        Ok(Task {
            id: row.get(0)?,
            title: row.get(1)?,
            description: row.get(2)?,
            due_date: row.get::<_, Option<String>>(3)?.and_then(|s| NaiveDate::parse_from_str(&s, "%Y-%m-%d").ok()),
            priority: row.get(4)?,
            completed: row.get::<_, i32>(5)? != 0,
            created_at: NaiveDate::parse_from_str(&created_str, "%Y-%m-%d")
                .map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?,
            completed_at: row.get::<_, Option<String>>(7)?.and_then(|s| NaiveDate::parse_from_str(&s, "%Y-%m-%d").ok()),
        })
    }

    pub fn complete_task(&self, id: i64) -> SqlResult<bool> {
        let updated = self.conn.execute(
            "UPDATE tasks SET completed = 1, completed_at = ?1 WHERE id = ?2",
            params![Utc::now().date_naive().to_string(), id],
        )?;
        Ok(updated > 0)
    }

    pub fn delete_task(&self, id: i64) -> SqlResult<bool> {
        let deleted = self.conn.execute("DELETE FROM tasks WHERE id = ?1", params![id])?;
        Ok(deleted > 0)
    }

    pub fn update_task(&self, id: i64, title: Option<&str>, description: Option<Option<&str>>, due_date: Option<Option<NaiveDate>>, priority: Option<&str>) -> SqlResult<bool> {
        let existing = self.get_task_by_id(id)?;
        let task = match existing {
            Some(t) => t,
            None => return Ok(false),
        };

        let new_title = title.unwrap_or(&task.title).to_string();
        let new_desc = match description {
            Some(Some(d)) => Some(d.to_string()),
            Some(None) => None,
            None => task.description.clone(),
        };
        let new_due = match due_date {
            Some(Some(d)) => Some(d.to_string()),
            Some(None) => None,
            None => task.due_date.map(|d| d.to_string()),
        };
        let new_priority = priority.unwrap_or(&task.priority).to_string();

        self.conn.execute(
            "UPDATE tasks SET title = ?1, description = ?2, due_date = ?3, priority = ?4 WHERE id = ?5",
            params![new_title, new_desc, new_due, new_priority, id],
        )?;
        Ok(true)
    }

    pub fn get_task_by_id(&self, id: i64) -> SqlResult<Option<Task>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, description, due_date, priority, completed, created_at, completed_at
             FROM tasks WHERE id = ?1"
        )?;
        let mut rows = stmt.query_map(params![id], Self::map_task_row)?;
        match rows.next() {
            Some(Ok(task)) => Ok(Some(task)),
            _ => Ok(None),
        }
    }

    pub fn get_tasks_for_week(&self, week_start: NaiveDate, week_end: NaiveDate) -> SqlResult<Vec<Task>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, description, due_date, priority, completed, created_at, completed_at
             FROM tasks
             WHERE due_date >= ?1 AND due_date <= ?2 AND completed = 0
             ORDER BY due_date ASC,
               CASE priority WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END"
        )?;
        let rows = stmt.query_map(
            params![week_start.to_string(), week_end.to_string()],
            Self::map_task_row,
        )?;
        rows.collect()
    }

    // --- Planning: Routines ---

    pub fn insert_routine_step(&self, name: &str, order: i32, minutes: Option<i32>) -> SqlResult<i64> {
        self.conn.execute(
            "INSERT INTO routines (step_name, step_order, estimated_minutes) VALUES (?1, ?2, ?3)",
            params![name, order, minutes],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn get_routine_steps(&self) -> SqlResult<Vec<Routine>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, step_name, step_order, estimated_minutes, enabled FROM routines WHERE enabled = 1 ORDER BY step_order"
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Routine {
                id: row.get(0)?,
                step_name: row.get(1)?,
                step_order: row.get(2)?,
                estimated_minutes: row.get(3)?,
                enabled: row.get::<_, i32>(4)? != 0,
            })
        })?;
        rows.collect()
    }

    pub fn seed_default_routines(&self) -> SqlResult<usize> {
        let count: i64 = self.conn.query_row("SELECT COUNT(*) FROM routines", [], |row| row.get(0))?;
        if count > 0 {
            return Ok(0);
        }
        let defaults = [
            (1, "Réveil + verre d'eau", 5),
            (2, "Lecture briefing Kairos", 5),
            (3, "Méditation / Pleine conscience", 10),
            (4, "Douche", 10),
            (5, "Petit-déjeuner", 20),
            (6, "Revue des objectifs du jour", 5),
            (7, "Première tâche prioritaire", 30),
        ];
        let mut seeded = 0;
        for (order, name, minutes) in &defaults {
            self.conn.execute(
                "INSERT INTO routines (step_name, step_order, estimated_minutes) VALUES (?1, ?2, ?3)",
                params![name, order, minutes],
            )?;
            seeded += 1;
        }
        Ok(seeded)
    }

    pub fn delete_routine_step(&self, id: i64) -> SqlResult<bool> {
        self.conn.execute("DELETE FROM routine_log WHERE routine_step_id = ?1", params![id])?;
        let deleted = self.conn.execute("DELETE FROM routines WHERE id = ?1", params![id])?;
        Ok(deleted > 0)
    }

    // --- Planning: Routine Log ---

    pub fn get_routine_log_for_today(&self) -> SqlResult<Vec<RoutineLogEntry>> {
        let today = Utc::now().date_naive().to_string();
        let mut stmt = self.conn.prepare(
            "SELECT id, date, routine_step_id, completed, completed_at
             FROM routine_log
             WHERE date = ?1
             ORDER BY routine_step_id"
        )?;
        let rows = stmt.query_map(params![today], |row| {
            Ok(RoutineLogEntry {
                id: row.get(0)?,
                date: row.get::<_, String>(1).and_then(|s| NaiveDate::parse_from_str(&s, "%Y-%m-%d").map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e))))?,
                routine_step_id: row.get(2)?,
                completed: row.get::<_, i32>(3)? != 0,
                completed_at: row.get::<_, Option<String>>(4)?.and_then(|s| NaiveDate::parse_from_str(&s, "%Y-%m-%d").ok()),
            })
        })?;
        rows.collect()
    }

    pub fn ensure_routine_log_for_today(&self, routine_step_id: i64) -> SqlResult<RoutineLogEntry> {
        let today = Utc::now().date_naive();
        let existing = self.get_routine_log_for_today()?;
        if let Some(entry) = existing.into_iter().find(|e| e.routine_step_id == routine_step_id) {
            return Ok(entry);
        }
        self.conn.execute(
            "INSERT INTO routine_log (date, routine_step_id) VALUES (?1, ?2)",
            params![today.to_string(), routine_step_id],
        )?;
        Ok(RoutineLogEntry {
            id: self.conn.last_insert_rowid(),
            date: today,
            routine_step_id,
            completed: false,
            completed_at: None,
        })
    }

    pub fn check_routine_step(&self, id: i64) -> SqlResult<bool> {
        let updated = self.conn.execute(
            "UPDATE routine_log SET completed = 1, completed_at = ?1 WHERE id = ?2 AND completed = 0",
            params![Utc::now().date_naive().to_string(), id],
        )?;
        Ok(updated > 0)
    }
    // --- Calendar ---

    pub fn upsert_calendar_event(&self, event: &CalendarEvent) -> SqlResult<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO calendar_events (uid, summary, description, location, start_time, end_time, all_day, source, etag, last_synced)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
            params![
                event.uid,
                event.summary,
                event.description,
                event.location,
                event.start_time.format("%Y-%m-%dT%H:%M:%S").to_string(),
                event.end_time.format("%Y-%m-%dT%H:%M:%S").to_string(),
                event.all_day as i32,
                event.source,
                event.etag,
                Utc::now().to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    pub fn get_today_events(&self) -> SqlResult<Vec<CalendarEvent>> {
        // Bornes du jour dans le fuseau local (les horodatages stockés sont en heure locale naïve).
        let today = Utc::now().with_timezone(&USER_TZ).date_naive();
        let start = today.format("%Y-%m-%dT00:00:00").to_string();
        let end = today.format("%Y-%m-%dT23:59:59").to_string();
        let mut stmt = self.conn.prepare(
            "SELECT uid, summary, description, location, start_time, end_time, all_day, source, etag
             FROM calendar_events
             WHERE start_time <= ?2 AND end_time >= ?1
             ORDER BY start_time"
        )?;
        let rows = stmt.query_map(params![start, end], row_to_event)?;
        rows.collect::<SqlResult<Vec<_>>>().map(|v| v.into_iter().flatten().collect())
    }

    pub fn get_upcoming_events(&self, limit: u32) -> SqlResult<Vec<CalendarEvent>> {
        let now = Utc::now().with_timezone(&USER_TZ).naive_local().format("%Y-%m-%dT%H:%M:%S").to_string();
        let mut stmt = self.conn.prepare(
            "SELECT uid, summary, description, location, start_time, end_time, all_day, source, etag
             FROM calendar_events
             WHERE end_time >= ?1
             ORDER BY start_time
             LIMIT ?2"
        )?;
        let rows = stmt.query_map(params![now, limit], row_to_event)?;
        rows.collect::<SqlResult<Vec<_>>>().map(|v| v.into_iter().flatten().collect())
    }

    pub fn delete_calendar_events_by_source(&self, source: &str) -> SqlResult<usize> {
        let deleted = self.conn.execute("DELETE FROM calendar_events WHERE source = ?1", params![source])?;
        Ok(deleted)
    }

    pub fn get_calendar_stats(&self) -> SqlResult<u32> {
        let count: u32 = self.conn.query_row(
            "SELECT COUNT(*) FROM calendar_events", [], |row| row.get(0)
        )?;
        Ok(count)
    }
}

pub struct StorageStats {
    pub total: u32,
    pub presented: u32,
    pub unscored: u32,
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Utc;

    #[test]
    fn test_insert_and_count() {
        let store = Storage::open(":memory:").unwrap();

        let offer = JobOffer {
            id: "test-1".into(),
            source: "test".into(),
            title: "Test Job".into(),
            company: "Test Corp".into(),
            description: "A test".into(),
            url: "https://example.com".into(),
            location: None,
            country: Some("CH".into()),
            salary_min: Some(50000),
            salary_max: Some(70000),
            currency: Some("CHF".into()),
            remote: Some(true),
            published_at: None,
            collected_at: Utc::now(),
        };

        store.insert_offer(&offer).unwrap();
        let stats = store.get_stats().unwrap();
        assert_eq!(stats.total, 1);
        assert_eq!(stats.presented, 0);
    }

    #[test]
    fn test_deduplication() {
        let store = Storage::open(":memory:").unwrap();

        let offer = JobOffer {
            id: "dup-1".into(),
            source: "test".into(),
            title: "Duplicate".into(),
            company: "Corp".into(),
            description: "desc".into(),
            url: "https://example.com".into(),
            location: None,
            country: None,
            salary_min: None,
            salary_max: None,
            currency: None,
            remote: None,
            published_at: None,
            collected_at: Utc::now(),
        };

        store.insert_offer(&offer).unwrap();
        store.insert_offer(&offer).unwrap();
        let stats = store.get_stats().unwrap();
        assert_eq!(stats.total, 1);
    }
}
