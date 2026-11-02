use axum::{Json, Router, http::HeaderMap, routing::post};
use serde_json::{Value, json};
use std::net::SocketAddr;
use tete::config::GatewayEnv;
use tete::gateway::{MAX_INPUT_CHARS, Outbound, inbound_router};
use tokio::sync::mpsc;

async fn serve(app: Router) -> SocketAddr {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(l, app).await.unwrap() });
    addr
}

async fn post_msg(addr: SocketAddr, token: Option<&str>, body: Value) -> u16 {
    let mut r = reqwest::Client::new().post(format!("http://{addr}/message")).json(&body);
    if let Some(t) = token {
        r = r.bearer_auth(t);
    }
    r.send().await.unwrap().status().as_u16()
}

#[tokio::test]
async fn refuse_sans_bon_jeton() {
    let (tx, mut rx) = mpsc::channel(16);
    let addr = serve(inbound_router(tx, "secret".into())).await;
    assert_eq!(post_msg(addr, None, json!({"text": "a"})).await, 401);
    assert_eq!(post_msg(addr, Some("autre"), json!({"text": "a"})).await, 401);
    assert!(rx.try_recv().is_err());
}

#[tokio::test]
async fn accepte_et_transmet() {
    let (tx, mut rx) = mpsc::channel(16);
    let addr = serve(inbound_router(tx, "secret".into())).await;
    assert_eq!(post_msg(addr, Some("secret"), json!({"text": "bonjour"})).await, 202);
    assert_eq!(rx.recv().await.unwrap(), "bonjour");
}

#[tokio::test]
async fn texte_vide_refuse() {
    let (tx, mut rx) = mpsc::channel(16);
    let addr = serve(inbound_router(tx, "secret".into())).await;
    assert_eq!(post_msg(addr, Some("secret"), json!({"text": ""})).await, 400);
    assert!(rx.try_recv().is_err());
}

#[tokio::test]
async fn texte_tronque() {
    let (tx, mut rx) = mpsc::channel(16);
    let addr = serve(inbound_router(tx, "secret".into())).await;
    let long = "é".repeat(10_000);
    assert_eq!(post_msg(addr, Some("secret"), json!({"text": long})).await, 202);
    assert_eq!(rx.recv().await.unwrap().chars().count(), MAX_INPUT_CHARS);
}

#[tokio::test]
async fn corps_trop_gros_et_file_pleine() {
    let (tx, _rx) = mpsc::channel(1);
    let addr = serve(inbound_router(tx, "secret".into())).await;
    let big = "a".repeat(70 * 1024);
    assert_eq!(post_msg(addr, Some("secret"), json!({"text": big})).await, 413);
    assert_eq!(post_msg(addr, Some("secret"), json!({"text": "1"})).await, 202);
    assert_eq!(post_msg(addr, Some("secret"), json!({"text": "2"})).await, 503);
}

fn env(url: String) -> GatewayEnv {
    GatewayEnv {
        bind: "127.0.0.1:0".parse().unwrap(),
        inbound_token: "in".into(),
        outbound_url: url,
        outbound_token: "jeton-sortant".into(),
    }
}

#[tokio::test]
async fn envoi_sortant() {
    let (tx, mut rx) = mpsc::unbounded_channel::<(String, Value)>();
    let app = Router::new().route(
        "/out",
        post(move |h: HeaderMap, Json(b): Json<Value>| async move {
            let auth = h["authorization"].to_str().unwrap().to_string();
            tx.send((auth, b)).unwrap();
        }),
    );
    let addr = serve(app).await;
    Outbound::new(&env(format!("http://{addr}/out"))).send("salut").await.unwrap();
    let (auth, body) = rx.recv().await.unwrap();
    assert_eq!(auth, "Bearer jeton-sortant");
    assert_eq!(body, json!({"text": "salut"}));
}

#[tokio::test]
async fn envoi_sortant_statut_500() {
    let app = Router::new().route("/out", post(|| async { axum::http::StatusCode::INTERNAL_SERVER_ERROR }));
    let addr = serve(app).await;
    let err = Outbound::new(&env(format!("http://{addr}/out"))).send("x").await.unwrap_err();
    let txt = format!("{err:#}");
    assert!(txt.contains("500"));
    assert!(!txt.contains("jeton-sortant"));
    assert!(!txt.contains(&addr.to_string()));
}
