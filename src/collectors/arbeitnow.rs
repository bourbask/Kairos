use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::Deserialize;
use crate::collectors::Collector;
use crate::models::JobOffer;

#[derive(Debug, Deserialize)]
struct ArbeitnowResponse {
    data: Vec<ArbeitnowJob>,
    meta: ArbeitnowMeta,
}

#[derive(Debug, Deserialize)]
struct ArbeitnowMeta {
    current_page: u32,
    per_page: u32,
}

#[derive(Debug, Deserialize)]
struct ArbeitnowJob {
    slug: String,
    title: String,
    company_name: String,
    description: String,
    remote: bool,
    url: String,
    tags: Vec<String>,
    location: String,
    created_at: i64,
}

pub struct ArbeitnowCollector {
    client: reqwest::Client,
}

impl ArbeitnowCollector {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::new(),
        }
    }

    fn extract_country(location: &str) -> Option<String> {
        let parts: Vec<&str> = location.split(',').collect();
        parts.last().map(|s| s.trim().to_string())
    }

    fn parse_tags(tags: &[String]) -> Option<bool> {
        let haystack: String = tags.join(" ").to_lowercase();
        if haystack.contains("remote") {
            Some(true)
        } else {
            None
        }
    }
}

#[async_trait]
impl Collector for ArbeitnowCollector {
    fn name(&self) -> &'static str {
        "arbeitnow"
    }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        let mut all = Vec::new();

        for page in 1..=5 {
            let url = format!("https://www.arbeitnow.com/api/job-board-api?page={}", page);
            let resp = self.client.get(&url).send().await;

            match resp {
                Ok(r) if r.status().is_success() => {
                    let body: ArbeitnowResponse = r.json().await?;
                    if body.data.is_empty() {
                        break;
                    }
                    for job in body.data {
                        let published_at = DateTime::from_timestamp(job.created_at, 0);
                        all.push(JobOffer {
                            id: format!("arbeitnow-{}", job.slug),
                            source: "arbeitnow".into(),
                            title: job.title,
                            company: job.company_name,
                            description: job.description,
                            url: job.url,
                            location: Some(job.location.clone()),
                            country: Self::extract_country(&job.location),
                            salary_min: None,
                            salary_max: None,
                            currency: None,
                            remote: Some(job.remote).or_else(|| Self::parse_tags(&job.tags)),
                            published_at,
                            collected_at: Utc::now(),
                        });
                    }
                }
                Ok(r) => {
                    tracing::warn!("Arbeitnow page {}: HTTP {}", page, r.status());
                    break;
                }
                Err(e) => {
                    tracing::error!("Arbeitnow page {}: {}", page, e);
                    break;
                }
            }
        }

        Ok(all)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_country() {
        assert_eq!(ArbeitnowCollector::extract_country("Berlin, Germany"), Some("Germany".into()));
        assert_eq!(ArbeitnowCollector::extract_country("Zurich, Switzerland"), Some("Switzerland".into()));
        assert_eq!(ArbeitnowCollector::extract_country("Paris"), Some("Paris".into()));
    }

    #[test]
    fn test_parse_tags_remote() {
        let tags = vec!["Remote".into(), "Dev".into()];
        assert_eq!(ArbeitnowCollector::parse_tags(&tags), Some(true));
    }

    #[test]
    fn test_parse_tags_no_remote() {
        let tags = vec!["On-site".into()];
        assert_eq!(ArbeitnowCollector::parse_tags(&tags), None);
    }
}
