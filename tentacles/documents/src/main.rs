use std::path::PathBuf;
use std::sync::Arc;

use kairos_documents::{
    calendar::CalDav,
    facts::Ollama,
    index::Index,
    tools::{DocServer, Settings, all_deadlines},
};
use tentacle_common::TentacleEnv;

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

/// Documents passés au modèle par passage (le reste attend le suivant).
const EXTRACT_BATCH: usize = 40;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let env = TentacleEnv::from_env("DOCUMENTS")?;
    let root = PathBuf::from(var("DOCUMENTS_ROOT").unwrap_or_else(|| "/docs".into()));
    if !root.is_dir() {
        anyhow::bail!("DOCUMENTS_ROOT n'est pas un dossier lisible");
    }
    let mut index = Index::open(&var("DOCUMENTS_DB").unwrap_or_else(|| "/data/documents.db".into()))?;
    index.ocr = var("DOCUMENTS_OCR").as_deref() != Some("0");
    let index = Arc::new(index);
    let private = var("DOCUMENTS_PRIVATE")
        .map(|v| v.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect())
        .unwrap_or_default();
    let tz = var("DOCUMENTS_TZ").and_then(|v| v.parse().ok()).unwrap_or(chrono_tz::Europe::Paris);
    let ollama = match (var("DOCUMENTS_OLLAMA_URL"), var("DOCUMENTS_MODEL")) {
        (Some(u), Some(m)) => Some(Ollama::new(&u, &m)),
        _ => None,
    };
    let calendar = match (var("DOCUMENTS_CALDAV_URL"), var("DOCUMENTS_CALDAV_USER"), var("DOCUMENTS_CALDAV_PASS")) {
        (Some(u), Some(us), Some(p)) => Some(CalDav::new(&u, us, p)),
        (Some(_), _, _) => {
            anyhow::bail!("DOCUMENTS_CALDAV_USER et DOCUMENTS_CALDAV_PASS requis avec DOCUMENTS_CALDAV_URL")
        }
        _ => None,
    };
    let settings = Arc::new(Settings {
        root: root.clone(),
        private,
        tz,
        model: ollama.as_ref().map(|o| o.model.clone()),
        write: var("DOCUMENTS_WRITE").as_deref() == Some("1"),
    });
    let minutes: u64 = var("DOCUMENTS_SCAN_MINUTES").and_then(|v| v.parse().ok()).filter(|m| *m > 0).unwrap_or(15);

    // Tâche de fond : inventaire (extraction de texte bloquante, hors du moteur
    // asynchrone), puis faits par le modèle local, puis calendrier des échéances.
    let (ix, st) = (index.clone(), settings.clone());
    tokio::spawn(async move {
        let mut every = tokio::time::interval(std::time::Duration::from_secs(minutes * 60));
        every.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            every.tick().await;
            let (i, r) = (ix.clone(), root.clone());
            let now = chrono::Utc::now().timestamp();
            match tokio::task::spawn_blocking(move || i.scan(&r, now)).await {
                Ok(Ok(r)) if r.added + r.updated + r.removed > 0 => eprintln!(
                    "documents : {} ajouté(s), {} modifié(s), {} retiré(s), {} inchangé(s)",
                    r.added, r.updated, r.removed, r.unchanged
                ),
                Ok(Ok(_)) => {}
                Ok(Err(e)) => eprintln!("documents : inventaire interrompu ({e})"),
                Err(_) => eprintln!("documents : inventaire interrompu"),
            }
            if let Some(o) = &ollama {
                let todo = ix.pending_facts(&o.model, EXTRACT_BATCH).unwrap_or_default();
                let (mut ok, mut ko) = (0, 0);
                for (path, name, body) in todo {
                    match o.extract(&name, &body).await {
                        Ok(f) => {
                            let _ = ix.set_facts(
                                &path,
                                &o.model,
                                serde_json::to_string(&f).ok().as_deref(),
                                chrono::Utc::now().timestamp(),
                            );
                            ok += 1;
                        }
                        // Modèle injoignable : on réessaiera au passage suivant.
                        Err(e) if e.contains("injoignable") => {
                            eprintln!("documents : extraction suspendue ({e})");
                            break;
                        }
                        Err(e) => {
                            eprintln!("documents : extraction en échec ({e})");
                            let _ = ix.set_facts(&path, &o.model, None, chrono::Utc::now().timestamp());
                            ko += 1;
                        }
                    }
                }
                if ok + ko > 0 {
                    eprintln!("documents : faits extraits pour {ok} document(s), {ko} en échec");
                }
            }
            if let Some(cal) = &calendar {
                let today = chrono::Utc::now().with_timezone(&st.tz).date_naive();
                let dl: Vec<_> = all_deadlines(&ix, &st, today).into_iter().map(|(d, _)| d).collect();
                match cal.sync(&ix, &dl).await {
                    Ok((w, r)) if w + r > 0 => eprintln!("documents : calendrier, {w} écrit(s), {r} retiré(s)"),
                    Ok(_) => {}
                    Err(e) => eprintln!("documents : calendrier non synchronisé ({e})"),
                }
            }
        }
    });
    tentacle_common::serve(env, move || DocServer::new(index.clone(), settings.clone())).await
}
