use rusqlite::{params, Connection, Result as SqlResult};
use chrono::{NaiveDate, Utc};
use crate::models::JobOffer;

pub struct Storage {
    conn: Connection,
}

impl Storage {
    pub fn open(path: &str) -> SqlResult<Self> {
        let conn = Connection::open(path)?;
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

    pub fn get_unpresented_scored(&self, limit: u32) -> SqlResult<Vec<(JobOffer, f64)>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, source, title, company, description, url, location, country,
                    salary_min, salary_max, currency, remote, published_at, collected_at, score
             FROM job_offers
             WHERE presented = 0 AND score IS NOT NULL
             ORDER BY score DESC
             LIMIT ?1"
        )?;

        let rows = stmt.query_map(params![limit], |row| {
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
