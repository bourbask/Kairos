//! Signaux prospectifs : énoncés datés portant une probabilité, agrégés depuis
//! une source externe puis synthétisés par le LLM en fonction du profil.
//!
//! La source (adresse, chemins, jeton) vient exclusivement de la configuration.

pub mod http;

use async_trait::async_trait;
use serde::Deserialize;

use crate::briefing::sanitize;
use crate::config::Profile;
use crate::llm::{LlmRouter, Sensitivity, Urgency};

/// Voie de génération déclarée par le module : la synthèse porte sur des sujets
/// suivis par l'utilisateur, et sa production n'attend aucun lecteur immédiat.
/// Le nom d'usage est celui auquel une concession de configuration se rattache.
pub const LLM_USAGE: &str = "signals";
pub const LLM_SENSITIVITY: Sensitivity = Sensitivity::Strategic;
pub const LLM_URGENCY: Urgency = Urgency::Deferred;

/// Source de signaux prospectifs et modèle chargé de leur synthèse. Toutes les
/// valeurs identifiantes vivent dans la configuration, jamais dans le code.
#[derive(Debug, Clone, Deserialize)]
pub struct SignalsConfig {
    pub base_url: String,
    pub api_key: String,
    pub auth_header: String,
    pub markets_path: String,
    pub forecasts_path: String,
    /// Sujets sur lesquels la synthèse doit se concentrer. Sans cette liste, la
    /// lecture des signaux se fait sous le seul angle professionnel du profil et
    /// conclut à l'absence de lien.
    #[serde(default)]
    pub interests: Vec<String>,
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

/// Nombre de signaux retenus par catégorie avant synthèse. Borne la taille du
/// prompt : au-delà, un modèle local dilue au lieu de hiérarchiser.
const MAX_PER_KIND: usize = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SignalKind {
    /// Estimation produite par un modèle.
    Forecast,
    /// Cotation d'un marché où des positions sont engagées.
    Market,
}

impl SignalKind {
    fn label(self) -> &'static str {
        match self {
            SignalKind::Forecast => "Estimation",
            SignalKind::Market => "Marché",
        }
    }
}

/// Un signal normalisé. `probability` est bornée à 0..=1 à la construction :
/// les sources expriment tantôt des ratios, tantôt des pourcentages.
#[derive(Debug, Clone, PartialEq)]
pub struct Signal {
    pub kind: SignalKind,
    pub statement: String,
    pub probability: f64,
    pub horizon: Option<String>,
    pub context: Option<String>,
}

impl Signal {
    pub fn new(
        kind: SignalKind,
        statement: &str,
        probability: f64,
        horizon: Option<&str>,
        context: Option<&str>,
    ) -> Self {
        Self {
            kind,
            statement: sanitize(statement),
            probability: normalize_probability(probability),
            horizon: horizon.map(sanitize).filter(|s| !s.is_empty()),
            context: context.map(sanitize).filter(|s| !s.is_empty()),
        }
    }

    fn percent(&self) -> u8 {
        (self.probability * 100.0).round() as u8
    }
}

/// Ramène une probabilité dans 0..=1. Une valeur > 1 est lue comme un
/// pourcentage ; toute valeur non finie retombe à 0.
fn normalize_probability(raw: f64) -> f64 {
    if !raw.is_finite() {
        return 0.0;
    }
    let ratio = if raw > 1.0 { raw / 100.0 } else { raw };
    ratio.clamp(0.0, 1.0)
}

#[async_trait]
pub trait SignalSource: Send + Sync {
    fn name(&self) -> &'static str;
    async fn fetch(&self) -> anyhow::Result<Vec<Signal>>;
}

