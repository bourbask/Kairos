use std::sync::Arc;

use kairos_alimentation::{
    caldav::CalDav,
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
    // Les trois variables ou aucune : un agenda à moitié configuré est ignoré.
    let caldav = match (var("ALIMENTATION_CALDAV_URL"), var("ALIMENTATION_CALDAV_USER"), var("ALIMENTATION_CALDAV_PASS")) {
        (Some(u), Some(user), Some(pass)) => Some(Arc::new(CalDav::new(&u, user, pass))),
        _ => None,
    };
    let settings = Arc::new(Settings { tz, caldav });
    tentacle_common::serve(env, move || FoodServer::new(store.clone(), off.clone(), settings.clone())).await
}
