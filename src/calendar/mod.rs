use chrono::{NaiveDate, NaiveDateTime, Duration, TimeZone, Utc};
use chrono_tz::Tz;
use crate::models::{CalendarEvent, Task};

// ponytail: fuseau par défaut codé en dur ; passer en config si multi-fuseau un jour.
pub const USER_TZ: Tz = chrono_tz::Europe::Paris;

/// Sources des événements : agenda personnel et agenda travail.
pub const SOURCE_PERSONAL: &str = "calendar";
pub const SOURCE_WORK: &str = "work";
/// Préfixe des sources d'agendas par abonnement (lecture seule).
pub const SOURCE_SUBSCRIPTION: &str = "subscription";

/// Source locale du `i`-ième abonnement (à partir de 0).
/// Source locale d'un abonnement : hachage court (FNV-1a) de son adresse, stable
/// quel que soit l'ordre de la liste.
pub fn subscription_source(url: &str) -> String {
    let h = url.bytes().fold(0x811c9dc5u32, |h, b| (h ^ u32::from(b)).wrapping_mul(0x01000193));
    format!("{SOURCE_SUBSCRIPTION}-{h:08x}")
}

pub fn is_subscription(source: &str) -> bool {
    source == SOURCE_SUBSCRIPTION || source.starts_with("subscription-")
}

// Convention interne : tous les horodatages sont stockés en heure murale locale (USER_TZ), naïfs.
// L'affichage formate donc le naïf tel quel, sans reconversion.

/// Propriétés utiles d'un VEVENT, telles que lues.
#[derive(Default)]
struct RawEvent {
    uid: String,
    summary: String,
    description: String,
    location: String,
    dtstart: String,
    dtend: String,
    dtstart_tzid: String,
    dtend_tzid: String,
    all_day: bool,
    rrule: String,
    exdates: Vec<(String, String)>,
    recurrence_id: Option<(String, String)>,
    cancelled: bool,
    transparent: bool,
}

/// Plafond d'occurrences d'un flux, récurrences dépliées : au-delà, le flux est refusé.
pub const OCCURRENCES_MAX: usize = 20_000;
/// Jours parcourus au plus pour une série bornée par COUNT (son début peut être très ancien).
const SERIES_SCAN_MAX: u32 = 5_000;

/// Parse an ICS (iCalendar) string and extract VEVENT entries.
/// Handles line folding (long lines continued with leading space/tab).
/// Les récurrences quotidiennes et hebdomadaires sont dépliées autour d'aujourd'hui.
/// Contenu illisible (composant non fermé, trop d'occurrences) : aucun événement.
pub fn parse_ics(input: &str, source: &str) -> Vec<CalendarEvent> {
    parse_ics_now(input, source).unwrap_or_default()
}

fn parse_ics_now(input: &str, source: &str) -> anyhow::Result<Vec<CalendarEvent>> {
    parse_ics_around(input, source, Utc::now().with_timezone(&USER_TZ).date_naive())
}

