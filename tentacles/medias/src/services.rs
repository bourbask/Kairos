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

pub async fn plex_unwatched_leaves(p: &Opt, show_key: &str) -> R<Vec<PlexItem>> {
    let q = [("unwatched", "1".to_string())];
    Ok(plex_get(need(p)?, &format!("/library/metadata/{show_key}/allLeaves"), &q, None).await?.items)
}

/// Fiche complète (pistes audio et sous-titres, identifiants externes).
pub async fn plex_metadata(p: &Opt, key: &str) -> R<PlexItem> {
    let q = [("includeGuids", "1".to_string())];
    plex_get(need(p)?, &format!("/library/metadata/{key}"), &q, None)
        .await?
        .items
        .into_iter()
        .next()
        .ok_or(SvcError::Unavailable)
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

pub async fn sonarr_series(s: &Opt) -> R<Vec<Series>> {
    need(s)?.get("/api/v3/series", &[]).await
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

pub async fn seerr_search(s: &Opt, query: &str) -> R<Vec<SeerrResult>> {
    let q = [("query", query.to_string()), ("page", "1".into()), ("language", "fr".into())];
    let r: SeerrSearch = need(s)?.get("/api/v1/search", &q).await?;
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

/// Session qBittorrent : connexion par formulaire, cookie de session.
async fn qbit_login(s: &Svc, user: &str, pass: &str) -> R<String> {
    let resp = s
        .raw()
        .post(s.url("/api/v2/auth/login"))
        .header("Referer", s.url(""))
        .form(&[("username", user), ("password", pass)])
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

#[cfg(test)]
mod tests {
    use super::*;

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
