//! Outils JSON de l'application, face à de faux services.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    extract::{Path, RawQuery},
    http::{StatusCode, header::SET_COOKIE},
    response::IntoResponse,
    routing::{get, post},
};
use chrono::{Duration, TimeZone, Utc};
use kairos_medias::{config::Stack, store::Store, tools::MediaServer};
use serde_json::{Value, json};
use tokio::sync::OnceCell;

type W = Arc<Mutex<Vec<String>>>;

fn rec(w: &W, s: impl Into<String>) {
    w.lock().unwrap().push(s.into());
}

fn logged(w: &W, prefix: &str) -> Vec<String> {
    w.lock().unwrap().iter().filter(|l| l.starts_with(prefix)).cloned().collect()
}

/// Titre externe hostile : caractères de contrôle et longueur excessive.
fn nasty() -> String {
    format!("Film\u{7}Gamma\u{1b}[31m\n{}", "x".repeat(300))
}

/// Épisode diffusé à 23:30 UTC dans deux jours : le jour local peut différer.
fn air_utc() -> chrono::DateTime<Utc> {
    let d = (Utc::now() + Duration::days(2)).date_naive();
    Utc.from_utc_datetime(&d.and_hms_opt(23, 30, 0).unwrap())
}

fn release_day() -> chrono::NaiveDate {
    (Utc::now() + Duration::days(3)).date_naive()
}

