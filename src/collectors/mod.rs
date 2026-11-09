pub mod arbeitnow;
pub mod adzuna;
pub mod remotive;
pub mod remoteok;
pub mod jobicy;
pub mod weworkremotely;
pub mod linkedin_email;

use async_trait::async_trait;
use crate::models::JobOffer;

#[async_trait]
pub trait Collector: Send + Sync {
    fn name(&self) -> &'static str;
    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>>;
}

/// Convertit un champ salaire JSON (nombre ou chaîne numérique) en u32 positif.
pub(crate) fn value_to_u32(v: &serde_json::Value) -> Option<u32> {
    let n = v.as_f64().or_else(|| v.as_str().and_then(|s| s.parse::<f64>().ok()))?;
    if n > 0.0 { Some(n as u32) } else { None }
}
