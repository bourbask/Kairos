//! Outils MCP du tentacule agenda : fine couche au-dessus de `Storage` ; les outils `app_*`
//! servent l'application (synchronisation à la demande, ajout d'événement, tâche faite).

use std::sync::{Arc, Mutex, MutexGuard};

use chrono::{Duration, NaiveDate, NaiveDateTime, NaiveTime, TimeZone, Utc};
use kairos::{
    calendar::{CalendarClient, SOURCE_WORK, USER_TZ},
    models::CalendarEvent,
    storage::Storage,
};
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tentacle_common::BriefPeriod;

use crate::{
    slots::{DAY_END, DAY_START, free_slots},
    write::{Cal, Calendars, LastSync, Span, ics, stable_uid},
};

/// Intervalle minimal entre deux synchronisations à la demande.
const SYNC_EVERY: std::time::Duration = std::time::Duration::from_secs(60);
/// Budget total d'une synchronisation à la demande, sous le délai d'appel d'outil de la tête (20 s).
const SYNC_BUDGET: std::time::Duration = std::time::Duration::from_secs(15);
// ponytail: seuil fixe des plages libres affichées ; préférence le jour où elle sert
const FREE_MIN: i64 = 30;

/// Handler MCP ; bon marché à construire (le stockage est partagé).
#[derive(Clone)]
pub struct AgendaServer {
    store: Arc<Mutex<Storage>>,
    cal: Arc<Calendars>,
    tool_router: ToolRouter<Self>,
}

impl AgendaServer {
    pub fn new(store: Arc<Mutex<Storage>>, cal: Arc<Calendars>) -> Self {
        Self { store, cal, tool_router: Self::tool_router() }
    }

    fn db(&self) -> Result<MutexGuard<'_, Storage>, String> {
        self.store.lock().map_err(|_| "stockage indisponible".to_string())
    }
}

type Out = Result<String, String>;

fn json_out<T: Serialize>(v: T) -> Out {
    serde_json::to_string(&v).map_err(|e| e.to_string())
}

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

