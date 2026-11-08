//! Écritures dans le dossier synchronisé, toujours en deux temps : un aperçu
//! (rien ne bouge), puis `confirm=true`. Jamais d'écrasement ni d'effacement :
//! « supprimer » range le fichier dans la corbeille datée, que le client de
//! synchronisation propage comme le reste (et que la sauvegarde quotidienne
//! couvre de toute façon).
//!
//! Les documents des natures privées ne sont ni déplacés, ni renommés, ni mis
//! à la corbeille par ces outils.

use std::collections::{HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::path::Path;

use chrono::NaiveDate;
use rmcp::{handler::server::wrapper::Parameters, schemars, tool, tool_router};
use serde::Deserialize;

use crate::facts::{self, Facts, fold};
use crate::index::{Doc, Index, TRASH};
use crate::tools::DocServer;

type Out = Result<String, String>;

/// Lignes d'aperçu au plus (le reste est compté).
const MAX_LINES: usize = 80;

/// Chemin relatif sûr : ni absolu, ni `..`, ni caché, ni caractère interdit sous Windows.
pub fn safe_rel(p: &str) -> Result<String, String> {
    let p = p.trim().trim_start_matches("./");
    let bad = |m: &str| Err(format!("Chemin refusé « {p} » : {m}."));
    if p.is_empty() || p.starts_with('/') {
        return bad("chemin relatif au dossier des documents attendu");
    }
    if p.chars().any(|c| matches!(c, '\\' | ':' | '<' | '>' | '"' | '|' | '?' | '*') || c.is_control()) {
        return bad("caractère interdit");
    }
    let parts: Vec<&str> = p.trim_end_matches('/').split('/').collect();
    for c in &parts {
        if c.trim().is_empty() || *c == "." || *c == ".." || c.starts_with('.') || c.ends_with(' ') || c.ends_with('.')
        {
            return bad("segment vide, caché ou relatif");
        }
    }
    if parts[0] == TRASH {
        return bad("la corbeille n'est pas une destination");
    }
    Ok(parts.join("/"))
}

/// Minuscules sans accents, mots reliés par des tirets, coupé à `max` caractères.
pub fn slug(s: &str, max: usize) -> String {
    let mut out = String::new();
    for c in fold(s).chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c);
        } else if !out.ends_with('-') && !out.is_empty() {
            out.push('-');
        }
    }
    let out = out.trim_end_matches('-');
    if out.chars().count() <= max {
        return out.to_string();
    }
    let cut: String = out.chars().take(max).collect();
    let mut cut = match cut.rfind('-') {
        Some(i) if i > max / 2 => cut[..i].to_string(),
        _ => cut,
    };
    // Pas de nom coupé sur un mot vide (« garantie-panne-et », « produit-d »).
    while let Some((head, last)) = cut.rsplit_once('-') {
        if !STOP.contains(&last) {
            break;
        }
        cut = head.to_string();
    }
    cut
}

const STOP: [&str; 12] = ["a", "au", "d", "de", "des", "du", "en", "et", "l", "la", "le", "les"];

/// Nom déjà normalisé (minuscules, chiffres, tirets, soulignés) : posé à la main
/// ou par un passage précédent, il n'est plus reproposé.
pub fn is_normalised(stem: &str) -> bool {
    !stem.is_empty()
        && stem.contains(['-', '_'])
        && stem.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_')
}

/// Documents au-delà desquels une même date est jugée personnelle (naissance,
/// embauche…) plutôt que propre au document : elle ne nomme rien.
pub const COMMON_DATE_DOCS: usize = 4;

/// Dates citées par au moins `COMMON_DATE_DOCS` documents.
pub fn common_dates<'a>(all: impl Iterator<Item = &'a Facts>) -> HashSet<NaiveDate> {
    let mut n: HashMap<NaiveDate, usize> = HashMap::new();
    for f in all {
        let mine: HashSet<NaiveDate> = f.dates.iter().filter_map(|d| facts::parse(&d.date)).collect();
        for d in mine {
            *n.entry(d).or_default() += 1;
        }
    }
    n.into_iter().filter(|(_, c)| *c >= COMMON_DATE_DOCS).map(|(d, _)| d).collect()
}

