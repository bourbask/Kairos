//! Outils `app_*` de l'espace Finances face à un faux service de finances.

use std::sync::{Arc, Mutex};

use axum::{Json, Router, http::StatusCode, routing::get};
use kairos_finances::{api::FinanceApi, tools::FinanceServer};
use serde_json::{Value, json};

type Log = Arc<Mutex<Vec<String>>>;

const META: &str = r#""quality":"fiable","stale":false,"last_sync":null,"consent_expired":false,"basis":null"#;

fn cycle(meta: &str) -> Value {
    serde_json::from_str(&format!(
        r#"{{"start":"2026-10-28","end":"2026-11-30","today":"2026-11-02","days_left":28,"mode":"serein","available":{{"available":73.0,"balance":500.0,"charges_left_total":50.0,"variable_expected":{{}},"margin":50.0,"days_left":28}},"charges_left":[{{"name":"PRLV​  ASSURANCE\nX","amount":50,"day":null,"due":"2026-11-15"}},{{"name":"Charge fixe","amount":400,"day":5,"due":null}}],"envelopes":{{"courses":300,"essence":80}},"spent":{{"courses":120.5,"restos":12}},"cushion":{{"balance":2000.0,"floor":1000}},{meta}}}"#
    ))
    .unwrap()
}

/// Réponse du faux service pour `path`, ou `None` pour un échec 500.
fn body(path: &str, meta: &str, down: &[&str]) -> Option<Value> {
    if down.contains(&path) {
        return None;
    }
    Some(match path {
        "cycle" => cycle(meta),
        "alerts" => serde_json::from_str(&format!(
            r#"{{"alerts":[{{"id":"a1","kind":"near_limit","title":"T1","details":"D1","remedy":"R1"}},{{"id":"a2","kind":"unexpected","title":"T2","details":"D2","remedy":""}}],{meta}}}"#
        ))
        .unwrap(),
        "afford" => serde_json::from_str(&format!(
            r#"{{"ok":false,"after":-70.97,"reason":"disponible insuffisant","cuts":[{{"poste":"courses","amount":0.01}},{{"poste":null,"amount":5}}],"available":73.0,"reserve_used":0.0,{meta}}}"#
        ))
        .unwrap(),
        "networth" => json!({"total": 42000.0, "net_of_debt": 30000.0, "debt": 8000.0, "loans": [{"name": "Prêt", "remaining_balance": 8000.0}], "dormant": 3}),
        "snapshots" => json!({"snapshots": [{"month": "2026-09", "net_worth": 28000.0, "debt": 8500.0}, {"month": "2026-10", "net_worth": 30000.0}]}),
        "loans" => json!({"loans": [{"name": "Prêt\u{202e}A", "balance": 8000.0, "payment": 100.0, "rate": 0.03, "payoff_month": "2029-01"}, {"name": "Prêt perso", "balance": 500.0, "payment": 0, "rate": null, "payoff_month": null}]}),
        "portfolio" => json!({"total_cost": 10000.0, "instruments": [{"name": "ETF Monde", "cost": 7000.0, "bucket": "mid", "class": "etf", "weight": 0.7}, {"name": "Livret", "cost": 3000.0, "bucket": "low", "class": "cash", "weight": 0.3}], "by_bucket": {"high": 0.0, "mid": 7000.0, "low": 3000.0}, "bucket_weights": {"high": 0.0, "mid": 0.7, "low": 0.3}, "crypto_weight": 0}),
        _ => unreachable!(),
    })
}

async fn start(meta: &str, down: &[&str]) -> (String, Log) {
    start_with(meta, down, &[]).await
}

