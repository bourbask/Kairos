use std::sync::Arc;

use kairos_finances::{api::FinanceApi, tools::FinanceServer};
use tentacle_common::TentacleEnv;

fn required(name: &str) -> anyhow::Result<String> {
    match std::env::var(name) {
        Ok(v) if !v.is_empty() => Ok(v),
        _ => Err(anyhow::anyhow!("variable {name} absente ou vide")),
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().with_writer(std::io::stderr).with_max_level(tracing::Level::WARN).init();
    let env = TentacleEnv::from_env("FINANCES")?;
    let url = required("FINANCES_API_URL")?;
    let valid = reqwest::Url::parse(&url).is_ok_and(|u| matches!(u.scheme(), "http" | "https") && u.has_host());
    if !valid {
        anyhow::bail!("variable FINANCES_API_URL invalide");
    }
    let api = Arc::new(FinanceApi::new(url, required("FINANCES_API_TOKEN")?));
    let dashboard = std::env::var("FINANCES_DASHBOARD_URL").ok().filter(|u| !u.is_empty());
    tentacle_common::serve(env, move || FinanceServer::new(api.clone(), dashboard.clone())).await
}