async fn fake(w: W) -> String {
    let (w1, w2, w3, w4, w5, w6) = (w.clone(), w.clone(), w.clone(), w.clone(), w.clone(), w.clone());
    let now = Utc::now().timestamp();
    let app = Router::new()
        .without_v07_checks()
        // ---- Lecteur
        .route("/lecteur/identity", get(|| async { Json(json!({"MediaContainer": {"machineIdentifier": "m1"}})) }))
        .route(
            "/lecteur/library/sections",
            get(|| async {
                Json(json!({"MediaContainer": {"Directory": [{"key": "1", "type": "movie"}, {"key": "2", "type": "show"}]}}))
            }),
        )
        .route(
            "/lecteur/library/sections/{k}/all",
            get(move |Path(k): Path<String>| async move {
                let items = if k == "1" {
                    json!([{"ratingKey": "800", "type": "movie", "title": "Film Beta", "year": 2025}])
                } else {
                    json!([{"ratingKey": "600", "type": "show", "title": "Série Alpha",
                        "leafCount": 10, "viewedLeafCount": 2, "lastViewedAt": now}])
                };
                Json(json!({"MediaContainer": {"Metadata": items}}))
            }),
        )
        .route(
            "/lecteur/library/metadata/{k}/allLeaves",
            get(|| async {
                Json(json!({"MediaContainer": {"Metadata": [
                    {"ratingKey": "701", "type": "episode", "title": "Retour", "parentIndex": 1, "index": 3},
                ]}}))
            }),
        )
        .route(
            "/lecteur/library/metadata/{k}",
            get(|Path(k): Path<String>| async move {
                let (kind, title, sub, views) = match k.as_str() {
                    "701" => ("episode", "Retour", "fra", 0),
                    "800" => ("movie", "Film Beta", "eng", 0),
                    "700" => ("movie", "Film Delta", "fra", 0),
                    "710" => ("movie", "Film Vu", "fra", 1),
                    "503" => return StatusCode::SERVICE_UNAVAILABLE.into_response(),
                    _ => return StatusCode::NOT_FOUND.into_response(),
                };
                Json(json!({"MediaContainer": {"Metadata": [{"ratingKey": k, "type": kind, "title": title,
                    "parentIndex": 1, "index": 3, "duration": 2_520_000, "viewCount": views,
                    "Media": [{"Part": [{"Stream": [{"streamType": 2, "languageCode": "eng"},
                                                     {"streamType": 3, "languageCode": sub}]}]}]}]}}))
                .into_response()
            }),
        )
        .route(
            "/lecteur/library/onDeck",
            get(|| async {
                Json(json!({"MediaContainer": {"Metadata": [
                    {"ratingKey": "702", "type": "episode", "grandparentTitle": "Série Alpha", "parentIndex": 1,
                     "index": 2, "title": "Pilote", "viewOffset": 600_000, "duration": 2_400_000},
                    {"ratingKey": "703", "type": "movie", "title": nasty(), "duration": 6_000_000},
                ]}}))
            }),
        )
        .route(
            "/lecteur/:/scrobble",
            get(move |RawQuery(q): RawQuery| async move {
                rec(&w1, format!("SCROBBLE {}", q.unwrap_or_default()));
                StatusCode::OK
            }),
        )
        // ---- Gestionnaire de séries
        .route(
            "/series/api/v3/calendar",
            get(|| async {
                Json(json!([
                    {"seriesId": 1, "seasonNumber": 2, "episodeNumber": 5, "monitored": true, "hasFile": false,
                     "airDateUtc": air_utc().to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
                     "series": {"title": "Série Alpha"}},
                    {"seriesId": 1, "seasonNumber": 2, "episodeNumber": 6, "monitored": false, "hasFile": false,
                     "airDateUtc": air_utc().to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
                     "series": {"title": "Série Omega"}},
                ]))
            }),
        )
        .route(
            "/series/api/v3/queue",
            get(|| async {
                Json(json!({"totalRecords": 5, "records": [
                    {"id": 1, "status": "downloading", "trackedDownloadStatus": "ok", "protocol": "torrent",
                     "size": 100.0, "sizeleft": 50.0, "timeleft": "00:12:30",
                     "series": {"title": "Série Alpha"}, "episode": {"seasonNumber": 1, "episodeNumber": 4}},
                    {"id": 2, "status": "paused", "trackedDownloadStatus": "ok", "protocol": "usenet",
                     "size": 100.0, "sizeleft": 100.0, "series": {"title": "Série Alpha"},
                     "episode": {"seasonNumber": 1, "episodeNumber": 5}},
                    {"id": 3, "status": "queued", "trackedDownloadStatus": "ok", "protocol": "torrent",
                     "series": {"title": "Série Alpha"}, "episode": {"seasonNumber": 1, "episodeNumber": 6}},
                    {"id": 4, "status": "completed", "trackedDownloadStatus": "warning",
                     "trackedDownloadState": "importBlocked", "protocol": "torrent", "size": 10.0, "sizeleft": 0.0,
                     "statusMessages": [{"title": "t", "messages": [nasty()]}],
                     "series": {"title": "Série Alpha"}, "episode": {"seasonNumber": 1, "episodeNumber": 7}},
                    {"id": 5, "status": "failed", "trackedDownloadStatus": "error", "protocol": "usenet",
                     "errorMessage": "Archive incomplète", "series": {"title": "Série Alpha"},
                     "episode": {"seasonNumber": 1, "episodeNumber": 8}},
                ]}))
            }),
        )
        // ---- Gestionnaire de films
        .route(
            "/films/api/v3/calendar",
            get(|| async {
                let d = format!("{}T00:00:00Z", release_day());
                Json(json!([{"id": 3, "tmdbId": 55, "title": "Film Beta", "year": 2025, "monitored": true,
                    "hasFile": true, "digitalRelease": d}]))
            }),
        )
        .route("/films/api/v3/queue", get(|| async { Json(json!({"totalRecords": 0, "records": []})) }))
        // ---- Service de demandes
        .route(
            "/demandes/api/v1/search",
            get(|| async {
                Json(json!({"results": [
                    {"id": 10, "mediaType": "tv", "name": "Série Alpha", "firstAirDate": "2024-03-01",
                     "mediaInfo": {"status": 5}},
                    {"id": 2, "mediaType": "movie", "title": "Film Beta", "releaseDate": "2025-06-01"},
                    {"id": 4, "mediaType": "movie", "title": "Film Kappa", "releaseDate": "",
                     "mediaInfo": {"status": 3}},
                    {"id": 9, "mediaType": "person", "name": "Quelqu'un"},
                ]}))
            }),
        )
        .route(
            "/demandes/api/v1/movie/{id}",
            get(|Path(id): Path<i64>| async move {
                let info = if id == 1 { json!({"status": 5}) } else { Value::Null };
                Json(json!({"id": id, "title": "Film Beta", "releaseDate": "2025-06-01", "mediaInfo": info}))
            }),
        )
        .route(
            "/demandes/api/v1/request",
            post(move |Json(b): Json<Value>| async move {
                rec(&w2, format!("POST request {b}"));
                Json(json!({"id": 900, "status": 2}))
            }),
        )
        // ---- Clients de téléchargement
        .route(
            "/torrent/api/v2/auth/login",
            post(|| async { (StatusCode::OK, [(SET_COOKIE, "SID=s; path=/")]).into_response() }),
        )
        .route(
            "/torrent/api/v2/torrents/info",
            get(|| async { Json(json!([{"name": "a", "state": "stoppedDL", "progress": 0.3}])) }),
        )
        .route(
            "/torrent/api/v2/torrents/stop",
            post(move || async move {
                rec(&w3, "TORRENT stop");
                StatusCode::OK
            }),
        )
        .route(
            "/torrent/api/v2/torrents/start",
            post(move || async move {
                rec(&w4, "TORRENT start");
                StatusCode::OK
            }),
        )
        .route(
            "/usenet/jsonrpc/status",
            get(|| async { Json(json!({"result": {"DownloadPaused": true, "RemainingSizeMB": 0}})) }),
        )
        .route(
            "/usenet/jsonrpc/pausedownload",
            get(move || async move {
                rec(&w5, "USENET pause");
                Json(json!({"result": true}))
            }),
        )
        .route(
            "/usenet/jsonrpc/resumedownload",
            get(move || async move {
                rec(&w6, "USENET resume");
                Json(json!({"result": true}))
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await });
    url
}

/// (variable de configuration, chemin du faux service).
const SERVICES: [(&str, &str); 6] = [
    ("PLEX", "lecteur"),
    ("SONARR", "series"),
    ("RADARR", "films"),
    ("SEERR", "demandes"),
    ("QBIT", "torrent"),
    ("NZBGET", "usenet"),
];

struct Env {
    url: String,
    w: W,
    store: Arc<Store>,
}

/// `tweak` ajuste la configuration (service absent, adresse en panne…).
async fn setup_with(tweak: impl FnOnce(&mut HashMap<String, String>, &str)) -> Env {
    let w: W = Arc::new(Mutex::new(Vec::new()));
    let base = fake(w.clone()).await;
    let mut m: HashMap<String, String> = HashMap::new();
    for (var, path) in SERVICES {
        m.insert(format!("MEDIAS_{var}_URL"), format!("{base}/{path}"));
        m.insert(format!("MEDIAS_{var}_KEY"), "k".into());
        m.insert(format!("MEDIAS_{var}_USER"), "u".into());
        m.insert(format!("MEDIAS_{var}_PASS"), "p".into());
    }
    m.insert("MEDIAS_TZ".into(), "Europe/Paris".into());
    tweak(&mut m, &base);
    let get = move |k: &str| m.get(k).cloned();
    let stack = Arc::new(Stack::from_lookup(&get).unwrap());
    let store = Arc::new(Store::memory());
    let (s2, st2) = (stack.clone(), store.clone());
    let machine = Arc::new(OnceCell::new());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        MediaServer::new(s2.clone(), st2.clone(), machine.clone())
    }));
    Env { url, w, store }
}

