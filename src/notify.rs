/// Notifications sortantes : ne transporte que le briefing (données publiques).

/// Poste `content` dans un salon Discord via un bot.
/// No-op (Ok(false)) si `NOTIFY_TOKEN` ou `NOTIFY_CHANNEL_ID` absent/vide.
pub async fn discord(content: &str) -> anyhow::Result<bool> {
    let (token, channel) = match (std::env::var("NOTIFY_TOKEN"), std::env::var("NOTIFY_CHANNEL_ID")) {
        (Ok(t), Ok(c)) if !t.is_empty() && !c.is_empty() => (t, c),
        _ => return Ok(false),
    };
    let url = format!("https://discord.com/api/v10/channels/{channel}/messages");
    let resp = reqwest::Client::new()
        .post(&url)
        .header("Authorization", format!("Bot {token}"))
        .json(&serde_json::json!({
            "content": truncate_discord(content),
            "allowed_mentions": { "parse": [] } // neutralise @everyone / mentions injectées via contenu externe
        }))
        .send()
        .await?;
    if !resp.status().is_success() {
        let status = resp.status();
        anyhow::bail!("Discord HTTP {status}: {}", resp.text().await.unwrap_or_default());
    }
    Ok(true)
}

/// Discord limite un message à 2000 caractères. Tronque proprement (sur les caractères, pas les octets).
fn truncate_discord(s: &str) -> String {
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
    use super::truncate_discord;

    #[test]
    fn court_inchange() {
        assert_eq!(truncate_discord("bonjour"), "bonjour");
    }

    #[test]
    fn long_tronque_sous_la_limite() {
        let out = truncate_discord(&"é".repeat(5000)); // multi-octets → on compte les caractères
        assert!(out.chars().count() <= 2000);
        assert!(out.ends_with("(briefing tronqué)"));
    }
}
