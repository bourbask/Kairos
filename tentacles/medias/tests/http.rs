//! Tentacule médias servi en HTTP, face à de faux services (titres fictifs).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    http::{HeaderMap, StatusCode, header::SET_COOKIE},
    response::IntoResponse,
    routing::{get, post},
};
use kairos_medias::{config::Stack, tools::MediaServer};
use serde_json::{Value, json};
use tokio::sync::OnceCell;

type Log = Arc<Mutex<Vec<String>>>;

fn rec(log: &Log, s: &str) {
    log.lock().unwrap().push(s.to_string());
}

fn count(log: &Log, s: &str) -> usize {
    log.lock().unwrap().iter().filter(|l| l.as_str() == s).count()
}

fn queue_item(series: &str, s: i64, e: i64, status: &str, err: Option<&str>, protocol: &str) -> Value {
    json!({
        "title": format!("{series}.S{s:02}E{e:02}.1080p"),
        "status": status,
        "trackedDownloadStatus": if err.is_some() { "warning" } else { "ok" },
        "trackedDownloadState": if err.is_some() { "importBlocked" } else { "downloading" },
        "timeleft": "00:10:00",
        "size": 1000.0,
        "sizeleft": 250.0,
        "protocol": protocol,
        "errorMessage": err,
        "statusMessages": [],
        "series": {"title": series},
        "episode": {"seasonNumber": s, "episodeNumber": e},
    })
}

