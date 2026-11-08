//! Passerelle de messagerie : API entrante neutre et envoi sortant.

use crate::config::GatewayEnv;
use anyhow::{Result, bail};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::StatusCode,
    routing::post,
};
use serde::Deserialize;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::sync::mpsc::Sender;

pub const MAX_INPUT_CHARS: usize = 8000;

/// Préfixe de la recopie, dans le salon, d'un message venu de l'application.
pub const FROM_APP_PREFIX: &str = "📱 ";

/// Message entrant ; `from_app` vrai s'il vient de l'application.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Inbound {
    pub text: String,
    pub from_app: bool,
}

/// Texte à recopier dans le salon pour un message de l'application.
pub fn mirror_text(m: &Inbound) -> Option<String> {
    m.from_app.then(|| format!("{FROM_APP_PREFIX}{}", m.text))
}

/// File d'entrée partagée par le salon et l'application : compte les messages
/// acceptés et pas encore répondus.
#[derive(Clone)]
pub struct Inbox {
    tx: Sender<Inbound>,
    pending: Arc<AtomicUsize>,
}

impl Inbox {
    pub fn new(tx: Sender<Inbound>) -> Self {
        Self { tx, pending: Arc::default() }
    }

    /// Faux si la file est pleine ou fermée (rien n'est compté).
    pub fn push(&self, m: Inbound) -> bool {
        // Compté avant l'envoi : la boucle ne peut pas décompter un message pas encore compté.
        self.pending.fetch_add(1, Ordering::SeqCst);
        let ok = self.tx.try_send(m).is_ok();
        if !ok {
            self.pending.fetch_sub(1, Ordering::SeqCst);
        }
        ok
    }

    /// Appelé par la boucle une fois la réponse envoyée ou mise en file.
    pub fn done(&self) {
        self.pending.fetch_sub(1, Ordering::SeqCst);
    }

    /// Vrai tant qu'un message accepté attend sa réponse.
    pub fn busy(&self) -> bool {
        self.pending.load(Ordering::SeqCst) > 0
    }
}

#[derive(Deserialize)]
struct Message {
    text: String,
}

async fn receive(State(inbox): State<Inbox>, Json(m): Json<Message>) -> StatusCode {
    if m.text.trim().is_empty() {
        return StatusCode::BAD_REQUEST;
    }
    let text: String = m.text.chars().take(MAX_INPUT_CHARS).collect();
    if inbox.push(Inbound { text, from_app: false }) { StatusCode::ACCEPTED } else { StatusCode::SERVICE_UNAVAILABLE }
}

pub fn inbound_router(inbox: Inbox, token: String) -> Router {
    let router = Router::new()
        .route("/message", post(receive))
        .layer(DefaultBodyLimit::max(64 * 1024))
        .with_state(inbox);
    tentacle_common::with_bearer(router, token)
}

pub struct Outbound {
    client: reqwest::Client,
    url: String,
    token: String,
}

impl Outbound {
    pub fn new(env: &GatewayEnv) -> Outbound {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .expect("client HTTP");
        Outbound { client, url: env.outbound_url.clone(), token: env.outbound_token.clone() }
    }

    pub async fn send(&self, text: &str) -> Result<()> {
        let resp = self
            .client
            .post(&self.url)
            .bearer_auth(&self.token)
            .json(&serde_json::json!({ "text": text }))
            .send()
            .await
            // l'erreur reqwest peut citer l'URL : message générique uniquement
            .map_err(|e| anyhow::anyhow!("envoi sortant impossible (délai ou connexion : {})", e.without_url()))?;
        let status = resp.status();
        if !status.is_success() {
            bail!("envoi sortant refusé : statut {}", status.as_u16());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_message_is_logged_and_mirrored() {
        let app = Inbound { text: "texte".into(), from_app: true };
        let salon = Inbound { text: "texte".into(), from_app: false };
        assert_eq!(mirror_text(&app).as_deref(), Some("📱 texte"));
        assert_eq!(mirror_text(&salon), None);
    }

    #[test]
    fn busy_until_every_accepted_message_is_answered() {
        let (tx, _rx) = tokio::sync::mpsc::channel(2);
        let inbox = Inbox::new(tx);
        let m = || Inbound { text: "x".into(), from_app: false };
        assert!(!inbox.busy());
        assert!(inbox.push(m()) && inbox.push(m()));
        // File pleine : refusé, non compté.
        assert!(!inbox.push(m()));
        inbox.done();
        assert!(inbox.busy());
        inbox.done();
        assert!(!inbox.busy());
    }
}
