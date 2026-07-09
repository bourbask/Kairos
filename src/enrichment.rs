use crate::models::{JobOffer, EnrichedCompany};

pub struct Enricher {
    client: reqwest::Client,
}

impl Enricher {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::builder()
                .user_agent("Kairos/0.1 (job-search assistant)")
                .build()
                .unwrap(),
        }
    }

    pub async fn enrich(&self, offer: &JobOffer) -> EnrichedCompany {
        let website = self.find_website(&offer.company).await;
        let description = match website.as_ref() {
            Some(w) => self.scrape_description(w).await,
            None => None,
        };

        EnrichedCompany {
            name: offer.company.clone(),
            website,
            description,
            revenue: None,
            employees: None,
            executive: None,
        }
    }

    pub async fn enrich_many(&self, offers: &[JobOffer]) -> Vec<(String, EnrichedCompany)> {
        let mut results = Vec::new();
        for offer in offers {
            let enriched = self.enrich(offer).await;
            results.push((offer.id.clone(), enriched));
        }
        results
    }

    async fn find_website(&self, company: &str) -> Option<String> {
        let query = company.replace(' ', "+");
        let url = format!("https://html.duckduckgo.com/html/?q={query}+site:linkedin.com+company");

        match self.client.get(&url).send().await {
            Ok(resp) => {
                let html = resp.text().await.unwrap_or_default();
                extract_urls(&html).into_iter().next()
            }
            Err(_) => None,
        }
    }

    async fn scrape_description(&self, url: &str) -> Option<String> {
        match self.client.get(url).send().await {
            Ok(resp) if resp.status().is_success() => {
                let html = resp.text().await.unwrap_or_default();
                let doc = scraper::Html::parse_document(&html);
                let sel = scraper::Selector::parse("meta[name=description]").ok()?;
                doc.select(&sel)
                    .next()
                    .and_then(|el| el.value().attr("content"))
                    .map(|s| s.to_string())
            }
            _ => None,
        }
    }
}

fn extract_urls(html: &str) -> Vec<String> {
    let doc = scraper::Html::parse_document(html);
    let sel = scraper::Selector::parse("a[href]").unwrap();
    doc.select(&sel)
        .filter_map(|el| el.value().attr("href"))
        .filter(|h| h.contains("linkedin.com/company/"))
        .map(|h| h.to_string())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_urls() {
        let html = r#"<a href="https://www.linkedin.com/company/acme/">Acme</a>"#;
        let urls = extract_urls(html);
        assert_eq!(urls.len(), 1);
        assert!(urls[0].contains("linkedin.com/company/"));
    }

    #[test]
    fn test_extract_urls_none() {
        let html = "<a href='https://example.com'>Nothing</a>";
        let urls = extract_urls(html);
        assert!(urls.is_empty());
    }
}
