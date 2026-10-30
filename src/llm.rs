use serde::{Deserialize, Serialize};

use crate::calendar::USER_TZ;
use crate::redact::Redactor;
use crate::storage::Storage;
use crate::config::{LlmConfig, LlmProtocol, LlmRoutingConfig, Placement};

/// Nature de la donnée soumise au modèle, déclarée par l'appelant, jamais
/// devinée. `Strategic` couvre aussi ce qui touche la surface exposée de
/// l'utilisateur.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sensitivity {
    Strategic,
    Common,
}

/// Délai attendu sur la réponse. N'influence pas le choix du moteur ; sert au
/// dimensionnement des temporisations et apparaît dans les journaux.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Urgency {
    Interactive,
    Deferred,
}

/// Voie retenue par le routeur.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Engine {
    SelfHosted,
    External,
}

/// Moteur retenu pour une génération, avec son nom et son plafond éventuel.
#[derive(Debug)]
pub struct RouteChoice<'a> {
    pub config: &'a LlmConfig,
    pub engine: Engine,
    pub engine_name: String,
    pub daily_token_cap: Option<u64>,
}

/// Applique les deux voies déclarées en configuration. Une génération
/// stratégique ne sort jamais vers le service externe, sauf concession
/// explicite de l'opérateur, journalisée à chaque application.
pub struct LlmRouter {
    config: LlmRoutingConfig,
    redactor: std::sync::Arc<Redactor>,
    db_path: Option<std::path::PathBuf>,
}

impl LlmRouter {
    pub fn from_config(config: LlmRoutingConfig) -> Self {
        let redactor = std::sync::Arc::new(Redactor::new(&config.redact_terms));
        Self { config, redactor, db_path: None }
    }

    /// Fichier absent ou illisible : la voie auto-hébergée reste pilotable par
    /// l'environnement, aucune voie externe n'est déduite — un service externe
    /// ne s'active que par une déclaration complète.
    pub fn load(path: impl AsRef<std::path::Path>) -> Self {
        let mut config = LlmRoutingConfig::from_file(path).unwrap_or_else(|e| {
            tracing::warn!("configuration de routage ignorée : {e}");
            LlmRoutingConfig::default()
        });

        if !config.self_hosted.as_ref().is_some_and(LlmConfig::is_usable)
            && let Ok(endpoint) = std::env::var("OLLAMA_URL")
        {
            config.self_hosted = Some(LlmConfig {
                endpoint,
                model: std::env::var("OLLAMA_MODEL").unwrap_or_default(),
                protocol: LlmProtocol::Ollama,
                api_key: None,
                ..Default::default()
            });
        }

        let mut router = Self::from_config(config);
        router.db_path = Some(Storage::default_path().into());
        router
    }

    fn usable(voie: &Option<LlmConfig>) -> Option<&LlmConfig> {
        voie.as_ref().filter(|c| c.is_usable())
    }

    /// `usage` nomme l'appelant : il sert au journal et rattache une éventuelle
    /// concession. Un usage associé à un moteur nommé le suit ; sinon le
    /// routage historique s'applique.
    fn route(&self, usage: &str, sensitivity: Sensitivity) -> anyhow::Result<RouteChoice<'_>> {
        let Some(name) = self.config.usages.get(usage) else {
            return self.route_legacy(usage, sensitivity);
        };
        let engine = self
            .config
            .engines
            .get(name)
            .filter(|e| e.llm.is_usable())
            .ok_or_else(|| {
                anyhow::anyhow!("usage {usage} : moteur {name} absent ou inutilisable")
            })?;

        match (engine.placement, sensitivity) {
            (Placement::SelfHosted, _) => Ok(RouteChoice {
                config: &engine.llm,
                engine: Engine::SelfHosted,
                engine_name: name.clone(),
                daily_token_cap: engine.daily_token_cap,
            }),
            (Placement::External, Sensitivity::Common) => Ok(RouteChoice {
                config: &engine.llm,
                engine: Engine::External,
                engine_name: name.clone(),
                daily_token_cap: engine.daily_token_cap,
            }),
            (Placement::External, Sensitivity::Strategic) => {
                if self.conceded(usage) {
                    tracing::warn!(
                        "usage {usage} : concession déclarée en configuration, génération \
                         stratégique confiée au moteur {name}"
                    );
                    return Ok(RouteChoice {
                        config: &engine.llm,
                        engine: Engine::External,
                        engine_name: name.clone(),
                        daily_token_cap: engine.daily_token_cap,
                    });
                }
                self.self_hosted_strategic(usage)
            }
        }
    }

    fn conceded(&self, usage: &str) -> bool {
        self.config.strategic_concessions.iter().any(|u| u == usage)
    }

    fn self_hosted_strategic(&self, usage: &str) -> anyhow::Result<RouteChoice<'_>> {
        Self::usable(&self.config.self_hosted)
            .map(|config| RouteChoice {
                config,
                engine: Engine::SelfHosted,
                engine_name: "self_hosted".into(),
                daily_token_cap: None,
            })
            .ok_or_else(|| {
                anyhow::anyhow!(
                    "usage {usage} : moteur auto-hébergé indisponible, une génération \
                     stratégique ne se déporte pas"
                )
            })
    }

    fn route_legacy(
        &self,
        usage: &str,
        sensitivity: Sensitivity,
    ) -> anyhow::Result<RouteChoice<'_>> {
        let external = Self::usable(&self.config.external);

        match sensitivity {
            Sensitivity::Strategic => {
                if self.conceded(usage) && let Some(config) = external {
                    tracing::warn!(
                        "usage {usage} : concession déclarée en configuration, génération \
                         stratégique confiée au service externe"
                    );
                    return Ok(RouteChoice {
                        config,
                        engine: Engine::External,
                        engine_name: "external".into(),
                        daily_token_cap: None,
                    });
                }
                self.self_hosted_strategic(usage)
            }
            Sensitivity::Common => external
                .map(|config| RouteChoice {
                    config,
                    engine: Engine::External,
                    engine_name: "external".into(),
                    daily_token_cap: None,
                })
                .or_else(|| {
                    Self::usable(&self.config.self_hosted).map(|config| RouteChoice {
                        config,
                        engine: Engine::SelfHosted,
                        engine_name: "self_hosted".into(),
                        daily_token_cap: None,
                    })
                })
                .ok_or_else(|| anyhow::anyhow!("usage {usage} : aucun moteur configuré")),
        }
    }

    pub fn client(
        &self,
        usage: &str,
        sensitivity: Sensitivity,
        urgency: Urgency,
    ) -> anyhow::Result<LlmClient> {
        let choice = self.route(usage, sensitivity)?;
        tracing::info!(
            "routage {usage} : {sensitivity:?}/{urgency:?} → {:?} ({})",
            choice.engine,
            choice.engine_name
        );
        // Le masquage n'est branché que sur la voie externe.
        let redactor = (choice.engine == Engine::External).then(|| self.redactor.clone());
        let mut client = LlmClient::from_config(choice.config).with_redactor(redactor);
        if let Some(db_path) = &self.db_path {
            let guard = SpendGuard { db_path: db_path.clone() };
            client = client.with_spend_guard(guard, &choice.engine_name, usage, choice.daily_token_cap);
        }
        Ok(client)
    }
}

/// Plafond quotidien de jetons par moteur, comptabilisé en base. Connexion
/// courte à chaque appel : le garde ne retient aucun état.
pub struct SpendGuard {
    db_path: std::path::PathBuf,
}