async fn setup() -> Env {
    setup_with(|_, _| {}).await
}

async fn raw(url: &str, name: &str, args: Value) -> Value {
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

/// Résultat JSON d'un outil ; échoue si l'outil renvoie une erreur.
async fn call(url: &str, name: &str, args: Value) -> Value {
    let v = raw(url, name, args).await;
    assert_ne!(v["result"]["isError"], json!(true), "{v}");
    let t = v["result"]["content"][0]["text"].as_str().unwrap_or_default();
    serde_json::from_str(t).unwrap_or_else(|_| panic!("JSON attendu : {t}"))
}

fn section_error<'a>(v: &'a Value, section: &str) -> Option<&'a str> {
    v["errors"].as_array()?.iter().find(|e| e["section"] == section)?["message"].as_str()
}

#[tokio::test]
async fn app_medias_json_shape() {
    let e = setup().await;
    e.store.add("movie", 2, None, "Film Beta", &[], None, Utc::now().timestamp()).unwrap();
    let v = call(&e.url, "app_medias", json!({})).await;
    assert_eq!(v["errors"], json!([]), "{v}");

    let c = &v["continue"][0];
    assert_eq!(c["key"], "702");
    assert_eq!(c["title"], "Série Alpha S01E02 « Pilote »");
    assert_eq!(c["left_min"], 30);

    let ready = v["ready"].as_array().unwrap();
    let ep = ready.iter().find(|r| r["kind"] == "episode").unwrap();
    assert_eq!(
        (ep["key"].as_str(), ep["subs"].as_str(), ep["minutes"].as_i64()),
        (Some("701"), Some("st_fr"), Some(42))
    );
    assert!(ep["title"].as_str().unwrap().starts_with("Série Alpha S01E03"), "{ep}");
    let film = ready.iter().find(|r| r["kind"] == "film").unwrap();
    assert_eq!((film["key"].as_str(), film["subs"].as_str()), (Some("800"), Some("absents")));

    assert!(v["downloads"]["items"].is_array());
    assert!(v["downloads"]["paused"].is_boolean());
    assert!(v["upcoming"].as_array().is_some_and(|u| !u.is_empty()));
    assert_eq!(v["requests"], json!([{"title": "Film Beta", "state": "en attente"}]));
}

