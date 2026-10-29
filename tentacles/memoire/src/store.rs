//! Stockage de la mémoire personnelle : journal, faits, intentions,
//! conversations, leçons. Horodatages en heure murale locale naïve.

use anyhow::{Result, bail};
use chrono::{NaiveDateTime, Utc};
use kairos::calendar::USER_TZ;
use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};

/// Clé réservée du fait portant la personnalité.
pub const PERSONA_KEY: &str = "persona";
/// Taille maximale d'une transcription acceptée.
pub const MAX_TRANSCRIPT_BYTES: usize = 1_500_000;

const TS: &str = "%Y-%m-%d %H:%M:%S";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum Sensitivity {
    Commune,
    Strategique,
}

impl Sensitivity {
    fn as_str(self) -> &'static str {
        match self {
            Self::Commune => "commune",
            Self::Strategique => "strategique",
        }
    }
    fn parse(s: &str) -> Result<Self> {
        match s {
            "commune" => Ok(Self::Commune),
            "strategique" => Ok(Self::Strategique),
            _ => bail!("sensibilité inconnue : {s}"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum IntentionState {
    Ouverte,
    Proposee,
    Acceptee,
    Refusee,
    Expiree,
}

impl IntentionState {
    fn as_str(self) -> &'static str {
        match self {
            Self::Ouverte => "ouverte",
            Self::Proposee => "proposee",
            Self::Acceptee => "acceptee",
            Self::Refusee => "refusee",
            Self::Expiree => "expiree",
        }
    }
    fn parse(s: &str) -> Result<Self> {
        Ok(match s {
            "ouverte" => Self::Ouverte,
            "proposee" => Self::Proposee,
            "acceptee" => Self::Acceptee,
            "refusee" => Self::Refusee,
            "expiree" => Self::Expiree,
            _ => bail!("état d'intention inconnu : {s}"),
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NewJournalEntry {
    pub source: String,
    pub kind: String,
    pub content: serde_json::Value,
    pub sensitivity: Sensitivity,
    pub provenance: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct JournalEntry {
    pub id: i64,
    pub at: NaiveDateTime,
    pub source: String,
    pub kind: String,
    pub content: serde_json::Value,
    pub sensitivity: Sensitivity,
    pub provenance: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct Fact {
    pub key: String,
    pub value: String,
    pub provenance: String,
    pub updated_at: NaiveDateTime,
}

#[derive(Debug, Clone, Serialize)]
pub struct Intention {
    pub id: i64,
    pub what: String,
    pub constraints: String,
    pub expires_at: NaiveDateTime,
    pub state: IntentionState,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NewConversation {
    pub channel: String,
    pub device: String,
    pub started_at: NaiveDateTime,
    pub ended_at: NaiveDateTime,
    pub transcript: String,
}

/// Fiche de conversation : tout sauf la transcription.
#[derive(Debug, Clone, Serialize)]
pub struct ConversationCard {
    pub id: i64,
    pub channel: String,
    pub device: String,
    pub started_at: NaiveDateTime,
    pub ended_at: NaiveDateTime,
    pub summary: Option<String>,
    pub topics: Vec<String>,
    pub decisions: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NewLesson {
    pub context: String,
    pub qualification: String,
    pub debrief: String,
    pub conclusion: String,
    pub actions: Vec<String>,
}

pub struct MemoryStore {
    conn: Connection,
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS journal (
    id INTEGER PRIMARY KEY, at TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL,
    content TEXT NOT NULL, sensitivity TEXT NOT NULL, provenance TEXT NOT NULL);
CREATE VIRTUAL TABLE IF NOT EXISTS journal_fts USING fts5(content);
CREATE TABLE IF NOT EXISTS fact_log (
    id INTEGER PRIMARY KEY, key TEXT NOT NULL, value TEXT NOT NULL,
    provenance TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS fact_log_key ON fact_log(key, id);
CREATE TABLE IF NOT EXISTS intentions (
    id INTEGER PRIMARY KEY, what TEXT NOT NULL, constraints TEXT NOT NULL,
    expires_at TEXT NOT NULL, state TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS conversations (
    id INTEGER PRIMARY KEY, channel TEXT NOT NULL, device TEXT NOT NULL,
    started_at TEXT NOT NULL, ended_at TEXT NOT NULL, transcript TEXT NOT NULL,
    summary TEXT, topics TEXT NOT NULL DEFAULT '[]', decisions TEXT NOT NULL DEFAULT '[]');
CREATE VIRTUAL TABLE IF NOT EXISTS conversations_fts USING fts5(text);
CREATE TABLE IF NOT EXISTS lessons (
    id INTEGER PRIMARY KEY, context TEXT NOT NULL, qualification TEXT NOT NULL,
    debrief TEXT NOT NULL, conclusion TEXT NOT NULL, actions TEXT NOT NULL);
";

fn now_local() -> NaiveDateTime {
    Utc::now().with_timezone(&USER_TZ).naive_local()
}

fn ts(t: NaiveDateTime) -> String {
    t.format(TS).to_string()
}

fn parse_ts(s: &str) -> Result<NaiveDateTime> {
    Ok(NaiveDateTime::parse_from_str(s, TS)?)
}

/// Rend la requête utilisateur littérale pour FTS5 : chaque terme entre
/// guillemets, guillemets internes doublés. `None` si aucun terme.
fn fts_literal(query: &str) -> Option<String> {
    let terms: Vec<String> = query
        .split_whitespace()
        .map(|t| format!("\"{}\"", t.replace('"', "\"\"")))
        .collect();
    (!terms.is_empty()).then(|| terms.join(" "))
}

fn card_from_row(r: &Row) -> rusqlite::Result<(ConversationCard, String, String, String, String)> {
    Ok((
        ConversationCard {
            id: r.get(0)?,
            channel: r.get(1)?,
            device: r.get(2)?,
            started_at: NaiveDateTime::default(),
            ended_at: NaiveDateTime::default(),
            summary: r.get(5)?,
            topics: vec![],
            decisions: vec![],
        },
        r.get(3)?,
        r.get(4)?,
        r.get(6)?,
        r.get(7)?,
    ))
}

fn finish_card(raw: (ConversationCard, String, String, String, String)) -> Result<ConversationCard> {
    let (mut c, s, e, t, d) = raw;
    c.started_at = parse_ts(&s)?;
    c.ended_at = parse_ts(&e)?;
    c.topics = serde_json::from_str(&t)?;
    c.decisions = serde_json::from_str(&d)?;
    Ok(c)
}

const CARD_COLS: &str = "c.id, c.channel, c.device, c.started_at, c.ended_at, c.summary, c.topics, c.decisions";

impl MemoryStore {
    pub fn open(path: &str) -> Result<MemoryStore> {
        let conn = Connection::open(path)?;
        conn.execute_batch(SCHEMA)?;
        Ok(MemoryStore { conn })
    }

    // --- Journal ---

    pub fn journal_write(&self, e: &NewJournalEntry) -> Result<i64> {
        let content = serde_json::to_string(&e.content)?;
        let tx = self.conn.unchecked_transaction()?;
        tx.execute(
            "INSERT INTO journal (at, source, kind, content, sensitivity, provenance) VALUES (?,?,?,?,?,?)",
            params![ts(now_local()), e.source, e.kind, content, e.sensitivity.as_str(), e.provenance],
        )?;
        let id = tx.last_insert_rowid();
        tx.execute("INSERT INTO journal_fts (rowid, content) VALUES (?,?)", params![id, content])?;
        tx.commit()?;
        Ok(id)
    }

    pub fn journal_search(&self, query: &str, limit: u32) -> Result<Vec<JournalEntry>> {
        let Some(q) = fts_literal(query) else { return Ok(vec![]) };
        let mut st = self.conn.prepare(
            "SELECT j.id, j.at, j.source, j.kind, j.content, j.sensitivity, j.provenance
             FROM journal_fts f JOIN journal j ON j.id = f.rowid
             WHERE journal_fts MATCH ? ORDER BY rank LIMIT ?",
        )?;
        let rows = st.query_map(params![q, limit], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get(2)?, r.get(3)?,
                r.get::<_, String>(4)?, r.get::<_, String>(5)?, r.get(6)?))
        })?;
        rows.map(|r| {
            let (id, at, source, kind, content, sens, provenance) = r?;
            Ok(JournalEntry {
                id,
                at: parse_ts(&at)?,
                source,
                kind,
                content: serde_json::from_str(&content)?,
                sensitivity: Sensitivity::parse(&sens)?,
                provenance,
            })
        })
        .collect()
    }

    // --- Faits (journal d'écritures : le dernier gagne, le reste est l'historique) ---

    pub fn fact_set(&self, key: &str, value: &str, provenance: &str) -> Result<()> {
        self.conn.execute(
            "INSERT INTO fact_log (key, value, provenance, updated_at) VALUES (?,?,?,?)",
            params![key, value, provenance, ts(now_local())],
        )?;
        Ok(())
    }

    fn fact_from(r: &Row) -> rusqlite::Result<(String, String, String, String)> {
        Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
    }

    fn fact_build(raw: (String, String, String, String)) -> Result<Fact> {
        Ok(Fact { key: raw.0, value: raw.1, provenance: raw.2, updated_at: parse_ts(&raw.3)? })
    }

    pub fn fact_get(&self, key: &str) -> Result<Option<Fact>> {
        self.conn
            .query_row(
                "SELECT key, value, provenance, updated_at FROM fact_log WHERE key = ? ORDER BY id DESC LIMIT 1",
                [key],
                Self::fact_from,
            )
            .optional()?
            .map(Self::fact_build)
            .transpose()
    }

    /// Toutes les valeurs successives d'une clé, de la plus ancienne à la plus récente.
    pub fn fact_history(&self, key: &str) -> Result<Vec<Fact>> {
        let mut st = self.conn.prepare(
            "SELECT key, value, provenance, updated_at FROM fact_log WHERE key = ? ORDER BY id",
        )?;
        st.query_map([key], Self::fact_from)?.map(|r| Self::fact_build(r?)).collect()
    }

    /// Valeur courante de chaque clé commençant par `prefix` (comparaison littérale).
    pub fn facts_list(&self, prefix: &str) -> Result<Vec<Fact>> {
        let mut st = self.conn.prepare(
            "SELECT key, value, provenance, updated_at FROM fact_log
             WHERE id IN (SELECT MAX(id) FROM fact_log GROUP BY key)
               AND substr(key, 1, length(?1)) = ?1 ORDER BY key",
        )?;
        st.query_map([prefix], Self::fact_from)?.map(|r| Self::fact_build(r?)).collect()
    }

    // --- Intentions ---

    pub fn intention_create(&self, what: &str, constraints: &str, expires_at: NaiveDateTime) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO intentions (what, constraints, expires_at, state) VALUES (?,?,?,?)",
            params![what, constraints, ts(expires_at), IntentionState::Ouverte.as_str()],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn intention_set_state(&self, id: i64, state: IntentionState) -> Result<bool> {
        let n = self
            .conn
            .execute("UPDATE intentions SET state = ? WHERE id = ?", params![state.as_str(), id])?;
        Ok(n > 0)
    }

    /// Expire d'abord les intentions échues, puis renvoie les ouvertes ou proposées.
    pub fn intentions_open(&self, now: NaiveDateTime) -> Result<Vec<Intention>> {
        let tx = self.conn.unchecked_transaction()?;
        tx.execute(
            "UPDATE intentions SET state = 'expiree' WHERE state IN ('ouverte','proposee') AND expires_at <= ?",
            [ts(now)],
        )?;
        let out = {
            let mut st = tx.prepare(
                "SELECT id, what, constraints, expires_at, state FROM intentions
                 WHERE state IN ('ouverte','proposee') ORDER BY expires_at, id",
            )?;
            st.query_map([], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get::<_, String>(3)?, r.get::<_, String>(4)?))
            })?
            .map(|r| {
                let (id, what, constraints, exp, state) = r?;
                Ok(Intention { id, what, constraints, expires_at: parse_ts(&exp)?, state: IntentionState::parse(&state)? })
            })
            .collect::<Result<Vec<_>>>()?
        };
        tx.commit()?;
        Ok(out)
    }

    // --- Conversations ---

    pub fn conversation_ingest(&self, c: &NewConversation) -> Result<i64> {
        if c.transcript.len() > MAX_TRANSCRIPT_BYTES {
            bail!("transcription trop volumineuse ({} octets)", c.transcript.len());
        }
        let tx = self.conn.unchecked_transaction()?;
        tx.execute(
            "INSERT INTO conversations (channel, device, started_at, ended_at, transcript) VALUES (?,?,?,?,?)",
            params![c.channel, c.device, ts(c.started_at), ts(c.ended_at), c.transcript],
        )?;
        let id = tx.last_insert_rowid();
        tx.execute("INSERT INTO conversations_fts (rowid, text) VALUES (?,?)", params![id, c.transcript])?;
        tx.commit()?;
        Ok(id)
    }

    pub fn conversation_search(&self, query: &str, limit: u32) -> Result<Vec<ConversationCard>> {
        let Some(q) = fts_literal(query) else { return Ok(vec![]) };
        let mut st = self.conn.prepare(&format!(
            "SELECT {CARD_COLS} FROM conversations_fts f JOIN conversations c ON c.id = f.rowid
             WHERE conversations_fts MATCH ? ORDER BY rank LIMIT ?"
        ))?;
        st.query_map(params![q, limit], card_from_row)?.map(|r| finish_card(r?)).collect()
    }

    pub fn conversations_unsummarized(&self, limit: u32) -> Result<Vec<ConversationCard>> {
        let mut st = self.conn.prepare(&format!(
            "SELECT {CARD_COLS} FROM conversations c WHERE c.summary IS NULL ORDER BY c.id LIMIT ?"
        ))?;
        st.query_map([limit], card_from_row)?.map(|r| finish_card(r?)).collect()
    }

    /// Enregistre la synthèse et la rend cherchable avec la transcription.
    pub fn conversation_set_summary(&self, id: i64, summary: &str, topics: &[String], decisions: &[String]) -> Result<bool> {
        let tx = self.conn.unchecked_transaction()?;
        let n = tx.execute(
            "UPDATE conversations SET summary = ?, topics = ?, decisions = ? WHERE id = ?",
            params![summary, serde_json::to_string(topics)?, serde_json::to_string(decisions)?, id],
        )?;
        if n > 0 {
            tx.execute(
                "UPDATE conversations_fts SET text = (SELECT summary || ' ' || transcript FROM conversations WHERE id = ?1) WHERE rowid = ?1",
                [id],
            )?;
        }
        tx.commit()?;
        Ok(n > 0)
    }

    // --- Leçons ---

    pub fn lesson_record(&self, l: &NewLesson) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO lessons (context, qualification, debrief, conclusion, actions) VALUES (?,?,?,?,?)",
            params![l.context, l.qualification, l.debrief, l.conclusion, serde_json::to_string(&l.actions)?],
        )?;
        Ok(self.conn.last_insert_rowid())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{Duration, NaiveDate};

    fn store() -> MemoryStore {
        MemoryStore::open(":memory:").unwrap()
    }

    fn t() -> NaiveDateTime {
        NaiveDate::from_ymd_opt(2026, 10, 29).unwrap().and_hms_opt(12, 0, 0).unwrap()
    }

    fn conv(text: &str) -> NewConversation {
        NewConversation { channel: "voix".into(), device: "d".into(), started_at: t(), ended_at: t(), transcript: text.into() }
    }

    #[test]
    fn journal_roundtrip_and_search() {
        let s = store();
        let id = s
            .journal_write(&NewJournalEntry {
                source: "s".into(),
                kind: "note".into(),
                content: serde_json::json!({"texte": "appelé grand-mère"}),
                sensitivity: Sensitivity::Commune,
                provenance: "p".into(),
            })
            .unwrap();
        let r = s.journal_search("grand-mère", 10).unwrap();
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].id, id);
        assert_eq!(r[0].content["texte"], "appelé grand-mère");
        assert!(s.journal_search("   ", 10).unwrap().is_empty());
    }

    #[test]
    fn search_with_fts_operators_is_literal() {
        let s = store();
        assert!(s.journal_search("\"AND* -x", 10).is_ok());
        assert!(s.journal_search("NEAR(", 10).is_ok());
        assert!(s.conversation_search("\"AND* -x", 10).is_ok());
        s.conversation_ingest(&conv("a AND b")).unwrap();
        // « AND » est un mot, pas un opérateur : « c AND » ne matche pas « a AND b ».
        assert!(s.conversation_search("c AND", 10).unwrap().is_empty());
        assert_eq!(s.conversation_search("a AND", 10).unwrap().len(), 1);
    }

    #[test]
    fn fact_set_keeps_history() {
        let s = store();
        s.fact_set("k", "a", "p").unwrap();
        s.fact_set("k", "b", "p").unwrap();
        s.fact_set("kk", "z", "p").unwrap();
        assert_eq!(s.fact_get("k").unwrap().unwrap().value, "b");
        let h: Vec<_> = s.fact_history("k").unwrap().into_iter().map(|f| f.value).collect();
        assert_eq!(h, ["a", "b"]);
        let l = s.facts_list("k").unwrap();
        assert_eq!(l.len(), 2);
        assert!(s.fact_get(PERSONA_KEY).unwrap().is_none());
    }

    #[test]
    fn intentions_expire_on_read() {
        let s = store();
        let id = s.intention_create("x", "", t()).unwrap();
        assert_eq!(s.intentions_open(t() - Duration::minutes(1)).unwrap().len(), 1);
        assert_eq!(s.intentions_open(t()).unwrap().len(), 0);
        let st: String = s.conn.query_row("SELECT state FROM intentions WHERE id=?", [id], |r| r.get(0)).unwrap();
        assert_eq!(st, "expiree");
    }

    #[test]
    fn refused_intention_is_not_open() {
        let s = store();
        let id = s.intention_create("x", "", t()).unwrap();
        assert!(s.intention_set_state(id, IntentionState::Refusee).unwrap());
        assert!(!s.intention_set_state(999, IntentionState::Refusee).unwrap());
        assert!(s.intentions_open(t() - Duration::hours(1)).unwrap().is_empty());
    }

    #[test]
    fn conversation_ingest_search_and_summary() {
        let s = store();
        let id = s.conversation_ingest(&conv("parlé de la rando")).unwrap();
        assert_eq!(s.conversations_unsummarized(10).unwrap().len(), 1);
        assert_eq!(s.conversation_search("rando", 10).unwrap().len(), 1);
        assert!(s.conversation_set_summary(id, "balade", &["a".into()], &["b".into()]).unwrap());
        assert!(s.conversations_unsummarized(10).unwrap().is_empty());
        let r = s.conversation_search("balade", 10).unwrap();
        assert_eq!(r[0].topics, ["a"]);
        assert_eq!(s.conversation_search("rando", 10).unwrap().len(), 1);
    }

    #[test]
    fn oversized_transcript_is_rejected_without_write() {
        let s = store();
        assert!(s.conversation_ingest(&conv(&"x".repeat(MAX_TRANSCRIPT_BYTES + 1))).is_err());
        assert!(s.conversations_unsummarized(10).unwrap().is_empty());
    }

    #[test]
    fn lesson_roundtrip() {
        let s = store();
        let id = s
            .lesson_record(&NewLesson { context: "c".into(), qualification: "q".into(), debrief: "d".into(), conclusion: "k".into(), actions: vec!["a".into()] })
            .unwrap();
        assert!(id > 0);
    }
}