/// Date qui nomme un document : émission, sinon début, contrôle ou paiement, sinon la plus ancienne
/// — les dates communes à beaucoup de documents mises à part.
pub fn doc_date(f: &Facts, common: &HashSet<NaiveDate>) -> Option<NaiveDate> {
    let own = |kind: Option<&str>| {
        f.dates
            .iter()
            .filter(|d| kind.is_none_or(|k| d.kind == k))
            .filter_map(|d| facts::parse(&d.date))
            .filter(|d| !common.contains(d))
            .min()
    };
    ["emission", "debut", "controle_technique", "paiement"].into_iter().find_map(|k| own(Some(k))).or_else(|| own(None))
}

/// Date déjà portée par un nom de fichier (`2026-03`, `2026-03-31`, `31_03_2026`…),
/// rendue au format AAAA-MM ou AAAA-MM-JJ : elle prime sur celle du modèle.
pub fn name_date(name: &str) -> Option<String> {
    let digits: Vec<(usize, &str)> = name
        .split(|c: char| !c.is_ascii_digit())
        .scan(0usize, |pos, w| {
            let at = *pos;
            *pos += w.len() + 1;
            Some((at, w))
        })
        .filter(|(_, w)| !w.is_empty())
        .collect();
    for i in 0..digits.len() {
        let n = |k: usize| digits.get(i + k).map(|x| x.1);
        let ok_m = |m: &str| m.len() == 2 && (1..=12).contains(&m.parse::<u32>().unwrap_or(0));
        let ok_d = |d: &str| d.len() == 2 && (1..=31).contains(&d.parse::<u32>().unwrap_or(0));
        let ok_y = |y: &str| y.len() == 4 && (1950..=2100).contains(&y.parse::<u32>().unwrap_or(0));
        if let (Some(y), Some(m)) = (n(0), n(1))
            && ok_y(y)
            && ok_m(m)
        {
            return Some(match n(2).filter(|d| ok_d(d)) {
                Some(d) => format!("{y}-{m}-{d}"),
                None => format!("{y}-{m}"),
            });
        }
        if let (Some(d), Some(m), Some(y)) = (n(0), n(1), n(2))
            && ok_d(d)
            && ok_m(m)
            && ok_y(y)
        {
            return Some(format!("{y}-{m}-{d}"));
        }
    }
    None
}

/// Nom normalisé `AAAA-MM-JJ_type_organisme.ext`, ou rien si le type est inexploitable
/// ou si le nom actuel est déjà celui-là.
pub fn proposed_name(current: &str, f: &Facts, common: &HashSet<NaiveDate>) -> Option<String> {
    let kind = slug(&f.type_document, 40);
    if kind.is_empty() || ["null", "none", "aucun"].contains(&kind.as_str()) || kind.contains("non-specifie") {
        return None;
    }
    let ext = Path::new(current).extension().map(|e| e.to_string_lossy().to_lowercase());
    let org = f.organisme.as_deref().map(|o| slug(&o.split_whitespace().take(3).collect::<Vec<_>>().join(" "), 24));
    let mut parts = Vec::new();
    let stem = Path::new(current).file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    if is_normalised(&stem) && name_date(&stem).is_none_or(|d| stem != d) {
        return None;
    }
    if let Some(d) = name_date(&stem).or_else(|| doc_date(f, common).map(|d| d.format("%Y-%m-%d").to_string())) {
        parts.push(d);
    }
    parts.push(kind);
    if let Some(o) = org.filter(|o| !o.is_empty() && o != "null") {
        parts.push(o);
    }
    let name = match ext {
        Some(e) => format!("{}.{e}", parts.join("_")),
        None => parts.join("_"),
    };
    (name != current).then_some(name)
}