#[tokio::test]
async fn section_errors_isolated() {
    // Lecteur en panne : ses deux sections tombent, les autres restent.
    let e = setup_with(|m, base| {
        m.insert("MEDIAS_PLEX_URL".into(), format!("{base}/panne"));
    })
    .await;
    let v = call(&e.url, "app_medias", json!({})).await;
    assert!(v["continue"].is_null() && v["ready"].is_null(), "{v}");
    assert_eq!(section_error(&v, "continue"), Some("lecteur injoignable"));
    assert_eq!(section_error(&v, "ready"), Some("lecteur injoignable"));
    assert!(v["downloads"].is_object() && v["upcoming"].is_array() && v["requests"].is_array(), "{v}");

    // Gestionnaires absents : à venir sans source, la raison est donnée.
    let e = setup_with(|m, _| {
        m.remove("MEDIAS_SONARR_URL");
        m.remove("MEDIAS_RADARR_URL");
    })
    .await;
    let v = call(&e.url, "app_medias", json!({})).await;
    assert!(v["upcoming"].is_null() && v["downloads"].is_null(), "{v}");
    assert!(section_error(&v, "upcoming").is_some_and(|m| m.contains("non configuré")), "{v}");
    assert!(v["continue"].is_array() && v["ready"].is_array(), "{v}");
}

#[tokio::test]
async fn external_text_cleaned() {
    let e = setup().await;
    let v = call(&e.url, "app_medias", json!({})).await;
    let ok = |s: &str| !s.chars().any(char::is_control) && s.chars().count() <= 200;
    let film = v["continue"][1]["title"].as_str().unwrap();
    assert!(ok(film) && film.starts_with("FilmGamma[31m"), "{film}");
    let why = v["downloads"]["items"]
        .as_array()
        .unwrap()
        .iter()
        .find_map(|i| i["why"].as_str().filter(|w| w.starts_with("Film")))
        .unwrap();
    assert!(ok(why), "{why}");
}

#[tokio::test]
async fn downloads_states_mapped() {
    let e = setup().await;
    let v = call(&e.url, "app_medias", json!({})).await;
    let d = &v["downloads"];
    let items = d["items"].as_array().unwrap();
    let by = |ep: &str| items.iter().find(|i| i["title"].as_str().unwrap().ends_with(ep)).unwrap().clone();
    let a = by("S01E04");
    assert_eq!(
        (a["state"].as_str(), a["progress"].as_f64(), a["eta_min"].as_i64()),
        (Some("en_cours"), Some(0.5), Some(12))
    );
    assert_eq!(by("S01E05")["state"], "pause");
    assert_eq!(by("S01E06")["state"], "attente");
    assert_eq!(by("S01E06")["progress"], 0.0);
    assert_eq!(by("S01E07")["state"], "bloque");
    let f = by("S01E08");
    assert_eq!((f["state"].as_str(), f["why"].as_str()), (Some("erreur"), Some("Archive incomplète")));
    assert!(a.get("why").is_none());
    // Client torrent arrêté et client usenet en pause : en pause.
    assert_eq!(d["paused"], true);
}

#[tokio::test]
async fn upcoming_local_days() {
    let e = setup().await;
    let v = call(&e.url, "app_medias", json!({})).await;
    let up = v["upcoming"].as_array().unwrap();
    let local = air_utc().with_timezone(&chrono_tz::Europe::Paris);
    let ep = up.iter().find(|u| u["title"] == "Série Alpha").unwrap();
    assert_eq!(ep["day"], local.format("%Y-%m-%d").to_string());
    assert_eq!(ep["time"], local.format("%H:%M").to_string());
    assert_eq!((ep["what"].as_str(), ep["have"].as_bool()), (Some("S02E05"), Some(false)));
    // Épisode non suivi : absent.
    assert!(!up.iter().any(|u| u["title"] == "Série Omega"));
    let film = up.iter().find(|u| u["title"] == "Film Beta").unwrap();
    assert_eq!(film["day"], release_day().format("%Y-%m-%d").to_string());
    assert!(film.get("time").is_none());
    assert_eq!(film["have"], true);
    // Trié par jour.
    let days: Vec<_> = up.iter().map(|u| u["day"].as_str().unwrap().to_string()).collect();
    assert!(days.windows(2).all(|w| w[0] <= w[1]), "{days:?}");
}

