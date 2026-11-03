//! Actions (deux temps : aperçu puis confirm) et suivi des demandes, face à de faux services.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use axum::{
    Json, Router,
    extract::{Path, RawQuery},
    http::{StatusCode, header::SET_COOKIE},
    response::IntoResponse,
    routing::{get, post},
};
use chrono::{TimeZone, Utc};
use kairos_medias::{
    config::Stack,
    store::{State, Store},
    tools::MediaServer,
    tracker,
};
use serde_json::{Value, json};
use tokio::sync::OnceCell;

/// État simulé, modifiable par les tests.
#[derive(Default)]
struct World {
    log: Vec<String>,
    /// Statut renvoyé pour la demande Seerr n° 900.
    request_status: i64,
    movie_has_file: bool,
    /// Sous-titres du film dans le lecteur.
    movie_subs: &'static str,
    free_gb: i64,
}

type W = Arc<Mutex<World>>;

fn rec(w: &W, s: impl Into<String>) {
    w.lock().unwrap().log.push(s.into());
}

fn logged(w: &W, prefix: &str) -> Vec<String> {
    w.lock().unwrap().log.iter().filter(|l| l.starts_with(prefix)).cloned().collect()
}

const GB: i64 = 1_073_741_824;

async fn fake(w: W) -> String {
    let w1 = w.clone();
    let w2 = w.clone();
    let w3 = w.clone();
    let w4 = w.clone();
    let w5 = w.clone();
    let w6 = w.clone();
    let w7 = w.clone();
    let w8 = w.clone();
    let w9 = w.clone();
    let w10 = w.clone();
    let w11 = w.clone();
    let w12 = w.clone();
    let w13 = w.clone();
    let w14 = w.clone();
    // Le lecteur a des chemins littéraux en « /: » (scrobble).
    let app = Router::new()
        .without_v07_checks()
        // ---- Seerr
        .route(
            "/seerr/api/v1/tv/{id}",
            get(|Path(id): Path<i64>| async move {
                Json(json!({"id": id, "name": "Série Delta", "firstAirDate": "2025-01-10",
                    "seasons": [{"seasonNumber": 0, "episodeCount": 2}, {"seasonNumber": 1, "episodeCount": 12},
                                {"seasonNumber": 2, "episodeCount": 10}],
                    "externalIds": {"tvdbId": 4242}}))
            }),
        )
        .route(
            "/seerr/api/v1/movie/{id}",
            get(|Path(id): Path<i64>| async move {
                let status = if id == 1 { 5 } else { 1 };
                Json(json!({"id": id, "title": "Film Epsilon", "releaseDate": "2026-05-01",
                    "mediaInfo": {"status": status}}))
            }),
        )
        .route(
            "/seerr/api/v1/request",
            post(move |Json(b): Json<Value>| async move {
                rec(&w1, format!("POST seerr request {b}"));
                Json(json!({"id": 900, "status": 2}))
            }),
        )
        .route(
            "/seerr/api/v1/request/{id}",
            get(move |Path(id): Path<i64>| async move {
                let st = w2.lock().unwrap().request_status;
                Json(json!({"id": id, "status": st}))
            }),
        )
        // ---- Sonarr
        .route(
            "/sonarr/api/v3/series",
            get(|| async {
                Json(json!([{"id": 7, "title": "Série Delta", "status": "continuing", "monitored": true,
                    "tvdbId": 4242, "statistics": {"episodeFileCount": 0, "episodeCount": 22}}]))
            }),
        )
        .route(
            "/sonarr/api/v3/series/{id}",
            get(|Path(id): Path<i64>| async move { Json(json!({"id": id, "title": "Série Delta", "monitored": true, "extra": 1})) })
                .put(move |Path(id): Path<i64>, Json(b): Json<Value>| async move {
                    rec(&w3, format!("PUT sonarr series {id} monitored={} extra={}", b["monitored"], b["extra"]));
                    StatusCode::ACCEPTED
                }),
        )
        .route(
            "/sonarr/api/v3/command",
            post(move |Json(b): Json<Value>| async move {
                rec(&w4, format!("POST sonarr command {b}"));
                StatusCode::CREATED
            }),
        )
        .route(
            "/sonarr/api/v3/queue",
            get(|| async {
                Json(json!({"totalRecords": 2, "records": [
                    {"id": 31, "title": "Delta.S01E01", "status": "completed", "trackedDownloadStatus": "warning",
                     "protocol": "torrent", "series": {"title": "Série Delta"},
                     "episode": {"seasonNumber": 1, "episodeNumber": 1}, "errorMessage": "Fichier illisible"},
                    {"id": 32, "title": "Delta.S01E02", "status": "downloading", "trackedDownloadStatus": "ok",
                     "protocol": "torrent", "series": {"title": "Série Delta"},
                     "episode": {"seasonNumber": 1, "episodeNumber": 2}},
                ]}))
            }),
        )
        .route(
            "/sonarr/api/v3/queue/{id}",
            axum::routing::delete(move |Path(id): Path<i64>, RawQuery(q): RawQuery| async move {
                rec(&w5, format!("DELETE sonarr queue {id} {}", q.unwrap_or_default()));
                StatusCode::OK
            }),
        )
        .route(
            "/sonarr/api/v3/episode",
            get(|| async {
                Json(json!([
                    {"seasonNumber": 0, "episodeNumber": 1, "airDateUtc": "2024-01-01T00:00:00Z", "hasFile": false},
                    {"seasonNumber": 2, "episodeNumber": 1, "airDateUtc": "2026-10-01T00:00:00Z", "hasFile": false},
                    {"seasonNumber": 1, "episodeNumber": 1, "airDateUtc": "2026-09-01T00:00:00Z", "hasFile": false},
                ]))
            }),
        )
        .route(
            "/sonarr/api/v3/rootfolder",
            get(move || async move {
                let gb = w6.lock().unwrap().free_gb;
                Json(json!([{"path": "/data/tv", "freeSpace": gb * GB}]))
            }),
        )
        // ---- Radarr
        .route(
            "/radarr/api/v3/movie",
            get(move || async move {
                let has = w7.lock().unwrap().movie_has_file;
                Json(json!([{"id": 3, "tmdbId": 55, "title": "Film Epsilon", "year": 2026, "monitored": true,
                    "hasFile": has, "digitalRelease": "2026-10-01T00:00:00Z"}]))
            }),
        )
        .route("/radarr/api/v3/rootfolder", get(|| async { Json(json!([])) }))
        // ---- Bazarr
        .route(
            "/bazarr/api/series",
            axum::routing::patch(move |RawQuery(q): RawQuery| async move {
                rec(&w8, format!("PATCH bazarr series {}", q.unwrap_or_default()));
                StatusCode::NO_CONTENT
            }),
        )
        // ---- Plex
        .route("/plex/identity", get(|| async { Json(json!({"MediaContainer": {"machineIdentifier": "m1"}})) }))
        .route(
            "/plex/library/sections",
            get(|| async {
                Json(json!({"MediaContainer": {"Directory": [{"key": "1", "type": "movie"}, {"key": "2", "type": "show"}]}}))
            }),
        )
        .route(
            "/plex/library/sections/{k}/all",
            get(|Path(k): Path<String>| async move {
                let items = if k == "1" {
                    json!([{"ratingKey": "500", "type": "movie", "title": "Film Epsilon", "Guid": [{"id": "tmdb://55"}]}])
                } else {
                    json!([{"ratingKey": "600", "type": "show", "title": "Série Delta (VF)", "Guid": [{"id": "tvdb://4242"}]}])
                };
                Json(json!({"MediaContainer": {"Metadata": items}}))
            }),
        )
        .route(
            "/plex/library/metadata/{k}",
            get(move |Path(k): Path<String>| async move {
                let lang = w9.lock().unwrap().movie_subs;
                Json(json!({"MediaContainer": {"Metadata": [{"ratingKey": k, "type": "movie", "title": "Film Epsilon",
                    "Media": [{"Part": [{"Stream": [{"streamType": 2, "languageCode": "eng"},
                                                     {"streamType": 3, "languageCode": lang}]}]}]}]}}))
            }),
        )
        .route(
            "/plex/library/metadata/{k}/allLeaves",
            get(|| async {
                Json(json!({"MediaContainer": {"Metadata": [
                    {"ratingKey": "601", "parentIndex": 1, "index": 1},
                    {"ratingKey": "602", "parentIndex": 1, "index": 2},
                    {"ratingKey": "603", "parentIndex": 2, "index": 1},
                ]}}))
            }),
        )
        .route(
            "/plex/:/scrobble",
            get(move |RawQuery(q): RawQuery| async move {
                rec(&w10, format!("SCROBBLE {}", q.unwrap_or_default()));
                StatusCode::OK
            }),
        )
        // ---- qBittorrent / NZBGet
        .route(
            "/qbit/api/v2/auth/login",
            post(|| async { (StatusCode::OK, [(SET_COOKIE, "SID=s; path=/")]).into_response() }),
        )
        .route(
            "/qbit/api/v2/torrents/stop",
            post(move || async move {
                rec(&w11, "POST qbit stop");
                StatusCode::OK
            }),
        )
        .route("/qbit/api/v2/transfer/speedLimitsMode", get(|| async { Json(json!(0)) }))
        .route(
            "/qbit/api/v2/transfer/toggleSpeedLimitsMode",
            post(move || async move {
                rec(&w12, "POST qbit toggle");
                StatusCode::OK
            }),
        )
        .route(
            "/nzbget/jsonrpc/pausedownload",
            get(move || async move {
                rec(&w13, "GET nzbget pause");
                Json(json!({"result": true}))
            }),
        )
        // ---- ntfy
        .route(
            "/ntfy/medias",
            post(move |RawQuery(q): RawQuery, body: String| async move {
                rec(&w14, format!("NTFY {} {body}", q.unwrap_or_default()));
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
    for s in ["sonarr", "radarr", "bazarr", "seerr", "plex", "qbit", "nzbget"] {
        let up = s.to_uppercase();
        m.insert(format!("MEDIAS_{up}_URL"), format!("{base}/{s}"));
        m.insert(format!("MEDIAS_{up}_KEY"), "k".into());
        m.insert(format!("MEDIAS_{up}_USER"), "u".into());
        m.insert(format!("MEDIAS_{up}_PASS"), "p".into());
    }
    m.insert("MEDIAS_NTFY_URL".into(), format!("{base}/ntfy"));
    m.insert("MEDIAS_NTFY_TOPIC".into(), "medias".into());
    let get = move |k: &str| m.get(k).cloned();
    Stack::from_lookup(&get).unwrap()
}

struct Env {
    url: String,
    w: W,
    stack: Arc<Stack>,
    store: Arc<Store>,
}

async fn setup() -> Env {
    let w: W = Arc::new(Mutex::new(World { free_gb: 500, movie_subs: "eng", ..Default::default() }));
    let base = fake(w.clone()).await;
    let stack = Arc::new(stack(&base));
    let store = Arc::new(Store::memory());
    let (s2, st2) = (stack.clone(), store.clone());
    let machine = Arc::new(OnceCell::new());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        MediaServer::new(s2.clone(), st2.clone(), machine.clone())
    }));
    Env { url, w, stack, store }
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

fn text(v: &Value) -> String {
    v["result"]["content"][0]["text"].as_str().unwrap_or_default().to_string()
}

fn is_err(v: &Value) -> bool {
    v["result"]["isError"] == json!(true)
}

#[tokio::test]
async fn request_previews_then_sends_and_tracks() {
    let e = setup().await;
    let t = text(&call(&e.url, "request", json!({"media_type": "tv", "id": 10, "seasons": [2]})).await);
    assert!(
        t.starts_with("Aperçu, rien n'est fait : demander la série Série Delta (2025) : saison(s) 2, 10 épisode(s)."),
        "{t}"
    );
    assert!(logged(&e.w, "POST seerr").is_empty());
    assert!(e.store.list(false).unwrap().is_empty());

    let t =
        text(&call(&e.url, "request", json!({"media_type": "tv", "id": 10, "seasons": [2], "confirm": true})).await);
    assert!(t.starts_with("Fait : demander la série"), "{t}");
    assert_eq!(logged(&e.w, "POST seerr"), [r#"POST seerr request {"mediaId":10,"mediaType":"tv","seasons":[2]}"#]);
    let r = &e.store.list(true).unwrap()[0];
    assert_eq!(
        (r.tvdb_id, r.seasons.clone(), r.seerr_id, r.title.as_str()),
        (Some(4242), vec![2], Some(900), "Série Delta")
    );

    // Déjà suivie ; saison inconnue ; déjà disponible ; type invalide.
    assert!(text(&call(&e.url, "request", json!({"media_type": "tv", "id": 10})).await).contains("déjà demandé"));
    assert!(
        text(&call(&e.url, "request", json!({"media_type": "tv", "id": 11, "seasons": [9]})).await)
            .contains("Saison 9 inconnue. Saisons : 1, 2.")
    );
    assert!(
        text(&call(&e.url, "request", json!({"media_type": "movie", "id": 1, "confirm": true})).await)
            .contains("déjà disponible")
    );
    assert!(is_err(&call(&e.url, "request", json!({"media_type": "book", "id": 1})).await));
    let t = text(&call(&e.url, "requests", json!({})).await);
    assert!(t.contains("- Série Delta (série) — en attente"), "{t}");
}

#[tokio::test]
async fn unfollow_keeps_the_whole_object_and_needs_confirm() {
    let e = setup().await;
    let t = text(&call(&e.url, "unfollow", json!({"title": "delta"})).await);
    assert!(t.contains("arrêter la surveillance de la série « Série Delta » (rien n'est supprimé)"), "{t}");
    assert!(logged(&e.w, "PUT").is_empty());
    call(&e.url, "unfollow", json!({"title": "delta", "confirm": true})).await;
    assert_eq!(logged(&e.w, "PUT"), ["PUT sonarr series 7 monitored=false extra=1"]);
    assert!(text(&call(&e.url, "follow", json!({"title": "delta"})).await).contains("déjà suivi"));
    assert!(text(&call(&e.url, "unfollow", json!({"title": "inconnu"})).await).contains("search puis request"));
}

#[tokio::test]
async fn retry_and_blocklist() {
    let e = setup().await;
    call(&e.url, "retry", json!({"title": "Série Delta", "confirm": true})).await;
    assert_eq!(logged(&e.w, "POST sonarr command"), [r#"POST sonarr command {"name":"SeriesSearch","seriesId":7}"#]);
    assert_eq!(logged(&e.w, "PATCH"), ["PATCH bazarr series seriesid=7&action=search-missing"]);

    let t = text(&call(&e.url, "blocklist_release", json!({"title": "delta"})).await);
    assert!(t.contains("écarter 1 version(s) en échec de la série « Série Delta » (Série Delta S01E01)"), "{t}");
    assert!(logged(&e.w, "DELETE").is_empty());
    call(&e.url, "blocklist_release", json!({"title": "delta", "confirm": true})).await;
    assert_eq!(
        logged(&e.w, "DELETE"),
        ["DELETE sonarr queue 31 removeFromClient=true&blocklist=true&skipRedownload=false"]
    );
}

#[tokio::test]
async fn downloads_control_and_mark_watched() {
    let e = setup().await;
    assert!(text(&call(&e.url, "downloads_control", json!({"action": "pause"})).await).starts_with("Aperçu"));
    let t = text(&call(&e.url, "downloads_control", json!({"action": "pause", "confirm": true})).await);
    assert_eq!(t, "Mettre en pause — torrents : fait ; usenet : fait.");
    assert_eq!(logged(&e.w, "POST qbit stop").len(), 1);
    assert_eq!(logged(&e.w, "GET nzbget pause").len(), 1);
    call(&e.url, "downloads_control", json!({"action": "lent", "confirm": true})).await;
    assert_eq!(logged(&e.w, "POST qbit toggle").len(), 1);
    assert!(is_err(&call(&e.url, "downloads_control", json!({"action": "vite"})).await));

    // Titre du lecteur différent du gestionnaire : correspondance par identifiant TVDB.
    let t = text(&call(&e.url, "mark_watched", json!({"title": "Série Delta", "season": 1})).await);
    assert!(t.contains("marquer « Série Delta (VF) » saison 1 (2 épisodes) comme vu."), "{t}");
    call(&e.url, "mark_watched", json!({"title": "Série Delta", "season": 1, "episode": 2, "confirm": true})).await;
    assert_eq!(logged(&e.w, "SCROBBLE"), ["SCROBBLE key=602&identifier=com.plexapp.plugins.library"]);
}

fn at(y: i32, m: u32, d: u32) -> chrono::DateTime<Utc> {
    Utc.with_ymd_and_hms(y, m, d, 12, 0, 0).unwrap()
}

#[tokio::test]
async fn tracker_moves_requests_to_done_failed_or_late() {
    let e = setup().await;
    let created = at(2026, 10, 2).timestamp();
    let movie = e.store.add("movie", 55, None, "Film Epsilon", &[], Some(900), created).unwrap();

    // Sorti le 01/10, pas de fichier : en retard (prévenu une seule fois).
    let n = tracker::tick(&e.stack, &e.store, at(2026, 10, 10)).await;
    assert_eq!(n.len(), 1, "{n:?}");
    assert_eq!(n[0].0, "En retard : Film Epsilon");
    assert!(n[0].1.contains("sorti le 01/10, Film Epsilon sorti, toujours pas téléchargé"), "{n:?}");
    assert!(tracker::tick(&e.stack, &e.store, at(2026, 10, 11)).await.is_empty());

    // Fichier présent, sous-titres anglais seulement : pas encore prêt.
    e.w.lock().unwrap().movie_has_file = true;
    assert!(tracker::tick(&e.stack, &e.store, at(2026, 10, 12)).await.is_empty());
    assert_eq!(e.store.list(true).unwrap()[0].state, State::Late);

    // Sous-titres FR : prêt, avec lien.
    e.w.lock().unwrap().movie_subs = "fra";
    let n = tracker::tick(&e.stack, &e.store, at(2026, 10, 13)).await;
    assert_eq!(n[0].0, "Prêt : Film Epsilon");
    assert!(n[0].1.contains("ST FR sur le lecteur — https://app.plex.tv/desktop/#!/server/m1/"), "{n:?}");
    assert!(e.store.list(true).unwrap().is_empty());
    assert_eq!(e.store.list(false).unwrap()[0].id, movie);

    // Demande refusée : échec.
    e.store.add("tv", 10, Some(4242), "Série Delta", &[1], Some(900), created).unwrap();
    e.w.lock().unwrap().request_status = 3;
    let n = tracker::tick(&e.stack, &e.store, at(2026, 10, 14)).await;
    assert_eq!(n, [("Échec : Série Delta".to_string(), "Série Delta : demande refusée.".to_string())]);
}

#[tokio::test]
async fn tracker_drops_old_requests_and_alerts_disk_once_a_day() {
    let e = setup().await;
    e.store.add("tv", 10, Some(4242), "Série Delta", &[1], None, at(2026, 1, 1).timestamp()).unwrap();
    let n = tracker::tick(&e.stack, &e.store, at(2026, 6, 1)).await;
    assert_eq!(n[0].0, "Suivi arrêté : Série Delta");

    e.w.lock().unwrap().free_gb = 20;
    let n = tracker::tick(&e.stack, &e.store, at(2026, 6, 2)).await;
    assert_eq!(n, [("Disque presque plein".to_string(), "Sous 50 Go : /data/tv : 20 Go libres.".to_string())]);
    assert!(tracker::tick(&e.stack, &e.store, at(2026, 6, 2)).await.is_empty());
    assert_eq!(tracker::tick(&e.stack, &e.store, at(2026, 6, 3)).await.len(), 1);
}

#[tokio::test]
async fn series_request_waits_for_first_requested_episode() {
    let e = setup().await;
    let id = e.store.add("tv", 10, None, "Série Delta", &[1], None, at(2026, 9, 2).timestamp()).unwrap();
    // Premier épisode demandé : S01E01, sorti le 01/09 → en retard au 10/09 ; tvdb récupéré via Seerr.
    let n = tracker::tick(&e.stack, &e.store, at(2026, 9, 10)).await;
    assert!(n[0].1.contains("S01E01 sorti, toujours pas téléchargé"), "{n:?}");
    let r = &e.store.list(true).unwrap()[0];
    assert_eq!((r.id, r.tvdb_id, r.state), (id, Some(4242), State::Late));
}

#[tokio::test]
async fn notifier_posts_title_and_message() {
    let e = setup().await;
    let n = e.stack.notifier.as_ref().unwrap();
    assert!(n.send("Prêt : Film", "Film : ST FR.").await);
    let l = logged(&e.w, "NTFY");
    assert_eq!(l, ["NTFY title=Pr%C3%AAt+%3A+Film Film : ST FR."]);
}
