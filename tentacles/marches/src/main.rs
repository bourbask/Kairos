use std::sync::Arc;
use std::time::Duration;

use kairos_marches::{api::Api, poll::{Topics, spawn_poller}, signals::Settings, store::Store, tools::MarketServer};
use tentacle_common::TentacleEnv;

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("MARCHES")?;
    let store = Arc::new(Store::open(var("MARCHES_DB").unwrap_or_else(|| "/data/marches.db".into()))?);
    let api_url = var("MARCHES_API_URL").ok_or_else(|| anyhow::anyhow!("MARCHES_API_URL est obligatoire (adresse de la source, à renseigner dans la configuration)"))?;
    let api = Arc::new(Api::new(&api_url));
    let keywords = var("MARCHES_KEYWORDS")
        .map(|v| v.split(',').map(|k| k.trim().to_string()).filter(|k| !k.is_empty()).collect())
        .unwrap_or_default();
    let topics = Arc::new(Topics { keywords, ..Topics::default() });
    let minutes: u64 = var("MARCHES_POLL_MINUTES").and_then(|v| v.parse().ok()).filter(|m| *m > 0).unwrap_or(30);
    let settings = Arc::new(Settings::default());
    spawn_poller(api, store.clone(), topics, settings.clone(), Duration::from_secs(minutes * 60));
    tentacle_common::serve(env, move || MarketServer::new(store.clone(), settings.clone())).await
}
