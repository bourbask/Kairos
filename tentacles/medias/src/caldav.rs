//! Calendrier des sorties poussé vers un serveur CalDAV (calendrier dédié).
//!
//! Épisodes suivis (heure de diffusion, durée) et sorties de films (journée
//! entière, une par type). Événements « transparents » : ils n'occupent pas
//! l'agenda. Synchronisation incrémentale : seul ce qui change est réécrit,
//! ce qui disparaît des gestionnaires est retiré.

use std::collections::hash_map::DefaultHasher;
use std::collections::{BTreeMap, HashSet};
use std::hash::{Hash, Hasher};
use std::time::Duration as StdDuration;

use chrono::{DateTime, Duration, Utc};
use reqwest::{Method, StatusCode};

use crate::config::Stack;
use crate::http::SvcError;
use crate::render::{parse_utc, se};
use crate::services::{self as sv, CalendarEpisode, Movie};
use crate::store::Store;

/// Fenêtre poussée : un peu de passé (rattrapage), l'essentiel à venir.
const PAST_DAYS: i64 = 14;
const AHEAD_DAYS: i64 = 90;
/// Préfixe des clés de suivi dans la base : `cal:<uid>` → empreinte du contenu.
const KV_PREFIX: &str = "cal:";

#[derive(Debug, Clone)]
pub struct CalDav {
    /// Adresse de la collection, terminée par `/`.
    url: String,
    user: String,
    pass: String,
    http: reqwest::Client,
}

impl CalDav {
    pub fn new(url: &str, user: String, pass: String) -> CalDav {
        let url = if url.ends_with('/') { url.to_string() } else { format!("{url}/") };
        let http = reqwest::Client::builder().timeout(StdDuration::from_secs(20)).build().expect("client HTTP");
        CalDav { url, user, pass, http }
    }

    async fn call(
        &self,
        method: &str,
        url: &str,
        body: Option<(String, &str)>,
        depth: bool,
    ) -> Result<StatusCode, SvcError> {
        let m = Method::from_bytes(method.as_bytes()).expect("méthode HTTP");
        let mut req = self.http.request(m, url).basic_auth(&self.user, Some(&self.pass));
        if depth {
            req = req.header("Depth", "0");
        }
        if let Some((b, ct)) = body {
            req = req.header("Content-Type", ct).body(b);
        }
        let r = req.send().await.map_err(|_| {
            eprintln!("medias/caldav : appel impossible");
            SvcError::Unavailable
        })?;
        match r.status().as_u16() {
            401 | 403 => {
                eprintln!("medias/caldav : accès refusé");
                Err(SvcError::Denied)
            }
            _ => Ok(r.status()),
        }
    }

    /// Crée le calendrier s'il n'existe pas ; vrai s'il vient d'être créé.
    pub async fn ensure(&self) -> Result<bool, SvcError> {
        let st = self.call("PROPFIND", &self.url, None, true).await?;
        if st.as_u16() == 207 {
            return Ok(false);
        }
        let body = r#"<?xml version="1.0" encoding="utf-8"?>
<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
 <D:set><D:prop><D:displayname>Sorties</D:displayname></D:prop></D:set>
</C:mkcalendar>"#;
        let st = self.call("MKCALENDAR", &self.url, Some((body.to_string(), "application/xml")), false).await?;
        if st.is_success() {
            Ok(true)
        } else {
            eprintln!("medias/caldav : création refusée ({})", st.as_u16());
            Err(SvcError::Unavailable)
        }
    }

