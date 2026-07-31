use serde::{Deserialize, Serialize};

use crate::config::{LlmConfig, LlmProtocol};

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
    pub fn new(endpoint: String, model: String) -> Self {
        Self {
            endpoint,
            model,
            protocol: LlmProtocol::default(),
            api_key: None,
            client: reqwest::Client::new(),
        }
    }

    pub fn from_config(config: &LlmConfig) -> Self {
        Self {
            endpoint: config.endpoint.clone(),
            model: config.model.clone(),
            protocol: config.protocol,
            api_key: config.api_key.clone(),
            client: reqwest::Client::new(),
        }
    }

    pub fn local_default() -> Self {
        Self::new("http://localhost:11434".into(), "phi3:mini".into())
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
    fn test_local_default() {
        let client = LlmClient::local_default();
        assert_eq!(client.model, "phi3:mini");
        assert_eq!(client.endpoint, "http://localhost:11434");
        assert_eq!(client.protocol, LlmProtocol::Ollama);
        assert!(client.api_key.is_none());
    }

    #[test]
    fn test_new_keeps_default_protocol() {
        let client = LlmClient::new("http://endpoint.invalid".into(), "m".into());
        assert_eq!(client.protocol, LlmProtocol::Ollama);
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
