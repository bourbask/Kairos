/// Notifications sortantes : ne transporte que le briefing (données publiques).

/// Poste `content` dans le canal de digest via un bot.
/// No-op (Ok(false)) si `NOTIFY_API_URL`, `NOTIFY_TOKEN` ou `NOTIFY_CHANNEL_ID` absent/vide.
pub async fn digest(content: &str) -> anyhow::Result<bool> {
    let (base, token, channel) = match (
        std::env::var("NOTIFY_API_URL"),
        std::env::var("NOTIFY_TOKEN"),
        std::env::var("NOTIFY_CHANNEL_ID"),
    ) {
        (Ok(b), Ok(t), Ok(c)) if !b.is_empty() && !t.is_empty() && !c.is_empty() => (b, t, c),
        _ => return Ok(false),
    };
    let url = format!("{}/channels/{channel}/messages", base.trim_end_matches('/'));
    let resp = reqwest::Client::new()
        .post(&url)
        .header("Authorization", format!("Bot {token}"))
        .json(&serde_json::json!({
            "content": truncate_digest(content),
            "allowed_mentions": { "parse": [] } // neutralise @everyone / mentions injectées via contenu externe
        }))
        .send()
        .await?;
    if !resp.status().is_success() {
        let status = resp.status();
        anyhow::bail!("digest HTTP {status}: {}", resp.text().await.unwrap_or_default());
    }
    Ok(true)
}

/// Pousse une alerte push pour un signal fort isolé du digest complet.
/// No-op (Ok(false)) si `ALERT_URL` ou `ALERT_TOPIC` absent/vide.
pub async fn alert(title: &str, message: &str) -> anyhow::Result<bool> {
    let (base, topic) = match (std::env::var("ALERT_URL"), std::env::var("ALERT_TOPIC")) {
        (Ok(u), Ok(t)) if !u.is_empty() && !t.is_empty() => (u, t),
        _ => return Ok(false),
    };
    // Titre en query param (pas en header) : les headers HTTP rejettent l'UTF-8 non-ASCII,
    // or titre/entreprise proviennent d'offres externes non fiables (accents, etc.).
    let url = format!("{}/{topic}", base.trim_end_matches('/'));
    let mut req = reqwest::Client::new()
        .post(&url)
        .query(&[("title", title)])
        .header("Priority", "high");
    if let Ok(token) = std::env::var("ALERT_TOKEN") {
        if !token.is_empty() {
            req = req.bearer_auth(token);
        }
    }
    let resp = req.body(message.to_string()).send().await?;
    if !resp.status().is_success() {
        let status = resp.status();
        anyhow::bail!("alerte HTTP {status}: {}", resp.text().await.unwrap_or_default());
    }
    Ok(true)
}

/// Le service de digest limite un message à 2000 caractères. Tronque proprement (sur les caractères, pas les octets).
fn truncate_digest(s: &str) -> String {
    const MAX: usize = 2000;
    const SUFFIX: &str = "\n… (briefing tronqué)";
    if s.chars().count() <= MAX {
        return s.to_string();
    }
    let keep = MAX - SUFFIX.chars().count();
    let head: String = s.chars().take(keep).collect();
    format!("{head}{SUFFIX}")
}

#[cfg(test)]
mod tests {
    use super::truncate_digest;

    #[test]
    fn court_inchange() {
        assert_eq!(truncate_digest("bonjour"), "bonjour");
    }

    #[test]
    fn long_tronque_sous_la_limite() {
        let out = truncate_digest(&"é".repeat(5000)); // multi-octets → on compte les caractères
        assert!(out.chars().count() <= 2000);
        assert!(out.ends_with("(briefing tronqué)"));
    }
}
