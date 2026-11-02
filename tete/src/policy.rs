//! Politique de canal : garde-fou codé décidant du sort d'une sortie spontanée.

use chrono::{NaiveDateTime, NaiveTime};
use serde::Deserialize;

use crate::config::{Schedule, WorkMode, WorkRhythm};
use crate::state::QuietScope;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outlet {
    Written,
    Hold,
    Drop,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Spontaneous {
    Brief,
    Suggestion,
    Flush,
    /// Rapport écrit d'un tentacule : comme un brief, sans abandon par la sourdine des briefs.
    Report,
}

/// Signal d'occupation renvoyé par un outil conventionnel.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub struct Busy {
    pub busy: bool,
    pub work: bool,
}

/// Premier résultat exploitable ; aucun → signal inconnu.
pub fn busy_from_tentacles(results: &[(String, Result<String, String>)]) -> Option<Busy> {
    results
        .iter()
        .filter_map(|(_, r)| r.as_ref().ok())
        .find_map(|txt| serde_json::from_str(txt).ok())
}

pub fn decide(
    kind: Spontaneous,
    at: NaiveDateTime,
    mode: WorkMode,
    work: &WorkRhythm,
    quiet: &Schedule,
    busy: Option<Busy>,
    mute: Option<QuietScope>,
) -> Outlet {
    match mute {
        Some(QuietScope::All) => return Outlet::Hold,
        Some(QuietScope::Briefs) if kind == Spontaneous::Brief => return Outlet::Drop,
        _ => {}
    }
    let now = at.time();
    // La plage de nuit peut chevaucher minuit.
    let night = if quiet.quiet_start <= quiet.quiet_end {
        in_range(now, quiet.quiet_start, quiet.quiet_end)
    } else {
        now >= quiet.quiet_start || now < quiet.quiet_end
    };
    if night {
        return Outlet::Hold;
    }
    let held = if in_range(now, work.start, work.end) {
        match mode {
            WorkMode::OnSite => true,
            // Signal inconnu : on ne prend pas le risque d'interrompre.
            WorkMode::Remote => busy.is_none_or(|b| b.busy),
            WorkMode::Off => busy.is_some_and(|b| b.busy && b.work),
        }
    } else {
        busy.is_some_and(|b| b.busy && b.work)
    };
    if held { Outlet::Hold } else { Outlet::Written }
}

fn in_range(t: NaiveTime, start: NaiveTime, end: NaiveTime) -> bool {
    t >= start && t < end
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{NaiveDate, Weekday};
    use std::collections::HashMap;

    fn t(h: u32, m: u32) -> NaiveTime {
        NaiveTime::from_hms_opt(h, m, 0).unwrap()
    }
    fn at(d: u32, h: u32, m: u32) -> NaiveDateTime {
        NaiveDate::from_ymd_opt(2026, 11, d).unwrap().and_time(t(h, m))
    }
    fn rhythm() -> WorkRhythm {
        WorkRhythm {
            start: t(9, 0),
            end: t(17, 30),
            days: HashMap::from([(Weekday::Mon, WorkMode::OnSite), (Weekday::Tue, WorkMode::Remote)]),
        }
    }
    fn sched() -> Schedule {
        Schedule { quiet_start: t(22, 30), quiet_end: t(7, 0), ..Schedule::default() }
    }
    const FREE: Option<Busy> = Some(Busy { busy: false, work: true });
    const MEETING: Option<Busy> = Some(Busy { busy: true, work: true });

    #[test]
    fn decisions() {
        use Outlet::*;
        use Spontaneous::*;
        use WorkMode::*;
        // 2026-11-02 lundi, 03 mardi, 07 samedi
        let cases = [
            ("lundi sur site", Brief, at(2, 10, 0), OnSite, FREE, None, Hold),
            ("mardi réunion", Brief, at(3, 10, 0), Remote, MEETING, None, Hold),
            ("mardi libre", Brief, at(3, 10, 0), Remote, FREE, None, Written),
            ("mardi inconnu", Brief, at(3, 10, 0), Remote, None, None, Hold),
            ("samedi", Brief, at(7, 15, 0), Off, None, None, Written),
            ("samedi réunion pro", Brief, at(7, 15, 0), Off, MEETING, None, Hold),
            ("nuit 23:30", Brief, at(3, 23, 30), Remote, FREE, None, Hold),
            ("06:59", Brief, at(3, 6, 59), Off, FREE, None, Hold),
            ("07:00", Brief, at(3, 7, 0), Off, FREE, None, Written),
            ("fin exclusive", Brief, at(2, 17, 30), OnSite, FREE, None, Written),
            ("sourdine tout", Brief, at(7, 15, 0), Off, FREE, Some(QuietScope::All), Hold),
            ("sourdine briefs", Brief, at(7, 15, 0), Off, FREE, Some(QuietScope::Briefs), Drop),
            ("sourdine briefs, suggestion", Suggestion, at(7, 15, 0), Off, FREE, Some(QuietScope::Briefs), Written),
            ("flush", Flush, at(7, 15, 0), Off, FREE, None, Written),
            ("sourdine briefs, rapport", Report, at(7, 15, 0), Off, FREE, Some(QuietScope::Briefs), Written),
            ("sourdine briefs, rapport sur site", Report, at(2, 10, 0), OnSite, FREE, Some(QuietScope::Briefs), Hold),
            ("rapport nuit", Report, at(3, 23, 30), Remote, FREE, None, Hold),
            ("rapport sourdine tout", Report, at(7, 15, 0), Off, FREE, Some(QuietScope::All), Hold),
        ];
        for (name, kind, when, mode, busy, mute, want) in cases {
            assert_eq!(decide(kind, when, mode, &rhythm(), &sched(), busy, mute), want, "{name}");
        }
    }

    #[test]
    fn busy_premier_resultat_valide() {
        let r = vec![
            ("a".to_string(), Err("hors ligne".to_string())),
            ("b".to_string(), Ok("pas du json".to_string())),
            ("c".to_string(), Ok(r#"{"busy":true,"work":true}"#.to_string())),
        ];
        assert_eq!(busy_from_tentacles(&r), Some(Busy { busy: true, work: true }));
        assert_eq!(busy_from_tentacles(&r[..2]), None);
    }
}
