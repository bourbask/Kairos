//! Module marchés servi en HTTP, base locale préremplie.

use std::sync::Arc;

use chrono::{Duration, Utc};
use kairos_marches::{
    signals::Settings,
    store::{Proposal, Store},
    tools::MarketServer,
};
use serde_json::{Value, json};

async fn start() -> (String, Arc<Store>) {
    let store = Arc::new(Store::memory());
    let settings = Arc::new(Settings::default());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    let st = store.clone();
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        MarketServer::new(st.clone(), settings.clone())
    }));
    (url, store)
}

async fn call(url: &str, name: &str, args: Value) -> (String, bool) {
    let body = json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}});
    let v: Value = reqwest::Client::new()
        .post(url)
        .header("Accept", "application/json, text/event-stream")
        .bearer_auth("t")
        .json(&body)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    (v["result"]["content"][0]["text"].as_str().unwrap_or_default().to_string(), v["result"]["isError"] == json!(true))
}

async fn alerts(url: &str) -> Vec<Value> {
    let (t, err) = call(url, "alerts", json!({})).await;
    assert!(!err, "{t}");
    serde_json::from_str(&t).unwrap()
}

/// Question suivie, relevée il y a 25 h puis il y a 1 h.
fn moved(st: &Store, id: &str, from: f64, to: f64) {
    let now = Utc::now();
    st.follow(id, "Yes", &format!("Question {id}")).unwrap();
    st.snapshot(id, "Yes", now - Duration::hours(25), from, 2e5).unwrap();
    st.snapshot(id, "Yes", now - Duration::hours(1), to, 2e5).unwrap();
}

