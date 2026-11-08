//! Relevé périodique des questions suivies, propositions hebdomadaires et dénouements.

use std::sync::Arc;

use chrono::{DateTime, Duration, Utc};

use crate::api::{Api, Market};
use crate::signals::{Settings, pct};
use crate::store::{Proposal, Store};

const MAX_PROPOSALS: usize = 5;
/// Question absente des réponses de la source depuis son échéance : dénouée comme inconnue.
const GONE_DAYS: i64 = 14;

pub struct Topics {
    pub keywords: Vec<String>,
    pub excluded_tags: Vec<String>,
}

impl Default for Topics {
    fn default() -> Self {
        Topics { keywords: vec![], excluded_tags: vec!["sports".into(), "crypto".into()] }
    }
}

/// `eve` : probabilité de l'issue suivie la veille, rappelée quand elle est connue.
fn summary(title: &str, m: &Market, outcome: &str, eve: Option<f64>) -> String {
    let base = match m.outcomes.iter().find(|o| o.prob >= 0.99) {
        Some(w) => format!("{title} : {}", w.label),
        None => {
            let p = m.outcomes.iter().find(|o| o.label == outcome).map_or(0.0, |o| o.prob);
            format!("{title} : clos (dernière probabilité {})", pct(p))
        }
    };
    match eve {
        Some(p) => format!("{base} (veille : {})", pct(p)),
        None => base,
    }
}

/// Une question absente de la réponse n'est pas close : on la saute, sauf
/// `GONE_DAYS` après son échéance, où elle est dénouée comme inconnue.
pub async fn poll_once(api: &Api, store: &Store, s: &Settings, now: DateTime<Utc>) -> anyhow::Result<()> {
    let followed = store.followed()?;
    if followed.is_empty() {
        return Ok(());
    }
    let ids: Vec<String> = followed.iter().map(|f| f.market.clone()).collect();
    let markets = api.markets(&ids).await?;
    for f in followed {
        let Some(m) = markets.iter().find(|m| m.id == f.market) else {
            if f.end.is_some_and(|end| now - end > Duration::days(GONE_DAYS)) {
                let last = store.latest(&f.market, &f.outcome)?.map(|(p, _, _)| pct(p));
                let text = match last {
                    Some(p) => format!("{} : résultat inconnu (dernière probabilité {p})", f.title),
                    None => format!("{} : résultat inconnu", f.title),
                };
                store.resolve(&f.market, &text, now)?;
            } else {
                eprintln!("marches: question {} absente de la réponse", f.market);
            }
            continue;
        };
        let eve = store.prob_at(&f.market, &f.outcome, now - Duration::hours(24), Duration::hours(s.stale_hours))?;
        store.set_end(&f.market, m.end)?;
        if let Some(o) = m.outcomes.iter().find(|o| o.label == f.outcome) {
            store.snapshot(&f.market, &f.outcome, now, o.prob, m.volume)?;
        }
        if m.closed {
            store.resolve(&f.market, &summary(&f.title, m, &f.outcome, eve), now)?;
        }
    }
    Ok(())
}

fn proposed_outcome(m: &Market) -> Option<String> {
    if m.outcomes.len() == 2 {
        if let Some(y) = m.outcomes.iter().find(|o| o.label == "Yes") {
            return Some(y.label.clone());
        }
    }
    m.outcomes.iter().max_by(|a, b| a.prob.total_cmp(&b.prob)).map(|o| o.label.clone())
}

pub async fn propose(
    api: &Api,
    store: &Store,
    topics: &Topics,
    s: &Settings,
) -> anyhow::Result<Vec<Proposal>> {
    let followed: Vec<String> = store.followed()?.into_iter().map(|f| f.market).collect();
    let mut found: Vec<Market> = vec![];
    let mut failed = 0;
    for kw in &topics.keywords {
        // Un mot-clé en échec n'annule pas les autres.
        let markets = match api.search(kw).await {
            Ok(v) => v,
            Err(x) => {
                eprintln!("marches: recherche en échec : {x}");
                failed += 1;
                continue;
            }
        };
        for m in markets {
            let ok = !m.closed
                && m.volume >= s.min_volume
                && !followed.contains(&m.id)
                && !m.tags.iter().any(|t| topics.excluded_tags.contains(t))
                && !found.iter().any(|x| x.id == m.id);
            if ok {
                found.push(m);
            }
        }
    }
    if failed > 0 && failed == topics.keywords.len() {
        anyhow::bail!("recherche en échec pour tous les mots-clés");
    }
    found.sort_by(|a, b| b.volume.total_cmp(&a.volume));
    let list: Vec<Proposal> = found
        .iter()
        .filter_map(|m| Some((m, proposed_outcome(m)?)))
        .take(MAX_PROPOSALS)
        .enumerate()
        .map(|(i, (m, outcome))| Proposal {
            n: i as u32 + 1,
            market: m.id.clone(),
            outcome,
            title: m.question.clone(),
        })
        .collect();
    store.set_proposals(&list)?;
    Ok(list)
}

