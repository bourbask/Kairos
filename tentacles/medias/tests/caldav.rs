//! Synchronisation du calendrier des sorties face à de faux gestionnaires et un faux serveur CalDAV.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    body::Bytes,
    extract::{Path, Request},
    http::StatusCode,
    routing::{any, get},
};
use chrono::{TimeZone, Utc};
use kairos_medias::{caldav, config::Stack, store::Store};
use serde_json::json;

#[derive(Default)]
struct World {
    exists: bool,
    /// Épisodes renvoyés par le calendrier des séries.
    episodes: usize,
    /// Événements présents sur le serveur CalDAV : uid → ics.
    events: HashMap<String, String>,
    log: Vec<String>,
}

type W = Arc<Mutex<World>>;

async fn fake(w: W) -> String {
    let (w1, w2, w3) = (w.clone(), w.clone(), w.clone());
    let app = Router::new()
        .route(
            "/sonarr/api/v3/calendar",
            get(move || async move {
                let n = w1.lock().unwrap().episodes;
                let eps: Vec<_> = (1..=n)
                    .map(|i| {
                        json!({"seriesId": 1, "seasonNumber": 1, "episodeNumber": i, "title": "Épisode",
                            "airDateUtc": format!("2026-11-{:02}T15:00:00Z", 3 + i), "monitored": true, "runtime": 24,
                            "series": {"title": "Série Zêta"}})
                    })
                    .chain(std::iter::once(json!({"seriesId": 2, "seasonNumber": 1, "episodeNumber": 1,
                        "airDateUtc": "2026-11-05T15:00:00Z", "monitored": false, "series": {"title": "Non suivie"}})))
                    .collect();
                Json(json!(eps))
            }),
        )
        .route(
            "/radarr/api/v3/calendar",
            get(|| async {
                Json(json!([{"id": 4, "title": "Film Êta", "monitored": true,
                    "inCinemas": "2020-01-01T00:00:00Z", "digitalRelease": "2026-11-20T00:00:00Z"}]))
            }),
        )
        .route(
            "/dav/u/sorties/",
            any(move |req: Request| async move {
                let mut w = w2.lock().unwrap();
                let m = req.method().as_str().to_string();
                w.log.push(m.clone());
                match m.as_str() {
                    "PROPFIND" if w.exists => StatusCode::MULTI_STATUS,
                    "PROPFIND" => StatusCode::NOT_FOUND,
                    "MKCALENDAR" => {
                        w.exists = true;
                        StatusCode::CREATED
                    }
                    _ => StatusCode::METHOD_NOT_ALLOWED,
                }
            }),
        )
        .route(
            "/dav/u/sorties/{file}",
            any(move |Path(file): Path<String>, req: Request| async move {
                let m = req.method().as_str().to_string();
                let body: Bytes = axum::body::to_bytes(req.into_body(), 1 << 20).await.unwrap();
                let uid = file.trim_end_matches(".ics").to_string();
                let mut w = w3.lock().unwrap();
                w.log.push(format!("{m} {uid}"));
                match m.as_str() {
                    "PUT" => {
                        w.events.insert(uid, String::from_utf8(body.to_vec()).unwrap());
                        StatusCode::CREATED
                    }
                    "DELETE" => {
                        w.events.remove(&uid);
                        StatusCode::NO_CONTENT
                    }
                    _ => StatusCode::METHOD_NOT_ALLOWED,
                }
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await });
    url
}

fn stack(base: &str) -> Stack {
    let mut m: HashMap<String, String> = HashMap::new();
    for s in ["sonarr", "radarr"] {
        m.insert(format!("MEDIAS_{}_URL", s.to_uppercase()), format!("{base}/{s}"));
        m.insert(format!("MEDIAS_{}_KEY", s.to_uppercase()), "k".into());
    }
    m.insert("MEDIAS_CALDAV_URL".into(), format!("{base}/dav/u/sorties"));
    m.insert("MEDIAS_CALDAV_USER".into(), "u".into());
    m.insert("MEDIAS_CALDAV_PASS".into(), "p".into());
    let get = move |k: &str| m.get(k).cloned();
    Stack::from_lookup(&get).unwrap()
}

#[tokio::test]
async fn sync_creates_calendar_then_writes_only_changes() {
    let w: W = Arc::new(Mutex::new(World { episodes: 2, ..Default::default() }));
    let base = fake(w.clone()).await;
    let stack = stack(&base);
    let cal = stack.caldav.clone().unwrap();
    let store = Store::memory();
    let now = Utc.with_ymd_and_hms(2026, 11, 3, 12, 0, 0).unwrap();

    // 1er passage : calendrier créé, 2 épisodes suivis + 1 sortie numérique (le cinéma de 2020 est hors fenêtre).
    let r = caldav::sync(&stack, &store, &cal, now).await.unwrap();
    assert_eq!((r.written, r.removed, r.unchanged), (3, 0, 0));
    {
        let w = w.lock().unwrap();
        assert!(w.log.contains(&"MKCALENDAR".to_string()));
        let mut uids: Vec<_> = w.events.keys().cloned().collect();
        uids.sort();
        assert_eq!(uids, ["kairos-ep-1-1-1", "kairos-ep-1-1-2", "kairos-film-4-numerique"]);
        assert!(w.events["kairos-ep-1-1-1"].contains("SUMMARY:Série Zêta S01E01"));
    }

    // 2e passage sans changement : rien n'est réécrit.
    w.lock().unwrap().log.clear();
    let r = caldav::sync(&stack, &store, &cal, now).await.unwrap();
    assert_eq!((r.written, r.removed, r.unchanged), (0, 0, 3));
    assert_eq!(w.lock().unwrap().log, ["PROPFIND"]);

    // Un épisode disparaît des gestionnaires : il est retiré du calendrier.
    w.lock().unwrap().episodes = 1;
    let r = caldav::sync(&stack, &store, &cal, now).await.unwrap();
    assert_eq!((r.written, r.removed, r.unchanged), (0, 1, 2));
    assert!(!w.lock().unwrap().events.contains_key("kairos-ep-1-1-2"));

    // Calendrier supprimé côté serveur : recréé et tout réécrit.
    {
        let mut w = w.lock().unwrap();
        w.exists = false;
        w.events.clear();
    }
    let r = caldav::sync(&stack, &store, &cal, now).await.unwrap();
    assert_eq!((r.written, r.removed), (2, 0));
    assert_eq!(w.lock().unwrap().events.len(), 2);
}

#[tokio::test]
async fn unreachable_manager_removes_nothing() {
    let w: W = Arc::new(Mutex::new(World { episodes: 1, ..Default::default() }));
    let base = fake(w.clone()).await;
    let ok = stack(&base);
    let cal = ok.caldav.clone().unwrap();
    let store = Store::memory();
    let now = Utc.with_ymd_and_hms(2026, 11, 3, 12, 0, 0).unwrap();
    caldav::sync(&ok, &store, &cal, now).await.unwrap();
    let before = w.lock().unwrap().events.len();

    // Gestionnaire de séries injoignable : la synchronisation s'interrompt sans rien retirer.
    let mut m: HashMap<String, String> = HashMap::new();
    m.insert("MEDIAS_SONARR_URL".into(), "http://127.0.0.1:9/sonarr".into());
    m.insert("MEDIAS_SONARR_KEY".into(), "k".into());
    let broken = Stack::from_lookup(&move |k: &str| m.get(k).cloned()).unwrap();
    assert!(caldav::sync(&broken, &store, &cal, now).await.is_err());
    assert_eq!(w.lock().unwrap().events.len(), before);
}
