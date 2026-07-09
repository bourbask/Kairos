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

    pub async fn summarize(&self, text: &str, max_words: usize) -> anyhow::Result<String> {
        let prompt = format!(
            "Résume le texte suivant en moins de {max_words} mots, en français, sans commentaire introductif :\n\n{text}",
            max_words = max_words,
            text = text,
        );
        self.generate(&prompt).await
    }

    pub async fn analyze_day(
        &self,
        calendar_entries: &str,
        health_data: &str,
        meals: &str,
    ) -> anyhow::Result<String> {
        let prompt = format!(
            "Tu es Kairos, assistant personnel. Analyse ces données et produis un résumé concis en français :

AGENDA :
{calendar}

SANTÉ :
{health}

REPAS :
{meals}

Produis un paragraphe de 3-4 phrases maximum, sans formule de politesse.",
            calendar = calendar_entries,
            health = health_data,
            meals = meals,
        );
        self.generate(&prompt).await
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
