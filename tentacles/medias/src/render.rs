//! Mise en forme : fonctions pures, sans appel réseau.

use chrono::{DateTime, Datelike, NaiveDate, TimeZone, Utc};
use chrono_tz::Tz;

use crate::services::{PlexItem, QueueItem, SeerrResult};

/// Minuscules, sans accents ni ponctuation, espaces simples : base des comparaisons de titres.
pub fn fold(s: &str) -> String {
    let mapped: String = s
        .to_lowercase()
        .chars()
        .map(|c| match c {
            'à' | 'á' | 'â' | 'ä' | 'ã' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'î' | 'ï' | 'í' => 'i',
            'ô' | 'ö' | 'ó' => 'o',
            'ù' | 'û' | 'ü' | 'ú' => 'u',
            'ç' => 'c',
            c if c.is_alphanumeric() => c,
            _ => ' ',
        })
        .collect();
    mapped.split_whitespace().collect::<Vec<_>>().join(" ")
}

pub fn title_matches(title: &str, needle: &str) -> bool {
    let n = fold(needle);
    !n.is_empty() && fold(title).contains(&n)
}

const JOURS: [&str; 7] = ["lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."];

pub fn parse_utc(s: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(s).ok().map(|d| d.with_timezone(&Utc))
}

/// « auj. 16:00 », « demain 16:45 », « ven. 07/11 16:00 ».
pub fn when(t: DateTime<Utc>, now: DateTime<Utc>, tz: Tz) -> String {
    let l = t.with_timezone(&tz);
    let today = now.with_timezone(&tz).date_naive();
    let d = l.date_naive();
    let hm = l.format("%H:%M");
    if d == today {
        format!("auj. {hm}")
    } else if Some(d) == today.succ_opt() {
        format!("demain {hm}")
    } else {
        format!("{} {} {hm}", JOURS[d.weekday().num_days_from_monday() as usize], l.format("%d/%m"))
    }
}

/// Date seule (sorties de films : l'heure n'a pas de sens).
pub fn day(d: NaiveDate, today: NaiveDate) -> String {
    if d == today {
        "auj.".to_string()
    } else if Some(d) == today.succ_opt() {
        "demain".to_string()
    } else {
        format!("{} {}", JOURS[d.weekday().num_days_from_monday() as usize], d.format("%d/%m/%Y"))
    }
}

pub fn local_date(s: &str, tz: Tz) -> Option<NaiveDate> {
    parse_utc(s).map(|t| tz.from_utc_datetime(&t.naive_utc()).date_naive())
}

pub fn minutes(ms: Option<i64>) -> Option<i64> {
    ms.filter(|m| *m > 0).map(|m| (m + 30_000) / 60_000)
}

pub fn gb(bytes: i64) -> i64 {
    bytes / 1_073_741_824
}

/// Taille lisible en Go avec une décimale, séparateur français.
pub fn go(bytes: f64) -> String {
    format!("{:.1} Go", bytes / 1_073_741_824.0).replace('.', ",")
}

pub fn plex_link(machine: &str, rating_key: &str) -> String {
    format!("https://app.plex.tv/desktop/#!/server/{machine}/details?key=%2Flibrary%2Fmetadata%2F{rating_key}")
}

fn is_fr(code: &Option<String>) -> bool {
    matches!(code.as_deref(), Some("fra" | "fre" | "fr"))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Subs {
    /// Sous-titres français complets.
    Fr,
    /// Seulement des sous-titres forcés (passages étrangers).
    ForcedOnly,
    /// Absents ; Bazarr les cherche.
    Wanted,
    Missing,
}

pub fn subs_of(item: &PlexItem, bazarr_wants: bool) -> Subs {
    let fr: Vec<_> = item.streams().filter(|s| s.stream_type == 3 && is_fr(&s.language_code)).collect();
    if fr.iter().any(|s| s.forced != Some(true)) {
        Subs::Fr
    } else if bazarr_wants {
        Subs::Wanted
    } else if !fr.is_empty() {
        Subs::ForcedOnly
    } else {
        Subs::Missing
    }
}

pub fn audio_fr(item: &PlexItem) -> bool {
    item.streams().any(|s| s.stream_type == 2 && is_fr(&s.language_code))
}

pub fn subs_label(s: Subs, vf: bool) -> &'static str {
    match (s, vf) {
        (_, true) => "VF dispo",
        (Subs::Fr, _) => "ST FR",
        (Subs::ForcedOnly, _) => "ST FR forcés seulement",
        (Subs::Wanted, _) => "sans ST FR — recherche en cours",
        (Subs::Missing, _) => "sans ST FR",
    }
}