/// Propositions calculées au premier relevé de la semaine (le lundi, avant le
/// brief) ; un échec laisse la semaine ouverte au relevé suivant.
pub async fn weekly_proposals(api: &Api, store: &Store, topics: &Topics, s: &Settings, now: DateTime<Utc>) -> anyhow::Result<()> {
    if store.proposed_in_week(now)? {
        return Ok(());
    }
    propose(api, store, topics, s).await?;
    store.mark_proposed(now)
}

/// Les erreurs sont journalisées ; la boucle ne s'arrête jamais.
pub fn spawn_poller(api: Arc<Api>, store: Arc<Store>, topics: Arc<Topics>, s: Arc<Settings>, every: std::time::Duration) {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(every);
        loop {
            tick.tick().await;
            let now = Utc::now();
            if let Err(e) = poll_once(&api, &store, &s, now).await {
                eprintln!("marches: relevé en échec : {e}");
            }
            if let Err(e) = weekly_proposals(&api, &store, &topics, &s, now).await {
                eprintln!("marches: propositions indisponibles : {e}");
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fake_server;
    use serde_json::{Value, json};

    fn mk(id: &str, vol: f64, closed: bool, prices: &str) -> Value {
        json!({"id":id,"question":format!("Q{id}"),"outcomes":"[\"Yes\",\"No\"]","outcomePrices":prices,
               "volumeNum":vol,"endDate":"2099-01-01T00:00:00Z","closed":closed})
    }

    #[tokio::test]
    async fn poll_snapshots_and_resolves_closed() {
        let body = json!([mk("1", 5e5, false, "[\"0.4\",\"0.6\"]"), mk("2", 5e5, true, "[\"1\",\"0\"]")]).to_string();
        let api = Api::new(&fake_server(vec![("GET /markets", body)]).await);
        let st = Store::memory();
        st.follow("1", "Yes", "T1").unwrap();
        st.follow("2", "Yes", "T2").unwrap();
        st.follow("3", "Yes", "T3").unwrap();
        let now = Utc::now();
        poll_once(&api, &st, &Settings::default(), now).await.unwrap();
        assert_eq!(st.prob_at("1", "Yes", now, chrono::Duration::zero()).unwrap(), Some(0.4));
        assert_eq!(st.take_resolutions().unwrap(), vec!["T2 : Yes".to_string()]);
        let ids: Vec<_> = st.followed().unwrap().into_iter().map(|f| f.market).collect();
        assert_eq!(ids, vec!["1", "3"]);
    }

    #[tokio::test]
    async fn expired_but_open_stays_followed() {
        let mut m = mk("1", 5e5, false, "[\"0.995\",\"0.005\"]");
        m["endDate"] = json!("2000-01-01T00:00:00Z");
        let api = Api::new(&fake_server(vec![("GET /markets", json!([m]).to_string())]).await);
        let st = Store::memory();
        st.follow("1", "Yes", "T1").unwrap();
        let now = Utc::now();
        poll_once(&api, &st, &Settings::default(), now).await.unwrap();
        assert_eq!(st.followed().unwrap().len(), 1);
        assert!(st.followed().unwrap()[0].end.is_some());
        assert!(st.take_resolutions().unwrap().is_empty());
        assert_eq!(st.prob_at("1", "Yes", now, chrono::Duration::zero()).unwrap(), Some(0.995));
    }

    #[tokio::test]
    async fn poll_error_writes_nothing() {
        let api = Api::new(&fake_server(vec![]).await);
        let st = Store::memory();
        st.follow("1", "Yes", "T1").unwrap();
        assert!(poll_once(&api, &st, &Settings::default(), Utc::now()).await.is_err());
        assert!(st.last_snapshot().unwrap().is_none());
    }

    #[tokio::test]
    async fn propose_filters_sorts_and_numbers() {
        let mut markets: Vec<Value> = (1..=7).map(|i| mk(&format!("{i}"), 2e5 + i as f64, false, "[\"0.3\",\"0.7\"]")).collect();
        markets.push(mk("8", 9e6, false, "[\"0.3\",\"0.7\"]")); // sports
        markets.push(mk("9", 50.0, false, "[\"0.3\",\"0.7\"]")); // trop faible
        let sports = json!({"events":[{"tags":[{"slug":"sports"}],"markets":[markets[7]]},
                                      {"tags":[{"slug":"econ"}],"markets":markets[..7].iter().chain([&markets[8]]).collect::<Vec<_>>()}]});
        let api = Api::new(&fake_server(vec![("GET /public-search", sports.to_string())]).await);
        let st = Store::memory();
        let t = Topics { keywords: vec!["rate".into(), "rate2".into()], ..Topics::default() };
        let out = propose(&api, &st, &t, &Settings::default()).await.unwrap();
        assert_eq!(out.len(), 5);
        assert_eq!(out.iter().map(|p| p.n).collect::<Vec<_>>(), vec![1, 2, 3, 4, 5]);
        assert_eq!(out[0].market, "7");
        assert_eq!(out[0].outcome, "Yes");
        assert!(out.iter().all(|p| p.market != "8" && p.market != "9"));
        assert_eq!(st.proposal(1).unwrap().unwrap().market, "7");
    }

    #[tokio::test]
    async fn failing_keyword_does_not_cancel_others() {
        let good = json!({"events":[{"tags":[],"markets":[mk("1", 2e5, false, "[\"0.3\",\"0.7\"]")]}]});
        let api = Api::new(&fake_server(vec![("GET /public-search?q=good", good.to_string())]).await);
        let st = Store::memory();
        let t = Topics { keywords: vec!["bad".into(), "good".into()], ..Topics::default() };
        let out = propose(&api, &st, &t, &Settings::default()).await.unwrap();
        assert_eq!(out.len(), 1);
        let t = Topics { keywords: vec!["bad".into()], ..Topics::default() };
        assert!(propose(&api, &st, &t, &Settings::default()).await.is_err());
        assert_eq!(st.proposal(1).unwrap().unwrap().market, "1");
    }


    fn good() -> String {
        json!({"events":[{"tags":[],"markets":[{"id":"1","question":"Q1","outcomes":"[\"Yes\",\"No\"]",
            "outcomePrices":"[\"0.3\",\"0.7\"]","volumeNum":2e5,"closed":false}]}]}).to_string()
    }

    #[tokio::test]
    async fn proposals_computed_once_per_week() {
        let api = Api::new(&fake_server(vec![("GET /public-search?q=good", good())]).await);
        let st = Store::memory();
        let (ok, bad) = (Topics { keywords: vec!["good".into()], ..Topics::default() }, Topics { keywords: vec!["bad".into()], ..Topics::default() });
        let s = Settings::default();
        let now = Utc::now();
        weekly_proposals(&api, &st, &ok, &s, now).await.unwrap();
        assert!(st.proposal(1).unwrap().is_some());
        assert_eq!(st.proposals_of_week(now).unwrap().len(), 1);
        st.set_proposals(&[]).unwrap();
        weekly_proposals(&api, &st, &ok, &s, now).await.unwrap();
        assert!(st.proposal(1).unwrap().is_none());
        // Toute la recherche en échec : semaine non marquée, nouvel essai au relevé suivant.
        let later = now + Duration::days(7);
        assert!(st.proposals_of_week(later).unwrap().is_empty());
        assert!(weekly_proposals(&api, &st, &bad, &s, later).await.is_err());
        weekly_proposals(&api, &st, &ok, &s, later).await.unwrap();
        assert_eq!(st.proposals_of_week(later).unwrap().len(), 1);
    }

    fn closed(id: &str, prices: &str) -> serde_json::Value {
        json!({"id":id,"question":"Q","outcomes":"[\"Yes\",\"No\"]","outcomePrices":prices,
               "volumeNum":2e5,"endDate":"2026-01-01T00:00:00Z","closed":true})
    }

    #[tokio::test]
    async fn resolution_recalls_eve_probability() {
        let body = json!([closed("2", "[\"1\",\"0\"]"), closed("3", "[\"0.5\",\"0.5\"]")]).to_string();
        let api = Api::new(&fake_server(vec![("GET /markets", body)]).await);
        let st = Store::memory();
        let now = Utc::now();
        st.follow("2", "Yes", "T2").unwrap();
        st.follow("3", "Yes", "T3").unwrap();
        st.snapshot("2", "Yes", now - Duration::hours(25), 0.62, 2e5).unwrap();
        poll_once(&api, &st, &Settings::default(), now).await.unwrap();
        assert_eq!(
            st.take_resolutions().unwrap(),
            vec!["T2 : Yes (veille : 62 %)".to_string(), "T3 : clos (dernière probabilité 50 %)".to_string()]
        );
    }

    #[tokio::test]
    async fn long_missing_question_resolved_as_unknown() {
        let api = Api::new(&fake_server(vec![("GET /markets", "[]".to_string())]).await);
        let st = Store::memory();
        let now = Utc::now();
        for (id, days) in [("old", 15), ("recent", 13)] {
            st.follow(id, "Yes", &format!("T{id}")).unwrap();
            st.set_end(id, Some(now - Duration::days(days))).unwrap();
            st.snapshot(id, "Yes", now - Duration::days(days), 0.4, 2e5).unwrap();
        }
        st.follow("open", "Yes", "Topen").unwrap();
        poll_once(&api, &st, &Settings::default(), now).await.unwrap();
        assert_eq!(st.take_resolutions().unwrap(), vec!["Told : résultat inconnu (dernière probabilité 40 %)".to_string()]);
        let ids: Vec<_> = st.followed().unwrap().into_iter().map(|f| f.market).collect();
        assert_eq!(ids, vec!["recent", "open"]);
    }
}