    /// Événements ayant une occurrence entre `start` et `end`, récurrences dépliées (corps brut).
    pub async fn report(&self, start: DateTime<Utc>, end: DateTime<Utc>) -> Result<String, SvcError> {
        let (a, b) = (stamp(start), stamp(end));
        let body = format!(
            r#"<?xml version="1.0" encoding="utf-8"?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
 <D:prop><C:calendar-data><C:expand start="{a}" end="{b}"/></C:calendar-data></D:prop>
 <C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT">
  <C:time-range start="{a}" end="{b}"/>
 </C:comp-filter></C:comp-filter></C:filter>
</C:calendar-query>"#
        );
        let m = Method::from_bytes(b"REPORT").expect("méthode HTTP");
        let r = self
            .http
            .request(m, &self.url)
            .basic_auth(&self.user, Some(&self.pass))
            .header("Depth", "1")
            .header("Content-Type", "application/xml")
            .body(body)
            .send()
            .await
            .map_err(|_| {
                eprintln!("medias/caldav : appel impossible");
                SvcError::Unavailable
            })?;
        match r.status().as_u16() {
            207 => r.text().await.map_err(|_| SvcError::Unavailable),
            401 | 403 => Err(SvcError::Denied),
            _ => Err(SvcError::Unavailable),
        }
    }

    pub async fn put(&self, uid: &str, ics: &str) -> Result<(), SvcError> {
        let url = format!("{}{uid}.ics", self.url);
        let st = self.call("PUT", &url, Some((ics.to_string(), "text/calendar; charset=utf-8")), false).await?;
        if st.is_success() { Ok(()) } else { Err(SvcError::Unavailable) }
    }

    pub async fn delete(&self, uid: &str) -> Result<(), SvcError> {
        let url = format!("{}{uid}.ics", self.url);
        let st = self.call("DELETE", &url, None, false).await?;
        if st.is_success() || st.as_u16() == 404 { Ok(()) } else { Err(SvcError::Unavailable) }
    }
}

/// Texte iCalendar : antislash, point-virgule, virgule et retours à la ligne échappés.
pub fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace(';', "\\;").replace(',', "\\,").replace('\n', "\\n").replace('\r', "")
}

fn stamp(t: DateTime<Utc>) -> String {
    t.format("%Y%m%dT%H%M%SZ").to_string()
}

fn vcal(uid: &str, dtstamp: &str, lines: &[String]) -> String {
    let mut out = vec![
        "BEGIN:VCALENDAR".to_string(),
        "VERSION:2.0".to_string(),
        "PRODID:-//Kairos//medias//FR".to_string(),
        "BEGIN:VEVENT".to_string(),
        format!("UID:{uid}"),
        format!("DTSTAMP:{dtstamp}"),
        "TRANSP:TRANSPARENT".to_string(),
    ];
    out.extend(lines.iter().cloned());
    out.push("END:VEVENT".to_string());
    out.push("END:VCALENDAR".to_string());
    out.join("\r\n") + "\r\n"
}

/// Événement d'un épisode : (uid, ics). L'horodatage reste stable pour ne réécrire que les vrais changements.
pub fn episode_event(e: &CalendarEpisode) -> Option<(String, String)> {
    let start = parse_utc(e.air_date_utc.as_deref()?)?;
    let minutes = e.runtime.filter(|m| *m > 0).unwrap_or(30);
    let series = e.series.as_ref().map(|s| s.title.as_str()).unwrap_or("?");
    let uid = format!("kairos-ep-{}-{}-{}", e.series_id, e.season_number, e.episode_number);
    let mut summary = format!("{series} {}", se(Some(e.season_number), Some(e.episode_number)));
    if e.has_file {
        summary.push_str(" ✓");
    }
    let mut desc = Vec::new();
    if !e.title.is_empty() && e.title != "TBA" {
        desc.push(format!("« {} »", e.title));
    }
    if e.has_file {
        desc.push("Déjà téléchargé.".to_string());
    }
    if e.finale_type.as_deref().is_some_and(|f| !f.is_empty()) {
        desc.push("Final.".to_string());
    }
    let mut lines = vec![
        format!("DTSTART:{}", stamp(start)),
        format!("DTEND:{}", stamp(start + Duration::minutes(minutes))),
        format!("SUMMARY:{}", escape(&summary)),
        "CATEGORIES:Séries".to_string(),
    ];
    if !desc.is_empty() {
        lines.push(format!("DESCRIPTION:{}", escape(&desc.join(" "))));
    }
    Some((uid.clone(), vcal(&uid, &stamp(start), &lines)))
}

