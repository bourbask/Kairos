use std::collections::BTreeMap;
use std::time::Duration;

use serde::Deserialize;
use serde::de::DeserializeOwned;

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("service de finances indisponible")]
    Unavailable,
    #[error("{0}")]
    Rejected(String),
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Basis {
    pub pay_date: Option<String>,
    pub complete_cycles: u32,
    pub reference_period: Option<Vec<String>>,
    pub coverage: f64,
}

/// Désérialisation stricte : une dérive du contrat devient une erreur, jamais un zéro plausible.
/// Champs communs à plat dans les réponses de calcul.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Meta {
    pub quality: String,
    pub stale: bool,
    pub last_sync: Option<String>,
    pub consent_expired: bool,
    pub basis: Option<Basis>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Available {
    pub available: f64,
    pub balance: f64,
    pub charges_left_total: f64,
    pub variable_expected: BTreeMap<String, f64>,
    pub margin: f64,
    pub days_left: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Charge {
    pub name: String,
    pub amount: f64,
    pub day: Option<i64>,
    pub due: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Cushion {
    pub balance: f64,
    pub floor: f64,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Cycle {
    pub start: String,
    pub end: String,
    pub today: String,
    pub days_left: i64,
    pub mode: String,
    pub available: Available,
    pub charges_left: Vec<Charge>,
    pub envelopes: BTreeMap<String, f64>,
    pub spent: BTreeMap<String, f64>,
    pub cushion: Cushion,
    #[serde(flatten)]
    pub meta: Meta,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Cut {
    pub poste: Option<String>,
    pub amount: f64,
}

/// Réponse de `afford` et de `save` (ce dernier sans `reason` ni `cuts`).
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Verdict {
    pub ok: bool,
    pub after: f64,
    pub reason: Option<String>,
    #[serde(default)]
    pub cuts: Vec<Cut>,
    pub available: f64,
    #[serde(default)]
    pub reserve_used: f64,
    #[serde(flatten)]
    pub meta: Meta,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Envelopes {
    pub days: i64,
    pub mode: String,
    pub envelopes: BTreeMap<String, f64>,
    #[serde(flatten)]
    pub meta: Meta,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Alert {
    pub id: String,
    pub kind: String,
    pub title: String,
    pub details: String,
    pub remedy: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Alerts {
    pub alerts: Vec<Alert>,
    #[serde(flatten)]
    pub meta: Meta,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct Operation {
    pub id: String,
    pub date: String,
    pub payee: String,
    pub amount: f64,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RuleResult {
    pub rule_id: String,
    pub created: bool,
    pub applied: bool,
}

/// Nature d'une erreur HTTP, sans son texte : il peut citer l'adresse appelée.
fn kind(e: &reqwest::Error) -> &'static str {
    if e.is_timeout() {
        "délai dépassé"
    } else if e.is_connect() {
        "connexion refusée"
    } else if e.is_decode() {
        "contenu hors contrat"
    } else if e.is_body() {
        "corps illisible"
    } else {
        "transport"
    }
}

pub struct FinanceApi {
    base: String,
    token: String,
    http: reqwest::Client,
}

impl FinanceApi {
    pub fn new(base: String, token: String) -> FinanceApi {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .expect("client HTTP");
        FinanceApi {
            base: base.trim_end_matches('/').to_string(),
            token,
            http,
        }
    }

    async fn send<T: DeserializeOwned>(&self, req: reqwest::RequestBuilder) -> Result<T, ApiError> {
        let resp = req.bearer_auth(&self.token).send().await.map_err(|e| {
            eprintln!("finances : appel impossible ({})", kind(&e));
            ApiError::Unavailable
        })?;
        let status = resp.status();
        if status.is_success() {
            return resp.json().await.map_err(|e| {
                eprintln!("finances : réponse illisible ({})", kind(&e));
                ApiError::Unavailable
            });
        }
        eprintln!("finances : réponse en échec ({})", status.as_u16());
        if status.as_u16() == 401 || status.is_server_error() {
            return Err(ApiError::Unavailable);
        }
        let body: serde_json::Value = resp.json().await.unwrap_or_default();
        let text = match body.get("detail").and_then(|d| d.as_str()) {
            Some(s) => s.chars().take(200).collect(),
            None => "Requête refusée.".to_string(),
        };
        Err(ApiError::Rejected(text))
    }

    async fn get<T: DeserializeOwned>(
        &self,
        path: &str,
        query: &[(&str, String)],
    ) -> Result<T, ApiError> {
        self.send(self.http.get(format!("{}{path}", self.base)).query(query))
            .await
    }

    pub async fn cycle(&self) -> Result<Cycle, ApiError> {
        self.get("/api/cycle", &[]).await
    }

    pub async fn afford(&self, amount: f64, poste: Option<&str>) -> Result<Verdict, ApiError> {
        let mut q = vec![("amount", amount.to_string())];
        if let Some(p) = poste {
            q.push(("poste", p.to_string()));
        }
        self.get("/api/cycle/afford", &q).await
    }

    pub async fn save(&self, amount: f64) -> Result<Verdict, ApiError> {
        self.get("/api/cycle/save", &[("amount", amount.to_string())])
            .await
    }

    pub async fn envelopes(&self) -> Result<Envelopes, ApiError> {
        self.get("/api/envelopes", &[]).await
    }

    pub async fn alerts(&self) -> Result<Alerts, ApiError> {
        self.get("/api/finance-alerts", &[]).await
    }

    pub async fn unclassified(&self) -> Result<Vec<Operation>, ApiError> {
        self.get("/api/unclassified", &[]).await
    }

    pub async fn create_rule(&self, payee: &str, poste: &str) -> Result<RuleResult, ApiError> {
        let req = self
            .http
            .post(format!("{}/api/rules", self.base))
            .json(&serde_json::json!({"payee": payee, "poste": poste}));
        self.send(req).await
    }
}