/// Événements d'un VCALENDAR, récurrences dépliées autour de `today` (un mois avant,
/// un an après). Erreur : composant encore ouvert à la fin du calendrier, ou plus de
/// [`OCCURRENCES_MAX`] occurrences.
pub fn parse_ics_around(input: &str, source: &str, today: NaiveDate) -> anyhow::Result<Vec<CalendarEvent>> {
    let unfolded = unfold_lines(input);
    // (événement, remplace une occurrence) ; clés uid#début des occurrences remplacées ou annulées.
    let mut events: Vec<(CalendarEvent, bool)> = Vec::new();
    let mut replaced = std::collections::HashSet::new();
    let mut in_vcalendar = false;
    let mut current: Option<RawEvent> = None;
    // sous-composants d'un VEVENT (VALARM…) : leurs propriétés ne sont pas celles de l'événement
    let mut nested = 0usize;

    for line in unfolded.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() { continue; }

        match trimmed {
            "BEGIN:VCALENDAR" => { in_vcalendar = true; continue; }
            "END:VCALENDAR" => {
                if current.is_some() { anyhow::bail!("agenda : composant non fermé"); }
                in_vcalendar = false;
                continue;
            }
            "BEGIN:VEVENT" if in_vcalendar && current.is_none() => {
                current = Some(RawEvent::default());
                nested = 0;
                continue;
            }
            "END:VEVENT" if current.is_some() && nested == 0 => {
                let raw = current.take().unwrap();
                collect_event(raw, source, today, &mut events, &mut replaced);
                if events.len() > OCCURRENCES_MAX { anyhow::bail!("agenda : trop d'occurrences"); }
                continue;
            }
            _ => {}
        }

        let Some(raw) = current.as_mut() else { continue };
        if trimmed.starts_with("BEGIN:") { nested += 1; continue; }
        if trimmed.starts_with("END:") { nested = nested.saturating_sub(1); continue; }
        if nested > 0 { continue; }
        let Some((name, params, val)) = split_property(trimmed) else { continue };
        let param = |k: &str| params.iter().find(|(n, _)| n == k).map(|(_, v)| v.clone()).unwrap_or_default();
        match name.as_str() {
            "UID" => raw.uid = val.to_string(),
            "SUMMARY" => raw.summary = unescape_ics(val),
            "DESCRIPTION" => raw.description = unescape_ics(val),
            "LOCATION" => raw.location = unescape_ics(val),
            "DTSTART" => {
                raw.dtstart = val.to_string();
                raw.dtstart_tzid = param("TZID");
                raw.all_day = param("VALUE") == "DATE" || is_date(val);
            }
            "DTEND" => {
                raw.dtend = val.to_string();
                raw.dtend_tzid = param("TZID");
            }
            "RRULE" => raw.rrule = val.to_string(),
            "EXDATE" => raw.exdates.extend(val.split(',').map(|v| (v.trim().to_string(), param("TZID")))),
            "RECURRENCE-ID" => raw.recurrence_id = Some((val.to_string(), param("TZID"))),
            "STATUS" => raw.cancelled = val.eq_ignore_ascii_case("CANCELLED"),
            "TRANSP" => raw.transparent = val.eq_ignore_ascii_case("TRANSPARENT"),
            _ => {}
        }
    }

    if current.is_some() { anyhow::bail!("agenda : composant non fermé"); }
    Ok(events.into_iter().filter(|(e, overrides)| *overrides || !replaced.contains(&e.uid)).map(|(e, _)| e).collect())
}

/// Range un VEVENT lu : occurrence remplacée, série dépliée ou événement simple.
fn collect_event(
    raw: RawEvent,
    source: &str,
    today: NaiveDate,
    events: &mut Vec<(CalendarEvent, bool)>,
    replaced: &mut std::collections::HashSet<String>,
) {
    if let Some((val, tzid)) = &raw.recurrence_id {
        let Some(at) = parse_when(val, tzid) else { return };
        let key = occurrence_uid(&raw.uid, at);
        replaced.insert(key.clone());
        if !raw.cancelled && let Some(mut e) = build_event(&raw, source) {
            e.uid = key;
            events.push((e, true));
        }
        return;
    }
    if raw.cancelled { return; }
    let Some(base) = build_event(&raw, source) else { return };
    if raw.rrule.is_empty() {
        events.push((base, false));
        return;
    }
    let excluded: Vec<NaiveDateTime> = raw.exdates.iter().filter_map(|(v, z)| parse_when(v, z)).collect();
    match expand(&base, &raw.rrule, &excluded, today) {
        Some(occurrences) => events.extend(occurrences.into_iter().map(|e| (e, false))),
        // fréquence non gérée : seule la première occurrence est connue
        None => events.push((base, false)),
    }
}

/// `NOM;PARAM=V;…:valeur` → (NOM, paramètres, valeur). Les `:` entre guillemets
/// appartiennent aux paramètres.
fn split_property(line: &str) -> Option<(String, Vec<(String, String)>, &str)> {
    let mut quoted = false;
    let colon = line.char_indices().find(|&(_, c)| {
        if c == '"' { quoted = !quoted; }
        c == ':' && !quoted
    })?.0;
    let (head, val) = (&line[..colon], &line[colon + 1..]);
    let mut parts = head.split(';');
    let name = parts.next()?.to_ascii_uppercase();
    let params = parts
        .filter_map(|p| p.split_once('='))
        .map(|(k, v)| (k.to_ascii_uppercase(), v.trim_matches('"').to_string()))
        .collect();
    Some((name, params, val))
}

