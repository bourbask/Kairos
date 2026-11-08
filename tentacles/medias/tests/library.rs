//! Bilans et autres médias (musique, livres, livres audio), face à de faux services (titres fictifs).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    extract::RawQuery,
    http::{StatusCode, header::SET_COOKIE},
    response::IntoResponse,
    routing::{get, post},
};
use chrono::Utc;
use kairos_medias::{config::Stack, store::Store, tools::MediaServer};
use serde_json::{Value, json};
use tokio::sync::OnceCell;

type Log = Arc<Mutex<Vec<String>>>;

fn rec(l: &Log, s: impl Into<String>) {
    l.lock().unwrap().push(s.into());
}

async fn fake(log: Log) -> String {
    let now = Utc::now().timestamp();
    let (l1, l2, l3) = (log.clone(), log.clone(), log.clone());
    let app = Router::new()
        // ---- Lecteur
        .route(
            "/plex/library/sections",
            get(|| async { Json(json!({"MediaContainer": {"Directory": [{"key": "2", "type": "show"}]}})) }),
        )
        .route(
            "/plex/library/sections/2/all",
            get(move || async move {
                Json(json!({"MediaContainer": {"Metadata": [
                    {"ratingKey": "1", "title": "Série Oubliée", "leafCount": 12, "viewedLeafCount": 3,
                     "lastViewedAt": now - 90 * 86400, "Guid": [{"id": "tvdb://1"}]},
                    {"ratingKey": "2", "title": "Série Récente", "leafCount": 12, "viewedLeafCount": 3,
                     "lastViewedAt": now - 86400},
                    {"ratingKey": "3", "title": "Série Finie", "leafCount": 5, "viewedLeafCount": 5,
                     "lastViewedAt": now - 90 * 86400},
                ]}}))
            }),
        )
        .route(
            "/plex/status/sessions/history/all",
            get(move || async move {
                Json(json!({"MediaContainer": {"Metadata": [
                    {"type": "episode", "title": "e1", "grandparentTitle": "Série A", "ratingKey": "10", "viewedAt": now - 100},
                    {"type": "episode", "title": "e2", "grandparentTitle": "Série A", "ratingKey": "11", "viewedAt": now - 200},
                    {"type": "episode", "title": "e1", "grandparentTitle": "Série B", "ratingKey": "20", "viewedAt": now - 300},
                    {"type": "movie", "title": "Film C", "ratingKey": "30", "viewedAt": now - 400},
                    {"type": "movie", "title": "Trop ancien", "ratingKey": "31", "viewedAt": now - 400 * 86400},
                ]}}))
            }),
        )
        .route(
            "/plex/library/metadata/{k}",
            get(|axum::extract::Path(k): axum::extract::Path<String>| async move {
                let ms = if k == "30" { 6_000_000 } else { 1_500_000 };
                Json(json!({"MediaContainer": {"Metadata": [{"ratingKey": k, "duration": ms}]}}))
            }),
        )
        // ---- Gestionnaire de séries
        .route(
            "/sonarr/api/v3/series",
            get(|| async { Json(json!([{"id": 1, "title": "Série Oubliée", "monitored": true, "tvdbId": 1}])) }),
        )
        // ---- Torrents
        .route("/qbit/api/v2/auth/login", post(|| async { (StatusCode::OK, [(SET_COOKIE, "SID=s")]).into_response() }))
        .route(
            "/qbit/api/v2/torrents/info",
            get(|| async {
                let g = 1_073_741_824i64;
                Json(json!([
                    {"name": "p1", "progress": 1.0, "private": true, "size": g, "ratio": 0.2, "max_ratio": -1,
                     "seeding_time": 86400, "max_seeding_time": 43200},
                    {"name": "p2", "progress": 1.0, "private": true, "size": g, "ratio": 0.2, "max_ratio": -1,
                     "seeding_time": 40 * 86400, "max_seeding_time": 43200},
                    {"name": "u1", "progress": 1.0, "private": false, "size": g, "ratio": 1.5, "max_ratio": 1.0,
                     "seeding_time": 3600, "max_seeding_time": 10080},
                    {"name": "u2", "progress": 1.0, "private": false, "size": g, "ratio": 0.0, "max_ratio": -1,
                     "seeding_time": 3600, "max_seeding_time": -1},
                    {"name": "dl", "progress": 0.5, "private": true, "size": g},
                ]))
            }),
        )
        // ---- Musique
        .route("/lidarr/api/v1/artist", get(|| async { Json(json!([])) }).post(move |Json(b): Json<Value>| async move {
            rec(&l1, format!("POST artist {} {} {} {}", b["foreignArtistId"], b["rootFolderPath"], b["metadataProfileId"], b["addOptions"]));
            Json(json!({"id": 5, "artistName": b["artistName"], "foreignArtistId": b["foreignArtistId"]}))
        }))
        .route(
            "/lidarr/api/v1/artist/lookup",
            get(|| async {
                Json(json!([{"id": 0, "artistName": "Groupe Thêta", "foreignArtistId": "abc-123", "disambiguation": "rock"}]))
            }),
        )
        .route("/lidarr/api/v1/rootfolder", get(|| async { Json(json!([{"path": "/data/music"}])) }))
        .route("/lidarr/api/v1/qualityprofile", get(|| async { Json(json!([{"id": 1, "name": "Any"}])) }))
        .route(
            "/lidarr/api/v1/metadataprofile",
            get(|| async { Json(json!([{"id": 2, "name": "None"}, {"id": 1, "name": "Standard"}])) }),
        )
        // ---- Livres
        .route(
            "/ll/api",
            get(move |RawQuery(q): RawQuery| async move {
                let q = q.unwrap_or_default();
                rec(&l2, format!("LL {q}"));
                if q.contains("cmd=findBook") {
                    return Json(json!([{"authorname": "Autrice Iota", "bookid": "OL1W", "bookname": "Livre Kappa", "bookpub": 1999}]))
                        .into_response();
                }
                if q.contains("cmd=getWanted") || q.contains("cmd=getSnatched") {
                    return Json(json!([])).into_response();
                }
                (StatusCode::OK, "OK").into_response()
            }),
        )
        // ---- Lecture
        .route(
            "/kavita/api/Plugin/authenticate",
            post(move || async move {
                rec(&l3, "KAVITA auth");
                Json(json!({"token": "jwt"}))
            }),
        )
        .route(
            "/kavita/api/Series/on-deck",
            post(|| async { Json(json!([{"id": 1, "name": "Manga Lambda", "pagesRead": 50, "pages": 200}])) }),
        )
        .route("/kavita/api/Series/recently-updated-series", post(|| async { Json(json!([])) }))
        // ---- Livres audio
        .route(
            "/abs/api/me/items-in-progress",
            get(|| async {
                Json(json!({"libraryItems": [{"id": "i1", "media": {"duration": 36000.0,
                    "metadata": {"title": "Audio Mu", "authorName": "Auteur Nu"}}}]}))
            }),
        )
        .route(
            "/abs/api/me",
            get(|| async {
                Json(json!({"mediaProgress": [{"libraryItemId": "i1", "progress": 0.25, "currentTime": 9000.0, "duration": 36000.0}]}))
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await });
    url
}

async fn setup() -> (String, Log) {
    let log: Log = Default::default();
    let base = fake(log.clone()).await;
    let mut m: HashMap<String, String> = HashMap::new();
    for (env, path) in [
        ("PLEX", "plex"),
        ("SONARR", "sonarr"),
        ("QBIT", "qbit"),
        ("LIDARR", "lidarr"),
        ("LAZYLIBRARIAN", "ll"),
        ("KAVITA", "kavita"),
        ("AUDIOBOOKSHELF", "abs"),
    ] {
        m.insert(format!("MEDIAS_{env}_URL"), format!("{base}/{path}"));
        m.insert(format!("MEDIAS_{env}_KEY"), "k".into());
        m.insert(format!("MEDIAS_{env}_USER"), "u".into());
        m.insert(format!("MEDIAS_{env}_PASS"), "p".into());
    }
    let stack = Arc::new(Stack::from_lookup(&move |k: &str| m.get(k).cloned()).unwrap());
    let store = Arc::new(Store::memory());
    let machine = Arc::new(OnceCell::new());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        MediaServer::new(stack.clone(), store.clone(), machine.clone())
    }));
    (url, log)
}

