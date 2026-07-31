use serde::{Deserialize, Serialize};

use crate::config::{LlmConfig, LlmProtocol, LlmRoutingConfig};

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

/// Applique les deux voies déclarées en configuration. Une génération
/// stratégique ne sort jamais vers le service externe, sauf concession
/// explicite de l'opérateur, journalisée à chaque application.
pub struct LlmRouter {
    config: LlmRoutingConfig,
}

impl LlmRouter {
    pub fn from_config(config: LlmRoutingConfig) -> Self {
        Self { config }
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
            });
        }

        Self::from_config(config)
    }

    fn usable(voie: &Option<LlmConfig>) -> Option<&LlmConfig> {
        voie.as_ref().filter(|c| c.is_usable())
    }

    /// `usage` nomme l'appelant : il sert au journal et rattache une éventuelle
    /// concession.
    fn route(&self, usage: &str, sensitivity: Sensitivity) -> anyhow::Result<(&LlmConfig, Engine)> {
        let self_hosted = Self::usable(&self.config.self_hosted);
        let external = Self::usable(&self.config.external);

        match sensitivity {
            Sensitivity::Strategic => {
                let conceded = self
                    .config
                    .strategic_concessions
                    .iter()
                    .any(|u| u == usage);
                if conceded && let Some(config) = external {
                    tracing::warn!(
                        "usage {usage} : concession déclarée en configuration, génération \
                         stratégique confiée au service externe"
                    );
                    return Ok((config, Engine::External));
                }
                self_hosted.map(|c| (c, Engine::SelfHosted)).ok_or_else(|| {
                    anyhow::anyhow!(
                        "usage {usage} : moteur auto-hébergé indisponible, une génération \
                         stratégique ne se déporte pas"
                    )
                })
            }
            Sensitivity::Common => external
                .map(|c| (c, Engine::External))
                .or_else(|| self_hosted.map(|c| (c, Engine::SelfHosted)))
                .ok_or_else(|| anyhow::anyhow!("usage {usage} : aucun moteur configuré")),
        }
    }

    pub fn client(
        &self,
        usage: &str,
        sensitivity: Sensitivity,
        urgency: Urgency,
    ) -> anyhow::Result<LlmClient> {
        let (config, engine) = self.route(usage, sensitivity)?;
        tracing::info!("routage {usage} : {sensitivity:?}/{urgency:?} → {engine:?}");
        Ok(LlmClient::from_config(config))
    }
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

pub struct LlmClient {
    endpoint: String,
    model: String,
    protocol: LlmProtocol,
    api_key: Option<String>,
    client: reqwest::Client,
}

impl LlmClient {
    pub fn from_config(config: &LlmConfig) -> Self {
        Self {
            endpoint: config.endpoint.clone(),
            model: config.model.clone(),
            protocol: config.protocol,
            api_key: config.api_key.clone(),
            client: reqwest::Client::new(),
        }
    }

    /// Request path and JSON body for the configured protocol.
    fn build_request(&self, prompt: &str) -> anyhow::Result<(&'static str, serde_json::Value)> {
        Ok(match self.protocol {
            LlmProtocol::Ollama => (
                "/api/generate",
                serde_json::to_value(OllamaRequest {
                    model: self.model.clone(),
                    prompt: prompt.to_string(),
                    stream: false,
                })?,
            ),
            LlmProtocol::OpenaiCompatible => (
                "/v1/chat/completions",
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

    pub async fn generate(&self, prompt: &str) -> anyhow::Result<String> {
        let (path, payload) = self.build_request(prompt)?;

        let mut request = self
            .client
            .post(format!("{}{}", self.endpoint, path))
            .json(&payload);
        if let Some(key) = &self.api_key {
            request = request.bearer_auth(key);
        }

        let resp = request.send().await?;
        let status = resp.status();
        let body = resp.text().await?;
        self.parse_response(status, &body)
    }

}

#[cfg(test)]
mod tests {
    use super::*;

    fn client_with(protocol: LlmProtocol) -> LlmClient {
        LlmClient::from_config(&LlmConfig {
            endpoint: "http://endpoint.invalid".into(),
            model: "test-model".into(),
            protocol,
            api_key: None,
        })
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
        })
    }

    #[test]
    fn strategic_stays_self_hosted_when_both_paths_exist() {
        let r = router(
            Some(voie("http://interne.invalid")),
            Some(voie("http://externe.invalid")),
            vec![],
        );
        let (config, engine) = r.route("veille", Sensitivity::Strategic).unwrap();
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
        let (config, engine) = r.route("veille", Sensitivity::Strategic).unwrap();
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
        let (_, engine) = r.route("veille", Sensitivity::Strategic).unwrap();
        assert_eq!(engine, Engine::SelfHosted);
    }

    #[test]
    fn common_prefers_external_then_falls_back() {
        let with_external = router(
            Some(voie("http://interne.invalid")),
            Some(voie("http://externe.invalid")),
            vec![],
        );
        let (_, engine) = with_external.route("resume", Sensitivity::Common).unwrap();
        assert_eq!(engine, Engine::External);

        let without_external = router(Some(voie("http://interne.invalid")), None, vec![]);
        let (_, engine) = without_external
            .route("resume", Sensitivity::Common)
            .unwrap();
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
}
