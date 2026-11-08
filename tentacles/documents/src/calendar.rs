//! Calendrier « Échéances » poussé en CalDAV : une journée par échéance, rappel
//! une semaine avant. Incrémental : seul ce qui change est réécrit, ce qui
//! disparaît est retiré.

use std::collections::hash_map::DefaultHasher;
use std::collections::{BTreeMap, HashSet};
use std::hash::{Hash, Hasher};
use std::time::Duration;

use reqwest::Method;

use crate::facts::Deadline;
use crate::index::Index;

const KV_PREFIX: &str = "cal:";

#[derive(Debug, Clone)]
pub struct CalDav {
    url: String,
    user: String,
    pass: String,
    http: reqwest::Client,
}

fn digest(s: &str) -> String {
    let mut h = DefaultHasher::new();
    s.hash(&mut h);
    format!("{:016x}", h.finish())
}

pub fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace(';', "\\;").replace(',', "\\,").replace('\n', "\\n").replace('\r', "")
}

/// Événement iCalendar d'une échéance : (uid, ics). Contenu stable d'un passage à l'autre.
pub fn event(d: &Deadline) -> (String, String) {
    let uid = format!("kairos-doc-{}", digest(&format!("{}|{}", d.path, d.label)));
    let day = d.date.format("%Y%m%d");
    let next = (d.date + chrono::Duration::days(1)).format("%Y%m%d");
    let ics = [
        "BEGIN:VCALENDAR".to_string(),
        "VERSION:2.0".into(),
        "PRODID:-//Kairos//documents//FR".into(),
        "BEGIN:VEVENT".into(),
        format!("UID:{uid}"),
        format!("DTSTAMP:{day}T000000Z"),
        format!("DTSTART;VALUE=DATE:{day}"),
        format!("DTEND;VALUE=DATE:{next}"),
        format!("SUMMARY:{}", escape(&d.label)),
        "TRANSP:TRANSPARENT".into(),
        "CATEGORIES:Échéances".into(),
        "BEGIN:VALARM".into(),
        "ACTION:DISPLAY".into(),
        format!("DESCRIPTION:{}", escape(&d.label)),
        "TRIGGER:-P7D".into(),
        "END:VALARM".into(),
        "END:VEVENT".into(),
        "END:VCALENDAR".into(),
    ]
    .join("\r\n")
        + "\r\n";
    (uid, ics)
}

impl CalDav {
    pub fn new(url: &str, user: String, pass: String) -> CalDav {
        let url = if url.ends_with('/') { url.to_string() } else { format!("{url}/") };
        let http = reqwest::Client::builder().timeout(Duration::from_secs(20)).build().expect("client HTTP");
        CalDav { url, user, pass, http }
    }

    async fn call(&self, method: &str, url: &str, body: Option<(String, &str)>, depth: bool) -> Result<u16, String> {
        let m = Method::from_bytes(method.as_bytes()).expect("méthode HTTP");
        let mut req = self.http.request(m, url).basic_auth(&self.user, Some(&self.pass));
        if depth {
            req = req.header("Depth", "0");
        }
        if let Some((b, ct)) = body {
            req = req.header("Content-Type", ct).body(b);
        }
        let r = req.send().await.map_err(|_| "calendrier injoignable".to_string())?;
        match r.status().as_u16() {
            401 | 403 => Err("calendrier : accès refusé".into()),
            s => Ok(s),
        }
    }

    /// Crée le calendrier s'il manque ; vrai s'il vient d'être créé.
    pub async fn ensure(&self) -> Result<bool, String> {
        if self.call("PROPFIND", &self.url, None, true).await? == 207 {
            return Ok(false);
        }
        let body = r#"<?xml version="1.0" encoding="utf-8"?>
<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
 <D:set><D:prop><D:displayname>Échéances</D:displayname></D:prop></D:set>
</C:mkcalendar>"#;
        match self.call("MKCALENDAR", &self.url, Some((body.into(), "application/xml")), false).await? {
            200..=299 => Ok(true),
            s => Err(format!("calendrier : création refusée ({s})")),
        }
    }

    async fn put(&self, uid: &str, ics: &str) -> Result<(), String> {
        let url = format!("{}{uid}.ics", self.url);
        match self.call("PUT", &url, Some((ics.into(), "text/calendar; charset=utf-8")), false).await? {
            200..=299 => Ok(()),
            s => Err(format!("calendrier : écriture refusée ({s})")),
        }
    }

    async fn delete(&self, uid: &str) -> Result<(), String> {
        let url = format!("{}{uid}.ics", self.url);
        match self.call("DELETE", &url, None, false).await? {
            200..=299 | 404 => Ok(()),
            s => Err(format!("calendrier : suppression refusée ({s})")),
        }
    }

    /// Synchronise : (écrits, retirés).
    pub async fn sync(&self, index: &Index, deadlines: &[Deadline]) -> Result<(usize, usize), String> {
        if self.ensure().await? {
            for k in index.meta_keys(KV_PREFIX) {
                index.del_meta(&k);
            }
        }
        let events: BTreeMap<String, String> = deadlines.iter().map(event).collect();
        let (mut written, mut removed) = (0, 0);
        for (uid, ics) in &events {
            let key = format!("{KV_PREFIX}{uid}");
            let h = digest(ics);
            if index.get_meta(&key).as_deref() == Some(h.as_str()) {
                continue;
            }
            self.put(uid, ics).await?;
            index.set_meta(&key, &h);
            written += 1;
        }
        let current: HashSet<String> = events.keys().map(|u| format!("{KV_PREFIX}{u}")).collect();
        for key in index.meta_keys(KV_PREFIX) {
            if !current.contains(&key) {
                self.delete(&key[KV_PREFIX.len()..]).await?;
                index.del_meta(&key);
                removed += 1;
            }
        }
        Ok((written, removed))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::NaiveDate;

    #[test]
    fn event_is_all_day_with_reminder_and_stable_uid() {
        let d = Deadline {
            date: NaiveDate::from_ymd_opt(2027, 1, 10).unwrap(),
            label: "Assurance, auto : échéance".into(),
            path: "a.pdf".into(),
            org: None,
            rolled: false,
        };
        let (uid, ics) = event(&d);
        assert_eq!(uid, event(&d).0);
        assert!(ics.contains("DTSTART;VALUE=DATE:20270110\r\nDTEND;VALUE=DATE:20270111"), "{ics}");
        assert!(ics.contains("SUMMARY:Assurance\\, auto : échéance") && ics.contains("TRIGGER:-P7D"), "{ics}");
        let moved = Deadline { date: NaiveDate::from_ymd_opt(2027, 2, 1).unwrap(), ..d.clone() };
        assert_eq!(event(&moved).0, uid, "même échéance déplacée : même événement, réécrit");
    }
}