/// Regardable par un francophone : VF ou ST FR complets.
pub fn watchable(s: Subs, vf: bool) -> bool {
    vf || s == Subs::Fr
}

pub fn se(season: Option<i64>, episode: Option<i64>) -> String {
    format!("S{:02}E{:02}", season.unwrap_or(0), episode.unwrap_or(0))
}

/// Prochain épisode non vu : saisons normales d'abord, épisodes spéciaux (saison 0) en dernier.
pub fn next_episode(mut leaves: Vec<PlexItem>) -> Option<(PlexItem, usize)> {
    let n = leaves.len();
    leaves.sort_by_key(|e| (e.parent_index.unwrap_or(0) == 0, e.parent_index.unwrap_or(0), e.index.unwrap_or(0)));
    leaves.into_iter().next().map(|e| (e, n))
}

pub fn seerr_status(r: &SeerrResult) -> &'static str {
    match r.media_info.as_ref().map(|m| m.status).unwrap_or(1) {
        2 => "demande en attente de validation",
        3 => "demandé, en cours",
        4 => "partiellement disponible",
        5 => "disponible",
        6 => "écarté",
        _ => "non demandé",
    }
}

pub fn kind_fr(media_type: &str) -> &'static str {
    if media_type == "tv" { "série" } else { "film" }
}

/// Avancement d'un élément de file, en pourcentage entier.
pub fn progress(q: &QueueItem) -> Option<i64> {
    (q.size > 0.0).then(|| (((q.size - q.sizeleft) / q.size) * 100.0).round() as i64)
}

/// État lisible d'un élément de file.
pub fn queue_state(q: &QueueItem) -> &'static str {
    match (q.status.as_str(), q.tracked_download_state.as_str()) {
        (_, "importPending" | "importing") => "import en cours",
        (_, "importBlocked" | "failedPending" | "failed") => "bloqué",
        ("paused", _) => "en pause",
        ("queued", _) => "en attente",
        ("downloading", _) => "en cours",
        ("completed", _) => "terminé",
        ("failed" | "warning", _) => "en échec",
        _ => "en attente",
    }
}

pub fn protocol_fr(p: &str) -> &'static str {
    match p {
        "torrent" => "torrent",
        "usenet" => "usenet",
        _ => "?",
    }
}

/// « 00:12:30 » ou « 1.02:00:00 » (format .NET) → « 12 min », « 26 h ».
pub fn timeleft(t: &str) -> Option<String> {
    let (days, rest) = match t.split_once('.') {
        Some((d, r)) if !d.contains(':') => (d.parse::<i64>().ok()?, r),
        _ => (0, t),
    };
    let mut parts = rest.split(':').map(|p| p.parse::<i64>().ok());
    let (h, m) = (parts.next()??, parts.next()??);
    let total_min = days * 1440 + h * 60 + m;
    Some(if total_min >= 120 { format!("{} h", (total_min + 30) / 60) } else { format!("{total_min} min") })
}

/// Raison la plus parlante d'un élément en échec.
pub fn queue_reason(q: &QueueItem) -> String {
    if let Some(m) = q.error_message.as_deref().filter(|m| !m.is_empty()) {
        return m.chars().take(160).collect();
    }
    q.status_messages
        .iter()
        .flat_map(|s| s.messages.iter())
        .next()
        .map(|m| m.chars().take(160).collect())
        .unwrap_or_else(|| "raison non précisée".to_string())
}

