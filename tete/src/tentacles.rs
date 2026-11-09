//! Client MCP des tentacules : connexion, catalogue d'outils préfixés, appels.

use std::time::Duration;

use kairos::llm::ToolSpec;
use rmcp::{
    RoleClient, ServiceExt,
    model::{CallToolRequestParams, Tool},
    service::RunningService,
    transport::{StreamableHttpClientTransport, streamable_http_client::StreamableHttpClientTransportConfig},
};

use crate::config::TentacleEntry;

pub const TOOL_RESULT_MAX_CHARS: usize = 8000;
pub const TOOL_TIMEOUT: Duration = Duration::from_secs(20);
const TRUNCATED: &str = "… [tronqué]";

type Client = RunningService<RoleClient, ()>;

struct Tentacle {
    name: String,
    url: String,
    token_env: String,
    link: Option<(Client, Vec<Tool>)>,
}

impl Tentacle {
    /// Échec = tentacule absent (journalisé, jamais le jeton).
    async fn connect(&mut self) {
        self.link = None;
        let token = match std::env::var(&self.token_env) {
            Ok(t) if !t.is_empty() => t,
            _ => {
                tracing::warn!(tentacule = %self.name, "jeton absent");
                return;
            }
        };
        let config = StreamableHttpClientTransportConfig::with_uri(self.url.as_str()).auth_header(token);
        let transport = StreamableHttpClientTransport::from_config(config);
        let attempt = async {
            let client = ().serve(transport).await.map_err(|e| e.to_string())?;
            let tools = client.list_all_tools().await.map_err(|e| e.to_string())?;
            Ok::<_, String>((client, tools))
        };
        match tokio::time::timeout(TOOL_TIMEOUT, attempt).await {
            Ok(Ok(link)) => self.link = Some(link),
            Ok(Err(e)) => tracing::warn!(tentacule = %self.name, erreur = %e, "tentacule injoignable"),
            Err(_) => tracing::warn!(tentacule = %self.name, "délai de connexion dépassé"),
        }
    }

    fn exposes(&self, tool: &str) -> bool {
        self.link.as_ref().is_some_and(|(_, tools)| tools.iter().any(|t| t.name == tool))
    }
}

pub struct Tentacles {
    list: Vec<Tentacle>,
}

impl Tentacles {
    /// N'échoue jamais : un tentacule inaccessible est simplement absent.
    pub async fn connect(entries: &[TentacleEntry]) -> Tentacles {
        let mut list: Vec<Tentacle> = entries
            .iter()
            .map(|e| Tentacle { name: e.name.clone(), url: e.url.clone(), token_env: e.token_env.clone(), link: None })
            .collect();
        for t in &mut list {
            t.connect().await;
        }
        Tentacles { list }
    }

    /// Reconnecte les absents et relit les listes d'outils des présents.
    pub async fn refresh(&mut self) {
        for t in &mut self.list {
            if let Some((client, tools)) = &mut t.link {
                if let Ok(Ok(fresh)) = tokio::time::timeout(TOOL_TIMEOUT, client.list_all_tools()).await {
                    *tools = fresh;
                    continue;
                }
            }
            t.connect().await;
        }
    }

    pub fn tool_specs(&self) -> Vec<ToolSpec> {
        self.list
            .iter()
            .filter_map(|t| t.link.as_ref().map(|(_, tools)| (t, tools)))
            .flat_map(|(t, tools)| {
                tools.iter().map(|tool| ToolSpec {
                    name: format!("{}__{}", t.name, tool.name),
                    description: tool.description.as_deref().unwrap_or_default().to_string(),
                    input_schema: serde_json::Value::Object((*tool.input_schema).clone()),
                })
            })
            .collect()
    }

    pub async fn call(&self, prefixed: &str, args: serde_json::Value) -> Result<String, String> {
        self.call_limited(prefixed, args, TOOL_RESULT_MAX_CHARS).await
    }

    /// Comme `call`, avec une borne de résultat explicite : réservé aux lectures
    /// destinées à un traitement hors conversation.
    pub async fn call_limited(&self, prefixed: &str, args: serde_json::Value, max_chars: usize) -> Result<String, String> {
        let (t, tool) = self
            .list
            .iter()
            .find_map(|t| {
                let tool = prefixed.strip_prefix(t.name.as_str())?.strip_prefix("__")?;
                t.exposes(tool).then_some((t, tool))
            })
            .ok_or_else(|| format!("outil inconnu : {prefixed}"))?;
        call_on(t, tool, args, max_chars).await
    }

    /// Appelle `tool` sur chaque tentacule qui l'expose, dans l'ordre de la configuration.
    pub async fn call_conventional(&self, tool: &str, args: serde_json::Value) -> Vec<(String, Result<String, String>)> {
        self.call_conventional_limited(tool, args, TOOL_RESULT_MAX_CHARS).await
    }

    /// Comme `call_conventional`, avec une borne de résultat explicite (voir `call_limited`).
    pub async fn call_conventional_limited(
        &self,
        tool: &str,
        args: serde_json::Value,
        max_chars: usize,
    ) -> Vec<(String, Result<String, String>)> {
        let mut out = Vec::new();
        for t in self.list.iter().filter(|t| t.exposes(tool)) {
            out.push((t.name.clone(), call_on(t, tool, args.clone(), max_chars).await));
        }
        out
    }
}

async fn call_on(t: &Tentacle, tool: &str, args: serde_json::Value, max_chars: usize) -> Result<String, String> {
    let (client, _) = t.link.as_ref().ok_or("tentacule absent")?;
    let mut params = CallToolRequestParams::new(tool.to_string());
    params.arguments = match args {
        serde_json::Value::Object(m) => Some(m),
        serde_json::Value::Null => None,
        _ => return Err("arguments : objet JSON attendu".into()),
    };
    let res = tokio::time::timeout(TOOL_TIMEOUT, client.call_tool(params))
        .await
        .map_err(|_| "délai dépassé".to_string())?
        // Le détail peut citer une adresse interne : journalisé, jamais transmis au moteur.
        .map_err(|e| {
            tracing::warn!(tentacule = %t.name, erreur = %e, "appel d'outil : erreur de transport");
            "transport indisponible".to_string()
        })?;
    let text: String = res.content.iter().filter_map(|c| c.as_text()).map(|t| t.text.as_str()).collect::<Vec<_>>().join("\n");
    let text = truncate(text, max_chars);
    if res.is_error == Some(true) { Err(text) } else { Ok(text) }
}

fn truncate(s: String, max_chars: usize) -> String {
    match s.char_indices().nth(max_chars) {
        Some((i, _)) => format!("{}{TRUNCATED}", &s[..i]),
        None => s,
    }
}