#[tokio::test]
async fn rise_alerts_once_with_matching_projection() {
    let (url, st) = start().await;
    moved(&st, "m1", 0.30, 0.45);
    let (t, err) = call(&url, "projection", json!({"number": 1, "up": "on accélère (hausse)", "down": "on attend (baisse)"})).await;
    assert!(!err, "{t}");

    let a = alerts(&url).await;
    assert_eq!(a.len(), 1, "{a:?}");
    assert_eq!(a[0]["counts_budget"], json!(true));
    let text = a[0]["text"].as_str().unwrap();
    assert!(text.contains("hausse") && !text.contains("baisse"), "{text}");
    assert!(text.contains("30 %") && text.contains("45 %"), "{text}");
    assert!(alerts(&url).await.is_empty());

    let (b, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(b.contains("Question m1 — 45 % (+15 pts / 24 h") && !b.starts_with('⚠'), "{b}");
    assert!(b.contains("hausse") && !b.contains("baisse"), "{b}");
    assert!(b.contains("Bascules depuis le dernier brief"), "{b}");
    // La bascule n'est rapportée qu'une fois.
    let (b, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(!b.contains("Bascules depuis le dernier brief"), "{b}");
}

#[tokio::test]
async fn follow_unfollow_and_validation() {
    let (url, st) = start().await;
    let p = |n: u32| Proposal { n, market: format!("p{n}"), outcome: "Yes".into(), title: format!("Proposée {n}") };
    st.set_proposals(&[p(1), p(2)]).unwrap();

    let (t, err) = call(&url, "follow", json!({"numbers": [1, 9]})).await;
    assert!(err, "{t}");
    let (t, err) = call(&url, "follow", json!({"numbers": [1, 1]})).await;
    assert!(err, "{t}");
    assert!(st.followed().unwrap().is_empty());

    let (t, err) = call(&url, "follow", json!({"numbers": [1]})).await;
    assert!(!err, "{t}");
    let (t, _) = call(&url, "followed_list", json!({})).await;
    assert!(t.contains("1. Proposée 1"), "{t}");

    call(&url, "follow", json!({"numbers": [2]})).await;
    let (t, err) = call(&url, "scenario", json!({"name": "crise", "links": "1+, 2-"})).await;
    assert!(!err, "{t}");
    let (t, err) = call(&url, "scenario", json!({"name": "x", "links": "1+, 7-"})).await;
    assert!(err, "{t}");
    assert_eq!(st.scenarios().unwrap().len(), 1);
    let (t, err) = call(&url, "projection", json!({"scenario_name": "crise", "up": "a", "down": "b"})).await;
    assert!(!err, "{t}");
    assert!(st.projection("sc:crise").unwrap().is_some());
    let (t, err) = call(&url, "projection", json!({"number": 1, "scenario_name": "crise", "up": "a", "down": "b"})).await;
    assert!(err, "{t}");

    let (t, err) = call(&url, "unfollow", json!({"number": 5})).await;
    assert!(err, "{t}");
    let (t, err) = call(&url, "unfollow", json!({"number": 1})).await;
    assert!(!err, "{t}");
    let (t, _) = call(&url, "followed_list", json!({})).await;
    assert!(t.contains("1. Proposée 2") && !t.contains("Proposée 1"), "{t}");
}

#[tokio::test]
async fn budget_overflow_goes_to_brief() {
    let (url, st) = start().await;
    for i in 1..=5 {
        moved(&st, &format!("q{i}"), 0.30, 0.45);
    }
    let a = alerts(&url).await;
    assert_eq!(a.len(), 4, "{a:?}");
    assert!(a.iter().all(|x| x["counts_budget"] == json!(true)));
    assert!(!a.iter().any(|x| x["text"].as_str().unwrap().contains("Question q5")));
    assert!(alerts(&url).await.is_empty());

    let (b, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    let tail = b.split("Bascules depuis le dernier brief").nth(1).unwrap_or_default();
    assert!(tail.contains("Question q5") && tail.contains("Question q1"), "{b}");

    let (s, _) = call(&url, "brief_section", json!({"period": "soir"})).await;
    assert_eq!(s, "");
}

#[tokio::test]
async fn expired_question_does_not_alert() {
    let (url, st) = start().await;
    moved(&st, "e1", 0.30, 0.45);
    st.set_end("e1", Some(Utc::now() - Duration::days(1))).unwrap();
    assert!(alerts(&url).await.is_empty());
    let (b, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(b.contains("Question e1") && b.contains("en attente du résultat"), "{b}");
}

#[tokio::test]
async fn stale_data_mutes_alerts_and_warns() {
    let (url, st) = start().await;
    let now = Utc::now();
    st.follow("s1", "Yes", "Question s1").unwrap();
    st.snapshot("s1", "Yes", now - Duration::hours(30), 0.30, 2e5).unwrap();
    st.snapshot("s1", "Yes", now - Duration::hours(7), 0.45, 2e5).unwrap();
    assert!(alerts(&url).await.is_empty());
    let (b, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(b.starts_with("Marchés de prédiction\n⚠ Données de marché du "), "{b}");
}

#[tokio::test]
async fn old_snapshot_after_outage_gives_no_variation() {
    let (url, st) = start().await;
    let now = Utc::now();
    st.follow("o1", "Yes", "Question o1").unwrap();
    // Relevé le plus proche de la fenêtre : 40 h, trop ancien pour une variation sur 24 h.
    st.snapshot("o1", "Yes", now - Duration::hours(40), 0.30, 2e5).unwrap();
    st.snapshot("o1", "Yes", now - Duration::hours(1), 0.45, 2e5).unwrap();
    assert!(alerts(&url).await.is_empty());
    let (b, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(b.contains("Question o1 — 45 %") && !b.contains("pts"), "{b}");
}

/// Scénario « crise » : a monte, b baisse, 8 points chacun (sous le seuil individuel).
fn crisis(st: &Store) {
    moved(st, "a", 0.30, 0.38);
    moved(st, "b", 0.50, 0.42);
    st.set_scenario("crise", &[("a".into(), "Yes".into(), 1), ("b".into(), "Yes".into(), -1)]).unwrap();
}

#[tokio::test]
async fn scenario_alert_not_repeated_next_day() {
    let now = Utc::now();
    let yesterday = (now - Duration::days(1)).date_naive();

    // Même sens signalé la veille, dans la fenêtre : rien de nouveau.
    let (url, st) = start().await;
    crisis(&st);
    st.record_alert(&format!("sc:crise:+:{yesterday}"), now - Duration::hours(20), true, "hier").unwrap();
    assert!(alerts(&url).await.is_empty());

    // Signalé hors de la fenêtre : nouvelle alerte.
    let (url, st) = start().await;
    crisis(&st);
    st.record_alert(&format!("sc:crise:+:{yesterday}"), now - Duration::hours(30), true, "avant-hier").unwrap();
    let a = alerts(&url).await;
    assert_eq!(a.len(), 1, "{a:?}");
    assert!(a[0]["text"].as_str().unwrap().starts_with("Scénario crise"));
}
