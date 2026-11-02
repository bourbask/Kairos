use std::path::Path;

use tete::config;

fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().init();
    let path = std::env::var("TETE_CONFIG").unwrap_or_else(|_| "config/tete.toml".into());
    let cfg = config::HeadConfig::load(Path::new(&path))?;
    tracing::info!(tentacules = cfg.tentacles.len(), "tête prête");
    Ok(())
}
