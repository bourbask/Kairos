pub mod arbeitnow;
pub mod jooble;
pub mod remotive;

use async_trait::async_trait;
use crate::models::JobOffer;

#[async_trait]
pub trait Collector: Send + Sync {
    fn name(&self) -> &'static str;
    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>>;
}
