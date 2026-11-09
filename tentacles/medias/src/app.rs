//! Outils `app_*` : vues et gestes JSON de l'application, sur les mêmes lectures
//! que les outils texte. Chaque section est indépendante : un service absent ou
//! en panne vide sa section (`null`) et l'explique dans `errors`.

use chrono::Utc;
use rmcp::{handler::server::wrapper::Parameters, schemars, tool, tool_router};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::http::SvcError;
use crate::render::{clean, fold, minutes, queue_code, queue_reason, timeleft_min};
use crate::services::{self as sv, QueueItem};
use crate::tools::{MediaServer, item_name, why};

type Out = Result<String, String>;
/// Contenu d'une section (`None` → `null`) et raisons à signaler.
type Section = (Option<Value>, Vec<String>);

const PLAYER: &str = "lecteur";
const SERIES_MGR: &str = "gestionnaire de séries";
const FILMS_MGR: &str = "gestionnaire de films";
const TORRENT: &str = "client torrent";
const USENET: &str = "client usenet";
const REQUESTS: &str = "service de demandes";
const UPCOMING_DAYS: i64 = 7;
// ponytail: file bornée, regroupement par œuvre si une saison entière encombre la liste.
const MAX_DOWNLOADS: usize = 30;
const MAX_SEARCH: usize = 10;
const UNKNOWN_ITEM: &str = "Élément introuvable dans le lecteur (déjà retiré).";

fn json_out(v: Value) -> Out {
    serde_json::to_string(&v).map_err(|e| e.to_string())
}

fn message(m: impl Into<String>) -> Out {
    json_out(json!({ "message": m.into() }))
}

/// Message à afficher et issue lisible par l'application (`done`, `already`, `gone`).
fn outcome(m: impl Into<String>, outcome: &str) -> Out {
    json_out(json!({ "message": m.into(), "outcome": outcome }))
}

