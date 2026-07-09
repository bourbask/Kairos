use std::sync::Arc;
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

    pub fn top_unpresented(&self, n: u32) -> Vec<ScoredJob> {
        let results = self.storage.get_unpresented_scored(n).unwrap_or_default();
        results
            .into_iter()
            .map(|(offer, score)| {
                let (_, breakdown) = self.matcher.score_with_breakdown(&offer);
                ScoredJob { offer, score, breakdown }
            })
            .collect()
    }
}