/// Date locale `YYYY-MM-DD HH:MM[:SS]`.
fn parse_dt(s: &str) -> Result<NaiveDateTime, String> {
    NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S")
        .or_else(|_| NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M"))
        .map_err(|_| format!("date invalide « {s} » (attendu AAAA-MM-JJ HH:MM)"))
}

fn parse_day(s: &str) -> Result<NaiveDate, String> {
    NaiveDate::parse_from_str(s, "%Y-%m-%d").map_err(|_| format!("jour invalide « {s} » (attendu AAAA-MM-JJ)"))
}

fn today_local() -> NaiveDate {
    Utc::now().with_timezone(&USER_TZ).date_naive()
}

fn event_line(e: &CalendarEvent) -> String {
    let when = if e.all_day {
        "journée".to_string()
    } else {
        format!("{}-{}", e.start_time.format("%H:%M"), e.end_time.format("%H:%M"))
    };
    format!("- {when} {}", e.summary.as_deref().unwrap_or("(sans titre)"))
}

#[derive(Deserialize, schemars::JsonSchema)]
struct BetweenArgs {
    from: String,
    to: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct FreeSlotsArgs {
    day: String,
    min_minutes: i64,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct UntilArgs {
    until: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct AtArgs {
    at: String,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct CardsArgs {
    /// Jour (AAAA-MM-JJ) ; aujourd'hui par défaut.
    day: Option<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct BriefArgs {
    period: BriefPeriod,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct WeekArgs {
    /// Premier jour (AAAA-MM-JJ) ; aujourd'hui par défaut.
    from: Option<String>,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct AddArgs {
    title: String,
    /// Jour (AAAA-MM-JJ).
    day: String,
    /// Heure de début (HH:MM), requise hors journée entière.
    start: Option<String>,
    /// Durée en minutes (60 par défaut).
    minutes: Option<i64>,
    all_day: Option<bool>,
    calendar: Cal,
}
#[derive(Deserialize, schemars::JsonSchema)]
struct TaskArgs {
    id: i64,
}

fn now_local() -> NaiveDateTime {
    Utc::now().with_timezone(&USER_TZ).naive_local()
}

fn opt_day(s: Option<&str>) -> Result<NaiveDate, String> {
    match s.filter(|d| !d.trim().is_empty()) {
        Some(d) => parse_day(d),
        None => Ok(today_local()),
    }
}

fn hhmm(t: NaiveDateTime) -> String {
    t.format("%H:%M").to_string()
}

fn title_of(e: &CalendarEvent) -> &str {
    e.summary.as_deref().unwrap_or("(sans titre)")
}

fn calendar_of(e: &CalendarEvent) -> &'static str {
    if e.source == SOURCE_WORK { "travail" } else { "perso" }
}

/// Événement vu depuis `day` : début / fin omis quand ils tombent un autre jour
/// (même règle que les cartes : « jusqu'à », « dès », « toute la journée »).
fn event_json(e: &CalendarEvent, day: NaiveDate) -> Value {
    let mut v = json!({"uid": e.uid, "title": title_of(e), "all_day": e.all_day, "calendar": calendar_of(e)});
    if !e.all_day && e.start_time.date() == day {
        v["start"] = hhmm(e.start_time).into();
    }
    if !e.all_day && e.end_time.date() == day {
        v["end"] = hhmm(e.end_time).into();
    }
    if let Some(p) = e.location.as_deref().filter(|l| !l.trim().is_empty()) {
        v["location"] = p.into();
    }
    v
}

fn bounds(day: NaiveDate) -> (NaiveDateTime, NaiveDateTime) {
    (day.and_time(DAY_START), day.and_time(DAY_END))
}

/// Saisie de titre : retours chariot normalisés, autres caractères de contrôle retirés.
fn clean_title(s: &str) -> Result<String, String> {
    let t: String = s.replace("\r\n", "\n").replace('\r', "\n").chars().filter(|c| *c == '\n' || !c.is_control()).collect();
    let t = t.trim().to_string();
    match t.chars().count() {
        0 => Err("titre vide".into()),
        n if n > 200 => Err("titre trop long (200 caractères au plus)".into()),
        _ => Ok(t),
    }
}

impl AgendaServer {
    fn events_of(&self, from: NaiveDate, days: i64) -> Result<Vec<CalendarEvent>, String> {
        self.db()?.get_events_between(from.and_time(NaiveTime::MIN), (from + Duration::days(days)).and_time(NaiveTime::MIN)).map_err(err)
    }

    /// Vue d'un jour ; `today` ajoute les tâches en retard au jour courant.
    fn day_json(&self, day: NaiveDate, today: NaiveDate) -> Out {
        let events = self.events_of(day, 1)?;
        let (start, end) = bounds(day);
        let free: Vec<_> = free_slots(&events, start, end, FREE_MIN)
            .into_iter()
            .map(|(s, e)| json!({"start": hhmm(s), "end": hhmm(e), "minutes": (e - s).num_minutes()}))
            .collect();
        let tasks: Vec<_> = self
            .db()?
            .get_tasks(false, false)
            .map_err(err)?
            .into_iter()
            .filter(|t| t.due_date.is_some_and(|d| d == day || (day == today && d < day)))
            .map(|t| json!({"id": t.id, "title": t.title, "due": t.due_date.map(|d| d.to_string())}))
            .collect();
        let events: Vec<_> = events.iter().map(|e| event_json(e, day)).collect();
        json_out(json!({"day": day.to_string(), "events": events, "free": free, "tasks": tasks}))
    }

    /// Sept jours à partir de `from` ; « à venir » = débuts postérieurs à `now`.
    fn week_json(&self, from: NaiveDate, now: NaiveDateTime) -> Out {
        let all = self.events_of(from, 7)?;
        let days: Vec<_> = (0..7)
            .map(|i| {
                let day = from + Duration::days(i);
                let (lo, hi) = (day.and_time(NaiveTime::MIN), (day + Duration::days(1)).and_time(NaiveTime::MIN));
                let evs: Vec<_> = all.iter().filter(|e| e.start_time < hi && e.end_time > lo).cloned().collect();
                let (start, end) = bounds(day);
                let free: i64 = free_slots(&evs, start, end, 0).iter().map(|(s, e)| (*e - *s).num_minutes()).sum();
                json!({
                    "day": day.to_string(),
                    "events": evs.iter().map(|e| event_json(e, day)).collect::<Vec<_>>(),
                    "busy_minutes": (end - start).num_minutes() - free,
                })
            })
            .collect();
        let upcoming: Vec<_> = all
            .iter()
            .filter(|e| e.start_time >= now.max(from.and_time(NaiveTime::MIN)))
            .map(|e| {
                let mut v = json!({"uid": e.uid, "title": title_of(e), "day": e.start_time.date().to_string(), "calendar": calendar_of(e)});
                if !e.all_day {
                    v["start"] = hhmm(e.start_time).into();
                }
                v
            })
            .collect();
        json_out(json!({"days": days, "upcoming": upcoming}))
    }

    /// Cartes du jour pour l'application : un événement ou une tâche due = une carte.
    fn cards_json(&self, day: NaiveDate) -> Out {
        let db = self.db()?;
        let events = db.get_events_between(day.and_time(chrono::NaiveTime::MIN), day.and_hms_opt(23, 59, 59).unwrap()).map_err(err)?;
        let mut cards: Vec<serde_json::Value> = events
            .iter()
            .map(|e| {
                let place = e.location.as_deref().filter(|l| !l.trim().is_empty());
                let (starts, ends) = (e.start_time.date() == day, e.end_time.date() == day);
                let (at, ranged) = (e.start_time.format("%H:%M"), e.end_time.format("%H:%M"));
                let hours = if e.all_day {
                    None
                } else {
                    Some(match (starts, ends) {
                        (true, true) => format!("{at}–{ranged}"),
                        (true, false) => format!("dès {at}"),
                        (false, true) => format!("jusqu'à {ranged}"),
                        (false, false) => "toute la journée".to_string(),
                    })
                };
                let body = match (hours, place) {
                    (Some(h), Some(p)) => Some(format!("{h} · {p}")),
                    (Some(h), None) => Some(h),
                    (None, Some(p)) => Some(p.to_string()),
                    (None, None) => None,
                };
                let mut c = serde_json::json!({
                    "id": format!("agenda-{}", e.uid), "kind": "info",
                    "title": e.summary.as_deref().unwrap_or("(sans titre)"),
                });
                if !e.all_day && starts {
                    c["time"] = e.start_time.format("%H:%M").to_string().into();
                }
                if let Some(b) = body {
                    c["body"] = b.into();
                }
                c
            })
            .collect();
        for t in db.get_tasks(false, false).map_err(err)?.iter().filter(|t| t.due_date == Some(day)) {
            cards.push(serde_json::json!({
                "id": format!("agenda-tache-{}", t.id), "kind": "info", "title": t.title,
                "chips": [{"text": "à faire", "tone": "attention"}],
            }));
        }
        json_out(cards)
    }
}

#[tool_router]
impl AgendaServer {
    #[tool(description = "Cartes JSON du jour pour l'application (événements et tâches dues).")]
    fn cards(&self, Parameters(a): Parameters<CardsArgs>) -> Out {
        self.cards_json(opt_day(a.day.as_deref())?)
    }

    #[tool(description = "Vue JSON d'un jour pour l'application : événements, plages libres, tâches dues.")]
    fn app_agenda_day(&self, Parameters(a): Parameters<CardsArgs>) -> Out {
        self.day_json(opt_day(a.day.as_deref())?, today_local())
    }

    #[tool(description = "Vue JSON de sept jours pour l'application : événements, temps occupé, à venir.")]
    fn app_agenda_week(&self, Parameters(a): Parameters<WeekArgs>) -> Out {
        self.week_json(opt_day(a.from.as_deref())?, now_local())
    }

    #[tool(description = "Resynchroniser l'agenda local depuis les calendriers (au plus une fois par minute).")]
    async fn app_agenda_sync(&self) -> Out {
        // verrou tenu pendant la synchronisation : deux appels simultanés n'en font qu'une
        let mut last = self.cal.last_sync.lock().await;
        if let Some(l) = last.as_ref().filter(|l| l.tried.elapsed() < SYNC_EVERY) {
            return json_out(json!({"synced": l.ok, "at": l.at}));
        }
        let collections = self.cal.collections();
        let mut ok = !collections.is_empty();
        let (user, pass) = self.cal.credentials();
        // une seule échéance pour toutes les collections : la réponse part avant le délai de la tête
        let deadline = tokio::time::Instant::now() + SYNC_BUDGET;
        for (url, source) in collections {
            // échec (réseau, statut, délai) : la base locale reste intacte, sans détail exposé
            let fetched = tokio::time::timeout_at(deadline, CalendarClient::new(url, user.clone(), pass.clone()).sync(source)).await;
            ok &= match fetched {
                Ok(Ok(events)) => self.db().and_then(|db| db.replace_calendar_events(source, &events).map_err(err)).is_ok(),
                _ => false,
            };
        }
        let at = if ok {
            Some(Utc::now().with_timezone(&USER_TZ).to_rfc3339_opts(chrono::SecondsFormat::Secs, false))
        } else {
            last.as_ref().and_then(|l| l.at.clone())
        };
        *last = Some(LastSync { tried: std::time::Instant::now(), ok, at: at.clone() });
        json_out(json!({"synced": ok, "at": at}))
    }

    #[tool(description = "Ajouter un événement au calendrier perso ou travail, puis à l'agenda local.")]
    async fn app_event_add(&self, Parameters(a): Parameters<AddArgs>) -> Out {
        let title = clean_title(&a.title)?;
        let day = parse_day(&a.day)?;
        let all_day = a.all_day.unwrap_or(false);
        let (span, start, end, key) = if all_day {
            (Span::Day(day), day.and_time(NaiveTime::MIN), (day + Duration::days(1)).and_time(NaiveTime::MIN), "journée".to_string())
        } else {
            let at = a.start.as_deref().ok_or("heure de début requise")?;
            let t = NaiveTime::parse_from_str(at, "%H:%M").map_err(|_| format!("heure invalide « {at} » (attendu HH:MM)"))?;
            let minutes = a.minutes.unwrap_or(60);
            if !(1..=1440).contains(&minutes) {
                return Err("durée invalide (1 à 1440 minutes)".into());
            }
            let s = USER_TZ.from_local_datetime(&day.and_time(t)).earliest().ok_or("heure inexistante ce jour-là")?.with_timezone(&Utc);
            let e = s + Duration::minutes(minutes);
            let local = |x: chrono::DateTime<Utc>| x.with_timezone(&USER_TZ).naive_local();
            (Span::Timed(s, e), local(s), local(e), format!("{at} {minutes}"))
        };
        let cal_name = match a.calendar {
            Cal::Perso => "perso",
            Cal::Travail => "travail",
        };
        let (url, source) = self.cal.collection(a.calendar).ok_or(format!("agenda {cal_name} non configuré"))?;
        let uid = stable_uid(&[cal_name, &title, &a.day, &key]);
        // sous le verrou de synchronisation : une synchro concurrente ne peut pas effacer l'ajout local
        let _sync = self.cal.last_sync.lock().await;
        self.cal.put(&url, &uid, ics(&uid, &title, &span, Utc::now())).await?;
        let event = CalendarEvent {
            uid: uid.clone(),
            summary: Some(title),
            description: None,
            location: None,
            start_time: start,
            end_time: end,
            all_day,
            source: source.into(),
            etag: None,
        };
        self.db()?.upsert_calendar_event(&event).map_err(err)?;
        json_out(json!({"message": "Événement ajouté.", "uid": uid}))
    }

    #[tool(description = "Marquer une tâche du planning comme faite.")]
    fn app_task_done(&self, Parameters(a): Parameters<TaskArgs>) -> Out {
        match self.db()?.complete_task(a.id).map_err(err)? {
            true => json_out(json!({"message": "Tâche faite."})),
            false => Err("tâche introuvable".into()),
        }
    }

    #[tool(description = "Lister les événements d'agenda entre deux instants locaux (AAAA-MM-JJ HH:MM).")]
    fn events_between(&self, Parameters(a): Parameters<BetweenArgs>) -> Out {
        let (from, to) = (parse_dt(&a.from)?, parse_dt(&a.to)?);
        json_out(self.db()?.get_events_between(from, to).map_err(err)?)
    }

    #[tool(description = "Trouver les plages libres d'un jour (AAAA-MM-JJ) d'au moins min_minutes minutes, entre 07:00 et 23:00.")]
    fn free_slots(&self, Parameters(a): Parameters<FreeSlotsArgs>) -> Out {
        let day = parse_day(&a.day)?;
        let (start, end) = (day.and_time(DAY_START), day.and_time(DAY_END));
        let events = self.db()?.get_events_between(start, end).map_err(err)?;
        let slots: Vec<_> = free_slots(&events, start, end, a.min_minutes)
            .into_iter()
            .map(|(s, e)| serde_json::json!({ "start": s.format("%Y-%m-%d %H:%M").to_string(), "end": e.format("%Y-%m-%d %H:%M").to_string() }))
            .collect();
        json_out(slots)
    }

    #[tool(description = "Lister les tâches non terminées dues au plus tard à une date (AAAA-MM-JJ).")]
    fn tasks_due(&self, Parameters(a): Parameters<UntilArgs>) -> Out {
        let until = parse_day(&a.until)?;
        let tasks: Vec<_> = self
            .db()?
            .get_tasks(false, false)
            .map_err(err)?
            .into_iter()
            .filter(|t| t.due_date.is_some_and(|d| d <= until))
            .collect();
        json_out(tasks)
    }

    #[tool(description = "Savoir si l'utilisateur est occupé à un instant local (AAAA-MM-JJ HH:MM) et si c'est pour le travail.")]
    fn busy_at(&self, Parameters(a): Parameters<AtArgs>) -> Out {
        let st = self.db()?.is_busy_at(parse_dt(&a.at)?).map_err(err)?;
        json_out(serde_json::json!({ "busy": st.busy, "work": st.work }))
    }

    #[tool(description = "Obtenir le texte court de la section agenda d'un briefing : aujourd'hui le matin, demain le soir.")]
    fn brief_section(&self, Parameters(a): Parameters<BriefArgs>) -> Out {
        let day = match a.period {
            BriefPeriod::Matin => today_local(),
            BriefPeriod::Soir => today_local() + Duration::days(1),
        };
        let db = self.db()?;
        let events = db.get_events_between(day.and_time(chrono::NaiveTime::MIN), day.and_hms_opt(23, 59, 59).unwrap()).map_err(err)?;
        let tasks = db.get_tasks(false, false).map_err(err)?;
        let lines = events.iter().map(event_line).chain(
            tasks.iter().filter(|t| t.due_date == Some(day)).map(|t| format!("- tâche : {}", t.title)),
        );
        Ok(lines.collect::<Vec<_>>().join("\n"))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for AgendaServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn busy_at_reports_busy_and_work() {
        let store = Storage::open(":memory:").unwrap();
        let day = NaiveDate::from_ymd_opt(2026, 1, 5).unwrap();
        store
            .upsert_calendar_event(&CalendarEvent {
                uid: "w".into(),
                summary: Some("réunion".into()),
                description: None,
                location: None,
                start_time: day.and_hms_opt(9, 0, 0).unwrap(),
                end_time: day.and_hms_opt(10, 0, 0).unwrap(),
                all_day: false,
                source: kairos::calendar::SOURCE_WORK.into(),
                etag: None,
            })
            .unwrap();
        let srv = AgendaServer::new(Arc::new(Mutex::new(store)), Default::default());
        let at = |s: &str| srv.busy_at(Parameters(AtArgs { at: s.into() }));
        assert_eq!(at("2026-01-05 09:30").unwrap(), r#"{"busy":true,"work":true}"#);
        assert_eq!(at("2026-01-05 11:00").unwrap(), r#"{"busy":false,"work":false}"#);
        assert!(at("pas une date").is_err());
    }

    fn ev(uid: &str, h: Option<(u32, u32)>, loc: Option<&str>) -> CalendarEvent {
        let day = NaiveDate::from_ymd_opt(2026, 1, 5).unwrap();
        let (s, e) = h.map_or((day.and_hms_opt(0, 0, 0).unwrap(), day.and_hms_opt(23, 59, 0).unwrap()), |(a, b)| {
            (day.and_hms_opt(a, 0, 0).unwrap(), day.and_hms_opt(b, 0, 0).unwrap())
        });
        CalendarEvent {
            uid: uid.into(),
            summary: Some(format!("ev {uid}")),
            description: None,
            location: loc.map(Into::into),
            start_time: s,
            end_time: e,
            all_day: h.is_none(),
            source: kairos::calendar::SOURCE_PERSONAL.into(),
            etag: None,
        }
    }

    fn cards(events: &[CalendarEvent], day: &str) -> Vec<serde_json::Value> {
        let store = Storage::open(":memory:").unwrap();
        for e in events {
            store.upsert_calendar_event(e).unwrap();
        }
        let srv = AgendaServer::new(Arc::new(Mutex::new(store)), Default::default());
        let out = srv.cards(Parameters(CardsArgs { day: Some(day.into()) })).unwrap();
        serde_json::from_str(&out).unwrap()
    }

    #[test]
    fn cards_lists_events_of_day() {
        let c = cards(&[ev("a", Some((9, 10)), Some("Salle 2")), ev("b", Some((14, 15)), None)], "2026-01-05");
        assert_eq!(c.len(), 2);
        assert_eq!(c[0]["id"], "agenda-a");
        assert_eq!(c[0]["time"], "09:00");
        assert_eq!(c[0]["title"], "ev a");
        assert_eq!(c[0]["body"], "09:00–10:00 · Salle 2");
        assert_eq!(c[1]["body"], "14:00–15:00");
    }

    #[test]
    fn all_day_event_has_no_time() {
        let c = cards(&[ev("j", None, None)], "2026-01-05");
        assert_eq!(c.len(), 1);
        assert!(c[0].get("time").is_none());
    }

    #[test]
    fn cards_empty_day() {
        assert_eq!(cards(&[ev("a", Some((9, 10)), None)], "2026-01-06"), Vec::<serde_json::Value>::new());
    }

    #[test]
    fn cards_include_tasks_due_that_day() {
        let store = Storage::open(":memory:").unwrap();
        let day = NaiveDate::from_ymd_opt(2026, 1, 5).unwrap();
        store.insert_task("payer", None, Some(day), "normal").unwrap();
        store.insert_task("autre jour", None, Some(day + Duration::days(1)), "normal").unwrap();
        let srv = AgendaServer::new(Arc::new(Mutex::new(store)), Default::default());
        let c: Vec<serde_json::Value> = serde_json::from_str(&srv.cards(Parameters(CardsArgs { day: Some("2026-01-05".into()) })).unwrap()).unwrap();
        assert_eq!(c.len(), 1);
        assert_eq!(c[0]["title"], "payer");
        assert_eq!(c[0]["chips"][0]["tone"], "attention");
        assert!(c[0].get("time").is_none());
    }

    fn ev_range(uid: &str, start: &str, end: &str, loc: Option<&str>) -> CalendarEvent {
        CalendarEvent { start_time: parse_dt(start).unwrap(), end_time: parse_dt(end).unwrap(), ..ev(uid, Some((9, 10)), loc) }
    }

    #[test]
    fn overnight_event_has_no_start_time_on_day_two() {
        let c = cards(&[ev_range("n", "2026-01-04 22:00", "2026-01-05 10:00", Some("Gare"))], "2026-01-05");
        assert!(c[0].get("time").is_none());
        assert_eq!(c[0]["body"], "jusqu'à 10:00 · Gare");
        let c = cards(&[ev_range("m", "2026-01-04 22:00", "2026-01-06 10:00", None)], "2026-01-05");
        assert!(c[0].get("time").is_none());
        assert_eq!(c[0]["body"], "toute la journée");
    }

    #[test]
    fn event_running_past_midnight_says_from() {
        let c = cards(&[ev_range("n", "2026-01-05 22:00", "2026-01-06 02:00", None)], "2026-01-05");
        assert_eq!(c[0]["time"], "22:00");
        assert_eq!(c[0]["body"], "dès 22:00");
    }
}

#[cfg(test)]
mod app_tests {
    use super::*;
    use crate::write::Calendars;
    use serde_json::Value;

    type Seen = Arc<Mutex<Vec<(String, String, String)>>>;

    const REPORT: &str = "<multistatus xmlns=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><response><propstat><prop><C:calendar-data>BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:distant-1\nDTSTART:20260105T150000Z\nDTEND:20260105T160000Z\nSUMMARY:Distant\nEND:VEVENT\nEND:VCALENDAR\n</C:calendar-data></prop></propstat></response></multistatus>";

    /// Faux serveur CalDAV : note chaque requête (méthode, chemin, corps) et répond `status`.
    async fn fake_dav(status: u16) -> (String, Seen) {
        let seen: Seen = Default::default();
        let s = seen.clone();
        let app = axum::Router::new().fallback(move |m: axum::http::Method, u: axum::http::Uri, body: String| {
            let s = s.clone();
            async move {
                s.lock().unwrap().push((m.to_string(), u.path().to_string(), body));
                (axum::http::StatusCode::from_u16(status).unwrap(), REPORT)
            }
        });
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/cal/", l.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(l, app).await });
        (url, seen)
    }

    fn d(s: &str) -> NaiveDate {
        parse_day(s).unwrap()
    }

    fn event(uid: &str, start: &str, end: &str, all_day: bool, source: &str) -> CalendarEvent {
        CalendarEvent {
            uid: uid.into(),
            summary: Some(format!("ev {uid}")),
            description: None,
            location: None,
            start_time: parse_dt(start).unwrap(),
            end_time: parse_dt(end).unwrap(),
            all_day,
            source: source.into(),
            etag: None,
        }
    }

    fn server(events: &[CalendarEvent], cal: Calendars) -> AgendaServer {
        let store = Storage::open(":memory:").unwrap();
        for e in events {
            store.upsert_calendar_event(e).unwrap();
        }
        AgendaServer::new(Arc::new(Mutex::new(store)), Arc::new(cal))
    }

    fn day(srv: &AgendaServer, day: &str) -> Value {
        serde_json::from_str(&srv.day_json(d(day), d(day)).unwrap()).unwrap()
    }

    const PERSO: &str = kairos::calendar::SOURCE_PERSONAL;
    const WORK: &str = kairos::calendar::SOURCE_WORK;

    #[test]
    fn app_day_json_shape() {
        let mut a = event("a", "2026-01-05 09:00", "2026-01-05 10:00", false, PERSO);
        a.location = Some("Salle 2".into());
        let srv = server(&[a, event("w", "2026-01-05 14:00", "2026-01-05 15:00", false, WORK)], Calendars::default());
        let id = srv.db().unwrap().insert_task("payer", None, Some(d("2026-01-05")), "normal").unwrap();
        let v = day(&srv, "2026-01-05");
        assert_eq!(v["day"], "2026-01-05");
        let e = &v["events"][0];
        assert_eq!(e["uid"], "a");
        assert_eq!(e["title"], "ev a");
        assert_eq!(e["start"], "09:00");
        assert_eq!(e["end"], "10:00");
        assert_eq!(e["all_day"], false);
        assert_eq!(e["location"], "Salle 2");
        assert_eq!(e["calendar"], "perso");
        assert_eq!(v["events"][1]["calendar"], "travail");
        assert!(v["events"][1].get("location").is_none());
        assert_eq!(v["tasks"][0]["id"], id);
        assert_eq!(v["tasks"][0]["title"], "payer");
        assert_eq!(v["tasks"][0]["due"], "2026-01-05");
    }

    #[test]
    fn free_slots_in_day() {
        let srv = server(
            &[
                event("a", "2026-01-05 09:00", "2026-01-05 10:00", false, PERSO),
                event("b", "2026-01-05 14:00", "2026-01-05 15:00", false, WORK),
                event("j", "2026-01-05 00:00", "2026-01-06 00:00", true, PERSO),
            ],
            Calendars::default(),
        );
        let v = day(&srv, "2026-01-05");
        assert_eq!(
            v["free"],
            serde_json::json!([
                {"start": "07:00", "end": "09:00", "minutes": 120},
                {"start": "10:00", "end": "14:00", "minutes": 240},
                {"start": "15:00", "end": "23:00", "minutes": 480},
            ])
        );
    }

    #[test]
    fn multi_day_in_day_view() {
        let srv = server(
            &[
                event("veille", "2026-01-04 22:00", "2026-01-05 10:00", false, PERSO),
                event("soir", "2026-01-05 22:00", "2026-01-06 02:00", false, PERSO),
                event("long", "2026-01-04 08:00", "2026-01-07 08:00", false, PERSO),
                event("conges", "2026-01-04 00:00", "2026-01-08 00:00", true, PERSO),
                event("hier", "2026-01-04 08:00", "2026-01-05 00:00", false, PERSO),
            ],
            Calendars::default(),
        );
        let v = day(&srv, "2026-01-05");
        let by = |uid: &str| v["events"].as_array().unwrap().iter().find(|e| e["uid"] == uid).cloned();
        assert!(by("hier").is_none(), "fini à minuit pile : pas ce jour");
        let veille = by("veille").unwrap();
        assert!(veille.get("start").is_none());
        assert_eq!(veille["end"], "10:00");
        let soir = by("soir").unwrap();
        assert_eq!(soir["start"], "22:00");
        assert!(soir.get("end").is_none());
        let long = by("long").unwrap();
        assert!(long.get("start").is_none() && long.get("end").is_none());
        assert_eq!(long["all_day"], false);
        let conges = by("conges").unwrap();
        assert_eq!(conges["all_day"], true);
        assert!(conges.get("start").is_none());
        // l'événement de plusieurs jours occupe toute la journée : aucune plage libre
        assert_eq!(v["free"], serde_json::json!([]));
    }

    #[test]
    fn app_week_counts_and_upcoming() {
        let srv = server(
            &[
                event("passe", "2026-01-05 09:00", "2026-01-05 10:00", false, PERSO),
                event("aprem", "2026-01-05 14:00", "2026-01-05 15:00", false, WORK),
                event("jour", "2026-01-07 00:00", "2026-01-08 00:00", true, PERSO),
                event("loin", "2026-01-12 09:00", "2026-01-12 10:00", false, PERSO),
            ],
            Calendars::default(),
        );
        let now = parse_dt("2026-01-05 12:00").unwrap();
        let v: Value = serde_json::from_str(&srv.week_json(d("2026-01-05"), now).unwrap()).unwrap();
        let days = v["days"].as_array().unwrap();
        assert_eq!(days.len(), 7);
        assert_eq!(days[0]["day"], "2026-01-05");
        assert_eq!(days[6]["day"], "2026-01-11");
        assert_eq!(days[0]["events"].as_array().unwrap().len(), 2);
        assert_eq!(days[0]["busy_minutes"], 120);
        assert_eq!(days[2]["events"][0]["all_day"], true);
        assert_eq!(days[2]["busy_minutes"], 0);
        assert_eq!(days[1]["events"], serde_json::json!([]));
        assert_eq!(
            v["upcoming"],
            serde_json::json!([
                {"uid": "aprem", "title": "ev aprem", "day": "2026-01-05", "start": "14:00", "calendar": "travail"},
                {"uid": "jour", "title": "ev jour", "day": "2026-01-07", "calendar": "perso"},
            ])
        );
    }

    #[tokio::test]
    async fn sync_throttled() {
        let (url, seen) = fake_dav(207).await;
        let srv = server(&[], Calendars::new(Some(url), None, "u".into(), "p".into()));
        let first: Value = serde_json::from_str(&srv.app_agenda_sync().await.unwrap()).unwrap();
        assert_eq!(first["synced"], true);
        assert!(first["at"].is_string());
        let second: Value = serde_json::from_str(&srv.app_agenda_sync().await.unwrap()).unwrap();
        assert_eq!(second, first);
        assert_eq!(seen.lock().unwrap().len(), 1, "une seule synchronisation par minute");
        assert_eq!(seen.lock().unwrap()[0].0, "REPORT");
        assert_eq!(day(&srv, "2026-01-05")["events"][0]["uid"], "distant-1");
    }

    #[tokio::test]
    async fn sync_failure_keeps_local() {
        let (url, _) = fake_dav(500).await;
        let local = event("local", "2026-01-05 09:00", "2026-01-05 10:00", false, PERSO);
        let srv = server(&[local], Calendars::new(Some(url), None, "u".into(), "secret".into()));
        let out = srv.app_agenda_sync().await.unwrap();
        assert!(!out.contains("127.0.0.1") && !out.contains("secret"));
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["synced"], false);
        assert!(v["at"].is_null());
        assert_eq!(day(&srv, "2026-01-05")["events"][0]["uid"], "local");
        // injoignable : même réponse, aucune fuite
        let srv = server(&[], Calendars::new(Some("http://127.0.0.1:1/cal/".into()), None, "u".into(), "p".into()));
        let out = srv.app_agenda_sync().await.unwrap();
        assert!(!out.contains("127.0.0.1"));
    }

    /// Calendrier qui accepte la connexion mais ne répond jamais.
    async fn silent_dav() -> String {
        let app = axum::Router::new().fallback(|| std::future::pending::<&'static str>());
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/cal/", l.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(l, app).await });
        url
    }

    #[tokio::test(start_paused = true)]
    async fn sync_bounded_overall() {
        let srv = server(&[], Calendars::new(Some(silent_dav().await), Some(silent_dav().await), "u".into(), "p".into()));
        let t0 = tokio::time::Instant::now();
        let v: Value = serde_json::from_str(&srv.app_agenda_sync().await.unwrap()).unwrap();
        assert_eq!(v["synced"], false);
        assert!(t0.elapsed() < std::time::Duration::from_secs(18), "synchro bornée dans son ensemble : {:?}", t0.elapsed());
    }

    fn add_args(title: &str, cal: &str) -> AddArgs {
        serde_json::from_value(serde_json::json!({
            "title": title, "day": "2026-01-05", "start": "09:30", "minutes": 30, "calendar": cal,
        }))
        .unwrap()
    }

    #[tokio::test]
    async fn event_add_escapes_ical() {
        let (url, seen) = fake_dav(201).await;
        let srv = server(&[], Calendars::new(Some(url), None, "u".into(), "p".into()));
        let out: Value =
            serde_json::from_str(&srv.app_event_add(Parameters(add_args("a, b; c\\d\r\ne", "perso"))).await.unwrap()).unwrap();
        let uid = out["uid"].as_str().unwrap();
        let seen = seen.lock().unwrap();
        let (method, path, body) = &seen[0];
        assert_eq!(method, "PUT");
        assert_eq!(path, &format!("/cal/{uid}.ics"));
        assert!(body.contains(r"SUMMARY:a\, b\; c\\d\ne"), "{body}");
        assert!(body.contains(&format!("UID:{uid}\r\n")));
        // 09:30 à Paris en hiver = 08:30 UTC
        assert!(body.contains("DTSTART:20260105T083000Z\r\n"), "{body}");
        assert!(body.contains("DTEND:20260105T090000Z\r\n"), "{body}");
        assert!(body.lines().all(|l| l.len() <= 75));
    }

    #[tokio::test]
    async fn event_add_visible_immediately() {
        let (url, seen) = fake_dav(201).await;
        let srv = server(&[], Calendars::new(Some(url.clone()), Some(url), "u".into(), "p".into()));
        srv.app_event_add(Parameters(add_args("Dentiste", "travail"))).await.unwrap();
        // même saisie : même uid, pas de doublon
        srv.app_event_add(Parameters(add_args("Dentiste", "travail"))).await.unwrap();
        let all_day: AddArgs =
            serde_json::from_value(serde_json::json!({"title": "Congé", "day": "2026-01-05", "all_day": true, "calendar": "perso"}))
                .unwrap();
        srv.app_event_add(Parameters(all_day)).await.unwrap();
        assert!(seen.lock().unwrap()[2].2.contains("DTSTART;VALUE=DATE:20260105\r\nDTEND;VALUE=DATE:20260106\r\n"));
        let v = day(&srv, "2026-01-05");
        let evs = v["events"].as_array().unwrap();
        assert_eq!(evs.len(), 2);
        let dentiste = evs.iter().find(|e| e["title"] == "Dentiste").unwrap();
        assert_eq!(dentiste["start"], "09:30");
        assert_eq!(dentiste["end"], "10:00");
        assert_eq!(dentiste["calendar"], "travail");
        assert!(evs.iter().any(|e| e["title"] == "Congé" && e["all_day"] == true));
    }

    #[tokio::test]
    async fn event_add_rejects_bad_input_and_failed_write() {
        let (url, seen) = fake_dav(201).await;
        let srv = server(&[], Calendars::new(Some(url), None, "u".into(), "p".into()));
        assert!(srv.app_event_add(Parameters(add_args("  ", "perso"))).await.is_err());
        assert!(srv.app_event_add(Parameters(add_args("x", "travail"))).await.is_err(), "agenda travail absent");
        let no_start: AddArgs =
            serde_json::from_value(serde_json::json!({"title": "x", "day": "2026-01-05", "calendar": "perso"})).unwrap();
        assert!(srv.app_event_add(Parameters(no_start)).await.is_err());
        assert!(seen.lock().unwrap().is_empty());
        let (url, _) = fake_dav(500).await;
        let srv = server(&[], Calendars::new(Some(url), None, "u".into(), "p".into()));
        let e = srv.app_event_add(Parameters(add_args("x", "perso"))).await.unwrap_err();
        assert!(!e.contains("127.0.0.1"));
        assert_eq!(day(&srv, "2026-01-05")["events"], serde_json::json!([]), "rien en local si l'écriture échoue");
    }

    #[test]
    fn task_done_marks_task() {
        let srv = server(&[], Calendars::default());
        let id = srv.db().unwrap().insert_task("payer", None, Some(d("2026-01-05")), "normal").unwrap();
        let out: Value = serde_json::from_str(&srv.app_task_done(Parameters(TaskArgs { id })).unwrap()).unwrap();
        assert!(out["message"].is_string());
        assert!(srv.db().unwrap().get_tasks(false, false).unwrap().is_empty());
        assert!(srv.app_task_done(Parameters(TaskArgs { id: 999 })).is_err());
    }
}