#[derive(Deserialize, schemars::JsonSchema)]
struct KeyArgs {
    /// Clé de l'élément renvoyée par `app_medias`.
    key: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ActionArgs {
    /// « pause » ou « reprise ».
    action: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct QueryArgs {
    query: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppRequestArgs {
    id: i64,
    /// « tv » ou « movie ».
    media_type: String,
}

fn download_json(q: &QueueItem) -> Value {
    let state = queue_code(q);
    let progress = if q.size > 0.0 { ((q.size - q.sizeleft) / q.size).clamp(0.0, 1.0) } else { 0.0 };
    let mut v = json!({"title": clean(&q.label()), "progress": (progress * 100.0).round() / 100.0, "state": state});
    if let Some(m) = q.timeleft.as_deref().and_then(timeleft_min) {
        v["eta_min"] = json!(m);
    }
    if matches!(state, "bloque" | "erreur") {
        v["why"] = json!(clean(&queue_reason(q)));
    }
    v
}

impl MediaServer {
    async fn app_continue(&self) -> Section {
        match sv::plex_on_deck(&self.stack.plex).await {
            Ok(items) => {
                let v: Vec<Value> = items
                    .iter()
                    .map(|i| {
                        let mut v = json!({"key": clean(&i.rating_key), "title": clean(&item_name(i))});
                        let left = match (i.view_offset, i.duration) {
                            (Some(o), Some(d)) if o > 0 => minutes(Some(d - o)),
                            (_, d) => minutes(d),
                        };
                        if let Some(m) = left {
                            v["left_min"] = json!(m);
                        }
                        v
                    })
                    .collect();
                (Some(json!(v)), vec![])
            }
            Err(e) => (None, vec![why(PLAYER, &e)]),
        }
    }

    async fn app_ready(&self) -> Section {
        match self.ready(None, true, true, 8).await {
            Ok(r) => {
                let v: Vec<Value> = r
                    .items
                    .iter()
                    .map(|i| {
                        let mut v =
                            json!({"key": clean(&i.key), "title": clean(&i.title), "kind": i.kind, "subs": i.subs});
                        if let Some(m) = i.minutes {
                            v["minutes"] = json!(m);
                        }
                        v
                    })
                    .collect();
                (Some(json!(v)), vec![])
            }
            Err(e) => (None, vec![why(PLAYER, &e)]),
        }
    }

    async fn app_downloads(&self) -> Section {
        let s = &self.stack;
        let (a, b, t, n) = tokio::join!(
            sv::arr_queue(&s.sonarr),
            sv::arr_queue(&s.radarr),
            sv::qbit_torrents(&s.qbit),
            sv::nzbget_status(&s.nzbget)
        );
        let mut items = Vec::new();
        let mut failed: Vec<(&str, SvcError)> = Vec::new();
        let mut any = false;
        for (name, r) in [(SERIES_MGR, a), (FILMS_MGR, b)] {
            match r {
                Ok(v) => {
                    any = true;
                    items.extend(v);
                }
                Err(e) => failed.push((name, e)),
            }
        }
        // Signal de pause de chaque client joignable ; aucun torrent = aucun signal.
        let mut signals = Vec::new();
        match t {
            Ok(v) if !v.is_empty() => signals.push(
                v.iter().all(|t| matches!(t.state.as_str(), "pausedDL" | "pausedUP" | "stoppedDL" | "stoppedUP")),
            ),
            Ok(_) => {}
            Err(e) => failed.push((TORRENT, e)),
        }
        match n {
            Ok(n) => signals.push(n.download_paused),
            Err(e) => failed.push((USENET, e)),
        }
        let errors = failed
            .iter()
            .filter(|(_, e)| !any || *e != SvcError::NotConfigured)
            .map(|(name, e)| why(name, e))
            .collect();
        if !any {
            return (None, errors);
        }
        // Problèmes d'abord, puis le plus avancé.
        let rank = |q: &QueueItem| match queue_code(q) {
            "erreur" | "bloque" => 0,
            "en_cours" => 1,
            _ => 2,
        };
        items.sort_by(|x, y| rank(x).cmp(&rank(y)).then((y.size - y.sizeleft).total_cmp(&(x.size - x.sizeleft))));
        let list: Vec<Value> = items.iter().take(MAX_DOWNLOADS).map(download_json).collect();
        let paused = !signals.is_empty() && signals.iter().all(|p| *p);
        (Some(json!({"items": list, "paused": paused})), errors)
    }

    async fn app_upcoming(&self) -> Section {
        let tz = self.stack.tz;
        let (eps, films) = self.releases(UPCOMING_DAYS, Utc::now()).await;
        let mut all = Vec::new();
        let mut failed = Vec::new();
        for (name, r) in [(SERIES_MGR, eps), (FILMS_MGR, films)] {
            match r {
                Ok(v) => all.push(v),
                Err(e) => failed.push((name, e)),
            }
        }
        let errors = failed
            .iter()
            .filter(|(_, e)| all.is_empty() || *e != SvcError::NotConfigured)
            .map(|(name, e)| why(name, e))
            .collect();
        if all.is_empty() {
            return (None, errors);
        }
        let mut rows: Vec<_> = all.into_iter().flatten().collect();
        rows.sort_by_key(|r| (r.day, r.at));
        let v: Vec<Value> = rows
            .iter()
            .map(|r| {
                let mut v = json!({"day": r.day.format("%Y-%m-%d").to_string(), "title": clean(&r.title),
                    "what": r.what, "have": r.have});
                if let Some(t) = r.at {
                    v["time"] = json!(t.with_timezone(&tz).format("%H:%M").to_string());
                }
                v
            })
            .collect();
        (Some(json!(v)), errors)
    }

    fn app_requests(&self) -> Section {
        match self.store.list(false) {
            Ok(all) => {
                let v: Vec<Value> =
                    all.iter().take(20).map(|r| json!({"title": clean(&r.title), "state": r.state.fr()})).collect();
                (Some(json!(v)), vec![])
            }
            Err(_) => (None, vec!["suivi des demandes indisponible".to_string()]),
        }
    }
}

#[tool_router(router = app_router, vis = "pub(crate)")]
impl MediaServer {
    #[tool(
        description = "Vue JSON de l'espace médias pour l'application : reprises, prêts à regarder, téléchargements, sorties à venir, demandes."
    )]
    async fn app_medias(&self) -> Out {
        let (cont, ready, dl, up) =
            tokio::join!(self.app_continue(), self.app_ready(), self.app_downloads(), self.app_upcoming());
        let mut errors = Vec::new();
        let mut out = json!({});
        for (name, (v, errs)) in [
            ("continue", cont),
            ("ready", ready),
            ("downloads", dl),
            ("upcoming", up),
            ("requests", self.app_requests()),
        ] {
            out[name] = v.unwrap_or(Value::Null);
            errors.extend(errs.into_iter().map(|m| json!({"section": name, "message": m})));
        }
        out["errors"] = json!(errors);
        json_out(out)
    }

    #[tool(
        description = "Marquer vu un élément du lecteur depuis l'application, par sa clé. Rend `message` (à afficher) et `outcome` : `done` (marqué vu), `already` (déjà vu), `gone` (inconnu du lecteur). Lecteur injoignable : erreur."
    )]
    async fn app_media_watched(&self, Parameters(a): Parameters<KeyArgs>) -> Out {
        let p = &self.stack.plex;
        let key = a.key.trim();
        // La clé finit dans un chemin d'URL : chiffres seulement.
        if key.is_empty() || !key.bytes().all(|b| b.is_ascii_digit()) {
            return outcome(UNKNOWN_ITEM, "gone");
        }
        let item = match sv::plex_metadata(p, key).await {
            Ok(i) => i,
            Err(SvcError::NotFound) => return outcome(UNKNOWN_ITEM, "gone"),
            Err(e) => return Err(why(PLAYER, &e)),
        };
        let name = clean(&item_name(&item));
        if item.view_count.unwrap_or(0) > 0 {
            return outcome(format!("{name} est déjà vu."), "already");
        }
        sv::plex_scrobble(p, key, true).await.map_err(|e| why(PLAYER, &e))?;
        outcome(format!("{name} marqué vu."), "done")
    }

    #[tool(description = "Mettre en pause ou relancer tous les téléchargements depuis l'application.")]
    async fn app_downloads_control(&self, Parameters(a): Parameters<ActionArgs>) -> Out {
        let s = &self.stack;
        let pause = match a.action.as_str() {
            "pause" => true,
            "reprise" => false,
            _ => return Err("action attendue : pause ou reprise".to_string()),
        };
        let (t, n) = tokio::join!(sv::qbit_pause_all(&s.qbit, pause), sv::nzbget_pause(&s.nzbget, pause));
        let mut done = 0;
        let mut failed = Vec::new();
        for (name, r) in [(TORRENT, t), (USENET, n)] {
            match r {
                Ok(()) => done += 1,
                Err(SvcError::NotConfigured) => {}
                Err(e) => failed.push(why(name, &e)),
            }
        }
        if done == 0 {
            return Err(if failed.is_empty() {
                "Aucun client de téléchargement configuré.".to_string()
            } else {
                failed.join(" ; ")
            });
        }
        let mut m = if pause { "Téléchargements en pause." } else { "Téléchargements relancés." }.to_string();
        if !failed.is_empty() {
            m.push_str(&format!(" ⚠ {}.", failed.join(" ; ")));
        }
        message(m)
    }

    #[tool(description = "Chercher une série ou un film à demander, depuis l'application (10 résultats au plus).")]
    async fn app_media_search(&self, Parameters(a): Parameters<QueryArgs>) -> Out {
        let q = a.query.trim();
        if fold(q).chars().count() < 2 {
            return json_out(json!([]));
        }
        let found = sv::seerr_search(&self.stack.seerr, q).await.map_err(|e| why(REQUESTS, &e))?;
        let open = self.store.list(true).map_err(|e| e.to_string())?;
        let v: Vec<Value> = found
            .iter()
            .take(MAX_SEARCH)
            .map(|r| {
                let status = r.media_info.as_ref().map(|m| m.status);
                let tracked = open.iter().any(|o| o.media_type == r.media_type && o.tmdb_id == r.id);
                let title = r.name.as_deref().or(r.title.as_deref()).unwrap_or_default();
                let mut v = json!({"id": r.id, "media_type": r.media_type, "title": clean(title),
                    "have": status == Some(5), "requested": tracked || matches!(status, Some(2 | 3))});
                let date = r.first_air_date.as_deref().or(r.release_date.as_deref()).unwrap_or("");
                if let Some(y) = date.get(..4).and_then(|y| y.parse::<i64>().ok()) {
                    v["year"] = json!(y);
                }
                v
            })
            .collect();
        json_out(json!(v))
    }

    #[tool(description = "Demander une série ou un film depuis l'application, avec l'identifiant de la recherche.")]
    async fn app_media_request(&self, Parameters(a): Parameters<AppRequestArgs>) -> Out {
        let mt = a.media_type.as_str();
        if !matches!(mt, "tv" | "movie") {
            return Err("media_type attendu : tv ou movie".to_string());
        }
        let d = sv::seerr_details(&self.stack.seerr, mt, a.id).await.map_err(|e| why(REQUESTS, &e))?;
        let label = clean(&d.label());
        let status = d.media_info.as_ref().map(|m| m.status);
        if status == Some(5) {
            return message(format!("{label} est déjà disponible."));
        }
        let tracked = self.store.open_for(mt, a.id).map_err(|e| e.to_string())?.is_some();
        if tracked || matches!(status, Some(2 | 3)) {
            return message(format!("{label} est déjà demandé."));
        }
        self.send_request(mt, a.id, d, &[], REQUESTS).await?;
        message(format!("{label} demandé ; suivi jusqu'à ce qu'il soit regardable."))
    }
}
