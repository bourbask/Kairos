//! Stockage de la mémoire personnelle : journal, faits, intentions,
//! conversations, leçons. Horodatages en heure murale locale naïve.

use anyhow::{Result, bail};
use chrono::{NaiveDateTime, Utc};
use kairos::calendar::USER_TZ;
use rusqlite::{Connection, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};

use crate::force::{Famille, est_actif, recuperabilite, renforcer, stabilite_initiale};

/// Clé réservée du fait portant la personnalité.
pub const PERSONA_KEY: &str = "persona";
/// Taille maximale d'une transcription acceptée.
pub const MAX_TRANSCRIPT_BYTES: usize = 1_500_000;
/// Transcription remise pour résumé : au plus `GET_MAX_CHARS` caractères,
/// début et fin conservés, milieu remplacé par `ELISION`.
pub const GET_MAX_CHARS: usize = 60_000;
pub const GET_HEAD_CHARS: usize = 15_000;
const ELISION: &str = "\n…\n";

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
    /// Clé de session : une nouvelle ingestion de la même session remplace la précédente.
    #[serde(default)]
    pub session_id: Option<String>,
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

/// Transcription d'une conversation, réduite pour tenir dans un résumé.
#[derive(Debug, Clone, Serialize)]
pub struct ConversationText {
    pub id: i64,
    pub channel: String,
    pub started_at: NaiveDateTime,
    pub transcript: String,
}

fn reduce(t: String) -> String {
    let n = t.chars().count();
    if n <= GET_MAX_CHARS {
        return t;
    }
    let tail_len = GET_MAX_CHARS - GET_HEAD_CHARS - ELISION.chars().count();
    let head: String = t.chars().take(GET_HEAD_CHARS).collect();
    let tail: String = t.chars().skip(n - tail_len).collect();
    format!("{head}{ELISION}{tail}")
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

/// Force d'un souvenir (fait ou entrée de journal).
#[derive(Debug, Clone, PartialEq)]
pub struct Force {
    pub stabilite: f64,
    pub dernier_rappel: NaiveDateTime,
    pub rappels: u32,
    pub importance: f64,
    pub ancre: bool,
    pub confiance: f64,
}

/// Importance par défaut d'une connaissance déclarée.
const IMPORTANCE_FAIT: f64 = 0.5;
/// Importance par défaut d'un épisode.
const IMPORTANCE_EPISODE: f64 = 0.0;

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
    summary TEXT, topics TEXT NOT NULL DEFAULT '[]', decisions TEXT NOT NULL DEFAULT '[]',
    session_id TEXT);
