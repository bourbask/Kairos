//! Plan de repas : les plats cuisinés sont placés, leurs portions suivantes
//! deviennent des restes sur les créneaux libres d'après, et un reste qui tombe
//! un midi de bureau devient une boîte préparée la veille au soir.

use chrono::{Datelike, NaiveDate, Weekday};

use crate::nature::Slot;
use crate::units::fold;

/// Plat cuisiné, tel que demandé.
#[derive(Debug, Clone, PartialEq)]
pub struct Cook {
    pub day: NaiveDate,
    pub slot: Slot,
    pub dish: String,
    pub portions: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MealKind {
    Cook,
    Leftover,
    Box,
}

impl MealKind {
    pub fn as_str(self) -> &'static str {
        match self {
            MealKind::Cook => "cuisine",
            MealKind::Leftover => "reste",
            MealKind::Box => "boite",
        }
    }

    pub fn parse(s: &str) -> Option<MealKind> {
        [MealKind::Cook, MealKind::Leftover, MealKind::Box].into_iter().find(|k| k.as_str() == s)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Meal {
    pub day: NaiveDate,
    pub slot: Slot,
    pub dish: String,
    pub kind: MealKind,
    /// Jour où le plat a été cuisiné.
    pub from: NaiveDate,
    pub note: Option<String>,
}

pub const BOX_NOTE: &str = "Demain bureau : mettre une portion en boîte ce soir.";

fn label(day: NaiveDate, slot: Slot) -> String {
    format!("{} {}", day.format("%d/%m"), slot.as_str())
}

/// Créneaux de la période, dans l'ordre : midi puis soir, jour après jour.
fn grid(start: NaiveDate, days: u32) -> Vec<(NaiveDate, Slot)> {
    (0..days)
        .map(|i| start + chrono::Duration::days(i64::from(i)))
        .flat_map(|d| [(d, Slot::Midi), (d, Slot::Soir)])
        .collect()
}

/// Repas de la période, dans l'ordre, et avertissements. Erreur : plat hors
/// période ou deux plats sur le même créneau (rien n'est alors retenu).
pub fn build(
    start: NaiveDate,
    days: u32,
    cooks: &[Cook],
    office: &[Weekday],
) -> Result<(Vec<Meal>, Vec<String>), String> {
    let slots = grid(start, days);
    let mut cells: Vec<Option<Meal>> = vec![None; slots.len()];
    let mut warnings = Vec::new();
    let mut placed: Vec<(usize, &Cook)> = Vec::new();
    for c in cooks {
        let i = slots
            .iter()
            .position(|&(d, s)| d == c.day && s == c.slot)
            .ok_or_else(|| format!("{} : hors de la période planifiée.", label(c.day, c.slot)))?;
        if cells[i].is_some() {
            return Err(format!("{} : deux plats sur le même créneau.", label(c.day, c.slot)));
        }
        cells[i] = Some(Meal { day: c.day, slot: c.slot, dish: c.dish.clone(), kind: MealKind::Cook, from: c.day, note: None });
        placed.push((i, c));
    }
    placed.sort_by_key(|&(i, _)| i);
    for (i, c) in placed {
        let mut left = c.portions.saturating_sub(1);
        for j in i + 1..slots.len() {
            if left == 0 {
                break;
            }
            if cells[j].is_none() {
                let (day, slot) = slots[j];
                let kind = if slot == Slot::Midi && office.contains(&day.weekday()) { MealKind::Box } else { MealKind::Leftover };
                cells[j] = Some(Meal { day, slot, dish: c.dish.clone(), kind, from: c.day, note: None });
                left -= 1;
            }
        }
        if left > 0 {
            warnings.push(format!("{} : {left} portion(s) au-delà de la période.", c.dish));
        }
    }
    // Rappel la veille au soir de chaque boîte.
    for j in 0..slots.len() {
        if cells[j].as_ref().is_some_and(|m| m.kind == MealKind::Box)
            && let Some(Some(eve)) = j.checked_sub(1).map(|p| cells[p].as_mut())
        {
            eve.note = Some(BOX_NOTE.to_string());
        }
    }
    for (j, &(day, slot)) in slots.iter().enumerate() {
        match &cells[j] {
            None => warnings.push(format!("{} : sans repas.", label(day, slot))),
            Some(m) if m.kind == MealKind::Cook && slot == Slot::Midi && office.contains(&day.weekday()) => {
                warnings.push(format!("{} : jour de bureau, prévoir une boîte plutôt qu'un plat cuisiné.", label(day, slot)))
            }
            _ => {}
        }
    }
    Ok((cells.into_iter().flatten().collect(), warnings))
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace(';', "\\;").replace(',', "\\,").replace('\n', "\\n").replace('\r', "")
}

/// Plie une ligne iCalendar à 75 octets, sans couper un caractère.
fn fold_line(line: &str) -> String {
    let mut out = String::new();
    let mut width = 0;
    for c in line.chars() {
        let n = c.len_utf8();
        if width + n > 75 {
            out.push_str("\r\n ");
            width = 1;
        }
        out.push(c);
        width += n;
    }
    out
}

/// Événement du repas : (uid, ics). Heure locale convertie en UTC (changements
/// d'heure compris).
pub fn event(m: &Meal, recipe: Option<&crate::store::Recipe>, tz: chrono_tz::Tz) -> (String, String) {
    use chrono::TimeZone;
    let (h, min, dur) = match m.slot {
        Slot::Midi => (12, 15, 45),
        _ => (19, 0, 60),
    };
    let local = m.day.and_hms_opt(h, min, 0).expect("heure valide");
    let start = tz.from_local_datetime(&local).earliest().map(|t| t.with_timezone(&chrono::Utc)).unwrap_or_else(|| local.and_utc());
    let end = start + chrono::Duration::minutes(dur);
    let uid = format!("kairos-repas-{}-{}", m.day.format("%Y%m%d"), m.slot.as_str());
    let head = if m.slot == Slot::Midi { "Midi" } else { "Soir" };
    let cooked = m.from.format("%d/%m");
    let (summary, mut desc) = match m.kind {
        MealKind::Cook => (
            format!("{head} : {}", m.dish),
            match recipe {
                Some(r) => format!(
                    "Pour {} portions.\nIngrédients : {}\n\n{}",
                    r.portions,
                    if r.ingredients.is_empty() { "—".to_string() } else { r.ingredients.join(", ") },
                    r.steps
                ),
                None => "Recette absente du carnet.".to_string(),
            },
        ),
        MealKind::Leftover => {
            (format!("{head} : {} (reste)", m.dish), format!("Reste du plat cuisiné le {cooked}. Réchauffer 3 min."))
        }
        MealKind::Box => (
            format!("{head} : {} (boîte)", m.dish),
            format!("Boîte préparée la veille au soir (plat du {cooked}) : à prendre en partant, réchauffer 2-3 min."),
        ),
    };
    if let Some(n) = &m.note {
        desc.push_str(&format!("\n\n→ {n}"));
    }
    let ts = |t: chrono::DateTime<chrono::Utc>| t.format("%Y%m%dT%H%M%SZ").to_string();
    let lines = [
        "BEGIN:VCALENDAR".to_string(),
        "VERSION:2.0".into(),
        "PRODID:-//kairos//repas//FR".into(),
        "BEGIN:VEVENT".into(),
        format!("UID:{uid}"),
        format!("DTSTAMP:{}", ts(start)),
        format!("DTSTART:{}", ts(start)),
        format!("DTEND:{}", ts(end)),
        fold_line(&format!("SUMMARY:{}", escape(&summary))),
        fold_line(&format!("DESCRIPTION:{}", escape(&desc))),
        "CATEGORIES:Repas".into(),
        "END:VEVENT".into(),
        "END:VCALENDAR".into(),
    ];
    (uid, lines.join("\r\n") + "\r\n")
}

/// Jours cités dans une préférence libre (« lundi, jeudi »). Illisible : aucun.
pub fn office_days(pref: &str) -> Vec<Weekday> {
    const NAMES: [(&str, Weekday); 7] = [
        ("lundi", Weekday::Mon),
        ("mardi", Weekday::Tue),
        ("mercredi", Weekday::Wed),
        ("jeudi", Weekday::Thu),
        ("vendredi", Weekday::Fri),
        ("samedi", Weekday::Sat),
        ("dimanche", Weekday::Sun),
    ];
    let words: Vec<String> = fold(pref).split(|c: char| !c.is_alphabetic()).map(str::to_string).collect();
    NAMES.iter().filter(|(n, _)| words.iter().any(|w| w == n)).map(|&(_, d)| d).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{NaiveDate, Weekday};

    fn d(day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 11, day).unwrap()
    }

    fn cook(day: u32, slot: Slot, dish: &str, portions: u32) -> Cook {
        Cook { day: d(day), slot, dish: dish.into(), portions }
    }

    #[test]
    fn build_fills_leftovers_in_order() {
        // 2026-11-07 = samedi ; soupe ×4 samedi soir → dim. midi, dim. soir, lun. midi.
        let (meals, _) = build(d(7), 3, &[cook(7, Slot::Soir, "Soupe", 4)], &[]).unwrap();
        let got: Vec<_> = meals.iter().map(|m| (m.day, m.slot, m.kind)).collect();
        assert_eq!(
            got,
            [
                (d(7), Slot::Soir, MealKind::Cook),
                (d(8), Slot::Midi, MealKind::Leftover),
                (d(8), Slot::Soir, MealKind::Leftover),
                (d(9), Slot::Midi, MealKind::Leftover),
            ]
        );
        assert!(meals.iter().all(|m| m.dish == "Soupe" && m.from == d(7)));
    }

    #[test]
    fn build_turns_office_lunch_into_box() {
        // Dimanche soir ×2, lundi au bureau : boîte lundi midi, rappel dimanche soir.
        let (meals, warnings) =
            build(d(8), 2, &[cook(8, Slot::Soir, "Farfalle", 2), cook(9, Slot::Soir, "Riz", 1)], &[Weekday::Mon])
                .unwrap();
        let lunch = meals.iter().find(|m| m.day == d(9) && m.slot == Slot::Midi).unwrap();
        assert_eq!(lunch.kind, MealKind::Box);
        let eve = meals.iter().find(|m| m.day == d(8) && m.slot == Slot::Soir).unwrap();
        assert!(eve.note.as_deref().unwrap().contains("boîte"));
        assert!(warnings.iter().any(|w| w.contains("08/11 midi")), "{warnings:?}");
    }

    #[test]
    fn build_rejects_double_booking() {
        let err = build(d(7), 2, &[cook(7, Slot::Soir, "A", 1), cook(7, Slot::Soir, "B", 1)], &[]).unwrap_err();
        assert!(err.contains("07/11"), "{err}");
        assert!(build(d(7), 2, &[cook(12, Slot::Soir, "A", 1)], &[]).is_err(), "hors période");
    }

    #[test]
    fn build_warns_overflow_and_cooking_at_the_office() {
        let (meals, warnings) =
            build(d(9), 1, &[cook(9, Slot::Midi, "Gratin", 1), cook(9, Slot::Soir, "Dhal", 4)], &[Weekday::Mon])
                .unwrap();
        assert_eq!(meals.len(), 2);
        assert!(warnings.iter().any(|w| w.contains("Dhal") && w.contains("3")), "{warnings:?}");
        assert!(warnings.iter().any(|w| w.contains("bureau")), "{warnings:?}");
    }

    #[test]
    fn event_times_follow_local_time() {
        let tz = chrono_tz::Europe::Paris;
        let m = |day: NaiveDate, kind| Meal { day, slot: Slot::Midi, dish: "Dhal".into(), kind, from: day, note: None };
        let (uid, ics) = event(&m(d(9), MealKind::Box), None, tz);
        assert_eq!(uid, "kairos-repas-20261109-midi");
        assert!(ics.contains("DTSTART:20261109T111500Z"), "{ics}");
        assert!(ics.contains("SUMMARY:Midi : Dhal (boîte)"), "{ics}");
        let summer = NaiveDate::from_ymd_opt(2026, 7, 6).unwrap();
        assert!(event(&m(summer, MealKind::Cook), None, tz).1.contains("DTSTART:20260706T101500Z"));
        let r = crate::store::Recipe { title: "Dhal".into(), portions: 4, ingredients: vec!["lentilles".into()], steps: "1. Cuire, puis servir.".into() };
        let ics = event(&m(d(9), MealKind::Cook), Some(&r), tz).1;
        assert!(ics.replace("\r\n ", "").contains("1. Cuire\\, puis servir."), "{ics}");
        assert!(ics.lines().all(|l| l.len() <= 75), "lignes pliées");
    }

    #[test]
    fn office_days_parse() {
        assert_eq!(office_days("lundi, jeudi"), [Weekday::Mon, Weekday::Thu]);
        assert_eq!(office_days("Mardi et VENDREDI"), [Weekday::Tue, Weekday::Fri]);
        assert!(office_days("").is_empty());
        assert!(office_days("parfois").is_empty());
    }
}
