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
use std::time::Duration;
use tokio::sync::mpsc::{Sender, error::TrySendError};

pub const MAX_INPUT_CHARS: usize = 8000;

#[derive(Deserialize)]
struct Message {
    text: String,
}

async fn receive(State(tx): State<Sender<String>>, Json(m): Json<Message>) -> StatusCode {
    if m.text.is_empty() {
        return StatusCode::BAD_REQUEST;
    }
    let text: String = m.text.chars().take(MAX_INPUT_CHARS).collect();
    match tx.try_send(text) {
        Ok(()) => StatusCode::ACCEPTED,
        Err(TrySendError::Full(_)) | Err(TrySendError::Closed(_)) => StatusCode::SERVICE_UNAVAILABLE,
    }
}

pub fn inbound_router(tx: Sender<String>, token: String) -> Router {
    let router = Router::new()
        .route("/message", post(receive))
        .layer(DefaultBodyLimit::max(64 * 1024))
        .with_state(tx);
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
