use async_trait::async_trait;
use chrono::Utc;
use serde::Deserialize;
use crate::collectors::Collector;
use crate::models::JobOffer;

// Flux RSS 2.0. Titre au format "Company: Poste".
#[derive(Debug, Deserialize)]
struct Rss {
    channel: Channel,
}

#[derive(Debug, Deserialize)]
struct Channel {
    #[serde(default, rename = "item")]
    items: Vec<Item>,
}

#[derive(Debug, Deserialize)]
struct Item {
    #[serde(default)] title: String,
    #[serde(default)] link: String,
    #[serde(default)] description: String,
    #[serde(default, rename = "pubDate")] pub_date: Option<String>,
}

pub struct WeWorkRemotelyCollector {
    client: reqwest::Client,
}

impl WeWorkRemotelyCollector {
    pub fn new() -> Self {
        Self { client: reqwest::Client::new() }
    }
}

#[async_trait]
impl Collector for WeWorkRemotelyCollector {
    fn name(&self) -> &'static str {
        "weworkremotely"
    }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        let mut out = Vec::new();
        let feeds = [
            "remote-programming-jobs",
            "remote-back-end-programming-jobs",
            "remote-front-end-programming-jobs",
            "remote-full-stack-programming-jobs",
            "remote-devops-sysadmin-jobs",
        ];
        for feed in feeds {
            let url = format!("https://weworkremotely.com/categories/{feed}.rss");
            match self.client.get(&url).send().await {
                Ok(r) if r.status().is_success() => {
                    let text = r.text().await?;
                    let rss: Rss = match quick_xml::de::from_str(&text) {
                        Ok(v) => v,
                        Err(e) => { tracing::warn!("WWR {} parse: {}", feed, e); continue; }
                    };
                    for it in rss.channel.items {
                        let (company, title) = it.title
                            .split_once(':')
                            .map(|(c, t)| (c.trim().to_string(), t.trim().to_string()))
                            .unwrap_or_else(|| (String::new(), it.title.clone()));
                        let id = it.link.trim_end_matches('/').rsplit('/').next()
                            .unwrap_or(&it.link).to_string();
                        out.push(JobOffer {
                            id: format!("wwr-{id}"),
                            source: "weworkremotely".into(),
                            title,
                            company,
                            description: it.description,
                            url: it.link,
                            location: None,
                            country: None,
                            salary_min: None,
                            salary_max: None,
                            currency: None,
                            remote: Some(true),
                            published_at: it.pub_date
                                .and_then(|d| chrono::DateTime::parse_from_rfc2822(&d).ok())
                                .map(|dt| dt.with_timezone(&Utc)),
                            collected_at: Utc::now(),
                        });
                    }
                }
                Ok(r) => tracing::warn!("WWR {}: HTTP {}", feed, r.status()),
                Err(e) => tracing::error!("WWR {}: {}", feed, e),
            }
        }
        Ok(out)
    }
}
