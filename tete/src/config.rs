//! Configuration de la tête : fichier TOML pour la structure, environnement
//! pour tout ce qui est secret ou identifiant.

// Consommés par les tâches suivantes.
#![allow(dead_code)]

use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::Path;

use anyhow::{Context, Result, bail};
use chrono::{NaiveTime, Weekday};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TentacleEntry {
    /// Préfixe des outils du tentacule, choisi par l'opérateur.
    pub name: String,
    pub url: String,
    /// Nom de la variable d'environnement portant le jeton.
    pub token_env: String,
}

fn t(h: u32, m: u32) -> NaiveTime {
    NaiveTime::from_hms_opt(h, m, 0).expect("heure valide")
}

#[derive(Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Schedule {
    pub morning: NaiveTime,
    pub evening: NaiveTime,
    pub summaries: NaiveTime,
    /// Nuit : aucune sortie spontanée, mise en file.
    pub quiet_start: NaiveTime,
    pub quiet_end: NaiveTime,
}

impl Default for Schedule {
    fn default() -> Self {
        Self {
            morning: t(7, 30),
            evening: t(21, 0),
            summaries: t(3, 0),
            quiet_start: t(22, 30),
            quiet_end: t(7, 0),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkMode {
    OnSite,
    Remote,
    Off,
}

#[derive(Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct WorkRhythm {
    pub start: NaiveTime,
    pub end: NaiveTime,
    /// Jour absent = `Off`.
    pub days: HashMap<Weekday, WorkMode>,
}

impl Default for WorkRhythm {
    fn default() -> Self {
        Self { start: t(9, 0), end: t(18, 0), days: HashMap::new() }
    }
}

pub struct GatewayEnv {
    pub bind: SocketAddr,
    pub inbound_token: String,
    pub outbound_url: String,
    pub outbound_token: String,
}

#[derive(Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct HeadConfig {
    pub tentacles: Vec<TentacleEntry>,
    pub schedule: Schedule,
    pub work: WorkRhythm,
    pub interruptions_per_day: u32,
    pub suggestion_every_minutes: u32,
    pub session_idle_minutes: u32,
    #[serde(skip, default = "no_gateway")]
    pub gateway: GatewayEnv,
}

impl std::fmt::Debug for GatewayEnv {
    // Jetons masqués : jamais dans les journaux.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("GatewayEnv")
            .field("bind", &self.bind)
            .field("inbound_token", &"***")
            .field("outbound_url", &self.outbound_url)
            .field("outbound_token", &"***")
            .finish()
    }
}

fn no_gateway() -> GatewayEnv {
    GatewayEnv {
        bind: "0.0.0.0:8090".parse().expect("adresse valide"),
        inbound_token: String::new(),
        outbound_url: String::new(),
        outbound_token: String::new(),
    }
}

impl Default for HeadConfig {
    fn default() -> Self {
        Self {
            tentacles: Vec::new(),
            schedule: Schedule::default(),
            work: WorkRhythm::default(),
            interruptions_per_day: 2,
            suggestion_every_minutes: 120,
            session_idle_minutes: 30,
            gateway: no_gateway(),
        }
    }
}

fn required(env: &impl Fn(&str) -> Option<String>, name: &str) -> Result<String> {
    match env(name) {
        Some(v) if !v.is_empty() => Ok(v),
        _ => bail!("variable d'environnement {name} absente ou vide"),
    }
}

impl HeadConfig {
    pub fn load(path: &Path) -> Result<Self> {
        let text = std::fs::read_to_string(path)
            .with_context(|| format!("lecture de {}", path.display()))?;
        Self::from_parts(&text, |k| std::env::var(k).ok())
    }

    /// Cœur de `load`, avec l'environnement injecté (testable sans état global).
    pub fn from_parts(toml_text: &str, env: impl Fn(&str) -> Option<String>) -> Result<Self> {
        let mut cfg: HeadConfig = toml::from_str(toml_text).context("configuration invalide")?;
        // Le nom préfixe les outils (`<nom>__<outil>`) : il doit être sans ambiguïté.
        let mut seen = std::collections::HashSet::new();
        for e in &cfg.tentacles {
            let n = &e.name;
            if n.is_empty()
                || n.contains("__")
                || !n.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
            {
                bail!("nom de tentacule invalide : « {n} » (lettres, chiffres, _ et - ; pas de __)");
            }
            if !seen.insert(n) {
                bail!("nom de tentacule en double : « {n} »");
            }
        }
        for e in &cfg.tentacles {
            required(&env, &e.token_env)
                .with_context(|| format!("jeton du tentacule « {} »", e.name))?;
        }
        let bind = match env("TETE_BIND").filter(|v| !v.is_empty()) {
            Some(v) => v.parse().context("TETE_BIND invalide")?,
            None => no_gateway().bind,
        };
        cfg.gateway = GatewayEnv {
            bind,
            inbound_token: required(&env, "TETE_INBOUND_TOKEN")?,
            outbound_url: required(&env, "TETE_OUTBOUND_URL")?,
            outbound_token: required(&env, "TETE_OUTBOUND_TOKEN")?,
        };
        Ok(cfg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EXAMPLE: &str = include_str!("../../config/tete.example.toml");

    fn env_of<'a>(pairs: &'a [(&'a str, &'a str)]) -> impl Fn(&str) -> Option<String> + 'a {
        move |k| pairs.iter().find(|(n, _)| *n == k).map(|(_, v)| v.to_string())
    }

