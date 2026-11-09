use async_trait::async_trait;
use chrono::Utc;
use serde::Deserialize;
use crate::collectors::Collector;
use crate::models::JobOffer;

// RemoteOK renvoie un tableau dont le 1er élément est une notice légale (ni id ni position) → ignoré.
// CGU : créditer « Remote OK » + lien retour ; on présente l'URL de l'offre (lien retour OK).
#[derive(Debug, Deserialize)]
struct RemoteOkJob {
    #[serde(default)] id: Option<String>,
    #[serde(default)] position: Option<String>,
    #[serde(default)] company: Option<String>,
    #[serde(default)] description: Option<String>,
    #[serde(default)] url: Option<String>,
    #[serde(default)] location: Option<String>,
    #[serde(default)] salary_min: Option<f64>,
    #[serde(default)] salary_max: Option<f64>,
    #[serde(default)] date: Option<String>,
}

pub struct RemoteOkCollector {
    client: reqwest::Client,
}

impl RemoteOkCollector {
    pub fn new() -> Self {
        Self { client: reqwest::Client::new() }
    }
}

#[async_trait]
impl Collector for RemoteOkCollector {
    fn name(&self) -> &'static str {
        "remoteok"
    }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        // RemoteOK bloque l'User-Agent par défaut de reqwest.
        let resp = self.client
            .get("https://remoteok.com/api")
            .header("User-Agent", "kairos-jobbot/1.0 (+https://remoteok.com)")
            .send().await?;
        if !resp.status().is_success() {
            tracing::warn!("RemoteOK: HTTP {}", resp.status());
            return Ok(Vec::new());
        }

        let jobs: Vec<RemoteOkJob> = resp.json().await?;
        let mut out = Vec::new();
        for j in jobs {
            // 1er élément = notice légale → pas d'id/position → on saute.
            let (Some(id), Some(position)) = (j.id, j.position) else { continue };
            out.push(JobOffer {
                id: format!("remoteok-{id}"),
                source: "remoteok".into(),
                title: position,
                company: j.company.unwrap_or_default(),
                description: j.description.unwrap_or_default(),
                url: j.url.unwrap_or_else(|| format!("https://remoteok.com/l/{id}")),
                location: j.location,
                country: None,
                salary_min: j.salary_min.filter(|s| *s > 0.0).map(|s| s as u32),
                salary_max: j.salary_max.filter(|s| *s > 0.0).map(|s| s as u32),
                currency: Some("USD".into()),
                remote: Some(true),
                published_at: j.date
                    .and_then(|d| chrono::DateTime::parse_from_rfc3339(&d).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                collected_at: Utc::now(),
            });
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::RemoteOkJob;

    #[test]
    fn skip_notice_garde_offres() {
        let json = r#"[{"legal":"link back please"},
            {"id":"1","position":"Rust Dev","company":"X","url":"https://x","salary_min":50000,"salary_max":80000}]"#;
        let jobs: Vec<RemoteOkJob> = serde_json::from_str(json).unwrap();
        let valid: Vec<_> = jobs.into_iter().filter(|j| j.id.is_some() && j.position.is_some()).collect();
        assert_eq!(valid.len(), 1);
        assert_eq!(valid[0].position.as_deref(), Some("Rust Dev"));
    }
}
