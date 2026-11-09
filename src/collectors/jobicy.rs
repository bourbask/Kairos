use async_trait::async_trait;
use chrono::Utc;
use serde::Deserialize;
use crate::collectors::{Collector, value_to_u32};
use crate::models::JobOffer;

#[derive(Debug, Deserialize)]
struct JobicyResponse {
    #[serde(default)]
    jobs: Vec<JobicyJob>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct JobicyJob {
    id: serde_json::Value,
    url: String,
    job_title: String,
    company_name: String,
    #[serde(default)] job_description: Option<String>,
    #[serde(default)] annual_salary_min: Option<serde_json::Value>,
    #[serde(default)] annual_salary_max: Option<serde_json::Value>,
    #[serde(default)] salary_currency: Option<String>,
    #[serde(default)] job_geo: Option<String>,
    #[serde(default)] pub_date: Option<String>,
}

pub struct JobicyCollector {
    client: reqwest::Client,
}

impl JobicyCollector {
    pub fn new() -> Self {
        Self { client: reqwest::Client::new() }
    }
}

#[async_trait]
impl Collector for JobicyCollector {
    fn name(&self) -> &'static str {
        "jobicy"
    }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        let mut out = Vec::new();
        for tag in ["dev", "engineering"] {
            let url = format!("https://jobicy.com/api/v2/remote-jobs?count=50&tag={tag}");
            match self.client.get(&url).send().await {
                Ok(r) if r.status().is_success() => {
                    let body: JobicyResponse = r.json().await?;
                    for j in body.jobs {
                        let id = j.id.as_i64().map(|n| n.to_string())
                            .or_else(|| j.id.as_str().map(String::from))
                            .unwrap_or_default();
                        out.push(JobOffer {
                            id: format!("jobicy-{id}"),
                            source: "jobicy".into(),
                            title: j.job_title,
                            company: j.company_name,
                            description: j.job_description.unwrap_or_default(),
                            url: j.url,
                            location: j.job_geo,
                            country: None,
                            salary_min: j.annual_salary_min.as_ref().and_then(value_to_u32),
                            salary_max: j.annual_salary_max.as_ref().and_then(value_to_u32),
                            currency: j.salary_currency,
                            remote: Some(true),
                            published_at: j.pub_date
                                .and_then(|d| chrono::DateTime::parse_from_rfc3339(&d).ok())
                                .map(|dt| dt.with_timezone(&Utc)),
                            collected_at: Utc::now(),
                        });
                    }
                }
                Ok(r) => tracing::warn!("Jobicy {}: HTTP {}", tag, r.status()),
                Err(e) => tracing::error!("Jobicy {}: {}", tag, e),
            }
        }
        Ok(out)
    }
}