/// Comme `start`, avec des réponses remplacées pour certains endpoints.
async fn start_with(meta: &str, down: &[&str], alt: &[(&str, Value)]) -> (String, Log) {
    let log: Log = Default::default();
    let mut app = Router::new();
    for (route, name) in [
        ("/api/cycle", "cycle"),
        ("/api/finance-alerts", "alerts"),
        ("/api/cycle/afford", "afford"),
        ("/api/networth", "networth"),
        ("/api/snapshots", "snapshots"),
        ("/api/loans", "loans"),
        ("/api/portfolio", "portfolio"),
    ] {
        let (l, m) = (log.clone(), meta.to_string());
        let down: Vec<String> = down.iter().map(|s| s.to_string()).collect();
        let alt = alt.iter().find(|(n, _)| *n == name).map(|(_, v)| v.clone());
        app = app.route(route, get(move |axum::extract::RawQuery(q): axum::extract::RawQuery| async move {
            l.lock().unwrap().push(format!("GET {name} {}", q.unwrap_or_default()));
            let down: Vec<&str> = down.iter().map(String::as_str).collect();
            match alt.or_else(|| body(name, &m, &down)) {
                Some(b) => (StatusCode::OK, Json(b)),
                None => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({}))),
            }
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

fn is_err(v: &Value) -> bool {
    v["result"]["isError"] == json!(true)
}

fn parsed(v: &Value) -> Value {
    assert!(!is_err(v), "{v}");
    serde_json::from_str(v["result"]["content"][0]["text"].as_str().unwrap()).unwrap()
}

#[tokio::test]
async fn app_cycle_json_shape() {
    let (url, _) = start(META, &[]).await;
    let c = parsed(&call(&url, "app_finance_cycle", json!({})).await);
    assert_eq!(c["start"], "2026-10-28");
    assert_eq!(c["end"], "2026-11-30");
    assert_eq!(c["today"], "2026-11-02");
    assert_eq!(c["days_left"], 28);
    assert_eq!(c["available"], 73.0);
    assert_eq!(c["balance"], 500.0);
    assert_eq!(
        c["envelopes"],
        json!([
            {"poste": "courses", "planned": 300.0, "spent": 120.5},
            {"poste": "essence", "planned": 80.0, "spent": 0.0},
            {"poste": "restos", "planned": 0.0, "spent": 12.0}
        ])
    );
    // Libellés bancaires nettoyés ; date absente quand l'échéance est inconnue.
    assert_eq!(
        c["charges"],
        json!([
            {"name": "PRLV ASSURANCE X", "amount": 50.0, "date": "2026-11-15"},
            {"name": "Charge fixe", "amount": 400.0}
        ])
    );
    assert_eq!(c["cushion"], json!({"balance": 2000.0, "floor": 1000.0}));
    assert_eq!(
        c["alerts"],
        json!([
            {"id": "a1", "title": "T1", "details": "D1", "remedy": "R1"},
            {"id": "a2", "title": "T2", "details": "D2"}
        ])
    );
    assert_eq!(c["postes"], json!(["courses", "essence"]));
    assert_eq!(c["freshness"], json!({"stale": false, "consent_expired": false}));
}

#[tokio::test]
async fn app_cycle_freshness_flags() {
    let meta = r#""quality":"partielle","stale":true,"last_sync":"2026-10-30T06:00:00Z","consent_expired":true,"basis":null"#;
    let (url, _) = start(meta, &[]).await;
    let c = parsed(&call(&url, "app_finance_cycle", json!({})).await);
    assert_eq!(
        c["freshness"],
        json!({"stale": true, "consent_expired": true, "last_sync": "2026-10-30T06:00:00Z"})
    );
    // Alertes indisponibles : section omise, le cycle reste servi.
    let (url, _) = start(META, &["alerts"]).await;
    let c = parsed(&call(&url, "app_finance_cycle", json!({})).await);
    assert!(c.get("alerts").is_none(), "{c}");
    assert_eq!(c["available"], 73.0);
}

#[tokio::test]
async fn app_wealth_json_shape() {
    let (url, _) = start(META, &[]).await;
    let w = parsed(&call(&url, "app_finance_wealth", json!({})).await);
    assert_eq!(w["net_worth"], 30000.0);
    assert_eq!(w["debt"], 8000.0);
    assert_eq!(
        w["history"],
        json!([{"month": "2026-09", "net_worth": 28000.0}, {"month": "2026-10", "net_worth": 30000.0}])
    );
    assert_eq!(
        w["loans"],
        json!([
            {"name": "PrêtA", "balance": 8000.0, "payment": 100.0, "payoff_month": "2029-01"},
            {"name": "Prêt perso", "balance": 500.0}
        ])
    );
    assert_eq!(
        w["portfolio"],
        json!({
            "total": 10000.0,
            "buckets": [
                {"name": "high", "weight": 0.0, "amount": 0.0},
                {"name": "mid", "weight": 0.7, "amount": 7000.0},
                {"name": "low", "weight": 0.3, "amount": 3000.0}
            ],
            "lines": [
                {"name": "ETF Monde", "amount": 7000.0, "bucket": "mid"},
                {"name": "Livret", "amount": 3000.0, "bucket": "low"}
            ]
        })
    );
    assert_eq!(w["freshness"], json!({"stale": false, "consent_expired": false}));
}

#[tokio::test]
async fn wealth_partial_when_endpoint_fails() {
    let (url, _) = start(META, &["networth", "loans"]).await;
    let w = parsed(&call(&url, "app_finance_wealth", json!({})).await);
    assert!(w.get("net_worth").is_none() && w.get("debt").is_none() && w.get("loans").is_none(), "{w}");
    assert_eq!(w["history"].as_array().unwrap().len(), 2);
    assert_eq!(w["portfolio"]["total"], 10000.0);
    // Tout indisponible : un objet vide, jamais une erreur.
    let (url, _) = start(META, &["cycle", "networth", "snapshots", "loans", "portfolio"]).await;
    assert_eq!(parsed(&call(&url, "app_finance_wealth", json!({})).await), json!({}));
}

#[tokio::test]
async fn app_afford_maps_verdict() {
    let (url, log) = start(META, &[]).await;
    let v = parsed(&call(&url, "app_afford", json!({"amount": 144.0, "poste": "Courses"})).await);
    assert_eq!(
        v,
        json!({
            "ok": false,
            "after": -70.97,
            "reason": "disponible insuffisant",
            "cuts": [{"poste": "courses", "amount": 0.01}, {"amount": 5.0}]
        })
    );
    assert!(log.lock().unwrap().iter().any(|l| l.starts_with("GET afford") && l.contains("poste=courses")));
    // Poste inconnu : refus avant tout appel au calcul.
    let v = call(&url, "app_afford", json!({"amount": 10.0, "poste": "yachts"})).await;
    assert!(is_err(&v), "{v}");
    assert_eq!(log.lock().unwrap().iter().filter(|l| l.starts_with("GET afford")).count(), 1);
}

#[tokio::test]
async fn app_afford_rejects_non_positive() {
    let (url, log) = start(META, &[]).await;
    for a in [json!(0.0), json!(-5.0), json!(-0.01)] {
        assert!(is_err(&call(&url, "app_afford", json!({"amount": a})).await));
    }
    assert!(log.lock().unwrap().is_empty());
}

#[tokio::test]
async fn wealth_null_drops_only_the_broken_item() {
    let alt = [
        ("networth", json!({"total": 1.0, "net_of_debt": null, "debt": 500.0})),
        ("snapshots", json!({"snapshots": [{"month": "2026-09", "net_worth": null}, {"month": "2026-10", "net_worth": 30000.0}, 7]})),
        ("loans", json!({"loans": [{"name": "A", "balance": null, "payment": 10}, {"name": null, "balance": 5}, {"name": "B", "balance": 500.0, "payment": null}]})),
        ("portfolio", json!({"total_cost": null, "instruments": [{"name": "X", "cost": null, "bucket": "mid"}, {"name": "Y", "cost": 3.0, "bucket": "low"}], "by_bucket": {"high": null, "mid": 7.0, "low": 3.0, "other": 1.0}, "bucket_weights": {"mid": null, "low": 0.3}})),
    ];
    let (url, _) = start_with(META, &[], &alt).await;
    let w = parsed(&call(&url, "app_finance_wealth", json!({})).await);
    assert!(w.get("net_worth").is_none(), "{w}");
    assert_eq!(w["debt"], 500.0);
    assert_eq!(w["history"], json!([{"month": "2026-10", "net_worth": 30000.0}]));
    assert_eq!(w["loans"], json!([{"name": "B", "balance": 500.0}]));
    assert_eq!(
        w["portfolio"],
        json!({
            "buckets": [
                {"name": "mid", "amount": 7.0},
                {"name": "low", "weight": 0.3, "amount": 3.0},
                {"name": "other", "amount": 1.0}
            ],
            "lines": [{"name": "Y", "amount": 3.0, "bucket": "low"}]
        })
    );
}

#[tokio::test]
async fn loans_fall_back_to_networth() {
    let (url, _) = start(META, &["loans"]).await;
    let w = parsed(&call(&url, "app_finance_wealth", json!({})).await);
    assert_eq!(w["loans"], json!([{"name": "Prêt", "balance": 8000.0}]));
}

/// Toutes les chaînes d'une réponse JSON.
fn strings(v: &Value, out: &mut Vec<String>) {
    match v {
        Value::String(s) => out.push(s.clone()),
        Value::Array(a) => a.iter().for_each(|x| strings(x, out)),
        Value::Object(o) => o.iter().for_each(|(k, x)| {
            out.push(k.clone());
            strings(x, out)
        }),
        _ => {}
    }
}

fn assert_clean(v: &Value) {
    let mut all = Vec::new();
    strings(v, &mut all);
    for s in all {
        assert!(
            !s.chars().any(|c| c.is_control() || matches!(c, '\u{200B}'..='\u{200F}' | '\u{202A}'..='\u{202E}' | '\u{2060}'..='\u{2069}' | '\u{FEFF}')),
            "{s:?}"
        );
    }
}

#[tokio::test]
async fn app_strings_are_cleaned() {
    let m = r#""quality":"fiable","stale":false,"last_sync":null,"consent_expired":false,"basis":null"#;
    let mut cyc = cycle(m);
    cyc["envelopes"] = json!({"cour\u{202e}ses": 300});
    cyc["spent"] = json!({"res\u{2066}tos": 3});
    let mut alerts: Value = serde_json::from_str(&format!("{{\"alerts\":[],{m}}}")).unwrap();
    alerts["alerts"] = json!([{"id": "a\u{0007}1", "kind": "near_limit", "title": "T\u{0007}1", "details": "D\u{200b}1", "remedy": "R\n\u{202e}1"}]);
    let mut afford: Value = serde_json::from_str(&format!("{{\"ok\":true,\"after\":1.0,\"available\":2.0,{m}}}")).unwrap();
    afford["reason"] = json!("rai\u{202e}son\u{0000}");
    afford["cuts"] = json!([{"poste": "re\u{2066}stos", "amount": 1.0}]);
    let alt = [
        ("cycle", cyc),
        ("alerts", alerts),
        ("afford", afford),
        ("snapshots", json!({"snapshots": [{"month": "2026\u{202e}-10", "net_worth": 1.0}]})),
        ("portfolio", json!({"total_cost": 1.0, "instruments": [{"name": "X", "cost": 1.0, "bucket": "m\u{0001}id"}], "by_bucket": {"hi\u{202e}gh": 1.0}, "bucket_weights": {}})),
    ];
    let (url, _) = start_with(META, &[], &alt).await;
    let c = parsed(&call(&url, "app_finance_cycle", json!({})).await);
    assert_clean(&c);
    assert_eq!(c["alerts"][0]["title"], "T 1");
    assert_clean(&parsed(&call(&url, "app_finance_wealth", json!({})).await));
    assert_clean(&parsed(&call(&url, "app_afford", json!({"amount": 5.0})).await));
}

#[tokio::test]
async fn amount_upper_bound() {
    let (url, log) = start(META, &[]).await;
    assert!(is_err(&call(&url, "app_afford", json!({"amount": 1.0e9 + 1.0})).await));
    assert!(log.lock().unwrap().is_empty());
    assert!(!is_err(&call(&url, "app_afford", json!({"amount": 1.0e9})).await));
}

#[tokio::test]
async fn afford_accepts_cleaned_poste() {
    // L'application ne voit que les postes nettoyés : ils doivent rester utilisables.
    let mut cyc = cycle(META);
    cyc["envelopes"] = json!({"cour\u{202e}ses": 300});
    let (url, log) = start_with(META, &[], &[("cycle", cyc)]).await;
    let c = parsed(&call(&url, "app_finance_cycle", json!({})).await);
    assert_eq!(c["postes"], json!(["courses"]));
    assert!(!is_err(&call(&url, "app_afford", json!({"amount": 5.0, "poste": "courses"})).await));
    assert!(log.lock().unwrap().iter().any(|l| l.starts_with("GET afford") && l.contains("poste=cour")));
}
