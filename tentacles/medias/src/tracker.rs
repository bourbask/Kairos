//! Suivi des demandes asynchrones jusqu'à « regardable », et alerte disque.
//!
//! Fait : le film, ou le premier épisode demandé, est dans le lecteur avec VF
//! ou sous-titres français complets. Échec : demande refusée ou en échec.
//! En retard : sorti depuis plus de `late_hours` sans être regardable (prévenu
//! une fois, le suivi continue). Abandon : au-delà de `drop_days`.

use std::collections::HashMap;
use std::sync::Arc;

use chrono::{DateTime, Duration, Utc};

use crate::config::Stack;
use crate::render::{audio_fr, parse_utc, plex_link, se, subs_label, subs_of, watchable};
use crate::services::{self as sv, PlexItem};
use crate::store::{Request, State, Store};

/// Une notification à envoyer : (titre, message).
pub type Notice = (String, String);

/// Intervalle minimal entre deux alertes disque.
const DISK_ALERT_EVERY_H: i64 = 24;

struct PlexIndex {
    by_tvdb: HashMap<i64, PlexItem>,
    by_tmdb: HashMap<i64, PlexItem>,
    machine: Option<String>,
}

async fn plex_index(stack: &Stack) -> PlexIndex {
    let mut idx = PlexIndex { by_tvdb: HashMap::new(), by_tmdb: HashMap::new(), machine: None };
    idx.machine = sv::plex_machine_id(&stack.plex).await.ok();
    let q = [("includeGuids", "1".to_string())];
    for kind in ["show", "movie"] {
        for sec in sv::plex_sections(&stack.plex, kind).await.unwrap_or_default() {
            for i in sv::plex_section_items(&stack.plex, &sec, &q, 5000).await.unwrap_or_default() {
                if let Some(t) = i.tvdb() {
                    idx.by_tvdb.insert(t, i.clone());
                }
                if let Some(t) = i.tmdb() {
                    idx.by_tmdb.insert(t, i);
                }
            }
        }
    }
    idx
}

/// Résultat de l'examen d'une demande.
struct Check {
    /// Regardable : texte « où / comment ».
    ready: Option<String>,
    /// Présent mais sans VF ni ST FR.
    present: bool,
    release: Option<DateTime<Utc>>,
    /// Ce qui est examiné (« S01E01 », titre du film).
    what: String,
}

async fn check_movie(stack: &Stack, r: &Request, plex: &PlexIndex) -> Option<Check> {
    let movies = sv::radarr_movies(&stack.radarr).await.ok()?;
    let m = movies.iter().find(|m| m.tmdb_id == r.tmdb_id);
    let release = m.and_then(|m| {
        [&m.digital_release, &m.physical_release].into_iter().filter_map(|d| d.as_deref().and_then(parse_utc)).min()
    });
    let mut c = Check { ready: None, present: false, release, what: r.title.clone() };
    if m.is_some_and(|m| m.has_file) {
        if let Some(item) = plex.by_tmdb.get(&r.tmdb_id) {
            let full = sv::plex_metadata(&stack.plex, &item.rating_key).await.ok()?;
            let (subs, vf) = (subs_of(&full, false), audio_fr(&full));
            c.present = true;
            if watchable(subs, vf) {
                c.ready = Some(format!("{} sur le lecteur{}", subs_label(subs, vf), link(plex, &full.rating_key)));
            }
        }
    }
    Some(c)
}

async fn check_series(stack: &Stack, store: &Store, r: &Request, plex: &PlexIndex) -> Option<Check> {
    let tvdb = match r.tvdb_id {
        Some(t) => t,
        None => {
            let d = sv::seerr_details(&stack.seerr, "tv", r.tmdb_id).await.ok()?;
            let t = d.external_ids.and_then(|e| e.tvdb_id)?;
            let _ = store.set_tvdb(r.id, t);
            t
        }
    };
    let series = stack.series().await.ok()?;
    let s = series.iter().find(|s| s.tvdb_id == tvdb)?;
    let mut eps = sv::sonarr_episodes(&stack.sonarr, s.id).await.ok()?;
    eps.retain(|e| e.season_number > 0 && (r.seasons.is_empty() || r.seasons.contains(&e.season_number)));
    eps.sort_by_key(|e| (e.season_number, e.episode_number));
    let first = eps.first()?;
    let what = se(Some(first.season_number), Some(first.episode_number));
    let release = first.air_date_utc.as_deref().and_then(parse_utc);
    let mut c = Check { ready: None, present: false, release, what };
    if first.has_file {
        if let Some(show) = plex.by_tvdb.get(&tvdb) {
            let leaves = sv::plex_all_leaves(&stack.plex, &show.rating_key).await.ok()?;
            let leaf = leaves
                .iter()
                .find(|l| l.parent_index == Some(first.season_number) && l.index == Some(first.episode_number));
            if let Some(leaf) = leaf {
                let full = sv::plex_metadata(&stack.plex, &leaf.rating_key).await.ok()?;
                let (subs, vf) = (subs_of(&full, false), audio_fr(&full));
                c.present = true;
                if watchable(subs, vf) {
                    c.ready = Some(format!(
                        "{} {} sur le lecteur{}",
                        c.what,
                        subs_label(subs, vf),
                        link(plex, &full.rating_key)
                    ));
                }
            }
        }
    }
    Some(c)
}

