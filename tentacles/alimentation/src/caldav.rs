//! Calendrier des repas en CalDAV : écriture et retrait d'événements.

use std::time::Duration;

use reqwest::Method;

pub struct CalDav {
    /// Adresse de la collection, terminée par `/`.
    url: String,
    user: String,
    pass: String,
    http: reqwest::Client,
}

impl CalDav {
    pub fn new(url: &str, user: String, pass: String) -> CalDav {
        let url = if url.ends_with('/') { url.to_string() } else { format!("{url}/") };
        let http = reqwest::Client::builder().timeout(Duration::from_secs(20)).build().expect("client HTTP");
        CalDav { url, user, pass, http }
    }

    /// Statut HTTP ; l'erreur ne cite ni l'adresse ni les identifiants.
    async fn call(&self, method: &str, uid: &str, body: Option<String>) -> Result<u16, String> {
        let m = Method::from_bytes(method.as_bytes()).expect("méthode HTTP");
        let mut req = self.http.request(m, format!("{}{uid}.ics", self.url)).basic_auth(&self.user, Some(&self.pass));
        if let Some(b) = body {
            req = req.header("Content-Type", "text/calendar; charset=utf-8").body(b);
        }
        let st = req.send().await.map_err(|_| "agenda injoignable".to_string())?.status().as_u16();
        match st {
            401 | 403 => Err("accès à l'agenda refusé".into()),
            _ => Ok(st),
        }
    }

    pub async fn put(&self, uid: &str, ics: &str) -> Result<(), String> {
        match self.call("PUT", uid, Some(ics.to_string())).await? {
            200..=299 => Ok(()),
            st => Err(format!("écriture refusée ({st})")),
        }
    }

    pub async fn delete(&self, uid: &str) -> Result<(), String> {
        match self.call("DELETE", uid, None).await? {
            200..=299 | 404 => Ok(()),
            st => Err(format!("suppression refusée ({st})")),
        }
    }
}
