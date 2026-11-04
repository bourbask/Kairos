use std::sync::Arc;

use kairos_alimentation::{
    off::Off,
    store::Store,
    tools::{FoodServer, Settings},
};
use tentacle_common::TentacleEnv;

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("ALIMENTATION")?;
    let store = Arc::new(Store::open(&var("ALIMENTATION_DB").unwrap_or_else(|| "/data/alimentation.db".into()))?);
    let off =
        Arc::new(Off::new(&var("ALIMENTATION_OFF_URL").unwrap_or_else(|| "https://world.openfoodfacts.org".into())));
    let tz = var("ALIMENTATION_TZ").and_then(|v| v.parse().ok()).unwrap_or(chrono_tz::Europe::Paris);
    let settings = Arc::new(Settings { tz });
    tentacle_common::serve(env, move || FoodServer::new(store.clone(), off.clone(), settings.clone())).await
}
