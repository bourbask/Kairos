use std::sync::{Arc, Mutex};

use anyhow::Context;
use kairos_memoire::{store::{MemoryStore, PERSONA_KEY}, tools::MemoryServer};
use tentacle_common::TentacleEnv;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let path = std::env::var("MEMOIRE_DB_PATH").context("variable MEMOIRE_DB_PATH absente")?;
    // `kairos-memoire persona "<texte>"` : seule voie d'écriture de la personnalité.
    let args: Vec<String> = std::env::args().skip(1).collect();
    if let [cmd, text] = args.as_slice()
        && cmd == "persona"
    {
        MemoryStore::open(&path)?.fact_set(PERSONA_KEY, text.trim(), "hote")?;
        println!("Personnalité enregistrée.");
        return Ok(());
    }
    let env = TentacleEnv::from_env("MEMOIRE")?;
    let store = Arc::new(Mutex::new(MemoryStore::open(&path)?));
    tentacle_common::serve(env, move || MemoryServer::new(store.clone())).await
}
