//! Client de lecture (GET uniquement) et modèle de question.

use chrono::{DateTime, Utc};
use serde_json::Value;

pub const MAX_TITLE: usize = 200;

#[derive(Debug, Clone, PartialEq)]
pub struct Outcome {
    pub label: String,
    pub prob: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Market {
    pub id: String,
    pub question: String,
    pub outcomes: Vec<Outcome>,
    pub volume: f64,
    pub end: Option<DateTime<Utc>>,
    pub closed: bool,
    pub tags: Vec<String>,
}

/// Texte externe non fiable : contrôles retirés, sauts de ligne en espaces, longueur bornée.
pub fn clean(s: &str) -> String {
    s.chars()
        .map(|c| if c == '\n' || c == '\r' { ' ' } else { c })
        .filter(|c| !c.is_control())
        .take(MAX_TITLE)
        .collect()
}

/// Les tableaux arrivent encodés dans une chaîne JSON.
fn str_array(v: &Value) -> Option<Vec<String>> {
    serde_json::from_str(v.as_str()?).ok()
}

pub fn parse_market(v: &Value) -> Option<Market> {
    let labels = str_array(v.get("outcomes")?)?;
    let prices = str_array(v.get("outcomePrices")?)?;
    if labels.is_empty() || labels.len() != prices.len() {
        return None;
    }
    let outcomes = labels
        .iter()
        .zip(&prices)
        .map(|(l, p)| {
            let prob: f64 = p.parse().ok()?;
            (0.0..=1.0).contains(&prob).then(|| Outcome { label: clean(l), prob })
        })
        .collect::<Option<Vec<_>>>()?;
    Some(Market {
        id: clean(v.get("id")?.as_str()?),
        question: clean(v.get("question")?.as_str()?),
        outcomes,
        volume: v.get("volumeNum").and_then(Value::as_f64).unwrap_or(0.0),
        end: v.get("endDate").and_then(Value::as_str).and_then(|s| s.parse().ok()),
        closed: v.get("closed").and_then(Value::as_bool).unwrap_or(false),
        tags: vec![],
    })
}

pub struct Api {
    base: String,
    http: reqwest::Client,
}

impl Api {
    pub fn new(base: &str) -> Self {
        Self { base: base.trim_end_matches('/').to_string(), http: reqwest::Client::builder().timeout(std::time::Duration::from_secs(20)).build().expect("client http") }
    }

    async fn get(&self, path: &str, query: &[(&str, &str)]) -> anyhow::Result<Value> {
        Ok(self.http.get(format!("{}{path}", self.base)).query(query).send().await?.error_for_status()?.json().await?)
    }

    pub async fn markets(&self, ids: &[String]) -> anyhow::Result<Vec<Market>> {
        let q: Vec<(&str, &str)> = ids.iter().map(|i| ("id", i.as_str())).collect();
        let v = self.get("/markets", &q).await?;
        Ok(v.as_array().into_iter().flatten().filter_map(parse_market).collect())
    }

    pub async fn search(&self, q: &str) -> anyhow::Result<Vec<Market>> {
        let v = self
            .get("/public-search", &[("q", q), ("events_status", "active"), ("limit_per_type", "20")])
            .await?;
        let mut out = vec![];
        for ev in v.get("events").and_then(Value::as_array).into_iter().flatten() {
            let tags: Vec<String> = ev
                .get("tags")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|t| t.get("slug").and_then(Value::as_str).map(clean))
                .collect();
            for m in ev.get("markets").and_then(Value::as_array).into_iter().flatten() {
                if let Some(mut m) = parse_market(m) {
                    m.tags = tags.clone();
                    out.push(m);
                }
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn mk(prices: &str) -> Value {
        json!({"id":"42","question":"Q ?","outcomes":"[\"Yes\",\"No\"]","outcomePrices":prices,
               "volumeNum":150000.0,"endDate":"2027-01-01T00:00:00Z","closed":false})
    }

    #[test]
    fn parses_string_encoded_arrays() {
        let m = parse_market(&mk("[\"0.375\",\"0.625\"]")).unwrap();
        assert_eq!(m.outcomes[0].label, "Yes");
        assert!((m.outcomes[0].prob - 0.375).abs() < 1e-9);
        assert_eq!(m.volume, 150000.0);
        assert!(!m.closed);
        assert!(m.end.is_some());
    }

    #[test]
    fn rejects_mismatched_lengths() {
        assert!(parse_market(&mk("[\"0.5\"]")).is_none());
    }

    #[test]
    fn rejects_out_of_range_price() {
        assert!(parse_market(&mk("[\"1.5\",\"0.5\"]")).is_none());
        assert!(parse_market(&mk("[\"abc\",\"0.5\"]")).is_none());
    }

    #[test]
    fn clean_strips_controls_and_truncates() {
        assert_eq!(clean("a\u{0007}b\nc"), "ab c");
        assert_eq!(clean(&"x".repeat(500)).chars().count(), MAX_TITLE);
    }

    async fn fake() -> String {
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", l.local_addr().unwrap());
        tokio::spawn(async move {
            loop {
                let Ok((mut s, _)) = l.accept().await else { break };
                let mut buf = vec![0u8; 4096];
                let n = s.read(&mut buf).await.unwrap_or(0);
                let req = String::from_utf8_lossy(&buf[..n]).to_string();
                let (code, body) = if req.starts_with("GET /markets?id=42") {
                    ("200 OK", json!([mk("[\"0.375\",\"0.625\"]")]).to_string())
                } else if req.starts_with("GET /public-search?q=rate") {
                    ("200 OK", json!({"events":[{"tags":[{"slug":"econ"}],"markets":[mk("[\"0.3\",\"0.7\"]")]}]}).to_string())
                } else {
                    ("500 Internal Server Error", "{}".to_string())
                };
                let resp = format!(
                    "HTTP/1.1 {code}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = s.write_all(resp.as_bytes()).await;
            }
        });
        url
    }

    #[tokio::test]
    async fn api_reads_and_fails_on_500() {
        let api = Api::new(&fake().await);
        assert_eq!(api.markets(&["42".into()]).await.unwrap().len(), 1);
        let s = api.search("rate").await.unwrap();
        assert_eq!(s.len(), 1);
        assert_eq!(s[0].tags, vec!["econ"]);
        assert!(api.markets(&["7".into()]).await.is_err());
    }
}
