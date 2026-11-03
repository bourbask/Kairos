//! Outils MCP d'action. Chaque écriture se fait en deux temps : sans
//! `confirm`, l'outil décrit ce qu'il ferait et ne touche à rien ; avec
//! `confirm=true`, il exécute. Aucun fichier de la bibliothèque n'est supprimé.

use chrono::{TimeZone, Utc};
use rmcp::{handler::server::wrapper::Parameters, schemars, tool, tool_router};
use serde::Deserialize;

use crate::render::{fold, kind_fr, se, seerr_status, title_matches};
use crate::services::{self as sv, Movie, PlexItem, QueueItem, Series};
use crate::tools::{MediaServer, why};

type Out = Result<String, String>;

const CONFIRM_HINT: &str =
    "Pour exécuter, rappelle cet outil avec confirm=true, seulement après accord explicite de l'utilisateur.";

fn preview(what: &str) -> Out {
    Ok(format!("Aperçu, rien n'est fait : {what}\n{CONFIRM_HINT}"))
}

enum Target {
    Series(Series),
    Movie(Movie),
}

impl Target {
    fn title(&self) -> &str {
        match self {
            Target::Series(s) => &s.title,
            Target::Movie(m) => &m.title,
        }
    }
    fn label(&self) -> String {
        match self {
            Target::Series(s) => format!("la série « {} »", s.title),
            Target::Movie(m) => format!("le film « {} » ({})", m.title, m.year),
        }
    }
}

/// Parmi plusieurs candidats, un titre exact l'emporte.
fn pick<T>(mut found: Vec<(String, T)>, needle: &str) -> Result<T, Vec<String>> {
    if found.len() > 1 {
        let n = fold(needle);
        if let Some(i) = found.iter().position(|(t, _)| fold(t) == n) {
            return Ok(found.swap_remove(i).1);
        }
        return Err(found.into_iter().map(|(t, _)| t).collect());
    }
    found.pop().map(|(_, t)| t).ok_or_else(Vec::new)
}

