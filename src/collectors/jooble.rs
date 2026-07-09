use async_trait::async_trait;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use crate::collectors::Collector;
use crate::models::JobOffer;

#[derive(Debug, Serialize)]
struct JoobleRequest {
    keywords: String,
    location: String,
}

#[derive(Debug, Deserialize)]
struct JoobleResponse {
    jobs: Vec<JoobleJob>,
    total_count: u32,
}

#[derive(Debug, Deserialize)]
struct JoobleJob {
    id: String,
    title: String,
    company: String,
    snippet: String,
    link: String,
    location: Option<String>,
    salary: Option<String>,
    updated: Option<String>,
}

pub struct JoobleCollector {
    api_key: String,
    countries: Vec<String>,
    client: reqwest::Client,
}

impl JoobleCollector {
    pub fn new(api_key: String, countries: Vec<String>) -> Self {
        Self {
            api_key,
            countries,
            client: reqwest::Client::new(),
        }
    }

    fn parse_salary(s: &str) -> (Option<u32>, Option<u32>, Option<String>) {
        let s = s.trim();
        let currency = if s.contains("€") { Some("EUR".into()) }
            else if s.contains("CHF") { Some("CHF".into()) }
            else if s.contains("$") { Some("USD".into()) }
            else { None };

        let digits_only: String = s.chars().filter(|c| c.is_ascii_digit() || *c == '-').collect();
        let parts: Vec<&str> = digits_only.split('-').map(|p| p.trim()).filter(|p| !p.is_empty()).collect();

        match parts.len() {
            2 => {
                let min = parts[0].parse::<u32>().ok();
                let max = parts[1].parse::<u32>().ok();
                (min, max, currency)
            }
            1 => {
                let val = parts[0].parse::<u32>().ok();
                (val, val, currency)
            }
            _ => (None, None, currency),
        }
    }
}

#[async_trait]
impl Collector for JoobleCollector {
    fn name(&self) -> &'static str {
        "jooble"
    }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        let mut all = Vec::new();

        for country in &self.countries {
            let payload = JoobleRequest {
                keywords: "full stack developer symfony react".into(),
                location: country.clone(),
            };

            let resp = self.client
                .post(format!("https://jooble.org/api/{}", self.api_key))
                .json(&payload)
                .send()
                .await;

            match resp {
                Ok(r) if r.status().is_success() => {
                    let body: JoobleResponse = r.json().await?;
                    for job in body.jobs {
                        let (sal_min, sal_max, currency) = job.salary
                            .as_deref()
                            .map(JoobleCollector::parse_salary)
                            .unwrap_or((None, None, None));

                        all.push(JobOffer {
                            id: format!("jooble-{}", job.id),
                            source: "jooble".into(),
                            title: job.title,
                            company: job.company,
                            description: job.snippet,
                            url: job.link,
                            location: job.location.clone(),
                            country: Some(country.clone()),
                            salary_min: sal_min,
                            salary_max: sal_max,
                            currency,
                            remote: None,
                            published_at: None,
                            collected_at: Utc::now(),
                        });
                    }
                }
                Ok(r) => tracing::warn!("Jooble {}: HTTP {}", country, r.status()),
                Err(e) => tracing::error!("Jooble {}: {}", country, e),
            }
        }

        Ok(all)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_salary_range() {
        let (min, max, currency) = JoobleCollector::parse_salary("50 000€ - 70 000€");
        assert_eq!(min, Some(50000));
        assert_eq!(max, Some(70000));
        assert_eq!(currency, Some("EUR".into()));
    }

    #[test]
    fn test_parse_salary_single() {
        let (min, max, _) = JoobleCollector::parse_salary("CHF 90000");
        assert_eq!(min, Some(90000));
        assert_eq!(max, Some(90000));
    }

    #[test]
    fn test_parse_salary_empty() {
        let (min, max, _) = JoobleCollector::parse_salary("");
        assert!(min.is_none());
        assert!(max.is_none());
    }
}
