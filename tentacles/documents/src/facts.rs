//! Faits extraits d'un document par un modèle local (Ollama), et échéances
//! qui en découlent.
//!
//! Le modèle ne fait que lire : il renvoie un JSON contraint par un schéma
//! (type, organisme, référence, dates typées, montants). Les échéances sont
//! calculées ici, de façon déterministe (contrôle technique à +2 ans, premier
//! contrôle à +4 ans de la mise en circulation…).

use std::time::Duration;

use chrono::{Datelike, Months, NaiveDate};
use serde::{Deserialize, Serialize};
use serde_json::json;

/// Caractères de texte transmis au modèle (le début porte l'essentiel).
pub const MAX_INPUT: usize = 12_000;

pub const DATE_KINDS: [&str; 12] = [
    "emission",
    "debut",
    "fin",
    "echeance",
    "renouvellement",
    "fin_engagement",
    "preavis_limite",
    "controle_technique",
    "premiere_immatriculation",
    "expiration",
    "paiement",
    "autre",
];

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct FactDate {
    #[serde(rename = "type")]
    pub kind: String,
    /// AAAA-MM-JJ.
    pub date: String,
    pub libelle: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct Amount {
    pub libelle: String,
    pub montant: f64,
    pub periodicite: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct Facts {
    pub type_document: String,
    pub organisme: Option<String>,
    pub reference: Option<String>,
    pub resume: String,
    pub dates: Vec<FactDate>,
    pub montants: Vec<Amount>,
}

fn schema() -> serde_json::Value {
    json!({
        "type": "object",
        "properties": {
            "type_document": {"type": "string"},
            "organisme": {"type": ["string", "null"]},
            "reference": {"type": ["string", "null"]},
            "resume": {"type": "string"},
            "dates": {"type": "array", "items": {"type": "object", "properties": {
                "type": {"type": "string", "enum": DATE_KINDS},
                "date": {"type": "string"},
                "libelle": {"type": "string"}
            }, "required": ["type", "date", "libelle"]}},
            "montants": {"type": "array", "items": {"type": "object", "properties": {
                "libelle": {"type": "string"},
                "montant": {"type": "number"},
                "periodicite": {"type": "string", "enum": ["unique", "mensuel", "trimestriel", "annuel", "autre"]}
            }, "required": ["libelle", "montant", "periodicite"]}}
        },
        "required": ["type_document", "organisme", "reference", "resume", "dates", "montants"]
    })
}

pub fn prompt(name: &str, text: &str) -> String {
    let mut t: String = text.chars().take(MAX_INPUT).collect();
    t = t.split_whitespace().collect::<Vec<_>>().join(" ");
    format!(
        "Tu lis un document administratif français (fichier « {name} »). Extrais uniquement ce qui y figure, \
         sans rien inventer ; mets null ou une liste vide si l'information est absente.\n\
         - type_document : nature précise en français (ex. « bulletin de salaire », « contrat d'assurance auto », \
         « procès-verbal de contrôle technique », « certificat d'immatriculation », « bail d'habitation », « facture d'entretien »). Une facture ou un ordre \
         de réparation de garage n'est pas un contrôle technique.\n\
         - organisme : émetteur ou contrepartie principale.\n\
         - reference : numéro de contrat, de dossier ou de client.\n\
         - resume : une phrase.\n\
         - dates : au format AAAA-MM-JJ, typées : emission, debut, fin, echeance, renouvellement, fin_engagement, \
         preavis_limite (date limite pour résilier), controle_technique (date du contrôle effectué), \
         premiere_immatriculation, expiration, paiement, autre. N'inclus que les dates réellement écrites dans \
         le document, au format complet AAAA-MM-JJ ; jamais de date vide, partielle ou « null ».\n\
         - montants : en euros, avec leur périodicité.\n\nDocument :\n{t}"
    )
}

#[derive(Debug, Clone)]
pub struct Ollama {
    url: String,
    pub model: String,
    http: reqwest::Client,
}

#[derive(Deserialize)]
struct GenResponse {
    response: String,
}

impl Ollama {
    pub fn new(url: &str, model: &str) -> Ollama {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(300)).build().expect("client HTTP");
        Ollama { url: url.trim_end_matches('/').to_string(), model: model.to_string(), http }
    }

    pub async fn extract(&self, name: &str, text: &str) -> Result<Facts, String> {
        let body = json!({
            "model": self.model,
            "prompt": prompt(name, text),
            "format": schema(),
            "stream": false,
            "options": {"temperature": 0, "num_ctx": 8192}
        });
        let r = self
            .http
            .post(format!("{}/api/generate", self.url))
            .json(&body)
            .send()
            .await
            .map_err(|_| "modèle injoignable".to_string())?;
        if !r.status().is_success() {
            return Err(format!("modèle en échec ({})", r.status().as_u16()));
        }
        let g: GenResponse = r.json().await.map_err(|_| "réponse illisible".to_string())?;
        let mut f: Facts = serde_json::from_str(&g.response).map_err(|_| "JSON hors schéma".to_string())?;
        f.dates.retain(|d| parse(&d.date).is_some() && DATE_KINDS.contains(&d.kind.as_str()));
        fix_swapped_dates(&mut f, text);
        Ok(f)
    }
}

/// Le modèle convertit parfois « 01/08/2026 » en 2026-01-08 : une date absente du texte
/// dont la version jour/mois inversée y figure est remise à l'endroit.
pub fn fix_swapped_dates(f: &mut Facts, text: &str) {
    let written = |d: NaiveDate| {
        [d.format("%d/%m/%Y").to_string(), d.format("%d-%m-%Y").to_string(), d.format("%d.%m.%Y").to_string()]
            .iter()
            .any(|x| text.contains(x.as_str()))
            || text.contains(&d.format("%Y-%m-%d").to_string())
    };
    for x in &mut f.dates {
        let Some(d) = parse(&x.date) else { continue };
        if written(d) {
            continue;
        }
        if let Some(sw) = NaiveDate::from_ymd_opt(d.year(), d.day(), d.month()).filter(|sw| written(*sw)) {
            x.date = sw.format("%Y-%m-%d").to_string();
        }
    }
}

/// Natures de documents qui se reconduisent tacitement.
const RENEWABLE: [&str; 10] = [
    "assurance",
    "abonnement",
    "conditions particulieres",
    "mutuelle",
    "fourniture",
    "internet",
    "box",
    "telephon",
    "electricite",
    "gaz",
];

/// Minuscules sans accents, pour comparer des libellés.
pub fn fold(s: &str) -> String {
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

/// Date complète : ISO, ou formats français (jour/mois/année). Partielle ou vide : rejetée.
pub fn parse(d: &str) -> Option<NaiveDate> {
    let d = d.trim();
    ["%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y"]
        .iter()
        .find_map(|f| NaiveDate::parse_from_str(d, f).ok())
        .filter(|d| (1950..=2100).contains(&d.year()))
}

#[derive(Debug, Clone, PartialEq)]
pub struct Deadline {
    pub date: NaiveDate,
    pub label: String,
    /// Document d'origine.
    pub path: String,
    /// Organisme (sans casse ni accents), pour rapprocher les pièces d'un même contrat.
    pub org: Option<String>,
    /// Renouvellement tacite passé, reporté d'année en année.
    pub rolled: bool,
    /// Type de document.
    pub what: String,
    /// Motif (fin, résiliation, renouvellement, expiration, contrôle…).
    pub kind: String,
    /// Organisme tel qu'extrait.
    pub organisme: Option<String>,
}

/// Retards gardés par défaut (jours).
pub const PAST_DAYS: i64 = 30;

/// Échéances d'un document. `today` borne les rappels déjà passés de plus de 30 jours.
pub fn deadlines(path: &str, f: &Facts, today: NaiveDate) -> Vec<Deadline> {
    deadlines_since(path, f, today, PAST_DAYS)
}

/// Échéances d'un document, en gardant les rappels passés depuis au plus `past` jours.
pub fn deadlines_since(path: &str, f: &Facts, today: NaiveDate, past: i64) -> Vec<Deadline> {
    let since = today - chrono::Duration::days(past);
    let organisme = f.organisme.clone().filter(|o| !o.is_empty());
    let mut out = Vec::new();
    let who = f.organisme.as_deref().filter(|o| !o.is_empty()).map(|o| format!(" — {o}")).unwrap_or_default();
    let what = if f.type_document.is_empty() { "document".to_string() } else { f.type_document.clone() };
    // Les dates propres au véhicule ne valent que sur la pièce qui les porte :
    // un modèle lit volontiers un « contrôle » dans une facture d'entretien.
    let kind_of = fold(&f.type_document);
    let is_ct = kind_of.contains("controle technique");
    let is_cg = kind_of.contains("immatriculation") || kind_of.contains("carte grise");
    let ct: Vec<NaiveDate> = if is_ct {
        f.dates.iter().filter(|d| d.kind == "controle_technique").filter_map(|d| parse(&d.date)).collect()
    } else {
        Vec::new()
    };
    // Carte grise : la date B (1re immatriculation) est la plus ancienne du
    // recto ; le modèle la type souvent « emission ».
    let first_reg = is_cg
        .then(|| {
            let typed = f.dates.iter().filter(|d| d.kind == "premiere_immatriculation").filter_map(|d| parse(&d.date));
            typed.min().or_else(|| f.dates.iter().filter_map(|d| parse(&d.date)).min())
        })
        .flatten();
    if let Some(date) = first_reg {
        let when = date.checked_add_months(Months::new(48)).unwrap_or(date);
        if when >= since {
            out.push(Deadline {
                date: when,
                label: "premier contrôle technique".to_string(),
                path: path.to_string(),
                org: None,
                rolled: false,
                what: what.clone(),
                kind: "contrôle".to_string(),
                organisme: None,
            });
        }
    }
    for d in &f.dates {
        let Some(date) = parse(&d.date) else { continue };
        let (when, label, kind) = match d.kind.as_str() {
            "fin" | "echeance" | "renouvellement" | "fin_engagement" | "expiration" | "preavis_limite" => {
                let l = match d.kind.as_str() {
                    "fin" => "fin",
                    "echeance" => "échéance",
                    "renouvellement" => "renouvellement",
                    "fin_engagement" => "fin d'engagement",
                    "expiration" => "expiration",
                    _ => "date limite de résiliation",
                };
                // Tacite reconduction : un renouvellement passé revient chaque année.
                if d.kind == "renouvellement" && date < today {
                    let kind = fold(&what);
                    if !RENEWABLE.iter().any(|k| kind.contains(k)) || kind.contains("travail") {
                        continue;
                    }
                    let mut next = date;
                    while next < today {
                        next = next.checked_add_months(Months::new(12)).unwrap_or(today);
                    }
                    let org = f.organisme.as_deref().map(fold).filter(|o| !o.is_empty());
                    out.push(Deadline {
                        date: next,
                        label: format!("{what}{who} : renouvellement (tacite)"),
                        path: path.to_string(),
                        org,
                        rolled: true,
                        what: what.clone(),
                        kind: "renouvellement".to_string(),
                        organisme: organisme.clone(),
                    });
                    continue;
                }
                let kind = if d.kind == "preavis_limite" { "résiliation" } else { l };
                (date, format!("{what}{who} : {l}"), kind)
            }
            "controle_technique" if Some(&date) == ct.iter().max() => (
                date.checked_add_months(Months::new(24)).unwrap_or(date),
                "contrôle technique à repasser".to_string(),
                "contrôle",
            ),
            _ => continue,
        };
        if when >= since {
            let org = f.organisme.as_deref().map(fold).filter(|o| !o.is_empty());
            out.push(Deadline {
                date: when,
                label,
                path: path.to_string(),
                org,
                rolled: false,
                what: what.clone(),
                kind: kind.to_string(),
                organisme: organisme.clone(),
            });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(kind: &str, date: &str) -> FactDate {
        FactDate { kind: kind.into(), date: date.into(), libelle: String::new() }
    }

    #[test]
    fn deadlines_are_derived_deterministically() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap();
        let f = Facts {
            type_document: "contrat d'assurance auto".into(),
            organisme: Some("Assureur".into()),
            dates: vec![
                d("emission", "2026-01-10"),
                d("echeance", "2027-01-10"),
                d("preavis_limite", "2026-11-10"),
                d("fin", "2020-01-01"),
            ],
            ..Default::default()
        };
        let got: Vec<_> = deadlines("a.pdf", &f, today).into_iter().map(|x| (x.date.to_string(), x.label)).collect();
        assert_eq!(
            got,
            [
                ("2027-01-10".to_string(), "contrat d'assurance auto — Assureur : échéance".to_string()),
                (
                    "2026-11-10".to_string(),
                    "contrat d'assurance auto — Assureur : date limite de résiliation".to_string()
                ),
            ]
        );
    }

    #[test]
    fn past_tacit_renewals_roll_forward_yearly() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 4).unwrap();
        let f = Facts {
            type_document: "conditions particulières assurance habitation".into(),
            organisme: Some("Assureur".into()),
            dates: vec![d("renouvellement", "2023-08-01"), d("debut", "2023-01-24")],
            ..Default::default()
        };
        let got = deadlines("cp.pdf", &f, today);
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].date.to_string(), "2027-08-01");
        assert!(got[0].rolled && got[0].label.ends_with("renouvellement (tacite)"));
        assert_eq!(got[0].org.as_deref(), Some("assureur"));
    }

    #[test]
    fn non_contracts_never_roll() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 4).unwrap();
        for t in ["diplôme", "diagnostic de performance énergétique", "contrat de travail"] {
            let f =
                Facts { type_document: t.into(), dates: vec![d("renouvellement", "2020-01-27")], ..Default::default() };
            assert!(deadlines("x.pdf", &f, today).is_empty(), "{t}");
        }
    }

    #[test]
    fn swapped_day_and_month_are_fixed_against_the_text() {
        let mut f =
            Facts { dates: vec![d("renouvellement", "2026-01-08"), d("debut", "2026-06-09")], ..Default::default() };
        fix_swapped_dates(&mut f, "se renouvelle le : 01/08/2026 ; effet le 09/06/2026");
        assert_eq!(f.dates[0].date, "2026-08-01");
        assert_eq!(f.dates[1].date, "2026-06-09", "date juste laissée telle quelle");
        let mut g = Facts { dates: vec![d("echeance", "2026-01-08")], ..Default::default() };
        fix_swapped_dates(&mut g, "aucune date lisible");
        assert_eq!(g.dates[0].date, "2026-01-08", "rien à quoi se fier : inchangé");
    }

    #[test]
    fn technical_inspection_rules() {
        let today = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap();
        let pv = Facts {
            type_document: "procès-verbal de contrôle technique".into(),
            dates: vec![d("controle_technique", "2024-03-01"), d("controle_technique", "2025-12-05")],
            ..Default::default()
        };
        let got = deadlines("pv.pdf", &pv, today);
        assert_eq!(got.len(), 1);
        assert_eq!(
            (got[0].date.to_string(), got[0].label.as_str()),
            ("2027-12-05".into(), "contrôle technique à repasser")
        );
        let cg = |dates| Facts { type_document: "certificat d'immatriculation".into(), dates, ..Default::default() };
        let got = deadlines("cg.pdf", &cg(vec![d("premiere_immatriculation", "2023-06-15")]), today);
        assert_eq!(got[0].date.to_string(), "2027-06-15");
        let got = deadlines("cg.jpg", &cg(vec![d("emission", "17/04/2023")]), today);
        assert_eq!(
            (got[0].date.to_string(), got[0].label.as_str()),
            ("2027-04-17".into(), "premier contrôle technique"),
            "date B typée « emission » par le modèle"
        );
        assert!(deadlines("cg.pdf", &cg(vec![d("premiere_immatriculation", "2010-06-15")]), today).is_empty());
        let invoice = Facts {
            type_document: "facture d'entretien".into(),
            dates: vec![d("controle_technique", "2025-08-04"), d("premiere_immatriculation", "2026-08-01")],
            ..Default::default()
        };
        assert!(deadlines("entretien.jpg", &invoice, today).is_empty(), "facture d'entretien : pas de CT");
    }

    #[test]
    fn invalid_dates_are_rejected() {
        assert!(parse("2026-13-01").is_none());
        assert!(parse("1900-01-01").is_none());
        assert_eq!(parse(" 2026-02-28 ").unwrap().to_string(), "2026-02-28");
        assert_eq!(parse("28/07/2026").unwrap().to_string(), "2026-07-28");
        assert_eq!(parse("05.12.2025").unwrap().to_string(), "2025-12-05");
        assert!(parse("07-2026").is_none() && parse("null").is_none() && parse("").is_none());
    }

    #[test]
    fn prompt_is_capped_and_whitespace_normalised() {
        let p = prompt("x.pdf", &format!("a   b\n\n{}", "c".repeat(20_000)));
        assert!(p.contains("a b c"));
        assert!(p.chars().count() < MAX_INPUT + 2_000);
    }
}
