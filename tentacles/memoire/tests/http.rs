//! Contrat HTTP du tentacule, figé ici : le hook shell (curl) reprend exactement
//! cette forme de requête.
//!   POST /mcp
//!   Authorization: Bearer <jeton>
//!   Content-Type: application/json
//!   Accept: application/json, text/event-stream
//!   corps JSON-RPC 2.0 direct (`tools/list`, `tools/call`), sans `initialize`
//!   préalable ni session ni en-tête de version de protocole (mode sans état).

use std::sync::{Arc, Mutex};

use kairos_memoire::{store::{MAX_TRANSCRIPT_BYTES, MemoryStore}, tools::MemoryServer};
use serde_json::{Value, json};

async fn start() -> String {
    let store = Arc::new(Mutex::new(MemoryStore::open(":memory:").unwrap()));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        MemoryServer::new(store.clone())
    }));
    url
}

async fn post(url: &str, token: Option<&str>, body: Value) -> reqwest::Response {
    let mut r = reqwest::Client::new()
        .post(url)
        .header("Accept", "application/json, text/event-stream")
        .json(&body);
    if let Some(t) = token {
        r = r.bearer_auth(t);
    }
    r.send().await.unwrap()
}

fn call(name: &str, args: Value) -> Value {
    json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}})
}

fn list() -> Value {
    json!({"jsonrpc":"2.0","id":1,"method":"tools/list"})
}

#[tokio::test]
async fn tools_list_requires_token() {
    let url = start().await;
    assert_eq!(post(&url, None, list()).await.status(), 401);
    let v: Value = post(&url, Some("t"), list()).await.json().await.unwrap();
    assert_eq!(v["result"]["tools"].as_array().unwrap().len(), 15);
}

#[tokio::test]
async fn ingest_then_search_over_raw_http() {
    let url = start().await;
    let args = json!({"channel":"vocal","device":"tel","started_at":"2026-10-29 09:00",
        "ended_at":"2026-10-29 09:10:30","transcript":"on parle de rando ce week-end"});
    let v: Value = post(&url, Some("t"), call("conversation_ingest", args)).await.json().await.unwrap();
    assert_ne!(v["result"]["isError"], json!(true), "{v}");
    let text = v["result"]["content"][0]["text"].as_str().unwrap();
    let id = serde_json::from_str::<Value>(text).unwrap()["id"].as_i64().unwrap();

    let v: Value = post(&url, Some("t"), call("conversation_search", json!({"query":"rando"})))
        .await.json().await.unwrap();
    let text = v["result"]["content"][0]["text"].as_str().unwrap();
    let cards: Value = serde_json::from_str(text).unwrap();
    assert_eq!(cards[0]["id"].as_i64().unwrap(), id);
}

#[tokio::test]
async fn oversized_ingest_returns_tool_error() {
    let url = start().await;
    let args = json!({"channel":"c","device":"d","started_at":"2026-10-29 09:00",
        "ended_at":"2026-10-29 09:10","transcript":"x".repeat(MAX_TRANSCRIPT_BYTES + 1)});
    let v: Value = post(&url, Some("t"), call("conversation_ingest", args)).await.json().await.unwrap();
    assert_eq!(v["result"]["isError"], json!(true), "{v}");
    let v: Value = post(&url, Some("t"), list()).await.json().await.unwrap();
    assert!(v["result"]["tools"].is_array());
}

#[tokio::test]
async fn bad_datetime_is_tool_error_and_brief_is_empty_without_intentions() {
    let url = start().await;
    let args = json!({"what":"x","constraints":"","expires_at":"demain"});
    let v: Value = post(&url, Some("t"), call("intention_create", args)).await.json().await.unwrap();
    assert_eq!(v["result"]["isError"], json!(true), "{v}");
    let v: Value = post(&url, Some("t"), call("brief_section", json!({"period":"matin"}))).await.json().await.unwrap();
    assert_eq!(v["result"]["content"][0]["text"], json!(""));
}
