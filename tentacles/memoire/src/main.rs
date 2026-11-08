use std::sync::{Arc, Mutex};

use anyhow::Context;
use chrono::Utc;
use kairos::calendar::USER_TZ;
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
    // Transcriptions résumées gardées N jours (0 = sans limite) ; purge au démarrage puis chaque jour.
    let days: i64 = match std::env::var("MEMOIRE_TRANSCRIPT_RETENTION_DAYS") {
        Ok(v) if !v.is_empty() => v.parse().context("MEMOIRE_TRANSCRIPT_RETENTION_DAYS invalide")?,
        _ => 90,
    };
    if days > 0 {
        let store = store.clone();
        tokio::spawn(async move {
            loop {
                let before = Utc::now().with_timezone(&USER_TZ).naive_local() - chrono::Duration::days(days);
                match store.lock().map_err(|_| anyhow::anyhow!("stockage indisponible")).and_then(|s| s.purge_transcripts(before)) {
                    Ok(n) if n > 0 => eprintln!("transcriptions purgées : {n}"),
                    Ok(_) => {}
                    Err(e) => eprintln!("purge des transcriptions : {e}"),
                }
                tokio::time::sleep(std::time::Duration::from_secs(24 * 3600)).await;
            }
        });
    }
    tentacle_common::serve(env, move || MemoryServer::new(store.clone())).await
}
