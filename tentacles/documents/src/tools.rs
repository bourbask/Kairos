//! Outils MCP de lecture du tentacule documents (écritures : `write.rs`, liste de contrôle : `missing.rs`).
//!
//! Les natures « privées » (pièces d'identité, signatures…) sont comptées mais
//! jamais exposées : ni liste de fichiers, ni recherche, ni extrait.

use std::path::PathBuf;
use std::sync::Arc;

use chrono::TimeZone;
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;

use crate::facts::{self, Deadline, Facts, fold};
use crate::index::Index;

type Out = Result<String, String>;

/// Longueur d'un passage autour d'une occurrence, et plafond d'un extrait.
const WINDOW: usize = 400;
const MAX_EXCERPT: usize = 3000;

pub struct Settings {
    pub root: PathBuf,
    /// Natures jamais exposées (comparaison insensible à la casse).
    pub private: Vec<String>,
    pub tz: chrono_tz::Tz,
    /// Modèle d'extraction des faits ; absent = extraction désactivée.
    pub model: Option<String>,
    /// Écritures (déplacer, renommer, corbeille) autorisées.
    pub write: bool,
}

/// Échéances de tous les documents. Natures privées : type et date seulement,
/// sans organisme ni chemin (rendu « document privé »).
pub fn all_deadlines(index: &Index, settings: &Settings, today: chrono::NaiveDate) -> Vec<(Deadline, bool)> {
    let private = |n: &str| settings.private.iter().any(|p| fold(p) == fold(n));
    let mut out = Vec::new();
    for (doc, json) in index.all_facts().unwrap_or_default() {
        let Ok(mut f) = serde_json::from_str::<Facts>(&json) else { continue };
        let is_private = private(&doc.nature);
        if is_private {
            f.organisme = None;
            f.reference = None;
        }
        for d in facts::deadlines(&doc.path, &f, today) {
            out.push((d, is_private));
        }
    }
    out.sort_by(|a, b| a.0.date.cmp(&b.0.date).then(a.0.label.cmp(&b.0.label)));
    out.dedup_by(|a, b| a.0.date == b.0.date && a.0.label == b.0.label);
    out
}

#[derive(Clone)]
pub struct DocServer {
    pub(crate) index: Arc<Index>,
    pub(crate) settings: Arc<Settings>,
    tool_router: ToolRouter<Self>,
}

/// Passages du texte autour des mots de la requête (insensible aux accents et à la casse).
pub fn passages(body: &str, query: &str) -> Vec<String> {
    let chars: Vec<char> = body.chars().collect();
    let folded: Vec<char> = chars.iter().map(|c| fold(&c.to_string()).chars().next().unwrap_or(*c)).collect();
    let hay: String = folded.iter().collect();
    let words: Vec<String> =
        query.split(|c: char| !c.is_alphanumeric()).filter(|w| w.chars().count() >= 3).map(fold).collect();
    let mut hits: Vec<usize> = Vec::new();
    for w in &words {
        let mut from = 0;
        while let Some(i) = hay[from..].find(w.as_str()) {
            let byte = from + i;
            hits.push(hay[..byte].chars().count());
            from = byte + w.len();
        }
    }
    hits.sort();
    let mut out = Vec::new();
    let mut last_end = 0usize;
    let mut total = 0;
    for h in hits {
        let start = h.saturating_sub(WINDOW / 2);
        if start < last_end && !out.is_empty() {
            continue;
        }
        let end = (h + WINDOW / 2).min(chars.len());
        let p: String = chars[start..end].iter().collect::<String>().split_whitespace().collect::<Vec<_>>().join(" ");
        total += p.chars().count();
        if total > MAX_EXCERPT {
            break;
        }
        out.push(format!("…{p}…"));
        last_end = end;
    }
    out
}

fn size(n: i64) -> String {
    if n >= 1_048_576 {
        format!("{:.1} Mo", n as f64 / 1_048_576.0).replace('.', ",")
    } else {
        format!("{} Ko", (n / 1024).max(1))
    }
}

