use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::Deserialize;
use crate::collectors::Collector;
use crate::models::JobOffer;

#[derive(Debug, Deserialize)]
struct AdzunaResponse {
    results: Vec<AdzunaJob>,
}

#[derive(Debug, Deserialize)]
struct AdzunaJob {
    id: String,
    title: String,
    company: AdzunaCompany,
    description: String,
    redirect_url: String,
    location: Option<AdzunaLocation>,
    salary_min: Option<f64>,
    salary_max: Option<f64>,
    salary_currency: Option<String>,
    salary_is_predicted: Option<String>,
    created: Option<String>,
}

#[derive(Debug, Deserialize)]
struct AdzunaCompany {
    display_name: String,
}

#[derive(Debug, Deserialize)]
struct AdzunaLocation {
    area: Vec<String>,
}

pub struct AdzunaCollector {
    app_id: String,
    app_key: String,
    countries: Vec<String>,
    client: reqwest::Client,
}

impl AdzunaCollector {
    pub fn new(app_id: String, app_key: String, countries: Vec<String>) -> Self {
        Self {
            app_id,
            app_key,
            countries,
            client: reqwest::Client::new(),
        }
    }

    fn build_url(&self, country: &str, page: u32) -> String {
        // permanent=1 → CDI only (pas de freelance/contract) ; category=it-jobs → tech only.
        // Pas de content-type ni full_description : Adzuna renvoie 400 sur ces params.
        format!(
            "https://api.adzuna.com/v1/api/jobs/{country}/search/{page}?app_id={}&app_key={}&results_per_page=50&permanent=1&category=it-jobs",
            self.app_id, self.app_key, country = country, page = page
        )
    }

    fn parse_datetime(s: &str) -> Option<DateTime<Utc>> {
        DateTime::parse_from_rfc3339(s).ok().map(|dt| dt.with_timezone(&Utc))
    }

}

#[async_trait]
impl Collector for AdzunaCollector {
    fn name(&self) -> &'static str {
        "adzuna"
    }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        let mut all = Vec::new();

        for country in &self.countries {
            // 2 pages/pays (limite volontaire).
            for page in 1..=2 {
                let url = self.build_url(country, page);
                let resp = self.client.get(&url).send().await;

                match resp {
                    Ok(r) if r.status().is_success() => {
                        let body: AdzunaResponse = r.json().await?;
                        if body.results.is_empty() {
                            break;
                        }
                        for job in body.results {
                            let offer = JobOffer {
                                id: format!("adzuna-{}", job.id),
                                source: "adzuna".into(),
                                title: job.title,
                                company: job.company.display_name,
                                description: job.description,
                                url: job.redirect_url,
                                location: job.location
                                    .and_then(|l| l.area.first().cloned()),
                                country: Some(country.to_uppercase()),
                                salary_min: job.salary_min.map(|s| s as u32),
                                salary_max: job.salary_max.map(|s| s as u32),
                                currency: job.salary_currency,
                                remote: None,
                                published_at: job
                                    .created
                                    .and_then(|c| Self::parse_datetime(&c)),
                                collected_at: Utc::now(),
                            };
                            all.push(offer);
                        }
                    }
                    Ok(r) => {
                        tracing::warn!("Adzuna {} page {}: HTTP {}", country, page, r.status());
                        break;
                    }
                    Err(e) => {
                        tracing::error!("Adzuna {} page {}: {}", country, page, e);
                        break;
                    }
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
    fn test_parse_datetime() {
        let dt = AdzunaCollector::parse_datetime("2025-01-15T10:30:00Z");
        assert!(dt.is_some());
        assert_eq!(dt.unwrap().format("%Y-%m-%d").to_string(), "2025-01-15");
    }

    #[test]
    fn test_parse_datetime_invalid() {
        assert!(AdzunaCollector::parse_datetime("not-a-date").is_none());
    }

    #[test]
    fn test_build_url() {
        let collector = AdzunaCollector::new(
            "test_id".into(),
            "test_key".into(),
            vec!["ch".into()],
        );
        let url = collector.build_url("ch", 1);
        assert!(url.contains("app_id=test_id"));
        assert!(url.contains("app_key=test_key"));
        assert!(url.contains("/ch/search/1"));
    }
}
