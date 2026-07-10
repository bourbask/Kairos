use crate::models::{JobOffer, EnrichedCompany};

pub struct Enricher {
    client: reqwest::Client,
}

impl Enricher {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::builder()
                .user_agent("Kairos/0.1 (job-search assistant)")
                .redirect(reqwest::redirect::Policy::none()) // pas de suivi de redirection (anti-SSRF)
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
        // `company` vient d'une source externe → encodage propre de la query, pas de concat brute.
        let mut url = reqwest::Url::parse("https://html.duckduckgo.com/html/").ok()?;
        url.query_pairs_mut()
            .append_pair("q", &format!("{company} site:linkedin.com company"));

        match self.client.get(url).send().await {
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
                    // contenu tiers non fiable : borner + retirer les caractères de contrôle.
                    .map(|s| s.chars().filter(|c| !c.is_control()).take(300).collect::<String>().trim().to_string())
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
        .filter(|h| is_company_url(h))
        .map(|h| h.to_string())
        .collect()
}

/// N'accepte que les URLs dont l'hôte est linkedin.com et le chemin commence par `/company/`.
/// Un contrôle par sous-chaîne laisserait passer `https://evil.tld/?x=linkedin.com/company/` (open-fetch/SSRF).
fn is_company_url(href: &str) -> bool {
    match reqwest::Url::parse(href) {
        Ok(u) => matches!(u.host_str(), Some("www.linkedin.com") | Some("linkedin.com"))
            && u.path().starts_with("/company/"),
        Err(_) => false,
    }
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

    #[test]
    fn test_extract_urls_rejects_lookalike() {
        // Anti-SSRF : hôte non-linkedin portant la sous-chaîne dans la query → rejeté.
        let html = r#"<a href="https://evil.tld/x?a=linkedin.com/company/acme">evil</a>"#;
        assert!(extract_urls(html).is_empty());
    }
}
