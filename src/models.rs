use chrono::{DateTime, NaiveDate, NaiveDateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JobOffer {
    pub id: String,
    pub source: String,
    pub title: String,
    pub company: String,
    pub description: String,
    pub url: String,
    pub location: Option<String>,
    pub country: Option<String>,
    pub salary_min: Option<u32>,
    pub salary_max: Option<u32>,
    pub currency: Option<String>,
    pub remote: Option<bool>,
    pub published_at: Option<DateTime<Utc>>,
    pub collected_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoredJob {
    pub offer: JobOffer,
    pub score: f64,
    pub breakdown: ScoreBreakdown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoreBreakdown {
    pub skills: f64,
    pub remote: f64,
    pub salary: f64,
    pub location: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnrichedCompany {
    pub name: String,
    pub website: Option<String>,
    pub description: Option<String>,
    pub revenue: Option<String>,
    pub employees: Option<u32>,
    pub executive: Option<String>,
}

// --- Planning / Agenda ---

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: i64,
    pub title: String,
    pub description: Option<String>,
    pub due_date: Option<NaiveDate>,
    pub priority: String,
    pub completed: bool,
    pub created_at: NaiveDate,
    pub completed_at: Option<NaiveDate>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Routine {
    pub id: i64,
    pub step_name: String,
    pub step_order: i32,
    pub estimated_minutes: Option<i32>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoutineLogEntry {
    pub id: i64,
    pub date: NaiveDate,
    pub routine_step_id: i64,
    pub completed: bool,
    pub completed_at: Option<NaiveDate>,
}

// --- Calendar ---

#[derive(Debug, Clone)]
pub struct CalendarEvent {
    pub uid: String,
    pub summary: Option<String>,
    pub description: Option<String>,
    pub location: Option<String>,
    pub start_time: NaiveDateTime,
    pub end_time: NaiveDateTime,
    pub all_day: bool,
    pub source: String,
    pub etag: Option<String>,
}

impl CalendarEvent {
    pub fn duration_minutes(&self) -> i64 {
        (self.end_time - self.start_time).num_minutes()
    }
}
