use chrono::{NaiveDate, NaiveDateTime, Duration, TimeZone, Utc};
use chrono_tz::Tz;
use crate::models::{CalendarEvent, Task};

// ponytail: fuseau par défaut codé en dur ; passer en config si multi-fuseau un jour.
pub const USER_TZ: Tz = chrono_tz::Europe::Paris;

// Convention interne : tous les horodatages sont stockés en heure murale locale (USER_TZ), naïfs.
// L'affichage formate donc le naïf tel quel, sans reconversion.

/// Parse an ICS (iCalendar) string and extract VEVENT entries.
/// Handles line folding (long lines continued with leading space/tab).
/// Returns a vec of (uid, summary, description, location, dtstart, dtend, allday).
pub fn parse_ics(input: &str) -> Vec<CalendarEvent> {
    let unfolded = unfold_lines(input);
    let mut events = Vec::new();
    let mut in_vevent = false;
    let mut in_vcalendar = false;

    let mut uid = String::new();
    let mut summary = String::new();
    let mut description = String::new();
    let mut location = String::new();
    let mut dtstart = String::new();
    let mut dtend = String::new();
    let mut dtstart_tzid = String::new();
    let mut dtend_tzid = String::new();
    let mut all_day = false;

    for line in unfolded.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() { continue; }

        match trimmed {
            "BEGIN:VCALENDAR" => { in_vcalendar = true; continue; }
            "END:VCALENDAR" => { in_vcalendar = false; continue; }
            "BEGIN:VEVENT" if in_vcalendar => {
                in_vevent = true;
                uid.clear(); summary.clear(); description.clear();
                location.clear(); dtstart.clear(); dtend.clear();
                dtstart_tzid.clear(); dtend_tzid.clear();
                all_day = false;
                continue;
            }
            "END:VEVENT" if in_vevent => {
                if let Some(event) = build_event(&uid, &summary, &description, &location, &dtstart, &dtend, &dtstart_tzid, &dtend_tzid, all_day) {
                    events.push(event);
                }
                in_vevent = false;
                continue;
            }
            _ => {}
        }

        if !in_vevent { continue; }

        if let Some(val) = trimmed.strip_prefix("UID:") {
            uid = val.to_string();
        } else if let Some(val) = trimmed.strip_prefix("SUMMARY:") {
            summary = unescape_ics(val);
        } else if let Some(val) = trimmed.strip_prefix("DESCRIPTION:") {
            description = unescape_ics(val);
        } else if let Some(val) = trimmed.strip_prefix("LOCATION:") {
            location = unescape_ics(val);
        } else if let Some(val) = trimmed.strip_prefix("DTSTART;VALUE=DATE:") {
            dtstart = val.to_string();
            all_day = true;
        } else if let Some(rest) = trimmed.strip_prefix("DTSTART;TZID=") {
            if let Some((zone, val)) = rest.split_once(':') {
                dtstart_tzid = zone.to_string();
                dtstart = val.to_string();
            }
        } else if let Some(val) = trimmed.strip_prefix("DTSTART:") {
            dtstart = val.to_string();
        } else if let Some(val) = trimmed.strip_prefix("DTEND;VALUE=DATE:") {
            dtend = val.to_string();
            all_day = true;
        } else if let Some(rest) = trimmed.strip_prefix("DTEND;TZID=") {
            if let Some((zone, val)) = rest.split_once(':') {
                dtend_tzid = zone.to_string();
                dtend = val.to_string();
            }
        } else if let Some(val) = trimmed.strip_prefix("DTEND:") {
            dtend = val.to_string();
        }
    }

    events
}

/// Unfold ICS lines: lines starting with space or tab continue the previous line.
/// Removes exactly one leading whitespace character (the folding marker).
fn unfold_lines(input: &str) -> String {
    let mut result = String::new();
    for line in input.lines() {
        if line.starts_with(' ') || line.starts_with('\t') {
            // Remove exactly one leading space/tab (the folding marker)
            let content = &line[1..];
            result.push_str(content);
        } else {
            if !result.is_empty() {
                result.push('\n');
            }
            result.push_str(line);
        }
    }
    result
}