async fn call(url: &str, name: &str, args: Value) -> String {
    let body = json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}});
    let v: Value = reqwest::Client::new()
        .post(url)
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

#[tokio::test]
async fn abandoned_and_watch_stats() {
    let (url, _) = setup().await;
    let t = call(&url, "abandoned", json!({})).await;
    assert!(t.starts_with("Séries délaissées depuis 4 semaine(s) (1) :\n- Série Oubliée — 9 non vu(s)"), "{t}");
    assert!(t.contains("toujours surveillée") && t.contains("1 encore surveillée(s)"), "{t}");
    assert!(!t.contains("Récente") && !t.contains("Finie"), "{t}");

    let t = call(&url, "watch_stats", json!({"days": 7})).await;
    // 3 épisodes × 25 min + 1 film de 100 min.
    assert!(t.starts_with("Sur 7 jour(s) : 3 épisode(s) de 2 série(s), 1 film(s), environ 2 h 55."), "{t}");
    assert!(t.contains("Les plus regardées : Série A (2), Série B (1).") && t.contains("Films : Film C."), "{t}");
}

#[tokio::test]
async fn seeding_obligations_split_private_and_public() {
    let (url, _) = setup().await;
    let t = call(&url, "seeding_obligations", json!({})).await;
    assert!(
        t.contains(
            "Privés : 2 terminé(s) — 1 encore à partager (1,0 Go), fin dans 29 j à 29 j ; 1 limites atteintes (1,0 Go)."
        ),
        "{t}"
    );
    assert!(
        t.contains(
            "Publics : 2 terminé(s) — 0 encore à partager (0,0 Go) ; 1 limites atteintes (1,0 Go) ; 1 sans limite."
        ),
        "{t}"
    );
}