    const GATEWAY: [(&str, &str); 3] = [
        ("TETE_INBOUND_TOKEN", "in-secret"),
        ("TETE_OUTBOUND_URL", "http://gw.invalid/out"),
        ("TETE_OUTBOUND_TOKEN", "out-secret"),
    ];

    #[test]
    fn parses_example_with_env() {
        let c = HeadConfig::from_parts(EXAMPLE, env_of(&GATEWAY)).unwrap();
        assert_eq!(c.interruptions_per_day, 2);
        assert_eq!(c.suggestion_every_minutes, 120);
        assert_eq!(c.session_idle_minutes, 30);
        assert_eq!(c.schedule.morning, t(7, 30));
        assert_eq!(c.schedule.evening, t(21, 0));
        assert_eq!(c.schedule.summaries, t(3, 0));
        assert_eq!(c.schedule.quiet_start, t(22, 30));
        assert_eq!(c.schedule.quiet_end, t(7, 0));
        assert!(c.work.days.is_empty());
        assert!(c.tentacles.is_empty());
        assert_eq!(c.gateway.bind.to_string(), "0.0.0.0:8090");
    }

    #[test]
    fn empty_file_uses_defaults_and_days_parse() {
        let toml = "[work.days]\nmonday = \"on_site\"\nfriday = \"remote\"\n";
        let c = HeadConfig::from_parts(toml, env_of(&GATEWAY)).unwrap();
        assert_eq!(c.work.days[&Weekday::Mon], WorkMode::OnSite);
        assert_eq!(c.work.days[&Weekday::Fri], WorkMode::Remote);
        assert_eq!(c.interruptions_per_day, 2);
    }

    #[test]
    fn missing_env_is_named_error() {
        let env = [("TETE_INBOUND_TOKEN", "a"), ("TETE_OUTBOUND_TOKEN", "b")];
        let err = HeadConfig::from_parts(EXAMPLE, env_of(&env)).unwrap_err();
        assert!(format!("{err:#}").contains("TETE_OUTBOUND_URL"));
    }

    #[test]
    fn token_value_never_in_error() {
        // Le premier jeton (valide) est lu avant l'échec du second.
        let toml = "[[tentacles]]\nname = \"a\"\nurl = \"http://a.invalid\"\ntoken_env = \"A_TOKEN\"\n\
                    [[tentacles]]\nname = \"b\"\nurl = \"http://b.invalid\"\ntoken_env = \"B_TOKEN\"\n";
        let env = [("A_TOKEN", "a-secret"), ("B_TOKEN", "")];
        let err = format!("{:#}", HeadConfig::from_parts(toml, env_of(&env)).unwrap_err());
        assert!(err.contains("B_TOKEN"));
        assert!(!err.contains("a-secret"));

        // Une valeur invalide lue dans l'environnement n'est pas recopiée.
        let env = [("TETE_BIND", "bind-secret"), ("TETE_INBOUND_TOKEN", "in-secret")];
        let err = format!("{:#}", HeadConfig::from_parts("", env_of(&env)).unwrap_err());
        assert!(err.contains("TETE_BIND"));
        assert!(!err.contains("bind-secret") && !err.contains("in-secret"));
    }

    #[test]
    fn tentacle_names_validated() {
        let one = |name: &str| format!("[[tentacles]]\nname = \"{name}\"\nurl = \"http://a.invalid\"\ntoken_env = \"A_TOKEN\"\n");
        let env = [GATEWAY[0], GATEWAY[1], GATEWAY[2], ("A_TOKEN", "a")];
        assert!(HeadConfig::from_parts(&one("agenda-2_b"), env_of(&env)).is_ok());
        for bad in ["", "a b", "a__b", "é", "a.b"] {
            assert!(HeadConfig::from_parts(&one(bad), env_of(&env)).is_err(), "{bad:?} accepté");
        }
        let twice = format!("{}{}", one("a"), one("a"));
        assert!(HeadConfig::from_parts(&twice, env_of(&env)).is_err());
    }

    #[test]
    fn debug_redacts_tokens_and_unknown_fields_fail() {
        let c = HeadConfig::from_parts("", env_of(&GATEWAY)).unwrap();
        let d = format!("{c:?}");
        assert!(!d.contains("in-secret") && !d.contains("out-secret"));
        assert!(HeadConfig::from_parts("[schedule]\nquiet_strat = \"22:00\"\n", env_of(&GATEWAY)).is_err());
    }
}
