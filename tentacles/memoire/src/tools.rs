//! Outils MCP du tentacule mémoire : fine couche d'adaptation au-dessus de `MemoryStore`.

use std::sync::{Arc, Mutex, MutexGuard};

use chrono::{Duration, NaiveDateTime, Utc};
use kairos::calendar::USER_TZ;
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::{Deserialize, Serialize};
use tentacle_common::BriefPeriod;

use crate::store::{IntentionState, MemoryStore, NewConversation, NewJournalEntry, NewLesson, PERSONA_KEY, Sensitivity};

/// Handler MCP ; bon marché à construire (le stockage est partagé).
#[derive(Clone)]
pub struct MemoryServer {
    store: Arc<Mutex<MemoryStore>>,
    tool_router: ToolRouter<Self>,
}

impl MemoryServer {
    pub fn new(store: Arc<Mutex<MemoryStore>>) -> Self {
        Self { store, tool_router: Self::tool_router() }
    }

    fn db(&self) -> Result<MutexGuard<'_, MemoryStore>, String> {
        self.store.lock().map_err(|_| "stockage indisponible".to_string())
    }
}

type Out = Result<String, String>;

fn json_out<T: Serialize>(v: T) -> Out {
    serde_json::to_string(&v).map_err(|e| e.to_string())
}

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

