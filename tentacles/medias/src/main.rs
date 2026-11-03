use std::sync::Arc;

use kairos_medias::{caldav, config::Stack, store::Store, tools::MediaServer, tracker, visio};
use tentacle_common::TentacleEnv;
use tokio::sync::OnceCell;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("MEDIAS")?;
    let stack = Arc::new(Stack::from_env()?);
    let db = std::env::var("MEDIAS_DB").ok().filter(|v| !v.is_empty()).unwrap_or_else(|| "/data/medias.db".into());
    let store = Arc::new(Store::open(&db)?);
    // Suivi des demandes et alerte disque, en tâche de fond.
    tokio::spawn(tracker::run(stack.clone(), store.clone()));
    // Calendrier des sorties poussé vers le serveur CalDAV, s'il est configuré.
    tokio::spawn(caldav::run(stack.clone(), store.clone()));
    // Débit réduit pendant les visios, si l'agenda à surveiller est déclaré.
    tokio::spawn(visio::run(stack.clone(), store.clone()));
    // Identifiant du serveur Plex (liens) : lu une fois, au premier besoin.
    let machine = Arc::new(OnceCell::new());
    tentacle_common::serve(env, move || MediaServer::new(stack.clone(), store.clone(), machine.clone())).await
}