fn link(plex: &PlexIndex, key: &str) -> String {
    plex.machine.as_deref().map(|m| format!(" — {}", plex_link(m, key))).unwrap_or_default()
}

/// Un passage de suivi : met à jour les demandes ouvertes, renvoie les notifications à envoyer.
pub async fn tick(stack: &Stack, store: &Store, now: DateTime<Utc>) -> Vec<Notice> {
    let mut out = Vec::new();
    let ts = now.timestamp();
    let open = store.list(true).unwrap_or_default();
    if !open.is_empty() {
        let plex = plex_index(stack).await;
        for r in open {
            out.extend(step(stack, store, &r, &plex, now).await);
        }
    }
    if let Some(n) = disk_alert(stack, store, ts).await {
        out.push(n);
    }
    out
}

async fn step(stack: &Stack, store: &Store, r: &Request, plex: &PlexIndex, now: DateTime<Utc>) -> Vec<Notice> {
    let ts = now.timestamp();
    if let Some(id) = r.seerr_id {
        if let Ok(q) = sv::seerr_request_status(&stack.seerr, id).await {
            let reason = match q.status {
                3 => Some("demande refusée"),
                4 => Some("demande en échec côté gestionnaire"),
                _ => None,
            };
            if let Some(reason) = reason {
                let _ = store.update(r.id, State::Failed, reason, r.late_notified, ts);
                return vec![(format!("Échec : {}", r.title), format!("{} : {reason}.", r.title))];
            }
        }
    }
    let check = if r.media_type == "movie" {
        check_movie(stack, r, plex).await
    } else {
        check_series(stack, store, r, plex).await
    };
    if let Some(c) = &check {
        if let Some(ready) = &c.ready {
            let _ = store.update(r.id, State::Done, ready, r.late_notified, ts);
            return vec![(format!("Prêt : {}", r.title), format!("{} : {ready}.", r.title))];
        }
    }
    if ts - r.created_at > Duration::days(stack.drop_days).num_seconds() {
        let _ = store.update(r.id, State::Dropped, "suivi arrêté (trop ancien)", r.late_notified, ts);
        return vec![(
            format!("Suivi arrêté : {}", r.title),
            format!("{} n'est toujours pas regardable après {} jours ; je ne le suis plus.", r.title, stack.drop_days),
        )];
    }
    // Service injoignable ou œuvre pas encore ajoutée : on garde l'état, on réessaiera.
    let Some(c) = check else { return Vec::new() };
    let late = c.release.is_some_and(|d| now > d + Duration::hours(stack.late_hours));
    let (state, detail) = match (c.present, late) {
        (_, true) if c.present => (State::Late, format!("{} présent sans VF ni sous-titres FR", c.what)),
        (_, true) => (State::Late, format!("{} sorti, toujours pas téléchargé", c.what)),
        (true, false) => (State::NoSubs, format!("{} présent, sous-titres FR attendus", c.what)),
        (false, false) => (State::Pending, format!("{} attendu", c.what)),
    };
    let notify_late = state == State::Late && !r.late_notified;
    let _ = store.update(r.id, state, &detail, r.late_notified || notify_late, ts);
    if notify_late {
        let since = c.release.map(|d| d.format("%d/%m").to_string()).unwrap_or_default();
        return vec![(format!("En retard : {}", r.title), format!("{} : sorti le {since}, {detail}.", r.title))];
    }
    Vec::new()
}

async fn disk_alert(stack: &Stack, store: &Store, ts: i64) -> Option<Notice> {
    let (a, b) = tokio::join!(sv::arr_rootfolders(&stack.sonarr), sv::arr_rootfolders(&stack.radarr));
    let mut low: Vec<String> = Vec::new();
    for f in a.unwrap_or_default().into_iter().chain(b.unwrap_or_default()) {
        let Some(free) = f.free_space else { continue };
        let gb = free / 1_073_741_824;
        let line = format!("{} : {gb} Go libres", f.path);
        if gb < stack.disk_min_gb && !low.contains(&line) {
            low.push(line);
        }
    }
    if low.is_empty() {
        return None;
    }
    let last: i64 = store.get_kv("disk_alert_at").ok().flatten().and_then(|v| v.parse().ok()).unwrap_or(0);
    if ts - last < DISK_ALERT_EVERY_H * 3600 {
        return None;
    }
    let _ = store.set_kv("disk_alert_at", &ts.to_string());
    Some(("Disque presque plein".to_string(), format!("Sous {} Go : {}.", stack.disk_min_gb, low.join(" ; "))))
}

/// Boucle de fond : un passage toutes les `track_minutes`, notifications envoyées si configurées.
pub async fn run(stack: Arc<Stack>, store: Arc<Store>) {
    // Préchargement : la liste des séries peut prendre plusieurs dizaines de secondes.
    let _ = stack.series().await;
    let mut every = tokio::time::interval(std::time::Duration::from_secs(stack.track_minutes * 60));
    every.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        every.tick().await;
        for (title, msg) in tick(&stack, &store, Utc::now()).await {
            match &stack.notifier {
                Some(n) => {
                    n.send(&title, &msg).await;
                }
                None => eprintln!("medias/suivi : {title} (notifications non configurées)"),
            }
        }
    }
}
