//! Commandes directes vers un tentacule privé : reconnues par du code et
//! transmises telles quelles, sans passer par un moteur.

use serde_json::{Value, json};

use crate::tentacles::Tentacles;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    Follow(Vec<u32>),
    Unfollow(u32),
    Scenario { name: String, links: String },
    /// `target` : numéro de la liste suivie, ou nom de scénario.
    Projection { target: String, up: String, down: String },
    List,
}

/// Retire `prefix` (en minuscules) du début de `s`, casse ignorée.
fn strip_ci<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    let n = prefix.chars().count();
    let end = s.char_indices().nth(n).map_or(s.len(), |(i, _)| i);
    (s.chars().count() >= n && s[..end].to_lowercase() == prefix).then(|| &s[end..])
}

/// Mot-clé seul (reste vide) ou suivi d'un espace.
fn keyword<'a>(s: &'a str, word: &str) -> Option<&'a str> {
    strip_ci(s, word).and_then(|r| if r.is_empty() { Some(r) } else { r.strip_prefix(' ') })
}

/// Reste vide ou commençant par un chiffre : sinon, conversation ordinaire.
fn numeric(r: &str) -> bool {
    r.chars().next().is_none_or(|c| c.is_ascii_digit())
}

const USAGE_FOLLOW: &str = "Usage : suivre 1, 3 (numéros de la dernière liste de propositions).";
const USAGE_UNFOLLOW: &str = "Usage : ne plus suivre 2 (numéro dans la liste des questions suivies).";
const USAGE_SCENARIO: &str = "Usage : scénario <nom> : 1+, 4-";
const USAGE_PROJECTION: &str = "Usage : projection <numéro ou scénario> : hausse = … ; baisse = …";

/// `nom : reste`, les deux parties non vides.
fn split_colon(s: &str) -> Option<(String, String)> {
    let (a, b) = s.split_once(':')?;
    let (a, b) = (a.trim(), b.trim());
    (!a.is_empty() && !b.is_empty()).then(|| (a.to_string(), b.to_string()))
}

/// `hausse = … ; baisse = …`, dans n'importe quel ordre, les deux obligatoires.
fn up_down(s: &str) -> Option<(String, String)> {
    let (mut up, mut down) = (None, None);
    for part in s.split(';').filter(|p| !p.trim().is_empty()) {
        let (k, v) = part.split_once('=')?;
        let v = v.trim();
        if v.is_empty() {
            return None;
        }
        match k.trim().to_lowercase().as_str() {
            "hausse" => up = Some(v.to_string()),
            "baisse" => down = Some(v.to_string()),
            _ => return None,
        }
    }
    Some((up?, down?))
}

/// Message reconnu comme commande, sinon `None` (conversation ordinaire).
/// Mot-clé reconnu mais forme incorrecte : `Err` porte la réponse d'usage.
pub fn parse(msg: &str) -> Option<Result<Command, &'static str>> {
    let msg = msg.split_whitespace().collect::<Vec<_>>().join(" ");
    let m = msg.as_str();
    if ["marchés", "marches"].contains(&m.to_lowercase().as_str()) {
        return Some(Ok(Command::List));
    }
    if let Some(r) = keyword(m, "ne plus suivre").filter(|r| numeric(r)) {
        return Some(r.parse().map(Command::Unfollow).map_err(|_| USAGE_UNFOLLOW));
    }
    if let Some(r) = keyword(m, "suivre").filter(|r| numeric(r)) {
        let numbers: Option<Vec<u32>> =
            r.split([',', ' ']).filter(|p| !p.is_empty()).map(|p| p.parse().ok()).collect();
        return Some(numbers.filter(|n| !n.is_empty()).map(Command::Follow).ok_or(USAGE_FOLLOW));
    }
    if let Some(r) = keyword(m, "scénario").or_else(|| keyword(m, "scenario")) {
        return Some(split_colon(r).map(|(name, links)| Command::Scenario { name, links }).ok_or(USAGE_SCENARIO));
    }
    if let Some(r) = keyword(m, "projection") {
        let cmd = split_colon(r).and_then(|(target, rest)| {
            let (up, down) = up_down(&rest)?;
            Some(Command::Projection { target, up, down })
        });
        return Some(cmd.ok_or(USAGE_PROJECTION));
    }
    None
}

/// Exécute la commande sur le tentacule `prefix` ; renvoie son texte ou son erreur.
pub async fn run(cmd: Command, tentacles: &Tentacles, prefix: &str) -> String {
    let (tool, args): (&str, Value) = match cmd {
        Command::Follow(numbers) => ("follow", json!({"numbers": numbers})),
        Command::Unfollow(number) => ("unfollow", json!({"number": number})),
        Command::Scenario { name, links } => ("scenario", json!({"name": name, "links": links})),
        Command::Projection { target, up, down } => {
            let number = target.bytes().all(|b| b.is_ascii_digit()).then(|| target.parse::<u32>().ok()).flatten();
            let mut args = match number {
                Some(n) => json!({"number": n}),
                None => json!({"scenario_name": target}),
            };
            args["up"] = json!(up);
            args["down"] = json!(down);
            ("projection", args)
        }
        Command::List => ("followed_list", json!({})),
    };
    tentacles.call(&format!("{prefix}__{tool}"), args).await.unwrap_or_else(|e| e)
}
