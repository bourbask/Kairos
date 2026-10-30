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
struct BriefArgs {
    period: BriefPeriod,
}

#[tool_router]
impl AgendaServer {
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
}
