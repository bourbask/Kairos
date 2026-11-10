use serde::Deserialize;
use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, Clone, Deserialize)]
pub struct Profile {
    pub name: Name,
    pub contact: Contact,
    pub preferences: Preferences,
    pub skills: Skills,
    pub experience: Experience,
    /// Ancien emplacement des réglages de la recherche d'emploi, lu en repli.
    #[serde(default)]
    pub filters: Option<Filters>,
}

/// Configuration des modules branchables, une section par module. Le profil
/// décrit l'utilisateur ; ce qu'un module a besoin de savoir vit ici.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct ModulesConfig {
    /// Absente : la section de synthèse est simplement omise du briefing.
    #[serde(default)]
    pub signals: Option<crate::signals::SignalsConfig>,
    /// Absente : repli sur l'ancien emplacement dans le profil, puis valeurs par défaut.
    #[serde(default)]
    pub jobs: Option<crate::matching::JobsConfig>,
}

impl ModulesConfig {
    /// Fichier absent ou illisible : les modules restent inactifs. Aucun module
    /// n'est indispensable au fonctionnement du reste.
    pub fn load(path: impl AsRef<Path>) -> Self {
        let Ok(content) = std::fs::read_to_string(path.as_ref()) else {
            return Self::default();
        };
        parse(&content).unwrap_or_else(|e| {
            tracing::warn!("configuration des modules ignorée : {e}");
            Self::default()
        })
    }

    /// Réglages de la recherche d'emploi. La section du fichier des modules
    /// prévaut ; à défaut, l'ancien emplacement dans le profil reste lu pour
    /// qu'une configuration existante fonctionne sans migration manuelle.
    pub fn jobs(&self, profile: &Profile) -> crate::matching::JobsConfig {
        let p = &profile.preferences;
        let legacy = p.min_score.is_some() || p.location_keywords.is_some() || profile.filters.is_some();
        if let Some(jobs) = &self.jobs {
            if legacy {
                tracing::warn!("profil : réglages de recherche d'emploi ignorés, la section [jobs] des modules prévaut");
            }
            return jobs.clone();
        }
        if legacy {
            tracing::warn!("profil : réglages de recherche d'emploi obsolètes, à déplacer dans la section [jobs] des modules");
        }
        let filters = profile.filters.clone().unwrap_or_default();
        let defaults = crate::matching::JobsConfig::default();
        crate::matching::JobsConfig {
            min_score: p.min_score.unwrap_or(defaults.min_score),
            location_keywords: p.location_keywords.clone().unwrap_or_default(),
            blacklist_companies: filters.blacklist_companies,
            blacklist_keywords: filters.blacklist_keywords,
        }
    }
}

/// Ancien emplacement du filtrage négatif, lu en repli.
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
    /// Villes acceptées en présentiel/hybride.
    /// Vide = full-remote strict, comportement historique.
    #[serde(default)]
    pub onsite_cities: Vec<String>,
    /// Ancien emplacement, lu en repli de la section [jobs] des modules.
    #[serde(default)]
    pub min_score: Option<f64>,
    /// Ancien emplacement, lu en repli de la section [jobs] des modules.
    #[serde(default)]
    pub location_keywords: Option<Vec<String>>,
}

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
        Ok(parse(&content)?)
    }
}

/// Wire protocol spoken by the configured inference endpoint.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LlmProtocol {
    #[default]
    Ollama,
    OpenaiCompatible,
    Messages,
}

#[derive(Clone, Default, Deserialize)]
pub struct LlmConfig {
    pub endpoint: String,
    pub model: String,
    #[serde(default)]
    pub protocol: LlmProtocol,
    /// Bearer credential, when the endpoint requires one.
    #[serde(default)]
    pub api_key: Option<String>,
    /// Nom de l'en-tête portant le jeton ; absent : `Authorization: Bearer`.
    #[serde(default)]
    pub api_key_header: Option<String>,
    /// En-têtes fixes envoyés tels quels à chaque requête.
    #[serde(default)]
    pub headers: HashMap<String, String>,
}

/// Les secrets (jeton, valeurs d'en-têtes) n'apparaissent jamais dans les traces.
impl std::fmt::Debug for LlmConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LlmConfig")
            .field("endpoint", &self.endpoint)
            .field("model", &self.model)
            .field("protocol", &self.protocol)
            .field("api_key", &self.api_key.as_ref().map(|_| "***"))
            .field("api_key_header", &self.api_key_header)
            .field("headers", &self.headers.keys().map(|k| (k, "***")).collect::<Vec<_>>())
            .finish()
    }
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
    /// Moteurs nommés, déclarés en plus des deux voies historiques.
    #[serde(default)]
    pub engines: HashMap<String, EngineConfig>,
    /// Association usage → nom de moteur. Un usage absent suit le routage
    /// historique.
    #[serde(default)]
    pub usages: HashMap<String, String>,
    /// Termes littéraux (insensibles à la casse) masqués avant tout envoi
    /// externe, en plus des identifiants détectés automatiquement.
    #[serde(default)]
    pub redact_terms: Vec<String>,
}

