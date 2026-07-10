use serde::{Deserialize, Serialize};

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

pub struct LlmClient {
    endpoint: String,
    model: String,
    client: reqwest::Client,
}

impl LlmClient {
    pub fn new(endpoint: String, model: String) -> Self {
        Self {
            endpoint,
            model,
            client: reqwest::Client::new(),
        }
    }

    pub fn local_default() -> Self {
        Self::new("http://localhost:11434".into(), "phi3:mini".into())
    }

    pub async fn generate(&self, prompt: &str) -> anyhow::Result<String> {
        let payload = OllamaRequest {
            model: self.model.clone(),
            prompt: prompt.to_string(),
            stream: false,
        };

        let resp = self.client
            .post(format!("{}/api/generate", self.endpoint))
            .json(&payload)
            .send()
            .await?;

        let body: OllamaResponse = resp.json().await?;
        Ok(body.response)
    }

}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_local_default() {
        let client = LlmClient::local_default();
        assert_eq!(client.model, "phi3:mini");
        assert_eq!(client.endpoint, "http://localhost:11434");
    }
}
