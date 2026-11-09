//! Outils MCP du tentacule mémoire : fine couche d'adaptation au-dessus de `MemoryStore`.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, MutexGuard};

use chrono::{Duration, NaiveDate, NaiveDateTime, Utc};
use kairos::calendar::USER_TZ;
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tentacle_common::BriefPeriod;

use crate::store::{
    ConversationCard, Indice, IntentionState, JournalEntry, MemoryStore, NewConversation, NewJournalEntry, NewLesson,
    Observation, PERSONA_KEY, Sensitivity,
};

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

/// Jour `AAAA-MM-JJ`.
fn parse_jour(s: &str) -> Result<NaiveDate, String> {
    NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d").map_err(|_| format!("jour invalide « {s} » (attendu AAAA-MM-JJ)"))
}

fn now_local() -> NaiveDateTime {
    Utc::now().with_timezone(&USER_TZ).naive_local()
}

/// Nombre maximal d'entrées renvoyées à l'application.
const APP_LIMIT: u32 = 30;
const APP_MAX_DAYS: i64 = 3650;
const APP_PROVENANCE: &str = "application";
/// La personnalité de l'assistant ne se lit ni ne se modifie depuis l'application.
const PERSONA_REFUSED: &str = "La personnalité de l'assistant ne se modifie pas d'ici.";

/// La personnalité ne s'écrit que depuis l'hôte (`kairos-memoire persona`), jamais par un outil.
fn is_persona(key: &str) -> bool {
    key.trim().eq_ignore_ascii_case(PERSONA_KEY)
}

/// Sensibilité des notes saisies depuis l'application.
const APP_SENSITIVITY: Sensitivity = Sensitivity::Commune;