#[derive(Deserialize, schemars::JsonSchema)]
struct NatureArgs {
    /// Nature (dossier de premier niveau), telle que listée par `docs_overview`.
    nature: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct SearchArgs {
    /// Mots à chercher dans le contenu et le nom des documents.
    query: String,
    /// Restreindre à une nature.
    nature: Option<String>,
    /// Nombre de résultats (10 par défaut, 30 au plus).
    limit: Option<usize>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct DeadlineArgs {
    /// Horizon en jours (180 par défaut, 1100 au plus).
    days: Option<i64>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct FactsArgs {
    /// Organisme, type de document ou mot-clé (ex. « assurance », « internet », « bail »).
    query: Option<String>,
    /// Restreindre à une nature.
    nature: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct BriefArgs {
    /// Contrat commun des sections de brief ; le contenu est le même matin et soir.
    #[allow(dead_code)]
    period: tentacle_common::BriefPeriod,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ExcerptArgs {
    /// Chemin du document tel que renvoyé par `docs_list` ou `docs_search`.
    path: String,
    /// Mots dont on veut les passages ; absent = début du document.
    query: Option<String>,
}

impl DocServer {
    pub fn new(index: Arc<Index>, settings: Arc<Settings>) -> Self {
        Self { index, settings, tool_router: Self::tool_router() + Self::write_router() + Self::missing_router() }
    }

    pub(crate) fn is_private(&self, nature: &str) -> bool {
        self.settings.private.iter().any(|p| fold(p) == fold(nature))
    }

    fn date(&self, ts: i64) -> String {
        self.settings.tz.timestamp_opt(ts, 0).single().map(|d| d.format("%d/%m/%Y").to_string()).unwrap_or_default()
    }

    fn deadline_line(&self, d: &Deadline, private: bool, today: chrono::NaiveDate) -> String {
        let left = (d.date - today).num_days();
        let when = match left {
            l if l < 0 => format!("dépassée depuis {} j", -l),
            0 => "aujourd'hui".to_string(),
            l => format!("dans {l} j"),
        };
        let src = if private { "document privé".to_string() } else { d.path.clone() };
        format!("- {} ({when}) — {} — {src}", d.date.format("%d/%m/%Y"), d.label)
    }

    fn today(&self) -> chrono::NaiveDate {
        chrono::Utc::now().with_timezone(&self.settings.tz).date_naive()
    }

    /// Noms exacts des natures privées présentes dans l'index (comparaison sans casse ni accents),
    /// pour que l'exclusion ne dépende pas de la façon dont la configuration les écrit.
    fn private_natures(&self) -> Result<Vec<String>, String> {
        let all = self.index.natures().map_err(|e| e.to_string())?;
        Ok(all.into_iter().map(|n| n.0).filter(|n| self.is_private(n)).collect())
    }

    /// Nature réelle (casse et accents tolérés).
    pub(crate) fn nature(&self, asked: &str) -> Result<String, String> {
        let all = self.index.natures().map_err(|e| e.to_string())?;
        all.iter().map(|n| n.0.clone()).find(|n| fold(n) == fold(asked.trim())).ok_or_else(|| {
            let names: Vec<_> = all.iter().map(|n| n.0.clone()).collect();
            format!("Nature inconnue « {} ». Natures : {}.", asked.trim(), names.join(", "))
        })
    }
}

#[tool_router]
impl DocServer {
    #[tool(
        description = "Vue d'ensemble des documents administratifs : natures (dossiers), nombre de documents, documents sans texte lisible (scans), dernière modification."
    )]
    async fn docs_overview(&self) -> Out {
        let n = self.index.natures().map_err(|e| e.to_string())?;
        if n.is_empty() {
            return Ok("Aucun document indexé.".to_string());
        }
        let total: i64 = n.iter().map(|x| x.1).sum();
        let lines: Vec<_> = n
            .iter()
            .map(|(name, count, no_text, last)| {
                if self.is_private(name) {
                    format!("- {name} : {count} document(s) — contenu non exposé")
                } else {
                    let scans = if *no_text > 0 { format!(", {no_text} sans texte lisible") } else { String::new() };
                    format!("- {name} : {count} document(s){scans}, modifié le {}", self.date(*last))
                }
            })
            .collect();
        let when = self.index.last_scan().map(|t| format!(" (index à jour du {})", self.date(t))).unwrap_or_default();
        let mut out = format!("{total} documents{when} :\n{}", lines.join("\n"));
        if let Some(m) = &self.settings.model
            && let Ok((ok, ko, todo)) = self.index.facts_status(m)
        {
            out.push_str(&format!("\nFaits extraits : {ok} document(s), {todo} en attente, {ko} en échec."));
        }
        Ok(out)
    }

    #[tool(description = "Lister les documents d'une nature : chemin, type, taille, date, lisible ou non.")]
    async fn docs_list(&self, Parameters(a): Parameters<NatureArgs>) -> Out {
        let nature = self.nature(&a.nature)?;
        if self.is_private(&nature) {
            return Err(format!("La nature « {nature} » n'est pas exposée."));
        }
        let docs = self.index.list(&nature).map_err(|e| e.to_string())?;
        let lines: Vec<_> = docs
            .iter()
            .map(|d| {
                format!(
                    "- {} — {}, {}, {}{}",
                    d.path,
                    d.kind,
                    size(d.size),
                    self.date(d.mtime),
                    if d.has_text { "" } else { " — sans texte lisible" }
                )
            })
            .collect();
        Ok(format!("{nature} ({}) :\n{}", docs.len(), lines.join("\n")))
    }

    #[tool(
        description = "Chercher dans le contenu et le nom des documents (insensible aux accents) : documents les plus pertinents avec un passage. Les scans sans texte ne sont trouvés que par leur nom."
    )]
    async fn docs_search(&self, Parameters(a): Parameters<SearchArgs>) -> Out {
        let nature = match a.nature.as_deref() {
            Some(n) => {
                let n = self.nature(n)?;
                if self.is_private(&n) {
                    return Err(format!("La nature « {n} » n'est pas exposée."));
                }
                Some(n)
            }
            None => None,
        };
        let limit = a.limit.unwrap_or(10).clamp(1, 30);
        let hits = self
            .index
            .search(&a.query, nature.as_deref(), &self.private_natures()?, limit)
            .map_err(|e| e.to_string())?;
        if hits.is_empty() {
            return Ok(format!("Aucun document ne correspond à « {} ».", a.query.trim()));
        }
        let lines: Vec<_> = hits
            .iter()
            .map(|(d, snip)| {
                let s = snip.split_whitespace().collect::<Vec<_>>().join(" ");
                if s.is_empty() {
                    format!("- {} ({})", d.path, self.date(d.mtime))
                } else {
                    format!("- {} ({}) : {s}", d.path, self.date(d.mtime))
                }
            })
            .collect();
        Ok(format!("Résultats :\n{}", lines.join("\n")))
    }

    #[tool(
        description = "Lister les échéances tirées des documents (fins de contrat, renouvellements, dates limites de résiliation, expirations, contrôle technique) sur un horizon donné, de la plus proche à la plus lointaine."
    )]
    async fn deadlines(&self, Parameters(a): Parameters<DeadlineArgs>) -> Out {
        let today = self.today();
        let horizon = today + chrono::Duration::days(a.days.unwrap_or(180).clamp(1, 1100));
        let all: Vec<_> =
            all_deadlines(&self.index, &self.settings, today).into_iter().filter(|(d, _)| d.date <= horizon).collect();
        if all.is_empty() {
            return Ok(format!("Aucune échéance d'ici le {}.", horizon.format("%d/%m/%Y")));
        }
        let lines: Vec<_> = all.iter().map(|(d, p)| self.deadline_line(d, *p, today)).collect();
        Ok(format!("Échéances jusqu'au {} :\n{}", horizon.format("%d/%m/%Y"), lines.join("\n")))
    }

    #[tool(
        description = "Donner les faits extraits des contrats et documents (type, organisme, référence, montants, dates), filtrés par mot-clé ou nature. Utile pour comparer une offre au contrat actuel."
    )]
    async fn contract_facts(&self, Parameters(a): Parameters<FactsArgs>) -> Out {
        let nature = match a.nature.as_deref() {
            Some(n) => Some(self.nature(n)?),
            None => None,
        };
        let q = a.query.as_deref().map(fold).unwrap_or_default();
        let words: Vec<&str> = q.split_whitespace().filter(|w| w.chars().count() >= 3).collect();
        let mut out = Vec::new();
        for (doc, json) in self.index.all_facts().map_err(|e| e.to_string())? {
            if self.is_private(&doc.nature) || nature.as_deref().is_some_and(|n| n != doc.nature) {
                continue;
            }
            let Ok(f) = serde_json::from_str::<Facts>(&json) else { continue };
            let hay = fold(&format!(
                "{} {} {} {} {}",
                doc.path,
                f.type_document,
                f.organisme.clone().unwrap_or_default(),
                f.resume,
                f.reference.clone().unwrap_or_default()
            ));
            if !words.iter().all(|w| hay.contains(w)) {
                continue;
            }
            let mut l = vec![format!(
                "{} — {}{}",
                doc.path,
                f.type_document,
                f.organisme.as_deref().map(|o| format!(", {o}")).unwrap_or_default()
            )];
            if let Some(r) = f.reference.as_deref().filter(|r| !r.is_empty()) {
                l.push(format!("  référence : {r}"));
            }
            if !f.resume.is_empty() {
                l.push(format!("  {}", f.resume));
            }
            for m in f.montants.iter().take(6) {
                l.push(format!(
                    "  {} : {} € ({})",
                    m.libelle,
                    format!("{:.2}", m.montant).replace('.', ","),
                    m.periodicite
                ));
            }
            for d in f.dates.iter().take(8) {
                if let Some(x) = facts::parse(&d.date) {
                    l.push(format!("  {} : {} ({})", d.kind.replace('_', " "), x.format("%d/%m/%Y"), d.libelle));
                }
            }
            out.push(l.join("\n"));
            if out.len() >= 12 {
                break;
            }
        }
        if out.is_empty() {
            return Ok("Aucun fait extrait ne correspond (extraction peut-être encore en cours : voir docs_overview)."
                .to_string());
        }
        Ok(out.join("\n\n"))
    }

    #[tool(description = "Obtenir la section documents d'un briefing : échéances des 30 prochains jours et retards.")]
    async fn brief_section(&self, Parameters(_a): Parameters<BriefArgs>) -> Out {
        let today = self.today();
        let soon: Vec<_> = all_deadlines(&self.index, &self.settings, today)
            .into_iter()
            .filter(|(d, _)| d.date <= today + chrono::Duration::days(30))
            .collect();
        if soon.is_empty() {
            return Ok("Documents : aucune échéance dans les 30 jours.".to_string());
        }
        let lines: Vec<_> = soon.iter().map(|(d, p)| self.deadline_line(d, *p, today)).collect();
        Ok(format!("Documents — échéances proches :\n{}", lines.join("\n")))
    }

    #[tool(
        description = "Lire des passages d'un document (autour des mots demandés, ou son début), jamais le document entier. À utiliser pour répondre à une question précise et citer le texte."
    )]
    async fn doc_excerpt(&self, Parameters(a): Parameters<ExcerptArgs>) -> Out {
        let (d, body) = self
            .index
            .get(a.path.trim())
            .map_err(|e| e.to_string())?
            .ok_or("Document inconnu : utilise un chemin renvoyé par docs_list ou docs_search.")?;
        if self.is_private(&d.nature) {
            return Err(format!("La nature « {} » n'est pas exposée.", d.nature));
        }
        let Some(body) = body else {
            return Ok(format!("« {} » n'a pas de texte lisible (scan ou image) : lecture des scans à venir.", d.path));
        };
        let parts = match a.query.as_deref().filter(|q| !q.trim().is_empty()) {
            Some(q) => passages(&body, q),
            None => {
                let head: String = body.chars().take(1500).collect();
                vec![head.split_whitespace().collect::<Vec<_>>().join(" ")]
            }
        };
        if parts.is_empty() {
            return Ok(format!(
                "« {} » : aucun passage ne contient « {} ».",
                d.path,
                a.query.unwrap_or_default().trim()
            ));
        }
        Ok(format!("{} ({}) :\n{}", d.path, self.date(d.mtime), parts.join("\n")))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for DocServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn passages_find_words_without_accents_and_merge_overlaps() {
        let body = format!(
            "{} La franchise est de 150 euros. {} Préavis : un mois. {}",
            "x ".repeat(300),
            "y ".repeat(300),
            "z"
        );
        let p = passages(&body, "franchise preavis");
        assert_eq!(p.len(), 2, "{p:?}");
        assert!(p[0].contains("franchise est de 150 euros"));
        assert!(p[1].contains("Préavis : un mois"));
        assert!(passages("rien à voir", "garantie").is_empty());
        // Deux mots proches : un seul passage.
        assert_eq!(passages("bris de glace inclus", "bris glace").len(), 1);
    }

    #[test]
    fn sizes() {
        assert_eq!(size(2048), "2 Ko");
        assert_eq!(size(3 * 1_048_576 / 2), "1,5 Mo");
    }
}
