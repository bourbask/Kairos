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
}

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_load_profile() {
        let profile = Profile::from_file("config/profile.toml").unwrap();
        assert_eq!(profile.name.first, "Kévin");
        assert_eq!(profile.preferences.salary_min, 45000);
        assert!(profile.skills.all().contains(&"Rust".to_string()));
    }
}
