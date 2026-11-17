//! Repas pris dehors : déduits des occupations exportées par l'agenda, sauf choix
//! contraire de l'utilisateur. Lieu de l'événement ignoré : le profil ne connaît pas le domicile.

use chrono::{NaiveDate, NaiveDateTime, NaiveTime};
use serde::Deserialize;

use crate::nature::Slot;

/// Occupation de l'agenda, telle qu'exportée.
#[derive(Debug, Clone, Deserialize)]
pub struct Busy {
    pub title: String,
    pub start: NaiveDateTime,
    pub end: NaiveDateTime,
    pub all_day: bool,
    #[serde(default)]
    pub transparent: bool,
}

// ponytail: fenêtres fixes (pas de réglage existant) ; préférence le jour où elles gênent.
/// Fenêtre d'un repas : un événement qui en couvre au moins la moitié fait manger dehors.
pub fn window(slot: Slot) -> Option<(NaiveTime, NaiveTime)> {
    let h = |h| NaiveTime::from_hms_opt(h, 0, 0).unwrap();
    match slot {
        Slot::Midi => Some((h(12), h(14))),
        Slot::Soir => Some((h(19), h(21))),
        _ => None,
    }
}

/// Taille maximale de l'export lu.
const EXPORT_MAX_BYTES: u64 = 2 * 1024 * 1024;

#[derive(Deserialize)]
struct Export {
    events: Vec<serde_json::Value>,
}

/// Occupations de l'export (fichier JSON écrit par l'agenda) qui touchent `from..=to`.
/// Absent, trop gros ou illisible : erreur. Une entrée illisible est écartée seule.
pub fn load(path: &str, from: NaiveDate, to: NaiveDate) -> Result<Vec<Busy>, String> {
    fn fail<E>(_: E) -> String {
        "agenda illisible".to_string()
    }
    if std::fs::metadata(path).map_err(fail)?.len() > EXPORT_MAX_BYTES {
        return Err("agenda illisible".into());
    }
    let export: Export = serde_json::from_str(&std::fs::read_to_string(path).map_err(fail)?).map_err(fail)?;
    let (lo, hi) = (from.and_time(NaiveTime::MIN), (to + chrono::Duration::days(1)).and_time(NaiveTime::MIN));
    Ok(export
        .events
        .into_iter()
        .filter_map(|v| serde_json::from_value::<Busy>(v).ok())
        .filter(|b| b.start < hi && b.end > lo)
        .map(|mut b| {
            b.title = b.title.chars().filter(|c| !c.is_control()).take(60).collect::<String>().trim().to_string();
            if b.title.is_empty() {
                b.title = "événement".into();
            }
            b
        })
        .collect())
}

/// Événement qui fait prendre ce repas dehors : journées entières sur **plusieurs** jours
/// couvrant le jour (une seule journée : anniversaire, férié, mention), ou événement
/// minuté couvrant au moins la moitié de la fenêtre. Transparent : jamais.
pub fn away(events: &[Busy], day: NaiveDate, slot: Slot) -> Option<&Busy> {
    let (a, b) = window(slot)?;
    let (ws, we) = (day.and_time(a), day.and_time(b));
    events.iter().filter(|e| !e.transparent).find(|e| {
        if e.all_day {
            (e.end.date() - e.start.date()).num_days() >= 2 && e.start.date() <= day && day < e.end.date()
        } else {
            (e.end.min(we) - e.start.max(ws)) * 2 >= we - ws
        }
    })
}

/// Où se prend un repas midi ou soir.
#[derive(Debug, Clone, PartialEq)]
pub struct Where {
    pub day: NaiveDate,
    pub slot: Slot,
    /// Titre de l'événement de l'agenda qui le couvre.
    pub event: Option<String>,
    /// Choix de l'utilisateur : vrai = dehors, faux = maison ; prime sur l'agenda.
    pub choice: Option<bool>,
}

impl Where {
    pub fn out(&self) -> bool {
        self.choice.unwrap_or(self.event.is_some())
    }

    /// Dehors par choix de l'utilisateur, pas seulement d'après l'agenda.
    pub fn manual(&self) -> bool {
        self.choice == Some(true)
    }

    /// Pour l'application : « Dehors : <événement> », ou « Dehors ».
    pub fn label(&self) -> String {
        match &self.event {
            Some(e) => format!("Dehors : {e}"),
            None => "Dehors".to_string(),
        }
    }

    /// Pour le moteur : le titre externe reste cité, jamais lu comme une consigne.
    pub fn reason(&self) -> String {
        match &self.event {
            Some(e) => format!("dehors (événement : « {e} »)"),
            None => "dehors (choix de l'utilisateur)".to_string(),
        }
    }
}

