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

    /// Top N offres non présentées. Filtres durs : score >= min_score (SQL),
    /// full remote, hors freelance, hors blacklist. Puis re-rank par score
    /// pénalisé par l'ancienneté (offres récentes d'abord) — le seuil reste sur
    /// le score brut, une offre parfaite mais vieille reste éligible, juste rétrogradée.
    pub fn top_unpresented(&self, n: u32) -> Vec<ScoredJob> {
        // On récupère large au-dessus du seuil, on filtre, on re-classe, puis on garde n.
        let candidates = self.storage
            .get_unpresented_scored(n.saturating_mul(6), self.matcher.min_score())
            .unwrap_or_default();

        let now = Utc::now();
        let mut kept: Vec<(JobOffer, f64)> = candidates
            .into_iter()
            .filter(|(offer, _)| {
                !is_freelance(offer) && is_remote(offer) && !self.matcher.is_blacklisted(offer)
            })
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

/// Détecte une offre freelance/contract (l'utilisateur veut un CDI, sans statut freelance).
/// Heuristique par mots-clés sur titre + description.
// ponytail: schéma-libre ; migrer vers un champ employment_type structuré si l'imprécision gêne.
fn is_freelance(offer: &JobOffer) -> bool {
    let h = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
    const MARKERS: [&str; 7] = [
        "freelance", "freelancer", "contractor", "c2c", "corp-to-corp", "corp to corp", "self-employed",
    ];
    MARKERS.iter().any(|m| h.contains(m))
}

/// Full remote DUR (exigence utilisateur) : on ne présente que le remote confirmé.
/// Flag explicite s'il existe ; sinon mot-clé remote dans le texte ET pas d'« hybride ».
/// Strict par choix : mieux vaut rater une offre non taggée que polluer avec de l'on-site.
fn is_remote(offer: &JobOffer) -> bool {
    match offer.remote {
        Some(v) => v,
        None => {
            let h = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
            let hybride = h.contains("hybrid") || h.contains("hybride");
            let remote = h.contains("remote") || h.contains("télétravail")
                || h.contains("teletravail") || h.contains("home office") || h.contains("work from home");
            remote && !hybride
        }
    }
}

#[cfg(test)]
mod tests {
    use super::is_freelance;
    use crate::models::JobOffer;
    use chrono::Utc;

    fn offer(title: &str, desc: &str) -> JobOffer {
        JobOffer {
            id: "t".into(), source: "t".into(), title: title.into(), company: "C".into(),
            description: desc.into(), url: "u".into(), location: None, country: None,
            salary_min: None, salary_max: None, currency: None, remote: None,
            published_at: None, collected_at: Utc::now(),
        }
    }

    #[test]
    fn detecte_freelance() {
        assert!(is_freelance(&offer("Freelance Rust Developer", "")));
        assert!(is_freelance(&offer("Backend Dev", "This is a contractor / C2C role")));
    }

    #[test]
    fn cdi_non_exclu() {
        assert!(!is_freelance(&offer("Senior Full-Stack Engineer", "Permanent position, full time")));
    }

    #[test]
    fn remote_dur() {
        use super::is_remote;
        assert!(is_remote(&offer("Dev", "Fully remote position")));
        assert!(!is_remote(&offer("Dev", "On-site in Paris")));
        assert!(!is_remote(&offer("Dev", "Hybrid remote, 3 days office"))); // hybride exclu
    }

    #[test]
    fn recency_penalise_les_vieilles() {
        use super::recency_factor;
        use chrono::Duration;
        let now = Utc::now();
        assert_eq!(recency_factor(None, now), 1.0);                    // pas de date = neutre
        assert!((recency_factor(Some(now), now) - 1.0).abs() < 1e-9); // frais
        assert!((recency_factor(Some(now - Duration::days(30)), now) - 0.7).abs() < 1e-6);
        assert!((recency_factor(Some(now - Duration::days(100)), now) - 0.7).abs() < 1e-6); // plancher
        // récent > vieux
        assert!(recency_factor(Some(now - Duration::days(2)), now)
            > recency_factor(Some(now - Duration::days(20)), now));
    }
}