impl SpendGuard {
    /// Jour courant en heure locale de l'utilisateur.
    fn today() -> chrono::NaiveDate {
        chrono::Utc::now().with_timezone(&USER_TZ).date_naive()
    }

    fn open(&self) -> anyhow::Result<Storage> {
        Ok(Storage::open(&self.db_path.to_string_lossy())?)
    }

    /// Refuse quand la consommation du jour a atteint le plafond.
    pub fn check(&self, engine: &str, cap: Option<u64>, today: chrono::NaiveDate) -> anyhow::Result<()> {
        let Some(cap) = cap else { return Ok(()) };
        let used = self.open()?.llm_tokens_on(today, engine)?;
        if used >= cap {
            anyhow::bail!("plafond quotidien atteint pour le moteur {engine} ({used}/{cap} jetons)");
        }
        Ok(())
    }

    /// Un échec d'enregistrement ne fait jamais échouer l'appel de l'utilisateur.
    pub fn record(&self, engine: &str, usage: &str, today: chrono::NaiveDate, t: &TokenUsage) {
        let res = self.open().and_then(|s| Ok(s.record_llm_usage(today, engine, usage, t)?));
        if let Err(e) = res {
            tracing::warn!("comptage des jetons non enregistré : {e}");
        }
    }
}

struct Spend {
    guard: SpendGuard,
    engine: String,
    usage: String,
    cap: Option<u64>,
}

#[derive(Debug, Serialize)]
struct OllamaRequest {
    model: String,
    prompt: String,
    stream: bool,
}

#[derive(Debug, Deserialize)]
struct OllamaResponse {
    response: String,
}

#[derive(Debug, Serialize)]
struct ChatRequest {
    model: String,
    messages: Vec<ChatMessage>,
    stream: bool,
}

#[derive(Debug, Serialize, Deserialize)]
struct ChatMessage {
    role: String,
    content: String,
}

#[derive(Debug, Deserialize)]
struct ChatResponse {
    choices: Vec<ChatChoice>,
}

#[derive(Debug, Deserialize)]
struct ChatChoice {
    message: ChatMessage,
}

/// Tour de conversation, indépendant du protocole.
// Construit par la boucle d'agent (tâche ultérieure du plan).
#[allow(dead_code)]
#[derive(Debug, Clone)]
pub enum ChatTurn {
    User(String),
    Assistant {
        text: Option<String>,
        tool_calls: Vec<ToolCall>,
    },
    ToolResult {
        call_id: String,
        content: String,
        is_error: bool,
    },
}

/// Outil proposé au modèle ; `input_schema` est un schéma JSON.
#[derive(Debug, Clone)]
pub struct ToolSpec {
    pub name: String,
    pub description: String,
    pub input_schema: serde_json::Value,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub arguments: serde_json::Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TokenUsage {
    pub input: u64,
    pub output: u64,
}

// Lue par la boucle d'agent et la comptabilité des jetons (tâches ultérieures).
#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct ChatReply {
    pub text: Option<String>,
    pub tool_calls: Vec<ToolCall>,
    pub usage: TokenUsage,
}

#[derive(Debug, Deserialize)]
struct WireChatResponse {
    choices: Vec<WireChoice>,
    usage: Option<WireUsage>,
}

#[derive(Debug, Deserialize)]
struct WireChoice {
    message: WireMessage,
}

#[derive(Debug, Deserialize)]
struct WireMessage {
    content: Option<String>,
    #[serde(default, deserialize_with = "null_as_empty")]
    tool_calls: Vec<WireToolCall>,
}

fn null_as_empty<'de, D, T>(d: D) -> Result<Vec<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Ok(Option::<Vec<T>>::deserialize(d)?.unwrap_or_default())
}

#[derive(Debug, Deserialize)]
struct WireToolCall {
    id: String,
    function: WireFunction,
}

#[derive(Debug, Deserialize)]
struct WireFunction {
    name: String,
    arguments: String,
}

#[derive(Debug, Deserialize)]
struct WireUsage {
    #[serde(default)]
    prompt_tokens: u64,
    #[serde(default)]
    completion_tokens: u64,
}

/// Plafond de génération par appel de conversation (protocole `messages`).
pub const CHAT_MAX_TOKENS: u32 = 4096;

pub struct LlmClient {
    endpoint: String,
    model: String,
    protocol: LlmProtocol,
    api_key: Option<String>,
    api_key_header: Option<String>,
    headers: Vec<(String, String)>,
    redactor: Option<std::sync::Arc<Redactor>>,
    spend: Option<Spend>,
    client: reqwest::Client,
}

impl LlmClient {
    pub fn from_config(config: &LlmConfig) -> Self {
        Self {
            endpoint: config.endpoint.clone(),
            model: config.model.clone(),
            protocol: config.protocol,
            api_key: config.api_key.clone(),
            api_key_header: config.api_key_header.clone(),
            headers: config.headers.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
            redactor: None,
            spend: None,
            client: reqwest::Client::new(),
        }
    }

    /// Masquage appliqué à tout texte sortant ; `None` = envoi tel quel.
    pub fn with_redactor(mut self, redactor: Option<std::sync::Arc<Redactor>>) -> Self {
        self.redactor = redactor;
        self
    }

    /// Branche le plafond quotidien : vérifié avant l'envoi, comptabilisé après.
    pub fn with_spend_guard(
        mut self,
        guard: SpendGuard,
        engine: &str,
        usage: &str,
        cap: Option<u64>,
    ) -> Self {
        self.spend = Some(Spend { guard, engine: engine.into(), usage: usage.into(), cap });
        self
    }

    fn check_cap(&self) -> anyhow::Result<()> {
        match &self.spend {
            Some(s) => s.guard.check(&s.engine, s.cap, SpendGuard::today()),
            None => Ok(()),
        }
    }

    fn record_usage(&self, t: &TokenUsage) {
        if let Some(s) = &self.spend {
            s.guard.record(&s.engine, &s.usage, SpendGuard::today(), t);
        }
    }

    /// Jetons d'une réponse de génération ; le protocole natif n'en compte pas (0).
    fn body_usage(&self, body: &str) -> TokenUsage {
        let zero = TokenUsage { input: 0, output: 0 };
        match self.protocol {
            LlmProtocol::Ollama => zero,
            LlmProtocol::Messages => self.parse_messages_reply(body).map_or(zero, |r| r.usage),
            LlmProtocol::OpenaiCompatible => {
                let v: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
                let n = |k: &str| v["usage"][k].as_u64().unwrap_or(0);
                TokenUsage { input: n("prompt_tokens"), output: n("completion_tokens") }
            }
        }
    }

    fn mask(&self, text: &str) -> String {
        match &self.redactor {
            Some(r) => r.redact(text),
            None => text.to_string(),
        }
    }

    /// Masque toutes les chaînes d'une valeur JSON, à tout niveau.
    fn mask_json(&self, v: &serde_json::Value) -> serde_json::Value {
        use serde_json::Value;
        match v {
            Value::String(s) => Value::String(self.mask(s)),
            Value::Array(a) => Value::Array(a.iter().map(|x| self.mask_json(x)).collect()),
            Value::Object(o) => Value::Object(o.iter().map(|(k, x)| (k.clone(), self.mask_json(x))).collect()),
            other => other.clone(),
        }
    }