fn is_date(v: &str) -> bool {
    v.len() == 8 && v.bytes().all(|b| b.is_ascii_digit())
}

/// Date ou date-heure ICS en heure murale locale (une date seule = minuit).
fn parse_when(v: &str, tzid: &str) -> Option<NaiveDateTime> {
    if is_date(v) {
        NaiveDate::parse_from_str(v, "%Y%m%d").ok()?.and_hms_opt(0, 0, 0)
    } else {
        parse_ics_datetime(v, (!tzid.is_empty()).then_some(tzid))
    }
}

fn occurrence_uid(uid: &str, start: NaiveDateTime) -> String {
    format!("{uid}#{}", start.format("%Y%m%dT%H%M%S"))
}

/// Déplie une règle `FREQ=DAILY` ou `FREQ=WEEKLY` (INTERVAL, COUNT, UNTIL, BYDAY sans
/// rang) entre un mois avant et un an après `today`. Autre fréquence : `None`.
// ponytail: dépliage en heure murale locale ; une série d'un autre fuseau peut glisser
// d'une heure au changement d'heure. MONTHLY/YEARLY : première occurrence seule, à
// ajouter le jour où un agenda en dépend.
fn expand(base: &CalendarEvent, rrule: &str, excluded: &[NaiveDateTime], today: NaiveDate) -> Option<Vec<CalendarEvent>> {
    use chrono::{Datelike, Weekday};
    let mut freq = "";
    let mut interval = 1i64;
    let mut count: Option<u32> = None;
    let mut until: Option<NaiveDateTime> = None;
    let mut byday: Vec<Weekday> = Vec::new();
    for part in rrule.split(';') {
        let Some((k, v)) = part.split_once('=') else { continue };
        match k.to_ascii_uppercase().as_str() {
            "FREQ" => freq = if v.eq_ignore_ascii_case("DAILY") { "D" } else if v.eq_ignore_ascii_case("WEEKLY") { "W" } else { "" },
            "INTERVAL" => interval = v.parse().ok().filter(|i: &i64| *i > 0)?,
            "COUNT" => count = Some(v.parse().ok()?),
            // jour seul : inclus en entier
            "UNTIL" => until = Some(if is_date(v) { parse_when(v, "")? + Duration::days(1) - Duration::seconds(1) } else { parse_when(v, "")? }),
            "BYDAY" => {
                for d in v.split(',') {
                    let code = d.trim_start_matches(|c: char| c == '+' || c == '-' || c.is_ascii_digit());
                    byday.push(match code.to_ascii_uppercase().as_str() {
                        "MO" => Weekday::Mon, "TU" => Weekday::Tue, "WE" => Weekday::Wed, "TH" => Weekday::Thu,
                        "FR" => Weekday::Fri, "SA" => Weekday::Sat, "SU" => Weekday::Sun,
                        _ => return None,
                    });
                }
            }
            _ => {}
        }
    }
    if freq.is_empty() { return None; }
    if freq == "W" && byday.is_empty() { byday.push(base.start_time.weekday()); }

    let first = base.start_time.date();
    let span = base.end_time - base.start_time;
    let (from, to) = (today - Duration::days(31), today + Duration::days(366));
    let monday = |d: NaiveDate| d - Duration::days(i64::from(d.weekday().num_days_from_monday()));
    let mut out = Vec::new();
    let mut n = 0u32;
    // Sans COUNT, les occurrences avant la fenêtre ne comptent pas : on la rejoint d'un saut.
    let mut day = if count.is_some() { first } else { first.max(from) };
    let mut scanned = 0u32;
    while day <= to {
        scanned += 1;
        if count.is_some() && scanned > SERIES_SCAN_MAX { break; }
        let hit = match freq {
            "D" => (day - first).num_days() % interval == 0 && (byday.is_empty() || byday.contains(&day.weekday())),
            _ => ((monday(day) - monday(first)).num_days() / 7) % interval == 0 && byday.contains(&day.weekday()),
        };
        if hit {
            let start = day.and_time(base.start_time.time());
            if until.is_some_and(|u| start > u) || count.is_some_and(|c| n >= c) { break; }
            n += 1;
            if start + span > from.and_hms_opt(0, 0, 0)? && !excluded.contains(&start) {
                let mut e = base.clone();
                e.uid = occurrence_uid(&base.uid, start);
                e.start_time = start;
                e.end_time = start + span;
                out.push(e);
            }
        }
        day += Duration::days(1);
    }
    Some(out)
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
/// Les caractères de contrôle (`\r` compris) sont retirés, sauf `\n` (échappé) et la tabulation.
pub fn escape_ics(s: &str) -> String {
    let s: String = s.chars().filter(|c| !c.is_control() || matches!(c, '\n' | '\t')).collect();
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

fn build_event(raw: &RawEvent, source: &str) -> Option<CalendarEvent> {
    let RawEvent { uid, summary, description, location, dtstart, dtend, dtstart_tzid, dtend_tzid, all_day, .. } = raw;
    let all_day = *all_day;
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
        let start_tzid = (!dtstart_tzid.is_empty()).then_some(dtstart_tzid.as_str());
        let end_tzid = (!dtend_tzid.is_empty()).then_some(dtend_tzid.as_str());
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
        source: source.into(),
        etag: None,
        transparent: raw.transparent,
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
    pub async fn sync(&self, source: &str) -> anyhow::Result<Vec<CalendarEvent>> {
        parse_report(&self.fetch_events().await?, source)
    }

    /// REPORT calendar-query : récupère tous les VEVENT de la collection.
    async fn fetch_events(&self) -> anyhow::Result<String> {
        let body = REPORT_BODY;

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

/// REPORT calendar-query : tous les VEVENT d'une collection.
const REPORT_BODY: &str = r#"<?xml version="1.0" encoding="utf-8"?>
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

/// Taille maximale d'une réponse d'abonnement.
pub const SUBSCRIPTION_MAX_BYTES: usize = 5 * 1024 * 1024;
/// Délai maximal d'une requête d'abonnement.
pub const SUBSCRIPTION_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(20);

/// Adresses d'abonnement d'une liste séparée par des espaces ou des virgules.
/// `webcal://` devient `https://` ; toute autre adresse non https est écartée.
pub fn subscription_urls(raw: &str) -> Vec<String> {
    raw.split(|c: char| c.is_whitespace() || c == ',')
        .filter(|u| !u.is_empty())
        .filter_map(|u| {
            let lower = u.to_ascii_lowercase();
            if lower.starts_with("webcal://") {
                Some(format!("https://{}", &u["webcal://".len()..]))
            } else if lower.starts_with("https://") {
                Some(format!("https://{}", &u["https://".len()..]))
            } else {
                tracing::warn!("abonnement d'agenda ignoré : adresse non https");
                None
            }
        })
        .collect()
}

/// Agenda par abonnement, en lecture seule : collection CalDAV (REPORT) ou flux
/// iCalendar (GET). L'adresse est un secret : aucune erreur ni journal ne la cite.
pub struct Subscription {
    url: String,
    auth: Option<(String, String)>,
    http: reqwest::Client,
}

impl Subscription {
    pub fn new(url: String, user: Option<String>, pass: Option<String>) -> Self {
        let http = reqwest::Client::builder()
            .timeout(SUBSCRIPTION_TIMEOUT)
            // https seul, redirections comprises (le clair n'est permis qu'aux tests)
            .https_only(!cfg!(any(test, feature = "test-http")))
            .redirect(redirect_policy())
            .build()
            .expect("client HTTP");
        Self { url, auth: user.map(|u| (u, pass.unwrap_or_default())), http }
    }

    /// `CALENDAR_SUBSCRIPTION_URLS` (+ `CALENDAR_SUBSCRIPTION_USERNAME` / `_PASSWORD`,
    /// facultatifs, communs aux adresses), chacune avec sa source locale.
    pub fn from_env() -> Vec<(Subscription, String)> {
        let var = |k: &str| std::env::var(k).ok().filter(|v| !v.trim().is_empty());
        let (user, pass) = (var("CALENDAR_SUBSCRIPTION_USERNAME"), var("CALENDAR_SUBSCRIPTION_PASSWORD"));
        subscription_urls(&var("CALENDAR_SUBSCRIPTION_URLS").unwrap_or_default())
            .into_iter()
            .map(|u| {
                let source = subscription_source(&u);
                (Subscription::new(u, user.clone(), pass.clone()), source)
            })
            .collect()
    }

    /// Événements de l'abonnement. Avec identifiants : REPORT d'abord, sinon GET ;
    /// l'autre méthode en repli. Échec des deux : erreur, sans rien remplacer.
    pub async fn sync(&self, source: &str) -> anyhow::Result<Vec<CalendarEvent>> {
        let report_first = self.auth.is_some();
        match self.fetch(report_first, source).await {
            Ok(events) => Ok(events),
            Err(_) => self
                .fetch(!report_first, source)
                .await
                .map_err(|e| anyhow::anyhow!("abonnement d'agenda illisible ou injoignable ({e})")),
        }
    }

    async fn fetch(&self, report: bool, source: &str) -> anyhow::Result<Vec<CalendarEvent>> {
        let req = if report {
            self.http
                .request(reqwest::Method::from_bytes(b"REPORT").unwrap(), &self.url)
                .header("Content-Type", "application/xml; charset=utf-8")
                .header("Depth", "1")
                .body(REPORT_BODY)
        } else {
            self.http.get(&self.url).header("Accept", "text/calendar")
        };
        let req = match &self.auth {
            Some((u, p)) => req.basic_auth(u, Some(p)),
            None => req,
        };
        let mut resp = req.send().await.map_err(|e| anyhow::anyhow!("{}", e.without_url()))?;
        if !resp.status().is_success() {
            anyhow::bail!("HTTP {}", resp.status().as_u16());
        }
        let mut body = Vec::new();
        while let Some(chunk) = resp.chunk().await.map_err(|e| anyhow::anyhow!("{}", e.without_url()))? {
            body.extend_from_slice(&chunk);
            if body.len() > SUBSCRIPTION_MAX_BYTES {
                anyhow::bail!("réponse trop volumineuse");
            }
        }
        let text = String::from_utf8_lossy(&body).into_owned();
        let src = source.to_string();
        // analyse et dépliage hors du fil asynchrone : leur coût dépend du flux
        let mut events = tokio::task::spawn_blocking(move || {
            if report { parse_report(&text, &src) } else { parse_feed(&text, &src) }
        })
        .await??;
        // UID préfixé par la source : un abonnement n'écrase jamais l'événement d'une autre source
        for e in &mut events {
            e.uid = format!("{source}:{}", e.uid);
        }
        Ok(events)
    }
}

/// Au plus 3 redirections, jamais vers une adresse privée ou de boucle locale.
// ponytail: seules les adresses IP littérales sont contrôlées ; un nom résolu vers le
// réseau privé passe. Résolution contrôlée si un abonnement non fiable arrive un jour.
fn redirect_policy() -> reqwest::redirect::Policy {
    reqwest::redirect::Policy::custom(|a| {
        if a.previous().len() >= 3 {
            a.error("trop de redirections")
        } else if forbidden_host(a.url().host_str().unwrap_or("")) {
            a.error("redirection refusée")
        } else {
            a.follow()
        }
    })
}

/// Hôte local ou privé : boucle locale, réseau privé, lien local, espace partagé (100.64/10).
fn forbidden_host(host: &str) -> bool {
    use std::net::IpAddr;
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if host.is_empty() || host.eq_ignore_ascii_case("localhost") || host.to_ascii_lowercase().ends_with(".localhost") {
        return true;
    }
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => {
            let o = ip.octets();
            ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified() || ip.is_broadcast()
                || (o[0] == 100 && (o[1] & 0xc0) == 64)
        }
        Ok(IpAddr::V6(ip)) => {
            let seg = ip.segments()[0];
            ip.is_loopback() || ip.is_unspecified() || (seg & 0xfe00) == 0xfc00 || (seg & 0xffc0) == 0xfe80
                || ip.to_ipv4_mapped().is_some_and(|v4| forbidden_host(&v4.to_string()))
        }
        Err(_) => false,
    }
}

/// Événements d'un flux iCalendar. Refuse ce qui n'est pas un VCALENDAR complet
/// (page de connexion, réponse tronquée…) : l'appelant remplace la source entière.
pub fn parse_feed(text: &str, source: &str) -> anyhow::Result<Vec<CalendarEvent>> {
    let has = |m: &str| text.lines().any(|l| l.trim().eq_ignore_ascii_case(m));
    if !(has("BEGIN:VCALENDAR") && has("END:VCALENDAR")) {
        anyhow::bail!("flux d'agenda : réponse inattendue");
    }
    parse_ics_now(text, source)
}

/// Événements d'une réponse REPORT. Refuse tout ce qui n'est pas un multistatus
/// (page de connexion, réponse tronquée…) : l'appelant remplace la source entière,
/// une réponse inattendue viderait donc l'agenda local. Un multistatus vide reste valide.
fn parse_report(xml: &str, source: &str) -> anyhow::Result<Vec<CalendarEvent>> {
    let is_multistatus = xml.split('<').skip(1).any(|tag| {
        let name = tag.split(|c: char| c.is_whitespace() || c == '>' || c == '/').next().unwrap_or("");
        name.rsplit(':').next() == Some("multistatus")
    });
    if !is_multistatus {
        anyhow::bail!("Calendar server REPORT: unexpected response");
    }
    let mut events = Vec::new();
    for block in extract_calendar_data(xml) {
        events.extend(parse_ics_now(&block, source)?);
        if events.len() > OCCURRENCES_MAX { anyhow::bail!("agenda : trop d'occurrences"); }
    }
    Ok(events)
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

    #[test]
    fn escape_ics_drops_control_chars() {
        let e = escape_ics("a\r\nDTSTART:1\u{0}b\tc;d");
        assert_eq!(e, "a\\nDTSTART:1b\tc\\;d");
        assert!(!e.contains('\r'));
    }
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

        let events = parse_ics(ics, SOURCE_PERSONAL);
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

        let events = parse_ics(ics, SOURCE_PERSONAL);
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

        let events = parse_ics(ics, SOURCE_PERSONAL);
        assert_eq!(events.len(), 2);
    }

    #[test]
    fn test_parse_ics_empty() {
        let events = parse_ics("BEGIN:VCALENDAR\nEND:VCALENDAR", SOURCE_PERSONAL);
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

        let events = parse_ics(ics, SOURCE_PERSONAL);
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
        let events = parse_ics(ics, SOURCE_PERSONAL);
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
        let events2 = parse_ics(ics2, SOURCE_PERSONAL);
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

        let events: Vec<_> = blocks.iter().flat_map(|b| parse_ics(b, SOURCE_PERSONAL)).collect();
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

    #[test]
    fn report_must_be_multistatus() {
        // une réponse 2xx qui n'est pas un multistatus ne doit jamais vider l'agenda local
        assert!(parse_report("<html><body>Connexion</body></html>", SOURCE_PERSONAL).is_err());
        assert!(parse_report("", SOURCE_PERSONAL).is_err());
        let empty = "<?xml version='1.0'?><D:multistatus xmlns:D=\"DAV:\"/>";
        assert!(parse_report(empty, SOURCE_PERSONAL).unwrap().is_empty());
        let one = "<multistatus xmlns=\"DAV:\"><response><C:calendar-data>BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:x\nDTSTART:20260709T090000\nDTEND:20260709T100000\nEND:VEVENT\nEND:VCALENDAR</C:calendar-data></response></multistatus>";
        assert_eq!(parse_report(one, SOURCE_PERSONAL).unwrap().len(), 1);
    }

    #[test]
    fn parse_ics_keeps_given_source() {
        let ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:w1\nDTSTART:20260709T090000\nDTEND:20260709T100000\nSUMMARY:X\nEND:VEVENT\nEND:VCALENDAR";
        let events = parse_ics(ics, SOURCE_WORK);
        assert_eq!(events[0].source, "work");
        assert_eq!(parse_ics(ics, SOURCE_PERSONAL)[0].source, "calendar");
    }

    fn day(y: i32, m: u32, d: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, d).unwrap()
    }

    #[test]
    fn subscription_urls_split_and_webcal() {
        let urls = subscription_urls("WebCal://agenda.example.com/a.ics, HTTPS://example.com/B.ics\nhttp://example.com/c.ics  ");
        assert_eq!(urls, ["https://agenda.example.com/a.ics", "https://example.com/B.ics"]);
        assert!(subscription_urls("").is_empty());
        // source stable, dérivée de l'adresse, jamais de la position
        let a = subscription_source("https://example.com/a.ics");
        assert_eq!(a, subscription_source("https://example.com/a.ics"));
        assert_ne!(a, subscription_source("https://example.com/b.ics"));
        assert!(a.starts_with("subscription-") && a.len() == "subscription-".len() + 8, "{a}");
        assert!(is_subscription(&a) && is_subscription("subscription") && !is_subscription("calendar"));
    }

    #[test]
    fn unclosed_component_is_an_error() {
        let ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:x\nDTSTART:20261103T120000\nBEGIN:VALARM\nTRIGGER:-PT5M\nEND:VEVENT\nEND:VCALENDAR\n";
        assert!(parse_feed(ics, SOURCE_SUBSCRIPTION).is_err());
        let xml = format!("<multistatus xmlns=\"DAV:\"><response><C:calendar-data>{}</C:calendar-data></response></multistatus>", ics.replace('\n', "\r\n"));
        assert!(parse_report(&xml, SOURCE_SUBSCRIPTION).is_err());
    }

    #[test]
    fn transparent_events_are_flagged() {
        let ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:t\nDTSTART;VALUE=DATE:20261103\nTRANSP:TRANSPARENT\nEND:VEVENT\nBEGIN:VEVENT\nUID:o\nDTSTART;VALUE=DATE:20261103\nTRANSP:OPAQUE\nEND:VEVENT\nEND:VCALENDAR";
        let e = parse_feed(ics, SOURCE_SUBSCRIPTION).unwrap();
        assert!(e[0].transparent && !e[1].transparent);
    }

    #[test]
    fn expansion_cost_is_bounded() {
        let today = day(2026, 11, 17);
        let series = |rule: &str| format!("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:s\nDTSTART;VALUE=DATE:00010101\nRRULE:{rule}\nEND:VEVENT\nEND:VCALENDAR");
        // début très ancien sans COUNT : seule la fenêtre est parcourue
        let t0 = std::time::Instant::now();
        let e = parse_ics_around(&series("FREQ=DAILY"), SOURCE_SUBSCRIPTION, today).unwrap();
        assert!(e.len() > 350 && e.len() < 400, "{}", e.len());
        assert!(t0.elapsed() < std::time::Duration::from_millis(500), "{:?}", t0.elapsed());
        // avec COUNT : parcours plafonné, rien d'aussi ancien n'atteint la fenêtre
        assert!(parse_ics_around(&series("FREQ=DAILY;COUNT=999999999"), SOURCE_SUBSCRIPTION, today).unwrap().is_empty());
        // INTERVAL nul ou absurde : première occurrence seule, ou aucune dans la fenêtre
        assert_eq!(parse_ics_around(&series("FREQ=DAILY;INTERVAL=0"), SOURCE_SUBSCRIPTION, today).unwrap().len(), 1);
        assert_eq!(parse_ics_around(&series("FREQ=DAILY;INTERVAL=99999999999999999999"), SOURCE_SUBSCRIPTION, today).unwrap().len(), 1);
        assert!(parse_ics_around(&series("FREQ=DAILY;INTERVAL=9223372036854775807"), SOURCE_SUBSCRIPTION, today).unwrap().len() <= 1);
        // flux qui dépasse le plafond total : refusé en entier
        let many: String = (0..60).map(|i| format!("BEGIN:VEVENT\nUID:s{i}\nDTSTART;VALUE=DATE:20261101\nRRULE:FREQ=DAILY\nEND:VEVENT\n")).collect();
        let feed = format!("BEGIN:VCALENDAR\n{many}END:VCALENDAR\n");
        assert!(parse_feed(&feed, SOURCE_SUBSCRIPTION).is_err());
    }

    #[test]
    fn feed_must_be_a_vcalendar() {
        // une réponse qui n'est pas un VCALENDAR ne doit jamais vider la source
        assert!(parse_feed("<html><body>Connexion</body></html>", SOURCE_SUBSCRIPTION).is_err());
        assert!(parse_feed("", SOURCE_SUBSCRIPTION).is_err());
        assert!(parse_feed("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:x\n", SOURCE_SUBSCRIPTION).is_err(), "tronqué");
        assert!(parse_feed("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", SOURCE_SUBSCRIPTION).unwrap().is_empty());
    }

    #[test]
    fn feed_all_day_multi_day_and_params() {
        let ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:we\r\nDTSTART;VALUE=DATE:20261121\r\nDTEND;VALUE=DATE:20261123\r\nSUMMARY;LANGUAGE=fr-FR:Week-end Exemple\r\nBEGIN:VALARM\r\nDESCRIPTION:Rappel\r\nEND:VALARM\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:soir\r\nDTSTART;TZID=\"Europe/Paris\":20261124T193000\r\nDTEND;TZID=\"Europe/Paris\":20261124T230000\r\nSUMMARY:Dîner Exemple\r\nLOCATION:example.com\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        let events = parse_feed(ics, SOURCE_SUBSCRIPTION).unwrap();
        assert_eq!(events.len(), 2);
        let we = &events[0];
        assert!(we.all_day);
        assert_eq!(we.summary.as_deref(), Some("Week-end Exemple"));
        assert!(we.description.is_none(), "la description de l'alarme n'est pas celle de l'événement");
        assert_eq!((we.start_time.date(), we.end_time.date()), (day(2026, 11, 21), day(2026, 11, 23)));
        assert_eq!(events[1].start_time.format("%H:%M").to_string(), "19:30");
        assert_eq!(events[1].source, SOURCE_SUBSCRIPTION);
    }

    #[test]
    fn weekly_recurrence_with_exdate_and_override() {
        // tous les mardis et jeudis 12:00-13:00 depuis le 03/11, sauf le 05/11 ; le 10/11 déplacé à 18:00
        let ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:r1\nDTSTART:20261103T120000\nDTEND:20261103T130000\nRRULE:FREQ=WEEKLY;BYDAY=TU,TH;COUNT=6\nEXDATE:20261105T120000\nSUMMARY:Déjeuner Exemple\nEND:VEVENT\nBEGIN:VEVENT\nUID:r1\nRECURRENCE-ID:20261110T120000\nDTSTART:20261110T180000\nDTEND:20261110T190000\nSUMMARY:Déplacé\nEND:VEVENT\nEND:VCALENDAR";
        let events = parse_ics_around(ics, SOURCE_SUBSCRIPTION, day(2026, 11, 17)).unwrap();
        let starts: Vec<String> = events.iter().map(|e| e.start_time.format("%d %H").to_string()).collect();
        // 03, (05 exclu), 10 → remplacé, 12, 17, 19 : six occurrences au total, COUNT compris l'exclue
        assert_eq!(starts, ["03 12", "12 12", "17 12", "19 12", "10 18"]);
        let uids: std::collections::HashSet<_> = events.iter().map(|e| e.uid.clone()).collect();
        assert_eq!(uids.len(), events.len(), "uid unique par occurrence");
    }

    #[test]
    fn daily_recurrence_until_interval_and_window() {
        let ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:d1\nDTSTART;VALUE=DATE:20261101\nRRULE:FREQ=DAILY;INTERVAL=2;UNTIL=20261107\nSUMMARY:Garde\nEND:VEVENT\nEND:VCALENDAR";
        let events = parse_ics_around(ics, SOURCE_SUBSCRIPTION, day(2026, 11, 17)).unwrap();
        let days: Vec<u32> = events.iter().map(|e| chrono::Datelike::day(&e.start_time.date())).collect();
        assert_eq!(days, [1, 3, 5, 7]);
        assert!(events.iter().all(|e| e.all_day));
        // série sans fin : bornée autour d'aujourd'hui
        let ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:d2\nDTSTART:20200101T080000\nDTEND:20200101T090000\nRRULE:FREQ=DAILY\nEND:VEVENT\nEND:VCALENDAR";
        let events = parse_ics_around(ics, SOURCE_SUBSCRIPTION, day(2026, 11, 17)).unwrap();
        assert!(events.len() > 350 && events.len() < 400, "{}", events.len());
        assert!(events[0].start_time.date() >= day(2026, 10, 17));
    }

    #[test]
    fn unsupported_recurrence_keeps_first_and_cancelled_dropped() {
        let ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:m\nDTSTART:20261103T120000\nRRULE:FREQ=MONTHLY\nEND:VEVENT\nBEGIN:VEVENT\nUID:c\nDTSTART:20261103T120000\nSTATUS:CANCELLED\nEND:VEVENT\nEND:VCALENDAR";
        let events = parse_ics_around(ics, SOURCE_SUBSCRIPTION, day(2026, 11, 17)).unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].uid, "m");
    }
}
