//! Outils `app_*` : vues JSON de l'application, en lecture seule, sur les mêmes
//! lectures que les outils texte. Natures privées : ni organisme, ni nom de
//! fichier, ni passage ; absentes de la recherche.

use chrono::TimeZone;
use rmcp::{handler::server::wrapper::Parameters, schemars, tool, tool_router};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::tools::{DocServer, all_deadlines_since};

type Out = Result<String, String>;

/// Fenêtre des échéances : retards et à venir (jours).
const WINDOW_DAYS: i64 = 90;
const MAX_SEARCH: usize = 20;
const MAX_PASSAGE: usize = 240;
const MAX_TEXT: usize = 200;

/// Texte sur une ligne : caractères de contrôle retirés, blancs resserrés, borné.
fn clean(s: &str, max: usize) -> String {
    // Caractères invisibles de mise en forme (largeur nulle, sens d'écriture) : retirés, ils peuvent tromper l'affichage.
    let invisible = |c: char| matches!(c, '\u{00AD}' | '\u{200B}'..='\u{200F}' | '\u{202A}'..='\u{202E}' | '\u{2060}'..='\u{2069}' | '\u{FEFF}');
    let flat: String = s.chars().filter(|&c| !invisible(c)).map(|c| if c.is_control() { ' ' } else { c }).collect();
    flat.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(max).collect()
}

