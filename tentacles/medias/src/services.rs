//! Clients des services de la stack : types lus (tolérants, champs par défaut)
//! et appels. Aucune mise en forme ici.

use serde::Deserialize;
use serde_json::json;

use crate::http::{Opt, Svc, SvcError, need};

type R<T> = Result<T, SvcError>;

// ---------------------------------------------------------------- Plex

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PlexStream {
    pub stream_type: i64,
    pub language_code: Option<String>,
    pub forced: Option<bool>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct PlexPart {
    #[serde(rename = "Stream")]
    pub streams: Vec<PlexStream>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct PlexMedia {
    #[serde(rename = "Part")]
    pub parts: Vec<PlexPart>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct PlexGuid {
    pub id: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PlexItem {
    pub rating_key: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub title: String,
    pub grandparent_title: Option<String>,
    pub grandparent_rating_key: Option<String>,
    pub parent_index: Option<i64>,
    pub index: Option<i64>,
    /// Millisecondes.
    pub duration: Option<i64>,
    pub view_offset: Option<i64>,
    pub view_count: Option<i64>,
    pub last_viewed_at: Option<i64>,
    pub added_at: Option<i64>,
    pub leaf_count: Option<i64>,
    pub viewed_leaf_count: Option<i64>,
    pub year: Option<i64>,
    pub summary: Option<String>,
    #[serde(rename = "Guid")]
    pub guids: Vec<PlexGuid>,
    #[serde(rename = "Media")]
    pub media: Vec<PlexMedia>,
}

impl PlexItem {
    pub fn streams(&self) -> impl Iterator<Item = &PlexStream> {
        self.media.iter().flat_map(|m| &m.parts).flat_map(|p| &p.streams)
    }
    pub fn tvdb(&self) -> Option<i64> {
        self.guids.iter().find_map(|g| g.id.strip_prefix("tvdb://")?.parse().ok())
    }
    pub fn tmdb(&self) -> Option<i64> {
        self.guids.iter().find_map(|g| g.id.strip_prefix("tmdb://")?.parse().ok())
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct PlexDir {
    pub key: String,
    #[serde(rename = "type")]
    pub kind: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PlexContainer {
    pub machine_identifier: Option<String>,
    #[serde(rename = "Metadata")]
    pub items: Vec<PlexItem>,
    #[serde(rename = "Directory")]
    pub dirs: Vec<PlexDir>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct PlexEnvelope {
    #[serde(rename = "MediaContainer", default)]
    pub mc: PlexContainer,
}

async fn plex_get(s: &Svc, path: &str, query: &[(&str, String)], size: Option<u32>) -> R<PlexContainer> {
    let mut req = s.raw().get(s.url(path)).query(query);
    if let Some(n) = size {
        req = req.header("X-Plex-Container-Start", "0").header("X-Plex-Container-Size", n.to_string());
    }
    Ok(s.send::<PlexEnvelope>(req).await?.mc)
}

pub async fn plex_machine_id(p: &Opt) -> R<String> {
    plex_get(need(p)?, "/identity", &[], None).await?.machine_identifier.ok_or(SvcError::Unavailable)
}

pub async fn plex_sections(p: &Opt, kind: &str) -> R<Vec<String>> {
    let mc = plex_get(need(p)?, "/library/sections", &[], None).await?;
    Ok(mc.dirs.into_iter().filter(|d| d.kind == kind).map(|d| d.key).collect())
}

pub async fn plex_on_deck(p: &Opt) -> R<Vec<PlexItem>> {
    Ok(plex_get(need(p)?, "/library/onDeck", &[], Some(20)).await?.items)
}

/// Éléments d'une section triés (`sort` Plex), avec filtres éventuels.
pub async fn plex_section_items(p: &Opt, section: &str, query: &[(&str, String)], size: u32) -> R<Vec<PlexItem>> {
    Ok(plex_get(need(p)?, &format!("/library/sections/{section}/all"), query, Some(size)).await?.items)
}

pub async fn plex_all_leaves(p: &Opt, show_key: &str) -> R<Vec<PlexItem>> {
    Ok(plex_get(need(p)?, &format!("/library/metadata/{show_key}/allLeaves"), &[], None).await?.items)
}

pub async fn plex_unwatched_leaves(p: &Opt, show_key: &str) -> R<Vec<PlexItem>> {
    let q = [("unwatched", "1".to_string())];
    Ok(plex_get(need(p)?, &format!("/library/metadata/{show_key}/allLeaves"), &q, None).await?.items)
}

/// Fiche complète (pistes audio et sous-titres, identifiants externes).
/// Élément inconnu du lecteur : [`SvcError::NotFound`], distinct d'un lecteur injoignable.
pub async fn plex_metadata(p: &Opt, key: &str) -> R<PlexItem> {
    let s = need(p)?;
    let req = s.raw().get(s.url(&format!("/library/metadata/{key}"))).query(&[("includeGuids", "1")]);
    s.send_or_missing::<PlexEnvelope>(req)
        .await?
        .mc
        .items
        .into_iter()
        .next()
        .ok_or(SvcError::NotFound)
}

// ---------------------------------------------------------------- Sonarr / Radarr

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Titled {
    pub title: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EpisodeRef {
    pub season_number: i64,
    pub episode_number: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct CalendarEpisode {
    pub series_id: i64,
    pub season_number: i64,
    pub episode_number: i64,
    pub title: String,
    pub air_date_utc: Option<String>,
    pub has_file: bool,
    pub monitored: bool,
    /// Minutes.
    pub runtime: Option<i64>,
    pub finale_type: Option<String>,
    pub series: Option<Titled>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Movie {
    pub id: i64,
    pub tmdb_id: i64,
    pub title: String,
    pub year: i64,
    pub monitored: bool,
    pub has_file: bool,
    pub status: String,
    pub in_cinemas: Option<String>,
    pub digital_release: Option<String>,
    pub physical_release: Option<String>,
    pub runtime: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StatusMessage {
    pub title: String,
    pub messages: Vec<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QueueItem {
    pub id: i64,
    pub title: String,
    pub status: String,
    pub tracked_download_status: String,
    pub tracked_download_state: String,
    pub timeleft: Option<String>,
    pub size: f64,
    pub sizeleft: f64,
    pub protocol: String,
    pub error_message: Option<String>,
    pub status_messages: Vec<StatusMessage>,
    pub series: Option<Titled>,
    pub episode: Option<EpisodeRef>,
    pub movie: Option<Titled>,
}

impl QueueItem {
    /// Titre lisible : série + épisode, ou film, sinon nom de la version.
    pub fn label(&self) -> String {
        match (&self.series, &self.episode, &self.movie) {
            (Some(s), Some(e), _) => format!("{} S{:02}E{:02}", s.title, e.season_number, e.episode_number),
            (Some(s), None, _) => s.title.clone(),
            (_, _, Some(m)) => m.title.clone(),
            _ => self.title.clone(),
        }
    }
    pub fn has_problem(&self) -> bool {
        self.tracked_download_status != "ok" && !self.tracked_download_status.is_empty()
            || self.error_message.as_deref().is_some_and(|m| !m.is_empty())
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Page<T> {
    pub records: Vec<T>,
    #[serde(rename = "totalRecords")]
    pub total_records: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Health {
    #[serde(rename = "type")]
    pub kind: String,
    pub source: String,
    pub message: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RootFolder {
    pub path: String,
    pub free_space: Option<i64>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SeriesStats {
    pub episode_file_count: i64,
    pub episode_count: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Series {
    pub id: i64,
    pub title: String,
    pub status: String,
    pub monitored: bool,
    pub tvdb_id: i64,
    pub next_airing: Option<String>,
    pub statistics: SeriesStats,
}

pub async fn sonarr_calendar(s: &Opt, start: &str, end: &str) -> R<Vec<CalendarEpisode>> {
    let q = [("start", start.to_string()), ("end", end.to_string()), ("includeSeries", "true".into())];
    need(s)?.get("/api/v3/calendar", &q).await
}

pub async fn radarr_calendar(s: &Opt, start: &str, end: &str) -> R<Vec<Movie>> {
    let q = [("start", start.to_string()), ("end", end.to_string())];
    need(s)?.get("/api/v3/calendar", &q).await
}

pub async fn arr_queue(s: &Opt) -> R<Vec<QueueItem>> {
    let q = [
        ("pageSize", "500".to_string()),
        ("includeSeries", "true".into()),
        ("includeEpisode", "true".into()),
        ("includeMovie", "true".into()),
    ];
    Ok(need(s)?.get::<Page<QueueItem>>("/api/v3/queue", &q).await?.records)
}

pub async fn arr_health(s: &Opt, api: &str) -> R<Vec<Health>> {
    need(s)?.get(&format!("/api/{api}/health"), &[]).await
}

pub async fn arr_rootfolders(s: &Opt) -> R<Vec<RootFolder>> {
    need(s)?.get("/api/v3/rootfolder", &[]).await
}

pub async fn arr_missing_count(s: &Opt) -> R<i64> {
    let q = [("pageSize", "1".to_string()), ("monitored", "true".into())];
    Ok(need(s)?.get::<Page<serde_json::Value>>("/api/v3/wanted/missing", &q).await?.total_records)
}

/// Liste lente sous charge (statistiques recalculées) : délai long ; passer par `Stack::series`.
pub async fn sonarr_series(s: &Opt) -> R<Vec<Series>> {
    let s = need(s)?;
    s.send(s.raw().get(s.url("/api/v3/series")).timeout(std::time::Duration::from_secs(90))).await
}

pub async fn radarr_movies(s: &Opt) -> R<Vec<Movie>> {
    need(s)?.get("/api/v3/movie", &[]).await
}

// ---------------------------------------------------------------- Bazarr

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct WantedEpisode {
    pub series_title: String,
    /// « SxE », ex. « 3x4 ».
    pub episode_number: String,
    pub sonarr_series_id: i64,
}

impl WantedEpisode {
    pub fn season_episode(&self) -> Option<(i64, i64)> {
        let (s, e) = self.episode_number.split_once('x')?;
        Some((s.trim().parse().ok()?, e.trim().parse().ok()?))
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Wanted<T> {
    pub data: Vec<T>,
    pub total: i64,
}

pub async fn bazarr_wanted_episodes(s: &Opt) -> R<Wanted<WantedEpisode>> {
    let q = [("start", "0".to_string()), ("length", "2000".into())];
    need(s)?.get("/api/episodes/wanted", &q).await
}

pub async fn bazarr_wanted_movies_total(s: &Opt) -> R<i64> {
    let q = [("start", "0".to_string()), ("length", "1".into())];
    Ok(need(s)?.get::<Wanted<serde_json::Value>>("/api/movies/wanted", &q).await?.total)
}

pub async fn bazarr_ping(s: &Opt) -> R<()> {
    need(s)?.get::<serde_json::Value>("/api/system/status", &[]).await.map(|_| ())
}

// ---------------------------------------------------------------- Seerr

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SeerrMediaInfo {
    pub status: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SeerrResult {
    pub id: i64,
    pub media_type: String,
    pub name: Option<String>,
    pub title: Option<String>,
    pub first_air_date: Option<String>,
    pub release_date: Option<String>,
    pub media_info: Option<SeerrMediaInfo>,
}

impl SeerrResult {
    pub fn label(&self) -> String {
        let t = self.name.clone().or_else(|| self.title.clone()).unwrap_or_default();
        let date = self.first_air_date.as_deref().or(self.release_date.as_deref()).unwrap_or("");
        match date.get(..4) {
            Some(y) if !y.is_empty() => format!("{t} ({y})"),
            _ => t,
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct SeerrSearch {
    pub results: Vec<SeerrResult>,
}

/// Encodage d'un composant d'URL en `%XX` : Seerr refuse l'espace encodé en `+`.
pub fn encode_component(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

pub async fn seerr_search(s: &Opt, query: &str) -> R<Vec<SeerrResult>> {
    let s = need(s)?;
    let url = format!("{}?query={}&page=1&language=fr", s.url("/api/v1/search"), encode_component(query));
    let r: SeerrSearch = s.send(s.raw().get(url)).await?;
    Ok(r.results.into_iter().filter(|r| r.media_type == "tv" || r.media_type == "movie").collect())
}

pub async fn seerr_ping(s: &Opt) -> R<()> {
    need(s)?.get::<serde_json::Value>("/api/v1/status", &[]).await.map(|_| ())
}

// ---------------------------------------------------------------- qBittorrent

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Torrent {
    pub name: String,
    pub state: String,
    pub progress: f64,
    pub dlspeed: i64,
    /// Secondes ; 8640000 = inconnu.
    pub eta: i64,
    pub category: String,
    pub size: i64,
    pub ratio: f64,
    /// Limite de ratio effective ; ≤ 0 = aucune.
    pub max_ratio: f64,
    /// Secondes de partage écoulées.
    pub seeding_time: i64,
    /// Limite de partage effective, en minutes ; ≤ 0 = aucune.
    pub max_seeding_time: i64,
    pub private: Option<bool>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Transfer {
    pub connection_status: String,
    pub dl_info_speed: i64,
}

/// Cookie de session : `SID` jusqu'à la v4, `QBT_SID_<port>` ensuite.
pub fn is_session_cookie(kv: &str) -> bool {
    kv.split_once('=').is_some_and(|(k, v)| (k == "SID" || k.starts_with("QBT_SID")) && !v.is_empty())
}

pub const QBIT_LOGIN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(45);

/// Session qBittorrent : connexion par formulaire, cookie de session.
async fn qbit_login(s: &Svc, user: &str, pass: &str) -> R<String> {
    let resp = s
        .raw()
        .post(s.url("/api/v2/auth/login"))
        .header("Referer", s.url(""))
        .form(&[("username", user), ("password", pass)])
        // Sous charge, la connexion seule peut dépasser 10 s ; les appels suivants restent rapides.
        .timeout(QBIT_LOGIN_TIMEOUT)
        .send()
        .await
        .map_err(|_| {
            eprintln!("medias/{} : appel impossible", s.name);
            SvcError::Unavailable
        })?;
    let sid = resp
        .headers()
        .get_all("set-cookie")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .find_map(|c| c.split(';').next().filter(|kv| is_session_cookie(kv)).map(str::to_string));
    sid.ok_or_else(|| {
        eprintln!("medias/{} : connexion refusée", s.name);
        SvcError::Denied
    })
}

pub struct Qbit {
    pub svc: Svc,
    pub user: String,
    pub pass: String,
    /// Cookie de session réutilisé : qBittorrent bannit une adresse après quelques échecs de connexion.
    pub sid: tokio::sync::Mutex<Option<String>>,
}

impl Qbit {
    pub fn new(svc: Svc, user: String, pass: String) -> Qbit {
        Qbit { svc, user, pass, sid: tokio::sync::Mutex::new(None) }
    }
}

async fn qbit_get<T: serde::de::DeserializeOwned>(q: &Option<Qbit>, path: &str, query: &[(&str, String)]) -> R<T> {
    let q = q.as_ref().ok_or(SvcError::NotConfigured)?;
    let mut sid = q.sid.lock().await;
    for attempt in 0..2 {
        let cookie = match sid.as_ref() {
            Some(c) => c.clone(),
            None => {
                let c = qbit_login(&q.svc, &q.user, &q.pass).await?;
                *sid = Some(c.clone());
                c
            }
        };
        match q.svc.send(q.svc.raw().get(q.svc.url(path)).query(query).header("Cookie", cookie)).await {
            // Session expirée : une seule reconnexion.
            Err(SvcError::Denied) if attempt == 0 => *sid = None,
            r => return r,
        }
    }
    Err(SvcError::Denied)
}

pub async fn qbit_torrents(q: &Option<Qbit>) -> R<Vec<Torrent>> {
    qbit_get(q, "/api/v2/torrents/info", &[]).await
}

pub async fn qbit_transfer(q: &Option<Qbit>) -> R<Transfer> {
    qbit_get(q, "/api/v2/transfer/info", &[]).await
}

// ---------------------------------------------------------------- NZBGet

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "PascalCase", default)]
pub struct NzbStatus {
    pub download_paused: bool,
    pub download_rate: i64,
    #[serde(rename = "RemainingSizeMB")]
    pub remaining_size_mb: i64,
    #[serde(rename = "FreeDiskSpaceMB")]
    pub free_disk_space_mb: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "PascalCase", default)]
pub struct NzbGroup {
    #[serde(rename = "NZBName")]
    pub nzb_name: String,
    pub status: String,
    #[serde(rename = "FileSizeMB")]
    pub file_size_mb: i64,
    #[serde(rename = "RemainingSizeMB")]
    pub remaining_size_mb: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RpcResult<T> {
    pub result: T,
}

pub async fn nzbget_status(s: &Opt) -> R<NzbStatus> {
    Ok(need(s)?.get::<RpcResult<NzbStatus>>("/jsonrpc/status", &[]).await?.result)
}

pub async fn nzbget_groups(s: &Opt) -> R<Vec<NzbGroup>> {
    Ok(need(s)?.get::<RpcResult<Vec<NzbGroup>>>("/jsonrpc/listgroups", &[]).await?.result)
}

// ---------------------------------------------------------------- Tdarr

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TdarrStats {
    pub total_transcode_count: f64,
    /// Gigaoctets gagnés.
    pub size_diff: f64,
}

pub async fn tdarr_stats(s: &Opt) -> R<TdarrStats> {
    let body = json!({"data": {"collection": "StatisticsJSONDB", "mode": "getById", "docID": "statistics"}});
    need(s)?.post("/api/v2/cruddb", &body).await
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct TdarrStatus {
    pub status: String,
}

pub async fn tdarr_status(s: &Opt) -> R<TdarrStatus> {
    need(s)?.get("/api/v2/status", &[]).await
}

// ---------------------------------------------------------------- Prowlarr

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct IndexerStatus {
    pub indexer_id: i64,
    pub disabled_till: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Indexer {
    pub id: i64,
    pub name: String,
    pub enable: bool,
}

pub async fn prowlarr_status(s: &Opt) -> R<(Vec<Indexer>, Vec<IndexerStatus>)> {
    let s = need(s)?;
    let (a, b) = tokio::join!(s.get("/api/v1/indexer", &[]), s.get("/api/v1/indexerstatus", &[]));
    Ok((a?, b?))
}

// ---------------------------------------------------------------- Écritures

/// Fiche Seerr d'une série ou d'un film (aperçu d'une demande).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SeerrSeason {
    pub season_number: i64,
    pub episode_count: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SeerrExternalIds {
    pub tvdb_id: Option<i64>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SeerrDetails {
    pub id: i64,
    pub name: Option<String>,
    pub title: Option<String>,
    pub first_air_date: Option<String>,
    pub release_date: Option<String>,
    pub seasons: Vec<SeerrSeason>,
    pub external_ids: Option<SeerrExternalIds>,
    pub media_info: Option<SeerrMediaInfo>,
    pub runtime: Option<i64>,
}

impl SeerrDetails {
    pub fn label(&self) -> String {
        let t = self.name.clone().or_else(|| self.title.clone()).unwrap_or_default();
        match self.first_air_date.as_deref().or(self.release_date.as_deref()).and_then(|d| d.get(..4)) {
            Some(y) if !y.is_empty() => format!("{t} ({y})"),
            _ => t,
        }
    }
}

pub async fn seerr_details(s: &Opt, media_type: &str, tmdb: i64) -> R<SeerrDetails> {
    let q = [("language", "fr".to_string())];
    need(s)?.get(&format!("/api/v1/{media_type}/{tmdb}"), &q).await
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct SeerrRequest {
    pub id: i64,
    /// 1 en attente de validation, 2 validée, 3 refusée, 4 en échec, 5 terminée.
    pub status: i64,
}

/// Crée une demande ; `seasons` vide = toutes les saisons (séries).
pub async fn seerr_request(s: &Opt, media_type: &str, tmdb: i64, seasons: &[i64]) -> R<SeerrRequest> {
    let mut body = serde_json::json!({"mediaType": media_type, "mediaId": tmdb});
    if media_type == "tv" {
        body["seasons"] = if seasons.is_empty() { serde_json::json!("all") } else { serde_json::json!(seasons) };
    }
    need(s)?.post("/api/v1/request", &body).await
}

pub async fn seerr_request_status(s: &Opt, id: i64) -> R<SeerrRequest> {
    need(s)?.get(&format!("/api/v1/request/{id}"), &[]).await
}

/// Active ou coupe la surveillance d'une série (`series`) ou d'un film (`movie`), sans rien supprimer.
pub async fn arr_set_monitored(s: &Opt, resource: &str, id: i64, monitored: bool) -> R<()> {
    let s = need(s)?;
    let path = format!("/api/v3/{resource}/{id}");
    let mut obj: serde_json::Value = s.get(&path, &[]).await?;
    obj["monitored"] = serde_json::json!(monitored);
    s.send_unit(s.raw().put(s.url(&path)).json(&obj)).await
}

/// Lance une commande Sonarr / Radarr (`SeriesSearch`, `MoviesSearch`…).
pub async fn arr_command(s: &Opt, body: serde_json::Value) -> R<()> {
    let s = need(s)?;
    s.send_unit(s.raw().post(s.url("/api/v3/command")).json(&body)).await
}

/// Retire un élément de la file, écarte la version et relance une recherche.
/// Le fichier en cours de téléchargement est abandonné ; la bibliothèque n'est pas touchée.
pub async fn arr_blocklist(s: &Opt, queue_id: i64) -> R<()> {
    let s = need(s)?;
    let q = [("removeFromClient", "true"), ("blocklist", "true"), ("skipRedownload", "false")];
    s.send_unit(s.raw().delete(s.url(&format!("/api/v3/queue/{queue_id}"))).query(&q)).await
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Episode {
    pub season_number: i64,
    pub episode_number: i64,
    pub air_date_utc: Option<String>,
    pub has_file: bool,
}

pub async fn sonarr_episodes(s: &Opt, series_id: i64) -> R<Vec<Episode>> {
    need(s)?.get("/api/v3/episode", &[("seriesId", series_id.to_string())]).await
}

/// Recherche des sous-titres manquants d'une série (`series`, id Sonarr) ou d'un film (`movies`, id Radarr).
pub async fn bazarr_search_missing(s: &Opt, resource: &str, id: i64) -> R<()> {
    let s = need(s)?;
    let key = if resource == "series" { "seriesid" } else { "radarrid" };
    let q = [(key, id.to_string()), ("action", "search-missing".to_string())];
    s.send_unit(s.raw().patch(s.url(&format!("/api/{resource}"))).query(&q)).await
}

/// Marque vu / non vu dans le lecteur (film, épisode, ou série entière).
pub async fn plex_scrobble(p: &Opt, rating_key: &str, watched: bool) -> R<()> {
    let s = need(p)?;
    let path = if watched { "/:/scrobble" } else { "/:/unscrobble" };
    let q = [("key", rating_key), ("identifier", "com.plexapp.plugins.library")];
    s.send_unit(s.raw().get(s.url(path)).query(&q)).await
}

async fn qbit_post(q: &Option<Qbit>, paths: &[&str], form: &[(&str, &str)]) -> R<()> {
    let q = q.as_ref().ok_or(SvcError::NotConfigured)?;
    let mut sid = q.sid.lock().await;
    let cookie = match sid.as_ref() {
        Some(c) => c.clone(),
        None => {
            let c = qbit_login(&q.svc, &q.user, &q.pass).await?;
            *sid = Some(c.clone());
            c
        }
    };
    // Variantes selon la version : la première qui existe gagne.
    let mut last = SvcError::Unavailable;
    for path in paths {
        let req = q.svc.raw().post(q.svc.url(path)).header("Cookie", cookie.clone()).form(form);
        match q.svc.send_unit(req).await {
            Ok(()) => return Ok(()),
            Err(SvcError::Denied) => {
                *sid = None;
                return Err(SvcError::Denied);
            }
            Err(e) => last = e,
        }
    }
    Err(last)
}

/// Met en pause ou relance tous les torrents.
pub async fn qbit_pause_all(q: &Option<Qbit>, pause: bool) -> R<()> {
    let paths: &[&str] = if pause {
        &["/api/v2/torrents/stop", "/api/v2/torrents/pause"]
    } else {
        &["/api/v2/torrents/start", "/api/v2/torrents/resume"]
    };
    qbit_post(q, paths, &[("hashes", "all")]).await
}

/// Mode de débit réduit (limites alternatives) : vrai s'il est actif.
pub async fn qbit_slow_mode(q: &Option<Qbit>) -> R<bool> {
    let v: i64 = qbit_get(q, "/api/v2/transfer/speedLimitsMode", &[]).await?;
    Ok(v == 1)
}

pub async fn qbit_toggle_slow(q: &Option<Qbit>) -> R<()> {
    qbit_post(q, &["/api/v2/transfer/toggleSpeedLimitsMode"], &[]).await
}

pub async fn nzbget_pause(s: &Opt, pause: bool) -> R<()> {
    let path = if pause { "/jsonrpc/pausedownload" } else { "/jsonrpc/resumedownload" };
    let r: RpcResult<bool> = need(s)?.get(path, &[]).await?;
    if r.result { Ok(()) } else { Err(SvcError::Unavailable) }
}

// ---------------------------------------------------------------- Historique du lecteur

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Viewed {
    #[serde(rename = "type")]
    pub kind: String,
    pub title: String,
    pub grandparent_title: Option<String>,
    pub grandparent_rating_key: Option<String>,
    pub rating_key: String,
    pub viewed_at: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ViewedContainer {
    #[serde(rename = "Metadata")]
    pub items: Vec<Viewed>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct ViewedEnvelope {
    #[serde(rename = "MediaContainer", default)]
    pub mc: ViewedContainer,
}

/// Visionnages depuis `since` (horodatage), du plus récent au plus ancien.
pub async fn plex_history(p: &Opt, since: i64) -> R<Vec<Viewed>> {
    let s = need(p)?;
    let mut out = Vec::new();
    for page in 0..20 {
        let req = s
            .raw()
            .get(s.url("/status/sessions/history/all"))
            .query(&[("sort", "viewedAt:desc"), ("viewedAt>", &since.to_string())])
            .header("X-Plex-Container-Start", (page * 200).to_string())
            .header("X-Plex-Container-Size", "200");
        let items = s.send::<ViewedEnvelope>(req).await?.mc.items;
        let n = items.len();
        out.extend(items.into_iter().filter(|v| v.viewed_at >= since));
        if n < 200 {
            break;
        }
    }
    Ok(out)
}

// ---------------------------------------------------------------- Lidarr (musique)

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Artist {
    pub id: i64,
    pub artist_name: String,
    pub foreign_artist_id: String,
    pub disambiguation: Option<String>,
    pub monitored: bool,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Album {
    pub title: String,
    pub release_date: Option<String>,
    pub monitored: bool,
    pub album_type: Option<String>,
    pub artist: Option<Artist>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Profile {
    pub id: i64,
    pub name: String,
}

pub async fn lidarr_artists(s: &Opt) -> R<Vec<Artist>> {
    need(s)?.get("/api/v1/artist", &[]).await
}

pub async fn lidarr_calendar(s: &Opt, start: &str, end: &str) -> R<Vec<Album>> {
    let q = [("start", start.to_string()), ("end", end.to_string()), ("includeArtist", "true".into())];
    need(s)?.get("/api/v1/calendar", &q).await
}

pub async fn lidarr_lookup(s: &Opt, term: &str) -> R<Vec<Artist>> {
    let s = need(s)?;
    let url = format!("{}?term={}", s.url("/api/v1/artist/lookup"), encode_component(term));
    s.send(s.raw().get(url).timeout(std::time::Duration::from_secs(45))).await
}

/// Ajoute un artiste suivi : albums futurs seulement, ou toute la discographie (recherchée).
pub async fn lidarr_add_artist(s: &Opt, foreign_id: &str, discography: bool) -> R<Artist> {
    let svc = need(s)?;
    let roots: Vec<RootFolder> = svc.get("/api/v1/rootfolder", &[]).await?;
    let quality: Vec<Profile> = svc.get("/api/v1/qualityprofile", &[]).await?;
    let meta: Vec<Profile> = svc.get("/api/v1/metadataprofile", &[]).await?;
    let found = lidarr_lookup(s, &format!("lidarr:{foreign_id}")).await?;
    let a = found.into_iter().find(|a| a.foreign_artist_id == foreign_id).ok_or(SvcError::Unavailable)?;
    let (Some(root), Some(q), Some(m)) =
        (roots.first(), quality.first(), meta.iter().find(|m| m.name != "None").or(meta.first()))
    else {
        return Err(SvcError::Unavailable);
    };
    let body = serde_json::json!({
        "artistName": a.artist_name,
        "foreignArtistId": a.foreign_artist_id,
        "qualityProfileId": q.id,
        "metadataProfileId": m.id,
        "rootFolderPath": root.path,
        "monitored": true,
        "addOptions": {
            "monitor": if discography { "all" } else { "future" },
            "searchForMissingAlbums": discography
        }
    });
    svc.post("/api/v1/artist", &body).await
}

// ---------------------------------------------------------------- LazyLibrarian (livres)

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct Book {
    pub authorname: String,
    pub bookid: String,
    pub bookname: String,
    pub bookpub: Option<serde_json::Value>,
}

impl Book {
    pub fn label(&self) -> String {
        let year = match &self.bookpub {
            Some(serde_json::Value::Number(n)) => format!(" ({n})"),
            Some(serde_json::Value::String(s)) if !s.is_empty() => format!(" ({s})"),
            _ => String::new(),
        };
        format!("{} — {}{year}", self.bookname, self.authorname)
    }
}

async fn ll_cmd<T: serde::de::DeserializeOwned>(s: &Opt, cmd: &str, args: &[(&str, &str)]) -> R<T> {
    let s = need(s)?;
    let mut q: Vec<(&str, String)> = vec![("cmd", cmd.to_string())];
    q.extend(args.iter().map(|(k, v)| (*k, v.to_string())));
    s.send(s.raw().get(s.url("/api")).query(&q).timeout(std::time::Duration::from_secs(60))).await
}

pub async fn ll_find_book(s: &Opt, name: &str) -> R<Vec<Book>> {
    ll_cmd(s, "findBook", &[("name", name)]).await
}

/// Ajoute un livre (identifiant de `ll_find_book`), le marque voulu et lance sa recherche.
/// Ces commandes répondent en texte : seul le statut HTTP compte.
pub async fn ll_request_book(s: &Opt, id: &str, audio: bool) -> R<()> {
    let svc = need(s)?;
    let kind = if audio { "AudioBook" } else { "eBook" };
    for (cmd, extra) in [("addBook", ("wait", "1")), ("queueBook", ("type", kind)), ("searchBook", ("type", kind))] {
        let req = svc
            .raw()
            .get(svc.url("/api"))
            .query(&[("cmd", cmd), ("id", id), extra])
            .timeout(std::time::Duration::from_secs(90));
        svc.send_unit(req).await?;
    }
    Ok(())
}

pub async fn ll_wanted(s: &Opt) -> R<Vec<serde_json::Value>> {
    ll_cmd(s, "getWanted", &[]).await
}

pub async fn ll_snatched(s: &Opt) -> R<Vec<serde_json::Value>> {
    ll_cmd(s, "getSnatched", &[]).await
}

// ---------------------------------------------------------------- Kavita (lecture)

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct KavitaSeries {
    pub id: i64,
    pub name: String,
    pub pages_read: i64,
    pub pages: i64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct KavitaRecent {
    pub series_name: String,
    pub title: String,
    pub created: Option<String>,
    pub count: i64,
}

/// Kavita : la clé d'API s'échange contre un jeton de session, gardé jusqu'au refus.
pub struct Kavita {
    pub svc: Svc,
    pub key: String,
    pub jwt: tokio::sync::Mutex<Option<String>>,
}

impl Kavita {
    pub fn new(svc: Svc, key: String) -> Kavita {
        Kavita { svc, key, jwt: tokio::sync::Mutex::new(None) }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
struct KavitaAuth {
    token: String,
}

async fn kavita_post<T: serde::de::DeserializeOwned>(k: &Option<Kavita>, path: &str) -> R<T> {
    let k = k.as_ref().ok_or(SvcError::NotConfigured)?;
    let mut jwt = k.jwt.lock().await;
    for attempt in 0..2 {
        let token = match jwt.as_ref() {
            Some(t) => t.clone(),
            None => {
                let req = k
                    .svc
                    .raw()
                    .post(k.svc.url("/api/Plugin/authenticate"))
                    .query(&[("apiKey", k.key.as_str()), ("pluginName", "Kairos")]);
                let a: KavitaAuth = k.svc.send(req).await?;
                *jwt = Some(a.token.clone());
                a.token
            }
        };
        let req = k.svc.raw().post(k.svc.url(path)).bearer_auth(token).json(&serde_json::json!({}));
        match k.svc.send(req).await {
            Err(SvcError::Denied) if attempt == 0 => *jwt = None,
            r => return r,
        }
    }
    Err(SvcError::Denied)
}

pub async fn kavita_on_deck(k: &Option<Kavita>) -> R<Vec<KavitaSeries>> {
    kavita_post(k, "/api/Series/on-deck?PageNumber=1&PageSize=10").await
}

pub async fn kavita_recent(k: &Option<Kavita>) -> R<Vec<KavitaRecent>> {
    kavita_post(k, "/api/Series/recently-updated-series").await
}

// ---------------------------------------------------------------- Audiobookshelf (livres audio)

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AbsMetadata {
    pub title: String,
    pub author_name: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AbsMedia {
    pub metadata: AbsMetadata,
    /// Secondes.
    pub duration: f64,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AbsItem {
    pub id: String,
    pub media: AbsMedia,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AbsInProgress {
    pub library_items: Vec<AbsItem>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AbsProgress {
    pub library_item_id: String,
    /// 0 à 1.
    pub progress: f64,
    pub current_time: f64,
    pub duration: f64,
    pub is_finished: bool,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AbsMe {
    pub media_progress: Vec<AbsProgress>,
}

pub async fn abs_in_progress(s: &Opt) -> R<(Vec<AbsItem>, Vec<AbsProgress>)> {
    let s = need(s)?;
    let (a, b) = tokio::join!(s.get::<AbsInProgress>("/api/me/items-in-progress", &[]), s.get::<AbsMe>("/api/me", &[]));
    Ok((a?.library_items, b?.media_progress))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_components_use_percent_encoding() {
        assert_eq!(encode_component("deux mots"), "deux%20mots");
        assert_eq!(encode_component("é&a+b"), "%C3%A9%26a%2Bb");
    }

    #[test]
    fn session_cookie_names() {
        assert!(is_session_cookie("SID=abc"));
        assert!(is_session_cookie("QBT_SID_7000=abc"));
        assert!(!is_session_cookie("SID="));
        assert!(!is_session_cookie("OTHER=abc"));
    }

    #[test]
    fn bazarr_episode_numbers() {
        let w = |s: &str| WantedEpisode { episode_number: s.into(), ..Default::default() };
        assert_eq!(w("3x4").season_episode(), Some((3, 4)));
        assert_eq!(w("x").season_episode(), None);
    }
}
