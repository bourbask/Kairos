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
    let settings = Arc::new(Settings {
        root: root.clone(),
        private: vec!["identite".into()],
        tz: chrono_tz::Europe::Paris,
        model: None,
        write: true,
    });
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

#[tokio::test]
async fn deadlines_and_facts_respect_private_natures() {
    let root = tree();
    let index = Arc::new(Index::memory());
    index.scan(&root, 1).unwrap();
    let soon = (chrono::Utc::now() + chrono::Duration::days(10)).format("%Y-%m-%d").to_string();
    let later = (chrono::Utc::now() + chrono::Duration::days(200)).format("%Y-%m-%d").to_string();
    let auto = json!({"type_document": "contrat d'assurance auto", "organisme": "Assureur Oméga", "reference": "AB-12",
        "resume": "Contrat auto.", "dates": [{"type": "echeance", "date": later, "libelle": "échéance annuelle"}],
        "montants": [{"libelle": "prime", "montant": 42.5, "periodicite": "mensuel"}]});
    let id = json!({"type_document": "carte d'identité", "organisme": "Préfecture Secrète", "reference": "X999",
        "resume": "Pièce.", "dates": [{"type": "expiration", "date": soon, "libelle": "fin de validité"}], "montants": []});
    index.set_facts("Assurances/auto.md", "m", Some(&auto.to_string()), 1).unwrap();
    index.set_facts("Identité/carte.md", "m", Some(&id.to_string()), 1).unwrap();
    let settings = Arc::new(Settings {
        root: root.clone(),
        private: vec!["identite".into()],
        tz: chrono_tz::Europe::Paris,
        model: Some("m".into()),
        write: false,
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        DocServer::new(index.clone(), settings.clone())
    }));

    let (t, _) = call(&url, "deadlines", json!({"days": 365})).await;
    assert!(t.contains("carte d'identité : expiration — document privé"), "{t}");
    assert!(t.contains("contrat d'assurance auto — Assureur Oméga : échéance — Assurances/auto.md"), "{t}");
    assert!(!t.contains("Préfecture") && !t.contains("Identité/carte.md"), "{t}");
    let (t, _) = call(&url, "deadlines", json!({"days": 30})).await;
    assert!(t.contains("carte d'identité") && !t.contains("assurance"), "{t}");

    let (t, _) = call(&url, "contract_facts", json!({"query": "assurance"})).await;
    assert!(t.contains("référence : AB-12") && t.contains("prime : 42,50 € (mensuel)"), "{t}");
    let (t, _) = call(&url, "contract_facts", json!({})).await;
    assert!(!t.contains("X999") && !t.contains("carte d'identité"), "{t}");

    let (t, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(t.starts_with("Documents — échéances proches :") && t.contains("document privé"), "{t}");
    let (t, _) = call(&url, "docs_overview", json!({})).await;
    assert!(t.contains("Faits extraits : 2 document(s)"), "{t}");
    std::fs::remove_dir_all(&root).unwrap();
}

async fn serve(root: &std::path::Path, index: Arc<Index>, write: bool) -> String {
    let settings = Arc::new(Settings {
        root: root.to_path_buf(),
        private: vec!["identite".into()],
        tz: chrono_tz::Europe::Paris,
        model: Some("m".into()),
        write,
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        DocServer::new(index.clone(), settings.clone())
    }));
    url
}

