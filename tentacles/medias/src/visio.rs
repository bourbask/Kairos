//! Débit réduit des torrents pendant les visioconférences (opt-in).
//!
//! Lit l'agenda personnel en CalDAV : un événement en cours dont le titre, le
//! lieu, la description ou le lien contient un mot-clé de visio déclenche le
//! débit réduit ; il est rétabli à la fin, seulement si c'est ce mécanisme qui
//! l'avait réduit (un réglage manuel n'est jamais défait).

use chrono::{DateTime, Duration, Utc};

use crate::caldav::CalDav;
use crate::config::Stack;
use crate::services as sv;
use crate::store::Store;

/// Mots-clés par défaut (minuscules).
pub const DEFAULT_KEYWORDS: &str =
    "visio,meet.google,teams.microsoft,teams.live,zoom.us,webex,jitsi,whereby,appel vidéo";

const KV_SLOWED: &str = "visio_slowed";

#[derive(Debug, Clone)]
pub struct VisioWatch {
    pub cal: CalDav,
    pub keywords: Vec<String>,
    pub minutes: u64,
}

pub fn parse_keywords(s: &str) -> Vec<String> {
    s.split(',').map(|k| k.trim().to_lowercase()).filter(|k| !k.is_empty()).collect()
}

fn xml_unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#13;", "")
        .replace("&amp;", "&")
}

/// Titre du premier événement de visio trouvé dans une réponse REPORT.
pub fn find_visio(report: &str, keywords: &[String]) -> Option<String> {
    let text = xml_unescape(report).replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "");
    for ev in text.split("BEGIN:VEVENT").skip(1) {
        let ev = ev.split("END:VEVENT").next().unwrap_or("");
        let mut summary = String::new();
        let mut hit = false;
        for line in ev.lines() {
            let Some((name, value)) = line.split_once(':') else { continue };
            let field = name.split(';').next().unwrap_or("").to_uppercase();
            if !matches!(field.as_str(), "SUMMARY" | "LOCATION" | "DESCRIPTION" | "URL" | "CONFERENCE") {
                continue;
            }
            if field == "SUMMARY" {
                summary = value.trim().to_string();
            }
            let v = value.to_lowercase();
            hit |= keywords.iter().any(|k| v.contains(k.as_str()));
        }
        if hit {
            return Some(if summary.is_empty() { "visio".to_string() } else { summary });
        }
    }
    None
}

/// Un passage : renvoie l'action menée (« ralenti », « rétabli ») ou `None`.
pub async fn step(stack: &Stack, store: &Store, w: &VisioWatch, now: DateTime<Utc>) -> Option<String> {
    let report = w.cal.report(now, now + Duration::minutes(1)).await.ok()?;
    let visio = find_visio(&report, &w.keywords);
    let ours = store.get_kv(KV_SLOWED).ok().flatten().is_some();
    match (visio, ours) {
        (Some(title), false) => {
            let slow = sv::qbit_slow_mode(&stack.qbit).await.ok()?;
            if slow {
                // Déjà réduit à la main : on n'y touche pas, et on ne le rétablira pas.
                return None;
            }
            sv::qbit_toggle_slow(&stack.qbit).await.ok()?;
            let _ = store.set_kv(KV_SLOWED, &title);
            Some(format!("ralenti ({title})"))
        }
        (None, true) => {
            if sv::qbit_slow_mode(&stack.qbit).await.ok()? {
                sv::qbit_toggle_slow(&stack.qbit).await.ok()?;
            }
            let _ = store.del_kv(KV_SLOWED);
            Some("rétabli".to_string())
        }
        _ => None,
    }
}

/// Visio en cours ayant réduit le débit (pour `stack_health`).
pub fn slowed_for(store: &Store) -> Option<String> {
    store.get_kv(KV_SLOWED).ok().flatten()
}

pub async fn run(stack: std::sync::Arc<Stack>, store: std::sync::Arc<Store>) {
    let Some(w) = stack.visio.clone() else { return };
    let mut every = tokio::time::interval(std::time::Duration::from_secs(w.minutes * 60));
    every.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        every.tick().await;
        if let Some(action) = step(&stack, &store, &w, Utc::now()).await {
            eprintln!("medias/visio : débit {action}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kw() -> Vec<String> {
        parse_keywords(DEFAULT_KEYWORDS)
    }

    #[test]
    fn detects_visio_in_any_text_field() {
        let r = "<multistatus><response><calendar-data>BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Point d&apos;équipe\r\nLOCATION:https://meet.google.com/abc-defg\r\nEND:VEVENT\r\nEND:VCALENDAR</calendar-data></response></multistatus>";
        assert_eq!(find_visio(r, &kw()).as_deref(), Some("Point d'équipe"));
        let folded = "BEGIN:VEVENT\r\nSUMMARY;LANGUAGE=fr:Entretien\r\nDESCRIPTION:Rejoindre : https://teams.mi\r\n crosoft.com/l/x\r\nEND:VEVENT";
        assert_eq!(find_visio(folded, &kw()).as_deref(), Some("Entretien"));
        let plain = "BEGIN:VEVENT\r\nSUMMARY:Dentiste\r\nLOCATION:Centre-ville\r\nEND:VEVENT";
        assert_eq!(find_visio(plain, &kw()), None);
        assert_eq!(find_visio("<multistatus/>", &kw()), None);
        assert_eq!(parse_keywords(" Visio , ,zoom.us"), ["visio", "zoom.us"]);
    }
}
