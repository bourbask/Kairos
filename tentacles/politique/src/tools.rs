//! Outils `app_*` du module Politique : réservés à l'application, jamais proposés au moteur.

use std::sync::Arc;

use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::donnees::{COMPARAISONS, Donnees, Etat, Identite, META, fold, listes};

type Out = Result<String, String>;

/// Taille maximale d'une réponse, en caractères.
pub const MAX_REPONSE: usize = 64_000;

#[derive(Clone)]
pub struct PolitiqueServer {
    donnees: Arc<Donnees>,
    tool_router: ToolRouter<Self>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct IdArgs {
    /// Identifiant (rubrique ou identité).
    id: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ComparerArgs {
    /// Identités à comparer (2 à 3).
    ids: Vec<String>,
    /// Thème comparable (liste donnée par l'accueil).
    theme: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ChercherArgs {
    /// Termes recherchés.
    q: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct FichierArgs {
    /// Nom du fichier (sans extension).
    id: String,
    /// Numéro de page, à partir de 0.
    #[serde(default)]
    page: u32,
}

/// Taille visée d'une page de fichier, en caractères (marge sous `MAX_REPONSE` pour l'enveloppe).
pub const BUDGET_PAGE: usize = 60_000;

/// Longueur maximale d'une recherche, en caractères.
const MAX_RECHERCHE: usize = 200;

/// Titre et résumé des rubriques connues ; un fichier inconnu garde son nom.
const RUBRIQUES: &[(&str, &str, &str)] = &[
    ("affirmations", "Déclarations vérifiées", "Affirmations confrontées aux faits, avec verdict et sources."),
    ("argent", "Argent", "Financement des partis et de la vie politique."),
    ("bilan", "Bilans", "Évaluations chiffrées des mesures passées."),
    ("budgets", "Budgets", "Coût des programmes, chiffres annoncés et évalués."),
    ("dit-vote", "Dit / voté", "Votes croisés avec les déclarations publiques."),
    ("dossiers", "Dossiers", "Contenus qui circulent, vérifiés affirmation par affirmation."),
    ("evaluateurs", "Qui chiffre ?", "Les producteurs des chiffres et leur indépendance."),
    ("medias", "Médias", "Propriété, audiences et encadrement des médias."),
    ("niveau-de-vie", "Niveau de vie", "Effet des mesures proposées sur le revenu des ménages."),
    ("probite", "Probité", "Affaires, influence et transparence."),
    ("revirements", "Revirements", "Positions qui ont changé dans le temps."),
    ("rhetorique", "Rhétorique", "Procédés rhétoriques : les repérer, les questionner."),
    ("villes", "Résultats locaux", "Bilans des exécutifs locaux."),
    ("votes", "Votes", "Votes des groupes au Parlement."),
];

fn titre(id: &str) -> (String, String) {
    RUBRIQUES.iter().find(|r| r.0 == id).map_or_else(|| (id.to_string(), String::new()), |r| (r.1.to_string(), r.2.to_string()))
}

fn json_out(v: Value) -> Out {
    let s = serde_json::to_string(&v).map_err(|x| x.to_string())?;
    if s.chars().count() > MAX_REPONSE {
        return Err("Réponse trop volumineuse.".into());
    }
    Ok(s)
}

fn taille(v: &Value) -> usize {
    v.to_string().chars().count()
}

/// Découpe un fichier en pages de `budget` caractères au plus. La plus grande liste (premier niveau,
/// ou second niveau d'un objet) est découpée en tranches consécutives : la page 0 garde toutes les
/// autres clés, les suivantes ne portent que la clé découpée. Un élément trop gros fait sa page seul.
fn paginer(v: &Value, budget: usize) -> Vec<Value> {
    if taille(v) <= budget {
        return vec![v.clone()];
    }
    let Value::Object(o) = v else { return vec![v.clone()] };
    // Chemin (1 ou 2 clés) de la liste la plus lourde.
    let mut candidats: Vec<(Vec<&str>, &Vec<Value>)> = Vec::new();
    for (k, x) in o {
        match x {
            Value::Array(a) => candidats.push((vec![k], a)),
            Value::Object(sub) => candidats.extend(sub.iter().filter_map(|(s, y)| y.as_array().map(|a| (vec![k.as_str(), s.as_str()], a)))),
            _ => {}
        }
    }
    let Some((chemin, liste)) = candidats.into_iter().max_by_key(|(_, a)| a.iter().map(taille).sum::<usize>()) else { return vec![v.clone()] };
    // Enveloppe portant une tranche donnée à la place de la liste.
    let avec = |base: Value, tranche: &[Value]| {
        let mut b = base;
        let mut cur = &mut b;
        for k in &chemin {
            cur = &mut cur[*k];
        }
        *cur = Value::Array(tranche.to_vec());
        b
    };
    let squelette_page0 = avec(v.clone(), &[]);
    let squelette_suite = {
        let mut m = json!({});
        let mut cur = &mut m;
        for k in &chemin[..chemin.len() - 1] {
            cur[*k] = json!({});
            cur = &mut cur[*k];
        }
        cur[chemin[chemin.len() - 1]] = json!([]);
        m
    };
    let mut pages = Vec::new();
    let mut debut = 0;
    while debut < liste.len() || pages.is_empty() {
        let (base, ouverture) = if pages.is_empty() { (&squelette_page0, taille(&squelette_page0)) } else { (&squelette_suite, taille(&squelette_suite)) };
        let mut fin = debut;
        let mut total = ouverture;
        while fin < liste.len() && total + taille(&liste[fin]) + 1 <= budget {
            total += taille(&liste[fin]) + 1;
            fin += 1;
        }
        // Élément seul trop gros : sa page à lui (seule la page 0 peut rester sans élément).
        if fin == debut && !pages.is_empty() {
            fin += 1;
        }
        pages.push(avec(base.clone(), &liste[debut..fin]));
        debut = fin;
    }
    pages
}

fn identite_json(i: &Identite) -> Value {
    let mut v = json!({"id": i.id, "nom": i.nom, "parti": i.parti, "couleur": i.couleur});
    if let Some(a) = &i.avatar {
        v["avatar"] = a.clone();
    }
    if let Some(t) = &i.genre {
        v["type"] = json!(t);
    }
    v
}

/// Toutes les chaînes d'une valeur, repliées : texte de recherche d'une entrée.
fn texte_plein(v: &Value, out: &mut String) {
    match v {
        Value::String(s) => {
            out.push(' ');
            out.push_str(&fold(s));
        }
        Value::Array(a) => a.iter().for_each(|x| texte_plein(x, out)),
        Value::Object(o) => o.values().for_each(|x| texte_plein(x, out)),
        _ => {}
    }
}

/// Entrées retenues, regroupées par rubrique, jusqu'au budget de taille ; `true` si coupé.
fn groupes(etat: &Etat, garde: impl Fn(&Value) -> bool) -> (Vec<Value>, bool) {
    // Marge pour l'enveloppe de la réponse.
    let mut reste = MAX_REPONSE - 2_000;
    let mut out = Vec::new();
    for (id, v) in etat.rubriques() {
        let mut entrees = Vec::new();
        for (chemin, liste) in listes(v) {
            for (index, e) in liste.iter().enumerate().filter(|(_, e)| garde(e)) {
                let item = json!({"chemin": chemin, "index": index, "entree": e});
                let taille = item.to_string().chars().count() + 1;
                if taille > reste {
                    if !entrees.is_empty() {
                        out.push(json!({"id": id, "titre": titre(id).0, "entrees": entrees}));
                    }
                    return (out, true);
                }
                reste -= taille;
                entrees.push(item);
            }
        }
        if !entrees.is_empty() {
            out.push(json!({"id": id, "titre": titre(id).0, "entrees": entrees}));
        }
    }
    (out, false)
}

impl PolitiqueServer {
    pub fn new(donnees: Arc<Donnees>) -> Self {
        Self { donnees, tool_router: Self::tool_router() }
    }
}

#[tool_router]
impl PolitiqueServer {
    #[tool(description = "Accueil Politique : date de vérification, identités, rubriques, thèmes comparables (application).")]
    fn app_politique_accueil(&self) -> Out {
        let etat = self.donnees.etat();
        let rubriques: Vec<Value> = etat
            .rubriques()
            .map(|(id, v)| {
                let (titre, resume) = titre(id);
                json!({"id": id, "titre": titre, "resume": resume, "entrees": listes(v).iter().map(|(_, l)| l.len()).sum::<usize>()})
            })
            .collect();
        let themes: Vec<Value> = etat
            .fichiers
            .get(COMPARAISONS)
            .and_then(|c| c.as_array())
            .map(|a| a.iter().filter_map(|t| Some(json!({"id": t.get("id")?.as_str()?, "titre": t.get("titre")?.as_str()?}))).collect())
            .unwrap_or_default();
        json_out(json!({
            "verifie_le": etat.fichiers.get(META).and_then(|m| m.get("verifie_le")).cloned().unwrap_or(Value::Null),
            "identites": etat.identites.iter().map(identite_json).collect::<Vec<_>>(),
            "rubriques": rubriques,
            "themes": themes,
            "presentations": etat.presentations,
        }))
    }

    #[tool(description = "Un fichier de données, par pages : { id, page, pages, version, contenu } (application).")]
    fn app_politique_fichier(&self, Parameters(a): Parameters<FichierArgs>) -> Out {
        let etat = self.donnees.etat();
        // Recherche parmi les fichiers chargés : l'identifiant ne sert jamais de chemin.
        let (id, v) = etat.fichiers.get_key_value(&a.id).ok_or("Fichier inconnu.")?;
        let pages = paginer(v, BUDGET_PAGE);
        let contenu = pages.get(a.page as usize).ok_or_else(|| format!("Page hors bornes : le fichier compte {} page(s), numérotées à partir de 0.", pages.len()))?;
        json_out(json!({"id": id, "page": a.page, "pages": pages.len(), "version": etat.versions.get(id), "contenu": contenu}))
    }

    #[tool(description = "Une rubrique telle quelle, avec l'identité rattachée à chaque entrée (application).")]
    fn app_politique_rubrique(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        let etat = self.donnees.etat();
        // Recherche parmi les rubriques chargées : l'identifiant ne sert jamais de chemin.
        let (id, v) = etat.rubriques().find(|(k, _)| **k == a.id).ok_or("Rubrique inconnue.")?;
        let rattachements: serde_json::Map<String, Value> =
            listes(v).into_iter().map(|(chemin, l)| (chemin, l.iter().map(|e| json!(etat.rattachement(e))).collect())).collect();
        let presentation: Vec<&Value> = etat.presentations.iter().filter(|p| p["rubrique"] == **id).collect();
        json_out(json!({"id": id, "titre": titre(id).0, "contenu": v.as_ref(), "rattachements": rattachements, "presentation": presentation}))
            .map_err(|_| "Rubrique trop volumineuse pour être affichée.".to_string())
    }

    #[tool(description = "Fiche d'une identité : ses entrées regroupées par rubrique, avec leurs sources (application).")]
    fn app_politique_fiche(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        let etat = self.donnees.etat();
        let i = etat.identite(&a.id).ok_or("Identité inconnue.")?;
        let (rubriques, tronque) = groupes(&etat, |e| etat.rattachement(e).contains(&i.id.as_str()));
        json_out(json!({"identite": identite_json(i), "rubriques": rubriques, "tronque": tronque}))
    }

    #[tool(description = "Comparer 2 à 3 identités sur un thème, chaque valeur avec sa source (application).")]
    fn app_politique_comparer(&self, Parameters(a): Parameters<ComparerArgs>) -> Out {
        if !(2..=3).contains(&a.ids.len()) {
            return Err("Choisir 2 ou 3 identités.".into());
        }
        if a.ids.iter().enumerate().any(|(n, id)| a.ids[..n].contains(id)) {
            return Err("Identité en double.".into());
        }
        let etat = self.donnees.etat();
        let ids = a.ids.iter().map(|id| etat.identite(id).ok_or("Identité inconnue.")).collect::<Result<Vec<_>, _>>()?;
        let theme = etat
            .fichiers
            .get(COMPARAISONS)
            .and_then(|c| c.as_array())
            .and_then(|l| l.iter().find(|t| t.get("id").and_then(Value::as_str) == Some(a.theme.as_str())))
            .ok_or("Thème inconnu.")?;
        let s = |k: &str| theme.get(k).and_then(Value::as_str).unwrap_or_default();
        let vide = Vec::new();
        let liste = etat
            .fichiers
            .get(s("rubrique"))
            .and_then(|r| listes(r).into_iter().find(|(c, _)| c == s("chemin")).map(|(_, l)| l))
            .unwrap_or(&vide);
        // `source` désigne un champ s'il figure dans une entrée de la liste, sinon c'est un texte.
        let source = s("source");
        let source_champ = liste.iter().any(|e| e.get(source).is_some());
        // ponytail: première entrée qui désigne l'identité ; une identité citée deux fois n'est pas fusionnée.
        let entrees: Vec<Option<&Value>> = ids
            .iter()
            .map(|i| {
                let cles = i.cles();
                liste.iter().find(|e| e.get(s("cle_identite")).and_then(Value::as_str).is_some_and(|l| cles.contains(&fold(l))))
            })
            .collect();
        let lignes: Vec<Value> = theme
            .get("indicateurs")
            .and_then(Value::as_array)
            .map(|inds| {
                inds.iter()
                    .map(|ind| {
                        let champ = ind.get("champ").and_then(Value::as_str).unwrap_or_default();
                        let valeurs: Vec<Value> = ids
                            .iter()
                            .zip(&entrees)
                            .map(|(i, e)| {
                                let valeur = e.and_then(|e| e.get(champ)).cloned().unwrap_or(Value::Null);
                                let src = match e {
                                    None => Value::Null,
                                    Some(e) if source_champ => e.get(source).cloned().unwrap_or(Value::Null),
                                    Some(_) => json!(source),
                                };
                                json!({"id": i.id, "valeur": valeur, "source": src})
                            })
                            .collect();
                        json!({"libelle": ind.get("libelle"), "unite": ind.get("unite"), "valeurs": valeurs})
                    })
                    .collect()
            })
            .unwrap_or_default();
        json_out(json!({
            "theme": {"id": s("id"), "titre": s("titre")},
            "identites": ids.iter().map(|i| identite_json(i)).collect::<Vec<_>>(),
            "lignes": lignes,
        }))
    }

    #[tool(description = "Chercher des termes dans toutes les rubriques, sans accents ni casse (application).")]
    fn app_politique_chercher(&self, Parameters(a): Parameters<ChercherArgs>) -> Out {
        if a.q.chars().count() > MAX_RECHERCHE {
            return Err(format!("Recherche trop longue ({MAX_RECHERCHE} caractères au plus)."));
        }
        let q = fold(&a.q);
        if q.is_empty() {
            return Err("Recherche vide.".into());
        }
        let termes: Vec<&str> = q.split(' ').collect();
        let etat = self.donnees.etat();
        let (groupes, tronque) = groupes(&etat, |e| {
            let mut t = String::new();
            texte_plein(e, &mut t);
            termes.iter().all(|m| t.contains(m))
        });
        let resultats: Vec<Value> = groupes.into_iter().map(|g| json!({"rubrique": g["id"], "titre": g["titre"], "entrees": g["entrees"]})).collect();
        json_out(json!({"q": a.q, "resultats": resultats, "tronque": tronque}))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for PolitiqueServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::{Path, PathBuf};
    use std::time::{Duration, SystemTime};

    const FIXTURES: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/donnees");

    fn server_sur(dir: impl Into<PathBuf>) -> PolitiqueServer {
        PolitiqueServer::new(Arc::new(Donnees::new(dir)))
    }
    fn server() -> PolitiqueServer {
        server_sur(FIXTURES)
    }
    fn v(out: Out) -> Value {
        serde_json::from_str(&out.unwrap()).unwrap()
    }
    fn rubrique(s: &PolitiqueServer, id: &str) -> Value {
        v(s.app_politique_rubrique(Parameters(IdArgs { id: id.into() })))
    }
    fn fiche(s: &PolitiqueServer, id: &str) -> Out {
        s.app_politique_fiche(Parameters(IdArgs { id: id.into() }))
    }
    fn comparer(s: &PolitiqueServer, ids: &[&str], theme: &str) -> Out {
        s.app_politique_comparer(Parameters(ComparerArgs { ids: ids.iter().map(|x| x.to_string()).collect(), theme: theme.into() }))
    }
    fn chercher(s: &PolitiqueServer, q: &str) -> Out {
        s.app_politique_chercher(Parameters(ChercherArgs { q: q.into() }))
    }

    /// Copie des fixtures dans un dossier propre au test.
    fn copie(nom: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("kairos-politique-{}-{nom}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        for e in std::fs::read_dir(FIXTURES).unwrap().flatten() {
            std::fs::copy(e.path(), dir.join(e.file_name())).unwrap();
        }
        dir
    }
    /// Écrit un fichier avec une date de modification distincte (la granularité du système ne suffit pas).
    fn ecrire(path: &Path, contenu: &str, decalage: u64) {
        std::fs::write(path, contenu).unwrap();
        let f = std::fs::File::options().write(true).open(path).unwrap();
        f.set_modified(SystemTime::now() + Duration::from_secs(decalage)).unwrap();
    }

    fn fichier(s: &PolitiqueServer, id: &str, page: u32) -> Out {
        s.app_politique_fichier(Parameters(FichierArgs { id: id.into(), page }))
    }
    /// Fichier `dit-vote` fictif de plus de `BUDGET_PAGE` caractères, avec une clé annexe.
    fn gros_dit_vote() -> String {
        let scrutins: Vec<Value> = (0..120)
            .map(|i| json!({"id": format!("s-{i}"), "titre": format!("Loi fictive {i}"), "objet": "Texte inventé pour les tests. ".repeat(25)}))
            .collect();
        json!({"SCRUTINS": scrutins, "ANNEXE": {"note": "Annexe fictive."}}).to_string()
    }
    /// Date de modification fixe, pour des échantillons identiques d'une machine à l'autre.
    fn date_fixe(path: &Path) {
        let f = std::fs::File::options().write(true).open(path).unwrap();
        f.set_modified(SystemTime::UNIX_EPOCH + Duration::from_secs(1_800_000_000)).unwrap();
    }

    #[test]
    fn fichier_petit_en_une_page() {
        let r = v(fichier(&server(), "evaluateurs", 0));
        assert_eq!((r["id"].as_str(), r["page"].as_u64(), r["pages"].as_u64()), (Some("evaluateurs"), Some(0), Some(1)));
        let brut: Value = serde_json::from_str(&std::fs::read_to_string(Path::new(FIXTURES).join("evaluateurs.json")).unwrap()).unwrap();
        assert_eq!(r["contenu"], brut);
        assert!(r["version"].as_str().is_some_and(|x| !x.is_empty() && x.chars().all(|c| c.is_ascii_hexdigit() || c == '-')));
    }

    #[test]
    fn paginer_decoupe_la_plus_grande_liste() {
        let brut: Value = serde_json::from_str(&std::fs::read_to_string(Path::new(FIXTURES).join("dit-vote.json")).unwrap()).unwrap();
        let pages = paginer(&brut, 1_000);
        assert!(pages.len() >= 3, "{}", pages.len());
        let tout: Vec<Value> = pages.iter().flat_map(|p| p["SCRUTINS"].as_array().unwrap().clone()).collect();
        assert_eq!(Value::Array(tout), brut["SCRUTINS"]);
        // Élément seul plus gros que le budget : sa page à lui.
        let seul = paginer(&brut, 10);
        assert_eq!(seul.len(), 5);
        assert!(seul[1..].iter().all(|p| p["SCRUTINS"].as_array().unwrap().len() == 1));
        // Liste au second niveau : `clé.sous_clé`, les pages suivantes gardent le chemin.
        let imbrique = json!({"AUTRE": "x", "G": {"rows": vec!["aaaa"; 6], "titre": "t"}});
        let p = paginer(&imbrique, 60);
        assert!(p.len() > 1);
        assert_eq!(p[0]["AUTRE"], "x");
        assert_eq!(p[0]["G"]["titre"], "t");
        assert!(p[1].get("AUTRE").is_none() && p[1]["G"]["rows"].is_array());
        assert_eq!(p.iter().map(|x| x["G"]["rows"].as_array().unwrap().len()).sum::<usize>(), 6);
    }

    #[test]
    fn fichier_gros_decoupe_sans_perte() {
        let dir = copie("gros-fichier");
        ecrire(&dir.join("dit-vote.json"), &gros_dit_vote(), 10);
        let s = server_sur(&dir);
        let p0 = v(fichier(&s, "dit-vote", 0));
        let n = p0["pages"].as_u64().unwrap() as u32;
        assert!(n >= 2);
        let brut: Value = serde_json::from_str(&gros_dit_vote()).unwrap();
        assert_eq!(p0["contenu"]["ANNEXE"], brut["ANNEXE"]);
        let mut tout = Vec::new();
        for page in 0..n {
            let txt = fichier(&s, "dit-vote", page).unwrap();
            assert!(txt.chars().count() <= MAX_REPONSE);
            let r: Value = serde_json::from_str(&txt).unwrap();
            assert!(taille(&r["contenu"]) <= BUDGET_PAGE);
            if page > 0 {
                assert!(r["contenu"].get("ANNEXE").is_none());
            }
            tout.extend(r["contenu"]["SCRUTINS"].as_array().unwrap().clone());
        }
        assert_eq!(Value::Array(tout), brut["SCRUTINS"]);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn fichier_pages_portent_la_version() {
        let dir = copie("version");
        ecrire(&dir.join("dit-vote.json"), &gros_dit_vote(), 10);
        let s = server_sur(&dir);
        let version = |page| v(fichier(&s, "dit-vote", page))["version"].clone();
        assert_eq!(version(0), version(1));
        let avant = version(0);
        ecrire(&dir.join("dit-vote.json"), &gros_dit_vote(), 20);
        assert_ne!(version(0), avant);
        assert_eq!(version(0), version(1));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn fichier_inconnu_ou_page_hors_bornes_erreur_claire() {
        let s = server();
        assert_eq!(fichier(&s, "inexistant", 0).unwrap_err(), "Fichier inconnu.");
        assert_eq!(fichier(&s, "../meta", 0).unwrap_err(), "Fichier inconnu.");
        assert!(fichier(&s, "evaluateurs", 1).unwrap_err().contains("hors bornes"));
    }

    #[test]
    fn carte_est_un_fichier_de_service() {
        let s = server();
        assert!(!v(s.app_politique_accueil())["rubriques"].as_array().unwrap().iter().any(|r| r["id"] == "carte"));
        assert!(s.app_politique_rubrique(Parameters(IdArgs { id: "carte".into() })).is_err());
        assert!(v(fichier(&s, "carte", 0))["contenu"]["regions"].is_array());
        // Les autres fichiers de service sont servis aussi.
        assert!(v(fichier(&s, "meta", 0))["contenu"]["verifie_le"].is_string());
        assert!(v(fichier(&s, "identites", 0))["contenu"].is_array());
    }

    #[test]
    fn nouvelles_rubriques_titrees() {
        let h = v(server().app_politique_accueil());
        for (id, titre) in [("dit-vote", "Dit / voté"), ("dossiers", "Dossiers"), ("evaluateurs", "Qui chiffre ?")] {
            let r = h["rubriques"].as_array().unwrap().iter().find(|r| r["id"] == id).unwrap_or_else(|| panic!("{id} absente"));
            assert_eq!(r["titre"], titre);
            assert!(!r["resume"].as_str().unwrap().is_empty());
        }
    }

    #[test]
    fn home_lists_identities_rubrics_themes() {
        let h = v(server().app_politique_accueil());
        assert_eq!(h["verifie_le"], "2026-01-15");
        let ids: Vec<&str> = h["identites"].as_array().unwrap().iter().map(|i| i["id"].as_str().unwrap()).collect();
        assert_eq!(ids, ["a", "b", "c", "parti-bleu"]);
        assert_eq!(h["identites"][0]["nom"], "Candidat A");
        assert_eq!(h["identites"][0]["parti"], "Parti Bleu");
        assert_eq!(h["identites"][0]["couleur"], "#3366cc");
        assert!(h["identites"][0].get("alias").is_none());
        let rub = h["rubriques"].as_array().unwrap();
        let noms: Vec<&str> = rub.iter().map(|r| r["id"].as_str().unwrap()).collect();
        assert_eq!(noms, ["affirmations", "argent", "budgets", "dit-vote", "divers", "dossiers", "evaluateurs", "niveau-de-vie", "rhetorique", "votes"]);
        let budgets = rub.iter().find(|r| r["id"] == "budgets").unwrap();
        assert_eq!(budgets["titre"], "Budgets");
        assert!(!budgets["resume"].as_str().unwrap().is_empty());
        assert_eq!(budgets["entrees"], 4);
        // Fichier inconnu : son nom pour titre.
        assert_eq!(rub.iter().find(|r| r["id"] == "divers").unwrap()["titre"], "divers");
        // Tableaux de premier niveau et `rows` comptent.
        assert_eq!(rub.iter().find(|r| r["id"] == "argent").unwrap()["entrees"], 4);
        assert_eq!(h["themes"], json!([{"id": "budget", "titre": "Budget des programmes"}, {"id": "niveau-de-vie", "titre": "Niveau de vie"}]));
    }

    #[test]
    fn entry_attached_by_alias() {
        let r = rubrique(&server(), "budgets");
        assert_eq!(r["titre"], "Budgets");
        assert_eq!(r["contenu"]["REF"], 1.5);
        // « Mme B » → b ; « Cand. A » repris sans casse ni ponctuation.
        assert_eq!(r["rattachements"]["PROGRAMS"], json!([["a", "parti-bleu"], ["b"], ["c"]]));
        assert_eq!(r["rattachements"]["GAPS"], json!([["a"]]));
        // Matrice : chaînes de la ligne ; `rows` d'un objet.
        let a = rubrique(&server(), "argent");
        assert_eq!(a["rattachements"]["PARTIS"], json!([["parti-bleu"], []]));
        assert_eq!(a["rattachements"]["AIDE.rows"], json!([["parti-bleu"], []]));
    }

    #[test]
    fn entry_with_two_identities_belongs_to_both() {
        let s = server();
        // Alias commun à deux identités.
        assert_eq!(rubrique(&s, "votes")["rattachements"]["COH"], json!([["a", "b"]]));
        for id in ["a", "b"] {
            let f = v(fiche(&s, id));
            let votes = f["rubriques"].as_array().unwrap().iter().find(|r| r["id"] == "votes").expect(id);
            assert_eq!(votes["entrees"][0]["entree"]["camp"], "Liste commune");
        }
    }

    #[test]
    fn unattached_entry_stays_in_rubric() {
        let r = rubrique(&server(), "affirmations");
        assert_eq!(r["contenu"]["CLAIMS"].as_array().unwrap().len(), 2);
        assert_eq!(r["rattachements"]["CLAIMS"], json!([["a"], []]));
        assert_eq!(r["rattachements"]["CAMPS"], json!([[], []]));
        let rh = rubrique(&server(), "rhetorique");
        assert_eq!(rh["contenu"]["RHET"][0]["t"], "Homme de paille");
    }

    #[test]
    fn fiche_groups_by_rubric_with_sources() {
        let f = v(fiche(&server(), "a"));
        assert_eq!(f["identite"]["id"], "a");
        assert_eq!(f["identite"]["nom"], "Candidat A");
        let rubs: Vec<&str> = f["rubriques"].as_array().unwrap().iter().map(|r| r["id"].as_str().unwrap()).collect();
        assert_eq!(rubs, ["affirmations", "budgets", "niveau-de-vie", "votes"]);
        let budgets = f["rubriques"].as_array().unwrap().iter().find(|r| r["id"] == "budgets").unwrap();
        assert_eq!(budgets["titre"], "Budgets");
        let e = budgets["entrees"].as_array().unwrap();
        assert_eq!(e.len(), 2);
        // Listes dans l'ordre des clés.
        assert_eq!(e[0]["chemin"], "GAPS");
        assert_eq!(e[1]["chemin"], "PROGRAMS");
        assert_eq!(e[1]["index"], 0);
        assert_eq!(e[1]["entree"]["src"], "https://example.org/budget-a");
        let aff = f["rubriques"].as_array().unwrap().iter().find(|r| r["id"] == "affirmations").unwrap();
        assert_eq!(aff["entrees"][0]["entree"]["src"][0][1], "https://example.org/elec");
    }

    #[test]
    fn compare_two_and_three() {
        let s = server();
        let c = v(comparer(&s, &["a", "b"], "budget"));
        assert_eq!(c["theme"], json!({"id": "budget", "titre": "Budget des programmes"}));
        assert_eq!(c["identites"].as_array().unwrap().iter().map(|i| i["id"].clone()).collect::<Vec<_>>(), [json!("a"), json!("b")]);
        assert_eq!(c["lignes"][0]["libelle"], "Déficit");
        assert_eq!(c["lignes"][0]["unite"], "Md€");
        assert_eq!(c["lignes"][0]["valeurs"][0], json!({"id": "a", "valeur": 3.1, "source": "https://example.org/budget-a"}));
        assert_eq!(c["lignes"][0]["valeurs"][1], json!({"id": "b", "valeur": 4.2, "source": "https://example.org/budget-b"}));
        let c3 = v(comparer(&s, &["c", "a", "b"], "budget"));
        assert_eq!(c3["lignes"][1]["valeurs"].as_array().unwrap().len(), 3);
        // Zéro reste zéro.
        assert_eq!(c3["lignes"][0]["valeurs"][0]["valeur"], 0);
        // Source textuelle du thème.
        let n = v(comparer(&s, &["a", "b"], "niveau-de-vie"));
        assert_eq!(n["lignes"][0]["valeurs"][1], json!({"id": "b", "valeur": -80, "source": "Évaluation fictive, example.org"}));
        assert!(comparer(&s, &["a"], "budget").is_err());
        assert!(comparer(&s, &["a", "b", "c", "parti-bleu"], "budget").is_err());
        assert!(comparer(&s, &["a", "a"], "budget").is_err());
    }

    #[test]
    fn missing_value_is_not_zero() {
        let s = server();
        let c = v(comparer(&s, &["a", "b"], "budget"));
        // b n'a pas d'effet par ménage ; sa source reste celle de l'entrée.
        assert_eq!(c["lignes"][1]["valeurs"][1], json!({"id": "b", "valeur": null, "source": "https://example.org/budget-b"}));
        // c absent du thème : ni valeur ni source.
        let n = v(comparer(&s, &["a", "c"], "niveau-de-vie"));
        assert_eq!(n["lignes"][0]["valeurs"][1], json!({"id": "c", "valeur": null, "source": null}));
    }

    #[test]
    fn unknown_identity_or_theme_is_clear_error() {
        let s = server();
        assert_eq!(fiche(&s, "zz").unwrap_err(), "Identité inconnue.");
        assert_eq!(comparer(&s, &["a", "zz"], "budget").unwrap_err(), "Identité inconnue.");
        assert_eq!(comparer(&s, &["a", "b"], "zz").unwrap_err(), "Thème inconnu.");
        assert_eq!(s.app_politique_rubrique(Parameters(IdArgs { id: "../meta".into() })).unwrap_err(), "Rubrique inconnue.");
        assert_eq!(s.app_politique_rubrique(Parameters(IdArgs { id: "identites".into() })).unwrap_err(), "Rubrique inconnue.");
    }

    #[test]
    fn search_ignores_accents_and_case() {
        let s = server();
        let r = v(chercher(&s, "ELECTRICITE, a double !"));
        assert_eq!(r["q"], "ELECTRICITE, a double !");
        let res = r["resultats"].as_array().unwrap();
        assert_eq!(res.len(), 1);
        assert_eq!(res[0]["rubrique"], "affirmations");
        assert_eq!(res[0]["titre"], "Déclarations vérifiées");
        assert_eq!(res[0]["entrees"][0]["entree"]["v"], "Exagéré");
        assert_eq!(res[0]["entrees"][0]["chemin"], "CLAIMS");
        let r = v(chercher(&s, "fictive"));
        let rubs: Vec<&str> = r["resultats"].as_array().unwrap().iter().map(|x| x["rubrique"].as_str().unwrap()).collect();
        assert!(rubs.contains(&"budgets") && rubs.contains(&"affirmations"), "{rubs:?}");
        assert!(v(chercher(&s, "introuvable"))["resultats"].as_array().unwrap().is_empty());
        assert!(chercher(&s, " ?! ").is_err());
    }

    #[test]
    fn file_change_is_reloaded() {
        let dir = copie("reload");
        let s = server_sur(&dir);
        assert_eq!(rubrique(&s, "budgets")["contenu"]["REF"], 1.5);
        ecrire(&dir.join("budgets.json"), r#"{"REF": 2.5, "PROGRAMS": []}"#, 10);
        assert_eq!(rubrique(&s, "budgets")["contenu"]["REF"], 2.5);
        ecrire(&dir.join("nouvelle.json"), r#"{"L": ["x"]}"#, 10);
        assert!(v(s.app_politique_accueil())["rubriques"].as_array().unwrap().iter().any(|r| r["id"] == "nouvelle"));
        std::fs::remove_file(dir.join("nouvelle.json")).unwrap();
        assert!(s.app_politique_rubrique(Parameters(IdArgs { id: "nouvelle".into() })).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn broken_file_keeps_last_good_version() {
        let dir = copie("broken");
        let s = server_sur(&dir);
        assert_eq!(rubrique(&s, "budgets")["contenu"]["REF"], 1.5);
        ecrire(&dir.join("budgets.json"), r#"{"REF": 9, "PROGRAMS": ["#, 10);
        assert_eq!(rubrique(&s, "budgets")["contenu"]["REF"], 1.5);
        ecrire(&dir.join("identites.json"), "pas du json", 10);
        assert_eq!(v(s.app_politique_accueil())["identites"].as_array().unwrap().len(), 4);
        // Réparé : la nouvelle version est prise.
        ecrire(&dir.join("budgets.json"), r#"{"REF": 3}"#, 20);
        assert_eq!(rubrique(&s, "budgets")["contenu"]["REF"], 3);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn all_tools_are_app_prefixed() {
        let names: Vec<String> = server().tool_router.list_all().into_iter().map(|t| t.name.to_string()).collect();
        assert_eq!(names.len(), 6);
        assert!(names.contains(&"app_politique_fichier".to_string()));
        assert!(names.iter().all(|n| n.starts_with("app_politique_")), "{names:?}");
    }

    #[test]
    fn responses_are_bounded() {
        let dir = copie("bounded");
        let gros: Vec<Value> = (0..3000).map(|i| json!({"who": "Candidat A", "t": format!("Entrée fictive numéro {i} {}", "x".repeat(40))})).collect();
        ecrire(&dir.join("gros.json"), &json!({"L": gros}).to_string(), 10);
        let s = server_sur(&dir);
        for out in [chercher(&s, "fictive"), fiche(&s, "a")] {
            let txt = out.unwrap();
            assert!(txt.chars().count() <= MAX_REPONSE, "{}", txt.len());
            assert_eq!(serde_json::from_str::<Value>(&txt).unwrap()["tronque"], true);
        }
        assert_eq!(v(chercher(&server(), "fictive"))["tronque"], false);
        // Rubrique trop grande pour une réponse : erreur claire.
        assert!(s.app_politique_rubrique(Parameters(IdArgs { id: "gros".into() })).unwrap_err().contains("volumineuse"));
        assert!(chercher(&s, &"mot ".repeat(100)).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn presentation_hints_exposed() {
        let s = server();
        let h = v(s.app_politique_accueil());
        // Fichier de service, pas une rubrique.
        assert!(!h["rubriques"].as_array().unwrap().iter().any(|r| r["id"] == "presentation"));
        let p = h["presentations"].as_array().unwrap();
        // Vue ou rôle inconnus : indication ignorée.
        assert_eq!(p.len(), 6);
        assert_eq!(
            p[0],
            json!({"rubrique": "affirmations", "chemin": "CLAIMS", "vue": "verifications",
                   "champs": {"titre": "q", "verdict": "v", "faits": "facts", "source": "src", "theme": "theme", "camp": "camp"}})
        );
        let r = rubrique(&s, "affirmations");
        let vues: Vec<&str> = r["presentation"].as_array().unwrap().iter().map(|x| x["vue"].as_str().unwrap()).collect();
        assert_eq!(vues, ["verifications", "bulle"]);
        assert_eq!(rubrique(&s, "budgets")["presentation"].as_array().unwrap().len(), 1);
        assert_eq!(rubrique(&s, "argent")["presentation"], json!([]));
        // Les entrées de fiche gardent rubrique et chemin pour appliquer l'indication.
        let f = v(fiche(&s, "a"));
        let votes = f["rubriques"].as_array().unwrap().iter().find(|r| r["id"] == "votes").unwrap();
        assert!(votes["entrees"].as_array().unwrap().iter().any(|e| e["chemin"] == "DV" && e["entree"]["said"].is_string()));
    }

    #[test]
    fn invalid_presentation_ignored() {
        let dir = copie("presentation");
        let s = server_sur(&dir);
        let n = |s: &PolitiqueServer| v(s.app_politique_accueil())["presentations"].as_array().unwrap().len();
        assert_eq!(n(&s), 6);
        // Illisible : dernière version valide gardée.
        ecrire(&dir.join("presentation.json"), "[{", 10);
        assert_eq!(n(&s), 6);
        // Forme inattendue : rien, l'application devine.
        ecrire(&dir.join("presentation.json"), r#"{"rubrique": "votes"}"#, 20);
        assert_eq!(n(&s), 0);
        ecrire(
            &dir.join("presentation.json"),
            r#"[{"rubrique": "votes", "chemin": "AN_V", "vue": "votes", "champs": {"titre": 3}},
                {"rubrique": "votes", "vue": "votes", "champs": {}},
                {"rubrique": "votes", "chemin": "AN_V", "vue": "votes", "champs": {"titre": "t"}, "autre": 1},
                {"rubrique": "votes", "chemin": "AN_G", "vue": "generique"}]"#,
            30,
        );
        let p = v(s.app_politique_accueil())["presentations"].clone();
        assert_eq!(
            p,
            json!([{"rubrique": "votes", "chemin": "AN_V", "vue": "votes", "champs": {"titre": "t"}},
                   {"rubrique": "votes", "chemin": "AN_G", "vue": "generique", "champs": {}}])
        );
        std::fs::remove_file(dir.join("presentation.json")).unwrap();
        assert_eq!(n(&s), 0);
        let _ = std::fs::remove_dir_all(dir);
    }

    /// Réponses réelles du module sur les fixtures, rangées dans les ressources de test de
    /// l'application : le décodage de l'application suit le contrat, pas une supposition.
    /// `POLITIQUE_ECHANTILLONS=1` les réécrit.
    #[test]
    fn contract_samples_match_app_resources() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../mobile/app/src/test/resources/politique");
        // L'application ne lit que des fichiers paginés. Dossier à dates fixes, avec un dit-vote généré
        // qui dépasse le budget d'une page.
        let tmp = copie("echantillons");
        std::fs::write(tmp.join("dit-vote.json"), gros_dit_vote()).unwrap();
        for f in ["dit-vote", "evaluateurs", "carte", "identites"] {
            date_fixe(&tmp.join(format!("{f}.json")));
        }
        let sp = server_sur(&tmp);
        let echantillons = [
            ("fichier-identites", v(fichier(&sp, "identites", 0))),
            ("fichier-dit-vote-p0", v(fichier(&sp, "dit-vote", 0))),
            ("fichier-dit-vote-p1", v(fichier(&sp, "dit-vote", 1))),
            ("fichier-evaluateurs", v(fichier(&sp, "evaluateurs", 0))),
            ("fichier-carte", v(fichier(&sp, "carte", 0))),
        ];
        let _ = std::fs::remove_dir_all(&tmp);
        let ecrire = std::env::var("POLITIQUE_ECHANTILLONS").is_ok_and(|x| x == "1");
        for (nom, rep) in echantillons {
            let path = dir.join(format!("{nom}.json"));
            if ecrire {
                std::fs::create_dir_all(&dir).unwrap();
                std::fs::write(&path, serde_json::to_string_pretty(&rep).unwrap() + "\n").unwrap();
                continue;
            }
            let lu: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap_or_else(|_| panic!("{nom} absent : POLITIQUE_ECHANTILLONS=1")))
                .unwrap();
            assert_eq!(lu, rep, "{nom} périmé : POLITIQUE_ECHANTILLONS=1 cargo test -p kairos-politique");
        }
    }

    #[test]
    fn avatar_valid_returned() {
        let s = server();
        let h = v(s.app_politique_accueil());
        let raw: Value = serde_json::from_str(&std::fs::read_to_string(Path::new(FIXTURES).join("identites.json")).unwrap()).unwrap();
        assert_eq!(h["identites"][0]["avatar"], raw[0]["avatar"]);
        assert_eq!(h["identites"][0]["avatar"]["images"].as_array().unwrap().len(), 2);
        assert_eq!(v(fiche(&s, "a"))["identite"]["avatar"], raw[0]["avatar"]);
    }

    #[test]
    fn avatar_invalid_omitted() {
        let s = server();
        let h = v(s.app_politique_accueil());
        // Image de 15 lignes : retirée, l'identité reste.
        assert_eq!(h["identites"][2]["id"], "c");
        assert!(h["identites"][2].get("avatar").is_none());
        assert!(v(fiche(&s, "c"))["identite"].get("avatar").is_none());
        let ligne = "................";
        let img = |l: &str| json!(vec![l; 16]);
        use crate::donnees::avatar_valide;
        assert!(avatar_valide(&json!({"images": [img(ligne)], "ms": 100})));
        assert!(!avatar_valide(&json!({"images": [img(ligne)], "ms": 99})));
        assert!(!avatar_valide(&json!({"images": [img(ligne)], "ms": 2001})));
        assert!(!avatar_valide(&json!({"images": [img(ligne)]})));
        assert!(!avatar_valide(&json!({"images": [], "ms": 400})));
        assert!(!avatar_valide(&json!({"images": vec![img(ligne); 5], "ms": 400})));
        assert!(avatar_valide(&json!({"images": [img("..FhDACgby......")], "ms": 400})));
        assert!(!avatar_valide(&json!({"images": [img("...............X")], "ms": 400})));
        assert!(!avatar_valide(&json!({"images": [img("...............")], "ms": 400})));
    }
}
