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
/// Outils réservés à l'API de l'application (conventions et préfixe `app_`) : jamais proposés au moteur.
pub fn is_app_tool(name: &str) -> bool {
    matches!(name, "cards" | "decisions" | "decide") || name.starts_with("app_")
}

type Client = RunningService<RoleClient, ()>;

struct Tentacle {
    name: String,
    url: String,
    token_env: String,
    private: bool,
    keywords: Vec<String>,
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
            .map(|e| Tentacle { name: e.name.clone(), url: e.url.clone(), token_env: e.token_env.clone(), private: e.private, keywords: e.keywords.clone(), link: None })
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
            .filter(|t| !t.private)
            .filter_map(|t| t.link.as_ref().map(|(_, tools)| (t, tools)))
            .flat_map(|(t, tools)| {
                tools.iter().filter(|tool| !is_app_tool(&tool.name)).map(|tool| ToolSpec {
                    name: format!("{}__{}", t.name, tool.name),
                    description: tool.description.as_deref().unwrap_or_default().to_string(),
                    input_schema: plain_schema(serde_json::Value::Object((*tool.input_schema).clone())),
                })
            })
            .collect()
    }

    /// Vrai si un tentacule connecté expose l'outil préfixé `nom__outil`.
    pub fn exposes(&self, prefixed: &str) -> bool {
        self.list.iter().any(|t| {
            prefixed.strip_prefix(t.name.as_str()).and_then(|r| r.strip_prefix("__")).is_some_and(|tool| t.exposes(tool))
        })
    }

    /// `Some(connecté)` si `name` est configuré, `None` sinon.
    pub fn connected(&self, name: &str) -> Option<bool> {
        self.list.iter().find(|t| t.name == name).map(|t| t.link.is_some())
    }

    /// Mots-clés déclarés du tentacule qui préfixe `tool` (`nom__outil`).
    pub fn keywords_of(&self, tool: &str) -> &[String] {
        self.list
            .iter()
            .find(|t| tool.strip_prefix(t.name.as_str()).is_some_and(|r| r.starts_with("__")))
            .map_or(&[], |t| t.keywords.as_slice())
    }

    pub fn private_names(&self) -> Vec<String> {
        self.list.iter().filter(|t| t.private).map(|t| t.name.clone()).collect()
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

    /// Comme `call_conventional`, tentacules privés exclus : rien ne leur est demandé.
    pub async fn call_conventional_public(&self, tool: &str, args: serde_json::Value) -> Vec<(String, Result<String, String>)> {
        self.call_each(tool, args, TOOL_RESULT_MAX_CHARS, false).await
    }

    /// Comme `call_conventional`, avec une borne de résultat explicite (voir `call_limited`).
    pub async fn call_conventional_limited(
        &self,
        tool: &str,
        args: serde_json::Value,
        max_chars: usize,
    ) -> Vec<(String, Result<String, String>)> {
        self.call_each(tool, args, max_chars, true).await
    }

    async fn call_each(
        &self,
        tool: &str,
        args: serde_json::Value,
        max_chars: usize,
        include_private: bool,
    ) -> Vec<(String, Result<String, String>)> {
        // En parallèle : un tentacule lent ne retarde pas les autres (ordre de la configuration conservé).
        let calls = self
            .list
            .iter()
            .filter(|t| t.exposes(tool) && (include_private || !t.private))
            .map(|t| async { (t.name.clone(), call_on(t, tool, args.clone(), max_chars).await) });
        futures::future::join_all(calls).await
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

/// Remplace les sous-schémas booléens (`true` = « toute valeur ») par `{}` :
/// même sens, mais certains moteurs n'acceptent que des objets comme schéma
/// de paramètre. Les mots-clés booléens légitimes (`additionalProperties`…)
/// sont conservés. Retire aussi ce qui coûte des jetons sans guider le
/// moteur : `$schema`, `format`, `default: null`.
pub fn plain_schema(v: serde_json::Value) -> serde_json::Value {
    use serde_json::Value;
    fn schema(v: Value) -> Value {
        match v {
            Value::Bool(_) => Value::Object(Default::default()),
            Value::Object(m) => Value::Object(
                m.into_iter()
                    .filter(|(k, v)| !matches!(k.as_str(), "$schema" | "format") && !(k == "default" && v.is_null()))
                    .map(|(k, v)| {
                        let v = match (k.as_str(), v) {
                            ("properties" | "$defs" | "definitions", Value::Object(p)) => {
                                Value::Object(p.into_iter().map(|(n, s)| (n, schema(s))).collect())
                            }
                            ("items" | "not", s) => schema(s),
                            ("anyOf" | "oneOf" | "allOf", Value::Array(a)) => Value::Array(a.into_iter().map(schema).collect()),
                            (_, v) => v,
                        };
                        (k, v)
                    })
                    .collect(),
            ),
            other => other,
        }
    }
    schema(v)
}

#[cfg(test)]
mod tests {
    use super::plain_schema;
    use serde_json::json;

    #[test]
    fn boolean_subschemas_become_empty_objects() {
        let s = json!({"type": "object", "properties": {
            "any": true,
            "list": {"type": "array", "items": true},
            "opt": {"anyOf": [true, {"type": "null"}]},
            "nested": {"type": "object", "properties": {"deep": true}, "additionalProperties": false}
        }, "$defs": {"X": true}});
        let out = plain_schema(s);
        assert_eq!(out["properties"]["any"], json!({}));
        assert_eq!(out["properties"]["list"]["items"], json!({}));
        assert_eq!(out["properties"]["opt"]["anyOf"][0], json!({}));
        assert_eq!(out["properties"]["nested"]["properties"]["deep"], json!({}));
        // mot-clé booléen légitime : conservé
        assert_eq!(out["properties"]["nested"]["additionalProperties"], json!(false));
        assert_eq!(out["$defs"]["X"], json!({}));
    }

    #[test]
    fn token_heavy_keywords_dropped() {
        let s = json!({"$schema": "x", "type": "object", "properties": {
            "p": {"type": ["number", "null"], "format": "double", "default": null},
            "d": {"type": "integer", "default": 3}
        }});
        let out = plain_schema(s);
        assert!(out.get("$schema").is_none());
        assert_eq!(out["properties"]["p"], json!({"type": ["number", "null"]}));
        assert_eq!(out["properties"]["d"]["default"], json!(3));
    }

    #[test]
    fn ordinary_schema_unchanged() {
        let s = json!({"type": "object", "properties": {"n": {"type": ["integer", "null"]}}, "required": ["n"]});
        assert_eq!(plain_schema(s.clone()), s);
    }

    #[test]
    fn app_prefixed_tools_hidden_from_engine() {
        for t in ["cards", "decisions", "decide", "app_inventory", "app_add"] {
            assert!(super::is_app_tool(t), "{t}");
        }
        for t in ["stock_set", "meal_plan_set", "apple"] {
            assert!(!super::is_app_tool(t), "{t}");
        }
    }
}