async fn fake(log: Log) -> String {
    let (l1, l2, l3, l4, l5) = (log.clone(), log.clone(), log.clone(), log.clone(), log.clone());
    let app = Router::new()
        .route(
            "/sonarr/api/v3/queue",
            get(|| async {
                Json(json!({"totalRecords": 4, "records": [
                    queue_item("Série Alpha", 2, 1, "completed", Some("Fichier unique pour toute la saison"), "torrent"),
                    queue_item("Série Alpha", 2, 2, "completed", Some("Fichier unique pour toute la saison"), "torrent"),
                    queue_item("Série Alpha", 2, 3, "completed", Some("Fichier unique pour toute la saison"), "torrent"),
                    queue_item("Série Beta", 1, 4, "paused", None, "usenet"),
                ]}))
            }),
        )
        .route(
            "/sonarr/api/v3/series",
            get(|| async {
                Json(json!([
                    {"id": 1, "title": "Série Alpha", "status": "continuing", "monitored": true, "tvdbId": 11,
                     "statistics": {"episodeFileCount": 10, "episodeCount": 12}},
                    {"id": 2, "title": "Série Beta", "status": "ended", "monitored": true, "tvdbId": 22,
                     "statistics": {"episodeFileCount": 3, "episodeCount": 4}},
                ]))
            }),
        )
        .route("/radarr/api/v3/queue", get(|| async { Json(json!({"totalRecords": 0, "records": []})) }))
        .route("/radarr/api/v3/movie", get(|| async { Json(json!([])) }))
        .route(
            "/bazarr/api/episodes/wanted",
            get(|| async {
                Json(json!({"total": 2, "data": [
                    {"seriesTitle": "Série Alpha", "episode_number": "2x1", "sonarrSeriesId": 1},
                    {"seriesTitle": "Série Alpha", "episode_number": "2x2", "sonarrSeriesId": 1},
                ]}))
            }),
        )
        .route(
            "/seerr/api/v1/search",
            get(move || async move {
                rec(&l1, "GET seerr search");
                Json(json!({"results": [
                    {"id": 77, "mediaType": "tv", "name": "Série Gamma", "firstAirDate": "2024-04-01"},
                    {"id": 5, "mediaType": "person", "name": "Quelqu'un"},
                ]}))
            }),
        )
        .route(
            "/nzbget/jsonrpc/status",
            get(|| async { Json(json!({"result": {"DownloadPaused": true, "RemainingSizeMB": 2048}})) }),
        )
        .route(
            "/qbit/api/v2/auth/login",
            post(move || async move {
                rec(&l2, "POST qbit login");
                (StatusCode::NO_CONTENT, [(SET_COOKIE, "QBT_SID_1=abc; HttpOnly; path=/")]).into_response()
            }),
        )
        .route(
            "/qbit/api/v2/torrents/info",
            get(move |h: HeaderMap| async move {
                rec(&l3, "GET qbit torrents");
                if h.get("cookie").and_then(|c| c.to_str().ok()) != Some("QBT_SID_1=abc") {
                    return StatusCode::FORBIDDEN.into_response();
                }
                Json(json!([
                    {"name": "a", "state": "downloading", "progress": 0.5, "dlspeed": 1048576},
                    {"name": "b", "state": "stalledDL", "progress": 0.1},
                    {"name": "c", "state": "uploading", "progress": 1.0},
                ]))
                .into_response()
            }),
        )
        .route(
            "/qbit/api/v2/transfer/info",
            get(move |h: HeaderMap| async move {
                rec(&l4, "GET qbit transfer");
                if h.get("cookie").is_none() {
                    return StatusCode::FORBIDDEN.into_response();
                }
                Json(json!({"connection_status": "disconnected", "dl_info_speed": 1048576})).into_response()
            }),
        )
        .route(
            "/prowlarr/api/v1/indexer",
            get(|| async {
                Json(json!([
                    {"id": 1, "name": "Indexeur Actif", "enable": true},
                    {"id": 2, "name": "Indexeur Coupé", "enable": false},
                    {"id": 3, "name": "Indexeur Sain", "enable": true},
                ]))
            }),
        )
        .route(
            "/prowlarr/api/v1/indexerstatus",
            get(|| async {
                Json(json!([
                    {"indexerId": 1, "disabledTill": "2999-01-01T00:00:00Z"},
                    {"indexerId": 2, "disabledTill": "2999-01-01T00:00:00Z"},
                ]))
            }),
        )
        .route(
            "/radarr/api/v3/calendar",
            get(move || async move {
                rec(&l5, "GET radarr calendar");
                Json(json!([]))
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await });
    url
}

fn stack(base: &str, services: &[&str]) -> Stack {
    let mut m: HashMap<String, String> = HashMap::new();
    for s in services {
        let up = s.to_uppercase();
        m.insert(format!("MEDIAS_{up}_URL"), format!("{base}/{s}"));
        m.insert(format!("MEDIAS_{up}_KEY"), "k".into());
        m.insert(format!("MEDIAS_{up}_USER"), "u".into());
        m.insert(format!("MEDIAS_{up}_PASS"), "p".into());
    }
    let get = move |k: &str| m.get(k).cloned();
    Stack::from_lookup(&get).unwrap()
}

async fn start(stack: Stack) -> String {
    let stack = Arc::new(stack);
    let machine = Arc::new(OnceCell::new());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        MediaServer::new(stack.clone(), machine.clone())
    }));
    url
}

async fn rpc(url: &str, body: Value) -> Value {
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

async fn call(url: &str, name: &str, args: Value) -> Value {
    rpc(url, json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}})).await
}

fn text(v: &Value) -> &str {
    v["result"]["content"][0]["text"].as_str().unwrap()
}

fn is_err(v: &Value) -> bool {
    v["result"]["isError"] == json!(true)
}

const ALL: [&str; 6] = ["sonarr", "radarr", "bazarr", "seerr", "nzbget", "qbit"];

