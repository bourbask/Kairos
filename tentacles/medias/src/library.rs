//! Outils MCP de bilan et des autres médias (musique, lecture, livres audio) — lecture seule.

use std::collections::{HashMap, HashSet};

use chrono::{Duration, TimeZone, Utc};
use rmcp::{handler::server::wrapper::Parameters, schemars, tool, tool_router};
use serde::Deserialize;

use crate::render::{go, minutes};
use crate::services::{self as sv, Torrent};
use crate::tools::{MediaServer, why};

type Out = Result<String, String>;

#[derive(Deserialize, schemars::JsonSchema)]
struct WeeksArgs {
    /// Semaines sans visionnage (4 par défaut).
    weeks: Option<i64>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct RecapArgs {
    /// Titre de la série.
    title: String,
    /// Saison à résumer ; absent = celle du dernier épisode vu.
    season: Option<i64>,
}

/// Synopsis tronqué proprement.
fn clip(s: &str, max: usize) -> String {
    let s = s.trim().replace('\n', " ");
    if s.chars().count() <= max { s } else { format!("{}…", s.chars().take(max).collect::<String>().trim_end()) }
}

#[derive(Deserialize, schemars::JsonSchema)]
struct DaysArgs {
    /// Période en jours (7 par défaut, 365 au plus).
    days: Option<i64>,
}

fn hours(min: i64) -> String {
    if min >= 90 { format!("{} h {:02}", min / 60, min % 60) } else { format!("{min} min") }
}

/// Limites de partage atteintes (ratio ou durée). `None` : aucune limite.
pub fn share_done(t: &Torrent) -> Option<bool> {
    let by_ratio = (t.max_ratio > 0.0).then(|| t.ratio >= t.max_ratio);
    let by_time = (t.max_seeding_time > 0).then(|| t.seeding_time >= t.max_seeding_time * 60);
    match (by_ratio, by_time) {
        (None, None) => None,
        (a, b) => Some(a.unwrap_or(false) || b.unwrap_or(false)),
    }
}

/// Secondes de partage restantes avant la limite de durée.
pub fn share_left(t: &Torrent) -> Option<i64> {
    (t.max_seeding_time > 0).then(|| (t.max_seeding_time * 60 - t.seeding_time).max(0))
}

fn days_label(secs: i64) -> String {
    let d = secs / 86_400;
    if d >= 1 { format!("{d} j") } else { format!("{} h", (secs / 3600).max(1)) }
}

#[tool_router(router = library_router, vis = "pub(crate)")]
impl MediaServer {
    #[tool(
        description = "Lister les séries commencées puis délaissées (non vues depuis N semaines, épisodes en attente), en signalant celles encore surveillées."
    )]
    async fn abandoned(&self, Parameters(a): Parameters<WeeksArgs>) -> Out {
        let s = &self.stack;
        let weeks = a.weeks.unwrap_or(4).clamp(1, 520);
        let cutoff = (Utc::now() - Duration::weeks(weeks)).timestamp();
        let secs = sv::plex_sections(&s.plex, "show").await.map_err(|e| why("Plex", &e))?;
        let q = [("sort", "lastViewedAt:desc".to_string()), ("includeGuids", "1".to_string())];
        let mut shows = Vec::new();
        for sec in secs {
            shows.extend(sv::plex_section_items(&s.plex, &sec, &q, 2000).await.map_err(|e| why("Plex", &e))?);
        }
        let monitored: HashMap<i64, bool> =
            s.series().await.unwrap_or_default().into_iter().map(|x| (x.tvdb_id, x.monitored)).collect();
        let mut rows: Vec<_> = shows
            .into_iter()
            .filter(|x| {
                let (seen, all) = (x.viewed_leaf_count.unwrap_or(0), x.leaf_count.unwrap_or(0));
                seen > 0 && all > seen && x.last_viewed_at.is_some_and(|t| t < cutoff)
            })
            .collect();
        rows.sort_by_key(|x| -x.last_viewed_at.unwrap_or(0));
        if rows.is_empty() {
            return Ok(format!("Aucune série délaissée depuis {weeks} semaine(s)."));
        }
        let mut still = 0;
        let lines: Vec<_> = rows
            .iter()
            .take(20)
            .map(|x| {
                let when = x
                    .last_viewed_at
                    .and_then(|t| s.tz.timestamp_opt(t, 0).single())
                    .map(|d| d.format("%d/%m/%Y").to_string())
                    .unwrap_or_default();
                let watched = x.tvdb().and_then(|t| monitored.get(&t).copied()) == Some(true);
                still += watched as usize;
                format!(
                    "- {} — {} non vu(s), dernier visionnage le {when}{}",
                    x.title,
                    x.leaf_count.unwrap_or(0) - x.viewed_leaf_count.unwrap_or(0),
                    if watched { " — toujours surveillée" } else { "" }
                )
            })
            .collect();
        let mut out = format!("Séries délaissées depuis {weeks} semaine(s) ({}) :\n{}", rows.len(), lines.join("\n"));
        if still > 0 {
            out.push_str(&format!(
                "\n{still} encore surveillée(s) : `unfollow` arrête leur téléchargement, sans rien supprimer."
            ));
        }
        Ok(out)
    }

    #[tool(
        description = "Donner la matière d'un résumé « previously » d'une série : synopsis des épisodes déjà vus de la saison (jusqu'au dernier vu), sans rien au-delà. Résume à partir de ces seuls éléments, sans spoiler la suite."
    )]
    async fn recap(&self, Parameters(a): Parameters<RecapArgs>) -> Out {
        let s = &self.stack;
        let show = self.plex_item(&a.title).await?;
        if show.kind != "show" {
            return Err(format!("« {} » n'est pas une série.", show.title));
        }
        let mut leaves = sv::plex_all_leaves(&s.plex, &show.rating_key).await.map_err(|e| why("Plex", &e))?;
        leaves.retain(|l| l.parent_index.unwrap_or(0) > 0);
        leaves.sort_by_key(|l| (l.parent_index.unwrap_or(0), l.index.unwrap_or(0)));
        let Some(last) = leaves.iter().rposition(|l| l.view_count.unwrap_or(0) > 0) else {
            return Ok(format!("Aucun épisode de « {} » n'a été vu : rien à résumer sans spoiler.", show.title));
        };
        let season = a.season.unwrap_or(leaves[last].parent_index.unwrap_or(1));
        // Borne : jamais au-delà du dernier épisode vu.
        let seen: Vec<_> = leaves[..=last].iter().filter(|l| l.parent_index == Some(season)).collect();
        if seen.is_empty() {
            return Err(format!("Saison {season} pas encore commencée : rien à résumer sans spoiler."));
        }
        let next = leaves.get(last + 1).map(|l| crate::render::se(l.parent_index, l.index));
        let skip = seen.len().saturating_sub(26);
        let lines: Vec<_> = seen[skip..]
            .iter()
            .map(|l| {
                let syn = l.summary.as_deref().filter(|x| !x.trim().is_empty()).map(|x| clip(x, 400));
                format!(
                    "- {} « {} » : {}",
                    crate::render::se(l.parent_index, l.index),
                    l.title,
                    syn.unwrap_or_else(|| "(pas de synopsis)".into())
                )
            })
            .collect();
        let up_to = crate::render::se(leaves[last].parent_index, leaves[last].index);
        let mut out = format!("« {} », saison {season}, épisodes vus jusqu'à {up_to}", show.title);
        if let Some(n) = next {
            out.push_str(&format!(" (prochain : {n})"));
        }
        out.push_str(&format!(" :\n{}", lines.join("\n")));
        if let Some(sum) = show.summary.as_deref().filter(|x| !x.trim().is_empty()) {
            out.push_str(&format!("\nSérie : {}", clip(sum, 400)));
        }
        Ok(out)
    }

    #[tool(
        description = "Faire le bilan de visionnage sur une période : épisodes et films vus, temps passé estimé, séries les plus regardées."
    )]
    async fn watch_stats(&self, Parameters(a): Parameters<DaysArgs>) -> Out {
        let s = &self.stack;
        let days = a.days.unwrap_or(7).clamp(1, 365);
        let since = (Utc::now() - Duration::days(days)).timestamp();
        let hist = sv::plex_history(&s.plex, since).await.map_err(|e| why("Plex", &e))?;
        if hist.is_empty() {
            return Ok(format!("Rien vu ces {days} derniers jours."));
        }
        let mut per_show: HashMap<String, (usize, String)> = HashMap::new();
        let mut movies = Vec::new();
        for v in &hist {
            match v.kind.as_str() {
                "episode" => {
                    let e = per_show
                        .entry(v.grandparent_title.clone().unwrap_or_default())
                        .or_insert((0, v.rating_key.clone()));
                    e.0 += 1;
                }
                "movie" => movies.push(v),
                _ => {}
            }
        }
        // Durée estimée : un épisode échantillon par série, chaque film (appels bornés).
        let mut total = 0i64;
        let mut calls = 0;
        for (n, key) in per_show.values() {
            if calls >= 30 {
                break;
            }
            calls += 1;
            if let Some(m) = sv::plex_metadata(&s.plex, key).await.ok().and_then(|m| minutes(m.duration)) {
                total += m * *n as i64;
            }
        }
        let mut seen = HashSet::new();
        for v in movies.iter().filter(|v| seen.insert(v.rating_key.clone())).take(20) {
            if let Some(m) = sv::plex_metadata(&s.plex, &v.rating_key).await.ok().and_then(|m| minutes(m.duration)) {
                total += m;
            }
        }
        let episodes: usize = per_show.values().map(|v| v.0).sum();
        let mut top: Vec<_> = per_show.iter().map(|(k, v)| (v.0, k.clone())).collect();
        top.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        let top: Vec<_> = top.iter().take(5).map(|(n, t)| format!("{t} ({n})")).collect();
        let mut out = format!(
            "Sur {days} jour(s) : {episodes} épisode(s) de {} série(s), {} film(s), environ {}.",
            per_show.len(),
            movies.len(),
            hours(total)
        );
        if !top.is_empty() {
            out.push_str(&format!("\nLes plus regardées : {}.", top.join(", ")));
        }
        if !movies.is_empty() {
            let names: Vec<_> = movies.iter().take(10).map(|m| m.title.clone()).collect();
            out.push_str(&format!("\nFilms : {}.", names.join(", ")));
        }
        Ok(out)
    }

    #[tool(
        description = "Dire quels téléchargements terminés doivent encore partager (trackers privés) et lesquels ont atteint leurs limites de partage."
    )]
    async fn seeding_obligations(&self) -> Out {
        let t = sv::qbit_torrents(&self.stack.qbit).await.map_err(|e| why("qBittorrent", &e))?;
        let done: Vec<_> = t.iter().filter(|t| t.progress >= 1.0).collect();
        if done.is_empty() {
            return Ok("Aucun téléchargement terminé.".to_string());
        }
        let mut out = Vec::new();
        for (label, private) in [("Privés", true), ("Publics", false)] {
            let set: Vec<_> = done.iter().filter(|t| t.private.unwrap_or(false) == private).collect();
            if set.is_empty() {
                continue;
            }
            let owed: Vec<_> = set.iter().filter(|t| share_done(t) == Some(false)).collect();
            let free: Vec<_> = set.iter().filter(|t| share_done(t) == Some(true)).collect();
            let unbounded = set.len() - owed.len() - free.len();
            let size = |v: &[&&&Torrent]| go(v.iter().map(|t| t.size as f64).sum());
            let mut line =
                format!("{label} : {} terminé(s) — {} encore à partager ({})", set.len(), owed.len(), size(&owed));
            let lefts: Vec<i64> = owed.iter().filter_map(|t| share_left(t)).collect();
            if let (Some(min), Some(max)) = (lefts.iter().min(), lefts.iter().max()) {
                line.push_str(&format!(", fin dans {} à {}", days_label(*min), days_label(*max)));
            }
            line.push_str(&format!(" ; {} limites atteintes ({})", free.len(), size(&free)));
            if unbounded > 0 {
                line.push_str(&format!(" ; {unbounded} sans limite"));
            }
            out.push(format!("{line}."));
        }
        out.push("Rien n'est supprimé automatiquement.".to_string());
        Ok(out.join("\n"))
    }

    #[tool(description = "Donner les nouveautés musicales des artistes suivis : albums sortis récemment et à venir.")]
    async fn music_updates(&self) -> Out {
        let s = &self.stack;
        let artists = sv::lidarr_artists(&s.lidarr).await.map_err(|e| why("Lidarr", &e))?;
        if artists.is_empty() {
            return Ok(
                "Aucun artiste suivi. Pour commencer : `search` avec kind=musique, puis `request` media_type=artist."
                    .to_string(),
            );
        }
        let now = Utc::now();
        let (start, end) = (
            (now - Duration::days(30)).format("%Y-%m-%d").to_string(),
            (now + Duration::days(90)).format("%Y-%m-%d").to_string(),
        );
        let mut albums = sv::lidarr_calendar(&s.lidarr, &start, &end).await.map_err(|e| why("Lidarr", &e))?;
        albums.sort_by(|a, b| a.release_date.cmp(&b.release_date));
        let today = now.format("%Y-%m-%d").to_string();
        let fmt = |a: &sv::Album| {
            format!(
                "- {} — {}{}",
                a.artist.as_ref().map(|x| x.artist_name.as_str()).unwrap_or("?"),
                a.title,
                a.release_date.as_deref().and_then(|d| d.get(..10)).map(|d| format!(" ({d})")).unwrap_or_default()
            )
        };
        let past: Vec<_> =
            albums.iter().filter(|a| a.release_date.as_deref().is_some_and(|d| d < today.as_str())).map(fmt).collect();
        let next: Vec<_> =
            albums.iter().filter(|a| a.release_date.as_deref().is_some_and(|d| d >= today.as_str())).map(fmt).collect();
        let mut out = vec![format!("{} artiste(s) suivi(s).", artists.len())];
        out.push(if past.is_empty() {
            "Rien de sorti ces 30 derniers jours.".into()
        } else {
            format!("Sortis récemment :\n{}", past.join("\n"))
        });
        out.push(if next.is_empty() {
            "Rien d'annoncé d'ici 90 jours.".into()
        } else {
            format!("À venir :\n{}", next.join("\n"))
        });
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Faire le point lecture : livres et mangas en cours (progression), nouveaux chapitres ou tomes, livres demandés en attente."
    )]
    async fn reading_updates(&self) -> Out {
        let s = &self.stack;
        let (deck, recent, wanted, snatched) = tokio::join!(
            sv::kavita_on_deck(&s.kavita),
            sv::kavita_recent(&s.kavita),
            sv::ll_wanted(&s.lazylibrarian),
            sv::ll_snatched(&s.lazylibrarian)
        );
        let mut out = Vec::new();
        let mut notes = Vec::new();
        match deck {
            Ok(v) if !v.is_empty() => {
                let l: Vec<_> = v
                    .iter()
                    .map(|x| {
                        let pct = if x.pages > 0 { x.pages_read * 100 / x.pages } else { 0 };
                        format!("- {} — {pct} % ({}/{} pages)", x.name, x.pages_read, x.pages)
                    })
                    .collect();
                out.push(format!("En cours :\n{}", l.join("\n")));
            }
            Ok(_) => out.push("Aucune lecture en cours.".to_string()),
            Err(sv_err) => notes.push(why("Kavita", &sv_err)),
        }
        if let Ok(v) = recent {
            if !v.is_empty() {
                let l: Vec<_> = v.iter().take(10).map(|x| format!("- {} : {}", x.series_name, x.title)).collect();
                out.push(format!("Ajouts récents :\n{}", l.join("\n")));
            }
        }
        match (wanted, snatched) {
            (Ok(w), Ok(sn)) if w.is_empty() && sn.is_empty() => out.push(
                "Aucun livre demandé. Pour commencer : `search` avec kind=livre, puis `request` media_type=book ou audiobook."
                    .to_string(),
            ),
            (Ok(w), Ok(sn)) => out.push(format!("Livres demandés : {} en recherche, {} en téléchargement.", w.len(), sn.len())),
            (Err(e), _) | (_, Err(e)) => {
                if e != crate::http::SvcError::NotConfigured {
                    notes.push(why("LazyLibrarian", &e))
                }
            }
        }
        out.extend(notes);
        Ok(out.join("\n"))
    }

    #[tool(description = "Lister les livres audio en cours d'écoute : progression et temps restant.")]
    async fn audiobooks(&self) -> Out {
        let (items, progress) =
            sv::abs_in_progress(&self.stack.audiobookshelf).await.map_err(|e| why("Audiobookshelf", &e))?;
        if items.is_empty() {
            return Ok("Aucun livre audio en cours. Pour en demander un : `search` kind=livre, puis `request` media_type=audiobook.".to_string());
        }
        let by_id: HashMap<_, _> = progress.iter().map(|p| (p.library_item_id.as_str(), p)).collect();
        let lines: Vec<_> = items
            .iter()
            .map(|i| {
                let m = &i.media.metadata;
                let author = m.author_name.as_deref().map(|a| format!(" — {a}")).unwrap_or_default();
                match by_id.get(i.id.as_str()) {
                    Some(p) => {
                        let total = if p.duration > 0.0 { p.duration } else { i.media.duration };
                        let left = ((total - p.current_time).max(0.0) / 60.0).round() as i64;
                        format!(
                            "- {}{author} — {} %, reste {}",
                            m.title,
                            (p.progress * 100.0).round() as i64,
                            hours(left)
                        )
                    }
                    None => format!("- {}{author}", m.title),
                }
            })
            .collect();
        Ok(format!("Livres audio en cours :\n{}", lines.join("\n")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(ratio: f64, max_ratio: f64, seeding_h: i64, max_min: i64) -> Torrent {
        Torrent { ratio, max_ratio, seeding_time: seeding_h * 3600, max_seeding_time: max_min, ..Default::default() }
    }

    #[test]
    fn share_limits() {
        assert_eq!(share_done(&t(0.5, -1.0, 10, -1)), None);
        assert_eq!(share_done(&t(1.2, 1.0, 10, 43200)), Some(true));
        assert_eq!(share_done(&t(0.2, 1.0, 10, 600)), Some(true), "10 h ≥ 600 min");
        assert_eq!(share_done(&t(0.2, 1.0, 5, 600)), Some(false));
        assert_eq!(share_left(&t(0.2, 1.0, 5, 600)), Some(5 * 3600));
        assert_eq!(share_left(&t(0.2, 1.0, 5, -1)), None);
        assert_eq!(days_label(3 * 86_400 + 5), "3 j");
        assert_eq!(days_label(7200), "2 h");
        assert_eq!(hours(45), "45 min");
        assert_eq!(hours(125), "2 h 05");
    }
}
