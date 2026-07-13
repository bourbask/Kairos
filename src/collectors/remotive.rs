use async_trait::async_trait;
use chrono::{NaiveDateTime, Utc};
use serde::Deserialize;
use crate::collectors::Collector;
use crate::models::JobOffer;

#[derive(Debug, Deserialize)]
struct RemotiveResponse {
    jobs: Vec<RemotiveJob>,
}

#[derive(Debug, Deserialize)]
struct RemotiveJob {
    id: i64,
    title: String,
    company_name: String,
    description: String,
    url: String,
    candidate_required_location: Option<String>,
    salary: Option<String>,
    publication_date: Option<String>,
}

pub struct RemotiveCollector {
    client: reqwest::Client,
}

impl RemotiveCollector {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::new(),
        }
    }
}

#[async_trait]
impl Collector for RemotiveCollector {
    fn name(&self) -> &'static str {
        "remotive"
    }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        let mut all = Vec::new();
        let categories = ["software-dev", "devops", "customer-support"];

        for cat in &categories {
            let url = format!("https://remotive.com/api/remote-jobs?category={cat}&limit=50", cat = cat);
            let resp = self.client.get(&url).send().await;

            match resp {
                Ok(r) if r.status().is_success() => {
                    let body: RemotiveResponse = r.json().await?;
                    for job in body.jobs {
                        let published_at = job.publication_date
                            .and_then(|d| NaiveDateTime::parse_from_str(&d, "%Y-%m-%dT%H:%M:%S").ok())
                            .map(|dt| dt.and_utc());

                        all.push(JobOffer {
                            id: format!("remotive-{}", job.id),
                            source: "remotive".into(),
                            title: job.title,
                            company: job.company_name,
                            description: job.description,
                            url: job.url,
                            location: job.candidate_required_location.clone(),
                            country: None,
                            salary_min: None,
                            salary_max: None,
                            currency: None,
                            remote: Some(true),
                            published_at,
                            collected_at: Utc::now(),
                        });
                    }
                }
                Ok(r) => tracing::warn!("Remotive {}: HTTP {}", cat, r.status()),
                Err(e) => tracing::error!("Remotive {}: {}", cat, e),
            }
        }

        Ok(all)
    }
}