/// Repas midi et soir de `from..=to`, choix de l'utilisateur (jour, créneau, dehors) appliqués.
pub fn resolve(events: &[Busy], choices: &[(NaiveDate, Slot, bool)], from: NaiveDate, to: NaiveDate) -> Vec<Where> {
    from.iter_days()
        .take_while(|d| *d <= to)
        .flat_map(|day| [Slot::Midi, Slot::Soir].map(|slot| (day, slot)))
        .map(|(day, slot)| Where {
            day,
            slot,
            event: away(events, day, slot).map(|e| e.title.clone()),
            choice: choices.iter().find(|(d, s, _)| *d == day && *s == slot).map(|c| c.2),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(s: &str) -> NaiveDateTime {
        NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M").unwrap()
    }

    fn d(s: &str) -> NaiveDate {
        NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
    }

    fn busy(title: &str, a: &str, b: &str, all_day: bool) -> Busy {
        Busy { title: title.into(), start: at(a), end: at(b), all_day, transparent: false }
    }

    #[test]
    fn multi_day_all_day_covers_every_meal() {
        let ev = [busy("Week-end Exemple", "2026-11-21 00:00", "2026-11-23 00:00", true)];
        for day in ["2026-11-21", "2026-11-22"] {
            assert_eq!(away(&ev, d(day), Slot::Midi).unwrap().title, "Week-end Exemple");
            assert!(away(&ev, d(day), Slot::Soir).is_some());
        }
        assert!(away(&ev, d("2026-11-23"), Slot::Midi).is_none());
        // séjour minuté du vendredi soir au dimanche après-midi
        let ev = [busy("Séjour", "2026-11-20 18:00", "2026-11-22 15:00", false)];
        assert!(away(&ev, d("2026-11-20"), Slot::Soir).is_some());
        assert!(away(&ev, d("2026-11-20"), Slot::Midi).is_none());
        assert!(away(&ev, d("2026-11-21"), Slot::Midi).is_some());
        assert!(away(&ev, d("2026-11-22"), Slot::Midi).is_some());
        assert!(away(&ev, d("2026-11-22"), Slot::Soir).is_none());
    }

    #[test]
    fn single_all_day_event_does_not_count() {
        // anniversaire, jour férié, mention : la journée reste à la maison
        let ev = [busy("Anniversaire Exemple", "2026-11-24 00:00", "2026-11-25 00:00", true)];
        assert!(away(&ev, d("2026-11-24"), Slot::Midi).is_none());
        assert!(away(&ev, d("2026-11-24"), Slot::Soir).is_none());
    }

    #[test]
    fn transparent_event_does_not_count() {
        let mut e = busy("Vacances scolaires", "2026-11-21 00:00", "2026-11-30 00:00", true);
        e.transparent = true;
        assert!(away(&[e], d("2026-11-24"), Slot::Midi).is_none());
    }

    #[test]
    fn timed_event_must_cover_half_the_window() {
        let ev = [busy("Déjeuner Exemple", "2026-11-24 12:30", "2026-11-24 13:30", false), busy("Cinéma", "2026-11-24 20:00", "2026-11-24 23:00", false)];
        assert_eq!(away(&ev, d("2026-11-24"), Slot::Midi).unwrap().title, "Déjeuner Exemple");
        assert_eq!(away(&ev, d("2026-11-24"), Slot::Soir).unwrap().title, "Cinéma");
        // moins d'une heure sur la fenêtre : repas à la maison
        let ev = [busy("Réunion", "2026-11-24 11:00", "2026-11-24 12:30", false), busy("Sport", "2026-11-24 18:00", "2026-11-24 19:59", false)];
        assert!(away(&ev, d("2026-11-24"), Slot::Midi).is_none());
        assert!(away(&ev, d("2026-11-24"), Slot::Soir).is_none());
    }

    #[test]
    fn choice_overrides_agenda() {
        let ev = [busy("Week-end Exemple", "2026-11-21 00:00", "2026-11-23 00:00", true)];
        let w = resolve(&ev, &[(d("2026-11-21"), Slot::Soir, false), (d("2026-11-22"), Slot::Midi, true)], d("2026-11-21"), d("2026-11-22"));
        assert_eq!(w.len(), 4);
        assert!(w[0].out() && w[0].label() == "Dehors : Week-end Exemple");
        assert_eq!(w[0].reason(), "dehors (événement : « Week-end Exemple »)");
        assert!(!w[1].out(), "maison choisie malgré l'agenda");
        assert!(w[2].out() && w[2].manual());
        assert!(w[3].out() && !w[3].manual());
    }

    #[test]
    fn load_reads_export_in_range() {
        let dir = std::env::temp_dir().join(format!("kairos-dehors-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("occupations.json");
        std::fs::write(
            &path,
            r#"{"events": [
                {"start": "2026-11-21T00:00:00", "end": "2026-11-23T00:00:00", "all_day": true, "transparent": false, "title": "Week-end Exemple"},
                {"start": "2026-12-01T12:00:00", "end": "2026-12-01T13:00:00", "all_day": false, "transparent": true, "title": ""},
                {"start": "illisible", "end": "2026-11-21T13:00:00", "all_day": false, "transparent": false, "title": "x"}
            ]}"#,
        )
        .unwrap();
        let ev = load(path.to_str().unwrap(), d("2026-11-21"), d("2026-11-21")).unwrap();
        assert_eq!(ev.len(), 1);
        assert!(ev[0].all_day && ev[0].title == "Week-end Exemple");
        let ev = load(path.to_str().unwrap(), d("2026-12-01"), d("2026-12-01")).unwrap();
        assert!(ev[0].transparent && ev[0].title == "événement");
        assert!(load(dir.join("absente.json").to_str().unwrap(), d("2026-11-21"), d("2026-11-21")).is_err());
        std::fs::write(&path, "<html>").unwrap();
        assert!(load(path.to_str().unwrap(), d("2026-11-21"), d("2026-11-21")).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
