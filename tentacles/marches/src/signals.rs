//! Calculs purs de variation et de bascule (aucune E/S, aucune horloge).

use crate::store::Scenario;
use chrono::NaiveDate;

#[derive(Debug, Clone)]
pub struct Settings {
    pub threshold: f64,
    pub scenario_threshold: f64,
    pub window_hours: i64,
    pub min_volume: f64,
    pub weekly_budget: u32,
    pub stale_hours: i64,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            threshold: 0.10,
            scenario_threshold: 0.06,
            window_hours: 24,
            min_volume: 100_000.0,
            weekly_budget: 4,
            stale_hours: 6,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Move {
    pub market: String,
    pub outcome: String,
    pub title: String,
    pub from: f64,
    pub to: f64,
    pub volume: f64,
}

impl Move {
    pub fn delta(&self) -> f64 {
        self.to - self.from
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct AlertOut {
    pub id: String,
    pub text: String,
    pub counts_budget: bool,
}

/// Comparaison en points entiers : insensible au bruit des flottants (0.90 - 0.80 < 0.10).
pub fn reaches(d: f64, threshold: f64) -> bool {
    (d.abs() * 100.0).round() >= (threshold * 100.0).round()
}

fn dir(d: f64) -> char {
    if d >= 0.0 { '+' } else { '-' }
}

/// La clé inclut le palier de probabilité : un même mouvement ne réalerte pas.
pub fn single_alerts(moves: &[Move], s: &Settings) -> Vec<(String, Move)> {
    moves
        .iter()
        .filter(|m| reaches(m.delta(), s.threshold) && m.volume >= s.min_volume)
        .map(|m| {
            let key = format!(
                "{}:{}:{}:{}",
                m.market,
                m.outcome,
                dir(m.delta()),
                (m.to * 10.0).round() as i64
            );
            (key, m.clone())
        })
        .collect()
}

/// `today` fournit la date de la clé (une alerte de scénario par jour et par sens).
pub fn scenario_alerts(
    sc: &Scenario,
    moves: &[Move],
    s: &Settings,
    today: NaiveDate,
) -> Option<(String, Vec<Move>)> {
    let hits: Vec<(f64, &Move)> = sc
        .links
        .iter()
        .filter_map(|l| {
            let m = moves
                .iter()
                .find(|m| m.market == l.market && m.outcome == l.outcome)?;
            let eff = f64::from(l.sign) * m.delta();
            reaches(eff, s.scenario_threshold).then_some((eff, m))
        })
        .collect();
    if hits.len() < 2 {
        return None;
    }
    let d = dir(hits[0].0);
    if hits.iter().any(|(e, _)| dir(*e) != d) {
        return None;
    }
    let key = format!("sc:{}:{}:{}", sc.name, d, today);
    Some((key, hits.into_iter().map(|(_, m)| m.clone()).collect()))
}

pub fn pct(p: f64) -> String {
    format!("{} %", (p * 100.0).round() as i64)
}

pub fn pts(d: f64) -> String {
    let n = (d * 100.0).round() as i64;
    if n < 0 {
        format!("\u{2212}{} pts", -n)
    } else {
        format!("+{} pts", n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::Link;

    fn mv(market: &str, from: f64, to: f64, volume: f64) -> Move {
        Move {
            market: market.into(),
            outcome: "Yes".into(),
            title: "t".into(),
            from,
            to,
            volume,
        }
    }

    fn sc() -> Scenario {
        Scenario {
            name: "crise".into(),
            links: vec![
                Link { market: "esc".into(), outcome: "Yes".into(), sign: 1 },
                Link { market: "cf".into(), outcome: "Yes".into(), sign: -1 },
            ],
        }
    }

    fn day() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 11, 6).unwrap()
    }

    #[test]
    fn single_alert_needs_threshold_and_volume() {
        let s = Settings::default();
        assert_eq!(single_alerts(&[mv("a", 0.30, 0.42, 2e5)], &s).len(), 1);
        assert_eq!(single_alerts(&[mv("a", 0.30, 0.38, 2e5)], &s).len(), 0);
        assert_eq!(single_alerts(&[mv("a", 0.30, 0.45, 5e4)], &s).len(), 0);
    }

    #[test]
    fn same_move_same_key() {
        let s = Settings::default();
        let a = single_alerts(&[mv("a", 0.30, 0.42, 2e5)], &s);
        let b = single_alerts(&[mv("a", 0.30, 0.43, 2e5)], &s);
        assert_eq!(a[0].0, b[0].0);
    }

    #[test]
    fn scenario_respects_sign() {
        let s = Settings::default();
        let same = [mv("esc", 0.10, 0.18, 1e6), mv("cf", 0.50, 0.40, 1e6)];
        let (key, ms) = scenario_alerts(&sc(), &same, &s, day()).unwrap();
        assert_eq!(key, "sc:crise:+:2026-11-06");
        assert_eq!(ms.len(), 2);
        let opp = [mv("esc", 0.10, 0.18, 1e6), mv("cf", 0.40, 0.50, 1e6)];
        assert!(scenario_alerts(&sc(), &opp, &s, day()).is_none());
    }

    #[test]
    fn scenario_needs_two_links() {
        let s = Settings::default();
        let one = [mv("esc", 0.10, 0.18, 1e6), mv("cf", 0.50, 0.49, 1e6)];
        assert!(scenario_alerts(&sc(), &one, &s, day()).is_none());
    }

    #[test]
    fn thresholds_compared_in_rounded_points() {
        let s = Settings::default();
        // 0.90 - 0.80 vaut un peu moins de 0.10 en flottant.
        assert_eq!(single_alerts(&[mv("a", 0.80, 0.90, 2e5)], &s).len(), 1);
        assert_eq!(single_alerts(&[mv("a", 0.80, 0.89, 2e5)], &s).len(), 0);
        // 0.52 - 0.58 vaut un peu moins de 6 points en valeur absolue.
        let six = [mv("esc", 0.58, 0.52, 1e6), mv("cf", 0.40, 0.46, 1e6)];
        assert!(scenario_alerts(&sc(), &six, &s, day()).is_some());
        let five = [mv("esc", 0.58, 0.53, 1e6), mv("cf", 0.40, 0.46, 1e6)];
        assert!(scenario_alerts(&sc(), &five, &s, day()).is_none());
    }

    #[test]
    fn formats() {
        assert_eq!(pct(0.375), "38 %");
        assert_eq!(pts(0.12), "+12 pts");
        assert_eq!(pts(-0.05), "\u{2212}5 pts");
    }
}
