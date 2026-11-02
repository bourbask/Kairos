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

async fn fake(log: Log, m: String) -> String {
    fake_with(log, m, true).await
}

async fn fake_with(log: Log, m: String, unc_ok: bool) -> String {
    let mm = move |body: &str| -> Value { serde_json::from_str(&format!("{{{body},{m}}}")).unwrap() };
    let (l1, l2, l3, l4, l5, l6, l7) = (log.clone(), log.clone(), log.clone(), log.clone(), log.clone(), log.clone(), log.clone());
    let (m1, m2, m3) = (mm.clone(), mm.clone(), mm.clone());
    let app = Router::new()
        .route("/api/cycle", get(move || async move {
            rec(&l1, "GET cycle");
            Json(m1(r#""start":"2026-10-28","end":"2026-11-30","today":"2026-11-02","days_left":28,"mode":"serein","available":{"available":73.0,"balance":500.0,"charges_left_total":50.0,"variable_expected":{},"margin":50.0,"days_left":28},"charges_left":[],"envelopes":{"courses":300,"essence":80},"spent":{},"cushion":{"balance":5000.0,"floor":3000}"#))
        }))
        .route("/api/cycle/afford", get(move |axum::extract::RawQuery(q): axum::extract::RawQuery| async move {
            rec(&l2, &format!("GET afford {}", q.unwrap_or_default()));
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
                Json(m(r#""alerts":[{"id":"a1","kind":"near_limit","title":"T1","details":"D1","remedy":"R1"},{"id":"a2","kind":"unexpected","title":"T2","details":"D2","remedy":"R2"}]"#))
            }
        }))
        .route("/api/unclassified", get(move || async move {
            rec(&l6, "GET unclassified");
            if !unc_ok {
                return (axum::http::StatusCode::INTERNAL_SERVER_ERROR, Json(json!({})));
            }
            (axum::http::StatusCode::OK, Json(json!([{"id":"o1","date":"2026-11-01","payee":"CARTE BOULANGERIE ACME","amount":12.5},{"id":"o2","date":"2026-11-01","payee":"CARTE GARAGE ACME","amount":80.0},{"id":"o3","date":"2026-11-02","payee":"PRLV ACME ENERGIE","amount":45.0}])))
        }))
        .route("/api/rules", post(move |Json(b): Json<Value>| async move {
            rec(&l7, &format!("POST rules {b}"));
            if b["poste"] == "inconnu" {
                return (axum::http::StatusCode::UNPROCESSABLE_ENTITY, Json(json!({"detail": "Poste inconnu."})));
            }
            (axum::http::StatusCode::OK, Json(json!({"rule_id":"r1","created": b["poste"] != "essence","applied":true})))
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
    let api = fake(log.clone(), META.to_string()).await;
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
    let v = call(&url, "can_afford", json!({"amount": 10.0, "poste": "Essence"})).await;
    assert!(text(&v).starts_with("Oui"), "{v}");
    assert!(log.lock().unwrap().iter().any(|l| l.starts_with("GET afford") && l.contains("poste=essence")));
}

fn posts(log: &Log) -> Vec<String> {
    log.lock().unwrap().iter().filter(|l| l.starts_with("POST")).cloned().collect()
}

#[tokio::test]
async fn classify_pattern_rules() {
    let (url, log) = setup().await;
    let long = "x".repeat(101);
    for (payee, want) in [
        ("INCONNU", "ne figure dans aucune"),
        ("ab", "entre 3 et 100"),
        (long.as_str(), "entre 3 et 100"),
        ("carte", "trop générique"),
        ("PRLV SEPA", "trop générique"),
        ("VIR SEPA", "trop générique"),
        ("CARTE CB", "trop générique"),
        ("PRLV SEPA 12", "trop générique"),
        ("PRÊT", "trop générique"),
        ("prlv.", "trop générique"),
        ("12.34", "trop générique"),
        ("acme", "trop large"),
    ] {
        let v = call(&url, "classify", json!({"payee": payee, "poste": "courses"})).await;
        assert!(is_err(&v) && text(&v).contains(want), "{payee}: {v}");
    }
    assert!(posts(&log).is_empty());
    let v = call(&url, "classify", json!({"payee": "boulangerie", "poste": "courses"})).await;
    assert_eq!(text(&v), "Règle enregistrée : boulangerie → courses (1 opération(s) en attente concernée(s)).");
    let p = posts(&log);
    assert_eq!(p.len(), 1);
    let b: Value = serde_json::from_str(p[0].trim_start_matches("POST rules ")).unwrap();
    assert_eq!(b, json!({"payee":"boulangerie","poste":"courses"}));
    let v = call(&url, "classify", json!({"payee": "garage", "poste": "essence"})).await;
    assert!(text(&v).starts_with("Règle mise à jour : garage → essence"), "{v}");
    let v = call(&url, "classify", json!({"payee": "garage", "poste": "inconnu"})).await;
    assert!(is_err(&v));
    assert_eq!(text(&v), "Poste inconnu.");
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
    assert!(text(&v).lines().next().unwrap().ends_with("(date de synchro inconnue)."), "{v}");
    let v = call(&url, "brief_section", json!({"period": "soir"})).await;
    assert!(text(&v).ends_with("Réponds par exemple « BOULANGERIE = courses » (le nom du commerçant suffit). Postes : courses, essence."), "{v}");
    assert!(!text(&v).contains("<poste>"));
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
    let stale = start(fake(log, META.replace(r#""stale":false"#, r#""stale":true"#)).await, None).await;
    let v = call(&stale, "alerts", json!({})).await;
    let a: Value = serde_json::from_str(text(&v)).unwrap();
    assert!(a[0]["text"].as_str().unwrap().starts_with("⚠ Données au"), "{a}");
    let expired = META.replace(r#""consent_expired":false"#, r#""consent_expired":true"#);
    let url = start(fake(Default::default(), expired).await, None).await;
    let a: Value = serde_json::from_str(text(&call(&url, "alerts", json!({})).await)).unwrap();
    assert!(a[0]["text"].as_str().unwrap().starts_with("⚠ Consentement bancaire expiré"), "{a}");
}

#[tokio::test]
async fn evening_brief_survives_unclassified_failure() {
    let url = start(fake_with(Default::default(), META.to_string(), false).await, None).await;
    let v = call(&url, "brief_section", json!({"period": "soir"})).await;
    assert!(!is_err(&v), "{v}");
    assert!(text(&v).starts_with("Disponible jusqu'au") && text(&v).contains("Coussin") && !text(&v).contains("Réponds"), "{v}");
}
