use std::sync::Arc;

use anyhow::Context;
use kairos_politique::{donnees::Donnees, tools::PolitiqueServer};
use tentacle_common::TentacleEnv;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("POLITIQUE")?;
    let dir = std::env::var("POLITIQUE_DONNEES").ok().filter(|v| !v.is_empty()).context("variable POLITIQUE_DONNEES absente ou vide")?;
    let donnees = Arc::new(Donnees::new(dir));
    // Premier chargement : le bilan (comptes seulement) part dans les journaux.
    donnees.etat();
    tentacle_common::serve(env, move || PolitiqueServer::new(donnees.clone())).await
}
