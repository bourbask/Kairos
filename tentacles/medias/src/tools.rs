//! Outils MCP de lecture du tentacule médias, par intention (actions : `actions.rs`).
//!
//! Chaque outil interroge plusieurs services et renvoie un texte déjà digéré.
//! Un service absent ou injoignable dégrade la réponse sans la faire échouer.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use chrono::{Duration, NaiveDate, Utc};
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use tentacle_common::BriefPeriod;
use tokio::sync::OnceCell;

use crate::config::Stack;
use crate::http::SvcError;
use crate::render::{
    Subs, audio_fr, day, fold, go, kind_fr, local_date, minutes, next_episode, parse_utc, plex_link, progress,
    protocol_fr, queue_reason, queue_state, se, seerr_status, subs_label, subs_of, timeleft, title_matches, watchable,
    when,
};
use crate::services::{self as sv, PlexItem, QueueItem};
use crate::store::Store;

type Out = Result<String, String>;

/// Fenêtre au-delà de laquelle une série n'est plus « en cours » (jours sans visionnage).
const IN_PROGRESS_DAYS: i64 = 120;

pub(crate) fn why(name: &str, e: &SvcError) -> String {
    match e {
        SvcError::NotConfigured => format!("{name} non configuré"),
        SvcError::Unavailable => format!("{name} injoignable"),
        SvcError::Denied => format!("{name} : accès refusé"),
    }
}

/// Handler MCP ; bon marché à construire (la stack est partagée).
#[derive(Clone)]
pub struct MediaServer {
    pub(crate) stack: Arc<Stack>,
    pub(crate) store: Arc<Store>,
    machine: Arc<OnceCell<String>>,
    tool_router: ToolRouter<Self>,
}

#[derive(Default)]
struct Ready {
    /// Regardables (VF ou ST FR complets).
    ok: Vec<String>,
    /// Présents mais sans sous-titres français exploitables.
    blocked: Vec<String>,
    movies: Vec<String>,
    /// Titres des séries regardables, pour le brief.
    ok_titles: Vec<String>,
    /// Écartés par le filtre de durée.
    too_long: usize,
    notes: Vec<String>,
}

impl MediaServer {
    pub fn new(stack: Arc<Stack>, store: Arc<Store>, machine: Arc<OnceCell<String>>) -> Self {
        Self {
            stack,
            store,
            machine,
            tool_router: Self::tool_router() + Self::action_router() + Self::library_router(),
        }
    }

    async fn link(&self, rating_key: &str) -> String {
        let mid = self.machine.get_or_try_init(|| sv::plex_machine_id(&self.stack.plex)).await;
        match mid {
            Ok(m) => format!(" — {}", plex_link(m, rating_key)),
            Err(_) => String::new(),
        }
    }

    /// Épisodes que Bazarr cherche encore : (id série Sonarr, saison, épisode), et tvdb → id Sonarr.
    async fn wanted_subs(&self) -> (HashSet<(i64, i64, i64)>, HashMap<i64, i64>) {
        let s = &self.stack;
        let (w, series) = tokio::join!(sv::bazarr_wanted_episodes(&s.bazarr), s.series());
        let wanted = w
            .map(|w| {
                w.data.iter().filter_map(|e| e.season_episode().map(|(a, b)| (e.sonarr_series_id, a, b))).collect()
            })
            .unwrap_or_default();
        let ids = series.map(|v| v.iter().map(|x| (x.tvdb_id, x.id)).collect()).unwrap_or_default();
        (wanted, ids)
    }