#[tokio::test]
async fn lists_read_only_tools() {
    let url = start(stack("http://unused.invalid", &[])).await;
    let v = rpc(&url, json!({"jsonrpc":"2.0","id":1,"method":"tools/list"})).await;
    let mut names: Vec<_> =
        v["result"]["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap().to_string()).collect();
    names.sort();
    assert_eq!(
        names,
        [
            "brief_section",
            "continue_watching",
            "downloads",
            "library_health",
            "media_status",
            "ready_to_watch",
            "stack_health",
            "upcoming"
        ]
    );
}

#[tokio::test]
async fn downloads_summarises_clients_and_groups_failures() {
    let log: Log = Default::default();
    let base = fake(log.clone()).await;
    let url = start(stack(&base, &ALL)).await;
    let v = call(&url, "downloads", json!({})).await;
    let t = text(&v);
    assert!(t.contains("File : 4 élément(s)."), "{t}");
    assert!(t.contains("Torrents : 1 en cours, 1 bloqués"), "{t}");
    assert!(t.contains("déconnecté (tunnel VPN ?)"), "{t}");
    assert!(t.contains("Usenet : ⚠ en pause — 2,0 Go restants."), "{t}");
    assert!(t.contains("- Série Alpha S02E01 → S02E03 (3) : Fichier unique pour toute la saison"), "{t}");
    assert!(t.contains("1 en pause"), "{t}");
    // Une seule connexion qBittorrent pour les deux appels.
    assert_eq!(count(&log, "POST qbit login"), 1);
    assert_eq!(count(&log, "GET qbit torrents"), 1);
}

#[tokio::test]
async fn media_status_explains_a_followed_series() {
    let log: Log = Default::default();
    let base = fake(log.clone()).await;
    let url = start(stack(&base, &ALL)).await;
    let t = text(&call(&url, "media_status", json!({"title": "serie beta"})).await).to_string();
    assert!(t.contains("Série « Série Beta » — suivie, terminée — 3/4 épisodes présents."), "{t}");
    assert!(t.contains("Série Beta S01E04 : en pause (usenet)"), "{t}");
    assert!(t.contains("client usenet est en pause"), "{t}");
    let t = text(&call(&url, "media_status", json!({"title": "Alpha"})).await).to_string();
    assert!(t.contains("Sous-titres FR recherchés pour 2 épisode(s)."), "{t}");
    assert_eq!(count(&log, "GET seerr search"), 0);
}

#[tokio::test]
async fn media_status_falls_back_to_search_when_not_followed() {
    let log: Log = Default::default();
    let base = fake(log.clone()).await;
    let url = start(stack(&base, &ALL)).await;
    let t = text(&call(&url, "media_status", json!({"title": "gamma"})).await).to_string();
    assert!(t.contains("« gamma » n'est pas suivi."), "{t}");
    assert!(t.contains("- Série Gamma (2024) — série — non demandé (id 77)"), "{t}");
    assert!(!t.contains("Quelqu'un"), "{t}");
    assert!(is_err(&call(&url, "media_status", json!({"title": " x "})).await));
}

#[tokio::test]
async fn missing_services_degrade_instead_of_failing() {
    let url = start(stack("http://unused.invalid", &[])).await;
    let t = text(&call(&url, "downloads", json!({})).await).to_string();
    assert_eq!(t, "File : 0 élément(s).");
    assert!(is_err(&call(&url, "upcoming", json!({})).await));
    let t = text(&call(&url, "stack_health", json!({})).await).to_string();
    assert!(t.starts_with("Services joignables : aucun."), "{t}");
}

#[tokio::test]
async fn unreachable_service_is_reported() {
    // Port fermé : Sonarr déclaré mais injoignable.
    let url = start(stack("http://127.0.0.1:9", &["sonarr"])).await;
    let t = text(&call(&url, "downloads", json!({})).await).to_string();
    assert!(t.contains("Sonarr injoignable"), "{t}");
}

#[tokio::test]
async fn disabled_indexers_are_not_reported_as_failing() {
    let log: Log = Default::default();
    let base = fake(log.clone()).await;
    let url = start(stack(&base, &["prowlarr"])).await;
    let t = text(&call(&url, "stack_health", json!({})).await).to_string();
    assert!(t.contains("Indexeurs : 2 actif(s), en échec : Indexeur Actif."), "{t}");
    assert!(!t.contains("Coupé"), "{t}");
}