/// Où tourne un moteur : détermine s'il est soumis à la règle de sensibilité.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Placement {
    SelfHosted,
    External,
}

/// Moteur nommé : connexion, emplacement et plafond quotidien optionnel.
#[derive(Debug, Clone, Deserialize)]
pub struct EngineConfig {
    #[serde(flatten)]
    pub llm: LlmConfig,
    pub placement: Placement,
    #[serde(default)]
    pub daily_token_cap: Option<u64>,
    /// Moteur pris à la place quand celui-ci ne répond pas (machine éteinte).
    #[serde(default)]
    pub fallback: Option<String>,
    /// Nombre maximal d'outils proposés par appel ; absent : tous.
    #[serde(default)]
    pub max_tools: Option<usize>,
}

impl LlmRoutingConfig {
    pub fn from_file(path: impl AsRef<Path>) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path.as_ref())?;
        Ok(parse(&content)?)
    }
}

/// Erreur de lecture réduite à sa position : le message natif cite la ligne
/// fautive, donc potentiellement une valeur de configuration.
fn parse<T: serde::de::DeserializeOwned>(content: &str) -> anyhow::Result<T> {
    toml::from_str(content).map_err(|e| {
        let Some(start) = e.span().map(|s| s.start) else {
            return anyhow::anyhow!("fichier TOML invalide");
        };
        let before = &content[..start.min(content.len())];
        let line = before.matches('\n').count() + 1;
        let column = before.rsplit('\n').next().unwrap_or("").chars().count() + 1;
        anyhow::anyhow!("fichier TOML invalide (ligne {line}, colonne {column})")
    })
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

    #[test]
    fn erreur_de_lecture_sans_valeur_de_configuration() {
        let err = parse::<ModulesConfig>("[jobs]\nmin_score = \"valeur-secrete\"\n").unwrap_err().to_string();
        assert!(!err.contains("valeur-secrete"), "{err}");
        assert!(err.contains("ligne 2"), "{err}");
    }

    fn profile_with_legacy_jobs() -> Profile {
        let example = std::fs::read_to_string("config/profile.example.toml").unwrap();
        let legacy = example.replace("[preferences]\n", "[preferences]\nmin_score = 0.75\nlocation_keywords = [\"Ancien\"]\n")
            + "\n[filters]\nblacklist_companies = [\"AncienneCorp\"]\n";
        toml::from_str(&legacy).unwrap()
    }

    fn modules_with_jobs() -> ModulesConfig {
        toml::from_str("[jobs]\nmin_score = 0.5\nlocation_keywords = [\"Nouveau\"]\nblacklist_keywords = [\"terme\"]\n").unwrap()
    }

    #[test]
    fn jobs_lus_dans_la_section_des_modules() {
        let profile = Profile::from_file("config/profile.example.toml").unwrap();
        let jobs = modules_with_jobs().jobs(&profile);
        assert_eq!(jobs.min_score, 0.5);
        assert_eq!(jobs.location_keywords, vec!["Nouveau".to_string()]);
        assert_eq!(jobs.blacklist_keywords, vec!["terme".to_string()]);
        assert!(jobs.blacklist_companies.is_empty());
    }

    #[test]
    fn jobs_repli_sur_l_ancien_emplacement_du_profil() {
        let jobs = ModulesConfig::default().jobs(&profile_with_legacy_jobs());
        assert_eq!(jobs.min_score, 0.75);
        assert_eq!(jobs.location_keywords, vec!["Ancien".to_string()]);
        assert_eq!(jobs.blacklist_companies, vec!["AncienneCorp".to_string()]);
    }

    #[test]
    fn jobs_section_des_modules_prevaut_sur_le_profil() {
        let jobs = modules_with_jobs().jobs(&profile_with_legacy_jobs());
        assert_eq!(jobs.min_score, 0.5);
        assert_eq!(jobs.location_keywords, vec!["Nouveau".to_string()]);
        assert!(jobs.blacklist_companies.is_empty());
    }

    #[test]
    fn jobs_valeurs_par_defaut_sans_aucun_reglage() {
        let profile = Profile::from_file("config/profile.example.toml").unwrap();
        let jobs = ModulesConfig::default().jobs(&profile);
        assert_eq!(jobs.min_score, 0.60);
        assert!(jobs.location_keywords.is_empty());
    }
}
