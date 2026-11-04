//! Liste de contrôle des pièces attendues, par domaine : présente, manquante,
//! ancienne, ou peut-être là mais illisible.
//!
//! La reconnaissance est déterministe : mots-clés (sans accents) cherchés en
//! début de mot dans le type extrait, l'organisme et le nom du fichier — le
//! résumé seulement pour les pièces qui le demandent, car un bulletin de
//! salaire cite volontiers la mutuelle.

use chrono::NaiveDate;
use rmcp::{handler::server::wrapper::Parameters, schemars, tool, tool_router};
use serde::Deserialize;

use crate::facts::{self, Facts, fold};
use crate::index::Doc;
use crate::tools::{DocServer, all_deadlines};

pub struct Item {
    pub domain: &'static str,
    pub label: &'static str,
    /// Au moins un groupe dont tous les mots-clés sont présents.
    pub any: &'static [&'static [&'static str]],
    /// Le résumé extrait compte aussi.
    pub resume: bool,
    /// Ancienneté au-delà de laquelle la pièce est signalée (jours).
    pub max_age: Option<i64>,
}

const fn item(domain: &'static str, label: &'static str, any: &'static [&'static [&'static str]]) -> Item {
    Item { domain, label, any, resume: false, max_age: None }
}

pub const CT_LABEL: &str = "Procès-verbal de contrôle technique";

pub const CHECKLIST: &[Item] = &[
    item("Véhicule", "Certificat d'immatriculation", &[&["immatriculation"], &["carte grise"]]),
    item(
        "Véhicule",
        "Attestation d'assurance auto",
        &[&["attestation", "assurance", "auto"], &["attestation", "assurance", "vehicule"]],
    ),
    item(
        "Véhicule",
        "Contrat ou conditions d'assurance auto",
        &[&["assurance", "auto", "condition"], &["contrat", "assurance", "auto"]],
    ),
    item(
        "Véhicule",
        "Contrat de financement",
        &[
            &["financement"],
            &["offre de credit"],
            &["offre de contrat"],
            &["contrat de credit"],
            &["location avec option"],
        ],
    ),
    item("Véhicule", CT_LABEL, &[&["controle technique"]]),
    item("Logement", "Bail", &[&["bail"]]),
    item("Logement", "État des lieux d'entrée", &[&["etat des lieux"], &["edl"], &["edle"]]),
    item(
        "Logement",
        "Assurance habitation",
        &[&["assurance", "habitation"], &["multirisque"], &["assurance", "locataire"]],
    ),
    item(
        "Énergie",
        "Contrat ou facture d'énergie",
        &[&["electricite"], &["energie"], &["gaz"], &["titulaire", "contrat"]],
    ),
    Item {
        domain: "Télécom",
        label: "Contrat ou facture internet / mobile",
        any: &[
            &["internet"],
            &["fibre"],
            &["box"],
            &["telecom"],
            &["forfait"],
            &["telephon"],
            &["facture", "fixe"],
            &["facture", "mobile"],
        ],
        resume: true,
        max_age: None,
    },
    item("Santé", "Attestation de droits à l'assurance maladie", &[&["attestation de droits"], &["assurance maladie"]]),
    item("Santé", "Mutuelle (carte ou garanties)", &[&["mutuelle"], &["complementaire sante"], &["tiers payant"]]),
    Item {
        domain: "Impôts",
        label: "Avis d'imposition",
        any: &[&["avis d impot"], &["avis d imposition"]],
        resume: false,
        max_age: Some(460),
    },
    item("Emploi", "Contrat de travail", &[&["contrat de travail"]]),
    Item {
        domain: "Emploi",
        label: "Bulletin de salaire récent",
        any: &[&["bulletin de salaire"], &["bulletin de paie"]],
        resume: false,
        max_age: Some(62),
    },
    item("Identité", "Carte d'identité", &[&["carte nationale d identite"], &["carte d identite"], &["identity card"]]),
    item("Identité", "Passeport", &[&["passeport"]]),
    item("Identité", "Permis de conduire", &[&["permis de conduire"]]),
    item("Banque", "Relevé d'identité bancaire", &[&["releve d identite bancaire"], &["rib"]]),
];

/// Texte normalisé « mot mot mot » entouré d'espaces, pour chercher en début de mot.
fn words(s: &str) -> String {
    let w: String = fold(s).chars().map(|c| if c.is_alphanumeric() { c } else { ' ' }).collect();
    format!(" {} ", w.split_whitespace().collect::<Vec<_>>().join(" "))
}

pub fn matches(it: &Item, hay: &str) -> bool {
    it.any.iter().any(|g| g.iter().all(|k| hay.contains(&format!(" {k}"))))
}

/// Date d'un document pour juger son ancienneté : la plus récente déjà passée
/// (le modèle prend volontiers une date d'embauche pour une date d'émission).
fn doc_date(f: &Facts, today: NaiveDate) -> Option<NaiveDate> {
    f.dates.iter().filter_map(|d| facts::parse(&d.date)).filter(|d| *d <= today).max()
}

pub struct Found<'a> {
    pub doc: &'a Doc,
    pub date: Option<NaiveDate>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct MissingArgs {
    /// Restreindre à un domaine (Véhicule, Logement, Énergie, Télécom, Santé, Impôts, Emploi, Identité, Banque).
    domain: Option<String>,
}

#[tool_router(router = missing_router, vis = "pub(crate)")]
impl DocServer {
    #[tool(
        description = "Liste de contrôle des pièces administratives attendues (véhicule, logement, énergie, télécom, santé, impôts, emploi, identité, banque) : présentes, manquantes, trop anciennes, ou peut-être présentes mais illisibles."
    )]
    async fn missing_documents(&self, Parameters(a): Parameters<MissingArgs>) -> Out {
        let today = chrono::Utc::now().with_timezone(&self.settings.tz).date_naive();
        let facts: Vec<(Doc, Facts)> = self
            .index
            .all_facts()
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter_map(|(d, j)| serde_json::from_str(&j).ok().map(|f| (d, f)))
            .collect();
        let unreadable: Vec<Doc> =
            self.index.all().map_err(|e| e.to_string())?.into_iter().filter(|d| !d.has_text).collect();
        let first_ct = all_deadlines(&self.index, &self.settings, today)
            .into_iter()
            .find(|(d, _)| d.label == "premier contrôle technique" && d.date >= today)
            .map(|(d, _)| d.date);
        let wanted = a.domain.as_deref().map(fold);
        let (mut ok, mut ko) = (0, 0);
        let mut out: Vec<String> = Vec::new();
        let mut current = "";
        for it in CHECKLIST {
            if wanted.as_deref().is_some_and(|w| fold(it.domain) != w.trim()) {
                continue;
            }
            let mut found: Vec<Found> = facts
                .iter()
                .filter(|(d, f)| {
                    let mut hay = format!(
                        "{} {} {}",
                        f.type_document,
                        f.organisme.as_deref().unwrap_or_default(),
                        d.name.rsplit_once('.').map(|x| x.0).unwrap_or(&d.name)
                    );
                    if it.resume {
                        hay.push(' ');
                        hay.push_str(&f.resume);
                    }
                    matches(it, &words(&hay))
                })
                .map(|(d, f)| Found { doc: d, date: doc_date(f, today) })
                .collect();
            found.sort_by(|a, b| b.date.cmp(&a.date).then(a.doc.path.cmp(&b.doc.path)));
            if it.domain != current {
                current = it.domain;
                out.push(it.domain.to_string());
            }
            // Avant le premier contrôle, une « pièce de CT » ne peut être qu'une erreur de lecture.
            let first_ct = first_ct.filter(|_| it.label == CT_LABEL);
            let line = match found.first() {
                _ if first_ct.is_some() => {
                    ok += 1;
                    format!(
                        "  ✓ {} — pas encore requis (premier contrôle avant le {})",
                        it.label,
                        first_ct.unwrap().format("%d/%m/%Y")
                    )
                }
                Some(best) => {
                    ok += 1;
                    let src = if self.is_private(&best.doc.nature) {
                        "document privé".to_string()
                    } else {
                        best.doc.path.clone()
                    };
                    let when = best.date.map(|d| format!(" ({})", d.format("%d/%m/%Y"))).unwrap_or_default();
                    let more =
                        if found.len() > 1 { format!(" — {} exemplaire(s)", found.len()) } else { String::new() };
                    let stale = match (it.max_age, best.date) {
                        (Some(max), Some(d)) if (today - d).num_days() > max => {
                            format!(" — à renouveler ? le plus récent a {} jours", (today - d).num_days())
                        }
                        _ => String::new(),
                    };
                    format!("  ✓ {} — {src}{when}{more}{stale}", it.label)
                }
                None => {
                    ko += 1;
                    let maybe: Vec<&str> = unreadable
                        .iter()
                        .filter(|d| !self.is_private(&d.nature) && matches(it, &words(&d.name)))
                        .map(|d| d.path.as_str())
                        .take(3)
                        .collect();
                    if maybe.is_empty() {
                        format!("  ✗ {} — manquant", it.label)
                    } else {
                        format!("  ? {} — peut-être illisible : {}", it.label, maybe.join(", "))
                    }
                }
            };
            out.push(line);
        }
        if out.is_empty() {
            let domains: Vec<_> =
                CHECKLIST.iter().map(|i| i.domain).collect::<std::collections::BTreeSet<_>>().into_iter().collect();
            return Err(format!("Domaine inconnu. Domaines : {}.", domains.join(", ")));
        }
        Ok(format!("Pièces attendues — {ok} présente(s), {ko} à trouver :\n{}", out.join("\n")))
    }
}

