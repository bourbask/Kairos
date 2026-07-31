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
}

/// Configuration des modules branchables, une section par module. Le profil
/// décrit l'utilisateur ; ce qu'un module a besoin de savoir vit ici.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct ModulesConfig {
    /// Absente : la section de synthèse est simplement omise du briefing.
    #[serde(default)]
    pub signals: Option<crate::signals::SignalsConfig>,
}

impl ModulesConfig {
    /// Fichier absent ou illisible : les modules restent inactifs. Aucun module
    /// n'est indispensable au fonctionnement du reste.
    pub fn load(path: impl AsRef<Path>) -> Self {
        let Ok(content) = std::fs::read_to_string(path.as_ref()) else {
            return Self::default();
        };
        toml::from_str(&content).unwrap_or_else(|e| {
            tracing::warn!("configuration des modules ignorée : {e}");
            Self::default()
        })
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
    /// Une voie à moitié renseignée produirait des appels voués à l'échec : elle
    /// est écartée à la lecture, comme une voie absente.
    pub fn is_usable(&self) -> bool {
        !self.endpoint.trim().is_empty() && !self.model.trim().is_empty()
    }
}

/// Les deux voies d'inférence et les concessions consenties. Source unique du
/// choix du moteur : aucun module ne porte d'endpoint ni de modèle.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct LlmRoutingConfig {
    /// Moteur auto-hébergé, seul destinataire des générations stratégiques.
    #[serde(default)]
    pub self_hosted: Option<LlmConfig>,
    /// Service externe, réservé aux générations banales.
    #[serde(default)]
    pub external: Option<LlmConfig>,
    /// Usages stratégiques que l'opérateur concède au service externe, par nom
    /// d'usage. Liste vide = refus. La relecture de cette seule liste montre
    /// l'intégralité des exceptions en vigueur.
    #[serde(default)]
    pub strategic_concessions: Vec<String>,
}

impl LlmRoutingConfig {
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

    #[test]
    fn missing_modules_file_leaves_every_module_inactive() {
        let modules = ModulesConfig::load("config/absent.toml");
        assert!(modules.signals.is_none());
    }

    /// L'exemple ne porte que des placeholders : la section existe mais reste
    /// inexploitable, donc le module est inactif sans erreur.
    #[test]
    fn example_modules_file_parses_but_stays_unusable() {
        let modules = ModulesConfig::load("config/modules.example.toml");
        let signals = modules.signals.expect("section présente dans l'exemple");
        assert!(!signals.is_usable());
    }
}