/// Génère un fichier ICS (VCALENDAR) avec un VEVENT all-day par tâche ayant une `due_date`.
/// Tâches sans échéance ignorées (rien à placer sur un agenda). `None` si aucune tâche exportable.
pub fn tasks_to_ics(tasks: &[Task]) -> Option<String> {
    let exportable: Vec<&Task> = tasks.iter().filter(|t| t.due_date.is_some()).collect();
    if exportable.is_empty() {
        return None;
    }
    let mut out = String::from("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Kairos//Tasks//FR\r\n");
    for task in exportable {
        let due = task.due_date.unwrap();
        let dtend = due.succ_opt().unwrap_or(due);
        out.push_str("BEGIN:VEVENT\r\n");
        out.push_str(&format!("UID:kairos-task-{}@kairos\r\n", task.id));
        out.push_str(&format!("DTSTART;VALUE=DATE:{}\r\n", due.format("%Y%m%d")));
        out.push_str(&format!("DTEND;VALUE=DATE:{}\r\n", dtend.format("%Y%m%d")));
        out.push_str(&format!("SUMMARY:{}\r\n", escape_ics(&task.title)));
        if let Some(desc) = &task.description {
            out.push_str(&format!("DESCRIPTION:{}\r\n", escape_ics(desc)));
        }
        out.push_str("END:VEVENT\r\n");
    }
    out.push_str("END:VCALENDAR\r\n");
    Some(out)
}

/// Escape ICS text: inverse de `unescape_ics` (\\, puis ; , et \n, dans cet ordre).
fn escape_ics(s: &str) -> String {
    s.replace('\\', "\\\\")
        .replace(';', "\\;")
        .replace(',', "\\,")
        .replace('\n', "\\n")
}