/// Construit la consigne de synthèse. Les sujets suivis orientent la lecture ;
/// la sortie attendue est une mise en perspective actionnable, pas une
/// reformulation. Le reste du profil est volontairement écarté : il décrit une
/// recherche d'emploi et ne fait que détourner la lecture des signaux.
///
/// Les énoncés sont déjà assainis (`Signal::new`) : ils proviennent d'une API et
/// sont traités comme non fiables avant d'entrer dans le prompt.
pub fn build_synthesis_prompt(profile: &Profile, interests: &[String], signals: &[Signal]) -> String {
    let mut prompt = String::new();

    prompt.push_str(
        "Tu es un analyste. À partir des signaux ci-dessous, rédige une synthèse \
         courte en français (200 mots maximum).\n\n\
         Contraintes :\n\
         - Dégage les 2 ou 3 éléments qui comptent, ignore le reste.\n\
         - Lis-les uniquement à travers les sujets suivis ci-dessous.\n\
         - N'évoque ni carrière, ni emploi, ni compétences techniques : hors sujet ici.\n\
         - Distingue une estimation de modèle d'une cotation de marché : la seconde engage de l'argent.\n\
         - N'invente aucun chiffre, aucun événement, aucune source absente de la liste.\n\
         - Si les signaux sont trop faibles pour conclure, dis-le en une phrase et arrête-toi.\n\
         - Pas de titre, pas de préambule, pas de liste à puces : du texte suivi.\n\n",
    );

    prompt.push_str("Contexte du lecteur :\n");
    if !interests.is_empty() {
        prompt.push_str(&format!(
            "- Sujets suivis : {}\n",
            interests
                .iter()
                .map(|i| sanitize(i))
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    prompt.push_str(&format!(
        "- Localisation : {}\n",
        sanitize(&profile.contact.location)
    ));
    if !profile.preferences.countries.is_empty() {
        prompt.push_str(&format!(
            "- Zones d'intérêt : {}\n",
            profile.preferences.countries.join(", ")
        ));
    }

    prompt.push_str("\nSignaux :\n");
    for kind in [SignalKind::Market, SignalKind::Forecast] {
        for signal in signals.iter().filter(|s| s.kind == kind).take(MAX_PER_KIND) {
            prompt.push_str(&format!(
                "- [{}] {} — {} %",
                kind.label(),
                signal.statement,
                signal.percent(),
            ));
            if let Some(horizon) = &signal.horizon {
                prompt.push_str(&format!(" (horizon {horizon})"));
            }
            if let Some(context) = &signal.context {
                prompt.push_str(&format!(" — {context}"));
            }
            prompt.push('\n');
        }
    }

    prompt
}

/// Récupère les signaux et rend la synthèse. Le profil ne fournit que le cadre
/// de lecture (localisation, zones). Renvoie `None` dès qu'une étape n'aboutit
/// pas — configuration absente incluse : la section est omise, le briefing reste
/// produit.
pub async fn synthesize(
    config: Option<&SignalsConfig>,
    profile: &Profile,
    router: &LlmRouter,
) -> Option<String> {
    let config = config.filter(|c| c.is_usable())?;

    let source = match http::HttpSignalSource::new(config.clone()) {
        Ok(source) => source,
        Err(e) => {
            tracing::warn!("source de signaux inutilisable : {e}");
            return None;
        }
    };

    tracing::info!("consultation de la source {}", source.name());
    let signals = match source.fetch().await {
        Ok(signals) if signals.is_empty() => {
            tracing::info!("aucun signal disponible");
            return None;
        }
        Ok(signals) => signals,
        Err(e) => {
            tracing::warn!("récupération des signaux échouée : {e}");
            return None;
        }
    };

    let prompt = build_synthesis_prompt(profile, &config.interests, &signals);
    let client = match router.client(LLM_USAGE, LLM_SENSITIVITY, LLM_URGENCY) {
        Ok(client) => client,
        Err(e) => {
            tracing::warn!("synthèse non routée : {e}");
            return None;
        }
    };

    match client.generate(&prompt).await {
        Ok(text) => Some(sanitize_synthesis(&text)).filter(|t| !t.is_empty()),
        Err(e) => {
            tracing::warn!("synthèse échouée : {e}");
            None
        }
    }
}

/// Neutralise la sortie du modèle avant rendu Markdown. Le texte dérive d'une
/// entrée non fiable : on retire les constructions de lien et de mention, on
/// conserve la ponctuation d'un texte suivi.
pub fn sanitize_synthesis(raw: &str) -> String {
    raw.chars()
        .filter(|c| !c.is_control() || *c == '\n')
        .map(|c| match c {
            '[' | ']' | '(' | ')' | '`' | '@' | '<' | '>' | '\\' | '|' => ' ',
            other => other,
        })
        .take(4000)
        .collect::<String>()
        .trim()
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn signal(kind: SignalKind, statement: &str, probability: f64) -> Signal {
        Signal::new(kind, statement, probability, Some("7d"), None)
    }

    fn profile() -> Profile {
        Profile::from_file("config/profile.example.toml").unwrap()
    }

    #[test]
    fn ratio_and_percentage_inputs_converge() {
        assert_eq!(normalize_probability(0.53), 0.53);
        assert_eq!(normalize_probability(53.0), 0.53);
    }

    #[test]
    fn out_of_range_values_are_clamped() {
        assert_eq!(normalize_probability(-4.0), 0.0);
        assert_eq!(normalize_probability(400.0), 1.0);
    }

    /// Une valeur non finie signale une donnée corrompue, pas une certitude :
    /// elle retombe à 0 et n'entre donc jamais dans la synthèse comme un
    /// événement acquis.
    #[test]
    fn non_finite_values_are_treated_as_unusable() {
        assert_eq!(normalize_probability(f64::NAN), 0.0);
        assert_eq!(normalize_probability(f64::INFINITY), 0.0);
        assert_eq!(normalize_probability(f64::NEG_INFINITY), 0.0);
    }

    #[test]
    fn statement_metacharacters_are_neutralised() {
        let s = signal(SignalKind::Market, "Vote [ici](http://x) @everyone", 0.5);
        assert!(!s.statement.contains('['));
        assert!(!s.statement.contains('@'));
        assert!(!s.statement.contains(')'));
    }

    #[test]
    fn empty_optional_fields_collapse_to_none() {
        let s = Signal::new(SignalKind::Forecast, "X", 0.5, Some("  "), Some(""));
        assert!(s.horizon.is_none());
        assert!(s.context.is_none());
    }

    #[test]
    fn tracked_topics_reach_the_prompt_sanitised() {
        let prompt = build_synthesis_prompt(
            &profile(),
            &["géopolitique".into(), "[énergie](x)".into()],
            &[signal(SignalKind::Market, "A", 0.5)],
        );
        assert!(prompt.contains("Sujets suivis : géopolitique"));
        assert!(!prompt.contains("[énergie]"));
    }

    #[test]
    fn prompt_omits_topics_line_when_none_configured() {
        let prompt =
            build_synthesis_prompt(&profile(), &[], &[signal(SignalKind::Market, "A", 0.5)]);
        assert!(!prompt.contains("Sujets suivis"));
    }

    #[test]
    fn prompt_caps_each_kind() {
        let signals: Vec<_> = (0..MAX_PER_KIND + 5)
            .map(|i| signal(SignalKind::Market, &format!("marche {i}"), 0.5))
            .collect();
        let prompt = build_synthesis_prompt(&profile(), &[], &signals);
        assert_eq!(prompt.matches("[Marché]").count(), MAX_PER_KIND);
    }

    #[test]
    fn prompt_reports_probability_as_percent() {
        let prompt = build_synthesis_prompt(
            &profile(),
            &[],
            &[signal(SignalKind::Forecast, "escalade", 0.79)],
        );
        assert!(prompt.contains("79 %"), "prompt = {prompt}");
        assert!(prompt.contains("horizon 7d"));
    }

    /// Sans configuration, le module ne tente rien et n'échoue pas.
    #[tokio::test]
    async fn absent_configuration_yields_no_synthesis() {
        let router = LlmRouter::from_config(crate::config::LlmRoutingConfig::default());
        assert!(synthesize(None, &profile(), &router).await.is_none());
    }

    /// La déclaration du module est stratégique : le routeur refuse la voie
    /// externe pour cet usage, même quand elle est la seule configurée.
    #[test]
    fn module_declares_a_strategic_deferred_generation() {
        assert_eq!(LLM_SENSITIVITY, Sensitivity::Strategic);
        assert_eq!(LLM_URGENCY, Urgency::Deferred);

        let router = LlmRouter::from_config(crate::config::LlmRoutingConfig {
            self_hosted: None,
            external: Some(crate::config::LlmConfig {
                endpoint: "http://externe.invalid".into(),
                model: "m".into(),
                protocol: Default::default(),
                api_key: None,
            }),
            strategic_concessions: vec![],
        });
        assert!(router
            .client(LLM_USAGE, LLM_SENSITIVITY, LLM_URGENCY)
            .is_err());
    }

    #[test]
    fn synthesis_output_is_neutralised_but_keeps_prose() {
        let out = sanitize_synthesis("Voir [lien](http://x). Rien d'alarmant.\nSuite : ok.");
        assert!(!out.contains('['));
        assert!(!out.contains("http://x)"));
        assert!(out.contains("Rien d'alarmant."));
        assert!(out.contains('\n'));
    }
}