/// Événements d'un film : un par date de sortie connue, en journée entière.
pub fn movie_events(m: &Movie, tz: chrono_tz::Tz) -> Vec<(String, String)> {
    [
        ("cinema", &m.in_cinemas, "au cinéma"),
        ("numerique", &m.digital_release, "sortie numérique"),
        ("physique", &m.physical_release, "sortie physique"),
    ]
    .into_iter()
    .filter_map(|(key, date, what)| {
        let t = parse_utc(date.as_deref()?)?;
        let d = t.with_timezone(&tz).date_naive();
        let uid = format!("kairos-film-{}-{key}", m.id);
        let have = if m.has_file { " ✓" } else { "" };
        let lines = vec![
            format!("DTSTART;VALUE=DATE:{}", d.format("%Y%m%d")),
            format!("DTEND;VALUE=DATE:{}", (d + Duration::days(1)).format("%Y%m%d")),
            format!("SUMMARY:{}", escape(&format!("{} — {what}{have}", m.title))),
            "CATEGORIES:Films".to_string(),
        ];
        Some((uid.clone(), vcal(&uid, &stamp(t), &lines)))
    })
    .collect()
}

fn digest(s: &str) -> String {
    let mut h = DefaultHasher::new();
    s.hash(&mut h);
    format!("{:016x}", h.finish())
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Report {
    pub written: usize,
    pub removed: usize,
    pub unchanged: usize,
}

/// Une synchronisation. Un gestionnaire injoignable interrompt sans rien retirer.
pub async fn sync(stack: &Stack, store: &Store, cal: &CalDav, now: DateTime<Utc>) -> Result<Report, SvcError> {
    if cal.ensure().await? {
        // Calendrier (re)créé : tout est à réécrire.
        for k in store.kv_keys(KV_PREFIX).unwrap_or_default() {
            let _ = store.del_kv(&k);
        }
    }
    let (start, end) = (
        (now - Duration::days(PAST_DAYS)).format("%Y-%m-%d").to_string(),
        (now + Duration::days(AHEAD_DAYS)).format("%Y-%m-%d").to_string(),
    );
    let mut events: BTreeMap<String, String> = BTreeMap::new();
    match sv::sonarr_calendar(&stack.sonarr, &start, &end).await {
        Ok(eps) => events.extend(eps.iter().filter(|e| e.monitored).filter_map(episode_event)),
        Err(SvcError::NotConfigured) => {}
        Err(e) => return Err(e),
    }
    match sv::radarr_calendar(&stack.radarr, &start, &end).await {
        Ok(ms) => {
            let (lo, hi) = (now - Duration::days(PAST_DAYS), now + Duration::days(AHEAD_DAYS));
            for m in ms.iter().filter(|m| m.monitored) {
                for (uid, ics) in movie_events(m, stack.tz) {
                    // Le calendrier des gestionnaires renvoie le film entier : ne garder que les dates de la fenêtre.
                    let in_window = [&m.in_cinemas, &m.digital_release, &m.physical_release]
                        .into_iter()
                        .zip(["cinema", "numerique", "physique"])
                        .any(|(d, k)| {
                            uid.ends_with(k) && d.as_deref().and_then(parse_utc).is_some_and(|t| t >= lo && t <= hi)
                        });
                    if in_window {
                        events.insert(uid, ics);
                    }
                }
            }
        }
        Err(SvcError::NotConfigured) => {}
        Err(e) => return Err(e),
    }
    let mut r = Report::default();
    for (uid, ics) in &events {
        let key = format!("{KV_PREFIX}{uid}");
        let h = digest(ics);
        if store.get_kv(&key).ok().flatten().as_deref() == Some(h.as_str()) {
            r.unchanged += 1;
            continue;
        }
        cal.put(uid, ics).await?;
        let _ = store.set_kv(&key, &h);
        r.written += 1;
    }
    let current: HashSet<String> = events.keys().map(|u| format!("{KV_PREFIX}{u}")).collect();
    for key in store.kv_keys(KV_PREFIX).unwrap_or_default() {
        if !current.contains(&key) {
            cal.delete(&key[KV_PREFIX.len()..]).await?;
            let _ = store.del_kv(&key);
            r.removed += 1;
        }
    }
    let _ = store.set_kv("cal_last", &format!("{}|{}", now.timestamp(), events.len()));
    Ok(r)
}

/// Boucle de fond : une synchronisation toutes les `caldav_minutes`.
pub async fn run(stack: std::sync::Arc<Stack>, store: std::sync::Arc<Store>) {
    let Some(cal) = stack.caldav.clone() else { return };
    let mut every = tokio::time::interval(StdDuration::from_secs(stack.caldav_minutes * 60));
    every.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        every.tick().await;
        match sync(&stack, &store, &cal, Utc::now()).await {
            Ok(r) if r.written + r.removed > 0 => {
                eprintln!(
                    "medias/caldav : {} écrit(s), {} retiré(s), {} inchangé(s)",
                    r.written, r.removed, r.unchanged
                )
            }
            Ok(_) => {}
            Err(e) => eprintln!("medias/caldav : synchronisation interrompue ({e})"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::Titled;

    #[test]
    fn escapes_ical_text() {
        assert_eq!(escape("a, b; c\\d\ne"), "a\\, b\\; c\\\\d\\ne");
    }

    #[test]
    fn episode_event_is_timed_transparent_and_stable() {
        let e = CalendarEpisode {
            series_id: 4,
            season_number: 2,
            episode_number: 5,
            title: "Le titre, enfin".into(),
            air_date_utc: Some("2026-11-04T15:00:00Z".into()),
            has_file: true,
            monitored: true,
            runtime: Some(24),
            series: Some(Titled { title: "Une Série".into() }),
            ..Default::default()
        };
        let (uid, ics) = episode_event(&e).unwrap();
        assert_eq!(uid, "kairos-ep-4-2-5");
        assert!(ics.contains("DTSTART:20261104T150000Z\r\nDTEND:20261104T152400Z\r\n"), "{ics}");
        assert!(ics.contains("SUMMARY:Une Série S02E05 ✓\r\n"), "{ics}");
        assert!(ics.contains("DESCRIPTION:« Le titre\\, enfin » Déjà téléchargé.\r\n"), "{ics}");
        assert!(ics.contains("TRANSP:TRANSPARENT"));
        assert_eq!(episode_event(&e).unwrap().1, ics, "contenu stable d'un passage à l'autre");
        let tba = CalendarEpisode { title: "TBA".into(), runtime: None, has_file: false, ..e };
        let (_, ics) = episode_event(&tba).unwrap();
        assert!(ics.contains("DTEND:20261104T153000Z") && !ics.contains("DESCRIPTION"), "{ics}");
    }

    #[test]
    fn movie_events_are_all_day_per_release() {
        let m = Movie {
            id: 9,
            title: "Un Film".into(),
            digital_release: Some("2026-11-16T23:00:00Z".into()),
            in_cinemas: None,
            ..Default::default()
        };
        let ev = movie_events(&m, chrono_tz::Europe::Paris);
        assert_eq!(ev.len(), 1);
        let (uid, ics) = &ev[0];
        assert_eq!(uid, "kairos-film-9-numerique");
        // 23:00 UTC = le 17 à Paris.
        assert!(ics.contains("DTSTART;VALUE=DATE:20261117\r\nDTEND;VALUE=DATE:20261118"), "{ics}");
        assert!(ics.contains("SUMMARY:Un Film — sortie numérique\r\n"), "{ics}");
    }
}