/// Date locale `YYYY-MM-DD HH:MM[:SS]`.
fn parse_dt(s: &str) -> Result<NaiveDateTime, String> {
    NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S")
        .or_else(|_| NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M"))
        .map_err(|_| format!("date invalide « {s} » (attendu AAAA-MM-JJ HH:MM)"))
}

fn now_local() -> NaiveDateTime {
    Utc::now().with_timezone(&USER_TZ).naive_local()
}

#[derive(Deserialize, schemars::JsonSchema)]
struct JournalWriteArgs {
    source: String,
    kind: String,
    content: serde_json::Value,
    sensitivity: Sensitivity,
    provenance: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct SearchArgs {
    query: String,
    limit: Option<u32>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct KeyArgs {
    key: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct FactSetArgs {
    key: String,
    value: String,
    provenance: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct PrefixArgs {
    prefix: Option<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct IntentionCreateArgs {
    what: String,
    constraints: String,
    /// Échéance locale, `AAAA-MM-JJ HH:MM`.
    expires_at: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct IntentionStateArgs {
    id: i64,
    state: IntentionState,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct ConversationIngestArgs {
    channel: String,
    device: String,
    /// Début local, `AAAA-MM-JJ HH:MM`.
    started_at: String,
    /// Fin locale, `AAAA-MM-JJ HH:MM`.
    ended_at: String,
    transcript: String,
    /// Identifiant de session : une ré-ingestion de la même session remplace la précédente.
    session_id: Option<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct LimitArgs {
    limit: Option<u32>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct SummaryArgs {
    id: i64,
    summary: String,
    topics: Vec<String>,
    decisions: Vec<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct LessonArgs {
    context: String,
    qualification: String,
    debrief: String,
    conclusion: String,
    actions: Vec<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
// La période est validée mais ne change pas encore le contenu.
#[allow(dead_code)]
struct BriefArgs {
    period: BriefPeriod,
}

#[tool_router]
impl MemoryServer {
    #[tool(description = "Consigner un événement ou une observation dans le journal, dès qu'il mérite d'être retenu.")]
    fn journal_write(&self, Parameters(a): Parameters<JournalWriteArgs>) -> Out {
        let id = self
            .db()?
            .journal_write(&NewJournalEntry {
                source: a.source,
                kind: a.kind,
                content: a.content,
                sensitivity: a.sensitivity,
                provenance: a.provenance,
            })
            .map_err(err)?;
        json_out(serde_json::json!({ "id": id }))
    }

    #[tool(description = "Rechercher dans le journal quand il faut retrouver un événement passé.")]
    fn journal_search(&self, Parameters(a): Parameters<SearchArgs>) -> Out {
        json_out(self.db()?.journal_search(&a.query, a.limit.unwrap_or(10)).map_err(err)?)
    }

    #[tool(description = "Lire la valeur courante d'un fait connu sur l'utilisateur, à partir de sa clé.")]
    fn fact_get(&self, Parameters(a): Parameters<KeyArgs>) -> Out {
        json_out(self.db()?.fact_get(&a.key).map_err(err)?)
    }

    #[tool(description = "Enregistrer ou mettre à jour un fait stable sur l'utilisateur (l'historique est conservé).")]
    fn fact_set(&self, Parameters(a): Parameters<FactSetArgs>) -> Out {
        self.db()?.fact_set(&a.key, &a.value, &a.provenance).map_err(err)?;
        json_out(serde_json::json!({ "ok": true }))
    }

    #[tool(description = "Lister les faits connus, éventuellement sous un préfixe de clé, pour voir ce qui est déjà su.")]
    fn facts_list(&self, Parameters(a): Parameters<PrefixArgs>) -> Out {
        json_out(self.db()?.facts_list(a.prefix.as_deref().unwrap_or("")).map_err(err)?)
    }

    #[tool(description = "Lire la personnalité de l'assistant, au début d'une conversation.")]
    fn persona_get(&self) -> Out {
        json_out(self.db()?.fact_get(PERSONA_KEY).map_err(err)?)
    }

    #[tool(description = "Créer une intention (chose à faire ou à proposer) avec une échéance, quand l'utilisateur en exprime une.")]
    fn intention_create(&self, Parameters(a): Parameters<IntentionCreateArgs>) -> Out {
        let at = parse_dt(&a.expires_at)?;
        let id = self.db()?.intention_create(&a.what, &a.constraints, at).map_err(err)?;
        json_out(serde_json::json!({ "id": id }))
    }

    #[tool(description = "Changer l'état d'une intention (proposée, acceptée, refusée…) quand l'utilisateur a réagi.")]
    fn intention_set_state(&self, Parameters(a): Parameters<IntentionStateArgs>) -> Out {
        json_out(serde_json::json!({ "updated": self.db()?.intention_set_state(a.id, a.state).map_err(err)? }))
    }

    #[tool(description = "Lister les intentions encore ouvertes (les échues sont expirées au passage).")]
    fn intentions_open(&self) -> Out {
        json_out(self.db()?.intentions_open(now_local()).map_err(err)?)
    }

    #[tool(description = "Archiver la transcription d'une conversation terminée, pour pouvoir la retrouver et la résumer.")]
    fn conversation_ingest(&self, Parameters(a): Parameters<ConversationIngestArgs>) -> Out {
        let c = NewConversation {
            channel: a.channel,
            device: a.device,
            started_at: parse_dt(&a.started_at)?,
            ended_at: parse_dt(&a.ended_at)?,
            transcript: a.transcript,
            session_id: a.session_id.filter(|s| !s.is_empty()),
        };
        json_out(serde_json::json!({ "id": self.db()?.conversation_ingest(&c).map_err(err)? }))
    }

    #[tool(description = "Rechercher dans les conversations passées quand il faut retrouver ce qui a été dit.")]
    fn conversation_search(&self, Parameters(a): Parameters<SearchArgs>) -> Out {
        json_out(self.db()?.conversation_search(&a.query, a.limit.unwrap_or(10)).map_err(err)?)
    }

    #[tool(description = "Lister les conversations pas encore résumées, pour les traiter en différé.")]
    fn conversations_unsummarized(&self, Parameters(a): Parameters<LimitArgs>) -> Out {
        json_out(self.db()?.conversations_unsummarized(a.limit.unwrap_or(10)).map_err(err)?)
    }

    #[tool(description = "Attacher résumé, sujets et décisions à une conversation une fois qu'elle a été résumée.")]
    fn conversation_set_summary(&self, Parameters(a): Parameters<SummaryArgs>) -> Out {
        let done = self.db()?.conversation_set_summary(a.id, &a.summary, &a.topics, &a.decisions).map_err(err)?;
        json_out(serde_json::json!({ "updated": done }))
    }

    #[tool(description = "Enregistrer une leçon (contexte, bilan, conclusion, actions) après un échec ou un succès à retenir.")]
    fn lesson_record(&self, Parameters(a): Parameters<LessonArgs>) -> Out {
        let id = self
            .db()?
            .lesson_record(&NewLesson {
                context: a.context,
                qualification: a.qualification,
                debrief: a.debrief,
                conclusion: a.conclusion,
                actions: a.actions,
            })
            .map_err(err)?;
        json_out(serde_json::json!({ "id": id }))
    }

    #[tool(description = "Obtenir le texte court de la section mémoire d'un briefing (intentions expirant sous 24 h), vide si rien.")]
    fn brief_section(&self, Parameters(_): Parameters<BriefArgs>) -> Out {
        // Même contenu matin et soir pour l'instant.
        let now = now_local();
        let limit = now + Duration::hours(24);
        let soon: Vec<_> =
            self.db()?.intentions_open(now).map_err(err)?.into_iter().filter(|i| i.expires_at <= limit).collect();
        Ok(soon
            .iter()
            .map(|i| format!("- {} (avant le {})", i.what, i.expires_at.format("%d/%m %H:%M")))
            .collect::<Vec<_>>()
            .join("\n"))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for MemoryServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}