#[tokio::test]
async fn music_search_and_follow_artist() {
    let (url, log) = setup().await;
    let t = call(&url, "music_updates", json!({})).await;
    assert!(t.starts_with("Aucun artiste suivi."), "{t}");
    let t = call(&url, "search", json!({"query": "theta", "kind": "musique"})).await;
    assert_eq!(t, "Artistes :\n- Groupe Thêta (rock) — non suivi — media_type=artist id=abc-123");
    let t = call(&url, "request", json!({"media_type": "artist", "id": "abc-123"})).await;
    assert!(
        t.starts_with("Aperçu, rien n'est fait : suivre l'artiste Groupe Thêta (albums à venir seulement)."),
        "{t}"
    );
    assert!(log.lock().unwrap().iter().all(|l| !l.starts_with("POST artist")));
    let t = call(&url, "request", json!({"media_type": "artist", "id": "abc-123", "confirm": true})).await;
    assert!(t.starts_with("Fait : suivre l'artiste"), "{t}");
    let posts: Vec<_> = log.lock().unwrap().iter().filter(|l| l.starts_with("POST artist")).cloned().collect();
    assert_eq!(posts, [r#"POST artist "abc-123" "/data/music" 1 {"monitor":"future","searchForMissingAlbums":false}"#]);
}

#[tokio::test]
async fn books_search_request_and_reading() {
    let (url, log) = setup().await;
    let t = call(&url, "search", json!({"query": "kappa", "kind": "livre"})).await;
    assert_eq!(t, "Livres :\n- Livre Kappa — Autrice Iota (1999) — media_type=book (ou audiobook) id=OL1W");
    let t = call(&url, "request", json!({"media_type": "audiobook", "id": "OL1W"})).await;
    assert!(t.starts_with("Aperçu, rien n'est fait : demander le livre OL1W en version audio."), "{t}");
    call(&url, "request", json!({"media_type": "audiobook", "id": "OL1W", "confirm": true})).await;
    let cmds: Vec<_> = log
        .lock()
        .unwrap()
        .iter()
        .filter(|l| l.contains("id=OL1W"))
        .map(|l| l.trim_start_matches("LL ").split('&').find(|p| p.starts_with("cmd=")).unwrap_or("").to_string())
        .collect();
    assert_eq!(cmds, ["cmd=addBook", "cmd=queueBook", "cmd=searchBook"]);
    assert!(log.lock().unwrap().iter().any(|l| l.contains("cmd=queueBook") && l.contains("type=AudioBook")));

    let t = call(&url, "reading_updates", json!({})).await;
    assert!(t.contains("En cours :\n- Manga Lambda — 25 % (50/200 pages)"), "{t}");
    assert!(t.contains("Aucun livre demandé."), "{t}");
    assert_eq!(log.lock().unwrap().iter().filter(|l| *l == "KAVITA auth").count(), 1, "jeton réutilisé");

    let t = call(&url, "audiobooks", json!({})).await;
    assert_eq!(t, "Livres audio en cours :\n- Audio Mu — Auteur Nu — 25 %, reste 7 h 30");
}