    /// Copie masquée des consignes et des tours, arguments d'appel compris ;
    /// noms d'outils et schémas restent intacts.
    fn masked(&self, system: &str, turns: &[ChatTurn]) -> (String, Vec<ChatTurn>) {
        let turns = turns
            .iter()
            .map(|t| match t {
                ChatTurn::User(text) => ChatTurn::User(self.mask(text)),
                ChatTurn::Assistant { text, tool_calls } => ChatTurn::Assistant {
                    text: text.as_deref().map(|t| self.mask(t)),
                    tool_calls: tool_calls
                        .iter()
                        .map(|c| ToolCall { arguments: self.mask_json(&c.arguments), ..c.clone() })
                        .collect(),
                },
                ChatTurn::ToolResult { call_id, content, is_error } => ChatTurn::ToolResult {
                    call_id: call_id.clone(),
                    content: self.mask(content),
                    is_error: *is_error,
                },
            })
            .collect();
        (self.mask(system), turns)
    }

    /// Request path and JSON body for the configured protocol.
    fn build_request(&self, prompt: &str) -> anyhow::Result<(String, serde_json::Value)> {
        if self.protocol == LlmProtocol::Messages {
            // Le masquage a lieu une seule fois, dans build_chat_request.
            return self.build_chat_request("", &[ChatTurn::User(prompt.to_string())], &[]);
        }
        let prompt = &self.mask(prompt);
        Ok(match self.protocol {
            LlmProtocol::Messages => unreachable!("traité ci-dessus"),
            LlmProtocol::Ollama => (
                "/api/generate".to_string(),
                serde_json::to_value(OllamaRequest {
                    model: self.model.clone(),
                    prompt: prompt.to_string(),
                    stream: false,
                })?,
            ),
            LlmProtocol::OpenaiCompatible => (
                "/v1/chat/completions".to_string(),
                serde_json::to_value(ChatRequest {
                    model: self.model.clone(),
                    messages: vec![ChatMessage {
                        role: "user".into(),
                        content: prompt.to_string(),
                    }],
                    stream: false,
                })?,
            ),
        })
    }

    /// Extract the generated text, or surface the endpoint's failure verbatim.
    fn parse_response(&self, status: reqwest::StatusCode, body: &str) -> anyhow::Result<String> {
        if !status.is_success() {
            let excerpt: String = body.chars().take(500).collect();
            anyhow::bail!("réponse d'inférence en échec ({status}) : {excerpt}");
        }

        match self.protocol {
            LlmProtocol::Messages => Ok(self.parse_chat_response(status, body)?.text.unwrap_or_default()),
            LlmProtocol::Ollama => Ok(serde_json::from_str::<OllamaResponse>(body)?.response),
            LlmProtocol::OpenaiCompatible => {
                let parsed: ChatResponse = serde_json::from_str(body)?;
                let choice = parsed
                    .choices
                    .into_iter()
                    .next()
                    .ok_or_else(|| anyhow::anyhow!("réponse d'inférence sans complétion"))?;
                Ok(choice.message.content)
            }
        }
    }

    /// Jeton (en-tête configurable, porteur par défaut) puis en-têtes fixes.
    fn auth_and_headers(&self) -> Vec<(String, String)> {
        let mut out = Vec::new();
        if let Some(key) = &self.api_key {
            let name = self.api_key_header.as_deref().filter(|n| !n.is_empty()).unwrap_or("Authorization");
            let value = if name.eq_ignore_ascii_case("authorization") {
                format!("Bearer {key}")
            } else {
                key.clone()
            };
            out.push((name.to_string(), value));
        }
        out.extend(self.headers.iter().cloned());
        out
    }

    pub async fn generate(&self, prompt: &str) -> anyhow::Result<String> {
        self.check_cap()?;
        let (path, payload) = self.build_request(prompt)?;

        let mut request = self
            .client
            .post(format!("{}{}", self.endpoint, path))
            .json(&payload);
        for (name, value) in self.auth_and_headers() {
            request = request.header(name, value);
        }

        let resp = request.send().await?;
        let status = resp.status();
        let body = resp.text().await?;
        let text = self.parse_response(status, &body)?;
        self.record_usage(&self.body_usage(&body));
        Ok(text)
    }

    /// Chemin et corps d'une conversation avec outils pour le protocole configuré.
    fn build_chat_request(
        &self,
        system: &str,
        turns: &[ChatTurn],
        tools: &[ToolSpec],
    ) -> anyhow::Result<(String, serde_json::Value)> {
        let (system, turns) = self.masked(system, turns);
        let (system, turns) = (system.as_str(), turns.as_slice());
        match self.protocol {
            LlmProtocol::Ollama => {
                anyhow::bail!("appel d'outils non pris en charge par ce protocole")
            }
            LlmProtocol::Messages => Ok(("/v1/messages".into(), self.messages_body(system, turns, tools))),
            LlmProtocol::OpenaiCompatible => {
                use serde_json::json;
                let mut messages = Vec::new();
                if !system.is_empty() {
                    messages.push(json!({"role": "system", "content": system}));
                }
                for turn in turns {
                    if matches!(turn, ChatTurn::Assistant { text: None, tool_calls } if tool_calls.is_empty()) {
                        continue;
                    }
                    messages.push(match turn {
                        ChatTurn::User(text) => json!({"role": "user", "content": text}),
                        ChatTurn::Assistant { text, tool_calls } => {
                            let mut m = json!({"role": "assistant", "content": text});
                            if !tool_calls.is_empty() {
                                m["tool_calls"] = tool_calls
                                    .iter()
                                    .map(|c| {
                                        json!({
                                            "id": c.id,
                                            "type": "function",
                                            "function": {
                                                "name": c.name,
                                                "arguments": c.arguments.to_string(),
                                            },
                                        })
                                    })
                                    .collect();
                            }
                            m
                        }
                        ChatTurn::ToolResult { call_id, content, is_error } => {
                            let content = if *is_error { format!("Erreur : {content}") } else { content.clone() };
                            json!({"role": "tool", "tool_call_id": call_id, "content": content})
                        }
                    });
                }
                let mut body = json!({"model": self.model, "messages": messages, "stream": false});
                if !tools.is_empty() {
                    body["tools"] = tools
                        .iter()
                        .map(|t| {
                            json!({
                                "type": "function",
                                "function": {
                                    "name": t.name,
                                    "description": t.description,
                                    "parameters": t.input_schema,
                                },
                            })
                        })
                        .collect();
                }
                Ok(("/v1/chat/completions".into(), body))
            }
        }
    }

