//! Tentacule finances servi en HTTP, face à un faux service de finances.

use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    routing::{get, post},
};
use kairos_finances::{api::FinanceApi, tools::FinanceServer};
use serde_json::{Value, json};

type Log = Arc<Mutex<Vec<String>>>;

const META: &str = r#""quality":"fiable","stale":false,"last_sync":null,"consent_expired":false,"basis":null"#;

fn meta(body: &str) -> Value {
    serde_json::from_str(&format!("{{{body},{META}}}")).unwrap()
}

fn rec(log: &Log, s: &str) {
    log.lock().unwrap().push(s.to_string());
}

async fn fake(log: Log, stale: bool) -> String {
    let m = if stale {
        META.replace(r#""stale":false"#, r#""stale":true"#)
    } else {
        META.to_string()
    };
    let mm = move |body: &str| -> Value { serde_json::from_str(&format!("{{{body},{m}}}")).unwrap() };
    let (l1, l2, l3, l4, l5, l6, l7) = (log.clone(), log.clone(), log.clone(), log.clone(), log.clone(), log.clone(), log.clone());
    let (m1, m2, m3) = (mm.clone(), mm.clone(), mm.clone());
    let app = Router::new()
        .route("/api/cycle", get(move || async move {
            rec(&l1, "GET cycle");
            Json(m1(r#""start":"2026-10-28","end":"2026-11-30","today":"2026-11-02","days_left":28,"mode":"serein","available":{"available":73.0,"balance":500.0,"charges_left_total":50.0,"variable_expected":{},"margin":50.0,"days_left":28},"charges_left":[],"envelopes":{"courses":300,"essence":80},"spent":{},"cushion":{"balance":5000.0,"floor":3000}"#))
        }))
        .route("/api/cycle/afford", get(move || async move {
            rec(&l2, "GET afford");
            Json(m2(r#""ok":true,"after":10.0,"reason":"ok","cuts":[],"available":73.0,"reserve_used":0.0"#))
        }))
        .route("/api/cycle/save", get(move || async move {
            rec(&l3, "GET save");
            Json(meta(r#""ok":true,"after":10.0,"available":73.0"#))
        }))
        .route("/api/envelopes", get(move || async move {
            rec(&l4, "GET envelopes");
            Json(m3(r#""days":30,"mode":"serein","envelopes":{"courses":300}"#))
        }))
        .route("/api/finance-alerts", get(move || {
            let l = l5.clone();
            let m = mm.clone();
            async move {
                rec(&l, "GET alerts");
                Json(m(r#""alerts":[{"id":"a1","kind":"near_limit","title":"T1","details":"D1","remedy":"R1"},{"id":"a2","kind":"duplicate","title":"T2","details":"D2","remedy":"R2"}]"#))
            }
        }))
        .route("/api/unclassified", get(move || async move {
            rec(&l6, "GET unclassified");
            Json(json!([{"id":"o1","date":"2026-11-01","payee":"BOULANGERIE ACME","amount":-12.5}]))
        }))
        .route("/api/rules", post(move |Json(b): Json<Value>| async move {
            rec(&l7, &format!("POST rules {b}"));
            Json(json!({"rule_id":"r1","created":true,"applied":true}))
        }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await });
    url
}

async fn start(api_url: String, dashboard: Option<&str>) -> String {
    let api = Arc::new(FinanceApi::new(api_url, "tok".into()));
    let dash = dashboard.map(String::from);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        FinanceServer::new(api.clone(), dash.clone())
    }));
    url
}

async fn call(url: &str, name: &str, args: Value) -> Value {
    let body = json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}});
    reqwest::Client::new()
        .post(url)
        .header("Accept", "application/json, text/event-stream")
        .bearer_auth("t")
        .json(&body)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap()
}

fn text(v: &Value) -> &str {
    v["result"]["content"][0]["text"].as_str().unwrap()
}

fn is_err(v: &Value) -> bool {
    v["result"]["isError"] == json!(true)
}

async fn setup() -> (String, Log) {
    let log: Log = Default::default();
    let api = fake(log.clone(), false).await;
    (start(api, Some("http://dash.invalid/")).await, log)
}

#[tokio::test]
async fn lists_nine_tools() {
    let (url, _) = setup().await;
    let body = json!({"jsonrpc":"2.0","id":1,"method":"tools/list"});
    let v: Value = reqwest::Client::new().post(&url)
        .header("Accept", "application/json, text/event-stream")
        .bearer_auth("t").json(&body).send().await.unwrap().json().await.unwrap();
    let mut names: Vec<_> = v["result"]["tools"].as_array().unwrap().iter()
        .map(|t| t["name"].as_str().unwrap().to_string()).collect();
    names.sort();
    assert_eq!(names, ["alerts","brief_section","can_afford","can_save","classify","cycle_status","dashboard","next_envelopes","unclassified"]);
}

#[tokio::test]
async fn invalid_amount_makes_no_api_call() {
    let (url, log) = setup().await;
    for a in [-5.0, 0.0] {
        assert!(is_err(&call(&url, "can_afford", json!({"amount": a})).await));
        assert!(is_err(&call(&url, "can_save", json!({"amount": a})).await));
    }
    assert!(log.lock().unwrap().is_empty());
}

#[tokio::test]
async fn can_afford_checks_poste_against_cycle() {
    let (url, log) = setup().await;
    let v = call(&url, "can_afford", json!({"amount": 10.0, "poste": "yachts"})).await;
    assert!(is_err(&v));
    assert!(text(&v).contains("courses") && text(&v).contains("essence"), "{v}");
    assert!(!log.lock().unwrap().iter().any(|l| l.contains("afford")));
    let v = call(&url, "can_afford", json!({"amount": 10.0, "poste": "essence"})).await;
    assert!(text(&v).starts_with("Oui"), "{v}");
}

#[tokio::test]
async fn classify_only_known_payee() {
    let (url, log) = setup().await;
    let v = call(&url, "classify", json!({"payee": "AUTRE CHOSE", "poste": "courses"})).await;
    assert!(is_err(&v));
    let v = call(&url, "classify", json!({"payee": "ab", "poste": "courses"})).await;
    assert!(is_err(&v));
    assert!(!log.lock().unwrap().iter().any(|l| l.starts_with("POST")));
    let v = call(&url, "classify", json!({"payee": "BOULANGERIE ACME", "poste": "courses"})).await;
    assert!(!is_err(&v), "{v}");
    assert_eq!(text(&v), "Règle enregistrée : BOULANGERIE ACME → courses.");
    let posts: Vec<_> = log.lock().unwrap().iter().filter(|l| l.starts_with("POST")).cloned().collect();
    assert_eq!(posts.len(), 1);
    let b: Value = serde_json::from_str(posts[0].trim_start_matches("POST rules ")).unwrap();
    assert_eq!(b, json!({"payee":"BOULANGERIE ACME","poste":"courses"}));
}

#[tokio::test]
async fn unavailable_api() {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let dead = format!("http://{}", l.local_addr().unwrap());
    drop(l);
    let url = start(dead, None).await;
    let v = call(&url, "cycle_status", json!({})).await;
    assert!(is_err(&v));
    assert_eq!(text(&v), "Données financières indisponibles.");
}

#[tokio::test]
async fn brief_sections_and_dashboard() {
    let (url, _) = setup().await;
    let v = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(text(&v).starts_with("Disponible jusqu'au") && text(&v).contains("Coussin"), "{v}");
    assert!(!text(&v).contains("Réponds"));
    let v = call(&url, "brief_section", json!({"period": "soir"})).await;
    assert!(text(&v).ends_with("Réponds par exemple « BOULANGERIE ACME = <poste> »."), "{v}");
    let v = call(&url, "dashboard", json!({})).await;
    assert!(text(&v).ends_with("Tableau de bord : http://dash.invalid/"), "{v}");
}

#[tokio::test]
async fn alerts_json_and_stale_prefix() {
    let (url, _) = setup().await;
    let v = call(&url, "alerts", json!({})).await;
    let a: Value = serde_json::from_str(text(&v)).unwrap();
    assert_eq!(a[0]["counts_budget"], json!(true));
    assert_eq!(a[1]["counts_budget"], json!(false));
    assert_eq!(a[0]["text"], "T1\nD1\nR1");
    let log: Log = Default::default();
    let stale = start(fake(log, true).await, None).await;
    let v = call(&stale, "alerts", json!({})).await;
    let a: Value = serde_json::from_str(text(&v)).unwrap();
    assert!(a[0]["text"].as_str().unwrap().starts_with("Données au"), "{a}");
}
