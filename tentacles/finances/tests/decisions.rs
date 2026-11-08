//! Outils `decisions` et `decide` face à un faux service de finances.

use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    routing::{get, post},
};
use kairos_finances::{api::FinanceApi, tools::FinanceServer};
use serde_json::{Value, json};

type Log = Arc<Mutex<Vec<String>>>;

const CYCLE: &str = r#"{"start":"2026-10-28","end":"2026-11-30","today":"2026-11-02","days_left":28,"mode":"serein","available":{"available":73.0,"balance":500.0,"charges_left_total":50.0,"variable_expected":{},"margin":50.0,"days_left":28},"charges_left":[],"envelopes":{"Courses":300,"Loisirs":80},"spent":{},"cushion":{"balance":5000.0,"floor":3000},"quality":"fiable","stale":false,"last_sync":null,"consent_expired":false,"basis":null}"#;

async fn start(ops: Value) -> (String, Log) {
    let log: Log = Default::default();
    let l = log.clone();
    let app = Router::new()
        .route("/api/cycle", get(|| async { Json(serde_json::from_str::<Value>(CYCLE).unwrap()) }))
        .route("/api/unclassified", get(move || async move { Json(ops) }))
        .route("/api/rules", post(move |Json(b): Json<Value>| async move {
            l.lock().unwrap().push(b.to_string());
            Json(json!({"rule_id":"r1","created":true,"applied":true}))
        }));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let api_url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await });
    let api = Arc::new(FinanceApi::new(api_url, "tok".into()));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        FinanceServer::new(api.clone(), None)
    }));
    (url, log)
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

fn three_ops() -> Value {
    json!([
        {"id":"o1","date":"2026-11-01","payee":"CARTE 01/11 BOULANGERIE ACME","amount":10.0},
        {"id":"o2","date":"2026-11-03","payee":"CARTE 03/11 BOULANGERIE ACME","amount":2.34},
        {"id":"o3","date":"2026-11-02","payee":"PRLV GARAGE EXEMPLE","amount":80.0}
    ])
}

#[tokio::test]
async fn decisions_group_by_merchant() {
    let (url, _) = start(three_ops()).await;
    let v = call(&url, "decisions", json!({})).await;
    let d: Vec<Value> = serde_json::from_str(text(&v)).unwrap();
    assert_eq!(d.len(), 2, "{d:?}");
    let b = d.iter().find(|x| x["id"] == "BOULANGERIE ACME").unwrap();
    assert_eq!(b["title"], "Dépense à classer");
    assert_eq!(b["headline"], "12,34 €");
    assert_eq!(b["subtitle"], "BOULANGERIE ACME · 2 opération(s) · 03/11");
    assert_eq!(b["expires"], "2026-12-03");
    assert!(b["proposal"].is_null());
    assert_eq!(b["alternatives"], json!([{"id":"Courses","label":"Courses"},{"id":"Loisirs","label":"Loisirs"}]));
    let g = d.iter().find(|x| x["id"] == "GARAGE EXEMPLE").unwrap();
    assert_eq!(g["headline"], "80,00 €");
}

#[tokio::test]
async fn decisions_skip_generic_labels() {
    let ops = json!([
        {"id":"o1","date":"2026-11-01","payee":"CB 06/11 PAIEMENT","amount":5.0},
        {"id":"o2","date":"2026-11-01","payee":"CARTE BOULANGERIE ACME","amount":12.5}
    ]);
    let (url, _) = start(ops).await;
    let d: Vec<Value> = serde_json::from_str(text(&call(&url, "decisions", json!({})).await)).unwrap();
    assert_eq!(d.len(), 1);
    assert_eq!(d[0]["id"], "BOULANGERIE ACME");
}

#[tokio::test]
async fn decide_creates_rule() {
    let (url, log) = start(three_ops()).await;
    let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":"loisirs"})).await;
    assert!(!is_err(&v), "{v}");
    assert!(text(&v).contains("Loisirs"), "{v}");
    assert_eq!(log.lock().unwrap().as_slice(), [json!({"payee":"GARAGE EXEMPLE","poste":"Loisirs"}).to_string()]);
}

#[tokio::test]
async fn decide_rejects_unknown_poste() {
    let (url, log) = start(three_ops()).await;
    let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":"Voyages"})).await;
    assert!(is_err(&v), "{v}");
    assert!(log.lock().unwrap().is_empty());
}

#[tokio::test]
async fn decide_rejects_generic_label() {
    let (url, log) = start(three_ops()).await;
    let v = call(&url, "decide", json!({"id":"CB 06/11 PAIEMENT","choice":"Courses"})).await;
    assert!(is_err(&v), "{v}");
    assert!(log.lock().unwrap().is_empty());
}

#[tokio::test]
async fn decide_single_merchant_all_ops_accepted() {
    let ops = json!([
        {"id":"o1","date":"2026-11-01","payee":"GARAGE EXEMPLE","amount":10.0},
        {"id":"o2","date":"2026-11-02","payee":"GARAGE EXEMPLE","amount":20.0},
        {"id":"o3","date":"2026-11-03","payee":"GARAGE EXEMPLE","amount":30.0}
    ]);
    let (url, log) = start(ops).await;
    let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":"Loisirs"})).await;
    assert!(!is_err(&v), "{v}");
    assert_eq!(log.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn decide_unknown_id_refused() {
    let (url, log) = start(three_ops()).await;
    let v = call(&url, "decide", json!({"id":"BOULANGER","choice":"Loisirs"})).await;
    assert!(is_err(&v), "{v}");
    assert!(text(&v).contains("Décision inconnue ou déjà traitée."), "{v}");
    assert!(log.lock().unwrap().is_empty());
}

#[tokio::test]
async fn refund_nets_in_headline() {
    let ops = json!([
        {"id":"o1","date":"2026-11-01","payee":"GARAGE EXEMPLE","amount":50.0},
        {"id":"o2","date":"2026-11-02","payee":"GARAGE EXEMPLE","amount":-20.0}
    ]);
    let (url, _) = start(ops).await;
    let d: Vec<Value> = serde_json::from_str(text(&call(&url, "decisions", json!({})).await)).unwrap();
    assert_eq!(d[0]["headline"], "30,00 €");
}

/// Un libellé très long donne un motif court (libellé nettoyé borné à 60 caractères) :
/// la décision reste acceptée par `decide`, sous la borne de 100 caractères des règles.
#[tokio::test]
async fn long_label_yields_a_decidable_pattern() {
    let long = format!("BOULANGERIE {}", "ACME ".repeat(25));
    let ops = json!([{"id":"o1","date":"2026-11-01","payee": long,"amount":5.0}]);
    let (url, log) = start(ops).await;
    let d: Vec<Value> = serde_json::from_str(text(&call(&url, "decisions", json!({})).await)).unwrap();
    let id = d[0]["id"].as_str().unwrap();
    assert!(id.chars().count() <= 100 && long.contains(id), "{id}");
    let v = call(&url, "decide", json!({"id": id, "choice": "Courses"})).await;
    assert!(!is_err(&v), "{v}");
    assert_eq!(log.lock().unwrap().len(), 1);
}
