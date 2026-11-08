//! Outils MCP du tentacule agenda (lecture seule) : fine couche au-dessus de `Storage`.

use std::sync::{Arc, Mutex, MutexGuard};

use chrono::{Duration, NaiveDate, NaiveDateTime, Utc};
use kairos::{calendar::USER_TZ, models::CalendarEvent, storage::Storage};
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::{Deserialize, Serialize};
use tentacle_common::BriefPeriod;

use crate::slots::{DAY_END, DAY_START, free_slots};

/// Handler MCP ; bon marché à construire (le stockage est partagé).
#[derive(Clone)]
pub struct AgendaServer {
    store: Arc<Mutex<Storage>>,
    tool_router: ToolRouter<Self>,
}

impl AgendaServer {
    pub fn new(store: Arc<Mutex<Storage>>) -> Self {
        Self { store, tool_router: Self::tool_router() }
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

impl AgendaServer {
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
        let day = match a.day.as_deref().filter(|d| !d.trim().is_empty()) {
            Some(d) => parse_day(d)?,
            None => today_local(),
        };
        self.cards_json(day)
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
        let srv = AgendaServer::new(Arc::new(Mutex::new(store)));
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
        let srv = AgendaServer::new(Arc::new(Mutex::new(store)));
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
        let srv = AgendaServer::new(Arc::new(Mutex::new(store)));
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
