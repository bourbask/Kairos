use serde::Deserialize;
use std::path::Path;
use std::collections::HashMap;

#[derive(Debug, Clone, Deserialize)]
pub struct Profile {
    pub name: Name,
    pub contact: Contact,
    pub preferences: Preferences,
    pub skills: Skills,
    pub experience: Experience,
    #[serde(default)]
    pub filters: Filters,
    /// Absente : la section de synthèse est simplement omise du briefing.
    #[serde(default)]
    pub signals: Option<SignalsConfig>,
}

/// Source de signaux prospectifs et modèle chargé de leur synthèse. Toutes les
/// valeurs identifiantes vivent ici, jamais dans le code.
#[derive(Debug, Clone, Deserialize)]
pub struct SignalsConfig {
    pub base_url: String,
    pub api_key: String,
    pub auth_header: String,
    pub markets_path: String,
    pub forecasts_path: String,
    /// Sujets sur lesquels la synthèse doit se concentrer. Le reste du profil
    /// décrit une recherche d'emploi : sans cette liste, la lecture des signaux
    /// se fait sous le seul angle professionnel et conclut à l'absence de lien.
    #[serde(default)]
    pub interests: Vec<String>,
    #[serde(default = "default_llm_endpoint")]
    pub llm_endpoint: String,
    #[serde(default = "default_llm_model")]
    pub llm_model: String,
}

fn default_llm_endpoint() -> String {
    "http://localhost:11434".into()
}

fn default_llm_model() -> String {
    "phi3:mini".into()
}

impl SignalsConfig {
    /// Une section partiellement remplie produit des requêtes silencieusement
    /// inutiles : on l'écarte à la lecture plutôt qu'à l'appel.
    pub fn is_usable(&self) -> bool {
        !self.base_url.trim().is_empty()
            && !self.api_key.trim().is_empty()
            && !self.auth_header.trim().is_empty()
            && !self.markets_path.trim().is_empty()
            && !self.forecasts_path.trim().is_empty()
    }
}

/// Filtrage négatif (optionnel) : sous-chaînes insensibles à la casse.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct Filters {
    #[serde(default)]
    pub blacklist_companies: Vec<String>,
    #[serde(default)]
    pub blacklist_keywords: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Name {
    pub first: String,
    pub last: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Contact {
    pub email: String,
    pub location: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Preferences {
    pub salary_min: u32,
    pub salary_target: u32,
    pub salary_max: u32,
    pub currency: String,
    pub remote: String,
    pub countries: Vec<String>,
    pub languages: Vec<String>,
    #[serde(default = "default_min_score")]
    pub min_score: f64,
}

fn default_min_score() -> f64 { 0.60 }

#[derive(Debug, Clone, Deserialize)]
pub struct Skills {
    pub backend: Vec<String>,
    pub frontend: Vec<String>,
    pub database: Vec<String>,
    pub devops: Vec<String>,
    pub learning: Vec<String>,
}

impl Skills {
    pub fn all(&self) -> Vec<String> {
        let mut all = Vec::new();
        all.extend(self.backend.clone());
        all.extend(self.frontend.clone());
        all.extend(self.database.clone());
        all.extend(self.devops.clone());
        all.extend(self.learning.clone());
        all
    }

    /// Return skills as a flat map with category labels for matching.
    pub fn categorized(&self) -> HashMap<&str, &[String]> {
        let mut map = HashMap::new();
        map.insert("backend", &self.backend[..]);
        map.insert("frontend", &self.frontend[..]);
        map.insert("database", &self.database[..]);
        map.insert("devops", &self.devops[..]);
        map.insert("learning", &self.learning[..]);
        map
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Experience {
    pub years: u8,
    pub current_role: String,
    pub current_company: String,
}

impl Profile {
    pub fn from_file(path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path.as_ref())?;
        let profile: Profile = toml::from_str(&content)?;
        Ok(profile)
    }
}

/// Wire protocol spoken by the configured inference endpoint.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LlmProtocol {
    #[default]
    Ollama,
    OpenaiCompatible,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LlmConfig {
    pub endpoint: String,
    pub model: String,
    #[serde(default)]
    pub protocol: LlmProtocol,
    /// Bearer credential, when the endpoint requires one.
    #[serde(default)]
    pub api_key: Option<String>,
}

impl LlmConfig {
    pub fn from_file(path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path.as_ref())?;
        Ok(toml::from_str(&content)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_load_profile() {
        let profile = Profile::from_file("config/profile.example.toml").unwrap();
        assert_eq!(profile.name.first, "Prénom");
        assert_eq!(profile.preferences.salary_min, 35000);
        assert!(profile.skills.all().contains(&"Node.js".to_string()));
    }
}
