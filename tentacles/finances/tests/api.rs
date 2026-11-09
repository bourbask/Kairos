use axum::{
    Json, Router,
    extract::Query,
    http::{HeaderMap, StatusCode},
    routing::{get, post},
};
use kairos_finances::api::{ApiError, FinanceApi};
use serde_json::{Value, json};
use std::collections::HashMap;

const META: &str = r#""quality":"fiable","stale":false,"last_sync":"2026-11-01T06:00:00Z","consent_expired":false,"basis":{"pay_date":null,"complete_cycles":3,"reference_period":null,"coverage":0.7},"extra":1"#;

fn meta(body: &str) -> Value {
    serde_json::from_str(&format!("{{{body},{META}}}")).unwrap()
}

fn authed(h: &HeaderMap) -> bool {
    h.get("authorization").and_then(|v| v.to_str().ok()) == Some("Bearer tok")
}

async fn serve() -> String {
    let app = Router::new()
        .route("/api/cycle", get(|h: HeaderMap| async move {
            if !authed(&h) { return (StatusCode::UNAUTHORIZED, Json(json!({"detail": "Jeton d'accès requis."}))); }
            (StatusCode::OK, Json(meta(r#""start":"2026-10-28","end":"2026-11-30","today":"2026-11-02","days_left":28,"mode":"serein","available":{"available":73.03,"balance":500.0,"charges_left_total":50.0,"variable_expected":{"Courses":270.97},"margin":50.0,"days_left":28},"charges_left":[{"name":"Assurance","amount":50,"day":null,"due":null}],"envelopes":{"courses":329.03},"spent":{"courses":40},"cushion":{"balance":5000.0,"floor":3000}"#)))
        }))
        .route("/api/cycle/afford", get(|Query(q): Query<HashMap<String, String>>| async move {
            if q["amount"] == "0" {
                return (StatusCode::UNPROCESSABLE_ENTITY, Json(json!({"detail": "Le montant doit être un nombre fini strictement positif."})));
            }
            if q["amount"] == "-1" {
                return (StatusCode::UNPROCESSABLE_ENTITY, Json(json!({"detail": [{"msg": "x"}]})));
            }
            assert_eq!(q.get("poste").map(String::as_str), Some("essence"));
            (StatusCode::OK, Json(meta(r#""ok":false,"after":-70.97,"reason":"disponible insuffisant","cuts":[{"poste":"restos","amount":0.01},{"poste":null,"amount":5}],"available":73.03,"reserve_used":56.0"#)))
        }))
        .route("/api/cycle/save", get(|Query(q): Query<HashMap<String, String>>| async move {
            if q["amount"] == "13" {
                return Json(json!({"ok": true, "after": 1.0, "available": 2.0}));
            }
            Json(meta(r#""ok":true,"after":23.03,"available":73.03"#))
        }))
        .route("/api/envelopes", get(|| async { Json(meta(r#""days":31,"mode":"serein","envelopes":{"restos":0.0,"courses":300.0}"#)) }))
        .route("/api/finance-alerts", get(|| async { Json(meta(r#""alerts":[{"id":"a","kind":"negative","title":"t","details":"d","remedy":"r"}]"#)) }))
        .route("/api/unclassified", get(|| async { Json(json!([{"id":"c5","date":"2026-11-02","payee":"BOULANGERIE","amount":12.5}])) }))
        .route("/api/rules", post(|Json(b): Json<Value>| async move {
            assert_eq!(b, json!({"payee": "MAGASIN", "poste": "courses"}));
            Json(json!({"rule_id": "1", "created": true, "applied": true}))
        }));
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(l, app).await.unwrap() });
    format!("http://{addr}/")
}

async fn api() -> FinanceApi {
    FinanceApi::new(serve().await, "tok".into())
}

#[tokio::test]
async fn routes_deserialize() {
    let api = api().await;
    let c = api.cycle().await.unwrap();
    assert_eq!(c.available.available, 73.03);
    assert_eq!(c.charges_left[0].day, None);
    assert_eq!(c.meta.last_sync.as_deref(), Some("2026-11-01T06:00:00Z"));
    let v = api.afford(200.0, Some("essence")).await.unwrap();
    assert!(!v.ok && v.cuts.len() == 2 && v.cuts[1].poste.is_none());
    assert!(api.save(50.0).await.unwrap().ok);
    assert_eq!(api.envelopes().await.unwrap().days, 31);
    assert_eq!(api.alerts().await.unwrap().alerts[0].kind, "negative");
    assert_eq!(api.unclassified().await.unwrap()[0].payee, "BOULANGERIE");
    assert!(api.create_rule("MAGASIN", "courses").await.unwrap().created);
}

#[tokio::test]
async fn bad_token_is_unavailable() {
    let bad = FinanceApi::new(serve().await, "nope".into());
    assert!(matches!(bad.cycle().await, Err(ApiError::Unavailable)));
}

#[tokio::test]
async fn rejected_and_unavailable() {
    let api = api().await;
    match api.afford(0.0, Some("essence")).await {
        Err(ApiError::Rejected(m)) => assert!(m.contains("strictement positif")),
        other => panic!("{other:?}"),
    }
    match api.afford(-1.0, Some("essence")).await {
        Err(ApiError::Rejected(m)) => assert_eq!(m, "Requête refusée."),
        other => panic!("{other:?}"),
    }
    let closed = FinanceApi::new("http://127.0.0.1:1".into(), "tok".into());
    assert!(matches!(closed.cycle().await, Err(ApiError::Unavailable)));
}

#[tokio::test]
async fn missing_quality_is_an_error() {
    let api = api().await;
    // Réponse sans champs méta : erreur, pas de valeurs par défaut.
    assert!(matches!(api.save(13.0).await, Err(ApiError::Unavailable)));
}
