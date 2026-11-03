//! Tentacule documents servi en HTTP sur une arborescence fictive.

use std::path::PathBuf;
use std::sync::Arc;

use kairos_documents::{
    index::Index,
    tools::{DocServer, Settings},
};
use serde_json::{Value, json};

fn tree() -> PathBuf {
    let n = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
    let d = std::env::temp_dir().join(format!("kd-http-{}-{n}", std::process::id()));
    std::fs::create_dir_all(d.join("Assurances")).unwrap();
    std::fs::create_dir_all(d.join("Identité")).unwrap();
    std::fs::write(
        d.join("Assurances/auto.md"),
        format!("{} Garantie bris de glace : franchise de 150 euros. {}", "a ".repeat(400), "b ".repeat(400)),
    )
    .unwrap();
    std::fs::write(d.join("Identité/carte.md"), "Numéro de carte confidentiel, garantie absolue de secret.").unwrap();
    std::fs::write(d.join("Assurances/scan.jpg"), [0u8; 4]).unwrap();
    d
}

async fn start() -> (String, PathBuf) {
    let root = tree();
    let index = Arc::new(Index::memory());
    index.scan(&root, 1).unwrap();
    let settings =
        Arc::new(Settings { root: root.clone(), private: vec!["identite".into()], tz: chrono_tz::Europe::Paris });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        DocServer::new(index.clone(), settings.clone())
    }));
    (url, root)
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

#[tokio::test]
async fn private_natures_are_counted_but_never_exposed() {
    let (url, root) = start().await;
    let (t, _) = call(&url, "docs_overview", json!({})).await;
    assert!(t.starts_with("3 documents"), "{t}");
    assert!(t.contains("- Identité : 1 document(s) — contenu non exposé"), "{t}");
    assert!(t.contains("- Assurances : 2 document(s), 1 sans texte lisible"), "{t}");

    // La recherche ignore la nature privée, même si elle contient le mot.
    let (t, _) = call(&url, "docs_search", json!({"query": "garantie"})).await;
    assert!(t.contains("Assurances/auto.md") && !t.contains("Identité") && !t.contains("confidentiel"), "{t}");
    for (tool, args) in [
        ("docs_list", json!({"nature": "IDENTITÉ"})),
        ("docs_search", json!({"query": "carte", "nature": "identité"})),
        ("doc_excerpt", json!({"path": "Identité/carte.md"})),
    ] {
        let (t, err) = call(&url, tool, args).await;
        assert!(err && t.contains("n'est pas exposée"), "{tool} : {t}");
    }
    std::fs::remove_dir_all(&root).unwrap();
}

#[tokio::test]
async fn excerpts_are_targeted_never_whole_documents() {
    let (url, root) = start().await;
    let (t, _) = call(&url, "doc_excerpt", json!({"path": "Assurances/auto.md", "query": "franchise"})).await;
    assert!(t.contains("franchise de 150 euros"), "{t}");
    assert!(t.chars().count() < 700, "extrait trop long : {}", t.chars().count());
    let (t, _) = call(&url, "doc_excerpt", json!({"path": "Assurances/scan.jpg"})).await;
    assert!(t.contains("pas de texte lisible"), "{t}");
    let (t, err) = call(&url, "doc_excerpt", json!({"path": "../../etc/passwd"})).await;
    assert!(err && t.contains("Document inconnu"), "{t}");
    let (t, err) = call(&url, "docs_list", json!({"nature": "Inconnue"})).await;
    assert!(err && t.contains("Natures : Assurances, Identité"), "{t}");
    std::fs::remove_dir_all(&root).unwrap();
}
