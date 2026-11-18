//! Export minimal des occupations de l'agenda, pour les modules qui n'ont pas
//! besoin de la base : début, fin, journée entière, transparence, titre tronqué.

use std::path::Path;

use chrono::{Duration, NaiveDate, NaiveTime};
use serde_json::json;

use crate::models::CalendarEvent;
use crate::storage::Storage;

/// Nom du fichier écrit dans le répertoire d'export.
pub const EXPORT_FILE: &str = "occupations.json";
/// Longueur maximale d'un titre exporté.
pub const TITLE_MAX: usize = 60;
const BEFORE_DAYS: i64 = 1;
const AFTER_DAYS: i64 = 45;
const FMT: &str = "%Y-%m-%dT%H:%M:%S";

/// Bornes de l'export : de la veille à J+45 inclus.
pub fn window(today: NaiveDate) -> (chrono::NaiveDateTime, chrono::NaiveDateTime) {
    (
        (today - Duration::days(BEFORE_DAYS)).and_time(NaiveTime::MIN),
        (today + Duration::days(AFTER_DAYS + 1)).and_time(NaiveTime::MIN),
    )
}

/// JSON de l'export. Les entrées écrites par un module Kairos (`kairos-…`) sont écartées.
pub fn export_json(events: &[CalendarEvent]) -> String {
    let items: Vec<_> = events
        .iter()
        .filter(|e| !e.uid.starts_with("kairos-"))
        .map(|e| {
            let title: String = e.summary.as_deref().unwrap_or("").chars().filter(|c| !c.is_control()).take(TITLE_MAX).collect();
            json!({
                "start": e.start_time.format(FMT).to_string(),
                "end": e.end_time.format(FMT).to_string(),
                "all_day": e.all_day,
                "transparent": e.transparent,
                "title": title.trim(),
            })
        })
        .collect();
    json!({ "events": items }).to_string()
}

/// Écrit l'export de `storage` dans `dir`, de façon atomique (fichier temporaire puis renommage).
pub fn write_export(storage: &Storage, dir: &Path, today: NaiveDate) -> anyhow::Result<()> {
    let (from, to) = window(today);
    let body = export_json(&storage.get_events_between(from, to)?);
    let tmp = dir.join(format!(".{EXPORT_FILE}.tmp"));
    std::fs::write(&tmp, body)?;
    std::fs::rename(&tmp, dir.join(EXPORT_FILE))?;
    Ok(())
}

/// Export vers `AGENDA_EXPORT_DIR` s'il est défini ; un échec est journalisé, jamais fatal.
pub fn export_from_env(storage: &Storage) {
    let Some(dir) = std::env::var("AGENDA_EXPORT_DIR").ok().filter(|d| !d.trim().is_empty()) else { return };
    let today = chrono::Utc::now().with_timezone(&super::USER_TZ).date_naive();
    if let Err(e) = write_export(storage, Path::new(&dir), today) {
        tracing::warn!("export de l'agenda impossible : {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(uid: &str, day: u32, title: &str) -> CalendarEvent {
        let d = NaiveDate::from_ymd_opt(2026, 11, day).unwrap();
        CalendarEvent {
            uid: uid.into(), summary: Some(title.into()), description: Some("détail".into()), location: Some("example.com".into()),
            start_time: d.and_hms_opt(12, 0, 0).unwrap(), end_time: d.and_hms_opt(13, 0, 0).unwrap(),
            all_day: false, source: "calendar".into(), etag: None, transparent: day == 20,
        }
    }

    #[test]
    fn export_is_minimal_and_written_atomically() {
        let s = Storage::open(":memory:").unwrap();
        let long = "x".repeat(100);
        for e in [ev("a", 18, "Déjeuner Exemple"), ev("kairos-repas-1", 18, "Midi"), ev("kairos-doc-1", 18, "Doc"), ev("b", 20, &long), ev("old", 1, "Ancien")] {
            s.upsert_calendar_event(&e).unwrap();
        }
        let dir = std::env::temp_dir().join(format!("kairos-export-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        write_export(&s, &dir, NaiveDate::from_ymd_opt(2026, 11, 18).unwrap()).unwrap();
        let text = std::fs::read_to_string(dir.join(EXPORT_FILE)).unwrap();
        assert!(!dir.join(format!(".{EXPORT_FILE}.tmp")).exists());
        let v: serde_json::Value = serde_json::from_str(&text).unwrap();
        let evs = v["events"].as_array().unwrap();
        assert_eq!(evs.len(), 2, "{text}");
        assert_eq!(evs[0], json!({"start": "2026-11-18T12:00:00", "end": "2026-11-18T13:00:00", "all_day": false, "transparent": false, "title": "Déjeuner Exemple"}));
        assert_eq!(evs[1]["title"].as_str().unwrap().chars().count(), TITLE_MAX);
        assert_eq!(evs[1]["transparent"], true);
        assert!(!text.contains("example.com") && !text.contains("détail") && !text.contains("kairos"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