    async fn ready(&self, max_min: Option<i64>, shows: bool, movies: bool, limit: usize) -> Result<Ready, String> {
        let p = &self.stack.plex;
        let mut out = Ready::default();
        let cutoff = (Utc::now() - Duration::days(IN_PROGRESS_DAYS)).timestamp();
        let fits = |m: Option<i64>| max_min.is_none_or(|max| m.is_some_and(|m| m <= max));

        if shows {
            let sections = sv::plex_sections(p, "show").await.map_err(|e| why("Plex", &e))?;
            let (wanted, tvdb_to_sonarr) = self.wanted_subs().await;
            let q = [("sort", "lastViewedAt:desc".to_string())];
            let mut in_progress: Vec<PlexItem> = Vec::new();
            for sec in &sections {
                let items = sv::plex_section_items(p, sec, &q, 60).await.map_err(|e| why("Plex", &e))?;
                in_progress.extend(items.into_iter().filter(|s| {
                    let (seen, all) = (s.viewed_leaf_count.unwrap_or(0), s.leaf_count.unwrap_or(0));
                    seen > 0 && all > seen && s.last_viewed_at.unwrap_or(0) >= cutoff
                }));
            }
            in_progress.sort_by_key(|s| -s.last_viewed_at.unwrap_or(0));
            for show in in_progress.into_iter().take(limit) {
                let Ok(leaves) = sv::plex_unwatched_leaves(p, &show.rating_key).await else { continue };
                let Some((ep, left)) = next_episode(leaves) else { continue };
                let full = sv::plex_metadata(p, &ep.rating_key).await.unwrap_or(ep);
                let vf = audio_fr(&full);
                let mut subs = subs_of(&full, false);
                if subs != Subs::Fr && !vf && !wanted.is_empty() {
                    let tvdb = sv::plex_metadata(p, &show.rating_key).await.ok().and_then(|m| m.tvdb());
                    let sid = tvdb.and_then(|t| tvdb_to_sonarr.get(&t).copied());
                    let key = sid.map(|id| (id, full.parent_index.unwrap_or(0), full.index.unwrap_or(0)));
                    subs = subs_of(&full, key.is_some_and(|k| wanted.contains(&k)));
                }
                let mins = minutes(full.duration);
                if !fits(mins) {
                    out.too_long += 1;
                    continue;
                }
                let line = format!(
                    "- {} {} « {} »{} — {} — {} non vu(s){}",
                    show.title,
                    se(full.parent_index, full.index),
                    full.title,
                    mins.map(|m| format!(" — {m} min")).unwrap_or_default(),
                    subs_label(subs, vf),
                    left,
                    self.link(&full.rating_key).await,
                );
                if watchable(subs, vf) {
                    out.ok.push(line);
                    out.ok_titles.push(show.title.clone());
                } else {
                    out.blocked.push(line);
                }
            }
        }

        if movies {
            match sv::plex_sections(p, "movie").await {
                Ok(secs) => {
                    let q = [("unwatched", "1".to_string()), ("sort", "addedAt:desc".to_string())];
                    for sec in &secs {
                        let Ok(items) = sv::plex_section_items(p, sec, &q, 5).await else { continue };
                        for m in items {
                            let full = sv::plex_metadata(p, &m.rating_key).await.unwrap_or(m);
                            let mins = minutes(full.duration);
                            if !fits(mins) {
                                continue;
                            }
                            let vf = audio_fr(&full);
                            let subs = subs_of(&full, false);
                            out.movies.push(format!(
                                "- {}{}{} — {}{}",
                                full.title,
                                full.year.map(|y| format!(" ({y})")).unwrap_or_default(),
                                mins.map(|m| format!(" — {m} min")).unwrap_or_default(),
                                subs_label(subs, vf),
                                self.link(&full.rating_key).await,
                            ));
                        }
                    }
                }
                Err(e) => out.notes.push(why("Plex", &e)),
            }
        }
        Ok(out)
    }

    async fn disk_lines(&self) -> (Vec<String>, bool) {
        let s = &self.stack;
        let (a, b) = tokio::join!(sv::arr_rootfolders(&s.sonarr), sv::arr_rootfolders(&s.radarr));
        let mut seen = HashSet::new();
        let mut low = false;
        let mut lines = Vec::new();
        for f in a.unwrap_or_default().into_iter().chain(b.unwrap_or_default()) {
            let Some(free) = f.free_space else { continue };
            if !seen.insert(f.path.clone()) {
                continue;
            }
            let free_gb = free / 1_073_741_824;
            let warn = free_gb < s.disk_min_gb;
            low |= warn;
            lines.push(format!("{}{} : {} Go libres", if warn { "⚠ " } else { "" }, f.path, free_gb));
        }
        (lines, low)
    }