#[tokio::test]
async fn watched_by_key() {
    let e = setup().await;
    let v = call(&e.url, "app_media_watched", json!({"key": "700"})).await;
    assert!(v["message"].as_str().unwrap().contains("Film Delta"), "{v}");
    assert_eq!(v["outcome"], "done");
    let l = logged(&e.w, "SCROBBLE");
    assert!(l.len() == 1 && l[0].starts_with("SCROBBLE key=700&"), "{l:?}");
}

#[tokio::test]
async fn watched_unknown_key_is_clear() {
    let e = setup().await;
    for key in ["999", "../x", ""] {
        let v = call(&e.url, "app_media_watched", json!({"key": key})).await;
        assert!(v["message"].as_str().unwrap().contains("introuvable"), "{key}: {v}");
        assert_eq!(v["outcome"], "gone", "{key}: {v}");
    }
    let v = call(&e.url, "app_media_watched", json!({"key": "710"})).await;
    assert!(v["message"].as_str().unwrap().contains("déjà"), "{v}");
    assert_eq!(v["outcome"], "already");
    assert!(logged(&e.w, "SCROBBLE").is_empty());
}

#[tokio::test]
async fn watched_player_down_is_error() {
    // Lecteur en échec : l'élément n'est pas déclaré disparu, l'application le remet en place.
    let e = setup().await;
    let v = raw(&e.url, "app_media_watched", json!({"key": "503"})).await;
    assert_eq!(v["result"]["isError"], true, "{v}");
    let e = setup_with(|m, _| {
        m.insert("MEDIAS_PLEX_URL".into(), "http://127.0.0.1:9".into());
    })
    .await;
    let v = raw(&e.url, "app_media_watched", json!({"key": "700"})).await;
    assert_eq!(v["result"]["isError"], true, "{v}");
}

#[tokio::test]
async fn controls_pause_resume() {
    let e = setup().await;
    let v = call(&e.url, "app_downloads_control", json!({"action": "pause"})).await;
    assert!(v["message"].is_string(), "{v}");
    assert_eq!((logged(&e.w, "TORRENT stop").len(), logged(&e.w, "USENET pause").len()), (1, 1));
    call(&e.url, "app_downloads_control", json!({"action": "reprise"})).await;
    assert_eq!((logged(&e.w, "TORRENT start").len(), logged(&e.w, "USENET resume").len()), (1, 1));
    assert_eq!(raw(&e.url, "app_downloads_control", json!({"action": "lent"})).await["result"]["isError"], true);
}

#[tokio::test]
async fn search_shape() {
    let e = setup().await;
    e.store.add("movie", 2, None, "Film Beta", &[], None, Utc::now().timestamp()).unwrap();
    let v = call(&e.url, "app_media_search", json!({"query": "alpha"})).await;
    assert_eq!(
        v,
        json!([
            {"id": 10, "media_type": "tv", "title": "Série Alpha", "year": 2024, "have": true, "requested": false},
            {"id": 2, "media_type": "movie", "title": "Film Beta", "year": 2025, "have": false, "requested": true},
            {"id": 4, "media_type": "movie", "title": "Film Kappa", "have": false, "requested": true},
        ])
    );
    assert_eq!(call(&e.url, "app_media_search", json!({"query": "  "})).await, json!([]));
}

#[tokio::test]
async fn request_idempotent() {
    let e = setup().await;
    let v = call(&e.url, "app_media_request", json!({"id": 2, "media_type": "movie"})).await;
    assert!(v["message"].as_str().unwrap().contains("Film Beta"), "{v}");
    assert_eq!(logged(&e.w, "POST request").len(), 1);
    assert_eq!(e.store.list(true).unwrap().len(), 1);

    let v = call(&e.url, "app_media_request", json!({"id": 2, "media_type": "movie"})).await;
    assert!(v["message"].as_str().unwrap().contains("déjà demandé"), "{v}");
    let v = call(&e.url, "app_media_request", json!({"id": 1, "media_type": "movie"})).await;
    assert!(v["message"].as_str().unwrap().contains("déjà disponible"), "{v}");
    assert_eq!(logged(&e.w, "POST request").len(), 1);
    assert_eq!(e.store.list(true).unwrap().len(), 1);
}
