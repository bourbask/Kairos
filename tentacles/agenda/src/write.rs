//! Calendriers CalDAV du module : configuration, écriture d'un VEVENT.

use std::time::{Duration, Instant};

use chrono::{DateTime, NaiveDate, Utc};
use kairos::calendar::{SOURCE_PERSONAL, SOURCE_WORK, escape_ics};
use reqwest::Method;
use serde::Deserialize;

pub const TIMEOUT: Duration = Duration::from_secs(20);

/// Calendrier cible côté application.
#[derive(Deserialize, schemars::JsonSchema, Clone, Copy, Hash)]
#[serde(rename_all = "lowercase")]
pub enum Cal {
    Perso,
    Travail,
}

/// Dernière tentative de synchronisation : instant, réussite, horodatage de la dernière réussite.
pub struct LastSync {
    pub tried: Instant,
    pub ok: bool,
    pub at: Option<String>,
}

/// Collections configurées et identifiants ; partagé entre sessions (état de synchronisation compris).
#[derive(Default)]
pub struct Calendars {
    personal: Option<String>,
    work: Option<String>,
    user: String,
    pass: String,
    http: reqwest::Client,
    pub last_sync: tokio::sync::Mutex<Option<LastSync>>,
}

impl Calendars {
    pub fn new(personal: Option<String>, work: Option<String>, user: String, pass: String) -> Calendars {
        let http = reqwest::Client::builder().timeout(TIMEOUT).build().expect("client HTTP");
        Calendars { personal, work, user, pass, http, last_sync: Default::default() }
    }

    /// Mêmes variables que la synchronisation de nuit ; absentes : aucun calendrier distant.
    pub fn from_env() -> Calendars {
        let var = |k: &str| std::env::var(k).ok().filter(|v| !v.trim().is_empty());
        Calendars::new(
            var("CALENDAR_URL"),
            var("CALENDAR_WORK_URL"),
            var("CALENDAR_USERNAME").unwrap_or_default(),
            var("CALENDAR_PASSWORD").unwrap_or_default(),
        )
    }

    pub fn credentials(&self) -> (String, String) {
        (self.user.clone(), self.pass.clone())
    }

    /// Collections configurées avec leur source locale.
    pub fn collections(&self) -> Vec<(String, &'static str)> {
        [(&self.personal, SOURCE_PERSONAL), (&self.work, SOURCE_WORK)]
            .into_iter()
            .filter_map(|(u, s)| u.clone().map(|u| (u, s)))
            .collect()
    }

    pub fn collection(&self, cal: Cal) -> Option<(String, &'static str)> {
        match cal {
            Cal::Perso => self.personal.clone().map(|u| (u, SOURCE_PERSONAL)),
            Cal::Travail => self.work.clone().map(|u| (u, SOURCE_WORK)),
        }
    }

    /// PUT de `ics` sous `uid` ; l'erreur ne cite ni l'adresse ni les identifiants.
    pub async fn put(&self, url: &str, uid: &str, ics: String) -> Result<(), String> {
        let base = url.trim_end_matches('/');
        let st = self
            .http
            .request(Method::PUT, format!("{base}/{uid}.ics"))
            .basic_auth(&self.user, Some(&self.pass))
            .header("Content-Type", "text/calendar; charset=utf-8")
            .body(ics)
            .send()
            .await
            .map_err(|_| "agenda injoignable".to_string())?
            .status()
            .as_u16();
        match st {
            200..=299 => Ok(()),
            401 | 403 => Err("accès à l'agenda refusé".into()),
            st => Err(format!("écriture refusée ({st})")),
        }
    }
}

/// Plage d'un événement : journée entière ou instants UTC.
pub enum Span {
    Day(NaiveDate),
    Timed(DateTime<Utc>, DateTime<Utc>),
}

/// Uid stable dérivé de la saisie (FNV-1a) : la même saisie réécrit le même événement.
pub fn stable_uid(parts: &[&str]) -> String {
    let h = parts.join("\u{1f}").bytes().fold(0xcbf29ce484222325u64, |h, b| (h ^ b as u64).wrapping_mul(0x100000001b3));
    format!("kairos-{h:016x}")
}

/// VCALENDAR d'un seul VEVENT, lignes repliées à 75 octets.
pub fn ics(uid: &str, title: &str, span: &Span, stamp: DateTime<Utc>) -> String {
    let utc = |t: &DateTime<Utc>| t.format("%Y%m%dT%H%M%SZ").to_string();
    let (start, end) = match span {
        Span::Day(d) => (
            format!("DTSTART;VALUE=DATE:{}", d.format("%Y%m%d")),
            format!("DTEND;VALUE=DATE:{}", (*d + chrono::Duration::days(1)).format("%Y%m%d")),
        ),
        Span::Timed(s, e) => (format!("DTSTART:{}", utc(s)), format!("DTEND:{}", utc(e))),
    };
    let lines = [
        "BEGIN:VCALENDAR".to_string(),
        "VERSION:2.0".into(),
        "PRODID:-//Kairos//Agenda//FR".into(),
        "BEGIN:VEVENT".into(),
        format!("UID:{uid}"),
        format!("DTSTAMP:{}", utc(&stamp)),
        start,
        end,
        format!("SUMMARY:{}", escape_ics(title)),
        "END:VEVENT".into(),
        "END:VCALENDAR".into(),
    ];
    lines.iter().map(|l| fold(l)).collect()
}

/// Repli iCalendar : au plus 75 octets par ligne, suite précédée d'une espace.
fn fold(line: &str) -> String {
    let mut out = String::new();
    let mut len = 0;
    for c in line.chars() {
        if len + c.len_utf8() > 75 {
            out.push_str("\r\n ");
            len = 1;
        }
        out.push(c);
        len += c.len_utf8();
    }
    out + "\r\n"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_lines_fold_on_char_boundaries() {
        let out = fold(&format!("SUMMARY:{}", "é".repeat(60)));
        assert!(out.split("\r\n").all(|l| l.len() <= 75));
        assert_eq!(out.replace("\r\n ", ""), format!("SUMMARY:{}\r\n", "é".repeat(60)));
    }

    #[test]
    fn uid_is_stable_and_input_sensitive() {
        assert_eq!(stable_uid(&["a", "b"]), stable_uid(&["a", "b"]));
        assert_ne!(stable_uid(&["a", "b"]), stable_uid(&["ab", ""]));
    }
}
