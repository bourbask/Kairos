//! Dépôt de bons de commande : surveille un dossier dédié (réservé aux bons,
//! pour ne jamais parcourir d'autres fichiers personnels), extrait le texte de
//! chaque nouveau PDF avec `pdftotext` et, s'il s'agit d'un bon de commande,
//! l'envoie au module alimentation (`order_import`, confirmé). Un autre PDF
//! déposé par erreur est lu localement puis ignoré : rien n'en sort.
//!
//! Variables : DEPOT_DIR (dossier surveillé), DEPOT_STATE (fichiers déjà vus),
//! DEPOT_INTERVAL (secondes entre deux passages), ALIMENTATION_URL (point MCP),
//! ALIMENTATION_TOKEN (jeton du module).

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use anyhow::{Context, bail};
use serde_json::{Value, json};

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

struct Client {
    http: reqwest::Client,
    url: String,
    token: String,
}

impl Client {
    async fn post(&self, body: &Value, session: Option<&str>) -> anyhow::Result<(Option<String>, Option<Value>)> {
        let mut req = self
            .http
            .post(&self.url)
            .bearer_auth(&self.token)
            .header("Accept", "application/json, text/event-stream")
            .json(body);
        if let Some(s) = session {
            req = req.header("Mcp-Session-Id", s);
        }
        let resp = req.send().await?.error_for_status()?;
        let sid = resp.headers().get("mcp-session-id").and_then(|v| v.to_str().ok()).map(str::to_string);
        let text = resp.text().await?;
        // Réponse JSON directe, ou flux d'événements dont on garde le dernier message.
        let msg = text
            .lines()
            .filter_map(|l| l.strip_prefix("data:"))
            .filter_map(|d| serde_json::from_str::<Value>(d.trim()).ok())
            .next_back()
            .or_else(|| serde_json::from_str(&text).ok());
        Ok((sid, msg))
    }

    /// Appelle un outil et rend son texte.
    async fn call(&self, tool: &str, args: Value) -> anyhow::Result<String> {
        let init = json!({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "depot", "version": "0.1"}}});
        let (sid, _) = self.post(&init, None).await?;
        self.post(&json!({"jsonrpc": "2.0", "method": "notifications/initialized"}), sid.as_deref()).await?;
        let call =
            json!({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": tool, "arguments": args}});
        let (_, msg) = self.post(&call, sid.as_deref()).await?;
        let msg = msg.context("réponse vide")?;
        if let Some(e) = msg.get("error") {
            bail!("{e}");
        }
        let result = &msg["result"];
        let text: Vec<&str> =
            result["content"].as_array().into_iter().flatten().filter_map(|c| c["text"].as_str()).collect();
        if result["isError"].as_bool() == Some(true) {
            bail!("{}", text.join("\n"));
        }
        Ok(text.join("\n"))
    }
}

/// Clé d'un fichier : nom, taille et date de modification.
fn key(p: &Path) -> Option<(String, SystemTime)> {
    let m = std::fs::metadata(p).ok()?;
    let modified = m.modified().ok()?;
    let secs = modified.duration_since(SystemTime::UNIX_EPOCH).ok()?.as_secs();
    Some((format!("{}|{}|{secs}", p.file_name()?.to_string_lossy(), m.len()), modified))
}

fn pdfs(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e.eq_ignore_ascii_case("pdf")))
        .collect();
    v.sort();
    v
}

fn text_of(p: &Path) -> Option<String> {
    let out = std::process::Command::new("pdftotext")
        .args(["-layout", "-q", "-enc", "UTF-8"])
        .arg(p)
        .arg("-")
        .output()
        .ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

fn load(state: &Path) -> HashSet<String> {
    std::fs::read_to_string(state).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

fn save(state: &Path, seen: &HashSet<String>) {
    let mut v: Vec<&String> = seen.iter().collect();
    v.sort();
    if let Err(e) = std::fs::write(state, serde_json::to_string_pretty(&v).unwrap_or_default()) {
        eprintln!("dépôt : état non enregistré ({e})");
    }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let dir = PathBuf::from(var("DEPOT_DIR").unwrap_or_else(|| "/depot".into()));
    let state = PathBuf::from(var("DEPOT_STATE").unwrap_or_else(|| "/data/depot.json".into()));
    let every = Duration::from_secs(var("DEPOT_INTERVAL").and_then(|v| v.parse().ok()).unwrap_or(30));
    let client = Client {
        http: reqwest::Client::builder().timeout(Duration::from_secs(60)).build()?,
        url: var("ALIMENTATION_URL").context("ALIMENTATION_URL manquant")?,
        token: var("ALIMENTATION_TOKEN").context("ALIMENTATION_TOKEN manquant")?,
    };
    let mut seen = load(&state);
    println!("dépôt : surveille {} toutes les {} s", dir.display(), every.as_secs());
    loop {
        let mut changed = false;
        for p in pdfs(&dir) {
            let Some((k, modified)) = key(&p) else { continue };
            // Copie peut-être en cours : attendre que le fichier se stabilise.
            if seen.contains(&k) || modified.elapsed().unwrap_or_default() < Duration::from_secs(10) {
                continue;
            }
            let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            let Some(text) = text_of(&p) else {
                seen.insert(k);
                changed = true;
                continue;
            };
            if kairos_alimentation::bon::parse(&text).is_err() {
                // Pas un bon de commande : rien n'est envoyé.
                seen.insert(k);
                changed = true;
                continue;
            }
            match client.call("order_import", json!({"text": text, "confirm": true})).await {
                Ok(out) => {
                    println!("dépôt : {name} → {}", out.lines().next().unwrap_or_default());
                    seen.insert(k);
                    changed = true;
                }
                // Module injoignable : nouvel essai au prochain passage.
                Err(e) => eprintln!("dépôt : {name} non transmis ({e})"),
            }
        }
        if changed {
            save(&state, &seen);
        }
        tokio::time::sleep(every).await;
    }
}
