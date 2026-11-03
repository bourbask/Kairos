//! Outils MCP du tentacule documents (lecture seule).
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
}

#[derive(Clone)]
pub struct DocServer {
    index: Arc<Index>,
    settings: Arc<Settings>,
    tool_router: ToolRouter<Self>,
}

fn fold(s: &str) -> String {
    s.to_lowercase()
        .chars()
        .map(|c| match c {
            'à' | 'â' | 'ä' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'î' | 'ï' => 'i',
            'ô' | 'ö' => 'o',
            'ù' | 'û' | 'ü' => 'u',
            'ç' => 'c',
            c => c,
        })
        .collect()
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
struct ExcerptArgs {
    /// Chemin du document tel que renvoyé par `docs_list` ou `docs_search`.
    path: String,
    /// Mots dont on veut les passages ; absent = début du document.
    query: Option<String>,
}

impl DocServer {
    pub fn new(index: Arc<Index>, settings: Arc<Settings>) -> Self {
        Self { index, settings, tool_router: Self::tool_router() }
    }

    fn is_private(&self, nature: &str) -> bool {
        self.settings.private.iter().any(|p| fold(p) == fold(nature))
    }

    fn date(&self, ts: i64) -> String {
        self.settings.tz.timestamp_opt(ts, 0).single().map(|d| d.format("%d/%m/%Y").to_string()).unwrap_or_default()
    }

    /// Noms exacts des natures privées présentes dans l'index (comparaison sans casse ni accents),
    /// pour que l'exclusion ne dépende pas de la façon dont la configuration les écrit.
    fn private_natures(&self) -> Result<Vec<String>, String> {
        let all = self.index.natures().map_err(|e| e.to_string())?;
        Ok(all.into_iter().map(|n| n.0).filter(|n| self.is_private(n)).collect())
    }

    /// Nature réelle (casse et accents tolérés).
    fn nature(&self, asked: &str) -> Result<String, String> {
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
        Ok(format!("{total} documents{when} :\n{}", lines.join("\n")))
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
