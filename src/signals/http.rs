//! Source de signaux sur API JSON. Adresse, chemins et jeton proviennent de la
//! configuration : rien d'identifiant ici.

use std::time::Duration;

use async_trait::async_trait;
use serde::Deserialize;

use super::{Signal, SignalKind, SignalSource};
use crate::config::SignalsConfig;

/// Au-delà, on considère la source indisponible plutôt que de retarder le
/// briefing : la synthèse est un complément, pas un bloquant.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Debug, Deserialize)]
struct ForecastEnvelope {
    #[serde(default)]
    forecasts: Vec<ForecastItem>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ForecastItem {
    #[serde(default)]
    title: String,
    #[serde(default)]
    probability: f64,
    #[serde(default)]
    time_horizon: Option<String>,
    #[serde(default)]
    domain: Option<String>,
    #[serde(default)]
    region: Option<String>,
}

#[derive(Debug, Deserialize)]
struct MarketEnvelope {
    #[serde(default)]
    markets: Vec<MarketItem>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MarketItem {
    #[serde(default)]
    title: String,
    #[serde(default)]
    yes_price: f64,
    #[serde(default)]
    volume: Option<f64>,
}

/// Assemble le contexte d'une estimation : domaine et zone quand ils sont là.
fn forecast_context(item: &ForecastItem) -> Option<String> {
    let parts: Vec<&str> = [item.domain.as_deref(), item.region.as_deref()]
        .into_iter()
        .flatten()
        .filter(|s| !s.trim().is_empty())
        .collect();
    if parts.is_empty() {
        None
    } else {
        Some(parts.join(" / "))
    }
}

/// Un volume élevé distingue une cotation adossée à des positions réelles d'un
/// marché anecdotique. Arrondi au millier, l'unité n'apporte rien ici.
fn market_context(item: &MarketItem) -> Option<String> {
    item.volume
        .filter(|v| v.is_finite() && *v > 0.0)
        .map(|v| format!("volume {:.0}k", v / 1000.0))
}

pub struct HttpSignalSource {
    config: SignalsConfig,
    client: reqwest::Client,
}

impl HttpSignalSource {
    pub fn new(config: SignalsConfig) -> anyhow::Result<Self> {
        let client = reqwest::Client::builder()
            .timeout(REQUEST_TIMEOUT)
            .build()?;
        Ok(Self { config, client })
    }

    async fn get<T: for<'de> Deserialize<'de>>(&self, path: &str) -> anyhow::Result<T> {
        let url = format!(
            "{}/{}",
            self.config.base_url.trim_end_matches('/'),
            path.trim_start_matches('/')
        );
        let resp = self
            .client
            .get(&url)
            .header(self.config.auth_header.as_str(), &self.config.api_key)
            .header("Accept", "application/json")
            .send()
            .await?;

        let status = resp.status();
        if !status.is_success() {
            anyhow::bail!("réponse inattendue de la source de signaux : {status}");
        }
        Ok(resp.json::<T>().await?)
    }
}

#[async_trait]
impl SignalSource for HttpSignalSource {
    fn name(&self) -> &'static str {
        "signals"
    }

