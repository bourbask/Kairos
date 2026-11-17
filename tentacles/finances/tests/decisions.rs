//! Outils `decisions` et `decide` face à un faux service de finances.

use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    extract::Query,
    routing::{get, post},
};
use kairos_finances::{api::FinanceApi, tools::FinanceServer};
use serde_json::{Value, json};

type Log = Arc<Mutex<Vec<String>>>;

const CYCLE: &str = r#"{"start":"2026-10-28","end":"2026-11-30","today":"2026-11-02","days_left":28,"mode":"serein","available":{"available":73.0,"balance":500.0,"charges_left_total":50.0,"variable_expected":{},"margin":50.0,"days_left":28},"charges_left":[],"envelopes":{"Courses":300,"Loisirs":80},"spent":{},"cushion":{"balance":5000.0,"floor":3000},"quality":"fiable","stale":false,"last_sync":null,"consent_expired":false,"basis":null}"#;

async fn start(ops: Value) -> (String, Log) {
    start_with(ops, false).await
}

/// Avec `categories`, le faux service connaît les catégories et leurs suggestions.
async fn start_with(ops: Value, categories: bool) -> (String, Log) {
    let log: Log = Default::default();
    let l = log.clone();
    let mut app = Router::new()
        .route("/api/cycle", get(|| async { Json(serde_json::from_str::<Value>(CYCLE).unwrap()) }))
        .route("/api/unclassified", get(move || async move { Json(ops) }))
        .route("/api/rules", post(move |Json(b): Json<Value>| async move {
            l.lock().unwrap().push(b.to_string());
            let made = b["create"] == json!(true);
            Json(json!({"rule_id":"r1","created":true,"applied":true,"category_created":made}))
        }));
    if categories {
        app = app
            .route("/api/categories", get(|| async {
                Json(json!({"categories":[
                    {"name":"Courses","budget":true,"poste":"courses"},
                    {"name":"Santé","budget":false,"poste":null},
                    {"name":"Divers","budget":false,"poste":null},
                    {"name":"Cadeaux","budget":false,"poste":null},
                    {"name":"Transport","budget":false,"poste":null}]}))
            }))
            .route("/api/categories/suggest", get(|Query(q): Query<Vec<(String, String)>>| async move {
                assert!(q.contains(&("n".into(), "4".into())), "{q:?}");
                let s: Vec<Value> = q.iter().filter(|(k, _)| k == "payee").map(|(_, p)| {
                    let matched = if p.contains("GARAGE") { 0 } else { 1 };
                    json!({"payee": p, "categories": ["Santé","Inconnue","Courses","Divers","Cadeaux","Transport"], "matched": matched})
                }).collect();
                Json(json!({"suggestions": s}))
            }));
    }
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
    assert!(b.get("suggestions").is_none() && b.get("can_create").is_none(), "{b}");
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

#[tokio::test]
async fn decisions_offer_all_categories_and_four_suggestions() {
    let (url, _) = start_with(three_ops(), true).await;
    let d: Vec<Value> = serde_json::from_str(text(&call(&url, "decisions", json!({})).await)).unwrap();
    let b = d.iter().find(|x| x["id"] == "BOULANGERIE ACME").unwrap();
    assert_eq!(b["alternatives"].as_array().unwrap().len(), 5);
    assert_eq!(b["alternatives"][0], json!({"id":"Courses","label":"Courses","badge":"budget"}));
    assert_eq!(b["alternatives"][1], json!({"id":"Santé","label":"Santé"}));
    // catégorie inconnue écartée, quatre au plus, dans l'ordre du service
    let ids: Vec<&str> = b["suggestions"].as_array().unwrap().iter().map(|o| o["id"].as_str().unwrap()).collect();
    assert_eq!(ids, ["Santé", "Courses", "Divers", "Cadeaux"]);
    assert_eq!(b["suggestions"][1]["badge"], "budget");
    assert_eq!(b["proposal"], json!({"id":"Santé","label":"Santé","reason":"Déjà utilisée pour ce commerçant."}));
    assert_eq!(b["can_create"], true);
    let g = d.iter().find(|x| x["id"] == "GARAGE EXEMPLE").unwrap();
    assert_eq!(g["proposal"]["reason"], "Parmi les plus utilisées.");
    // champs communs inchangés
    assert_eq!(b["headline"], "12,34 €");
    assert_eq!(b["expires"], "2026-12-03");
}

#[tokio::test]
async fn decide_any_existing_category() {
    let (url, log) = start_with(three_ops(), true).await;
    let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":"SANTÉ"})).await;
    assert!(!is_err(&v), "{v}");
    assert!(text(&v).contains("→ Santé"), "{v}");
    assert_eq!(log.lock().unwrap().as_slice(),
        [json!({"payee":"GARAGE EXEMPLE","category":"Santé","create":false}).to_string()]);
}

#[tokio::test]
async fn decide_poste_still_accepted_with_categories() {
    let (url, log) = start_with(three_ops(), true).await;
    let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":"loisirs"})).await;
    assert!(!is_err(&v), "{v}");
    assert_eq!(log.lock().unwrap().as_slice(), [json!({"payee":"GARAGE EXEMPLE","poste":"Loisirs"}).to_string()]);
}

#[tokio::test]
async fn decide_unknown_category_needs_explicit_creation() {
    let (url, log) = start_with(three_ops(), true).await;
    let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":"Voyages"})).await;
    assert!(is_err(&v) && text(&v).contains("Catégorie inconnue"), "{v}");
    assert!(log.lock().unwrap().is_empty());
    let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":"  Voyages\n","create":true})).await;
    assert!(!is_err(&v), "{v}");
    assert!(text(&v).contains("→ Voyages (catégorie créée)"), "{v}");
    assert_eq!(log.lock().unwrap().as_slice(),
        [json!({"payee":"GARAGE EXEMPLE","category":"Voyages","create":true}).to_string()]);
}

#[tokio::test]
async fn decide_create_rejects_bad_names() {
    let (url, log) = start_with(three_ops(), true).await;
    for name in ["   ", &"x".repeat(61)] {
        let v = call(&url, "decide", json!({"id":"GARAGE EXEMPLE","choice":name,"create":true})).await;
        assert!(is_err(&v), "{v}");
    }
    assert!(log.lock().unwrap().is_empty());
}