/// Unescape ICS text: replace \\n with \n, \\; with ;, \\, with ,, \\\\ with \\
fn unescape_ics(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('n') | Some('N') => out.push('\n'),
                Some(';') => out.push(';'),
                Some(',') => out.push(','),
                Some('\\') => out.push('\\'),
                Some(other) => { out.push('\\'); out.push(other); }
                None => out.push('\\'),
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn build_event(uid: &str, summary: &str, description: &str, location: &str,
    dtstart: &str, dtend: &str, dtstart_tzid: &str, dtend_tzid: &str, all_day: bool) -> Option<CalendarEvent> {

    let uid = if uid.is_empty() {
        format!("kairos-{}", std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos())
    } else {
        uid.to_string()
    };

    let (start_time, end_time) = if all_day {
        let date = NaiveDate::parse_from_str(dtstart, "%Y%m%d").ok()?;
        let end_date = if dtend.is_empty() {
            date + Duration::days(1)
        } else {
            NaiveDate::parse_from_str(dtend, "%Y%m%d").ok()?
        };
        (date.and_hms_opt(0, 0, 0)?, end_date.and_hms_opt(0, 0, 0)?)
    } else {
        let start_tzid = if dtstart_tzid.is_empty() { None } else { Some(dtstart_tzid) };
        let end_tzid = if dtend_tzid.is_empty() { None } else { Some(dtend_tzid) };
        let start = parse_ics_datetime(dtstart, start_tzid)?;
        let end = if dtend.is_empty() {
            start + Duration::hours(1)
        } else {
            parse_ics_datetime(dtend, end_tzid)?
        };
        (start, end)
    };

    Some(CalendarEvent {
        uid,
        summary: if summary.is_empty() { None } else { Some(summary.to_string()) },
        description: if description.is_empty() { None } else { Some(description.to_string()) },
        location: if location.is_empty() { None } else { Some(location.to_string()) },
        start_time,
        end_time,
        all_day,
        source: "calendar".into(),
        etag: None,
    })
}

/// Parse un DTSTART/DTEND ICS et le normalise en heure murale locale (USER_TZ), naïve.
/// - suffixe `Z` → interprété UTC, converti vers USER_TZ
/// - `tzid` fourni (DTSTART;TZID=zone:...) → interprété dans `zone`, converti vers USER_TZ
/// - sinon (heure flottante) → supposé déjà en heure locale (USER_TZ), gardé tel quel
fn parse_ics_datetime(s: &str, tzid: Option<&str>) -> Option<NaiveDateTime> {
    let s = s.trim();

    if let Some(body) = s.strip_suffix('Z') {
        let naive = NaiveDateTime::parse_from_str(body, "%Y%m%dT%H%M%S").ok()?;
        return Some(Utc.from_utc_datetime(&naive).with_timezone(&USER_TZ).naive_local());
    }

    let naive = NaiveDateTime::parse_from_str(s, "%Y%m%dT%H%M%S").ok()?;
    match tzid.and_then(|z| z.parse::<Tz>().ok()) {
        Some(src) => Some(src.from_local_datetime(&naive).single()?.with_timezone(&USER_TZ).naive_local()),
        None => Some(naive),
    }
}

/// Calendar sync: fetch events from the configured calendar server.
/// Uses the configured URL, username, password.
pub struct CalendarClient {
    url: String,
    username: String,
    password: String,
    client: reqwest::Client,
}

impl CalendarClient {
    pub fn new(url: String, username: String, password: String) -> Self {
        Self {
            url,
            username,
            password,
            client: reqwest::Client::new(),
        }
    }

    /// Fetch all events from the configured calendar collection.
    /// `url` must point directly at the calendar collection
    /// (`http://host:port/user/calendar-id/`). Pas de découverte de principal :
    /// un serveur self-hosted mono-calendrier expose une URL de collection stable,
    /// et la découverte multi-étapes (principal → home-set → énumération) était
    /// fragile et renvoyait la home (REPORT vide) au lieu de la collection.
    pub async fn sync(&self) -> anyhow::Result<Vec<CalendarEvent>> {
        let xml = self.fetch_events().await?;
        let mut events = Vec::new();
        for block in extract_calendar_data(&xml) {
            events.extend(parse_ics(&block));
        }
        Ok(events)
    }

    /// REPORT calendar-query : récupère tous les VEVENT de la collection.
    async fn fetch_events(&self) -> anyhow::Result<String> {
        let body = r#"<?xml version="1.0" encoding="utf-8"?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop>
    <D:getetag/>
    <C:calendar-data/>
  </D:prop>
  <C:filter>
    <C:comp-filter name="VCALENDAR">
      <C:comp-filter name="VEVENT"/>
    </C:comp-filter>
  </C:filter>
</C:calendar-query>"#;

        let resp = self.client
            .request(reqwest::Method::from_bytes(b"REPORT").unwrap(), &self.url)
            .header("Content-Type", "application/xml; charset=utf-8")
            .header("Depth", "1")
            .basic_auth(&self.username, Some(&self.password))
            .body(body)
            .send()
            .await?;

        let status = resp.status();
        let text = resp.text().await?;
        if !status.is_success() {
            anyhow::bail!("Calendar server REPORT failed: HTTP {status}");
        }
        Ok(text)
    }
}

/// Extrait chaque bloc VCALENDAR d'une réponse multistatus.
/// Le payload `<calendar-data>` est souvent collé à sa balise sur la même ligne
/// et échappé en XML ; on découpe donc directement sur les marqueurs VCALENDAR
/// (indépendant du namespace) et on déséchappe les entités avant le parsing ICS.
fn extract_calendar_data(xml: &str) -> Vec<String> {
    let mut blocks = Vec::new();
    let mut rest = xml;
    while let Some(b) = rest.find("BEGIN:VCALENDAR") {
        let after = &rest[b..];
        match after.find("END:VCALENDAR") {
            Some(e_rel) => {
                let end = e_rel + "END:VCALENDAR".len();
                blocks.push(xml_unescape(&after[..end]));
                rest = &after[end..];
            }
            None => break,
        }
    }
    blocks
}

/// Déséchappe les entités XML d'un payload calendar-data. `&amp;` en dernier
/// pour ne pas re-déséchapper une entité déjà décodée.
fn xml_unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::Task;

    fn make_task(id: i64, title: &str, due: Option<NaiveDate>) -> Task {
        Task {
            id, title: title.to_string(), description: None, due_date: due,
            priority: "normal".to_string(), completed: false,
            created_at: NaiveDate::from_ymd_opt(2026, 7, 1).unwrap(), completed_at: None,
        }
    }

    #[test]
    fn tasks_sans_echeance_ignorees() {
        let tasks = vec![make_task(1, "sans date", None)];
        assert!(tasks_to_ics(&tasks).is_none());
    }

    #[test]
    fn tasks_avec_echeance_genere_vevent() {
        let due = NaiveDate::from_ymd_opt(2026, 7, 20).unwrap();
        let tasks = vec![make_task(1, "Relancer ; entreprise, X\ny", Some(due))];
        let ics = tasks_to_ics(&tasks).unwrap();
        assert!(ics.contains("BEGIN:VEVENT"));
        assert!(ics.contains("DTSTART;VALUE=DATE:20260720"));
        assert!(ics.contains("DTEND;VALUE=DATE:20260721"));
        assert!(ics.contains("UID:kairos-task-1@kairos"));
        assert!(ics.contains(r"SUMMARY:Relancer \; entreprise\, X\ny"));
    }

    #[test]
    fn test_parse_ics_basic() {
        let ics = r#"BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//FR
BEGIN:VEVENT
UID:test-1
DTSTART:20260709T090000
DTEND:20260709T100000
SUMMARY:Réunion test
DESCRIPTION:Description avec\nsaut de ligne
LOCATION:Bureau
END:VEVENT
END:VCALENDAR"#;

        let events = parse_ics(ics);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].summary.as_deref(), Some("Réunion test"));
        assert_eq!(events[0].location.as_deref(), Some("Bureau"));
        assert_eq!(events[0].start_time.format("%H:%M").to_string(), "09:00");
        assert_eq!(events[0].end_time.format("%H:%M").to_string(), "10:00");
    }

    #[test]
    fn test_parse_ics_all_day() {
        let ics = r#"BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:test-allday
DTSTART;VALUE=DATE:20260709
DTEND;VALUE=DATE:20260710
SUMMARY:Vacances
END:VEVENT
END:VCALENDAR"#;

        let events = parse_ics(ics);
        assert_eq!(events.len(), 1);
        assert!(events[0].all_day);
        assert_eq!(events[0].summary.as_deref(), Some("Vacances"));
    }

    #[test]
    fn test_parse_ics_multiple() {
        let ics = r#"BEGIN:VCALENDAR
BEGIN:VEVENT
UID:1
DTSTART:20260709T090000
DTEND:20260709T100000
SUMMARY:First
END:VEVENT
BEGIN:VEVENT
UID:2
DTSTART:20260709T140000
DTEND:20260709T150000
SUMMARY:Second
END:VEVENT
END:VCALENDAR"#;

        let events = parse_ics(ics);
        assert_eq!(events.len(), 2);
    }

    #[test]
    fn test_parse_ics_empty() {
        let events = parse_ics("BEGIN:VCALENDAR\nEND:VCALENDAR");
        assert!(events.is_empty());
    }

    #[test]
    fn test_unfold_lines() {
        // ICS folding: long lines broken with \r\n + 1 leading space (folding marker)
        // After unfold: the leading space (folding marker) is removed
        let input = "SUMMARY:This is a very long\n continuation";
        let unfolded = unfold_lines(input);
        assert!(unfolded.contains("continuation"));
        assert!(!unfolded.contains("\n"));

        // Realistic: two spaces — one for folding, one for word boundary
        let input2 = "SUMMARY:This is a very long\n  summary line";
        let unfolded2 = unfold_lines(input2);
        assert!(unfolded2.contains("This is a very long summary line"));
    }

    #[test]
    fn test_parse_ics_utc() {
        // 09:00 UTC en juillet = 11:00 à Paris (CEST, UTC+2)
        let ics = r#"BEGIN:VCALENDAR
BEGIN:VEVENT
UID:utc-test
DTSTART:20260709T090000Z
DTEND:20260709T100000Z
SUMMARY:UTC Event
END:VEVENT
END:VCALENDAR"#;

        let events = parse_ics(ics);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].start_time.format("%H:%M").to_string(), "11:00");
        assert_eq!(events[0].end_time.format("%H:%M").to_string(), "12:00");
    }

    #[test]
    fn test_parse_ics_tzid() {
        // TZID même zone (Europe/Paris) → heure inchangée. Ces événements n'étaient
        // même pas parsés avant (DTSTART;TZID=... non reconnu → droppé).
        let ics = r#"BEGIN:VCALENDAR
BEGIN:VEVENT
UID:tzid-paris
DTSTART;TZID=Europe/Paris:20260709T090000
DTEND;TZID=Europe/Paris:20260709T100000
SUMMARY:Paris Event
END:VEVENT
END:VCALENDAR"#;
        let events = parse_ics(ics);
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].start_time.format("%H:%M").to_string(), "09:00");

        // TZID cross-zone : 09:00 New York (EDT, UTC-4) = 15:00 Paris (CEST, UTC+2)
        let ics2 = r#"BEGIN:VCALENDAR
