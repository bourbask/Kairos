use std::sync::{Arc, Mutex};

use anyhow::Context;
use kairos::storage::Storage;
use kairos_agenda::tools::AgendaServer;
use tentacle_common::TentacleEnv;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("AGENDA")?;
    let path = std::env::var("DATABASE_PATH").context("variable DATABASE_PATH absente")?;
    let store = Arc::new(Mutex::new(Storage::open_readonly(&path)?));
    tentacle_common::serve(env, move || AgendaServer::new(store.clone())).await
}
