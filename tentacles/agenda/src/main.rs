use std::sync::{Arc, Mutex};

use anyhow::Context;
use kairos::storage::Storage;
use kairos_agenda::{tools::AgendaServer, write::Calendars};
use tentacle_common::TentacleEnv;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("AGENDA")?;
    let path = std::env::var("DATABASE_PATH").context("variable DATABASE_PATH absente")?;
    // lecture-écriture : synchronisation à la demande, ajout d'événement, tâche faite
    let store = Arc::new(Mutex::new(Storage::open(&path)?));
    let cal = Arc::new(Calendars::from_env());
    tentacle_common::serve(env, move || AgendaServer::new(store.clone(), cal.clone())).await
}
