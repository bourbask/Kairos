use std::sync::Arc;

use kairos_medias::{config::Stack, tools::MediaServer};
use tentacle_common::TentacleEnv;
use tokio::sync::OnceCell;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("MEDIAS")?;
    let stack = Arc::new(Stack::from_env()?);
    // Identifiant du serveur Plex (liens) : lu une fois, au premier besoin.
    let machine = Arc::new(OnceCell::new());
    tentacle_common::serve(env, move || MediaServer::new(stack.clone(), machine.clone())).await
}