BEGIN:VEVENT
UID:tzid-ny
DTSTART;TZID=America/New_York:20260709T090000
DTEND;TZID=America/New_York:20260709T100000
SUMMARY:NY Event
END:VEVENT
END:VCALENDAR"#;
        let events2 = parse_ics(ics2);
        assert_eq!(events2.len(), 1);
        assert_eq!(events2[0].start_time.format("%H:%M").to_string(), "15:00");
    }

    #[test]
    fn test_extract_and_parse_radicale_report() {
        // Réponse REPORT calendar-query réelle capturée depuis Radicale :
        // namespace par défaut DAV: sans préfixe, calendar-data collé à sa balise
        // et échappé en XML. Régression contre les deux bugs (BEGIN collé + namespace).
        let xml = "<?xml version='1.0' encoding='utf-8'?>\n<multistatus xmlns=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><response><href>/test/mycal/ev-allday.ics</href><propstat><prop><getetag>\"x\"</getetag><C:calendar-data>BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:ev-allday-1\nDTSTART;VALUE=DATE:20260713\nDTEND;VALUE=DATE:20260714\nSUMMARY:Cong&amp;\nEND:VEVENT\nEND:VCALENDAR\n</C:calendar-data></prop><status>HTTP/1.1 200 OK</status></propstat></response><response><href>/test/mycal/ev-timed.ics</href><propstat><prop><C:calendar-data>BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:ev-timed-1\nDTSTART:20260713T090000Z\nDTEND:20260713T100000Z\nLOCATION:Visio\nSUMMARY:Standup\nEND:VEVENT\nEND:VCALENDAR\n</C:calendar-data></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>";

        let blocks = extract_calendar_data(xml);
        assert_eq!(blocks.len(), 2, "deux blocs VCALENDAR attendus");

        let events: Vec<_> = blocks.iter().flat_map(|b| parse_ics(b)).collect();
        assert_eq!(events.len(), 2, "deux VEVENT parsés");

        let allday = events.iter().find(|e| e.uid == "ev-allday-1").unwrap();
        assert!(allday.all_day);
        assert_eq!(allday.summary.as_deref(), Some("Cong&")); // &amp; déséchappé

        let timed = events.iter().find(|e| e.uid == "ev-timed-1").unwrap();
        assert!(!timed.all_day);
        // 09:00 UTC en juillet = 11:00 Paris (CEST)
        assert_eq!(timed.start_time.format("%H:%M").to_string(), "11:00");
        assert_eq!(timed.location.as_deref(), Some("Visio"));
    }
}