fn json_out(v: Value) -> Out {
    serde_json::to_string(&v).map_err(|e| e.to_string())
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppQueryArgs {
    /// Mots à chercher.
    query: String,
}

#[tool_router(router = app_router, vis = "pub(crate)")]
impl DocServer {
    #[tool(description = "Vue JSON des documents pour l'application : échéances, pièces attendues, natures, extraction.")]
    fn app_documents(&self) -> Out {
        let today = self.today();
        let deadlines: Vec<Value> = all_deadlines_since(&self.index, &self.settings, today, WINDOW_DAYS)
            .into_iter()
            .filter(|(d, _)| d.date <= today + chrono::Duration::days(WINDOW_DAYS))
            .map(|(d, private)| {
                let mut v = json!({
                    "date": d.date.format("%Y-%m-%d").to_string(),
                    "kind": d.kind,
                    "what": clean(&d.what, MAX_TEXT),
                    "private": private,
                    "overdue": d.date < today,
                });
                if let Some(o) = d.organisme.as_deref().filter(|_| !private).map(|o| clean(o, MAX_TEXT)) {
                    v["org"] = json!(o);
                }
                v
            })
            .collect();
        let missing: Vec<Value> = self
            .checklist(None)?
            .iter()
            .map(|c| json!({"domain": c.domain, "item": c.label, "present": c.present()}))
            .collect();
        let natures: Vec<Value> = self
            .index
            .natures()
            .map_err(|e| e.to_string())?
            .into_iter()
            .map(|(name, count, no_text, _)| {
                let private = self.is_private(&name);
                // Nature privée : comptée seulement, comme dans la vue texte.
                let unreadable = if private { 0 } else { no_text };
                json!({"name": clean(&name, MAX_TEXT), "count": count, "unreadable": unreadable, "private": private})
            })
            .collect();
        let (done, total) = match &self.settings.model {
            Some(m) => {
                let (ok, ko, todo) = self.index.facts_status(m).map_err(|e| e.to_string())?;
                (ok + ko, ok + ko + todo)
            }
            None => (0, 0),
        };
        json_out(json!({
            "deadlines": deadlines,
            "missing": missing,
            "natures": natures,
            "extraction": {"done": done, "total": total},
        }))
    }

    #[tool(description = "Recherche plein texte pour l'application (JSON, 20 résultats au plus).")]
    fn app_documents_search(&self, Parameters(a): Parameters<AppQueryArgs>) -> Out {
        let hits = self
            .index
            .search(&a.query, None, &self.private_natures()?, MAX_SEARCH)
            .map_err(|e| e.to_string())?;
        let out: Vec<Value> = hits
            .iter()
            .filter(|(d, _)| !self.is_private(&d.nature))
            .map(|(d, snip)| {
                let mut v = json!({
                    "name": clean(&d.name, MAX_TEXT),
                    "nature": clean(&d.nature, MAX_TEXT),
                    "passage": clean(snip, MAX_PASSAGE),
                });
                if let Some(t) = self.settings.tz.timestamp_opt(d.mtime, 0).single().filter(|_| d.mtime > 0) {
                    v["date"] = json!(t.format("%Y-%m-%d").to_string());
                }
                v
            })
            .collect();
        json_out(json!(out))
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::sync::Arc;

    use chrono::{Duration, NaiveDate};

    use super::*;
    use crate::index::Index;
    use crate::tools::Settings;

    const TZ: chrono_tz::Tz = chrono_tz::Europe::Paris;

    fn today() -> NaiveDate {
        chrono::Utc::now().with_timezone(&TZ).date_naive()
    }

    fn day(offset: i64) -> String {
        (today() + Duration::days(offset)).format("%Y-%m-%d").to_string()
    }

    /// Serveur sur une arborescence fictive ; `facts` : (chemin, JSON des faits).
    fn server(files: &[(&str, &str)], facts: &[(&str, Value)]) -> (DocServer, PathBuf) {
        let n = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let root = std::env::temp_dir().join(format!("kd-app-{}-{n}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        for (p, body) in files {
            let f = root.join(p);
            std::fs::create_dir_all(f.parent().unwrap()).unwrap();
            std::fs::write(f, body).unwrap();
        }
        let index = Arc::new(Index::memory());
        index.scan(&root, 1).unwrap();
        for (p, f) in facts {
            index.set_facts(p, "m", Some(&f.to_string()), 2).unwrap();
        }
        let settings = Arc::new(Settings {
            root: root.clone(),
            private: vec!["identite".into()],
            tz: TZ,
            model: Some("m".into()),
            write: false,
            not_applicable: Vec::new(),
        });
        (DocServer::new(index, settings), root)
    }

    fn js(o: Out) -> Value {
        serde_json::from_str(&o.unwrap()).unwrap()
    }

    fn search(s: &DocServer, q: &str) -> Value {
        js(s.app_documents_search(Parameters(AppQueryArgs { query: q.into() })))
    }

    fn fact(kind: &str, org: Option<&str>, dates: &[(&str, String)]) -> Value {
        let dates: Vec<Value> = dates.iter().map(|(k, d)| json!({"type": k, "date": d, "libelle": ""})).collect();
        json!({"type_document": kind, "organisme": org, "reference": "REF-0001", "resume": "", "dates": dates, "montants": [{"libelle": "prime", "montant": 321.5, "periodicite": "annuel"}]})
    }

    #[test]
    fn app_documents_json_shape() {
        let (s, root) = server(
            &[("Assurances/habitation.md", "Contrat d'assurance habitation.")],
            &[(
                "Assurances/habitation.md",
                fact("contrat d'assurance habitation", Some("Assureur Exemple"), &[("echeance", day(10))]),
            )],
        );
        let v = js(s.app_documents());
        assert_eq!(
            v["deadlines"][0],
            json!({"date": day(10), "kind": "échéance", "what": "contrat d'assurance habitation",
                   "org": "Assureur Exemple", "private": false, "overdue": false})
        );
        let m = &v["missing"][0];
        assert!(m["domain"].is_string() && m["item"].is_string() && m["present"].is_boolean(), "{m}");
        assert_eq!(v["natures"], json!([{"name": "Assurances", "count": 1, "unreadable": 0, "private": false}]));
        assert_eq!(v["extraction"], json!({"done": 1, "total": 1}));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn deadlines_overdue_then_upcoming() {
        let f = fact(
            "contrat d'abonnement",
            Some("Fournisseur Exemple"),
            &[
                ("fin", day(-120)),
                ("fin", day(-60)),
                ("preavis_limite", day(5)),
                ("echeance", day(20)),
                ("expiration", day(100)),
            ],
        );
        let (s, root) = server(&[("Contrats/abonnement.md", "texte")], &[("Contrats/abonnement.md", f)]);
        let d = js(s.app_documents())["deadlines"].clone();
        let got: Vec<(String, String, bool)> = d
            .as_array()
            .unwrap()
            .iter()
            .map(|x| {
                (x["date"].as_str().unwrap().into(), x["kind"].as_str().unwrap().into(), x["overdue"].as_bool().unwrap())
            })
            .collect();
        assert_eq!(
            got,
            [(day(-60), "fin".into(), true), (day(5), "résiliation".into(), false), (day(20), "échéance".into(), false)]
        );
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn private_deadline_redacted() {
        let f = fact("carte nationale d'identité", Some("Préfecture Exemple"), &[("expiration", day(30))]);
        let (s, root) = server(&[("Identité/carte-recto.md", "texte")], &[("Identité/carte-recto.md", f)]);
        let v = js(s.app_documents());
        assert_eq!(
            v["deadlines"],
            json!([{"date": day(30), "kind": "expiration", "what": "carte nationale d'identité", "private": true, "overdue": false}])
        );
        let all = v.to_string();
        for leak in ["Préfecture", "REF-0001", "321", "carte-recto", "Identité/"] {
            assert!(!all.contains(leak), "{leak} : {all}");
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn missing_present_flags() {
        let (s, root) = server(
            &[("Logement/bail.md", "texte")],
            &[("Logement/bail.md", fact("bail d'habitation", Some("Bailleur Exemple"), &[]))],
        );
        let v = js(s.app_documents());
        let flag = |item: &str| {
            v["missing"].as_array().unwrap().iter().find(|m| m["item"] == item).unwrap_or_else(|| panic!("{item}"))
                ["present"]
                .clone()
        };
        assert_eq!(flag("Bail"), json!(true));
        assert_eq!(flag("Passeport"), json!(false));
        assert!(v["missing"].as_array().unwrap().iter().any(|m| m["domain"] == "Logement"));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn natures_counts_and_extraction() {
        let (s, root) = server(
            &[
                ("Assurances/a.md", "Attestation lisible, texte assez long pour compter."),
                ("Assurances/scan.jpg", "\0\0"),
                ("Logement/b.md", "Quittance lisible, texte assez long pour compter."),
                ("Identité/c.jpg", "\0"),
            ],
            &[("Assurances/a.md", fact("attestation", None, &[]))],
        );
        let v = js(s.app_documents());
        assert_eq!(
            v["natures"],
            json!([
                {"name": "Assurances", "count": 2, "unreadable": 1, "private": false},
                {"name": "Identité", "count": 1, "unreadable": 0, "private": true},
                {"name": "Logement", "count": 1, "unreadable": 0, "private": false},
            ])
        );
        assert_eq!(v["extraction"], json!({"done": 1, "total": 2}));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn empty_index_shape() {
        let (s, root) = server(&[], &[]);
        let v = js(s.app_documents());
        assert_eq!(v["deadlines"], json!([]));
        assert_eq!(v["natures"], json!([]));
        assert_eq!(v["extraction"], json!({"done": 0, "total": 0}));
        assert!(v["missing"].as_array().unwrap().iter().all(|m| m["present"] == json!(false)));
        assert_eq!(search(&s, "garantie"), json!([]));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn search_shape() {
        let (s, root) = server(&[("Assurances/auto.md", "Garantie bris de glace : franchise de 150 euros.")], &[]);
        let r = search(&s, "franchise");
        let hits = r.as_array().unwrap();
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0]["name"], "auto.md");
        assert_eq!(hits[0]["nature"], "Assurances");
        assert!(NaiveDate::parse_from_str(hits[0]["date"].as_str().unwrap(), "%Y-%m-%d").is_ok(), "{r}");
        assert!(hits[0]["passage"].as_str().unwrap().contains("franchise"), "{r}");
        assert_eq!(search(&s, "   "), json!([]));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn search_excludes_private() {
        let (s, root) = server(
            &[
                ("Assurances/auto.md", "Garantie bris de glace : franchise de 150 euros."),
                ("Identité/carte.md", "Garantie confidentielle, ne doit jamais ressortir."),
            ],
            &[],
        );
        let r = search(&s, "garantie");
        assert_eq!(r.as_array().unwrap().len(), 1, "{r}");
        assert_eq!(r[0]["nature"], "Assurances");
        assert_eq!(search(&s, "confidentielle"), json!([]));
        assert_eq!(search(&s, "carte"), json!([]), "pas même par le nom de fichier");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn search_escapes_fts_syntax() {
        let (s, root) = server(&[("Assurances/auto.md", "Garantie bris de glace : franchise de 150 euros.")], &[]);
        for q in ["\"bris", "NEAR(bris glace", "garantie OR *", "-x:y ^", "\"", "bris AND NOT glace", "name:bris"] {
            assert!(search(&s, q).is_array(), "{q}");
        }
        assert_eq!(search(&s, "\"bris\" glace")[0]["name"], "auto.md");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn no_paths_and_clean_text() {
        let long = "x".repeat(60);
        let body = format!("{long} {long}\u{7} franchise\t\u{1b}[31m {long} {long} {long} {long}");
        let f = fact("conditions\u{0} générales", Some("Assureur\u{7}\nExemple"), &[("echeance", day(3))]);
        let (s, root) = server(&[("Assurances/Auto/contrat.md", &body)], &[("Assurances/Auto/contrat.md", f)]);
        let r = search(&s, "franchise");
        let p = r[0]["passage"].as_str().unwrap();
        assert_eq!(r[0]["name"], "contrat.md");
        assert!(p.chars().count() <= 240, "{}", p.chars().count());
        assert!(!p.chars().any(char::is_control), "{p:?}");
        let v = js(s.app_documents());
        assert_eq!(v["deadlines"][0]["org"], "Assureur Exemple");
        assert_eq!(v["deadlines"][0]["what"], "conditions générales");
        for out in [r.to_string(), v.to_string()] {
            assert!(!out.contains("Assurances/") && !out.contains("Auto/"), "{out}");
        }
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn clean_drops_invisible_format_chars() {
        assert_eq!(super::clean("a\u{200B}b\u{202E}c\u{2066}d", 50), "abcd");
    }

}
