use std::sync::Arc;
use chrono::{DateTime, Utc};
use crate::models::{JobOffer, ScoredJob};
use crate::matching::Matcher;
use crate::storage::Storage;

pub struct Ranker {
    matcher: Matcher,
    storage: Arc<Storage>,
}

impl Ranker {
    pub fn new(matcher: Matcher, storage: Arc<Storage>) -> Self {
        Self { matcher, storage }
    }

    pub fn score_new_offers(&self, offers: Vec<JobOffer>) -> Vec<ScoredJob> {
        let scored = self.matcher.rank(offers);
        for job in &scored {
            if let Err(e) = self.storage.update_score(&job.offer.id, job.score) {
                tracing::warn!("Failed to update score for {}: {}", job.offer.id, e);
            }
        }
        scored
    }

    /// Top N offres non présentées. Filtres durs : score >= min_score (SQL, encode déjà
    /// full remote/hors freelance via matching.rs), hors blacklist (dynamique, non persisté).
    /// Puis re-rank par score pénalisé par l'ancienneté (offres récentes d'abord) — le seuil
    /// reste sur le score brut, une offre parfaite mais vieille reste éligible, juste rétrogradée.
    pub fn top_unpresented(&self, n: u32) -> Vec<ScoredJob> {
        // On récupère large au-dessus du seuil, on filtre, on re-classe, puis on garde n.
        let candidates = self.storage
            .get_unpresented_scored(n.saturating_mul(6), self.matcher.min_score())
            .unwrap_or_default();

        let now = Utc::now();
        let mut kept: Vec<(JobOffer, f64)> = candidates
            .into_iter()
            .filter(|(offer, _)| !self.matcher.is_blacklisted(offer))
            .collect();

        kept.sort_by(|a, b| {
            let ea = a.1 * recency_factor(a.0.published_at, now);
            let eb = b.1 * recency_factor(b.0.published_at, now);
            eb.partial_cmp(&ea).unwrap_or(std::cmp::Ordering::Equal)
        });

        kept.into_iter()
            .take(n as usize)
            .map(|(offer, score)| {
                let (_, breakdown) = self.matcher.score_with_breakdown(&offer);
                ScoredJob { offer, score, breakdown }
            })
            .collect()
    }
}

/// Facteur de fraîcheur ∈ [0.7, 1.0] appliqué au score au moment du classement.
/// Sans date de publication → 1.0 (neutre, fail-open : ne pas enterrer les offres
/// sans date, ex. ingestion email).
// ponytail: décroissance linéaire simple sur 30 jours ; ajuster la pente/plancher si besoin.
fn recency_factor(published_at: Option<DateTime<Utc>>, now: DateTime<Utc>) -> f64 {
    match published_at {
        Some(p) => {
            let age_days = (now - p).num_days().max(0) as f64;
            1.0 - (age_days.min(30.0) / 30.0) * 0.3
        }
        None => 1.0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Profile;

    #[test]
    fn recency_penalise_les_vieilles() {
        use chrono::Duration;
        let now = Utc::now();
        assert_eq!(recency_factor(None, now), 1.0);                    // pas de date = neutre
        assert!((recency_factor(Some(now), now) - 1.0).abs() < 1e-9); // frais
        assert!((recency_factor(Some(now - Duration::days(30)), now) - 0.7).abs() < 1e-6);
        assert!((recency_factor(Some(now - Duration::days(100)), now) - 0.7).abs() < 1e-6); // plancher
        assert!(recency_factor(Some(now - Duration::days(2)), now)
            > recency_factor(Some(now - Duration::days(20)), now));
    }

    fn offer(id: &str, company: &str) -> JobOffer {
        JobOffer {
            id: id.into(), source: "t".into(), title: "Dev".into(), company: company.into(),
            description: "Permanent position, fully remote".into(), url: "u".into(), location: None,
            country: None, salary_min: None, salary_max: None, currency: None,
            remote: Some(true), published_at: None, collected_at: Utc::now(),
        }
    }

    #[test]
    #[allow(clippy::arc_with_non_send_sync)]
    fn top_unpresented_filtre_la_blacklist() {
        let mut profile = Profile::from_file("config/profile.example.toml").unwrap();
        profile.filters.blacklist_companies = vec!["EvilCorp".into()];

        let storage = Arc::new(Storage::open(":memory:").unwrap());
        storage.insert_offers(&[offer("good", "GoodCo"), offer("bad", "EvilCorp GmbH")]).unwrap();
        storage.update_score("good", 0.60).unwrap();
        storage.update_score("bad", 0.60).unwrap();

        let ranker = Ranker::new(Matcher::new(profile), Arc::clone(&storage));
        let top = ranker.top_unpresented(10);

        assert_eq!(top.len(), 1);
        assert_eq!(top[0].offer.id, "good");
    }
}
