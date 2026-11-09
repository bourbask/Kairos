//! Formules pures de force des souvenirs : stabilité, récupérabilité, renforcement.

use chrono::NaiveDateTime;

pub const S_MAX: f64 = 3650.0;
pub const K: f64 = 2.0;
pub const SEUIL_ACTIF: f64 = 0.5;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Famille {
    Episode,
    Habitude,
    Connaissance,
    Synthese,
}

impl Famille {
    /// Stabilité de base, en jours.
    pub fn s0(self) -> f64 {
        match self {
            Famille::Episode => 3.0,
            Famille::Habitude => 14.0,
            Famille::Connaissance => 30.0,
            Famille::Synthese => 60.0,
        }
    }
}

pub fn stabilite_initiale(f: Famille, importance: f64) -> f64 {
    (f.s0() * (1.0 + 4.0 * importance.clamp(0.0, 1.0))).min(S_MAX)
}

/// Probabilité de rappel : décroissance exponentielle depuis le dernier rappel.
pub fn recuperabilite(
    dernier_rappel: NaiveDateTime,
    stabilite: f64,
    maintenant: NaiveDateTime,
    ancre: bool,
) -> f64 {
    if ancre {
        return 1.0;
    }
    let jours = (maintenant - dernier_rappel).num_milliseconds() as f64 / 86_400_000.0;
    if jours <= 0.0 {
        return 1.0;
    }
    (-jours / stabilite).exp()
}

/// Effet d'espacement : plus le souvenir était oublié, plus le rappel le consolide.
pub fn renforcer(stabilite: f64, r: f64) -> f64 {
    (stabilite * (1.0 + K * (1.0 - r))).min(S_MAX)
}

pub fn est_actif(r: f64) -> bool {
    r >= SEUIL_ACTIF
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::Duration;

    fn dt(s: &str) -> NaiveDateTime {
        NaiveDateTime::parse_from_str(s, "%Y-%m-%d %H:%M:%S").unwrap()
    }
    fn days(n: i64) -> Duration {
        Duration::days(n)
    }
    fn hours(n: i64) -> Duration {
        Duration::hours(n)
    }

    #[test]
    fn initial_stability_by_family() {
        assert_eq!(stabilite_initiale(Famille::Episode, 0.0), 3.0);
        assert_eq!(stabilite_initiale(Famille::Connaissance, 0.5), 90.0);
        assert_eq!(stabilite_initiale(Famille::Connaissance, 7.0), 150.0); // borné à 1
    }
    #[test]
    fn retrievability_decays_and_anchor_stays() {
        let t0 = dt("2026-01-01 00:00:00");
        assert!((recuperabilite(t0, 3.0, t0 + days(3), false) - (-1f64).exp()).abs() < 1e-9);
        assert_eq!(recuperabilite(t0, 3.0, t0 + days(400), true), 1.0);
        assert_eq!(recuperabilite(t0 + days(1), 3.0, t0, false), 1.0);
    }
    #[test]
    fn faded_episode_after_about_two_days() {
        let t0 = dt("2026-01-01 00:00:00");
        assert!(est_actif(recuperabilite(t0, 3.0, t0 + hours(49), false)));
        assert!(!est_actif(recuperabilite(t0, 3.0, t0 + hours(51), false)));
    }
    #[test]
    fn spacing_effect_and_cap() {
        assert_eq!(renforcer(10.0, 1.0), 10.0); // relu à chaud : rien
        assert!((renforcer(90.0, 0.72) - 140.4).abs() < 1e-9);
        assert_eq!(renforcer(3000.0, 0.0), S_MAX);
    }
}