    /// Corps `messages` : consignes et dernier outil marqués pour la mise en
    /// cache du préfixe ; résultats d'outils consécutifs regroupés en un message.
    fn messages_body(&self, system: &str, turns: &[ChatTurn], tools: &[ToolSpec]) -> serde_json::Value {
        use serde_json::{Value, json};
        let mut messages: Vec<Value> = Vec::new();
        for turn in turns {
            match turn {
                ChatTurn::User(text) => messages.push(json!({"role": "user", "content": text})),
                ChatTurn::Assistant { text, tool_calls } => {
                    let mut blocks = Vec::new();
                    if let Some(t) = text.as_deref().filter(|t| !t.is_empty()) {
                        blocks.push(json!({"type": "text", "text": t}));
                    }
                    blocks.extend(tool_calls.iter().map(|c| {
                        json!({"type": "tool_use", "id": c.id, "name": c.name, "input": c.arguments})
                    }));
                    // Un tour vide serait refusé par l'endpoint.
                    if blocks.is_empty() {
                        continue;
                    }
                    messages.push(json!({"role": "assistant", "content": blocks}));
                }
                ChatTurn::ToolResult { call_id, content, is_error } => {
                    let block = json!({
                        "type": "tool_result", "tool_use_id": call_id,
                        "content": content, "is_error": is_error,
                    });
                    match messages.last_mut() {
                        Some(last) if last["role"] == "user" && last["content"].is_array() => {
                            last["content"].as_array_mut().expect("tableau").push(block);
                        }
                        _ => messages.push(json!({"role": "user", "content": [block]})),
                    }
                }
            }
        }
        let mut body = json!({
            "model": self.model,
            "max_tokens": CHAT_MAX_TOKENS,
            "messages": messages,
        });
        if !system.is_empty() {
            body["system"] = json!([{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]);
        }
        if !tools.is_empty() {
            let mut specs: Vec<Value> = tools
                .iter()
                .map(|t| json!({"name": t.name, "description": t.description, "input_schema": t.input_schema}))
                .collect();
            if let Some(last) = specs.last_mut() {
                last["cache_control"] = json!({"type": "ephemeral"});
            }
            body["tools"] = Value::Array(specs);
        }
        body
    }

    fn parse_messages_reply(&self, body: &str) -> anyhow::Result<ChatReply> {
        let v: serde_json::Value = serde_json::from_str(body)?;
        let blocks = v["content"]
            .as_array()
            .ok_or_else(|| anyhow::anyhow!("réponse d'inférence sans contenu"))?;
        let mut text = String::new();
        let mut tool_calls = Vec::new();
        for b in blocks {
            match b["type"].as_str() {
                Some("text") => text.push_str(b["text"].as_str().unwrap_or_default()),
                Some("tool_use") => tool_calls.push(ToolCall {
                    id: b["id"].as_str().unwrap_or_default().to_string(),
                    name: b["name"].as_str().unwrap_or_default().to_string(),
                    arguments: b["input"].clone(),
                }),
                _ => {}
            }
        }
        let n = |k: &str| v["usage"][k].as_u64().unwrap_or(0);
        let usage = TokenUsage {
            input: n("input_tokens") + n("cache_read_input_tokens") + n("cache_creation_input_tokens"),
            output: n("output_tokens"),
        };
        Ok(ChatReply { text: (!text.is_empty()).then_some(text), tool_calls, usage })
    }

    fn parse_chat_response(
        &self,
        status: reqwest::StatusCode,
        body: &str,
    ) -> anyhow::Result<ChatReply> {
        if !status.is_success() {
            let excerpt: String = body.chars().take(500).collect();
            anyhow::bail!("réponse d'inférence en échec ({status}) : {excerpt}");
        }
        match self.protocol {
            LlmProtocol::Ollama => {
                anyhow::bail!("appel d'outils non pris en charge par ce protocole")
            }
            LlmProtocol::Messages => self.parse_messages_reply(body),
            LlmProtocol::OpenaiCompatible => {
                let parsed: WireChatResponse = serde_json::from_str(body)?;
                let message = parsed
                    .choices
                    .into_iter()
                    .next()
                    .ok_or_else(|| anyhow::anyhow!("réponse d'inférence sans complétion"))?
                    .message;
                let tool_calls = message
                    .tool_calls
                    .into_iter()
                    .map(|c| {
                        Ok(ToolCall {
                            // Un outil sans paramètre peut renvoyer une chaîne vide.
                            arguments: match c.function.arguments.trim() {
                                "" => serde_json::json!({}),
                                a => serde_json::from_str(a)
                                    .map_err(|e| anyhow::anyhow!("arguments d'outil invalides : {e}"))?,
                            },
                            id: c.id,
                            name: c.function.name,
                        })
                    })
                    .collect::<anyhow::Result<Vec<_>>>()?;
                let usage = parsed.usage.map_or(
                    TokenUsage { input: 0, output: 0 },
                    |u| TokenUsage { input: u.prompt_tokens, output: u.completion_tokens },
                );
                Ok(ChatReply { text: message.content, tool_calls, usage })
            }
        }
    }

    // Consommé par la boucle d'agent (tâche ultérieure du plan).
    #[allow(dead_code)]
    pub async fn chat(
        &self,
        system: &str,
        turns: &[ChatTurn],
        tools: &[ToolSpec],
    ) -> anyhow::Result<ChatReply> {
        self.check_cap()?;
        let (path, payload) = self.build_chat_request(system, turns, tools)?;
        let mut request = self
            .client
            .post(format!("{}{}", self.endpoint, path))
            .json(&payload);
        for (name, value) in self.auth_and_headers() {
            request = request.header(name, value);
        }
        let resp = request.send().await?;
        let status = resp.status();
        let body = resp.text().await?;
        let reply = self.parse_chat_response(status, &body)?;
        self.record_usage(&reply.usage);
        Ok(reply)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{EngineConfig, Placement};
    use std::collections::HashMap;

    fn client_with(protocol: LlmProtocol) -> LlmClient {
        LlmClient::from_config(&LlmConfig {
            endpoint: "http://endpoint.invalid".into(),
            model: "test-model".into(),
            protocol,
            api_key: None,
            ..Default::default()
        })
    }

    fn guard_with(consumed: u64) -> (SpendGuard, chrono::NaiveDate) {
        static N: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let path = std::env::temp_dir().join(format!(
            "kairos-spend-{}-{}.db",
            std::process::id(),
            N.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        let _ = std::fs::remove_file(&path);
        let guard = SpendGuard { db_path: path };
        let today = SpendGuard::today();
        if consumed > 0 {
            guard.record("e", "u", today, &TokenUsage { input: consumed, output: 0 });
        }
        (guard, today)
    }

    #[test]
    fn cap_reached_refuses_before_call() {
        let (g, today) = guard_with(20);
        assert!(g.check("e", Some(20), today).is_err());
        let (g, today) = guard_with(19);
        assert!(g.check("e", Some(20), today).is_ok());
    }

    #[test]
    fn no_cap_never_refuses() {
        let (g, today) = guard_with(10_000_000);
        assert!(g.check("e", None, today).is_ok());
    }

    #[tokio::test]
    async fn chat_refuses_at_cap_without_network_call() {
        let (g, _) = guard_with(5);
        let client = LlmClient::from_config(&LlmConfig {
            endpoint: "http://127.0.0.1:9".into(),
            model: "m".into(),
            protocol: LlmProtocol::OpenaiCompatible,
            ..Default::default()
        })
        .with_spend_guard(g, "e", "u", Some(5));
        let err = client.chat("", &[ChatTurn::User("x".into())], &[]).await.unwrap_err();
        assert!(err.to_string().contains("plafond"), "{err}");
        let err = client.generate("x").await.unwrap_err();
        assert!(err.to_string().contains("plafond"), "{err}");
    }

    #[test]
    fn test_build_request_native() {
        let (path, body) = client_with(LlmProtocol::Ollama).build_request("bonjour").unwrap();
        assert_eq!(path, "/api/generate");
        assert_eq!(body["model"], "test-model");
        assert_eq!(body["prompt"], "bonjour");
        assert_eq!(body["stream"], false);
    }

    #[test]
    fn test_build_request_chat() {
        let (path, body) = client_with(LlmProtocol::OpenaiCompatible)
            .build_request("bonjour")
            .unwrap();
        assert_eq!(path, "/v1/chat/completions");
        assert_eq!(body["model"], "test-model");
        assert_eq!(body["stream"], false);
        assert_eq!(body["messages"].as_array().unwrap().len(), 1);
        assert_eq!(body["messages"][0]["role"], "user");
        assert_eq!(body["messages"][0]["content"], "bonjour");
    }

    #[test]
    fn test_parse_response_native() {
        let text = client_with(LlmProtocol::Ollama)
            .parse_response(reqwest::StatusCode::OK, r#"{"response":"salut","done":true}"#)
            .unwrap();
        assert_eq!(text, "salut");
    }

    #[test]
    fn test_parse_response_chat() {
        let body = r#"{"choices":[{"index":0,"message":{"role":"assistant","content":"salut"}}]}"#;
        let text = client_with(LlmProtocol::OpenaiCompatible)
            .parse_response(reqwest::StatusCode::OK, body)
            .unwrap();
        assert_eq!(text, "salut");
    }

    #[test]
    fn test_parse_response_chat_without_choice() {
        let err = client_with(LlmProtocol::OpenaiCompatible)
            .parse_response(reqwest::StatusCode::OK, r#"{"choices":[]}"#)
            .unwrap_err();
        assert!(err.to_string().contains("sans complétion"));
    }

    #[test]
    fn test_parse_response_http_error() {
        let err = client_with(LlmProtocol::OpenaiCompatible)
            .parse_response(
                reqwest::StatusCode::UNAUTHORIZED,
                r#"{"error":{"message":"refusé"}}"#,
            )
            .unwrap_err();
        let msg = err.to_string();
        assert!(msg.contains("401"));
        assert!(msg.contains("refusé"));
    }

    #[test]
    fn test_parse_response_malformed_body() {
        assert!(client_with(LlmProtocol::Ollama)
            .parse_response(reqwest::StatusCode::OK, "pas du json")
            .is_err());
    }

    #[test]
    fn test_protocol_from_toml() {
        let config: LlmConfig = toml::from_str(
            r#"
            endpoint = "http://endpoint.invalid"
            model = "test-model"
            protocol = "openai-compatible"
            api_key = "secret"
            "#,
        )
        .unwrap();
        assert_eq!(config.protocol, LlmProtocol::OpenaiCompatible);
        assert_eq!(config.api_key.as_deref(), Some("secret"));
    }

    fn voie(endpoint: &str) -> LlmConfig {
        LlmConfig {
            endpoint: endpoint.into(),
            model: "test-model".into(),
            protocol: LlmProtocol::Ollama,
            api_key: None,
            ..Default::default()
        }
    }

    fn router(
        self_hosted: Option<LlmConfig>,
        external: Option<LlmConfig>,
        strategic_concessions: Vec<String>,
    ) -> LlmRouter {
        LlmRouter::from_config(LlmRoutingConfig {
            self_hosted,
            external,
            strategic_concessions,
            ..Default::default()
        })
    }

    #[test]
    fn strategic_stays_self_hosted_when_both_paths_exist() {
        let r = router(
            Some(voie("http://interne.invalid")),
            Some(voie("http://externe.invalid")),
            vec![],
        );
        let RouteChoice { config, engine, .. } = r.route("veille", Sensitivity::Strategic).unwrap();
        assert_eq!(engine, Engine::SelfHosted);
        assert_eq!(config.endpoint, "http://interne.invalid");
    }

    /// Sans moteur auto-hébergé, une génération stratégique échoue : elle ne se
    /// reporte jamais sur le service externe, même disponible.
    #[test]
    fn strategic_fails_rather_than_falling_back_to_external() {
        let r = router(None, Some(voie("http://externe.invalid")), vec![]);
        assert!(r.route("veille", Sensitivity::Strategic).is_err());
    }

    /// Une voie à moitié renseignée compte comme absente, pas comme un repli.
    #[test]
    fn strategic_rejects_half_configured_self_hosted() {
        let mut incomplete = voie("http://interne.invalid");
        incomplete.model = "  ".into();
        let r = router(Some(incomplete), Some(voie("http://externe.invalid")), vec![]);
        assert!(r.route("veille", Sensitivity::Strategic).is_err());
    }

    #[test]
    fn declared_concession_sends_strategic_usage_to_external() {
        let r = router(
            Some(voie("http://interne.invalid")),
            Some(voie("http://externe.invalid")),
            vec!["veille".into()],
        );
        let RouteChoice { config, engine, .. } = r.route("veille", Sensitivity::Strategic).unwrap();
        assert_eq!(engine, Engine::External);
        assert_eq!(config.endpoint, "http://externe.invalid");
    }

    /// La concession vaut pour l'usage nommé, pas pour les autres.
    #[test]
    fn concession_does_not_extend_to_other_usages() {
        let r = router(
            Some(voie("http://interne.invalid")),
            Some(voie("http://externe.invalid")),
            vec!["autre".into()],
        );
        let engine = r.route("veille", Sensitivity::Strategic).unwrap().engine;
        assert_eq!(engine, Engine::SelfHosted);
    }

    #[test]
    fn common_prefers_external_then_falls_back() {
        let with_external = router(
            Some(voie("http://interne.invalid")),
            Some(voie("http://externe.invalid")),
            vec![],
        );
        let engine = with_external.route("resume", Sensitivity::Common).unwrap().engine;
        assert_eq!(engine, Engine::External);

        let without_external = router(Some(voie("http://interne.invalid")), None, vec![]);
        let engine = without_external
            .route("resume", Sensitivity::Common)
            .unwrap()
            .engine;
        assert_eq!(engine, Engine::SelfHosted);
    }

    #[test]
    fn no_configured_engine_yields_an_error() {
        let r = router(None, None, vec![]);
        assert!(r.route("resume", Sensitivity::Common).is_err());
        assert!(r.route("resume", Sensitivity::Strategic).is_err());
    }

    /// L'urgence est portée par l'API sans peser sur le choix du moteur.
    #[test]
    fn urgency_does_not_change_the_selected_engine() {
        let r = router(
            Some(voie("http://interne.invalid")),
            Some(voie("http://externe.invalid")),
            vec![],
        );
        for urgency in [Urgency::Interactive, Urgency::Deferred] {
            let client = r
                .client("veille", Sensitivity::Strategic, urgency)
                .unwrap();
            assert_eq!(client.endpoint, "http://interne.invalid");
        }
    }

    #[test]
    fn routing_config_reads_both_paths_and_concessions() {
        let config: LlmRoutingConfig = toml::from_str(
            r#"
            strategic_concessions = ["veille"]

            [self_hosted]
            endpoint = "http://interne.invalid"
            model = "m"

            [external]
            endpoint = "http://externe.invalid"
            model = "m"
            protocol = "openai-compatible"
            api_key = "secret"
            "#,
        )
        .unwrap();
        assert!(config.self_hosted.unwrap().is_usable());
        let external = config.external.unwrap();
        assert_eq!(external.protocol, LlmProtocol::OpenaiCompatible);
        assert_eq!(config.strategic_concessions, vec!["veille".to_string()]);
    }

    /// Le fichier d'exemple ne porte que des placeholders : aucune voie n'en
    /// ressort utilisable, et aucune concession n'y est consentie.
    #[test]
    fn example_routing_file_declares_no_usable_path() {
        let config = LlmRoutingConfig::from_file("config/llm.example.toml").unwrap();
        assert!(!config.self_hosted.unwrap().is_usable());
        assert!(!config.external.unwrap().is_usable());
        assert!(config.strategic_concessions.is_empty());
    }

    #[test]
    fn test_protocol_defaults_when_absent() {
        let config: LlmConfig = toml::from_str(
            r#"
            endpoint = "http://endpoint.invalid"
            model = "test-model"
            "#,
        )
        .unwrap();
        assert_eq!(config.protocol, LlmProtocol::Ollama);
        assert!(config.api_key.is_none());
    }

    fn moteur(endpoint: &str, placement: Placement) -> EngineConfig {
        EngineConfig {
            llm: voie(endpoint),
            placement,
            daily_token_cap: None,
        }
    }

    fn mapped_router(self_hosted: Option<LlmConfig>, concessions: Vec<String>) -> LlmRouter {
        LlmRouter::from_config(LlmRoutingConfig {
            self_hosted,
            strategic_concessions: concessions,
            engines: HashMap::from([(
                "fort".to_string(),
                moteur("http://fort.invalid", Placement::External),
            )]),
            usages: HashMap::from([("conversation".to_string(), "fort".to_string())]),
            ..Default::default()
        })
    }

    #[test]
    fn mapped_usage_uses_named_engine() {
        let r = mapped_router(None, vec![]);
        let choice = r.route("conversation", Sensitivity::Common).unwrap();
        assert_eq!(choice.engine_name, "fort");
        assert_eq!(choice.engine, Engine::External);
        assert_eq!(choice.config.endpoint, "http://fort.invalid");
    }

    #[test]
    fn mapped_external_strategic_without_concession_is_refused() {
        let r = mapped_router(None, vec![]);
        assert!(r.route("conversation", Sensitivity::Strategic).is_err());
    }

    #[test]
    fn mapped_external_strategic_falls_back_to_self_hosted() {
        let r = mapped_router(Some(voie("http://interne.invalid")), vec![]);
        let choice = r.route("conversation", Sensitivity::Strategic).unwrap();
        assert_eq!(choice.engine, Engine::SelfHosted);
        assert_eq!(choice.engine_name, "self_hosted");
    }

    #[test]
    fn mapped_external_strategic_with_concession_goes_external() {
        let r = mapped_router(None, vec!["conversation".into()]);
        let choice = r.route("conversation", Sensitivity::Strategic).unwrap();
        assert_eq!(choice.engine_name, "fort");
        assert_eq!(choice.engine, Engine::External);
    }

    #[test]
    fn mapped_to_unknown_engine_is_error() {
        let r = LlmRouter::from_config(LlmRoutingConfig {
            usages: HashMap::from([("x".to_string(), "absent".to_string())]),
            ..Default::default()
        });
        let err = r.route("x", Sensitivity::Common).unwrap_err();
        assert!(err.to_string().contains("absent"));
    }

    #[test]
    fn unmapped_usage_keeps_legacy_routing() {
        let r = mapped_router(Some(voie("http://interne.invalid")), vec![]);
        let choice = r.route("autre", Sensitivity::Strategic).unwrap();
        assert_eq!(choice.engine_name, "self_hosted");
        assert_eq!(choice.daily_token_cap, None);
    }

    #[test]
    fn engines_from_toml() {
        let config: LlmRoutingConfig = toml::from_str(
            r#"
            [engines.fort]
            endpoint = "http://fort.invalid"
            model = "m"
            placement = "external"
            daily_token_cap = 1000

            [usages]
            conversation = "fort"
            "#,
        )
        .unwrap();
        let fort = &config.engines["fort"];
        assert_eq!(fort.placement, Placement::External);
        assert_eq!(fort.daily_token_cap, Some(1000));
        assert_eq!(fort.llm.model, "m");
        assert_eq!(config.usages["conversation"], "fort");
    }

    fn chat_tools() -> Vec<ToolSpec> {
        vec![ToolSpec {
            name: "chercher".into(),
            description: "cherche".into(),
            input_schema: serde_json::json!({"type":"object","properties":{"q":{"type":"string"}}}),
        }]
    }

    #[test]
    fn openai_chat_request_carries_system_tools_and_tool_results() {
        let turns = vec![
            ChatTurn::User("salut".into()),
            ChatTurn::Assistant {
                text: None,
                tool_calls: vec![ToolCall {
                    id: "c1".into(),
                    name: "chercher".into(),
                    arguments: serde_json::json!({"q":"x"}),
                }],
            },
            ChatTurn::ToolResult {
                call_id: "c1".into(),
                content: "ok".into(),
                is_error: false,
            },
        ];
        let (path, body) = client_with(LlmProtocol::OpenaiCompatible)
            .build_chat_request("sys", &turns, &chat_tools())
            .unwrap();
        assert_eq!(path, "/v1/chat/completions");
        assert_eq!(body["model"], "test-model");
        assert_eq!(body["messages"][0]["role"], "system");
        assert_eq!(body["messages"][0]["content"], "sys");
        assert_eq!(body["messages"][1]["role"], "user");
        let a = &body["messages"][2];
        assert_eq!(a["role"], "assistant");
        assert!(a["content"].is_null());
        assert_eq!(a["tool_calls"][0]["id"], "c1");
        assert_eq!(a["tool_calls"][0]["type"], "function");
        assert_eq!(a["tool_calls"][0]["function"]["name"], "chercher");
        assert_eq!(a["tool_calls"][0]["function"]["arguments"], r#"{"q":"x"}"#);
        assert_eq!(body["messages"][3]["role"], "tool");
        assert_eq!(body["messages"][3]["tool_call_id"], "c1");
        assert_eq!(body["messages"][3]["content"], "ok");
        assert_eq!(body["tools"][0]["type"], "function");
        assert_eq!(body["tools"][0]["function"]["name"], "chercher");
        assert_eq!(body["tools"][0]["function"]["description"], "cherche");
        assert_eq!(body["tools"][0]["function"]["parameters"]["type"], "object");
    }

    #[test]
    fn openai_parse_two_tool_calls_in_order() {
        let body = r#"{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"a","type":"function","function":{"name":"un","arguments":"{\"k\":1}"}},
            {"id":"b","type":"function","function":{"name":"deux","arguments":"{}"}}]}}]}"#;
        let reply = client_with(LlmProtocol::OpenaiCompatible)
            .parse_chat_response(reqwest::StatusCode::OK, body)
            .unwrap();
        assert_eq!(reply.text, None);
        assert_eq!(reply.tool_calls.len(), 2);
        assert_eq!(reply.tool_calls[0].id, "a");
        assert_eq!(reply.tool_calls[0].arguments, serde_json::json!({"k":1}));
        assert_eq!(reply.tool_calls[1].name, "deux");
    }

    #[test]
    fn openai_parse_text_and_usage() {
        let body = r#"{"choices":[{"message":{"role":"assistant","content":"salut"}}],
            "usage":{"prompt_tokens":12,"completion_tokens":34}}"#;
        let reply = client_with(LlmProtocol::OpenaiCompatible)
            .parse_chat_response(reqwest::StatusCode::OK, body)
            .unwrap();
        assert_eq!(reply.text.as_deref(), Some("salut"));
        assert!(reply.tool_calls.is_empty());
        assert_eq!(reply.usage, TokenUsage { input: 12, output: 34 });

        let sans = r#"{"choices":[{"message":{"role":"assistant","content":"x"}}]}"#;
        let reply = client_with(LlmProtocol::OpenaiCompatible)
            .parse_chat_response(reqwest::StatusCode::OK, sans)
            .unwrap();
        assert_eq!(reply.usage, TokenUsage { input: 0, output: 0 });
    }

    #[test]
    fn openai_null_tool_calls_is_empty() {
        let body = r#"{"choices":[{"message":{"role":"assistant","content":"x","tool_calls":null}}]}"#;
        let reply = client_with(LlmProtocol::OpenaiCompatible)
            .parse_chat_response(reqwest::StatusCode::OK, body)
            .unwrap();
        assert!(reply.tool_calls.is_empty());
    }

    #[test]
    fn openai_invalid_tool_arguments_is_error() {
        let body = r#"{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"a","type":"function","function":{"name":"un","arguments":"{pas du json"}}]}}]}"#;
        assert!(client_with(LlmProtocol::OpenaiCompatible)
            .parse_chat_response(reqwest::StatusCode::OK, body)
            .is_err());
    }

    #[test]
    fn external_route_redacts_and_self_hosted_does_not() {
        let iban = "FR76 3000 6000 0112 3456 7890 189";
        let turns = vec![
            ChatTurn::User(format!("mon iban {iban}")),
            ChatTurn::ToolResult { call_id: "c".into(), content: format!("compte {iban}"), is_error: false },
        ];
        let redactor = Some(std::sync::Arc::new(crate::redact::Redactor::new(&[])));
        for protocol in [LlmProtocol::Messages, LlmProtocol::OpenaiCompatible] {
            let ext = client_with(protocol).with_redactor(redactor.clone());
            let (_, body) = ext.build_chat_request(&format!("sys {iban}"), &turns, &[]).unwrap();
            let text = body.to_string();
            assert!(text.contains("[IBAN]") && !text.contains("3000"), "{text}");
            let (_, body) = client_with(protocol).build_chat_request("sys", &turns, &[]).unwrap();
            assert!(body.to_string().contains("3000 6000"));
        }
        let (_, body) = client_with(LlmProtocol::Ollama).with_redactor(redactor).build_request(iban).unwrap();
        assert!(body.to_string().contains("[IBAN]"));
    }

    #[test]
    fn router_redacts_only_on_external_route() {
        let r = LlmRouter::from_config(LlmRoutingConfig {
            self_hosted: Some(voie("http://interne.invalid")),
            external: Some(voie("http://externe.invalid")),
            redact_terms: vec!["secret".into()],
            ..Default::default()
        });
        let ext = r.client("x", Sensitivity::Common, Urgency::Interactive).unwrap();
        let (_, b) = ext.build_request("un secret").unwrap();
        assert!(b.to_string().contains("[MASQUE]"));
        let int = r.client("x", Sensitivity::Strategic, Urgency::Interactive).unwrap();
        let (_, b) = int.build_request("un secret").unwrap();
        assert!(b.to_string().contains("un secret"));
    }

    #[test]
    fn native_protocol_refuses_chat() {
        let c = client_with(LlmProtocol::Ollama);
        assert!(c.build_chat_request("s", &[], &[]).is_err());
        assert!(c.parse_chat_response(reqwest::StatusCode::OK, "{}").is_err());
    }

    fn messages_client() -> LlmClient {
        client_with(LlmProtocol::Messages)
    }

    fn call(id: &str) -> ToolCall {
        ToolCall { id: id.into(), name: "chercher".into(), arguments: serde_json::json!({"q":"x"}) }
    }

    #[test]
    fn messages_request_marks_system_and_last_tool_for_cache() {
        let mut tools = chat_tools();
        tools.push(ToolSpec { name: "autre".into(), description: "d".into(), input_schema: serde_json::json!({"type":"object"}) });
        let turns = vec![
            ChatTurn::User("salut".into()),
            ChatTurn::Assistant { text: Some("je cherche".into()), tool_calls: vec![call("c1")] },
            ChatTurn::Assistant { text: Some(String::new()), tool_calls: vec![call("c2")] },
        ];
        let (path, body) = messages_client().build_chat_request("sys", &turns, &tools).unwrap();
        assert_eq!(path, "/v1/messages");
        assert_eq!(body["model"], "test-model");
        assert_eq!(body["max_tokens"], CHAT_MAX_TOKENS);
        assert_eq!(body["system"][0]["type"], "text");
        assert_eq!(body["system"][0]["text"], "sys");
        assert_eq!(body["system"][0]["cache_control"]["type"], "ephemeral");
        assert!(body["tools"][0].get("cache_control").is_none());
        assert_eq!(body["tools"][0]["input_schema"]["type"], "object");
        assert_eq!(body["tools"][1]["cache_control"]["type"], "ephemeral");
        assert_eq!(body["messages"][0], serde_json::json!({"role":"user","content":"salut"}));
        let a = &body["messages"][1];
        assert_eq!(a["role"], "assistant");
        assert_eq!(a["content"][0], serde_json::json!({"type":"text","text":"je cherche"}));
        assert_eq!(a["content"][1], serde_json::json!({"type":"tool_use","id":"c1","name":"chercher","input":{"q":"x"}}));
        // texte vide : pas de bloc text
        assert_eq!(body["messages"][2]["content"][0]["type"], "tool_use");
    }

    #[test]
    fn messages_skips_empty_assistant_turn() {
        let turns = vec![
            ChatTurn::User("u".into()),
            ChatTurn::Assistant { text: None, tool_calls: vec![] },
        ];
        let (_, body) = messages_client().build_chat_request("s", &turns, &[]).unwrap();
        assert_eq!(body["messages"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn messages_without_tools_omits_tools_key() {
        let (_, body) = messages_client().build_chat_request("s", &[ChatTurn::User("u".into())], &[]).unwrap();
        assert!(body.get("tools").is_none());
    }

    #[test]
    fn messages_groups_consecutive_tool_results() {
        let turns = vec![
            ChatTurn::User("q".into()),
            ChatTurn::ToolResult { call_id: "c1".into(), content: "r1".into(), is_error: false },
            ChatTurn::ToolResult { call_id: "c2".into(), content: "r2".into(), is_error: true },
            ChatTurn::User("suite".into()),
        ];
        let (_, body) = messages_client().build_chat_request("s", &turns, &[]).unwrap();
        let msgs = body["messages"].as_array().unwrap();
        assert_eq!(msgs.len(), 3);
        assert_eq!(msgs[1]["role"], "user");
        let blocks = msgs[1]["content"].as_array().unwrap();
        assert_eq!(blocks.len(), 2);
        assert_eq!(blocks[0], serde_json::json!({"type":"tool_result","tool_use_id":"c1","content":"r1","is_error":false}));
        assert_eq!(blocks[1]["tool_use_id"], "c2");
        assert_eq!(blocks[1]["is_error"], true);
        assert_eq!(msgs[2]["content"], "suite");
    }

    #[test]
    fn messages_parse_text_tool_use_and_usage() {
        let body = r#"{"content":[
            {"type":"text","text":"a"},
            {"type":"tool_use","id":"t1","name":"un","input":{"k":1}},
            {"type":"text","text":"b"},
            {"type":"tool_use","id":"t2","name":"deux","input":{}}],
          "usage":{"input_tokens":10,"cache_read_input_tokens":100,"cache_creation_input_tokens":5,"output_tokens":7}}"#;
        let reply = messages_client().parse_chat_response(reqwest::StatusCode::OK, body).unwrap();
        assert_eq!(reply.text.as_deref(), Some("ab"));
        assert_eq!(reply.tool_calls.len(), 2);
        assert_eq!(reply.tool_calls[0].id, "t1");
        assert_eq!(reply.tool_calls[0].arguments, serde_json::json!({"k":1}));
        assert_eq!(reply.tool_calls[1].name, "deux");
        assert_eq!(reply.usage, TokenUsage { input: 115, output: 7 });

        let vide = r#"{"content":[{"type":"tool_use","id":"t","name":"n","input":{}}]}"#;
        let reply = messages_client().parse_chat_response(reqwest::StatusCode::OK, vide).unwrap();
        assert_eq!(reply.text, None);
        assert_eq!(reply.usage, TokenUsage { input: 0, output: 0 });
    }

    #[test]
    fn messages_http_error_is_error() {
        assert!(messages_client().parse_chat_response(reqwest::StatusCode::BAD_REQUEST, "{}").is_err());
    }

    #[test]
    fn messages_generate_path_uses_text_only() {
        let (path, body) = messages_client().build_request("bonjour").unwrap();
        assert_eq!(path, "/v1/messages");
        assert_eq!(body["messages"][0]["content"], "bonjour");
        assert!(body.get("tools").is_none());
        assert!(body.get("system").is_none());
        let txt = messages_client()
            .parse_response(reqwest::StatusCode::OK, r#"{"content":[{"type":"text","text":"ok"}]}"#)
            .unwrap();
        assert_eq!(txt, "ok");
    }

    fn client_auth(protocol: LlmProtocol, key: Option<&str>, header: Option<&str>) -> LlmClient {
        LlmClient::from_config(&LlmConfig {
            endpoint: "http://e.invalid".into(),
            model: "m".into(),
            protocol,
            api_key: key.map(Into::into),
            api_key_header: header.map(Into::into),
            headers: HashMap::from([("x-version".to_string(), "1".to_string())]),
        })
    }

    #[test]
    fn messages_custom_auth_header_and_fixed_headers() {
        let h = client_auth(LlmProtocol::Messages, Some("k"), Some("x-cle")).auth_and_headers();
        assert_eq!(h, vec![("x-cle".to_string(), "k".to_string()), ("x-version".to_string(), "1".to_string())]);
        assert!(!h.iter().any(|(n, _)| n.eq_ignore_ascii_case("authorization")));
    }

    #[test]
    fn auth_defaults_to_bearer_and_is_absent_without_key() {
        let h = client_auth(LlmProtocol::OpenaiCompatible, Some("k"), None).auth_and_headers();
        assert_eq!(h[0], ("Authorization".to_string(), "Bearer k".to_string()));
        let h = client_auth(LlmProtocol::OpenaiCompatible, Some("k"), Some("authorization")).auth_and_headers();
        assert_eq!(h[0].1, "Bearer k");
        let h = client_auth(LlmProtocol::OpenaiCompatible, None, None).auth_and_headers();
        assert_eq!(h, vec![("x-version".to_string(), "1".to_string())]);
    }

    #[test]
    fn messages_protocol_from_toml() {
        let c: LlmConfig = toml::from_str(
            r#"
            endpoint = "http://e.invalid"
            model = "m"
            protocol = "messages"
            api_key_header = "x-cle"
            [headers]
            x-version = "1"
            "#,
        )
        .unwrap();
        assert_eq!(c.protocol, LlmProtocol::Messages);
        assert_eq!(c.api_key_header.as_deref(), Some("x-cle"));
        assert_eq!(c.headers["x-version"], "1");

        let r: LlmRoutingConfig = toml::from_str(
            r#"
            [engines.e]
            endpoint = "http://e.invalid"
            model = "m"
            protocol = "messages"
            placement = "external"
            api_key_header = "x-cle"
            [engines.e.headers]
            x-version = "1"
            "#,
        )
        .unwrap();
        let e = &r.engines["e"].llm;
        assert_eq!(e.protocol, LlmProtocol::Messages);
        assert_eq!(e.api_key_header.as_deref(), Some("x-cle"));
        assert_eq!(e.headers["x-version"], "1");
    }

    const IBAN: &str = "FR76 3000 6000 0112 3456 7890 189";

    fn external(protocol: LlmProtocol, terms: &[String]) -> LlmClient {
        client_with(protocol).with_redactor(Some(std::sync::Arc::new(crate::redact::Redactor::new(terms))))
    }

    #[test]
    fn tool_call_arguments_are_redacted_on_external_route() {
        let turns = vec![ChatTurn::Assistant {
            text: None,
            tool_calls: vec![ToolCall {
                id: "c1".into(),
                name: "virer".into(),
                arguments: serde_json::json!({"dest": {"iban": IBAN}, "liste": [IBAN], "n": 3}),
            }],
        }];
        for protocol in [LlmProtocol::Messages, LlmProtocol::OpenaiCompatible] {
            let (_, body) = external(protocol, &[]).build_chat_request("s", &turns, &[]).unwrap();
            let text = body.to_string();
            assert!(text.contains("[IBAN]") && !text.contains("3000"), "{text}");
            assert!(text.contains("virer"));
        }
    }

    #[test]
    fn messages_generate_masks_once() {
        let c = external(LlmProtocol::Messages, &["tel".to_string()]);
        let (_, body) = c.build_request("appelle 06 12 34 56 78").unwrap();
        let text = body.to_string();
        assert!(text.contains("[TEL]") && !text.contains("[[MASQUE]"), "{text}");
    }

    #[test]
    fn openai_omits_empty_system_and_empty_assistant_turn() {
        let turns = vec![
            ChatTurn::User("a".into()),
            ChatTurn::Assistant { text: None, tool_calls: vec![] },
        ];
        let (_, body) = client_with(LlmProtocol::OpenaiCompatible).build_chat_request("", &turns, &[]).unwrap();
        let m = body["messages"].as_array().unwrap();
        assert_eq!(m.len(), 1);
        assert_eq!(m[0]["role"], "user");
    }

    #[test]
    fn openai_empty_tool_arguments_parse_as_empty_object() {
        let body = r#"{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"a","type":"function","function":{"name":"un","arguments":""}}]}}]}"#;
        let reply = client_with(LlmProtocol::OpenaiCompatible)
            .parse_chat_response(reqwest::StatusCode::OK, body)
            .unwrap();
        assert_eq!(reply.tool_calls[0].arguments, serde_json::json!({}));
    }

    #[test]
    fn openai_tool_error_is_prefixed() {
        let turns = vec![ChatTurn::ToolResult { call_id: "c".into(), content: "boum".into(), is_error: true }];
        let (_, body) = client_with(LlmProtocol::OpenaiCompatible).build_chat_request("s", &turns, &[]).unwrap();
        assert_eq!(body["messages"][1]["content"], "Erreur : boum");
    }

    #[test]
    fn debug_hides_credentials() {
        let cfg = LlmConfig {
            endpoint: "http://e.invalid".into(),
            model: "m".into(),
            api_key: Some("cle-tres-secrete".into()),
            headers: HashMap::from([("x-v".to_string(), "valeur-secrete".to_string())]),
            ..Default::default()
        };
        let s = format!("{cfg:?}");
        assert!(!s.contains("cle-tres-secrete") && !s.contains("valeur-secrete"), "{s}");
        assert!(s.contains("***"));
    }
}