    async fn queues(&self) -> (Vec<QueueItem>, Vec<String>) {
        let s = &self.stack;
        let (a, b) = tokio::join!(sv::arr_queue(&s.sonarr), sv::arr_queue(&s.radarr));
        let mut notes = Vec::new();
        let mut items = Vec::new();
        for (name, r) in [("Sonarr", a), ("Radarr", b)] {
            match r {
                Ok(v) => items.extend(v),
                Err(SvcError::NotConfigured) => {}
                Err(e) => notes.push(why(name, &e)),
            }
        }
        (items, notes)
    }
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ReadyArgs {
    /// Durée maximale d'un épisode ou film, en minutes (ex. temps libre ce soir).
    max_minutes: Option<i64>,
    /// « series », « films » ou absent pour les deux.
    kind: Option<String>,
    /// Nombre maximal de séries examinées (8 par défaut).
    limit: Option<usize>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct UpcomingArgs {
    /// Horizon en jours (7 par défaut, 60 au plus).
    days: Option<i64>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct TitleArgs {
    /// Titre (ou partie du titre) d'une série ou d'un film.
    title: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct BriefArgs {
    /// Contrat commun des sections de brief ; le contenu médias est le même matin et soir.
    #[allow(dead_code)]
    period: BriefPeriod,
}

/// Regroupe les échecs d'une même œuvre pour une même raison : « Titre S02E01 → S02E09 (9) : raison ».
fn group_problems(items: &[&QueueItem]) -> Vec<String> {
    group_queue(items, queue_reason)
}

/// Regroupe les éléments de file d'une même œuvre partageant le même texte d'état.
fn group_queue(items: &[&QueueItem], describe: impl Fn(&QueueItem) -> String) -> Vec<String> {
    let mut groups: Vec<(String, String, Vec<String>)> = Vec::new();
    for q in items {
        let work = q.series.as_ref().or(q.movie.as_ref()).map(|t| t.title.clone()).unwrap_or_else(|| q.label());
        let reason = describe(q);
        let ep = q.episode.as_ref().map(|e| se(Some(e.season_number), Some(e.episode_number)));
        match groups.iter_mut().find(|g| g.0 == work && g.1 == reason) {
            Some(g) => g.2.extend(ep),
            None => groups.push((work, reason, ep.into_iter().collect())),
        }
    }
    groups
        .into_iter()
        .map(|(work, reason, mut eps)| {
            eps.sort();
            let which = match eps.as_slice() {
                [] => String::new(),
                [one] => format!(" {one}"),
                [first, .., last] => format!(" {first} → {last} ({})", eps.len()),
            };
            format!("{work}{which} : {reason}")
        })
        .collect()
}

fn speed(bps: i64) -> String {
    format!("{:.1} Mo/s", bps as f64 / 1_048_576.0).replace('.', ",")
}

fn connection_fr(s: &str) -> &'static str {
    match s {
        "connected" => "connecté",
        "firewalled" => "connecté, ports fermés",
        "disconnected" => "⚠ déconnecté (tunnel VPN ?)",
        _ => "état inconnu",
    }
}

#[tool_router]
impl MediaServer {
    #[tool(
        description = "Lister ce qui est prêt à regarder : prochain épisode des séries en cours et films récents non vus, avec l'état des sous-titres français (ST FR, VF, absents, recherche en cours) et un lien Plex. Filtre facultatif sur la durée maximale."
    )]
    async fn ready_to_watch(&self, Parameters(a): Parameters<ReadyArgs>) -> Out {
        let kind = a.kind.as_deref().map(fold);
        let shows = !matches!(kind.as_deref(), Some("films" | "film" | "movies"));
        let movies = !matches!(kind.as_deref(), Some("series" | "serie" | "shows"));
        let r = self.ready(a.max_minutes, shows, movies, a.limit.unwrap_or(8).clamp(1, 30)).await?;
        let mut out = Vec::new();
        if shows {
            out.push(if r.ok.is_empty() && r.blocked.is_empty() && r.too_long > 0 {
                format!("Aucun épisode assez court ({} écarté(s) par la durée maximale).", r.too_long)
            } else if r.ok.is_empty() && r.blocked.is_empty() {
                format!(
                    "À jour : aucun épisode non vu dans les séries regardées ces {IN_PROGRESS_DAYS} derniers jours."
                )
            } else if r.ok.is_empty() {
                "Aucun épisode regardable dans les séries en cours.".to_string()
            } else {
                format!("Prêts à regarder :\n{}", r.ok.join("\n"))
            });
            if !r.blocked.is_empty() {
                out.push(format!("Disponibles mais sans sous-titres français :\n{}", r.blocked.join("\n")));
            }
        }
        if movies && !r.movies.is_empty() {
            out.push(format!("Films récents non vus :\n{}", r.movies.join("\n")));
        }
        out.extend(r.notes);
        Ok(out.join("\n\n"))
    }

    #[tool(
        description = "Lister ce qui est en cours de visionnage dans Plex (reprise d'un film ou d'un épisode, épisode suivant), avec le temps restant et un lien Plex."
    )]
    async fn continue_watching(&self) -> Out {
        let items = sv::plex_on_deck(&self.stack.plex).await.map_err(|e| why("Plex", &e))?;
        if items.is_empty() {
            return Ok("Rien en cours dans Plex.".to_string());
        }
        let mut lines = Vec::new();
        for i in items {
            let name = match i.kind.as_str() {
                "episode" => format!(
                    "{} {} « {} »",
                    i.grandparent_title.clone().unwrap_or_default(),
                    se(i.parent_index, i.index),
                    i.title
                ),
                _ => format!("{}{}", i.title, i.year.map(|y| format!(" ({y})")).unwrap_or_default()),
            };
            let state = match (i.view_offset, i.duration) {
                (Some(o), Some(d)) if o > 0 => format!(
                    "reprise à {} min, reste {} min",
                    minutes(Some(o)).unwrap_or(0),
                    minutes(Some(d - o)).unwrap_or(0)
                ),
                (_, d) => format!("à commencer{}", minutes(d).map(|m| format!(", {m} min")).unwrap_or_default()),
            };
            lines.push(format!("- {name} — {state}{}", self.link(&i.rating_key).await));
        }
        Ok(format!("En cours :\n{}", lines.join("\n")))
    }

    #[tool(
        description = "Donner l'agenda des prochaines sorties suivies : épisodes des séries surveillées (heure locale) et sorties de films (cinéma, numérique, physique)."
    )]
    async fn upcoming(&self, Parameters(a): Parameters<UpcomingArgs>) -> Out {
        let s = &self.stack;
        let days = a.days.unwrap_or(7).clamp(1, 60);
        let now = Utc::now();
        let (start, end) =
            (now.format("%Y-%m-%d").to_string(), (now + Duration::days(days)).format("%Y-%m-%d").to_string());
        let (eps, films) =
            tokio::join!(sv::sonarr_calendar(&s.sonarr, &start, &end), sv::radarr_calendar(&s.radarr, &start, &end));
        let mut out = Vec::new();
        match eps {
            Ok(mut v) => {
                v.retain(|e| e.monitored);
                v.sort_by(|a, b| a.air_date_utc.cmp(&b.air_date_utc));
                let lines: Vec<_> = v
                    .iter()
                    .filter_map(|e| {
                        let t = parse_utc(e.air_date_utc.as_deref()?)?;
                        (t >= now - Duration::hours(12)).then(|| {
                            format!(
                                "- {} — {} {}{}",
                                when(t, now, s.tz),
                                e.series.as_ref().map(|x| x.title.as_str()).unwrap_or("?"),
                                se(Some(e.season_number), Some(e.episode_number)),
                                if e.has_file { " (déjà là)" } else { "" }
                            )
                        })
                    })
                    .collect();
                out.push(if lines.is_empty() {
                    format!("Aucun épisode suivi d'ici {days} jours.")
                } else {
                    format!("Épisodes ({days} j) :\n{}", lines.join("\n"))
                });
            }
            Err(SvcError::NotConfigured) => {}
            Err(e) => out.push(why("Sonarr", &e)),
        }
        match films {
            Ok(v) => {
                let today = now.with_timezone(&s.tz).date_naive();
                let horizon = today + Duration::days(days);
                let mut rows: Vec<(NaiveDate, String)> = Vec::new();
                for m in v.iter().filter(|m| m.monitored) {
                    for (date, what) in [
                        (&m.in_cinemas, "au cinéma"),
                        (&m.digital_release, "sortie numérique"),
                        (&m.physical_release, "sortie physique"),
                    ] {
                        let Some(d) = date.as_deref().and_then(|d| local_date(d, s.tz)) else { continue };
                        if d >= today && d <= horizon {
                            let have = if m.has_file { " (déjà là)" } else { "" };
                            rows.push((d, format!("- {} — {} : {what}{have}", day(d, today), m.title)));
                        }
                    }
                }
                rows.sort();
                if !rows.is_empty() {
                    out.push(format!("Films :\n{}", rows.into_iter().map(|r| r.1).collect::<Vec<_>>().join("\n")));
                }
            }
            Err(SvcError::NotConfigured) => {}
            Err(e) => out.push(why("Radarr", &e)),
        }
        if out.is_empty() {
            return Err("Aucun service de suivi configuré.".to_string());
        }
        Ok(out.join("\n\n"))
    }

    #[tool(
        description = "Dire où en sont les téléchargements : file des séries et films (avancement, temps restant, éléments bloqués et pourquoi), état des clients torrent et usenet."
    )]
    async fn downloads(&self) -> Out {
        let s = &self.stack;
        let ((queue, mut notes), torrents, transfer, nzb) = tokio::join!(
            self.queues(),
            sv::qbit_torrents(&s.qbit),
            sv::qbit_transfer(&s.qbit),
            sv::nzbget_status(&s.nzbget)
        );
        let mut out = Vec::new();
        out.push(format!("File : {} élément(s).", queue.len()));
        match (&torrents, &transfer) {
            (Ok(t), Ok(x)) => {
                let active: Vec<_> = t.iter().filter(|t| t.progress < 1.0).collect();
                let count = |states: &[&str]| active.iter().filter(|t| states.contains(&t.state.as_str())).count();
                out.push(format!(
                    "Torrents : {} en cours, {} bloqués, {} en attente, {} en pause, {} en erreur — {} — {}.",
                    count(&["downloading", "forcedDL"]),
                    count(&["stalledDL", "metaDL"]),
                    count(&["queuedDL", "checkingDL"]),
                    count(&["pausedDL", "stoppedDL"]),
                    count(&["error", "missingFiles"]),
                    speed(x.dl_info_speed),
                    connection_fr(&x.connection_status)
                ));
            }
            (Err(SvcError::NotConfigured), _) => {}
            (Err(e), _) | (_, Err(e)) => notes.push(why("qBittorrent", e)),
        }
        match &nzb {
            Ok(n) => out.push(format!(
                "Usenet : {}{} restants.",
                if n.download_paused { "⚠ en pause — " } else { "" },
                go(n.remaining_size_mb as f64 * 1_048_576.0)
            )),
            Err(SvcError::NotConfigured) => {}
            Err(e) => notes.push(why("NZBGet", e)),
        }
        let problems: Vec<_> = queue.iter().filter(|q| q.has_problem()).collect();
        if !problems.is_empty() {
            let lines: Vec<_> = group_problems(&problems).into_iter().take(10).map(|l| format!("- {l}")).collect();
            out.push(format!("À regarder ({}) :\n{}", problems.len(), lines.join("\n")));
        }
        let mut active: Vec<_> = queue.iter().filter(|q| !q.has_problem() && queue_state(q) == "en cours").collect();
        active.sort_by_key(|q| -progress(q).unwrap_or(0));
        if !active.is_empty() {
            let lines: Vec<_> = active
                .iter()
                .take(10)
                .map(|q| {
                    format!(
                        "- {} — {}{} ({})",
                        q.label(),
                        progress(q).map(|p| format!("{p} %")).unwrap_or_else(|| "?".into()),
                        q.timeleft.as_deref().and_then(timeleft).map(|t| format!(", reste {t}")).unwrap_or_default(),
                        protocol_fr(&q.protocol)
                    )
                })
                .collect();
            out.push(format!("En cours :\n{}", lines.join("\n")));
        }
        let mut waiting: HashMap<&str, usize> = HashMap::new();
        for q in queue.iter().filter(|q| !q.has_problem() && queue_state(q) != "en cours") {
            *waiting.entry(queue_state(q)).or_default() += 1;
        }
        if !waiting.is_empty() {
            let mut w: Vec<_> = waiting.into_iter().map(|(k, v)| format!("{v} {k}")).collect();
            w.sort();
            out.push(format!("Ailleurs dans la file : {}.", w.join(", ")));
        }
        out.extend(notes);
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Dire où en est un titre (série ou film) et pourquoi il n'est pas encore disponible : suivi, épisodes présents, prochain épisode, file de téléchargement, sous-titres manquants, présence dans Plex ; sinon, s'il peut être demandé."
    )]
    async fn media_status(&self, Parameters(a): Parameters<TitleArgs>) -> Out {
        let s = &self.stack;
        let needle = a.title.trim();
        if fold(needle).chars().count() < 2 {
            return Err("Titre trop court.".to_string());
        }
        let now = Utc::now();
        let (series, movies, (queue, mut notes), wanted, nzb) = tokio::join!(
            s.series(),
            sv::radarr_movies(&s.radarr),
            self.queues(),
            sv::bazarr_wanted_episodes(&s.bazarr),
            sv::nzbget_status(&s.nzbget)
        );
        let usenet_paused = nzb.as_ref().is_ok_and(|n| n.download_paused);
        let mut out = Vec::new();
        // Titres retrouvés côté gestionnaires : Plex peut afficher un autre titre (traduction).
        // Identifiants externes des œuvres retrouvées : Plex peut les afficher sous un autre titre.
        let (mut tvdb_ids, mut tmdb_ids): (Vec<i64>, Vec<i64>) = (Vec::new(), Vec::new());
        let queue_for = |title: &str| -> Vec<&QueueItem> {
            queue
                .iter()
                .filter(|q| {
                    q.series.as_ref().map(|x| x.title.as_str()) == Some(title)
                        || q.movie.as_ref().map(|x| x.title.as_str()) == Some(title)
                })
                .collect()
        };
        let queue_lines = |items: &[&QueueItem]| -> Vec<String> {
            let mut l: Vec<String> = group_queue(items, |q| {
                let mut t = format!("{} ({})", queue_state(q), protocol_fr(&q.protocol));
                if q.has_problem() {
                    t.push_str(&format!(" — {}", queue_reason(q)));
                }
                t
            })
            .into_iter()
            .map(|g| format!("  · {g}"))
            .collect();
            if l.len() > 8 {
                let more = l.len() - 8;
                l.truncate(8);
                l.push(format!("  · … et {more} autre(s)"));
            }
            if usenet_paused && items.iter().any(|q| q.protocol == "usenet" && q.status == "paused") {
                l.push("  ⚠ Le client usenet est en pause : ces éléments n'avancent pas.".to_string());
            }
            l
        };

        match &series {
            Ok(v) => {
                for x in v.iter().filter(|x| title_matches(&x.title, needle)).take(3) {
                    tvdb_ids.push(x.tvdb_id);
                    let st = &x.statistics;
                    let mut l = vec![format!(
                        "Série « {} » — {}, {} — {}/{} épisodes présents.",
                        x.title,
                        if x.monitored { "suivie" } else { "non suivie" },
                        if x.status == "ended" { "terminée" } else { "en cours de diffusion" },
                        st.episode_file_count,
                        st.episode_count
                    )];
                    if let Some(t) = x.next_airing.as_deref().and_then(parse_utc) {
                        l.push(format!("Prochain épisode : {}.", when(t, now, s.tz)));
                    }
                    let q = queue_for(&x.title);
                    if !q.is_empty() {
                        l.push(format!("Dans la file ({}) :", q.len()));
                        l.extend(queue_lines(&q));
                    }
                    if let Ok(w) = &wanted {
                        let n = w.data.iter().filter(|e| e.sonarr_series_id == x.id).count();
                        if n > 0 {
                            l.push(format!("Sous-titres FR recherchés pour {n} épisode(s)."));
                        }
                    }
                    out.push(l.join("\n"));
                }
            }
            Err(SvcError::NotConfigured) => {}
            Err(e) => notes.push(why("Sonarr", e)),
        }
        match &movies {
            Ok(v) => {
                for m in v.iter().filter(|m| title_matches(&m.title, needle)).take(3) {
                    tmdb_ids.push(m.tmdb_id);
                    let mut l = vec![format!(
                        "Film « {} » ({}) — {}, {}.",
                        m.title,
                        m.year,
                        if m.monitored { "suivi" } else { "non suivi" },
                        if m.has_file { "présent" } else { "absent" }
                    )];
                    if !m.has_file {
                        let today = now.with_timezone(&s.tz).date_naive();
                        let dates: Vec<_> = [
                            (&m.digital_release, "numérique"),
                            (&m.physical_release, "physique"),
                            (&m.in_cinemas, "cinéma"),
                        ]
                        .into_iter()
                        .filter_map(|(d, w)| {
                            Some(format!("{w} {}", day(d.as_deref().and_then(|d| local_date(d, s.tz))?, today)))
                        })
                        .collect();
                        if !dates.is_empty() {
                            l.push(format!("Sorties : {}.", dates.join(", ")));
                        }
                    }
                    let q = queue_for(&m.title);
                    if !q.is_empty() {
                        l.push(format!("Dans la file ({}) :", q.len()));
                        l.extend(queue_lines(&q));
                    }
                    out.push(l.join("\n"));
                }
            }
            Err(SvcError::NotConfigured) => {}
            Err(e) => notes.push(why("Radarr", e)),
        }

        // Présence dans Plex : non vus et lien.
        if !out.is_empty() {
            let mut secs = sv::plex_sections(&s.plex, "show").await.unwrap_or_default();
            secs.extend(sv::plex_sections(&s.plex, "movie").await.unwrap_or_default());
            let q = [("includeGuids", "1".to_string())];
            for sec in secs {
                for i in sv::plex_section_items(&s.plex, &sec, &q, 5000).await.unwrap_or_default() {
                    let hit = i.tvdb().is_some_and(|t| tvdb_ids.contains(&t))
                        || i.tmdb().is_some_and(|t| tmdb_ids.contains(&t))
                        || title_matches(&i.title, needle);
                    if !hit {
                        continue;
                    }
                    let unseen = match (i.leaf_count, i.viewed_leaf_count, i.view_count) {
                        (Some(a), Some(b), _) => format!("{} non vu(s)", a - b),
                        (None, _, Some(v)) if v > 0 => "vu".to_string(),
                        _ => "non vu".to_string(),
                    };
                    out.push(format!("Sur Plex : {} — {unseen}{}", i.title, self.link(&i.rating_key).await));
                }
            }
        }

        if out.is_empty() {
            match sv::seerr_search(&s.seerr, needle).await {
                Ok(r) if !r.is_empty() => {
                    let lines: Vec<_> = r
                        .iter()
                        .take(5)
                        .map(|x| {
                            format!("- {} — {} — {} (id {})", x.label(), kind_fr(&x.media_type), seerr_status(x), x.id)
                        })
                        .collect();
                    out.push(format!("« {needle} » n'est pas suivi. Résultats de recherche :\n{}", lines.join("\n")));
                }
                Ok(_) => out.push(format!("« {needle} » : aucun résultat, ni suivi ni en recherche.")),
                Err(e) => out.push(format!("« {needle} » n'est pas suivi ({}).", why("Seerr", &e))),
            }
        }
        out.extend(notes);
        Ok(out.join("\n\n"))
    }

    #[tool(
        description = "Faire le point sur la bibliothèque : épisodes et films manquants, sous-titres français manquants, éléments en échec, réencodage, espace disque libre."
    )]
    async fn library_health(&self) -> Out {
        let s = &self.stack;
        let (ms, mr, we, wm, (queue, notes), td, (disk, _)) = tokio::join!(
            sv::arr_missing_count(&s.sonarr),
            sv::arr_missing_count(&s.radarr),
            sv::bazarr_wanted_episodes(&s.bazarr),
            sv::bazarr_wanted_movies_total(&s.bazarr),
            self.queues(),
            sv::tdarr_stats(&s.tdarr),
            self.disk_lines()
        );
        let n = |r: Result<i64, SvcError>, name: &str| match r {
            Ok(v) => v.to_string(),
            Err(e) => format!("? ({})", why(name, &e)),
        };
        let mut out = vec![
            "Bibliothèque :".to_string(),
            format!("- Manquants (suivis) : {} épisode(s), {} film(s).", n(ms, "Sonarr"), n(mr, "Radarr")),
            format!(
                "- Sous-titres FR manquants : {} épisode(s), {} film(s).",
                n(we.map(|w| w.total), "Bazarr"),
                n(wm, "Bazarr")
            ),
            format!(
                "- File : {} élément(s), dont {} en échec.",
                queue.len(),
                queue.iter().filter(|q| q.has_problem()).count()
            ),
        ];
        match td {
            Ok(t) => out.push(format!(
                "- Réencodage : {} fichier(s) traités, {} gagnés.",
                t.total_transcode_count as i64,
                go(t.size_diff * 1_073_741_824.0)
            )),
            Err(SvcError::NotConfigured) => {}
            Err(e) => out.push(format!("- Réencodage : {}.", why("Tdarr", &e))),
        }
        if disk.is_empty() {
            out.push("- Disque : espace libre inconnu.".to_string());
        } else {
            out.extend(disk.into_iter().map(|d| format!("- Disque {d}.")));
        }
        out.extend(notes);
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Vérifier la santé de la stack média : services joignables, alertes des gestionnaires, indexeurs en échec, connexion du client torrent (tunnel VPN), client usenet en pause."
    )]
    async fn stack_health(&self) -> Out {
        let s = &self.stack;
        let (plex, son, rad, baz, seer, prow, qb, nzb, td) = tokio::join!(
            sv::plex_machine_id(&s.plex),
            sv::arr_health(&s.sonarr, "v3"),
            sv::arr_health(&s.radarr, "v3"),
            sv::bazarr_ping(&s.bazarr),
            sv::seerr_ping(&s.seerr),
            sv::prowlarr_status(&s.prowlarr),
            sv::qbit_transfer(&s.qbit),
            sv::nzbget_status(&s.nzbget),
            sv::tdarr_status(&s.tdarr)
        );
        let (li, ll, ka, ab) = tokio::join!(
            sv::lidarr_artists(&s.lidarr),
            sv::ll_wanted(&s.lazylibrarian),
            sv::kavita_on_deck(&s.kavita),
            sv::abs_in_progress(&s.audiobookshelf)
        );
        let mut up = Vec::new();
        let mut down = Vec::new();
        let mut mark = |name: &str, r: Result<(), &SvcError>| match r {
            Ok(()) => up.push(name.to_string()),
            Err(SvcError::NotConfigured) => {}
            Err(e) => down.push(why(name, e)),
        };
        mark("Plex", plex.as_ref().map(|_| ()));
        mark("Sonarr", son.as_ref().map(|_| ()));
        mark("Radarr", rad.as_ref().map(|_| ()));
        mark("Bazarr", baz.as_ref().map(|_| ()));
        mark("Seerr", seer.as_ref().map(|_| ()));
        mark("Prowlarr", prow.as_ref().map(|_| ()));
        mark("qBittorrent", qb.as_ref().map(|_| ()));
        mark("NZBGet", nzb.as_ref().map(|_| ()));
        mark("Tdarr", td.as_ref().map(|_| ()));
        mark("Lidarr", li.as_ref().map(|_| ()));
        mark("LazyLibrarian", ll.as_ref().map(|_| ()));
        mark("Kavita", ka.as_ref().map(|_| ()));
        mark("Audiobookshelf", ab.as_ref().map(|_| ()));
        let mut out =
            vec![format!("Services joignables : {}.", if up.is_empty() { "aucun".into() } else { up.join(", ") })];
        if !down.is_empty() {
            out.push(format!("⚠ {}.", down.join(" ; ")));
        }
        let mut alerts: Vec<String> = Vec::new();
        for (name, h) in [("Sonarr", &son), ("Radarr", &rad)] {
            if let Ok(h) = h {
                for x in h.iter().filter(|x| x.kind != "ok") {
                    let line = format!("- {name} : {}", x.message.chars().take(160).collect::<String>());
                    if !alerts.contains(&line) {
                        alerts.push(line);
                    }
                }
            }
        }
        if !alerts.is_empty() {
            out.push(format!("Alertes :\n{}", alerts.join("\n")));
        }
        if let Ok((idx, st)) = &prow {
            let now = Utc::now();
            let failing: Vec<_> = st
                .iter()
                .filter(|x| x.disabled_till.as_deref().and_then(parse_utc).is_some_and(|t| t > now))
                // Un indexeur désactivé à la main n'est plus « en échec ».
                .filter_map(|x| idx.iter().find(|i| i.id == x.indexer_id && i.enable).map(|i| i.name.clone()))
                .collect();
            let enabled = idx.iter().filter(|i| i.enable).count();
            out.push(if failing.is_empty() {
                format!("Indexeurs : {enabled} actif(s), aucun en échec.")
            } else {
                format!("Indexeurs : {enabled} actif(s), en échec : {}.", failing.join(", "))
            });
        }
        if let Ok(t) = &qb {
            out.push(format!("Torrents : {}.", connection_fr(&t.connection_status)));
        }
        if let Ok(n) = &nzb {
            if n.download_paused {
                out.push("Usenet : ⚠ en pause.".to_string());
            }
        }
        if let Ok(t) = &td {
            if t.status != "good" {
                out.push(format!("Réencodage : état « {} ».", t.status));
            }
        }
        if s.visio.is_some() {
            out.push(match crate::visio::slowed_for(&self.store) {
                Some(t) => format!("Débit réduit pendant les visios : actif, réduit en ce moment (« {t} »)."),
                None => "Débit réduit pendant les visios : actif.".to_string(),
            });
        }
        if s.caldav.is_some() {
            let last = self.store.get_kv("cal_last").ok().flatten();
            let parsed =
                last.as_deref().and_then(|v| v.split_once('|')).and_then(|(t, n)| Some((t.parse::<i64>().ok()?, n)));
            out.push(
                match parsed.and_then(|(t, n)| Some((chrono::TimeZone::timestamp_opt(&s.tz, t, 0).single()?, n))) {
                    Some((d, n)) => {
                        format!("Calendrier des sorties : {n} événement(s), synchronisé à {}.", d.format("%d/%m %H:%M"))
                    }
                    None => "Calendrier des sorties : pas encore synchronisé.".to_string(),
                },
            );
        }
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Obtenir la section médias d'un briefing : épisodes prêts à regarder, sorties du jour, problèmes à traiter (disque, échecs)."
    )]
    async fn brief_section(&self, Parameters(_a): Parameters<BriefArgs>) -> Out {
        let s = &self.stack;
        let now = Utc::now();
        let today = now.with_timezone(&s.tz).date_naive();
        let (start, end) =
            (now.format("%Y-%m-%d").to_string(), (now + Duration::days(2)).format("%Y-%m-%d").to_string());
        let (ready, cal, (queue, _), (disk, low)) = tokio::join!(
            self.ready(None, true, false, 8),
            sv::sonarr_calendar(&s.sonarr, &start, &end),
            self.queues(),
            self.disk_lines()
        );
        let mut out = Vec::new();
        if let Ok(r) = &ready {
            if !r.ok.is_empty() {
                out.push(format!("Prêts à regarder : {} ({}).", r.ok.len(), r.ok_titles.join(", ")));
            }
        }
        if let Ok(v) = &cal {
            let names: Vec<_> = v
                .iter()
                .filter(|e| e.monitored)
                .filter(|e| e.air_date_utc.as_deref().and_then(|d| local_date(d, s.tz)) == Some(today))
                .map(|e| {
                    format!(
                        "{} {}",
                        e.series.as_ref().map(|x| x.title.as_str()).unwrap_or("?"),
                        se(Some(e.season_number), Some(e.episode_number))
                    )
                })
                .collect();
            if !names.is_empty() {
                out.push(format!("Sorties du jour : {}.", names.join(", ")));
            }
        }
        let failed = queue.iter().filter(|q| q.has_problem()).count();
        if failed > 0 {
            out.push(format!("⚠ {failed} téléchargement(s) en échec."));
        }
        if low {
            out.push(format!(
                "⚠ Espace disque bas : {}.",
                disk.iter().filter(|d| d.starts_with('⚠')).cloned().collect::<Vec<_>>().join(" ; ")
            ));
        }
        if out.is_empty() {
            return Ok("Médias : rien de neuf.".to_string());
        }
        Ok(format!("Médias :\n{}", out.join("\n")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::{EpisodeRef, Titled};

    fn q(series: &str, s: i64, e: i64, reason: &str) -> QueueItem {
        QueueItem {
            series: Some(Titled { title: series.into() }),
            episode: Some(EpisodeRef { season_number: s, episode_number: e }),
            error_message: Some(reason.into()),
            ..Default::default()
        }
    }

    #[test]
    fn problems_are_grouped_by_work_and_reason() {
        let items = [q("A", 2, 3, "r1"), q("A", 2, 1, "r1"), q("A", 2, 2, "r2"), q("B", 1, 1, "r1")];
        let refs: Vec<_> = items.iter().collect();
        assert_eq!(group_problems(&refs), vec!["A S02E01 → S02E03 (2) : r1", "A S02E02 : r2", "B S01E01 : r1"]);
    }

    #[test]
    fn connection_states_are_translated() {
        assert!(connection_fr("disconnected").contains("VPN"));
        assert_eq!(connection_fr("connected"), "connecté");
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for MediaServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}
