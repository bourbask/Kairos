//! Notifications push (ntfy) : fin des demandes asynchrones, disque presque plein.

use std::time::Duration;

#[derive(Debug, Clone)]
pub struct Notifier {
    url: String,
    token: Option<String>,
    http: reqwest::Client,
}

impl Notifier {
    /// `base` = serveur ntfy, `topic` = sujet ; le jeton est facultatif.
    pub fn new(base: &str, topic: &str, token: Option<String>) -> Notifier {
        let http = reqwest::Client::builder().timeout(Duration::from_secs(15)).build().expect("client HTTP");
        Notifier { url: format!("{}/{topic}", base.trim_end_matches('/')), token, http }
    }

    /// Envoie ; un échec est journalisé (sans adresse) et renvoyé, jamais fatal.
    pub async fn send(&self, title: &str, message: &str) -> bool {
        // Titre en paramètre : un en-tête HTTP ne porte pas les accents de façon fiable.
        let mut req = self.http.post(&self.url).query(&[("title", title)]).body(message.to_string());
        if let Some(t) = &self.token {
            req = req.bearer_auth(t);
        }
        match req.send().await {
            Ok(r) if r.status().is_success() => true,
            Ok(r) => {
                eprintln!("medias/notif : réponse en échec ({})", r.status().as_u16());
                false
            }
            Err(_) => {
                eprintln!("medias/notif : envoi impossible");
                false
            }
        }
    }
}