CREATE VIRTUAL TABLE IF NOT EXISTS conversations_fts USING fts5(text);
CREATE TABLE IF NOT EXISTS lessons (
    id INTEGER PRIMARY KEY, context TEXT NOT NULL, qualification TEXT NOT NULL,
    debrief TEXT NOT NULL, conclusion TEXT NOT NULL, actions TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS souvenirs (
    nature TEXT NOT NULL, ref TEXT NOT NULL, stabilite REAL NOT NULL,
    dernier_rappel TEXT NOT NULL, rappels INTEGER NOT NULL DEFAULT 0,
    importance REAL NOT NULL, ancre INTEGER NOT NULL DEFAULT 0, confiance REAL NOT NULL,
    PRIMARY KEY (nature, ref));
CREATE VIRTUAL TABLE IF NOT EXISTS facts_fts USING fts5(key, value);
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

/// Variante en OU pour le rappel : termes d'au moins 3 caractères, chacun
/// littéral. `None` si aucun terme cherchable.
fn fts_any(query: &str) -> Option<String> {
    let terms: Vec<String> = query
        .split_whitespace()
        .filter(|t| t.chars().count() >= 3)
        .map(|t| format!("\"{}\"", t.replace('"', "\"\"")))
        .collect();
    (!terms.is_empty()).then(|| terms.join(" OR "))
}

/// Souvenir proposé au modèle par le rappel.
#[derive(Debug, Clone, Serialize)]
pub struct Rappel {
    pub nature: String,
    pub r#ref: String,
    pub texte: String,
    pub score: f64,
    pub estompe: bool,
    pub ancre: bool,
}

/// Budget de texte des ancres, toujours fournies.
const BUDGET_ANCRES: usize = 3_200;
/// Longueur maximale du texte d'un souvenir rappelé.
const TEXTE_MAX: usize = 300;
/// Épisodes candidats sans recherche : les plus récents.
const RAPPEL_JOURNAL_RECENT: u32 = 50;
/// Résultats retenus par recherche plein texte.
const RAPPEL_RECHERCHE_MAX: u32 = 50;
/// Un souvenir estompé ne remonte que dans ces premiers rangs de recherche.
const RANG_ESTOMPE: usize = 3;

fn tronquer(t: &str) -> String {
    t.chars().take(TEXTE_MAX).collect()
}

/// Texte lisible d'une entrée de journal : champ texte s'il existe, sinon le JSON brut.
fn texte_journal(content: &str) -> String {
    serde_json::from_str::<serde_json::Value>(content)
        .ok()
        .and_then(|v| ["texte", "text"].iter().find_map(|k| v.get(k)?.as_str().map(str::to_owned)))
        .unwrap_or_else(|| content.to_owned())
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
        // Une suppression efface aussi le contenu sur le disque.
        conn.execute_batch("PRAGMA secure_delete = ON;")?;
        conn.execute_batch(SCHEMA)?;
        // Migration idempotente des bases créées avant la clé de session.
        let has: bool = conn
            .prepare("SELECT 1 FROM pragma_table_info('conversations') WHERE name = 'session_id'")?
            .exists([])?;
        if !has {
            conn.execute_batch("ALTER TABLE conversations ADD COLUMN session_id TEXT;")?;
        }
        conn.execute_batch(
            "CREATE UNIQUE INDEX IF NOT EXISTS conversations_session ON conversations(session_id) WHERE session_id IS NOT NULL;",
        )?;
        Self::migrate_souvenirs(&conn)?;
        Ok(MemoryStore { conn })
    }

    /// Donne une force à chaque fait et chaque entrée de journal qui n'en a pas,
    /// et indexe les valeurs courantes des faits. Idempotent ; ne touche ni
    /// `fact_log` ni `journal`. La personnalité reste hors souvenirs.
    fn migrate_souvenirs(conn: &Connection) -> Result<()> {
        let tx = conn.unchecked_transaction()?;
        tx.execute(
            "INSERT OR IGNORE INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance)
             SELECT 'fait', key, ?1, updated_at, ?2, 1.0 FROM fact_log
             WHERE id IN (SELECT MAX(id) FROM fact_log GROUP BY key) AND key <> ?3",
            params![stabilite_initiale(Famille::Connaissance, IMPORTANCE_FAIT), IMPORTANCE_FAIT, PERSONA_KEY],
        )?;
        tx.execute(
            "INSERT OR IGNORE INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance)
             SELECT 'journal', CAST(id AS TEXT), ?1, at, ?2, 1.0 FROM journal",
            params![stabilite_initiale(Famille::Episode, IMPORTANCE_EPISODE), IMPORTANCE_EPISODE],
        )?;
        let indexed: bool = tx.prepare("SELECT 1 FROM facts_fts LIMIT 1")?.exists([])?;
        if !indexed {
            tx.execute(
                "INSERT INTO facts_fts (key, value) SELECT key, value FROM fact_log
                 WHERE id IN (SELECT MAX(id) FROM fact_log GROUP BY key) AND key <> ?",
                [PERSONA_KEY],
            )?;
        }
        tx.commit()?;
        Ok(())
    }

    // --- Force des souvenirs ---

    pub fn force(&self, nature: &str, r#ref: &str) -> Result<Option<Force>> {
        self.conn
            .query_row(
                "SELECT stabilite, dernier_rappel, rappels, importance, ancre, confiance
                 FROM souvenirs WHERE nature = ? AND ref = ?",
                [nature, r#ref],
                |r| {
                    Ok((r.get::<_, f64>(0)?, r.get::<_, String>(1)?, r.get::<_, u32>(2)?,
                        r.get::<_, f64>(3)?, r.get::<_, bool>(4)?, r.get::<_, f64>(5)?))
                },
            )
            .optional()?
            .map(|(stabilite, dr, rappels, importance, ancre, confiance)| {
                Ok(Force { stabilite, dernier_rappel: parse_ts(&dr)?, rappels, importance, ancre, confiance })
            })
            .transpose()
    }

    /// Pose ou retire l'ancre ; `false` si le souvenir est inconnu.
    pub fn ancrer(&self, nature: &str, r#ref: &str, ancre: bool) -> Result<bool> {
        let n = self.conn.execute(
            "UPDATE souvenirs SET ancre = ? WHERE nature = ? AND ref = ?",
            params![ancre, nature, r#ref],
        )?;
        Ok(n > 0)
    }

    // --- Journal ---

    pub fn journal_write(&self, e: &NewJournalEntry) -> Result<i64> {
        let content = serde_json::to_string(&e.content)?;
        let at = ts(now_local());
        let tx = self.conn.unchecked_transaction()?;
        tx.execute(
            "INSERT INTO journal (at, source, kind, content, sensitivity, provenance) VALUES (?,?,?,?,?,?)",
            params![at, e.source, e.kind, content, e.sensitivity.as_str(), e.provenance],
        )?;
        let id = tx.last_insert_rowid();
        tx.execute("INSERT INTO journal_fts (rowid, content) VALUES (?,?)", params![id, content])?;
        tx.execute(
            "INSERT INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance)
             VALUES ('journal', ?, ?, ?, ?, 1.0)",
            params![id.to_string(), stabilite_initiale(Famille::Episode, IMPORTANCE_EPISODE), at, IMPORTANCE_EPISODE],
        )?;
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

    /// Les `limit` dernières entrées, la plus récente d'abord.
    pub fn journal_recent(&self, limit: u32) -> Result<Vec<JournalEntry>> {
        let mut st = self.conn.prepare(
            "SELECT id, at, source, kind, content, sensitivity, provenance FROM journal ORDER BY id DESC LIMIT ?",
        )?;
        let rows = st.query_map([limit], |r| {
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

    /// Supprime l'entrée et son index ; `false` si elle n'existe pas.
    pub fn journal_delete(&self, id: i64) -> Result<bool> {
        let tx = self.conn.unchecked_transaction()?;
        let n = tx.execute("DELETE FROM journal WHERE id = ?", [id])?;
        tx.execute("DELETE FROM journal_fts WHERE rowid = ?", [id])?;
        tx.execute("DELETE FROM souvenirs WHERE nature = 'journal' AND ref = ?", [id.to_string()])?;
        tx.execute("INSERT INTO journal_fts (journal_fts) VALUES ('optimize')", [])?;
        tx.commit()?;
        Ok(n > 0)
    }

    // --- Faits (journal d'écritures : le dernier gagne, le reste est l'historique) ---

    pub fn fact_set(&self, key: &str, value: &str, provenance: &str) -> Result<()> {
        self.fact_set_at(key, value, provenance, now_local())
    }

    /// Écrit la valeur ; une clé déjà connue est renforcée (rappel), une
    /// nouvelle devient une connaissance déclarée.
    fn fact_set_at(&self, key: &str, value: &str, provenance: &str, now: NaiveDateTime) -> Result<()> {
        let tx = self.conn.unchecked_transaction()?;
        tx.execute(
            "INSERT INTO fact_log (key, value, provenance, updated_at) VALUES (?,?,?,?)",
            params![key, value, provenance, ts(now)],
        )?;
        if key != PERSONA_KEY {
            let prev = tx
                .query_row(
                    "SELECT stabilite, dernier_rappel, ancre FROM souvenirs WHERE nature = 'fait' AND ref = ?",
                    [key],
                    |r| Ok((r.get::<_, f64>(0)?, r.get::<_, String>(1)?, r.get::<_, bool>(2)?)),
                )
                .optional()?;
            match prev {
                Some((stabilite, dr, ancre)) => {
                    let r = recuperabilite(parse_ts(&dr)?, stabilite, now, ancre);
                    tx.execute(
                        "UPDATE souvenirs SET stabilite = ?, dernier_rappel = ?, rappels = rappels + 1
                         WHERE nature = 'fait' AND ref = ?",
                        params![renforcer(stabilite, r), ts(now), key],
                    )?;
                }
                None => {
                    tx.execute(
                        "INSERT INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance)
                         VALUES ('fait', ?, ?, ?, ?, 1.0)",
                        params![key, stabilite_initiale(Famille::Connaissance, IMPORTANCE_FAIT), ts(now), IMPORTANCE_FAIT],
                    )?;
                }
            }
            tx.execute("DELETE FROM facts_fts WHERE key = ?", [key])?;
            tx.execute("INSERT INTO facts_fts (key, value) VALUES (?,?)", [key, value])?;
        }
        tx.commit()?;
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

    /// Oublie une clé : valeur courante et historique. Renvoie le nombre de valeurs effacées.
    pub fn fact_forget(&self, key: &str) -> Result<usize> {
        let tx = self.conn.unchecked_transaction()?;
        let n = tx.execute("DELETE FROM fact_log WHERE key = ?", [key])?;
        tx.execute("DELETE FROM souvenirs WHERE nature = 'fait' AND ref = ?", [key])?;
        tx.execute("DELETE FROM facts_fts WHERE key = ?", [key])?;
        tx.commit()?;
        Ok(n)
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
        let existing: Option<i64> = match &c.session_id {
            Some(sid) => tx
                .query_row("SELECT id FROM conversations WHERE session_id = ?", [sid], |r| r.get(0))
                .optional()?,
            None => None,
        };
        if let Some(id) = existing {
            // Même session : la dernière transcription fait foi, la synthèse sera refaite.
            tx.execute(
                "UPDATE conversations SET channel = ?, device = ?, started_at = MIN(started_at, ?), ended_at = ?,
                 transcript = ?, summary = NULL, topics = '[]', decisions = '[]' WHERE id = ?",
                params![c.channel, c.device, ts(c.started_at), ts(c.ended_at), c.transcript, id],
            )?;
            tx.execute("UPDATE conversations_fts SET text = ? WHERE rowid = ?", params![c.transcript, id])?;
            tx.commit()?;
            return Ok(id);
        }
        tx.execute(
            "INSERT INTO conversations (channel, device, started_at, ended_at, transcript, session_id) VALUES (?,?,?,?,?,?)",
            params![c.channel, c.device, ts(c.started_at), ts(c.ended_at), c.transcript, c.session_id],
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

    /// Les `limit` dernières conversations, la plus récente d'abord.
    pub fn conversations_recent(&self, limit: u32) -> Result<Vec<ConversationCard>> {
        let mut st = self.conn.prepare(&format!(
            "SELECT {CARD_COLS} FROM conversations c ORDER BY c.id DESC LIMIT ?"
        ))?;
        st.query_map([limit], card_from_row)?.map(|r| finish_card(r?)).collect()
    }

    pub fn conversations_unsummarized(&self, limit: u32) -> Result<Vec<ConversationCard>> {
        let mut st = self.conn.prepare(&format!(
            "SELECT {CARD_COLS} FROM conversations c WHERE c.summary IS NULL ORDER BY c.id LIMIT ?"
        ))?;
        st.query_map([limit], card_from_row)?.map(|r| finish_card(r?)).collect()
    }

    pub fn conversation_get(&self, id: i64) -> Result<Option<ConversationText>> {
        let row = self
            .conn
            .query_row(
                "SELECT channel, started_at, transcript FROM conversations WHERE id = ?",
                [id],
                |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?)),
            )
            .optional()?;
        row.map(|(channel, started, transcript)| {
            Ok(ConversationText { id, channel, started_at: parse_ts(&started)?, transcript: reduce(transcript) })
        })
        .transpose()
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

    /// Supprime la conversation et son index ; `false` si elle n'existe pas.
    pub fn conversation_delete(&self, id: i64) -> Result<bool> {
        let tx = self.conn.unchecked_transaction()?;
        let n = tx.execute("DELETE FROM conversations WHERE id = ?", [id])?;
        tx.execute("DELETE FROM conversations_fts WHERE rowid = ?", [id])?;
        tx.execute("INSERT INTO conversations_fts (conversations_fts) VALUES ('optimize')", [])?;
        tx.commit()?;
        Ok(n > 0)
    }

    /// Vide la transcription des conversations résumées terminées avant `before` ;
    /// résumé, sujets et décisions restent, l'index ne porte plus que le résumé.
    pub fn purge_transcripts(&self, before: NaiveDateTime) -> Result<usize> {
        let tx = self.conn.unchecked_transaction()?;
        tx.execute(
            "UPDATE conversations_fts SET text = (SELECT summary FROM conversations WHERE id = conversations_fts.rowid)
             WHERE rowid IN (SELECT id FROM conversations
                             WHERE summary IS NOT NULL AND summary != '' AND transcript != '' AND ended_at < ?1)",
            [ts(before)],
        )?;
        let n = tx.execute(
            "UPDATE conversations SET transcript = '' WHERE summary IS NOT NULL AND summary != '' AND transcript != '' AND ended_at < ?1",
            [ts(before)],
        )?;
        if n > 0 {
            tx.execute("INSERT INTO conversations_fts (conversations_fts) VALUES ('optimize')", [])?;
        }
        tx.commit()?;
        Ok(n)
    }

    // --- Leçons ---

    pub fn lesson_record(&self, l: &NewLesson) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO lessons (context, qualification, debrief, conclusion, actions) VALUES (?,?,?,?,?)",
            params![l.context, l.qualification, l.debrief, l.conclusion, serde_json::to_string(&l.actions)?],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    // --- Rappel (lecture seule : ne renforce rien) ---

    /// Souvenirs à proposer au modèle : ancres d'abord dans leur budget, puis
    /// les autres par `R + importance + pertinence` jusqu'à `limite`.
    pub fn rappel(&self, requete: Option<&str>, maintenant: NaiveDateTime, limite: usize) -> Result<Vec<Rappel>> {
        // Rang commun : les deux recherches fusionnées par score bm25 (plus bas = meilleur).
        let mut hits: Vec<(f64, &str, String)> = Vec::new();
        if let Some(q) = requete.and_then(fts_any) {
            let mut st = self.conn.prepare("SELECT key, rank FROM facts_fts WHERE facts_fts MATCH ? ORDER BY rank LIMIT ?")?;
            for r in st.query_map(params![q, RAPPEL_RECHERCHE_MAX], |r| Ok((r.get::<_, f64>(1)?, r.get::<_, String>(0)?)))? {
                let (rank, k) = r?;
                hits.push((rank, "fait", k));
            }
            let mut st = self.conn.prepare("SELECT rowid, rank FROM journal_fts WHERE journal_fts MATCH ? ORDER BY rank LIMIT ?")?;
            for r in st.query_map(params![q, RAPPEL_RECHERCHE_MAX], |r| Ok((r.get::<_, f64>(1)?, r.get::<_, i64>(0)?)))? {
                let (rank, id) = r?;
                hits.push((rank, "journal", id.to_string()));
            }
        }
        hits.sort_by(|a, b| a.0.total_cmp(&b.0));
        let rang: std::collections::HashMap<(&str, &str), usize> =
            hits.iter().enumerate().map(|(i, (_, n, r))| ((*n, r.as_str()), i)).collect();
        let journal_hits: Vec<String> = hits.iter().filter(|h| h.1 == "journal").map(|h| h.2.clone()).collect();

        // Candidats : tous les faits ; épisodes récents, ancrés ou trouvés.
        // ponytail: tous les faits sont lus à chaque rappel ; filtrer en SQL si leur nombre explose.
        let sql = format!(
            "SELECT 'fait', s.ref, s.ref || ' : ' || f.value, s.stabilite, s.dernier_rappel, s.importance, s.ancre
             FROM souvenirs s JOIN facts_fts f ON f.key = s.ref WHERE s.nature = 'fait'
             UNION ALL
             SELECT 'journal', s.ref, substr(j.at, 1, 10) || char(1) || j.content, s.stabilite, s.dernier_rappel, s.importance, s.ancre
             FROM souvenirs s JOIN journal j ON j.id = CAST(s.ref AS INTEGER) WHERE s.nature = 'journal'
               AND (s.ancre OR j.id IN (SELECT id FROM journal ORDER BY id DESC LIMIT {RAPPEL_JOURNAL_RECENT})
                    OR j.id IN ({}))",
            // Identifiants entiers issus de la base : pas de risque d'injection.
            journal_hits.join(",")
        );
        struct Cand { r: Rappel, importance: f64, dernier: NaiveDateTime, rang: Option<usize> }
        let mut st = self.conn.prepare(&sql)?;
        let rows = st.query_map([], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, f64>(3)?,
                r.get::<_, String>(4)?, r.get::<_, f64>(5)?, r.get::<_, bool>(6)?))
        })?;
        let mut ancres = Vec::new();
        let mut autres = Vec::new();
        for row in rows {
            let (nature, r#ref, brut, stabilite, dr, importance, ancre) = row?;
            let texte = match brut.split_once('\u{1}') {
                Some((date, content)) if nature == "journal" => format!("{date} : {}", texte_journal(content)),
                _ => brut,
            };
            let dernier = parse_ts(&dr)?;
            let rec = recuperabilite(dernier, stabilite, maintenant, ancre);
            let rang = rang.get(&(nature.as_str(), r#ref.as_str())).copied();
            let pertinence = rang.map_or(0.0, |i| 1.0 / (1.0 + i as f64));
            let r = Rappel {
                texte: tronquer(&texte),
                score: rec + importance + pertinence,
                estompe: !est_actif(rec),
                ancre,
                nature,
                r#ref,
            };
            let c = Cand { r, importance, dernier, rang };
            if ancre { ancres.push(c) } else { autres.push(c) }
        }

        ancres.sort_by(|a, b| b.importance.total_cmp(&a.importance).then(b.dernier.cmp(&a.dernier)));
        let mut out = Vec::new();
        let mut budget = 0;
        for c in ancres {
            let n = c.r.texte.chars().count();
            if budget + n <= BUDGET_ANCRES {
                budget += n;
                out.push(c.r);
            }
        }
        autres.retain(|c| !c.r.estompe || c.rang.is_some_and(|i| i < RANG_ESTOMPE));
        autres.sort_by(|a, b| b.r.score.total_cmp(&a.r.score));
        let place = limite.saturating_sub(out.len());
        out.extend(autres.into_iter().take(place).map(|c| c.r));
        Ok(out)
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
        NewConversation { channel: "voix".into(), device: "d".into(), started_at: t(), ended_at: t(), transcript: text.into(), session_id: None }
    }

    fn note(s: &MemoryStore, text: &str) -> i64 {
        s.journal_write(&NewJournalEntry {
            source: "s".into(),
            kind: "note".into(),
            content: serde_json::json!({ "texte": text }),
            sensitivity: Sensitivity::Commune,
            provenance: "p".into(),
        })
        .unwrap()
    }

    #[test]
    fn purge_transcripts_keeps_summaries() {
        let s = store();
        let old = s.conversation_ingest(&conv("vieux secret transcrit")).unwrap();
        s.conversation_set_summary(old, "bilan ancien", &["a".into()], &["d".into()]).unwrap();
        let pending = s.conversation_ingest(&conv("vieux pas encore résumé")).unwrap();
        // Résumé vide : la transcription n'est pas remplacée par rien.
        let blank = s.conversation_ingest(&conv("vieux résumé vide")).unwrap();
        s.conversation_set_summary(blank, "", &[], &[]).unwrap();
        let recent = s.conversation_ingest(&NewConversation { started_at: t() + Duration::days(60), ended_at: t() + Duration::days(60), ..conv("récent transcrit") }).unwrap();
        s.conversation_set_summary(recent, "bilan récent", &[], &[]).unwrap();

        assert_eq!(s.purge_transcripts(t() + Duration::days(30)).unwrap(), 1);
        assert_eq!(s.conversation_get(old).unwrap().unwrap().transcript, "");
        assert!(s.conversation_get(pending).unwrap().unwrap().transcript.contains("résumé"));
        assert!(s.conversation_get(blank).unwrap().unwrap().transcript.contains("vide"));
        assert!(s.conversation_get(recent).unwrap().unwrap().transcript.contains("récent"));
        let card = &s.conversation_search("ancien", 10).unwrap()[0];
        assert_eq!((card.id, card.summary.as_deref(), card.decisions.len()), (old, Some("bilan ancien"), 1));
        assert!(s.conversation_search("secret", 10).unwrap().is_empty());
        // Idempotent.
        assert_eq!(s.purge_transcripts(t() + Duration::days(30)).unwrap(), 0);
    }

    #[test]
    fn journal_delete_removes_entry_and_index() {
        let s = store();
        let a = note(&s, "rendez-vous médecin");
        let b = note(&s, "rendez-vous garage");
        assert!(s.journal_delete(a).unwrap());
        assert!(!s.journal_delete(a).unwrap());
        let r = s.journal_search("rendez-vous", 10).unwrap();
        assert_eq!(r.iter().map(|e| e.id).collect::<Vec<_>>(), [b]);
        assert!(s.journal_search("médecin", 10).unwrap().is_empty());
        assert_eq!(s.journal_recent(10).unwrap().len(), 1);
    }

    #[test]
    fn fact_forget_drops_value_and_history() {
        let s = store();
        s.fact_set("sante.poids", "80", "p").unwrap();
        s.fact_set("sante.poids", "78", "p").unwrap();
        s.fact_set("sante.taille", "180", "p").unwrap();
        assert_eq!(s.fact_forget("sante.poids").unwrap(), 2);
        assert!(s.fact_get("sante.poids").unwrap().is_none());
        assert!(s.fact_history("sante.poids").unwrap().is_empty());
        assert_eq!(s.facts_list("").unwrap().len(), 1);
    }

    #[test]
    fn conversation_delete_removes_row_and_index() {
        let s = store();
        let id = s.conversation_ingest(&conv("parlons du jardin")).unwrap();
        s.conversation_set_summary(id, "jardin", &[], &[]).unwrap();
        assert!(s.conversation_delete(id).unwrap());
        assert!(!s.conversation_delete(id).unwrap());
        assert!(s.conversation_get(id).unwrap().is_none());
        assert!(s.conversation_search("jardin", 10).unwrap().is_empty());
        let n: i64 = s.conn.query_row("SELECT count(*) FROM conversations_fts", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 0);
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
        let id = s.conversation_ingest(&conv("parlé du jardinage")).unwrap();
        assert_eq!(s.conversations_unsummarized(10).unwrap().len(), 1);
        assert_eq!(s.conversation_search("jardinage", 10).unwrap().len(), 1);
        assert!(s.conversation_set_summary(id, "balade", &["a".into()], &["b".into()]).unwrap());
        assert!(s.conversations_unsummarized(10).unwrap().is_empty());
        let r = s.conversation_search("balade", 10).unwrap();
        assert_eq!(r[0].topics, ["a"]);
        assert_eq!(s.conversation_search("jardinage", 10).unwrap().len(), 1);
    }

    #[test]
    fn conversation_get_reduces_long_transcript() {
        let s = store();
        let id = s.conversation_ingest(&conv("court")).unwrap();
        let c = s.conversation_get(id).unwrap().unwrap();
        assert_eq!((c.id, c.channel.as_str(), c.started_at, c.transcript.as_str()), (id, "voix", t(), "court"));
        assert!(s.conversation_get(id + 1).unwrap().is_none());

        // Caractères multi-octets : coupe sur les frontières de caractères.
        let long = format!("{}{}{}", "é".repeat(15_000), "m".repeat(10), "à".repeat(45_000));
        let id = s.conversation_ingest(&conv(&long)).unwrap();
        let r = s.conversation_get(id).unwrap().unwrap().transcript;
        assert_eq!(r, format!("{}\n…\n{}", "é".repeat(15_000), "à".repeat(44_997)));
        assert_eq!(r.chars().count(), 60_000);
        let id = s.conversation_ingest(&conv(&"y".repeat(60_001))).unwrap();
        assert_eq!(s.conversation_get(id).unwrap().unwrap().transcript.chars().count(), 60_000);
        let exact = "x".repeat(60_000);
        let id = s.conversation_ingest(&conv(&exact)).unwrap();
        assert_eq!(s.conversation_get(id).unwrap().unwrap().transcript, exact);
    }

    #[test]
    fn reingest_same_session_replaces() {
        let s = store();
        let mut a = conv("ancien texte unique");
        a.session_id = Some("s1".into());
        let id = s.conversation_ingest(&a).unwrap();
        s.conversation_set_summary(id, "résumé", &[], &[]).unwrap();
        let mut b = conv("nouveau texte complet");
        b.session_id = Some("s1".into());
        b.started_at = t() + Duration::hours(1);
        b.ended_at = t() + Duration::hours(2);
        assert_eq!(s.conversation_ingest(&b).unwrap(), id);
        let n: i64 = s.conn.query_row("SELECT COUNT(*) FROM conversations", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 1);
        assert_eq!(s.conversations_unsummarized(10).unwrap().len(), 1);
        assert!(s.conversation_search("ancien", 10).unwrap().is_empty());
        let r = s.conversation_search("nouveau", 10).unwrap();
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].started_at, t());
        assert_eq!(r[0].ended_at, t() + Duration::hours(2));
        // sans clé de session, chaque ingestion reste distincte
        s.conversation_ingest(&conv("x")).unwrap();
        s.conversation_ingest(&conv("x")).unwrap();
        let n: i64 = s.conn.query_row("SELECT COUNT(*) FROM conversations", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 3);
    }

    #[test]
    fn open_adds_session_column_to_old_schema() {
        let path = std::env::temp_dir().join(format!("kairos-mem-old-{}.db", std::process::id()));
        let p = path.to_str().unwrap();
        {
            let c = Connection::open(p).unwrap();
            c.execute_batch("CREATE TABLE conversations (
                id INTEGER PRIMARY KEY, channel TEXT NOT NULL, device TEXT NOT NULL,
                started_at TEXT NOT NULL, ended_at TEXT NOT NULL, transcript TEXT NOT NULL,
                summary TEXT, topics TEXT NOT NULL DEFAULT '[]', decisions TEXT NOT NULL DEFAULT '[]');").unwrap();
        }
        let s = MemoryStore::open(p).unwrap();
        let mut a = conv("t");
        a.session_id = Some("s".into());
        assert_eq!(s.conversation_ingest(&a).unwrap(), s.conversation_ingest(&a).unwrap());
        drop(s);
        MemoryStore::open(p).unwrap(); // idempotent
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn migration_is_idempotent_and_keeps_everything() {
        let path = std::env::temp_dir().join(format!("kairos-mem-force-{}.db", std::process::id()));
        let p = path.to_str().unwrap();
        let _ = std::fs::remove_file(p);
        {
            let c = Connection::open(p).unwrap();
            c.execute_batch("
                CREATE TABLE journal (
                    id INTEGER PRIMARY KEY, at TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL,
                    content TEXT NOT NULL, sensitivity TEXT NOT NULL, provenance TEXT NOT NULL);
                CREATE VIRTUAL TABLE journal_fts USING fts5(content);
                CREATE TABLE fact_log (
                    id INTEGER PRIMARY KEY, key TEXT NOT NULL, value TEXT NOT NULL,
                    provenance TEXT NOT NULL, updated_at TEXT NOT NULL);
                CREATE INDEX fact_log_key ON fact_log(key, id);
                INSERT INTO fact_log (key, value, provenance, updated_at) VALUES
                    ('k', 'a', 'p', '2026-01-01 10:00:00'),
                    ('k', 'b', 'p', '2026-02-01 10:00:00'),
                    ('kk', 'z', 'p', '2026-03-01 10:00:00'),
                    ('persona', 'calme', 'hote', '2026-03-01 10:00:00');
                INSERT INTO journal (at, source, kind, content, sensitivity, provenance) VALUES
                    ('2026-04-01 08:00:00', 's', 'note', '{}', 'commune', 'p'),
                    ('2026-04-02 08:00:00', 's', 'note', '{}', 'commune', 'p');
            ").unwrap();
        }
        drop(MemoryStore::open(p).unwrap());
        let s = MemoryStore::open(p).unwrap();
        let count = |sql: &str| -> i64 { s.conn.query_row(sql, [], |r| r.get(0)).unwrap() };
        assert_eq!(count("SELECT count(*) FROM souvenirs WHERE nature = 'fait'"), 2);
        assert_eq!(count("SELECT count(*) FROM souvenirs WHERE nature = 'journal'"), 2);
        assert_eq!(count("SELECT count(*) FROM facts_fts"), 2);
        assert_eq!(count("SELECT count(*) FROM fact_log"), 4);
        assert_eq!(count("SELECT count(*) FROM journal"), 2);
        let f = s.force("fait", "k").unwrap().unwrap();
        assert_eq!((f.confiance, f.importance, f.stabilite, f.rappels, f.ancre), (1.0, 0.5, 90.0, 0, false));
        assert_eq!(f.dernier_rappel, parse_ts("2026-02-01 10:00:00").unwrap());
        let j = s.force("journal", "1").unwrap().unwrap();
        assert_eq!((j.stabilite, j.importance), (3.0, 0.0));
        assert_eq!(j.dernier_rappel, parse_ts("2026-04-01 08:00:00").unwrap());
        assert!(s.force("fait", PERSONA_KEY).unwrap().is_none());
        assert_eq!(s.fact_get("k").unwrap().unwrap().value, "b");
        drop(s);
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn redeclaring_a_fact_reinforces_it() {
        let s = store();
        s.fact_set_at("k", "a", "p", t()).unwrap();
        let later = t() + Duration::days(60);
        s.fact_set_at("k", "b", "p", later).unwrap();
        let f = s.force("fait", "k").unwrap().unwrap();
        assert_eq!(f.rappels, 1);
        assert!(f.stabilite > 90.0, "{}", f.stabilite);
        assert_eq!(f.dernier_rappel, later);
        assert_eq!(s.fact_get("k").unwrap().unwrap().value, "b");
        let v: String = s.conn.query_row("SELECT value FROM facts_fts WHERE key = 'k'", [], |r| r.get(0)).unwrap();
        assert_eq!(v, "b");
    }

    #[test]
    fn new_journal_entry_is_a_low_importance_episode() {
        let s = store();
        let id = note(&s, "x");
        let f = s.force("journal", &id.to_string()).unwrap().unwrap();
        assert_eq!((f.stabilite, f.importance, f.rappels), (3.0, 0.0, 0));
    }

    #[test]
    fn anchor_and_forget_cleanup() {
        let s = store();
        s.fact_set("k", "a", "p").unwrap();
        assert!(s.ancrer("fait", "k", true).unwrap());
        assert!(s.force("fait", "k").unwrap().unwrap().ancre);
        assert!(!s.ancrer("fait", "inconnue", true).unwrap());
        s.fact_forget("k").unwrap();
        assert!(s.force("fait", "k").unwrap().is_none());
        let n: i64 = s.conn.query_row("SELECT count(*) FROM facts_fts", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 0);
        let id = note(&s, "x");
        assert!(s.journal_delete(id).unwrap());
        assert!(s.force("journal", &id.to_string()).unwrap().is_none());
    }

    #[test]
    fn persona_has_no_memory_row() {
        let s = store();
        s.fact_set(PERSONA_KEY, "calme", "hote").unwrap();
        s.fact_set(PERSONA_KEY, "vif", "hote").unwrap();
        assert!(s.force("fait", PERSONA_KEY).unwrap().is_none());
        assert_eq!(s.fact_get(PERSONA_KEY).unwrap().unwrap().value, "vif");
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

    // --- Rappel ---

    fn vieillir(s: &MemoryStore, nature: &str, r#ref: &str, quand: NaiveDateTime) {
        s.conn
            .execute("UPDATE souvenirs SET dernier_rappel = ? WHERE nature = ? AND ref = ?", params![ts(quand), nature, r#ref])
            .unwrap();
    }

    #[test]
    fn recall_never_reinforces() {
        let s = store();
        s.fact_set_at("ville", "Lyon", "p", t()).unwrap();
        let id = note(&s, "promenade au parc").to_string();
        let avant = (s.force("fait", "ville").unwrap(), s.force("journal", &id).unwrap());
        let plus_tard = t() + Duration::days(40);
        s.rappel(Some("Lyon promenade"), plus_tard, 10).unwrap();
        s.rappel(None, plus_tard, 10).unwrap();
        assert_eq!(avant, (s.force("fait", "ville").unwrap(), s.force("journal", &id).unwrap()));
    }

    #[test]
    fn anchors_always_first_even_without_match() {
        let s = store();
        s.fact_set_at("animal", "chat noir", "p", t()).unwrap();
        s.fact_set_at("transport", "velo rouge", "p", t()).unwrap();
        s.ancrer("fait", "animal", true).unwrap();
        let r = s.rappel(Some("velo"), t(), 10).unwrap();
        assert_eq!((r[0].r#ref.as_str(), r[0].ancre), ("animal", true));
        assert_eq!(r[1].r#ref, "transport");
    }

    #[test]
    fn faded_only_when_top_three() {
        let s = store();
        let id = note(&s, "vu une baleine").to_string();
        vieillir(&s, "journal", &id, t() - Duration::days(30));
        let a_id = |r: &Vec<Rappel>| r.iter().any(|x| x.nature == "journal" && x.r#ref == id);
        assert!(!a_id(&s.rappel(None, t(), 10).unwrap()));
        let r = s.rappel(Some("baleine"), t(), 10).unwrap();
        assert!(a_id(&r));
        assert!(r.iter().find(|x| x.r#ref == id).unwrap().estompe);
    }

    #[test]
    fn relevance_orders_results() {
        let s = store();
        s.fact_set_at("aaa", "pomme", "p", t()).unwrap();
        s.fact_set_at("bbb", "banane", "p", t()).unwrap();
        let r = s.rappel(Some("banane"), t(), 10).unwrap();
        assert_eq!(r[0].r#ref, "bbb");
        assert_eq!(r[0].texte, "bbb : banane");
        assert!(r[0].score > r[1].score);
    }

    #[test]
    fn recall_query_is_escaped() {
        let s = store();
        s.fact_set_at("k", "v", "p", t()).unwrap();
        note(&s, "x");
        for q in ["\"", "a AND", "*", "NEAR(", "-x", "((", "le la de", "\"\"\"", "(((", "abc\"def"] {
            assert!(s.rappel(Some(q), t(), 10).is_ok(), "{q}");
        }
    }

    #[test]
    fn anchor_budget_is_respected() {
        let s = store();
        for i in 0..50 {
            let k = format!("cle{i}");
            s.fact_set_at(&k, &"x".repeat(250), "p", t()).unwrap();
            s.ancrer("fait", &k, true).unwrap();
        }
        let r = s.rappel(None, t(), 10).unwrap();
        let total: usize = r.iter().filter(|x| x.ancre).map(|x| x.texte.chars().count()).sum();
        assert!(total <= 3200 && total > 0, "{total}");
    }
}
