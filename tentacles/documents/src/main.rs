use std::path::PathBuf;
use std::sync::Arc;

use kairos_documents::{
    index::Index,
    tools::{DocServer, Settings},
};
use tentacle_common::TentacleEnv;

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("DOCUMENTS")?;
    let root = PathBuf::from(var("DOCUMENTS_ROOT").unwrap_or_else(|| "/docs".into()));
    if !root.is_dir() {
        anyhow::bail!("DOCUMENTS_ROOT n'est pas un dossier lisible");
    }
    let index = Arc::new(Index::open(&var("DOCUMENTS_DB").unwrap_or_else(|| "/data/documents.db".into()))?);
    let private = var("DOCUMENTS_PRIVATE")
        .map(|v| v.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect())
        .unwrap_or_default();
    let tz = var("DOCUMENTS_TZ").and_then(|v| v.parse().ok()).unwrap_or(chrono_tz::Europe::Paris);
    let settings = Arc::new(Settings { root: root.clone(), private, tz });
    let minutes: u64 = var("DOCUMENTS_SCAN_MINUTES").and_then(|v| v.parse().ok()).filter(|m| *m > 0).unwrap_or(15);

    // Inventaire périodique en tâche de fond (extraction de texte bloquante : hors du moteur asynchrone).
    let ix = index.clone();
    tokio::spawn(async move {
        let mut every = tokio::time::interval(std::time::Duration::from_secs(minutes * 60));
        every.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            every.tick().await;
            let (ix, root) = (ix.clone(), root.clone());
            let now = chrono::Utc::now().timestamp();
            match tokio::task::spawn_blocking(move || ix.scan(&root, now)).await {
                Ok(Ok(r)) if r.added + r.updated + r.removed > 0 => eprintln!(
                    "documents : {} ajouté(s), {} modifié(s), {} retiré(s), {} inchangé(s)",
                    r.added, r.updated, r.removed, r.unchanged
                ),
                Ok(Ok(_)) => {}
                Ok(Err(e)) => eprintln!("documents : inventaire interrompu ({e})"),
                Err(_) => eprintln!("documents : inventaire interrompu"),
            }
        }
    });
    tentacle_common::serve(env, move || DocServer::new(index.clone(), settings.clone())).await
}
