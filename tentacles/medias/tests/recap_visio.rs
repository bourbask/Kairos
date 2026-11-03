//! Résumé sans spoiler et débit réduit pendant les visios, face à de faux services.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    http::{StatusCode, header::SET_COOKIE},
    response::IntoResponse,
    routing::{any, get, post},
};
use chrono::Utc;
use kairos_medias::{config::Stack, store::Store, tools::MediaServer, visio};
use serde_json::{Value, json};
use tokio::sync::OnceCell;

#[derive(Default)]
struct World {
    /// Corps renvoyé par le REPORT de l'agenda.
    agenda: String,
    slow: bool,
    toggles: usize,
}

type W = Arc<Mutex<World>>;

async fn fake(w: W) -> String {
    let (w1, w2, w3) = (w.clone(), w.clone(), w.clone());
    let app = Router::new()
        .route(
            "/plex/library/sections",
            get(|| async { Json(json!({"MediaContainer": {"Directory": [{"key": "2", "type": "show"}]}})) }),
        )
        .route(
            "/plex/library/sections/2/all",
            get(|| async {
                Json(json!({"MediaContainer": {"Metadata": [
                    {"ratingKey": "9", "type": "show", "title": "Série Sigma", "summary": "Une quête."}]}}))
            }),
        )
        .route(
            "/plex/library/metadata/9/allLeaves",
            get(|| async {
                Json(json!({"MediaContainer": {"Metadata": [
                    {"ratingKey": "a", "parentIndex": 1, "index": 1, "title": "Début", "summary": "S1 début.", "viewCount": 1},
                    {"ratingKey": "b", "parentIndex": 2, "index": 1, "title": "Retour", "summary": "S2 retour.", "viewCount": 1},
                    {"ratingKey": "c", "parentIndex": 2, "index": 2, "title": "Duel", "summary": "S2 duel.", "viewCount": 1},
                    {"ratingKey": "d", "parentIndex": 2, "index": 3, "title": "Trahison", "summary": "SPOILER ici."},
                    {"ratingKey": "e", "parentIndex": 0, "index": 1, "title": "Spécial", "summary": "Hors série.", "viewCount": 1},
                ]}}))
            }),
        )
        .route(
            "/dav/u/perso/",
            any(move || async move {
                let body = w1.lock().unwrap().agenda.clone();
                (StatusCode::MULTI_STATUS, body)
            }),
        )
        .route("/qbit/api/v2/auth/login", post(|| async { (StatusCode::OK, [(SET_COOKIE, "SID=s")]).into_response() }))
        .route(
            "/qbit/api/v2/transfer/speedLimitsMode",
            get(move || async move { Json(json!(if w2.lock().unwrap().slow { 1 } else { 0 })) }),
        )
        .route(
            "/qbit/api/v2/transfer/toggleSpeedLimitsMode",
            post(move || async move {
                let mut w = w3.lock().unwrap();
                w.slow = !w.slow;
                w.toggles += 1;
                StatusCode::OK
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await });
    url
}

fn stack(base: &str) -> Stack {
    let mut m: HashMap<String, String> = HashMap::new();
    m.insert("MEDIAS_PLEX_URL".into(), format!("{base}/plex"));
    m.insert("MEDIAS_PLEX_KEY".into(), "k".into());
    m.insert("MEDIAS_QBIT_URL".into(), format!("{base}/qbit"));
    m.insert("MEDIAS_QBIT_USER".into(), "u".into());
    m.insert("MEDIAS_QBIT_PASS".into(), "p".into());
    m.insert("MEDIAS_VISIO_CALENDAR_URL".into(), format!("{base}/dav/u/perso"));
    m.insert("MEDIAS_CALDAV_USER".into(), "u".into());
    m.insert("MEDIAS_CALDAV_PASS".into(), "p".into());
    Stack::from_lookup(&move |k: &str| m.get(k).cloned()).unwrap()
}

const MEET: &str = "<multistatus><response><calendar-data>BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Entretien\r\nLOCATION:https://meet.google.com/x\r\nEND:VEVENT\r\nEND:VCALENDAR</calendar-data></response></multistatus>";

#[tokio::test]
async fn recap_stops_at_the_last_watched_episode() {
    let w: W = Default::default();
    let base = fake(w).await;
    let stack = Arc::new(stack(&base));
    let store = Arc::new(Store::memory());
    let machine = Arc::new(OnceCell::new());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        MediaServer::new(stack.clone(), store.clone(), machine.clone())
    }));
    let call = |args: Value| {
        let url = url.clone();
        async move {
            let body = json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"recap","arguments":args}});
            let v: Value = reqwest::Client::new()
                .post(&url)
                .header("Accept", "application/json, text/event-stream")
                .bearer_auth("t")
                .json(&body)
                .send()
                .await
                .unwrap()
                .json()
                .await
                .unwrap();
            v["result"]["content"][0]["text"].as_str().unwrap_or_default().to_string()
        }
    };
    let t = call(json!({"title": "sigma"})).await;
    assert!(t.starts_with("« Série Sigma », saison 2, épisodes vus jusqu'à S02E02 (prochain : S02E03) :"), "{t}");
    assert!(t.contains("- S02E01 « Retour » : S2 retour.\n- S02E02 « Duel » : S2 duel."), "{t}");
    assert!(!t.contains("SPOILER") && !t.contains("Hors série") && !t.contains("S1 début"), "{t}");
    assert!(t.ends_with("Série : Une quête."), "{t}");
    let t = call(json!({"title": "sigma", "season": 1})).await;
    assert!(t.contains("- S01E01 « Début » : S1 début.") && !t.contains("SPOILER"), "{t}");
    let t = call(json!({"title": "sigma", "season": 3})).await;
    assert!(t.contains("pas encore commencée"), "{t}");
}

#[tokio::test]
async fn visio_slows_then_restores_only_what_it_changed() {
    let w: W = Default::default();
    let base = fake(w.clone()).await;
    let stack = stack(&base);
    let watch = stack.visio.clone().unwrap();
    let store = Store::memory();
    let now = Utc::now();

    // Pas de visio : rien.
    assert_eq!(visio::step(&stack, &store, &watch, now).await, None);
    // Visio en cours : débit réduit, une seule fois.
    w.lock().unwrap().agenda = MEET.into();
    assert_eq!(visio::step(&stack, &store, &watch, now).await.as_deref(), Some("ralenti (Entretien)"));
    assert_eq!(visio::step(&stack, &store, &watch, now).await, None);
    assert!(w.lock().unwrap().slow);
    assert_eq!(visio::slowed_for(&store).as_deref(), Some("Entretien"));
    // Fin de la visio : rétabli.
    w.lock().unwrap().agenda = "<multistatus/>".into();
    assert_eq!(visio::step(&stack, &store, &watch, now).await.as_deref(), Some("rétabli"));
    assert!(!w.lock().unwrap().slow);
    assert_eq!(w.lock().unwrap().toggles, 2);

    // Débit déjà réduit à la main avant la visio : ni réduit ni rétabli par le mécanisme.
    {
        let mut w = w.lock().unwrap();
        w.slow = true;
        w.agenda = MEET.into();
    }
    assert_eq!(visio::step(&stack, &store, &watch, now).await, None);
    w.lock().unwrap().agenda = "<multistatus/>".into();
    assert_eq!(visio::step(&stack, &store, &watch, now).await, None);
    assert!(w.lock().unwrap().slow);
    assert_eq!(w.lock().unwrap().toggles, 2);
}