fn parent(path: &str) -> &str {
    path.rsplit_once('/').map(|(p, _)| p).unwrap_or("")
}

fn join(dir: &str, name: &str) -> String {
    if dir.is_empty() { name.to_string() } else { format!("{dir}/{name}") }
}

/// Premier nom libre : `nom.ext`, puis `nom-2.ext`, `nom-3.ext`…
fn free(path: &str, taken: &dyn Fn(&str) -> bool) -> String {
    if !taken(path) {
        return path.to_string();
    }
    let (dir, name) = (parent(path), path.rsplit('/').next().unwrap_or(path));
    let (stem, ext) = match name.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s, format!(".{e}")),
        _ => (name, String::new()),
    };
    (2..).map(|n| join(dir, &format!("{stem}-{n}{ext}"))).find(|p| !taken(p)).expect("nom libre")
}

/// Déplace un fichier sous la racine, sans jamais écraser, et met l'index à jour.
fn apply_move(root: &Path, index: &Index, from: &str, to: &str) -> Result<(), String> {
    let (src, dst) = (root.join(from), root.join(to));
    if !src.is_file() {
        return Err(format!("{from} : introuvable"));
    }
    if dst.exists() {
        return Err(format!("{to} : existe déjà, rien n'est écrasé"));
    }
    if let Some(p) = dst.parent() {
        std::fs::create_dir_all(p).map_err(|e| format!("{to} : dossier impossible à créer ({e})"))?;
    }
    std::fs::rename(&src, &dst).map_err(|e| format!("{from} : déplacement impossible ({e})"))?;
    if to.split('/').next() == Some(TRASH) {
        let _ = index.forget(from);
    } else {
        let _ = index.rename(from, to);
    }
    // Dossiers vidés par le déplacement : retirés (jamais la racine).
    let mut dir = src.parent().map(Path::to_path_buf);
    while let Some(d) = dir {
        if d == root || std::fs::remove_dir(&d).is_err() {
            break;
        }
        dir = d.parent().map(Path::to_path_buf);
    }
    Ok(())
}

fn same_content(a: &Path, b: &Path) -> bool {
    match (std::fs::read(a), std::fs::read(b)) {
        (Ok(x), Ok(y)) => x == y,
        _ => false,
    }
}

fn content_hash(p: &Path) -> Option<u64> {
    let bytes = std::fs::read(p).ok()?;
    let mut h = std::collections::hash_map::DefaultHasher::new();
    bytes.hash(&mut h);
    Some(h.finish())
}

/// Groupes de fichiers au contenu identique (même taille, puis même octets).
pub fn duplicate_groups(root: &Path, docs: &[Doc]) -> Vec<Vec<Doc>> {
    let mut by_size: HashMap<i64, Vec<&Doc>> = HashMap::new();
    for d in docs.iter().filter(|d| d.size > 0) {
        by_size.entry(d.size).or_default().push(d);
    }
    let mut groups = Vec::new();
    for same in by_size.into_values().filter(|v| v.len() > 1) {
        let mut by_hash: HashMap<u64, Vec<&Doc>> = HashMap::new();
        for d in same {
            if let Some(h) = content_hash(&root.join(&d.path)) {
                by_hash.entry(h).or_default().push(d);
            }
        }
        for v in by_hash.into_values().filter(|v| v.len() > 1) {
            let first = root.join(&v[0].path);
            let g: Vec<Doc> = v
                .iter()
                .enumerate()
                .filter(|(i, d)| *i == 0 || same_content(&first, &root.join(&d.path)))
                .map(|(_, d)| (*d).clone())
                .collect();
            if g.len() > 1 {
                groups.push(g);
            }
        }
    }
    for g in &mut groups {
        g.sort_by(|a, b| a.path.cmp(&b.path));
    }
    groups.sort_by(|a, b| a[0].path.cmp(&b[0].path));
    groups
}

