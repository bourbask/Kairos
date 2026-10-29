//! Calcul des plages libres d'une journée.

use chrono::{NaiveDateTime, NaiveTime};
use kairos::models::CalendarEvent;

// ponytail: bornes fixes ; à passer en préférence mémoire quand la tête les lira
pub const DAY_START: NaiveTime = NaiveTime::from_hms_opt(7, 0, 0).unwrap();
pub const DAY_END: NaiveTime = NaiveTime::from_hms_opt(23, 0, 0).unwrap();

/// Trous entre événements (hors « journée entière ») sur `[day_start, day_end]`,
/// chevauchements fusionnés, trous plus courts que `min_minutes` écartés.
pub fn free_slots(
    events: &[CalendarEvent],
    day_start: NaiveDateTime,
    day_end: NaiveDateTime,
    min_minutes: i64,
) -> Vec<(NaiveDateTime, NaiveDateTime)> {
    let mut busy: Vec<_> = events.iter().filter(|e| !e.all_day).map(|e| (e.start_time, e.end_time)).collect();
    busy.sort();
    let mut slots = Vec::new();
    let mut cursor = day_start;
    for (s, e) in busy {
        if s > cursor && (s.min(day_end) - cursor).num_minutes() >= min_minutes {
            slots.push((cursor, s.min(day_end)));
        }
        cursor = cursor.max(e);
    }
    if day_end > cursor && (day_end - cursor).num_minutes() >= min_minutes {
        slots.push((cursor, day_end));
    }
    slots
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::NaiveDate;

    fn at(h: u32, m: u32) -> NaiveDateTime {
        NaiveDate::from_ymd_opt(2026, 1, 5).unwrap().and_hms_opt(h, m, 0).unwrap()
    }
    fn ev(s: NaiveDateTime, e: NaiveDateTime, all_day: bool) -> CalendarEvent {
        CalendarEvent {
            uid: "u".into(),
            summary: None,
            description: None,
            location: None,
            start_time: s,
            end_time: e,
            all_day,
            source: "t".into(),
            etag: None,
        }
    }

    #[test]
    fn free_slots_between_meetings() {
        let evs = [ev(at(9, 0), at(10, 0), false), ev(at(14, 0), at(15, 0), false)];
        assert_eq!(
            free_slots(&evs, at(7, 0), at(23, 0), 30),
            vec![(at(7, 0), at(9, 0)), (at(10, 0), at(14, 0)), (at(15, 0), at(23, 0))]
        );
    }

    #[test]
    fn free_slots_merge_overlaps() {
        let evs = [ev(at(9, 0), at(11, 0), false), ev(at(10, 0), at(12, 0), false)];
        assert_eq!(free_slots(&evs, at(7, 0), at(23, 0), 30), vec![(at(7, 0), at(9, 0)), (at(12, 0), at(23, 0))]);
    }

    #[test]
    fn free_slots_drop_short_gaps() {
        let evs = [ev(at(9, 0), at(10, 0), false), ev(at(10, 20), at(11, 0), false)];
        assert_eq!(free_slots(&evs, at(7, 0), at(23, 0), 30), vec![(at(7, 0), at(9, 0)), (at(11, 0), at(23, 0))]);
    }

    #[test]
    fn free_slots_ignore_all_day() {
        let evs = [ev(at(0, 0), at(23, 59), true)];
        assert_eq!(free_slots(&evs, at(7, 0), at(23, 0), 30), vec![(at(7, 0), at(23, 0))]);
    }
}
