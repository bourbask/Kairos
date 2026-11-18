use std::net::SocketAddr;
use std::sync::Arc;

use anyhow::Context;
use kairos_politique::{
    donnees::Donnees,
    signalements::{limite::Limiteur, ouvrir_optionnel, public, store::Store},
    tools::PolitiqueServer,
};
use tentacle_common::TentacleEnv;

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

fn maintenant() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64)
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().with_writer(std::io::stderr).with_ansi(false).init();
    let env = TentacleEnv::from_env("POLITIQUE")?;
    let dir = var("POLITIQUE_DONNEES").context("variable POLITIQUE_DONNEES absente ou vide")?;
    let donnees = Arc::new(Donnees::new(dir));
    // Premier chargement : le bilan (comptes seulement) part dans les journaux.
    donnees.etat();

    // Signalements actifs seulement si l'écouteur public est configuré.
    let mut signalements: Option<(Arc<Store>, Arc<Limiteur>)> = None;
    let mut plafond = 200;
    let mut ecouteur = None;
    if let Some(bind) = var("POLITIQUE_PUBLIC_BIND") {
        let bind: SocketAddr = bind.parse().context("POLITIQUE_PUBLIC_BIND invalide")?;
        let chemin = var("POLITIQUE_SIGNALEMENTS_DB").unwrap_or_else(|| "/app/signalements/signalements.db".into());
        if let Some(store) = ouvrir_optionnel(chemin) {
        let store = Arc::new(store);
        let limiteur = Arc::new(Limiteur::new(10));
        let plafond_jour = match var("POLITIQUE_SIGNALEMENTS_PLAFOND_JOUR") {
            None => 200,
            Some(v) => v.parse::<u32>().ok().filter(|n| *n > 0).context("POLITIQUE_SIGNALEMENTS_PLAFOND_JOUR : entier positif attendu")?,
        };
        plafond = plafond_jour;
        let etat = public::PublicState { store: store.clone(), limiteur: limiteur.clone(), donnees: donnees.clone(), plafond_jour, now: maintenant, comptes: Default::default() };
        ecouteur = Some((tokio::net::TcpListener::bind(bind).await?, public::router(etat)));
        signalements = Some((store, limiteur));
        }
    }
    let (store, limiteur) = signalements.unzip();

    let mcp = tentacle_common::serve(env, move || PolitiqueServer::new(donnees.clone(), store.clone(), limiteur.clone(), plafond));
    match ecouteur {
        None => mcp.await,
        Some((listener, router)) => {
            let public = async move { axum::serve(listener, router).await.map_err(anyhow::Error::from) };
            tokio::try_join!(public, mcp)?;
            Ok(())
        }
    }
}