    /// Une catégorie indisponible ne fait pas échouer l'autre : on synthétise ce
    /// qui a répondu.
    async fn fetch(&self) -> anyhow::Result<Vec<Signal>> {
        let mut signals = Vec::new();

        match self
            .get::<MarketEnvelope>(&self.config.markets_path)
            .await
        {
            Ok(envelope) => {
                // La catégorisation de la source mélange des cotations
                // anecdotiques et des cotations engageantes. Le volume est le
                // seul discriminant disponible : on présente les plus dotées,
                // puisque seules les premières atteignent la synthèse.
                let mut markets = envelope.markets;
                markets.sort_by(|a, b| {
                    b.volume
                        .unwrap_or(0.0)
                        .partial_cmp(&a.volume.unwrap_or(0.0))
                        .unwrap_or(std::cmp::Ordering::Equal)
                });
                signals.extend(markets.iter().map(|m| {
                    Signal::new(
                        SignalKind::Market,
                        &m.title,
                        m.yes_price,
                        None,
                        market_context(m).as_deref(),
                    )
                }));
            }
            Err(e) => tracing::warn!("catégorie marché indisponible : {e}"),
        }

        match self
            .get::<ForecastEnvelope>(&self.config.forecasts_path)
            .await
        {
            Ok(envelope) => signals.extend(envelope.forecasts.iter().map(|f| {
                Signal::new(
                    SignalKind::Forecast,
                    &f.title,
                    f.probability,
                    f.time_horizon.as_deref(),
                    forecast_context(f).as_deref(),
                )
            })),
            Err(e) => tracing::warn!("catégorie estimation indisponible : {e}"),
        }

        signals.retain(|s| !s.statement.is_empty());
        Ok(signals)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forecast_shape_is_parsed() {
        let raw = r#"{"forecasts":[{"title":"Cyber threat concentration: China",
            "probability":0.5,"confidence":0.25,"timeHorizon":"7d",
            "domain":"cyber","region":"China"}]}"#;
        let env: ForecastEnvelope = serde_json::from_str(raw).unwrap();
        assert_eq!(env.forecasts.len(), 1);
        let item = &env.forecasts[0];
        assert_eq!(item.probability, 0.5);
        assert_eq!(item.time_horizon.as_deref(), Some("7d"));
        assert_eq!(forecast_context(item).as_deref(), Some("cyber / China"));
    }

    #[test]
    fn market_shape_is_parsed() {
        let raw = r#"{"markets":[{"id":"X","title":"Will it happen?",
            "yesPrice":0.13,"volume":114574.67,"url":"https://example.test",
            "closesAt":4089243540000,"category":"","source":"S"}],
            "fetchedAt":1,"dataAvailable":true}"#;
        let env: MarketEnvelope = serde_json::from_str(raw).unwrap();
        assert_eq!(env.markets.len(), 1);
        assert_eq!(env.markets[0].yes_price, 0.13);
        assert_eq!(market_context(&env.markets[0]).as_deref(), Some("volume 115k"));
    }

    #[test]
    fn missing_and_empty_collections_are_tolerated() {
        let env: ForecastEnvelope = serde_json::from_str("{}").unwrap();
        assert!(env.forecasts.is_empty());
        let env: MarketEnvelope = serde_json::from_str(r#"{"markets":[]}"#).unwrap();
        assert!(env.markets.is_empty());
    }

    #[test]
    fn absent_or_invalid_volume_yields_no_context() {
        let env: MarketEnvelope =
            serde_json::from_str(r#"{"markets":[{"title":"A","yesPrice":0.5}]}"#).unwrap();
        assert!(market_context(&env.markets[0]).is_none());
    }

    /// L'ordre de présentation décide de ce qui atteint la synthèse : une
    /// cotation sans volume ne doit jamais précéder une cotation dotée.
    #[test]
    fn markets_are_ordered_by_volume_desc() {
        let raw = r#"{"markets":[
            {"title":"anecdotique","yesPrice":0.5},
            {"title":"dotee","yesPrice":0.5,"volume":45000000.0},
            {"title":"moyenne","yesPrice":0.5,"volume":114574.0}]}"#;
        let env: MarketEnvelope = serde_json::from_str(raw).unwrap();
        let mut markets = env.markets;
        markets.sort_by(|a, b| {
            b.volume
                .unwrap_or(0.0)
                .partial_cmp(&a.volume.unwrap_or(0.0))
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        let titles: Vec<&str> = markets.iter().map(|m| m.title.as_str()).collect();
        assert_eq!(titles, vec!["dotee", "moyenne", "anecdotique"]);
    }

    #[test]
    fn context_is_omitted_when_fields_are_blank() {
        let env: ForecastEnvelope = serde_json::from_str(
            r#"{"forecasts":[{"title":"A","probability":0.1,"domain":"  ","region":""}]}"#,
        )
        .unwrap();
        assert!(forecast_context(&env.forecasts[0]).is_none());
    }
}