pub fn tz_or_paris(name: Option<&str>) -> Tz {
    name.and_then(|n| n.parse().ok()).unwrap_or(chrono_tz::Europe::Paris)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::{EpisodeRef, PlexMedia, PlexPart, PlexStream, StatusMessage, Titled};

    fn item(streams: Vec<(i64, &str, bool)>) -> PlexItem {
        let streams = streams
            .into_iter()
            .map(|(t, l, f)| PlexStream { stream_type: t, language_code: Some(l.into()), forced: Some(f) })
            .collect();
        PlexItem { media: vec![PlexMedia { parts: vec![PlexPart { streams }] }], ..Default::default() }
    }

    #[test]
    fn fold_ignores_case_accents_punctuation() {
        assert_eq!(fold("L'Épée  du Héros!"), "l epee du heros");
        assert!(title_matches("La Chronique du Héros", "chronique heros") == false);
        assert!(title_matches("La Chronique du Héros", "chronique du heros"));
        assert!(!title_matches("Quelque chose", "  "));
    }

    #[test]
    fn subtitles_states() {
        assert_eq!(subs_of(&item(vec![(2, "jpn", false), (3, "fra", false)]), false), Subs::Fr);
        assert_eq!(subs_of(&item(vec![(3, "fre", false)]), true), Subs::Fr);
        assert_eq!(subs_of(&item(vec![(3, "fra", true)]), false), Subs::ForcedOnly);
        assert_eq!(subs_of(&item(vec![(3, "fra", true)]), true), Subs::Wanted);
        assert_eq!(subs_of(&item(vec![(3, "eng", false)]), true), Subs::Wanted);
        assert_eq!(subs_of(&item(vec![(3, "eng", false)]), false), Subs::Missing);
    }

    #[test]
    fn french_audio_counts_as_watchable() {
        let i = item(vec![(2, "fra", false)]);
        assert!(audio_fr(&i));
        assert!(watchable(subs_of(&i, false), audio_fr(&i)));
        assert!(!watchable(Subs::Wanted, false));
    }

    #[test]
    fn next_episode_skips_specials_first() {
        let e = |s, i| PlexItem { parent_index: Some(s), index: Some(i), ..Default::default() };
        let (n, count) = next_episode(vec![e(2, 3), e(0, 1), e(2, 1), e(1, 9)]).unwrap();
        assert_eq!((n.parent_index, n.index, count), (Some(1), Some(9), 4));
        assert!(next_episode(vec![]).is_none());
    }

    #[test]
    fn when_is_relative_and_local() {
        let tz = chrono_tz::Europe::Paris;
        let now = parse_utc("2026-11-03T10:00:00Z").unwrap();
        assert_eq!(when(parse_utc("2026-11-03T15:00:00Z").unwrap(), now, tz), "auj. 16:00");
        assert_eq!(when(parse_utc("2026-11-04T15:45:00Z").unwrap(), now, tz), "demain 16:45");
        assert_eq!(when(parse_utc("2026-11-07T15:00:00Z").unwrap(), now, tz), "sam. 07/11 16:00");
    }

    #[test]
    fn timeleft_formats() {
        assert_eq!(timeleft("00:12:30").as_deref(), Some("12 min"));
        assert_eq!(timeleft("1.02:00:00").as_deref(), Some("26 h"));
        assert_eq!(timeleft("n/a"), None);
    }

    #[test]
    fn queue_labels_and_reasons() {
        let q = QueueItem {
            title: "Release.Name.S01E02".into(),
            series: Some(Titled { title: "Une Série".into() }),
            episode: Some(EpisodeRef { season_number: 1, episode_number: 2 }),
            status: "paused".into(),
            tracked_download_status: "warning".into(),
            size: 200.0,
            sizeleft: 50.0,
            status_messages: vec![StatusMessage { title: "x".into(), messages: vec!["Aucun fichier vidéo".into()] }],
            ..Default::default()
        };
        assert_eq!(q.label(), "Une Série S01E02");
        assert_eq!(queue_state(&q), "en pause");
        assert_eq!(progress(&q), Some(75));
        assert!(q.has_problem());
        assert_eq!(queue_reason(&q), "Aucun fichier vidéo");
    }

    #[test]
    fn seerr_labels() {
        let r = SeerrResult {
            media_type: "tv".into(),
            name: Some("Titre".into()),
            first_air_date: Some("2023-09-29".into()),
            ..Default::default()
        };
        assert_eq!(r.label(), "Titre (2023)");
        assert_eq!(seerr_status(&r), "non demandé");
        assert_eq!(kind_fr(&r.media_type), "série");
    }

    #[test]
    fn plex_link_points_to_item() {
        assert!(plex_link("abc", "42").ends_with("/server/abc/details?key=%2Flibrary%2Fmetadata%2F42"));
    }
}
