use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, bail};
use kairos_partage::{now, public, store::Store, tools::{ShareServer, clean_label}};
use tentacle_common::TentacleEnv;

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

fn hours(name: &str, default: i64) -> anyhow::Result<i64> {
    match var(name) {
        None => Ok(default),
        Some(v) => v.parse::<i64>().ok().filter(|h| *h > 0).with_context(|| format!("{name} : nombre d'heures positif attendu")),
    }
}

/// Adresse publique du site : https obligatoire.
fn site_url() -> anyhow::Result<String> {
    let url = var("PARTAGE_PUBLIC_URL").context("PARTAGE_PUBLIC_URL est obligatoire (adresse publique du site partagé)")?;
    if !url.starts_with("https://") || url.len() <= "https://".len() {
        bail!("PARTAGE_PUBLIC_URL doit commencer par https://");
    }
    Ok(url)
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let store = Arc::new(Store::open(var("PARTAGE_DB").unwrap_or_else(|| "/app/partage/partage.db".into()))?);
    let link_ttl = hours("PARTAGE_LIEN_HEURES", 72)? * 3600;
    let session_ttl = hours("PARTAGE_SESSION_HEURES", 30 * 24)? * 3600;

    // Administration depuis l'hôte, sans l'application : `kairos-partage creer <libellé>` ou `revoquer <id>`.
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["creer", label] => {
            let (id, token, _) = store.create(&clean_label(label).map_err(anyhow::Error::msg)?, now(), link_ttl)?;
            println!("{id} {}/entrer#k={token}", site_url()?.trim_end_matches('/'));
            return Ok(());
        }
        ["revoquer", id] => {
            let done = store.revoke(id.parse().context("identifiant numérique attendu")?, now())?;
            println!("{}", if done { "révoqué" } else { "inconnu ou déjà révoqué" });
            return Ok(());
        }
        [] => {}
        _ => bail!("usage : kairos-partage [creer <libellé> | revoquer <id>]"),
    }

    let env = TentacleEnv::from_env("PARTAGE")?;
    let site = site_url()?;
    let public_bind: SocketAddr = var("PARTAGE_PUBLIC_BIND").unwrap_or_else(|| "0.0.0.0:8102".into()).parse().context("PARTAGE_PUBLIC_BIND invalide")?;

    let purge = store.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_secs(3600));
        loop {
            tick.tick().await;
            let _ = purge.purge(now());
        }
    });

    let state = public::PublicState { store: store.clone(), session_ttl, now };
    let listener = tokio::net::TcpListener::bind(public_bind).await?;
    let public = async move { axum::serve(listener, public::router(state)).await.map_err(anyhow::Error::from) };
    let mcp = tentacle_common::serve(env, move || ShareServer::new(store.clone(), &site, link_ttl, now));
    tokio::try_join!(public, mcp)?;
    Ok(())
}
