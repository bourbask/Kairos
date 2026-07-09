use chrono::{DateTime, Utc};
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DailyBriefing {
    pub date: chrono::NaiveDate,
    pub jobs: Vec<ScoredJob>,
    pub enrichment: Vec<(String, EnrichedCompany)>,
}
