use std::sync::{Arc, Mutex};

use anyhow::Context;
use kairos_memoire::{store::MemoryStore, tools::MemoryServer};
use tentacle_common::TentacleEnv;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("MEMOIRE")?;
    let path = std::env::var("MEMOIRE_DB_PATH").context("variable MEMOIRE_DB_PATH absente")?;
    let store = Arc::new(Mutex::new(MemoryStore::open(&path)?));
    tentacle_common::serve(env, move || MemoryServer::new(store.clone())).await
}