#[derive(Deserialize, schemars::JsonSchema)]
struct AppIntentionSetArgs {
    id: i64,
    /// `acceptee` ou `refusee`.
    state: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct AppIntentionAddArgs {
    what: String,
    /// Jours avant l'échéance ; 0 = fin de journée.
    days: i64,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct AppTextArgs {
    text: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct AppQueryArgs {
    query: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct AppFactSetArgs {
    key: String,
    value: String,
}

/// Texte d'une entrée de journal, quelle que soit la forme du contenu.
fn entry_text(c: &Value) -> String {
    ["texte", "text"]
        .iter()
        .find_map(|k| c.get(k).and_then(Value::as_str))
        .or_else(|| c.as_str())
        .map(str::to_string)
        .unwrap_or_else(|| c.to_string())
}

fn entry_json(e: &JournalEntry) -> Value {
    json!({
        "id": e.id,
        "at": e.at,
        "kind": e.kind,
        "text": entry_text(&e.content),
        "sensitive": e.sensitivity != Sensitivity::Commune,
    })
}

fn conversation_json(c: &ConversationCard, with_decisions: bool) -> Value {
    let mut v = json!({"id": c.id, "at": c.started_at, "topics": c.topics});
    if let Some(s) = &c.summary {
        v["summary"] = json!(s);
    }
    if with_decisions {
        v["decisions"] = json!(c.decisions);
    }
    v
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
struct RappelArgs {
    /// Sujet du rappel ; absent pour un rappel général.
    message: Option<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct FactAnchorArgs {
    key: String,
    /// `true` pour toujours retenir le fait, `false` pour ne plus le forcer.
    ancre: bool,
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
struct IdArgs {
    id: i64,
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
struct JourArgs {
    /// `AAAA-MM-JJ`.
    jour: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct AppliquerArgs {
    /// `AAAA-MM-JJ`.
    jour: String,
    indices: Vec<Indice>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct NuitHabitudesArgs {
    /// `AAAA-MM-JJ`.
    jour: String,
    /// Observations de la journée, par source.
    sources: BTreeMap<String, Vec<Observation>>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct MaxArgs {
    max: usize,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct CondenserArgs {
    niveau: String,
    periode: String,
    texte: String,
    empreinte: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct NoterArgs {
    etape: String,
    statut: String,
    /// `AAAA-MM-JJ` ; absent, le précédent est gardé.
    dernier_jour: Option<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
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
        if is_persona(&a.key) {
            return Err(PERSONA_REFUSED.into());
        }
        self.db()?.fact_set(&a.key, &a.value, &a.provenance).map_err(err)?;
        json_out(serde_json::json!({ "ok": true }))
    }

    #[tool(description = "Lister les faits connus, éventuellement sous un préfixe de clé, pour voir ce qui est déjà su.")]
    fn facts_list(&self, Parameters(a): Parameters<PrefixArgs>) -> Out {
        json_out(self.db()?.facts_list(a.prefix.as_deref().unwrap_or("")).map_err(err)?)
    }

    #[tool(description = "Usage interne de la tête : rappeler les souvenirs les plus utiles, éventuellement selon un message.")]
    fn rappel(&self, Parameters(a): Parameters<RappelArgs>) -> Out {
        let r = self.db()?.rappel(a.message.as_deref(), now_local(), 12).map_err(err)?;
        json_out(r)
    }

    #[tool(description = "Quand l'utilisateur demande de toujours retenir (ou de ne plus forcer) un fait.")]
    fn fact_anchor(&self, Parameters(a): Parameters<FactAnchorArgs>) -> Out {
        if is_persona(&a.key) {
            return Err(PERSONA_REFUSED.into());
        }
        // Clé telle qu'écrite (éventuellement avec espaces), sinon nettoyée.
        let db = self.db()?;
        if !db.ancrer("fait", &a.key, a.ancre).map_err(err)? && !db.ancrer("fait", a.key.trim(), a.ancre).map_err(err)? {
            return json_out(json!({"message": "Fait inconnu : rien n'a été modifié."}));
        }
        let m = if a.ancre { "Fait ancré : il sera toujours rappelé." } else { "Fait désancré : il n'est plus forcé." };
        json_out(json!({"message": m}))
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

    #[tool(description = "Lire la transcription d'une conversation (début et fin si elle est très longue), pour la résumer.")]
    fn conversation_get(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        json_out(self.db()?.conversation_get(a.id).map_err(err)?.ok_or("conversation inconnue")?)
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

    #[tool(description = "Vue JSON de la mémoire pour l'application : intentions ouvertes, journal et conversations récents, faits.")]
    fn app_memory(&self) -> Out {
        let db = self.db()?;
        let intentions: Vec<Value> = db
            .intentions_open(now_local())
            .map_err(err)?
            .iter()
            .map(|i| {
                let mut v = json!({"id": i.id, "what": i.what, "expires": i.expires_at, "state": i.state});
                if !i.constraints.is_empty() {
                    v["constraints"] = json!(i.constraints);
                }
                v
            })
            .collect();
        let journal: Vec<Value> = db.journal_recent(APP_LIMIT).map_err(err)?.iter().map(entry_json).collect();
        let facts: Vec<Value> = db
            .facts_list("")
            .map_err(err)?
            .iter()
            .filter(|f| f.key != PERSONA_KEY)
            .map(|f| json!({"key": f.key, "value": f.value, "updated": f.updated_at}))
            .collect();
        let conversations: Vec<Value> =
            db.conversations_recent(APP_LIMIT).map_err(err)?.iter().map(|c| conversation_json(c, true)).collect();
        json_out(json!({"intentions": intentions, "journal": journal, "facts": facts, "conversations": conversations}))
    }

    #[tool(
        description = "Accepter ou refuser une intention depuis l'application (acceptee ou refusee). Rend `message` (à afficher) et `outcome` : `done`, ou `stale` (échue, déjà traitée ou inconnue : rien n'est changé)."
    )]
    fn app_intention_set(&self, Parameters(a): Parameters<AppIntentionSetArgs>) -> Out {
        let state = match a.state.as_str() {
            "acceptee" => IntentionState::Acceptee,
            "refusee" => IntentionState::Refusee,
            _ => return Err("état attendu : acceptee ou refusee".into()),
        };
        let db = self.db()?;
        // Seules les intentions encore ouvertes peuvent changer d'état.
        if !db.intentions_open(now_local()).map_err(err)?.iter().any(|i| i.id == a.id) {
            return json_out(json!({"message": "Cette intention est échue ou déjà traitée.", "outcome": "stale"}));
        }
        db.intention_set_state(a.id, state).map_err(err)?;
        let message = if state == IntentionState::Acceptee { "Intention acceptée." } else { "Intention refusée." };
        json_out(json!({ "message": message, "outcome": "done" }))
    }

    #[tool(description = "Ajouter une intention depuis l'application, avec une échéance en jours.")]
    fn app_intention_add(&self, Parameters(a): Parameters<AppIntentionAddArgs>) -> Out {
        let what = a.what.trim();
        if what.is_empty() {
            return Err("texte vide".into());
        }
        if !(0..=APP_MAX_DAYS).contains(&a.days) {
            return Err(format!("délai invalide : 0 à {APP_MAX_DAYS} jours"));
        }
        let now = now_local();
        let at = if a.days == 0 {
            now.date().and_hms_opt(23, 59, 0).unwrap_or(now)
        } else {
            now + Duration::days(a.days)
        };
        let id = self.db()?.intention_create(what, "", at).map_err(err)?;
        json_out(json!({"message": "Intention ajoutée.", "id": id}))
    }

    #[tool(description = "Ajouter une note au journal depuis l'application.")]
    fn app_journal_add(&self, Parameters(a): Parameters<AppTextArgs>) -> Out {
        let text = a.text.trim();
        if text.is_empty() {
            return Err("texte vide".into());
        }
        let id = self
            .db()?
            .journal_write(&NewJournalEntry {
                source: APP_PROVENANCE.into(),
                kind: "note".into(),
                content: json!({ "texte": text }),
                sensitivity: APP_SENSITIVITY,
                provenance: APP_PROVENANCE.into(),
            })
            .map_err(err)?;
        json_out(json!({"message": "Note ajoutée.", "id": id}))
    }

    #[tool(description = "Rechercher dans le journal pour l'application (30 résultats au plus).")]
    fn app_journal_search(&self, Parameters(a): Parameters<AppQueryArgs>) -> Out {
        let found = self.db()?.journal_search(&a.query, APP_LIMIT).map_err(err)?;
        json_out(found.iter().map(entry_json).collect::<Vec<_>>())
    }

    #[tool(description = "Historique des valeurs d'un fait pour l'application, de la plus ancienne à la plus récente.")]
    fn app_fact_history(&self, Parameters(a): Parameters<KeyArgs>) -> Out {
        if is_persona(&a.key) {
            return Err(PERSONA_REFUSED.into());
        }
        let h = self.db()?.fact_history(&a.key).map_err(err)?;
        json_out(h.iter().map(|f| json!({"value": f.value, "at": f.updated_at})).collect::<Vec<_>>())
    }

    #[tool(description = "Corriger la valeur d'un fait depuis l'application (l'historique est conservé).")]
    fn app_fact_set(&self, Parameters(a): Parameters<AppFactSetArgs>) -> Out {
        let (key, value) = (a.key.trim(), a.value.trim());
        if key.is_empty() || value.is_empty() {
            return Err("clé ou valeur vide".into());
        }
        if is_persona(key) {
            return Err(PERSONA_REFUSED.into());
        }
        self.db()?.fact_set(key, value, APP_PROVENANCE).map_err(err)?;
        json_out(json!({"message": "Fait enregistré."}))
    }

    #[tool(description = "Supprimer définitivement une entrée du journal depuis l'application.")]
    fn app_journal_delete(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        if !self.db()?.journal_delete(a.id).map_err(err)? {
            return Err("entrée inconnue".into());
        }
        json_out(json!({"message": "Entrée supprimée."}))
    }

    #[tool(description = "Oublier un fait depuis l'application : valeur courante, historique, déductions et questions de confirmation.")]
    fn app_fact_forget(&self, Parameters(a): Parameters<KeyArgs>) -> Out {
        if is_persona(&a.key) {
            return Err(PERSONA_REFUSED.into());
        }
        // Clé telle qu'écrite (éventuellement avec espaces), sinon nettoyée.
        let db = self.db()?;
        if db.fact_forget(&a.key).map_err(err)? == 0 && db.fact_forget(a.key.trim()).map_err(err)? == 0 {
            return Err("fait inconnu".into());
        }
        json_out(json!({"message": "Fait oublié."}))
    }

    #[tool(description = "Oublier définitivement une habitude depuis l'application.")]
    fn app_habitude_forget(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        if !self.db()?.habitude_oublier(a.id).map_err(err)? {
            return Err("habitude inconnue".into());
        }
        json_out(json!({"message": "Habitude oubliée."}))
    }

    #[tool(description = "Supprimer définitivement une conversation depuis l'application.")]
    fn app_conversation_delete(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        if !self.db()?.conversation_delete(a.id).map_err(err)? {
            return Err("conversation inconnue".into());
        }
        json_out(json!({"message": "Conversation supprimée."}))
    }

    #[tool(description = "Rechercher dans les conversations pour l'application (30 résultats au plus).")]
    fn app_conversation_search(&self, Parameters(a): Parameters<AppQueryArgs>) -> Out {
        let found = self.db()?.conversation_search(&a.query, APP_LIMIT).map_err(err)?;
        json_out(found.iter().map(|c| conversation_json(c, false)).collect::<Vec<_>>())
    }

    #[tool(description = "Obtenir le texte court de la section mémoire d'un briefing (intentions expirant sous 24 h), vide si rien.")]
    fn brief_section(&self, Parameters(a): Parameters<BriefArgs>) -> Out {
        self.brief_at(now_local(), a.period)
    }

    // --- Nuit : usage interne de la tête, jamais proposé au modèle ---

    #[tool(description = "Usage interne de la tête : épisodes complets d'une journée.")]
    fn nuit_episodes(&self, Parameters(a): Parameters<JourArgs>) -> Out {
        json_out(self.db()?.episodes_du_jour(parse_jour(&a.jour)?).map_err(err)?)
    }

    #[tool(description = "Usage interne de la tête : conversations d'une journée encore à résumer.")]
    fn nuit_en_attente(&self, Parameters(a): Parameters<JourArgs>) -> Out {
        json_out(json!({"conversations": self.db()?.conversations_non_resumees_le(parse_jour(&a.jour)?).map_err(err)?}))
    }

    #[tool(description = "Usage interne de la tête : clés connues, déclarées et déduites.")]
    fn nuit_cles(&self) -> Out {
        json_out(self.db()?.cles().map_err(err)?)
    }

    #[tool(description = "Usage interne de la tête : appliquer les indices extraits d'une journée.")]
    fn nuit_appliquer(&self, Parameters(a): Parameters<AppliquerArgs>) -> Out {
        json_out(self.db()?.appliquer_extraction(parse_jour(&a.jour)?, &a.indices, now_local()).map_err(err)?)
    }

    #[tool(description = "Usage interne de la tête : compter les observations d'habitudes d'une journée.")]
    fn nuit_habitudes(&self, Parameters(a): Parameters<NuitHabitudesArgs>) -> Out {
        json_out(self.db()?.appliquer_observations(parse_jour(&a.jour)?, &a.sources, now_local()).map_err(err)?)
    }

    #[tool(description = "Usage interne de la tête : lots de souvenirs à condenser.")]
    fn nuit_condensables(&self, Parameters(a): Parameters<MaxArgs>) -> Out {
        json_out(self.db()?.condensables(now_local(), a.max).map_err(err)?)
    }

    #[tool(description = "Usage interne de la tête : enregistrer la synthèse d'un lot.")]
    fn nuit_condenser(&self, Parameters(a): Parameters<CondenserArgs>) -> Out {
        let id = self.db()?.condenser(&a.niveau, &a.periode, &a.texte, &a.empreinte, now_local()).map_err(err)?;
        json_out(json!({"id": id}))
    }

    #[tool(description = "Usage interne de la tête : effacer les souvenirs oubliés.")]
    fn nuit_oublier(&self) -> Out {
        json_out(json!({"supprimes": self.db()?.oublier(now_local()).map_err(err)?}))
    }

    #[tool(description = "Usage interne de la tête : état des étapes de nuit.")]
    fn nuit_etat(&self) -> Out {
        json_out(self.db()?.nuit_etat().map_err(err)?)
    }

    #[tool(description = "Usage interne de la tête : noter l'exécution d'une étape de nuit.")]
    fn nuit_noter(&self, Parameters(a): Parameters<NoterArgs>) -> Out {
        let jour = a.dernier_jour.as_deref().map(parse_jour).transpose()?;
        self.db()?.nuit_noter(&a.etape, &a.statut, jour, now_local()).map_err(err)?;
        json_out(json!({"message": "État noté."}))
    }
}

/// Au-delà de ce délai sans exécution, une étape de nuit est signalée arrêtée.
const NUIT_MUETTE_HEURES: i64 = 36;

/// Raison courte d'un statut d'étape, pour le briefing.
fn raison(statut: &str) -> &'static str {
    match statut {
        "modele_indisponible" => "modèle indisponible",
        "sortie_invalide" => "réponse du modèle inutilisable",
        "source_indisponible" => "source indisponible",
        _ => "échec",
    }
}

impl MemoryServer {
    /// Section mémoire du briefing à l'instant `now`.
    fn brief_at(&self, now: NaiveDateTime, period: BriefPeriod) -> Out {
        let limit = now + Duration::hours(24);
        let db = self.db()?;
        let soon: Vec<_> = db.intentions_open(now).map_err(err)?.into_iter().filter(|i| i.expires_at <= limit).collect();
        let mut lignes: Vec<String> =
            soon.iter().map(|i| format!("- {} (avant le {})", i.what, i.expires_at.format("%d/%m %H:%M"))).collect();
        // Une habitude remarquée la nuit se dit au matin qui suit.
        if period == BriefPeriod::Matin {
            for l in db.habitudes_annoncees(now - Duration::hours(24)).map_err(err)? {
                lignes.push(format!("- Habitude remarquée : {l} — à confirmer"));
            }
        }
        // État de la nuit seulement : jamais le contenu de la mémoire.
        let jj = |d: NaiveDate| d.format("%d/%m/%Y").to_string();
        for e in db.nuit_etat().map_err(err)? {
            let (Some(derniere), false) = (e.derniere_execution, e.illisible) else {
                lignes.push("- Mémoire : état de nuit illisible".into());
                continue;
            };
            if e.statut != "ok" {
                let depuis = e.dernier_jour.and_then(|j| j.succ_opt()).unwrap_or(derniere.date());
                lignes.push(format!("- Mémoire : {} en attente depuis le {} — {}", e.etape, jj(depuis), raison(&e.statut)));
            }
            if now - derniere > Duration::hours(NUIT_MUETTE_HEURES) {
                lignes.push(format!("- Mémoire : {} n'a pas tourné depuis le {}", e.etape, jj(derniere.date())));
            }
        }
        let n = db.conversations_non_resumees_avant(now.date() - Duration::days(7)).map_err(err)?;
        if n > 0 {
            lignes.push(format!("- Mémoire : {n} conversation(s) à résumer depuis plus d'une semaine"));
        }
        Ok(lignes.join("\n"))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for MemoryServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Datelike;

    fn srv() -> MemoryServer {
        MemoryServer::new(Arc::new(Mutex::new(MemoryStore::open(":memory:").unwrap())))
    }

    fn js(o: Out) -> Value {
        serde_json::from_str(&o.unwrap()).unwrap()
    }

    fn far() -> NaiveDateTime {
        now_local() + Duration::days(5)
    }

    fn q(s: &str) -> Parameters<AppQueryArgs> {
        Parameters(AppQueryArgs { query: s.into() })
    }

    fn jw(s: &MemoryServer, text: &str, sens: Sensitivity) -> i64 {
        s.db()
            .unwrap()
            .journal_write(&NewJournalEntry {
                source: "s".into(),
                kind: "note".into(),
                content: json!({"texte": text}),
                sensitivity: sens,
                provenance: "p".into(),
            })
            .unwrap()
    }

    fn set_state(s: &MemoryServer, id: i64, st: &str) -> Out {
        s.app_intention_set(Parameters(AppIntentionSetArgs { id, state: st.into() }))
    }

    #[test]
    fn rappel_tool_shape() {
        let s = srv();
        s.db().unwrap().fact_set("cafe", "noir", "t").unwrap();
        for m in [None, Some("cafe".to_string())] {
            let v = js(s.rappel(Parameters(RappelArgs { message: m })));
            let e = &v.as_array().unwrap()[0];
            for k in ["nature", "ref", "texte", "score", "estompe", "ancre"] {
                assert!(e.get(k).is_some(), "{k}");
            }
        }
    }

    #[test]
    fn fact_anchor_refuses_persona_and_unknown_is_clear() {
        let s = srv();
        let a = |key: &str, ancre| Parameters(FactAnchorArgs { key: key.into(), ancre });
        assert!(s.fact_anchor(a(PERSONA_KEY, true)).is_err());
        assert!(js(s.fact_anchor(a("inconnu", true)))["message"].as_str().unwrap().contains("inconnu"));
        s.db().unwrap().fact_set("cafe", "noir", "t").unwrap();
        assert!(js(s.fact_anchor(a(" cafe ", true)))["message"].is_string());
        assert!(s.db().unwrap().rappel(None, now_local(), 12).unwrap().iter().any(|r| r.ancre));
    }

    #[test]
    fn fact_anchor_tries_raw_key_first() {
        let s = srv();
        s.db().unwrap().fact_set(" the ", "vert", "t").unwrap();
        let m = js(s.fact_anchor(Parameters(FactAnchorArgs { key: " the ".into(), ancre: true })));
        assert!(!m["message"].as_str().unwrap().contains("inconnu"));
        assert!(s.db().unwrap().force("fait", " the ").unwrap().unwrap().ancre);
    }

    #[test]
    fn app_memory_json_shape() {
        let s = srv();
        s.db().unwrap().intention_create("appeler", "avant midi", far()).unwrap();
        s.db().unwrap().intention_create("lire", "", far()).unwrap();
        jw(&s, "bonne journée", Sensitivity::Commune);
        s.db().unwrap().fact_set("sante.allergie", "pollen", "p").unwrap();
        let c = NewConversation {
            channel: "voix".into(),
            device: "d".into(),
            started_at: now_local(),
            ended_at: now_local(),
            transcript: "bonjour".into(),
            session_id: None,
        };
        let cid = s.db().unwrap().conversation_ingest(&c).unwrap();
        s.db().unwrap().conversation_set_summary(cid, "résumé", &["a".into()], &["d".into()]).unwrap();
        let v = js(s.app_memory());
        let i = &v["intentions"][0];
        assert_eq!(i["what"], "appeler");
        assert_eq!(i["state"], "ouverte");
        assert_eq!(i["constraints"], "avant midi");
        assert!(i["id"].is_i64() && i["expires"].is_string());
        assert!(v["intentions"][1].get("constraints").is_none());
        let j = &v["journal"][0];
        assert_eq!((j["kind"].as_str(), j["text"].as_str()), (Some("note"), Some("bonne journée")));
        assert!(j["id"].is_i64() && j["at"].is_string());
        assert_eq!(v["facts"][0]["key"], "sante.allergie");
        assert_eq!(v["facts"][0]["value"], "pollen");
        assert!(v["facts"][0]["updated"].is_string());
        let c = &v["conversations"][0];
        assert_eq!(c["summary"], "résumé");
        assert_eq!(c["topics"][0], "a");
        assert_eq!(c["decisions"][0], "d");
    }

    #[test]
    fn app_memory_keeps_30_latest() {
        let s = srv();
        for n in 0..35 {
            jw(&s, &format!("n{n}"), Sensitivity::Commune);
        }
        let v = js(s.app_memory());
        assert_eq!(v["journal"].as_array().unwrap().len(), 30);
        assert_eq!(v["journal"][0]["text"], "n34");
    }

    #[test]
    fn sensitive_flag_exposed() {
        let s = srv();
        jw(&s, "secret banal", Sensitivity::Strategique);
        jw(&s, "public banal", Sensitivity::Commune);
        let v = js(s.app_memory());
        assert_eq!(v["journal"][0]["sensitive"], false);
        assert_eq!(v["journal"][1]["sensitive"], true);
        let f = js(s.app_journal_search(q("secret")));
        assert_eq!(f[0]["sensitive"], true);
    }

    #[test]
    fn intention_accept_and_refuse() {
        let s = srv();
        let a = s.db().unwrap().intention_create("a", "", far()).unwrap();
        let b = s.db().unwrap().intention_create("b", "", far()).unwrap();
        let v = js(set_state(&s, a, "acceptee"));
        assert!(v["message"].is_string() && v["outcome"] == "done", "{v}");
        assert!(js(set_state(&s, b, "refusee"))["message"].is_string());
        assert!(js(s.app_memory())["intentions"].as_array().unwrap().is_empty());
        let c = s.db().unwrap().intention_create("c", "", far()).unwrap();
        assert!(set_state(&s, c, "expiree").is_err());
    }

    #[test]
    fn set_state_on_expired_is_clear() {
        let s = srv();
        let past = now_local() - Duration::hours(1);
        let id = s.db().unwrap().intention_create("x", "", past).unwrap();
        let v = js(set_state(&s, id, "acceptee"));
        assert!(v["message"].as_str().unwrap().contains("échue"));
        assert_eq!(v["outcome"], "stale");
        // Déjà traitée, ou inconnue : même issue.
        let id = s.db().unwrap().intention_create("y", "", far()).unwrap();
        set_state(&s, id, "refusee").unwrap();
        assert_eq!(js(set_state(&s, id, "acceptee"))["outcome"], "stale");
        assert_eq!(js(set_state(&s, 9999, "acceptee"))["outcome"], "stale");
    }

    #[test]
    fn intention_add_with_days() {
        let s = srv();
        let v = js(s.app_intention_add(Parameters(AppIntentionAddArgs { what: " payer ".into(), days: 3 })));
        assert!(v["id"].is_i64() && v["message"].is_string());
        let open = s.db().unwrap().intentions_open(now_local()).unwrap();
        assert_eq!(open[0].what, "payer");
        let d = open[0].expires_at - now_local();
        assert!(d > Duration::days(3) - Duration::minutes(1) && d <= Duration::days(3));
        let today = js(s.app_intention_add(Parameters(AppIntentionAddArgs { what: "t".into(), days: 0 })));
        assert!(today["id"].is_i64());
        for (what, days) in [("", 1), ("  ", 1), ("x", -1), ("x", 99999)] {
            assert!(s.app_intention_add(Parameters(AppIntentionAddArgs { what: what.into(), days })).is_err());
        }
    }

    #[test]
    fn journal_add_from_app() {
        let s = srv();
        let v = js(s.app_journal_add(Parameters(AppTextArgs { text: "ma note".into() })));
        let id = v["id"].as_i64().unwrap();
        let e = &s.db().unwrap().journal_recent(1).unwrap()[0];
        assert_eq!((e.id, e.provenance.as_str()), (id, "application"));
        assert_eq!(e.sensitivity, APP_SENSITIVITY);
        assert_eq!(entry_text(&e.content), "ma note");
        assert!(s.app_journal_add(Parameters(AppTextArgs { text: "  ".into() })).is_err());
    }

    #[test]
    fn search_escapes_fts_syntax() {
        let s = srv();
        jw(&s, "a AND b", Sensitivity::Commune);
        for bad in ["\"AND* -x", "NEAR(", "a OR", "col:x", "\"", "(("] {
            assert!(s.app_journal_search(q(bad)).is_ok(), "{bad}");
            assert!(s.app_conversation_search(q(bad)).is_ok(), "{bad}");
        }
        assert_eq!(js(s.app_journal_search(q("a AND"))).as_array().unwrap().len(), 1);
        assert!(js(s.app_journal_search(q("   "))).as_array().unwrap().is_empty());
    }

    #[test]
    fn fact_history_ordered() {
        let s = srv();
        for v in ["un", "deux", "trois"] {
            s.app_fact_set(Parameters(AppFactSetArgs { key: "k".into(), value: v.into() })).unwrap();
        }
        let h = js(s.app_fact_history(Parameters(KeyArgs { key: "k".into() })));
        let vals: Vec<_> = h.as_array().unwrap().iter().map(|x| x["value"].as_str().unwrap()).collect();
        assert_eq!(vals, ["un", "deux", "trois"]);
        assert!(h[0]["at"].is_string());
        assert_eq!(js(s.app_memory())["facts"][0]["value"], "trois");
    }

    #[test]
    fn fact_set_rejects_empty() {
        let s = srv();
        for (k, v) in [("k", ""), ("k", "   "), ("", "v")] {
            assert!(s.app_fact_set(Parameters(AppFactSetArgs { key: k.into(), value: v.into() })).is_err());
        }
        assert!(js(s.app_memory())["facts"].as_array().unwrap().is_empty());
    }

    #[test]
    fn persona_hidden_from_app() {
        let s = srv();
        s.db().unwrap().fact_set(PERSONA_KEY, "calme", "test").unwrap();
        assert!(js(s.app_memory())["facts"].as_array().unwrap().is_empty());
        assert!(s.app_fact_set(Parameters(AppFactSetArgs { key: " persona ".into(), value: "ok".into() })).is_err());
        assert!(s.app_fact_history(Parameters(KeyArgs { key: PERSONA_KEY.into() })).is_err());
        assert_eq!(s.db().unwrap().fact_get(PERSONA_KEY).unwrap().unwrap().value, "calme");
    }

    #[test]
    fn llm_fact_set_refuses_persona() {
        let s = srv();
        s.db().unwrap().fact_set(PERSONA_KEY, "calme", "hote").unwrap();
        for k in ["persona", " persona ", "PERSONA"] {
            let a = FactSetArgs { key: k.into(), value: "autre".into(), provenance: "x".into() };
            assert!(s.fact_set(Parameters(a)).is_err(), "{k}");
        }
        assert_eq!(s.db().unwrap().fact_get(PERSONA_KEY).unwrap().unwrap().value, "calme");
        let a = FactSetArgs { key: "gout".into(), value: "thé".into(), provenance: "x".into() };
        assert!(s.fact_set(Parameters(a)).is_ok());
    }

    #[test]
    fn app_erasure_tools() {
        let s = srv();
        let j = jw(&s, "à oublier", Sensitivity::Strategique);
        assert!(js(s.app_journal_delete(Parameters(IdArgs { id: j })))["message"].is_string());
        assert!(s.app_journal_delete(Parameters(IdArgs { id: j })).is_err());
        assert!(js(s.app_memory())["journal"].as_array().unwrap().is_empty());

        s.app_fact_set(Parameters(AppFactSetArgs { key: "k".into(), value: "a".into() })).unwrap();
        s.app_fact_set(Parameters(AppFactSetArgs { key: "k".into(), value: "b".into() })).unwrap();
        assert!(js(s.app_fact_forget(Parameters(KeyArgs { key: " k ".into() })))["message"].is_string());
        assert!(js(s.app_fact_history(Parameters(KeyArgs { key: "k".into() }))).as_array().unwrap().is_empty());
        assert!(s.app_fact_forget(Parameters(KeyArgs { key: "k".into() })).is_err());

        s.db().unwrap().fact_set(PERSONA_KEY, "calme", "hote").unwrap();
        assert!(s.app_fact_forget(Parameters(KeyArgs { key: "persona".into() })).is_err());
        assert!(s.db().unwrap().fact_get(PERSONA_KEY).unwrap().is_some());

        let c = NewConversation {
            channel: "voix".into(),
            device: "d".into(),
            started_at: now_local(),
            ended_at: now_local(),
            transcript: "parlons du jardin".into(),
            session_id: None,
        };
        let id = s.db().unwrap().conversation_ingest(&c).unwrap();
        assert!(js(s.app_conversation_delete(Parameters(IdArgs { id })))["message"].is_string());
        assert!(js(s.app_conversation_search(q("jardin"))).as_array().unwrap().is_empty());
        assert!(s.app_conversation_delete(Parameters(IdArgs { id })).is_err());
    }

    #[test]
    fn app_forgets_deduction_only_key() {
        let s = srv();
        s.db().unwrap().fact_set("goûts", "variés", "x").unwrap();
        let indices = [crate::store::Indice { key: "goûts.loisir".into(), value: "poterie".into() }];
        s.db().unwrap().appliquer_extraction(now_local().date(), &indices, now_local()).unwrap();
        assert!(js(s.app_fact_forget(Parameters(KeyArgs { key: "goûts.loisir".into() })))["message"].is_string());
        assert!(s.app_fact_forget(Parameters(KeyArgs { key: "goûts.loisir".into() })).is_err());
    }

    #[test]
    fn conversation_search_shape() {
        let s = srv();
        let c = NewConversation {
            channel: "voix".into(),
            device: "d".into(),
            started_at: now_local(),
            ended_at: now_local(),
            transcript: "parlons du jardin".into(),
            session_id: None,
        };
        let id = s.db().unwrap().conversation_ingest(&c).unwrap();
        let v = js(s.app_conversation_search(q("jardin")));
        assert_eq!(v[0]["id"], id);
        assert!(v[0]["at"].is_string() && v[0]["topics"].is_array());
        assert!(v[0].get("summary").is_none());
        s.db().unwrap().conversation_set_summary(id, "bilan", &[], &[]).unwrap();
        assert_eq!(js(s.app_conversation_search(q("jardin")))[0]["summary"], "bilan");
    }

    #[test]
    fn pending_step_shows_in_morning_brief() {
        let s = srv();
        // Instant figé : le test ne dépend pas de l'heure du lancement.
        let now = NaiveDate::from_ymd_opt(2026, 11, 9).unwrap().and_hms_opt(7, 0, 0).unwrap();
        let noter = |etape: &str, statut: &str, jour: Option<NaiveDate>| s.db().unwrap().nuit_noter(etape, statut, jour, now).unwrap();
        for etape in crate::store::ETAPES_NUIT {
            noter(etape, "ok", None);
        }
        assert_eq!(s.brief_at(now, BriefPeriod::Matin).unwrap(), "");
        noter("extraction", "modele_indisponible", NaiveDate::from_ymd_opt(2026, 11, 3));
        let b = s.brief_at(now, BriefPeriod::Matin).unwrap();
        assert!(b.contains("Mémoire : extraction en attente depuis le 04/11/2026 — modèle indisponible"), "{b}");
        assert!(!b.contains("condensation"), "{b}");
        noter("oubli", "echec", None);
        assert!(s.brief_at(now, BriefPeriod::Matin).unwrap().contains("Mémoire : oubli en attente depuis le 09/11/2026 — échec"));
        noter("condensation", "sortie_invalide", None);
        assert!(s.brief_at(now, BriefPeriod::Matin).unwrap().contains("condensation en attente depuis le 09/11/2026 — réponse du modèle inutilisable"));
        // Rattrapée : la ligne disparaît.
        noter("extraction", "ok", NaiveDate::from_ymd_opt(2026, 11, 8));
        assert!(!s.brief_at(now, BriefPeriod::Matin).unwrap().contains("extraction"));
        // Conversation sans résumé depuis plus d'une semaine, sans rien en révéler.
        let vieux = now - Duration::days(9);
        let c = NewConversation {
            channel: "voix".into(),
            device: "d".into(),
            started_at: vieux,
            ended_at: vieux,
            transcript: "secret du jardin".into(),
            session_id: None,
        };
        s.db().unwrap().conversation_ingest(&c).unwrap();
        let b = s.brief_at(now, BriefPeriod::Matin).unwrap();
        assert!(b.contains("Mémoire : 1 conversation(s) à résumer depuis plus d'une semaine"), "{b}");
        assert!(!b.contains("jardin"));
    }

    #[test]
    fn fresh_store_brief_is_silent() {
        let s = srv();
        assert_eq!(s.brief_section(Parameters(serde_json::from_value(json!({"period": "matin"})).unwrap())).unwrap(), "");
    }

    #[test]
    fn stale_step_is_reported() {
        let s = srv();
        let now = NaiveDate::from_ymd_opt(2026, 11, 9).unwrap().and_hms_opt(7, 0, 0).unwrap();
        for etape in crate::store::ETAPES_NUIT {
            s.db().unwrap().nuit_noter(etape, "ok", None, now - Duration::hours(35)).unwrap();
        }
        assert_eq!(s.brief_at(now, BriefPeriod::Matin).unwrap(), "");
        s.db().unwrap().nuit_noter("oubli", "ok", None, now - Duration::hours(37)).unwrap();
        let b = s.brief_at(now, BriefPeriod::Matin).unwrap();
        assert_eq!(b, "- Mémoire : oubli n'a pas tourné depuis le 07/11/2026");
        // En échec et arrêtée : les deux se voient.
        s.db().unwrap().nuit_noter("oubli", "echec", None, now - Duration::hours(37)).unwrap();
        let b = s.brief_at(now, BriefPeriod::Matin).unwrap();
        assert!(b.contains("oubli en attente") && b.contains("oubli n'a pas tourné"), "{b}");
    }

    #[test]
    fn unreadable_state_keeps_the_brief() {
        let chemin = std::env::temp_dir().join(format!("memoire-etat-{}.db", std::process::id()));
        let _ = std::fs::remove_file(&chemin);
        let p = chemin.to_str().unwrap();
        let s = MemoryServer::new(Arc::new(Mutex::new(MemoryStore::open(p).unwrap())));
        let now = now_local();
        s.db().unwrap().intention_create("arroser", "", now + Duration::hours(2)).unwrap();
        rusqlite::Connection::open(p)
            .unwrap()
            .execute("UPDATE nuit_etat SET derniere_execution = 'secret', statut = 'secret' WHERE etape = 'oubli'", [])
            .unwrap();
        let b = s.brief_at(now, BriefPeriod::Matin).unwrap();
        let _ = std::fs::remove_file(&chemin);
        assert!(b.contains("arroser"), "{b}");
        assert!(b.contains("- Mémoire : état de nuit illisible"), "{b}");
        assert!(!b.contains("secret"), "{b}");
    }

    #[test]
    fn night_tools_shape() {
        let s = srv();
        let j = now_local().format("%Y-%m-%d").to_string();
        jw(&s, "promenade", Sensitivity::Commune);
        let v = js(s.nuit_episodes(Parameters(JourArgs { jour: j.clone() })));
        assert_eq!(v[0]["nature"], "journal");
        assert!(v[0]["texte"].as_str().unwrap().contains("promenade"));
        assert!(s.nuit_episodes(Parameters(JourArgs { jour: "hier".into() })).is_err());
        assert_eq!(js(s.nuit_en_attente(Parameters(JourArgs { jour: j.clone() }))), json!({"conversations": 0}));

        s.db().unwrap().fact_set("ville", "Ville-A", "x").unwrap();
        let indices = vec![Indice { key: "ville.quartier".into(), value: "Nord".into() }, Indice { key: "ville".into(), value: "Ville-B".into() }];
        let v = js(s.nuit_appliquer(Parameters(AppliquerArgs { jour: j.clone(), indices })));
        assert_eq!((v["deduits"].as_u64(), v["questions"].as_u64()), (Some(1), Some(1)));
        assert_eq!(js(s.nuit_cles()), json!(["ville", "ville.quartier"]));

        assert_eq!(js(s.nuit_condensables(Parameters(MaxArgs { max: 5 }))), json!([]));
        let a = CondenserArgs { niveau: "semaine".into(), periode: "2020-W01".into(), texte: "t".into(), empreinte: "x".into() };
        assert_eq!(js(s.nuit_condenser(Parameters(a))), json!({"id": null}));
        assert_eq!(js(s.nuit_oublier()), json!({"supprimes": 0}));

        assert_eq!(js(s.nuit_etat()).as_array().unwrap().len(), 4);
        let a = NoterArgs { etape: "extraction".into(), statut: "ok".into(), dernier_jour: Some("2026-11-08".into()) };
        assert!(js(s.nuit_noter(Parameters(a)))["message"].is_string());
        let e = &js(s.nuit_etat())[1];
        assert_eq!((e["etape"].as_str(), e["dernier_jour"].as_str(), e["statut"].as_str()), (Some("extraction"), Some("2026-11-08"), Some("ok")));
        assert!(e["derniere_execution"].is_string());
        let a = NoterArgs { etape: "rêve".into(), statut: "ok".into(), dernier_jour: None };
        assert!(s.nuit_noter(Parameters(a)).is_err());
    }

    fn obs_soir(valeur: &str) -> Observation {
        Observation {
            quoi: "repas.lieu".into(),
            contexte: "soir.ouvre".into(),
            valeur: valeur.into(),
            libelle: format!("repas du soir : {valeur}"),
            cadence: "quotidien".into(),
        }
    }

    #[test]
    fn night_habits_tool_shape() {
        let s = srv();
        let sources = BTreeMap::from([("test".to_string(), vec![obs_soir("maison")])]);
        let v = js(s.nuit_habitudes(Parameters(NuitHabitudesArgs { jour: "2026-11-09".into(), sources })));
        for k in ["complet", "appliquees", "nees", "changements", "retours", "ignorees"] {
            assert!(v.get(k).is_some(), "{k} : {v}");
        }
        assert_eq!(v["appliquees"], 1);
        assert!(s.nuit_habitudes(Parameters(NuitHabitudesArgs { jour: "hier".into(), sources: BTreeMap::new() })).is_err());
        let e = s.app_habitude_forget(Parameters(IdArgs { id: 999 })).unwrap_err();
        assert_eq!(e, "habitude inconnue");
    }

    #[test]
    fn habits_step_pending_shows_in_brief() {
        let s = srv();
        let now = NaiveDate::from_ymd_opt(2026, 11, 9).unwrap().and_hms_opt(7, 0, 0).unwrap();
        for etape in crate::store::ETAPES_NUIT {
            s.db().unwrap().nuit_noter(etape, "ok", None, now).unwrap();
        }
        s.db().unwrap().nuit_noter("habitudes", "source_indisponible", NaiveDate::from_ymd_opt(2026, 11, 6), now).unwrap();
        let b = s.brief_at(now, BriefPeriod::Matin).unwrap();
        assert!(b.contains("habitudes en attente depuis le") && b.contains("source indisponible"), "{b}");
    }

    #[test]
    fn new_habit_announced_in_morning_brief_only() {
        let s = srv();
        let lundi = NaiveDate::from_ymd_opt(2026, 11, 2).unwrap();
        let mut nuit = lundi.and_hms_opt(3, 0, 0).unwrap();
        for n in 0..9u64 {
            let jour = lundi + chrono::Days::new(n);
            if jour.weekday().num_days_from_monday() >= 5 {
                continue;
            }
            nuit = (jour + chrono::Days::new(1)).and_hms_opt(3, 0, 0).unwrap();
            let sources = BTreeMap::from([("test".to_string(), vec![obs_soir("maison")])]);
            s.db().unwrap().appliquer_observations(jour, &sources, nuit).unwrap();
        }
        let matin = nuit + Duration::hours(4);
        let b = s.brief_at(matin, BriefPeriod::Matin).unwrap();
        assert!(b.contains("- Habitude remarquée : repas du soir : maison — à confirmer"), "{b}");
        assert!(!s.brief_at(matin, BriefPeriod::Soir).unwrap().contains("Habitude remarquée : "));
        let q = s.db().unwrap().intentions_open(matin).unwrap()[0].id;
        s.db().unwrap().intention_set_state(q, IntentionState::Refusee).unwrap();
        assert!(!s.brief_at(matin, BriefPeriod::Matin).unwrap().contains("Habitude remarquée"));
    }
}
