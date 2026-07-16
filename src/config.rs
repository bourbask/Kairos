use serde::Deserialize;
use std::path::Path;

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
    #[serde(default)]
    pub location_keywords: Vec<String>,
}

fn default_min_score() -> f64 { 0.60 }

#[derive(Debug, Clone, Deserialize)]
pub struct Skills {
    pub skills: Vec<String>,
}

impl Skills {
    pub fn all(&self) -> Vec<String> {
        self.skills.clone()
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