type Out = Result<String, String>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn freshness_uses_latest_past_date() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 4).unwrap();
        let d = |k: &str, v: &str| facts::FactDate { kind: k.into(), date: v.into(), libelle: String::new() };
        let f = Facts {
            dates: vec![d("emission", "06/05/2019"), d("echeance", "28/07/2026"), d("fin", "2027-01-01")],
            ..Default::default()
        };
        assert_eq!(doc_date(&f, today).unwrap().to_string(), "2026-07-28");
    }

    fn find(label: &str) -> &'static Item {
        CHECKLIST.iter().find(|i| i.label == label).unwrap()
    }

    #[test]
    fn keywords_match_word_starts_without_accents() {
        let it = find("Attestation d'assurance auto");
        assert!(matches(it, &words("Attestation d'assurance AUTOMOBILE")));
        assert!(!matches(it, &words("Conditions générales d'assurance auto")));
        assert!(matches(
            find("Contrat ou conditions d'assurance auto"),
            &words("Conditions Générales d'assurance auto")
        ));
        assert!(matches(find("Avis d'imposition"), &words("Avis d'impôt établi en 2022")));
        assert!(matches(find("État des lieux d'entrée"), &words("edle dupont")));
        assert!(!matches(find("Assurance habitation"), &words("Bail habitation")));
        assert!(!matches(find("Relevé d'identité bancaire"), &words("tribunal")), "« rib » en début de mot seulement");
        assert!(matches(find("Bulletin de salaire récent"), &words("BULLETIN DE SALAIRE")));
        assert!(matches(find("Contrat ou facture internet / mobile"), &words("facture Facture fixe M. X")));
    }
}
