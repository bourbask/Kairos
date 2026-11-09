//! Client HTTP commun aux services de la stack média.
//!
//! Chaque service est facultatif : absent de la configuration, il répond
//! `NotConfigured` et les outils se dégradent au lieu d'échouer.

use std::time::Duration;

use serde::Serialize;
use serde::de::DeserializeOwned;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SvcError {
    #[error("non configuré")]
    NotConfigured,
    #[error("injoignable")]
    Unavailable,
    /// Le service répond mais ne connaît pas l'élément demandé (404) ; seulement via [`Svc::send_or_missing`].
    #[error("introuvable")]
    NotFound,
    #[error("accès refusé")]
    Denied,
}

/// Nature d'une erreur HTTP, sans son texte : il peut citer l'adresse appelée.
fn kind(e: &reqwest::Error) -> &'static str {
    if e.is_timeout() {
        "délai dépassé"
    } else if e.is_connect() {
        "connexion refusée"
    } else if e.is_decode() {
        "contenu hors contrat"
    } else {
        "transport"
    }
}

#[derive(Debug, Clone)]
pub enum Auth {
    None,
    /// En-tête porteur de clé (`X-Api-Key`, `X-Plex-Token`…).
    Header(&'static str, String),
    Basic(String, String),
    /// Jeton porteur (`Authorization: Bearer`).
    Bearer(String),
    /// Clé passée en paramètre d'URL (`?apikey=`).
    Query(&'static str, String),
}

/// Un service joignable : nom (journaux), adresse de base, authentification.
#[derive(Debug, Clone)]
pub struct Svc {
    pub name: &'static str,
    base: String,
    auth: Auth,
    http: reqwest::Client,
}

pub fn client() -> reqwest::Client {
    reqwest::Client::builder().timeout(Duration::from_secs(15)).build().expect("client HTTP")
}

impl Svc {
    pub fn new(name: &'static str, base: &str, auth: Auth, http: reqwest::Client) -> Svc {
        Svc { name, base: base.trim_end_matches('/').to_string(), auth, http }
    }

    pub fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }

    fn authed(&self, req: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        let req = req.header("Accept", "application/json");
        match &self.auth {
            Auth::None => req,
            Auth::Header(h, v) => req.header(*h, v),
            Auth::Basic(u, p) => req.basic_auth(u, Some(p)),
            Auth::Bearer(t) => req.bearer_auth(t),
            Auth::Query(k, v) => req.query(&[(*k, v)]),
        }
    }

    pub async fn send<T: DeserializeOwned>(&self, req: reqwest::RequestBuilder) -> Result<T, SvcError> {
        self.send_or_missing(req).await.map_err(|e| match e {
            SvcError::NotFound => {
                eprintln!("medias/{} : réponse en échec (404)", self.name);
                SvcError::Unavailable
            }
            e => e,
        })
    }

    /// Comme [`Svc::send`], mais un 404 rend [`SvcError::NotFound`] : l'élément demandé n'existe pas.
    pub async fn send_or_missing<T: DeserializeOwned>(&self, req: reqwest::RequestBuilder) -> Result<T, SvcError> {
        let resp = self.authed(req).send().await.map_err(|e| {
            eprintln!("medias/{} : appel impossible ({})", self.name, kind(&e));
            SvcError::Unavailable
        })?;
        let status = resp.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            eprintln!("medias/{} : accès refusé ({})", self.name, status.as_u16());
            return Err(SvcError::Denied);
        }
        if status.as_u16() == 404 {
            return Err(SvcError::NotFound);
        }
        if !status.is_success() {
            eprintln!("medias/{} : réponse en échec ({})", self.name, status.as_u16());
            return Err(SvcError::Unavailable);
        }
        resp.json().await.map_err(|e| {
            eprintln!("medias/{} : réponse illisible ({})", self.name, kind(&e));
            SvcError::Unavailable
        })
    }

    pub async fn get<T: DeserializeOwned>(&self, path: &str, query: &[(&str, String)]) -> Result<T, SvcError> {
        self.send(self.http.get(self.url(path)).query(query)).await
    }

    pub async fn post<T: DeserializeOwned, B: Serialize + ?Sized>(&self, path: &str, body: &B) -> Result<T, SvcError> {
        self.send(self.http.post(self.url(path)).json(body)).await
    }

    /// Écriture dont seul le statut compte (corps vide ou ignoré).
    pub async fn send_unit(&self, req: reqwest::RequestBuilder) -> Result<(), SvcError> {
        let resp = self.authed(req).send().await.map_err(|e| {
            eprintln!("medias/{} : appel impossible ({})", self.name, kind(&e));
            SvcError::Unavailable
        })?;
        let status = resp.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            eprintln!("medias/{} : accès refusé ({})", self.name, status.as_u16());
            return Err(SvcError::Denied);
        }
        if !status.is_success() {
            eprintln!("medias/{} : réponse en échec ({})", self.name, status.as_u16());
            return Err(SvcError::Unavailable);
        }
        Ok(())
    }

    pub fn raw(&self) -> &reqwest::Client {
        &self.http
    }
}

/// Service facultatif : `None` quand la configuration ne le déclare pas.
pub type Opt = Option<Svc>;

pub fn need(s: &Opt) -> Result<&Svc, SvcError> {
    s.as_ref().ok_or(SvcError::NotConfigured)
}