#[derive(Deserialize, schemars::JsonSchema)]
struct SearchArgs {
    /// Titre ou mots-clés.
    query: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct RequestArgs {
    /// « tv » (série) ou « movie » (film), tel que renvoyé par `search`.
    media_type: String,
    /// Identifiant renvoyé par `search`.
    id: i64,
    /// Saisons voulues (séries) ; absent = toutes.
    seasons: Option<Vec<i64>>,
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct TitleConfirm {
    /// Titre (ou partie du titre) d'une série ou d'un film suivi.
    title: String,
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct RetryArgs {
    title: String,
    /// « version » (nouvelle recherche de téléchargement), « sous-titres », ou absent pour les deux.
    what: Option<String>,
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ControlArgs {
    /// « pause », « reprise », « lent » (débit réduit, torrents) ou « normal ».
    action: String,
    /// « torrent », « usenet » ou absent pour les deux (pause / reprise).
    target: Option<String>,
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct WatchedArgs {
    title: String,
    /// Saison (séries) ; absent = toute la série.
    season: Option<i64>,
    /// Épisode dans la saison.
    episode: Option<i64>,
    /// Vrai (défaut) : marquer vu ; faux : non vu.
    watched: Option<bool>,
    confirm: Option<bool>,
}

impl MediaServer {
    /// Série ou film suivi correspondant au titre, sans ambiguïté.
    async fn resolve(&self, title: &str) -> Result<Target, String> {
        let s = &self.stack;
        if fold(title).chars().count() < 2 {
            return Err("Titre trop court.".to_string());
        }
        let (series, movies) = tokio::join!(s.series(), sv::radarr_movies(&s.radarr));
        let mut found: Vec<(String, Target)> = Vec::new();
        for x in series.unwrap_or_default().into_iter().filter(|x| title_matches(&x.title, title)) {
            found.push((x.title.clone(), Target::Series(x)));
        }
        for m in movies.unwrap_or_default().into_iter().filter(|m| title_matches(&m.title, title)) {
            found.push((m.title.clone(), Target::Movie(m)));
        }
        pick(found, title).map_err(|names| {
            if names.is_empty() {
                format!("Aucune série ni film suivi ne correspond à « {title} ». Pour l'ajouter : search puis request.")
            } else {
                format!("Plusieurs titres correspondent : {}. Précise lequel.", names.join(", "))
            }
        })
    }

    /// Élément du lecteur pour une cible suivie (identifiants externes), sinon par titre.
    async fn plex_item(&self, title: &str) -> Result<PlexItem, String> {
        let s = &self.stack;
        let target = self.resolve(title).await.ok();
        let q = [("includeGuids", "1".to_string())];
        let mut found: Vec<(String, PlexItem)> = Vec::new();
        for kind in ["show", "movie"] {
            for sec in sv::plex_sections(&s.plex, kind).await.map_err(|e| why("Plex", &e))? {
                for i in sv::plex_section_items(&s.plex, &sec, &q, 5000).await.unwrap_or_default() {
                    let by_id = match &target {
                        Some(Target::Series(x)) => i.tvdb() == Some(x.tvdb_id),
                        Some(Target::Movie(m)) => i.tmdb() == Some(m.tmdb_id),
                        None => false,
                    };
                    if by_id {
                        return Ok(i);
                    }
                    if title_matches(&i.title, title) {
                        found.push((i.title.clone(), i));
                    }
                }
            }
        }
        pick(found, title).map_err(|names| {
            if names.is_empty() {
                format!("« {title} » est introuvable dans le lecteur.")
            } else {
                format!("Plusieurs titres correspondent : {}. Précise lequel.", names.join(", "))
            }
        })
    }

    async fn set_monitored(&self, title: &str, on: bool, confirm: bool) -> Out {
        let s = &self.stack;
        let t = self.resolve(title).await?;
        let already = match &t {
            Target::Series(x) => x.monitored == on,
            Target::Movie(m) => m.monitored == on,
        };
        if already {
            return Ok(format!("{} est déjà {}.", t.label(), if on { "suivi(e)" } else { "hors suivi" }));
        }
        let desc = if on {
            format!("reprendre la surveillance de {}", t.label())
        } else {
            format!("arrêter la surveillance de {} (rien n'est supprimé)", t.label())
        };
        if !confirm {
            return preview(&format!("{desc}."));
        }
        let r = match &t {
            Target::Series(x) => sv::arr_set_monitored(&s.sonarr, "series", x.id, on).await,
            Target::Movie(m) => sv::arr_set_monitored(&s.radarr, "movie", m.id, on).await,
        };
        r.map_err(|e| why("gestionnaire", &e))?;
        s.forget_series().await;
        Ok(format!("Fait : {desc}."))
    }

    async fn problem_items(&self, t: &Target) -> Vec<QueueItem> {
        let s = &self.stack;
        let q = match t {
            Target::Series(_) => sv::arr_queue(&s.sonarr).await,
            Target::Movie(_) => sv::arr_queue(&s.radarr).await,
        };
        q.unwrap_or_default()
            .into_iter()
            .filter(|q| q.has_problem())
            .filter(|q| q.series.as_ref().or(q.movie.as_ref()).is_some_and(|x| x.title == t.title()))
            .collect()
    }
}

#[tool_router(router = action_router, vis = "pub(crate)")]
impl MediaServer {
    #[tool(
        description = "Chercher une série ou un film à ajouter (recherche du service de demandes) : renvoie le type, l'identifiant à passer à `request` et l'état (déjà disponible, demandé, non demandé)."
    )]
    async fn search(&self, Parameters(a): Parameters<SearchArgs>) -> Out {
        if fold(&a.query).chars().count() < 2 {
            return Err("Recherche trop courte.".to_string());
        }
        let r = sv::seerr_search(&self.stack.seerr, a.query.trim()).await.map_err(|e| why("Seerr", &e))?;
        if r.is_empty() {
            return Ok(format!("Aucun résultat pour « {} ».", a.query.trim()));
        }
        let lines: Vec<_> = r
            .iter()
            .take(10)
            .map(|x| {
                format!(
                    "- {} — {} — {} — media_type={} id={}",
                    x.label(),
                    kind_fr(&x.media_type),
                    seerr_status(x),
                    x.media_type,
                    x.id
                )
            })
            .collect();
        Ok(format!("Résultats :\n{}", lines.join("\n")))
    }

    #[tool(
        description = "Demander une série ou un film (identifiant issu de `search`). Sans confirm : aperçu (saisons, épisodes), rien n'est fait. Avec confirm=true, seulement après accord explicite : la demande part et elle est suivie jusqu'à ce qu'elle soit regardable (VF ou ST FR) ; une notification signale la fin, l'échec ou le retard."
    )]
    async fn request(&self, Parameters(a): Parameters<RequestArgs>) -> Out {
        let s = &self.stack;
        let mt = match a.media_type.as_str() {
            "tv" | "movie" => a.media_type.as_str(),
            _ => return Err("media_type doit valoir « tv » ou « movie ».".to_string()),
        };
        let d = sv::seerr_details(&s.seerr, mt, a.id).await.map_err(|e| why("Seerr", &e))?;
        let label = d.label();
        if d.media_info.as_ref().is_some_and(|m| m.status == 5) {
            return Err(format!("{label} est déjà disponible."));
        }
        if self.store.open_for(mt, a.id).map_err(|e| e.to_string())?.is_some() {
            return Err(format!("{label} est déjà demandé et suivi."));
        }
        let wanted: Vec<i64> = a.seasons.clone().unwrap_or_default();
        let mut what = format!("demander le film {label}");
        if mt == "tv" {
            let real: Vec<_> = d.seasons.iter().filter(|x| x.season_number > 0).collect();
            if let Some(bad) = wanted.iter().find(|n| !real.iter().any(|x| x.season_number == **n)) {
                let valid: Vec<_> = real.iter().map(|x| x.season_number.to_string()).collect();
                return Err(format!("Saison {bad} inconnue. Saisons : {}.", valid.join(", ")));
            }
            let chosen: Vec<_> =
                real.iter().filter(|x| wanted.is_empty() || wanted.contains(&x.season_number)).collect();
            let eps: i64 = chosen.iter().map(|x| x.episode_count).sum();
            let which = if wanted.is_empty() {
                format!("toutes les saisons ({})", real.len())
            } else {
                format!(
                    "saison(s) {}",
                    chosen.iter().map(|x| x.season_number.to_string()).collect::<Vec<_>>().join(", ")
                )
            };
            what = format!("demander la série {label} : {which}, {eps} épisode(s)");
        }
        if a.confirm != Some(true) {
            return preview(&format!("{what}."));
        }
        let r = sv::seerr_request(&s.seerr, mt, a.id, &wanted).await.map_err(|e| why("Seerr", &e))?;
        let tvdb = d.external_ids.and_then(|e| e.tvdb_id);
        let title = d.name.or(d.title).unwrap_or_else(|| label.clone());
        self.store
            .add(mt, a.id, tvdb, &title, &wanted, Some(r.id), Utc::now().timestamp())
            .map_err(|e| e.to_string())?;
        let note = if s.notifier.is_some() {
            "Je préviens dès que c'est regardable, en cas d'échec ou de retard."
        } else {
            "Suivi actif ; notifications non configurées, l'état se consulte avec `requests`."
        };
        Ok(format!("Fait : {what}. {note}"))
    }

    #[tool(
        description = "Lister les demandes suivies et leur état (en attente, présent sans ST FR, en retard, prêt, échec)."
    )]
    async fn requests(&self) -> Out {
        let all = self.store.list(false).map_err(|e| e.to_string())?;
        if all.is_empty() {
            return Ok("Aucune demande suivie.".to_string());
        }
        let tz = self.stack.tz;
        let lines: Vec<_> = all
            .iter()
            .take(20)
            .map(|r| {
                let since = tz.timestamp_opt(r.created_at, 0).single().map(|d| d.format("%d/%m").to_string());
                let detail = if r.detail.is_empty() { String::new() } else { format!(" — {}", r.detail) };
                format!(
                    "- {} ({}) — {}{detail} — demandé le {}",
                    r.title,
                    kind_fr(&r.media_type),
                    r.state.fr(),
                    since.unwrap_or_default()
                )
            })
            .collect();
        Ok(format!("Demandes :\n{}", lines.join("\n")))
    }

    #[tool(
        description = "Reprendre la surveillance d'une série ou d'un film (nouveaux épisodes, recherche automatique). Sans confirm : aperçu ; confirm=true après accord explicite."
    )]
    async fn follow(&self, Parameters(a): Parameters<TitleConfirm>) -> Out {
        self.set_monitored(&a.title, true, a.confirm == Some(true)).await
    }

    #[tool(
        description = "Arrêter la surveillance d'une série ou d'un film, sans rien supprimer de la bibliothèque. Sans confirm : aperçu ; confirm=true après accord explicite."
    )]
    async fn unfollow(&self, Parameters(a): Parameters<TitleConfirm>) -> Out {
        self.set_monitored(&a.title, false, a.confirm == Some(true)).await
    }

    #[tool(
        description = "Relancer la recherche d'une série ou d'un film suivi : nouvelle version à télécharger, sous-titres français manquants, ou les deux. Sans confirm : aperçu ; confirm=true après accord explicite."
    )]
    async fn retry(&self, Parameters(a): Parameters<RetryArgs>) -> Out {
        let s = &self.stack;
        let t = self.resolve(&a.title).await?;
        let what = a.what.as_deref().map(fold);
        let (release, subs) = match what.as_deref() {
            None | Some("tout" | "les deux") => (true, true),
            Some("version" | "telechargement") => (true, false),
            Some("sous titres" | "soustitres" | "st") => (false, true),
            Some(_) => return Err("what : « version », « sous-titres » ou absent.".to_string()),
        };
        let mut parts = Vec::new();
        if release {
            parts.push("recherche des versions manquantes");
        }
        if subs {
            parts.push("recherche des sous-titres manquants");
        }
        let desc = format!("{} pour {}", parts.join(" et "), t.label());
        if a.confirm != Some(true) {
            return preview(&format!("{desc}."));
        }
        let mut done = Vec::new();
        let mut errs = Vec::new();
        if release {
            let r = match &t {
                Target::Series(x) => {
                    sv::arr_command(&s.sonarr, serde_json::json!({"name": "SeriesSearch", "seriesId": x.id})).await
                }
                Target::Movie(m) => {
                    sv::arr_command(&s.radarr, serde_json::json!({"name": "MoviesSearch", "movieIds": [m.id]})).await
                }
            };
            match r {
                Ok(()) => done.push("versions"),
                Err(e) => errs.push(why("gestionnaire", &e)),
            }
        }
        if subs {
            let r = match &t {
                Target::Series(x) => sv::bazarr_search_missing(&s.bazarr, "series", x.id).await,
                Target::Movie(m) => sv::bazarr_search_missing(&s.bazarr, "movies", m.id).await,
            };
            match r {
                Ok(()) => done.push("sous-titres"),
                Err(e) => errs.push(why("Bazarr", &e)),
            }
        }
        if done.is_empty() {
            return Err(errs.join(" ; "));
        }
        let mut out = format!("Recherche lancée ({}) pour {}.", done.join(", "), t.label());
        if !errs.is_empty() {
            out.push_str(&format!(" ⚠ {}", errs.join(" ; ")));
        }
        Ok(out)
    }

    #[tool(
        description = "Écarter les versions en échec d'un titre dans la file de téléchargement (elles ne seront plus reprises) et relancer une recherche. Ne touche pas à la bibliothèque. Sans confirm : liste ce qui serait écarté ; confirm=true après accord explicite."
    )]
    async fn blocklist_release(&self, Parameters(a): Parameters<TitleConfirm>) -> Out {
        let s = &self.stack;
        let t = self.resolve(&a.title).await?;
        let items = self.problem_items(&t).await;
        if items.is_empty() {
            return Err(format!("Aucun téléchargement en échec pour {}.", t.label()));
        }
        let names: Vec<_> = items.iter().take(10).map(|q| q.label()).collect();
        let desc = format!(
            "écarter {} version(s) en échec de {} ({}{}) et relancer la recherche",
            items.len(),
            t.label(),
            names.join(", "),
            if items.len() > 10 { ", …" } else { "" }
        );
        if a.confirm != Some(true) {
            return preview(&format!("{desc}."));
        }
        let svc = match t {
            Target::Series(_) => &s.sonarr,
            Target::Movie(_) => &s.radarr,
        };
        let mut ok = 0;
        // Une même version peut couvrir plusieurs épisodes : ses doublons disparaissent avec elle.
        let mut seen = std::collections::HashSet::new();
        for q in &items {
            if !seen.insert(q.title.clone()) {
                ok += 1;
                continue;
            }
            if sv::arr_blocklist(svc, q.id).await.is_ok() {
                ok += 1;
            }
        }
        Ok(format!("{ok}/{} élément(s) écarté(s) ; nouvelle recherche lancée.", items.len()))
    }

    #[tool(
        description = "Piloter les téléchargements : « pause », « reprise » (torrents et/ou usenet), « lent » ou « normal » (débit réduit des torrents, ex. pendant une visio). Sans confirm : aperçu ; confirm=true après accord explicite."
    )]
    async fn downloads_control(&self, Parameters(a): Parameters<ControlArgs>) -> Out {
        let s = &self.stack;
        let action = fold(&a.action);
        let target = a.target.as_deref().map(fold);
        let (torrent, usenet) = match target.as_deref() {
            None | Some("tout") => (true, true),
            Some("torrent" | "torrents") => (true, false),
            Some("usenet") => (false, true),
            Some(_) => return Err("target : « torrent », « usenet » ou absent.".to_string()),
        };
        match action.as_str() {
            "pause" | "reprise" => {
                let pause = action == "pause";
                let verb = if pause { "mettre en pause" } else { "relancer" };
                let who = match (torrent, usenet) {
                    (true, true) => "les torrents et l'usenet",
                    (true, false) => "les torrents",
                    _ => "l'usenet",
                };
                if a.confirm != Some(true) {
                    return preview(&format!("{verb} {who}."));
                }
                let mut out = Vec::new();
                if torrent {
                    out.push(match sv::qbit_pause_all(&s.qbit, pause).await {
                        Ok(()) => "torrents : fait".to_string(),
                        Err(e) => why("qBittorrent", &e),
                    });
                }
                if usenet {
                    out.push(match sv::nzbget_pause(&s.nzbget, pause).await {
                        Ok(()) => "usenet : fait".to_string(),
                        Err(e) => why("NZBGet", &e),
                    });
                }
                Ok(format!("{} — {}.", verb[..1].to_uppercase() + &verb[1..], out.join(" ; ")))
            }
            "lent" | "normal" => {
                let want_slow = action == "lent";
                let slow = sv::qbit_slow_mode(&s.qbit).await.map_err(|e| why("qBittorrent", &e))?;
                if slow == want_slow {
                    return Ok(format!("Déjà en débit {}.", if slow { "réduit" } else { "normal" }));
                }
                let desc =
                    if want_slow { "passer les torrents en débit réduit" } else { "rétablir le débit normal" };
                if a.confirm != Some(true) {
                    return preview(&format!("{desc}."));
                }
                sv::qbit_toggle_slow(&s.qbit).await.map_err(|e| why("qBittorrent", &e))?;
                Ok(format!("Fait : {desc}."))
            }
            _ => Err("action : « pause », « reprise », « lent » ou « normal ».".to_string()),
        }
    }

    #[tool(
        description = "Marquer un film, un épisode, une saison ou une série comme vu (ou non vu) dans le lecteur. Sans confirm : aperçu ; confirm=true après accord explicite."
    )]
    async fn mark_watched(&self, Parameters(a): Parameters<WatchedArgs>) -> Out {
        let s = &self.stack;
        let watched = a.watched.unwrap_or(true);
        let verb = if watched { "vu" } else { "non vu" };
        let item = self.plex_item(&a.title).await?;
        if a.episode.is_some() && a.season.is_none() {
            return Err("Précise la saison de l'épisode.".to_string());
        }
        let keys: Vec<(String, String)> = match (item.kind.as_str(), a.season) {
            ("show", Some(season)) => {
                let leaves = sv::plex_all_leaves(&s.plex, &item.rating_key).await.map_err(|e| why("Plex", &e))?;
                leaves
                    .into_iter()
                    .filter(|l| l.parent_index == Some(season) && a.episode.is_none_or(|e| l.index == Some(e)))
                    .map(|l| (l.rating_key.clone(), se(l.parent_index, l.index)))
                    .collect()
            }
            _ => vec![(item.rating_key.clone(), String::new())],
        };
        if keys.is_empty() {
            return Err(format!("Aucun épisode correspondant dans « {} ».", item.title));
        }
        let what = match (item.kind.as_str(), a.season, keys.len()) {
            ("show", None, _) => format!("toute la série « {} »", item.title),
            ("show", Some(_), 1) => format!("« {} » {}", item.title, keys[0].1),
            ("show", Some(sn), n) => format!("« {} » saison {sn} ({n} épisodes)", item.title),
            _ => format!("« {} »", item.title),
        };
        if a.confirm != Some(true) {
            return preview(&format!("marquer {what} comme {verb}."));
        }
        let mut ok = 0;
        for (k, _) in &keys {
            if sv::plex_scrobble(&s.plex, k, watched).await.is_ok() {
                ok += 1;
            }
        }
        if ok == 0 {
            return Err("Plex : échec du marquage.".to_string());
        }
        Ok(format!("Fait : {what} marqué {verb} ({ok}/{}).", keys.len()))
    }
}
