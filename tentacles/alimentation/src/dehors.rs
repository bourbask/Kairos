//! Repas pris dehors : déduits des événements de l'agenda, sauf choix contraire
//! de l'utilisateur. Lieu de l'événement ignoré : le profil ne connaît pas le domicile.

use chrono::{NaiveDate, NaiveDateTime, NaiveTime};
use rusqlite::{Connection, OpenFlags};

use crate::nature::Slot;

/// Événement de l'agenda, réduit à ce qui sert ici.
#[derive(Debug, Clone)]
pub struct Busy {
    pub title: String,
    pub start: NaiveDateTime,
    pub end: NaiveDateTime,
    pub all_day: bool,
}

// ponytail: fenêtres fixes (pas de réglage existant) ; préférence le jour où elles gênent.
/// Fenêtre d'un repas : un événement qui la chevauche fait manger dehors.
pub fn window(slot: Slot) -> Option<(NaiveTime, NaiveTime)> {
    let h = |h| NaiveTime::from_hms_opt(h, 0, 0).unwrap();
    match slot {
        Slot::Midi => Some((h(12), h(14))),
        Slot::Soir => Some((h(19), h(21))),
        _ => None,
    }
}

const FMT: &str = "%Y-%m-%dT%H:%M:%S";

/// Titre affichable d'un événement externe : sans caractère de contrôle, borné.
fn title(raw: Option<String>) -> String {
    let t: String = raw.unwrap_or_default().chars().filter(|c| !c.is_control()).take(60).collect();
    if t.trim().is_empty() { "événement".to_string() } else { t.trim().to_string() }
}

/// Événements de la base de l'agenda (lecture seule) qui touchent `from..=to`.
/// Les repas et tâches écrits par Kairos lui-même sont écartés.
pub fn load(path: &str, from: NaiveDate, to: NaiveDate) -> Result<Vec<Busy>, String> {
    let fail = |_| "agenda illisible".to_string();
    let c = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX)
        .map_err(fail)?;
    let mut st = c
        .prepare(
            "SELECT summary, start_time, end_time, all_day FROM calendar_events
             WHERE start_time < ?2 AND end_time > ?1
               AND uid NOT LIKE 'kairos-repas-%' AND uid NOT LIKE 'kairos-task-%'",
        )
        .map_err(fail)?;
    let lo = from.and_time(NaiveTime::MIN).format(FMT).to_string();
    let hi = (to + chrono::Duration::days(1)).and_time(NaiveTime::MIN).format(FMT).to_string();
    let rows = st
        .query_map([lo, hi], |r| {
            Ok((r.get::<_, Option<String>>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, i64>(3)?))
        })
        .map_err(fail)?;
    Ok(rows
        .flatten()
        .filter_map(|(s, a, b, all)| {
            Some(Busy {
                title: title(s),
                start: NaiveDateTime::parse_from_str(&a, FMT).ok()?,
                end: NaiveDateTime::parse_from_str(&b, FMT).ok()?,
                all_day: all != 0,
            })
        })
        .collect())
}

/// Événement qui fait prendre ce repas dehors : journée(s) entière(s) couvrant le
/// jour, ou chevauchement de la fenêtre du repas (événements sur plusieurs jours compris).
pub fn away(events: &[Busy], day: NaiveDate, slot: Slot) -> Option<&Busy> {
    let (a, b) = window(slot)?;
    let (ws, we) = (day.and_time(a), day.and_time(b));
    events.iter().find(|e| if e.all_day { e.start.date() <= day && day < e.end.date() } else { e.start < we && e.end > ws })
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

    /// « Dehors : <événement> », ou « Dehors » quand c'est un choix sans événement.
    pub fn label(&self) -> String {
        match &self.event {
            Some(e) => format!("Dehors : {e}"),
            None => "Dehors".to_string(),
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
        Busy { title: title.into(), start: at(a), end: at(b), all_day }
    }

    #[test]
    fn all_day_and_multi_day_cover_every_meal() {
        // week-end sur deux jours entiers : samedi et dimanche, pas lundi
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
    fn overlap_with_meal_window() {
        let ev = [busy("Déjeuner Exemple", "2026-11-24 12:30", "2026-11-24 13:30", false), busy("Cinéma", "2026-11-24 20:30", "2026-11-24 23:00", false)];
        assert_eq!(away(&ev, d("2026-11-24"), Slot::Midi).unwrap().title, "Déjeuner Exemple");
        assert_eq!(away(&ev, d("2026-11-24"), Slot::Soir).unwrap().title, "Cinéma");
        // juste avant / juste après : pas de chevauchement
        let ev = [busy("Réunion", "2026-11-24 10:00", "2026-11-24 12:00", false), busy("Sport", "2026-11-24 14:00", "2026-11-24 19:00", false)];
        assert!(away(&ev, d("2026-11-24"), Slot::Midi).is_none());
        assert!(away(&ev, d("2026-11-24"), Slot::Soir).is_none());
    }

    #[test]
    fn choice_overrides_agenda() {
        let ev = [busy("Week-end Exemple", "2026-11-21 00:00", "2026-11-22 00:00", true)];
        let w = resolve(&ev, &[(d("2026-11-21"), Slot::Soir, false), (d("2026-11-22"), Slot::Midi, true)], d("2026-11-21"), d("2026-11-22"));
        assert_eq!(w.len(), 4);
        assert!(w[0].out() && w[0].label() == "Dehors : Week-end Exemple");
        assert!(!w[1].out(), "maison choisie malgré l'agenda");
        assert!(w[2].out() && w[2].label() == "Dehors");
        assert!(!w[3].out());
    }

    #[test]
    fn load_reads_agenda_without_kairos_entries() {
        let dir = std::env::temp_dir().join(format!("kairos-dehors-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("agenda.db");
        let _ = std::fs::remove_file(&path);
        let c = Connection::open(&path).unwrap();
        c.execute_batch(
            "CREATE TABLE calendar_events (uid TEXT PRIMARY KEY, summary TEXT, start_time TEXT, end_time TEXT, all_day INTEGER);
             INSERT INTO calendar_events VALUES ('a', 'Week-end Exemple', '2026-11-21T00:00:00', '2026-11-23T00:00:00', 1);
             INSERT INTO calendar_events VALUES ('kairos-repas-20261121-midi', 'Midi : Dhal', '2026-11-21T12:15:00', '2026-11-21T13:00:00', 0);
             INSERT INTO calendar_events VALUES ('kairos-task-1@kairos', 'Tâche', '2026-11-21T00:00:00', '2026-11-22T00:00:00', 1);
             INSERT INTO calendar_events VALUES ('b', NULL, '2026-12-01T12:00:00', '2026-12-01T13:00:00', 0);",
        )
        .unwrap();
        let ev = load(path.to_str().unwrap(), d("2026-11-21"), d("2026-11-21")).unwrap();
        assert_eq!(ev.len(), 1);
        assert!(ev[0].all_day && ev[0].title == "Week-end Exemple");
        assert!(load(dir.join("absente.db").to_str().unwrap(), d("2026-11-21"), d("2026-11-21")).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