#[tokio::test]
async fn writes_are_previewed_then_applied_without_overwriting() {
    let root = tree();
    std::fs::create_dir_all(root.join("Dossier/Copies")).unwrap();
    std::fs::write(root.join("Dossier/Copies/auto-copie.md"), std::fs::read(root.join("Assurances/auto.md")).unwrap())
        .unwrap();
    std::fs::write(root.join("Dossier/carte.md"), std::fs::read(root.join("Identité/carte.md")).unwrap()).unwrap();
    let index = Arc::new(Index::memory());
    index.scan(&root, 1).unwrap();
    let facts = json!({"type_document": "Contrat d'assurance auto", "organisme": "Assureur Oméga", "reference": null,
        "resume": "", "dates": [{"type": "emission", "date": "2026-01-10", "libelle": ""}], "montants": []});
    index.set_facts("Assurances/auto.md", "m", Some(&facts.to_string()), 1).unwrap();

    // Écritures coupées : seuls les aperçus de doublons et de noms restent possibles.
    let ro = serve(&root, index.clone(), false).await;
    let (t, err) = call(&ro, "doc_move", json!({"moves": [{"from": "Assurances/auto.md", "to": "X/"}]})).await;
    assert!(err && t.contains("DOCUMENTS_WRITE"), "{t}");
    let (t, err) = call(&ro, "duplicates", json!({})).await;
    assert!(!err && t.contains("Aperçu"), "{t}");

    let url = serve(&root, index.clone(), true).await;
    // Doublons : l'original le moins profond est gardé ; la copie d'une pièce privée garde la pièce privée.
    let (t, _) = call(&url, "duplicates", json!({})).await;
    assert!(t.starts_with("2 copie(s) en trop dans 2 groupe(s)"), "{t}");
    assert!(t.contains("- garder : Assurances/auto.md\n    corbeille : Dossier/Copies/auto-copie.md"), "{t}");
    assert!(t.contains("- garder : document privé\n    corbeille : Dossier/carte.md"), "{t}");
    assert!(!t.contains("Identité/"), "{t}");
    assert!(root.join("Dossier/Copies/auto-copie.md").exists(), "aperçu : rien ne bouge");

    // Pièces privées intouchables, chemins hors racine refusés, jamais d'écrasement.
    for (args, msg) in [
        (json!({"moves": [{"from": "Identité/carte.md", "to": "X/"}]}), "n'est pas exposée"),
        (json!({"moves": [{"from": "Assurances/auto.md", "to": "../evasion.md"}]}), "Chemin refusé"),
        (json!({"moves": [{"from": "Assurances/auto.md", "to": "Assurances/scan.jpg"}]}), "existe déjà"),
        (json!({"moves": [{"from": "Assurances/auto.md", "to": "Identité/"}]}), "non exposée"),
    ] {
        let (t, err) = call(&url, "doc_move", args).await;
        assert!(err && t.contains(msg), "{msg} : {t}");
    }

    let (t, _) = call(&url, "duplicates", json!({"confirm": true})).await;
    assert!(t.contains("2 fait(s), 0 en échec"), "{t}");
    assert!(!root.join("Dossier").exists(), "dossiers vidés retirés");
    let trash: Vec<_> = std::fs::read_dir(root.join("_Corbeille")).unwrap().flatten().collect();
    assert_eq!(trash.len(), 1, "un dossier daté");
    assert!(trash[0].path().join("Dossier/Copies/auto-copie.md").exists());

    // Nom normalisé tiré des faits, puis déplacement d'un dossier à l'autre.
    let (t, _) = call(&url, "rename_plan", json!({})).await;
    assert!(t.contains("Assurances/auto.md → Assurances/2026-01-10_contrat-d-assurance-auto_assureur-omega.md"), "{t}");
    let (t, _) = call(&url, "rename_plan", json!({"confirm": true})).await;
    assert!(t.contains("1 fait(s)"), "{t}");
    let new = "Assurances/2026-01-10_contrat-d-assurance-auto_assureur-omega.md";
    let (t, _) = call(&url, "doc_move", json!({"moves": [{"from": new, "to": "Véhicule/"}], "confirm": true})).await;
    assert!(t.contains("1 fait(s)"), "{t}");
    let moved = "Véhicule/2026-01-10_contrat-d-assurance-auto_assureur-omega.md";
    assert!(root.join(moved).exists());
    // L'index suit : texte et faits conservés.
    let (t, _) = call(&url, "doc_excerpt", json!({"path": moved, "query": "franchise"})).await;
    assert!(t.contains("franchise de 150 euros"), "{t}");
    let (t, _) = call(&url, "contract_facts", json!({"query": "omega"})).await;
    assert!(t.contains(moved), "{t}");

    // Corbeille en deux temps ; le scan suivant ne la réindexe pas.
    let (t, _) = call(&url, "doc_delete", json!({"paths": ["Assurances/scan.jpg"]})).await;
    assert!(t.contains("Aperçu") && root.join("Assurances/scan.jpg").exists(), "{t}");
    let (t, _) = call(&url, "doc_delete", json!({"paths": ["Assurances/scan.jpg"], "confirm": true})).await;
    assert!(t.contains("1 fait(s)") && !root.join("Assurances").exists(), "{t}");
    let r = index.scan(&root, 2).unwrap();
    assert_eq!((r.added, r.removed), (0, 0), "{r:?}");
    std::fs::remove_dir_all(&root).unwrap();
}

#[tokio::test]
async fn missing_documents_checklist() {
    let root = tree();
    let index = Arc::new(Index::memory());
    index.scan(&root, 1).unwrap();
    let auto = json!({"type_document": "Attestation d'assurance automobile", "organisme": "Assureur", "reference": null,
        "resume": "", "dates": [], "montants": []});
    let id = json!({"type_document": "carte nationale d'identité", "organisme": "Préfecture Secrète", "reference": null,
        "resume": "", "dates": [], "montants": []});
    index.set_facts("Assurances/auto.md", "m", Some(&auto.to_string()), 1).unwrap();
    index.set_facts("Identité/carte.md", "m", Some(&id.to_string()), 1).unwrap();
    let url = serve(&root, index, false).await;
    let (t, _) = call(&url, "missing_documents", json!({})).await;
    assert!(t.contains("✓ Attestation d'assurance auto — Assurances/auto.md"), "{t}");
    assert!(t.contains("✓ Carte d'identité — document privé") && !t.contains("Préfecture"), "{t}");
    assert!(t.contains("✗ Assurance habitation — manquant"), "{t}");
    let (t, _) = call(&url, "missing_documents", json!({"domain": "vehicule"})).await;
    assert!(t.starts_with("Pièces attendues — 1 présente(s), 4 à trouver") && !t.contains("Logement"), "{t}");
    let (t, err) = call(&url, "missing_documents", json!({"domain": "jardin"})).await;
    assert!(err && t.contains("Domaines :"), "{t}");
    std::fs::remove_dir_all(&root).unwrap();
}
