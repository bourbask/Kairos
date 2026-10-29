//! Amorçage commun des tentacules : configuration d'environnement, jeton porteur
//! et service MCP HTTP sans état.

use std::net::SocketAddr;

use anyhow::{Context, bail};
use axum::{
    Router,
    extract::Request,
    http::{StatusCode, header::AUTHORIZATION},
    middleware::{Next, from_fn},
    response::Response,
};
use rmcp::{
    ServerHandler,
    transport::streamable_http_server::{
        StreamableHttpServerConfig, StreamableHttpService, session::never::NeverSessionManager,
    },
};
use serde::Deserialize;
use subtle::ConstantTimeEq;

/// Limite de corps de requête appliquée par `serve`.
pub const MAX_BODY_BYTES: usize = 2 * 1024 * 1024;

/// Moment de la journée visé par un briefing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum BriefPeriod {
    Matin,
    Soir,
}

/// Paramètres d'exécution d'un tentacule, lus dans l'environnement.
#[derive(Debug, Clone)]
pub struct TentacleEnv {
    pub bind: SocketAddr,
    pub token: String,
}

impl TentacleEnv {
    /// Lit `{PREFIX}_BIND` et `{PREFIX}_TOKEN`, tous deux obligatoires et non vides.
    pub fn from_env(prefix: &str) -> anyhow::Result<TentacleEnv> {
        let var = |suffix: &str| {
            let name = format!("{prefix}_{suffix}");
            match std::env::var(&name) {
                Ok(v) if !v.is_empty() => Ok(v),
                _ => bail!("variable {name} absente ou vide"),
            }
        };
        let bind = var("BIND")?.parse().context("adresse de écoute invalide")?;
        Ok(TentacleEnv { bind, token: var("TOKEN")? })
    }
}

/// Refuse (401) toute requête sans `Authorization: Bearer <token>` exact.
pub fn with_bearer(router: Router, token: String) -> Router {
    let expected = format!("Bearer {token}");
    router.layer(from_fn(move |req: Request, next: Next| {
        let expected = expected.clone();
        async move {
            let given = req.headers().get(AUTHORIZATION).map(|v| v.as_bytes());
            // Comparaison à temps constant ; la longueur seule peut fuiter.
            let ok = given.is_some_and(|g| g.ct_eq(expected.as_bytes()).into());
            if ok {
                next.run(req).await
            } else {
                let mut r = Response::new(axum::body::Body::empty());
                *r.status_mut() = StatusCode::UNAUTHORIZED;
                r
            }
        }
    }))
}

/// Sert le handler MCP sur `/mcp` (HTTP streamable sans état, réponses JSON),
/// protégé par le jeton porteur.
pub async fn serve<S>(env: TentacleEnv, factory: impl Fn() -> S + Send + Sync + 'static) -> anyhow::Result<()>
where
    S: ServerHandler + Send + 'static,
{
    // Le contrôle d'hôte par défaut (boucle locale) bloquerait l'accès en
    // conteneur ; l'authentification repose sur le jeton.
    let mut config = StreamableHttpServerConfig::default()
        .with_legacy_session_mode(false)
        .with_json_response(true)
        .disable_allowed_hosts();
    config.max_request_body_bytes = MAX_BODY_BYTES;
    let service = StreamableHttpService::new(
        move || Ok(factory()),
        std::sync::Arc::new(NeverSessionManager::default()),
        config,
    );
    let app = with_bearer(Router::new().nest_service("/mcp", service), env.token);
    let listener = tokio::net::TcpListener::bind(env.bind).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Router, body::Body, http::{Request, StatusCode}, routing::get};
    use tower::ServiceExt;

    fn app() -> Router {
        with_bearer(Router::new().route("/", get(|| async { "ok" })), "t".into())
    }
    async fn status(auth: Option<&str>) -> StatusCode {
        let mut r = Request::builder().uri("/");
        if let Some(a) = auth { r = r.header("authorization", a); }
        app().oneshot(r.body(Body::empty()).unwrap()).await.unwrap().status()
    }

    #[test]
    fn env_without_token_is_refused() {
        unsafe { std::env::set_var("TCA_BIND", "127.0.0.1:1"); std::env::remove_var("TCA_TOKEN"); }
        assert!(TentacleEnv::from_env("TCA").is_err());
    }
    #[test]
    fn env_with_empty_token_is_refused() {
        unsafe { std::env::set_var("TCB_BIND", "127.0.0.1:1"); std::env::set_var("TCB_TOKEN", ""); }
        assert!(TentacleEnv::from_env("TCB").is_err());
    }
    #[test]
    fn env_ok() {
        unsafe { std::env::set_var("TCC_BIND", "127.0.0.1:1"); std::env::set_var("TCC_TOKEN", "x"); }
        assert_eq!(TentacleEnv::from_env("TCC").unwrap().token, "x");
    }
    #[tokio::test] async fn missing_bearer_is_401() { assert_eq!(status(None).await, StatusCode::UNAUTHORIZED); }
    #[tokio::test] async fn wrong_bearer_is_401() { assert_eq!(status(Some("Bearer x")).await, StatusCode::UNAUTHORIZED); }
    #[tokio::test] async fn right_bearer_passes() { assert_eq!(status(Some("Bearer t")).await, StatusCode::OK); }
    #[test]
    fn brief_period_serde() {
        assert_eq!(serde_json::from_str::<BriefPeriod>("\"matin\"").unwrap(), BriefPeriod::Matin);
    }
}