/// Exemplaire conservé : celui demandé, sinon une copie privée, sinon le moins profond (puis le plus court).
pub fn keeper<'a>(group: &'a [Doc], keep: &[String], private: &dyn Fn(&Doc) -> bool) -> &'a Doc {
    group
        .iter()
        .find(|d| keep.iter().any(|k| k.trim() == d.path))
        .or_else(|| group.iter().find(|d| private(d)))
        .unwrap_or_else(|| {
            group.iter().min_by_key(|d| (d.path.matches('/').count(), d.path.chars().count(), d.path.clone())).unwrap()
        })
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Move {
    /// Chemin actuel (tel que renvoyé par `docs_list` ou `docs_search`).
    from: String,
    /// Nouveau chemin complet, ou dossier terminé par « / » pour garder le nom.
    to: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct MoveArgs {
    /// Déplacements ou renommages à faire ensemble.
    moves: Vec<Move>,
    /// false (défaut) : aperçu seulement ; true : appliquer.
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct DeleteArgs {
    /// Documents à ranger dans la corbeille.
    paths: Vec<String>,
    /// false (défaut) : aperçu seulement ; true : appliquer.
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct DuplicatesArgs {
    /// Exemplaires à conserver (un par groupe) quand la proposition ne convient pas.
    keep: Option<Vec<String>>,
    /// false (défaut) : aperçu seulement ; true : ranger les copies dans la corbeille.
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct RenameArgs {
    /// Restreindre à une nature.
    nature: Option<String>,
    /// Restreindre à ces documents.
    paths: Option<Vec<String>>,
    /// false (défaut) : aperçu seulement ; true : appliquer.
    confirm: Option<bool>,
}

const PREVIEW: &str = "Aperçu — rien n'a été modifié. Relancer avec confirm=true pour appliquer.";

impl DocServer {
    fn writable(&self) -> Result<(), String> {
        if self.settings.write {
            Ok(())
        } else {
            Err("Écritures désactivées sur ce poste (DOCUMENTS_WRITE=1 pour les autoriser).".to_string())
        }
    }

    /// Document indexé et non privé.
    fn movable(&self, path: &str) -> Result<Doc, String> {
        let rel = safe_rel(path)?;
        let (d, _) = self
            .index
            .get(&rel)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("{rel} : document inconnu (chemin renvoyé par docs_list ou docs_search)"))?;
        if self.is_private(&d.nature) {
            return Err(format!("La nature « {} » n'est pas exposée.", d.nature));
        }
        Ok(d)
    }

    /// Applique ou décrit une liste de déplacements déjà validés.
    fn run_moves(&self, plan: &[(String, String)], confirm: bool, title: &str) -> Out {
        if plan.is_empty() {
            return Ok(format!("{title} : rien à faire."));
        }
        let mut lines = Vec::new();
        let mut failed = 0;
        for (from, to) in plan {
            if confirm {
                match apply_move(&self.settings.root, &self.index, from, to) {
                    Ok(()) => lines.push(format!("- {from} → {to}")),
                    Err(e) => {
                        failed += 1;
                        lines.push(format!("- ÉCHEC {e}"));
                    }
                }
            } else {
                lines.push(format!("- {from} → {to}"));
            }
        }
        let more = lines.len().saturating_sub(MAX_LINES);
        lines.truncate(MAX_LINES);
        if more > 0 {
            lines.push(format!("… et {more} autre(s)."));
        }
        let head = if confirm {
            format!("{title} : {} fait(s), {failed} en échec.", plan.len() - failed)
        } else {
            format!("{title} ({}). {PREVIEW}", plan.len())
        };
        Ok(format!("{head}\n{}", lines.join("\n")))
    }

    fn taken(&self, path: &str, planned: &HashSet<String>) -> bool {
        planned.contains(path) || self.settings.root.join(path).exists()
    }

    fn trash_target(&self, path: &str, planned: &HashSet<String>) -> String {
        let day = chrono::Utc::now().with_timezone(&self.settings.tz).format("%Y-%m-%d");
        free(&format!("{TRASH}/{day}/{path}"), &|p| self.taken(p, planned))
    }
}

#[tool_router(router = write_router, vis = "pub(crate)")]
impl DocServer {
    #[tool(
        description = "Déplacer ou renommer des documents (par lot), en deux temps : aperçu, puis confirm=true. Jamais d'écrasement ; les dossiers manquants sont créés, ceux vidés sont retirés."
    )]
    async fn doc_move(&self, Parameters(a): Parameters<MoveArgs>) -> Out {
        self.writable()?;
        let mut plan = Vec::new();
        let mut planned = HashSet::new();
        for m in &a.moves {
            let d = self.movable(&m.from)?;
            let to = safe_rel(&m.to)?;
            let to = if m.to.trim().ends_with('/') { join(&to, &d.name) } else { to };
            if to == d.path {
                continue;
            }
            let nature = to.split_once('/').map(|x| x.0).unwrap_or("");
            if self.is_private(nature) {
                return Err(format!("{to} : destination dans une nature non exposée."));
            }
            if self.taken(&to, &planned) {
                return Err(format!("{to} : existe déjà (ou déjà visé par ce lot), rien n'est écrasé."));
            }
            planned.insert(to.clone());
            plan.push((d.path, to));
        }
        self.run_moves(&plan, a.confirm.unwrap_or(false), "Déplacements")
    }

    #[tool(
        description = "Mettre des documents à la corbeille (dossier daté dans le drive, restaurable), en deux temps : aperçu, puis confirm=true. Rien n'est effacé."
    )]
    async fn doc_delete(&self, Parameters(a): Parameters<DeleteArgs>) -> Out {
        self.writable()?;
        let mut plan = Vec::new();
        let mut planned = HashSet::new();
        for p in &a.paths {
            let d = self.movable(p)?;
            let to = self.trash_target(&d.path, &planned);
            planned.insert(to.clone());
            plan.push((d.path, to));
        }
        self.run_moves(&plan, a.confirm.unwrap_or(false), "Mise à la corbeille")
    }

    #[tool(
        description = "Repérer les doublons exacts (même contenu) et proposer l'exemplaire à garder ; confirm=true range les autres copies dans la corbeille. `keep` impose l'exemplaire conservé d'un groupe."
    )]
    async fn duplicates(&self, Parameters(a): Parameters<DuplicatesArgs>) -> Out {
        let confirm = a.confirm.unwrap_or(false);
        if confirm {
            self.writable()?;
        }
        let docs = self.index.all().map_err(|e| e.to_string())?;
        let root = self.settings.root.clone();
        let groups = tokio::task::spawn_blocking(move || duplicate_groups(&root, &docs))
            .await
            .map_err(|_| "recherche des doublons interrompue".to_string())?;
        let keep = a.keep.unwrap_or_default();
        let private = |d: &Doc| self.is_private(&d.nature);
        let show = |d: &Doc| if private(d) { "document privé".to_string() } else { d.path.clone() };
        let mut lines = Vec::new();
        let mut plan = Vec::new();
        let mut planned = HashSet::new();
        for g in &groups {
            let k = keeper(g, &keep, &private);
            // Groupe entièrement privé : rien à montrer ni à toucher.
            if g.iter().all(&private) {
                continue;
            }
            lines.push(format!("- garder : {}", show(k)));
            for d in g.iter().filter(|d| d.path != k.path) {
                if private(d) {
                    lines.push("    copie privée laissée en place".to_string());
                    continue;
                }
                let to = self.trash_target(&d.path, &planned);
                planned.insert(to.clone());
                lines.push(format!("    corbeille : {}", d.path));
                plan.push((d.path.clone(), to));
            }
        }
        if plan.is_empty() {
            return Ok("Aucun doublon exact.".to_string());
        }
        if confirm {
            return self.run_moves(&plan, true, "Doublons mis à la corbeille");
        }
        let more = lines.len().saturating_sub(MAX_LINES);
        lines.truncate(MAX_LINES);
        if more > 0 {
            lines.push(format!("… et {more} ligne(s) de plus."));
        }
        Ok(format!(
            "{} copie(s) en trop dans {} groupe(s). {PREVIEW}\n{}",
            plan.len(),
            lines.iter().filter(|l| l.starts_with("- ")).count(),
            lines.join("\n")
        ))
    }

    #[tool(
        description = "Proposer des noms normalisés (AAAA-MM-JJ_type_organisme.ext) tirés des faits extraits, dans le même dossier ; confirm=true renomme. Les documents sans faits exploitables sont laissés tels quels."
    )]
    async fn rename_plan(&self, Parameters(a): Parameters<RenameArgs>) -> Out {
        let confirm = a.confirm.unwrap_or(false);
        if confirm {
            self.writable()?;
        }
        let nature = match a.nature.as_deref() {
            Some(n) => Some(self.nature(n)?),
            None => None,
        };
        let only: Option<HashSet<String>> =
            a.paths.map(|v| v.iter().map(|p| safe_rel(p)).collect::<Result<_, _>>()).transpose()?;
        let all: Vec<(Doc, Facts)> = self
            .index
            .all_facts()
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter_map(|(d, j)| serde_json::from_str(&j).ok().map(|f| (d, f)))
            .collect();
        let common = common_dates(all.iter().map(|x| &x.1));
        let mut plan = Vec::new();
        let mut planned = HashSet::new();
        for (doc, f) in all {
            if self.is_private(&doc.nature)
                || nature.as_deref().is_some_and(|n| n != doc.nature)
                || only.as_ref().is_some_and(|o| !o.contains(&doc.path))
            {
                continue;
            }
            let Some(name) = proposed_name(&doc.name, &f, &common) else { continue };
            let to = free(&join(parent(&doc.path), &name), &|p| self.taken(p, &planned));
            planned.insert(to.clone());
            plan.push((doc.path, to));
        }
        self.run_moves(&plan, confirm, "Renommages")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::facts::FactDate;

    #[test]
    fn unsafe_paths_are_refused() {
        assert_eq!(safe_rel("./Logement/bail.pdf").unwrap(), "Logement/bail.pdf");
        assert_eq!(safe_rel("Véhicule/").unwrap(), "Véhicule");
        for p in ["", "/etc/passwd", "../x", "a/../b", "a/.cache", "C:/x", "a\\b", "_Corbeille/x", "a//b", "a/b."] {
            assert!(safe_rel(p).is_err(), "{p}");
        }
    }

    #[test]
    fn names_are_normalised_from_facts() {
        let none = HashSet::new();
        assert_eq!(slug("Attestation d'assurance AUTOMOBILE", 40), "attestation-d-assurance-automobile");
        assert_eq!(slug("procès-verbal de contrôle technique périodique", 30), "proces-verbal-de-controle");
        assert_eq!(slug("garantie Panne Mécanique et Électronique Automobile", 30), "garantie-panne-mecanique");
        assert_eq!(
            slug("document d'information sur le produit d'assurance", 40),
            "document-d-information-sur-le-produit"
        );
        assert!(is_normalised("carte-grise_recto") && is_normalised("2024-07-19_revision_garage"));
        assert!(!is_normalised("Notice HPA") && !is_normalised("RIB") && !is_normalised("cv"));
        let any = Facts { type_document: "contrat".into(), ..Default::default() };
        assert_eq!(proposed_name("carte-grise_recto.jpg", &any, &HashSet::new()), None, "nom posé à la main");
        let f = Facts {
            type_document: "Attestation d'assurance automobile".into(),
            organisme: Some("Assureur Mutuel de l'Ouest".into()),
            dates: vec![
                FactDate { kind: "echeance".into(), date: "2027-06-01".into(), libelle: String::new() },
                FactDate { kind: "emission".into(), date: "09/06/2026".into(), libelle: String::new() },
            ],
            ..Default::default()
        };
        assert_eq!(
            proposed_name("0123456789.PDF", &f, &none).as_deref(),
            Some("2026-06-09_attestation-d-assurance-automobile_assureur-mutuel-de.pdf")
        );
        let same = "2026-06-09_attestation-d-assurance-automobile_assureur-mutuel-de.pdf";
        assert_eq!(proposed_name(same, &f, &none), None, "déjà normalisé");
        let junk = Facts { type_document: "null".into(), ..Default::default() };
        assert_eq!(proposed_name("x.jpg", &junk, &none), None);
        // Date de naissance citée partout : elle ne nomme pas le document.
        let born = facts::parse("1985-04-12").unwrap();
        let card = Facts {
            type_document: "diplôme".into(),
            dates: vec![
                FactDate { kind: "emission".into(), date: "1985-04-12".into(), libelle: String::new() },
                FactDate { kind: "autre".into(), date: "2021-09-30".into(), libelle: String::new() },
            ],
            ..Default::default()
        };
        assert_eq!(
            common_dates(std::iter::repeat_n(&card, 4)),
            HashSet::from([born, facts::parse("2021-09-30").unwrap()])
        );
        assert_eq!(proposed_name("d.jpg", &card, &HashSet::from([born])).as_deref(), Some("2021-09-30_diplome.jpg"));
        let undated = Facts { type_document: "Plan d'entretien".into(), ..Default::default() };
        assert_eq!(proposed_name("p.JPG", &undated, &none).as_deref(), Some("plan-d-entretien.jpg"));
    }

    #[test]
    fn dates_in_file_names_win() {
        assert_eq!(name_date("2026-03").as_deref(), Some("2026-03"));
        assert_eq!(name_date("quittance_15_01_2026").as_deref(), Some("2026-01-15"));
        assert_eq!(name_date("REF_20260101_12345"), None);
        assert_eq!(name_date("2024-02_prime").as_deref(), Some("2024-02"));
        assert_eq!(name_date("fournisseur-attestation-contrat-14-10-2023").as_deref(), Some("2023-10-14"));
        assert_eq!(name_date("operateur-facture-0612345678-0"), None);
        let f = Facts {
            type_document: "bulletin de salaire".into(),
            organisme: Some("Employeur".into()),
            dates: vec![FactDate { kind: "emission".into(), date: "2019-05-06".into(), libelle: String::new() }],
            ..Default::default()
        };
        assert_eq!(
            proposed_name("2026-03.pdf", &f, &HashSet::new()).as_deref(),
            Some("2026-03_bulletin-de-salaire_employeur.pdf")
        );
    }

    #[test]
    fn free_names_never_collide() {
        let taken = |p: &str| ["a/x.pdf", "a/x-2.pdf"].contains(&p);
        assert_eq!(free("a/x.pdf", &taken), "a/x-3.pdf");
        assert_eq!(free("a/y.pdf", &taken), "a/y.pdf");
        assert_eq!(free("noext", &|p| p == "noext"), "noext-2");
    }

    fn doc(path: &str, nature: &str) -> Doc {
        Doc {
            path: path.into(),
            nature: nature.into(),
            name: path.rsplit('/').next().unwrap().into(),
            kind: "pdf".into(),
            size: 1,
            mtime: 0,
            has_text: true,
        }
    }

    #[test]
    fn keeper_prefers_requested_then_private_then_shallowest() {
        let g = vec![doc("Auto/Dossier/Salaires/2026-03.pdf", "Auto"), doc("Salaires/2026-03.pdf", "Salaires")];
        let none = |_: &Doc| false;
        assert_eq!(keeper(&g, &[], &none).path, "Salaires/2026-03.pdf");
        assert_eq!(keeper(&g, &["Auto/Dossier/Salaires/2026-03.pdf".into()], &none).path, g[0].path);
        let auto = |d: &Doc| d.nature == "Auto";
        assert_eq!(keeper(&g, &[], &auto).path, g[0].path);
    }
}
