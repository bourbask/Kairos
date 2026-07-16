use crate::config::Profile;
use crate::models::{JobOffer, ScoredJob, ScoreBreakdown};

#[derive(Clone)]
pub struct Matcher {
    profile: Profile,
}

impl Matcher {
    pub fn new(profile: Profile) -> Self {
        Self { profile }
    }

    /// Seuil de pertinence minimal (préférence profil, défaut 0.60), tunable sans rebuild.
    pub fn min_score(&self) -> f64 {
        self.profile.preferences.min_score
    }

    /// Filtre négatif : entreprise blacklistée (sous-chaîne du nom) ou mot-clé
    /// secteur indésirable dans le titre/description. Insensible à la casse.
    /// Les entrées vides sont ignorées (sinon `""` exclurait toutes les offres).
    pub fn is_blacklisted(&self, offer: &JobOffer) -> bool {
        let f = &self.profile.filters;
        let company = offer.company.to_lowercase();
        if f.blacklist_companies.iter()
            .any(|c| !c.is_empty() && company.contains(&c.to_lowercase()))
        {
            return true;
        }
        if !f.blacklist_keywords.is_empty() {
            let hay = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
            if f.blacklist_keywords.iter()
                .any(|k| !k.is_empty() && hay.contains(&k.to_lowercase()))
            {
                return true;
            }
        }
        false
    }

    pub fn score_with_breakdown(&self, offer: &JobOffer) -> (f64, ScoreBreakdown) {
        let remote_gate_ok = match self.profile.preferences.remote.as_str() {
            "full" => is_remote(offer),
            _ => true, // "on-site"/"hybrid" : pas de contrainte remote pour ce profil
        };
        if is_freelance(offer) || !remote_gate_ok || !self.location_gate_ok(offer) {
            return (0.0, ScoreBreakdown { skills: 0.0, remote: 0.0, salary: 0.0, location: 0.0 });
        }

        const BASELINE: f64 = 0.40;
        let skills = self.skill_bonus(offer);
        let location = self.country_bonus(offer);
        let salary = self.salary_adjustment(offer);

        let total = (BASELINE + skills + location + salary).clamp(0.0, 1.0);

        (total, ScoreBreakdown { skills, remote: 1.0, salary, location })
    }

    /// Gate localisation (opt-in) : vide = aucune contrainte (profil remote international) ;
    /// non vide = l'offre doit matcher au moins un mot-clé (ville/région/département), sinon
    /// exclue — pour un profil régional non-remote.
    fn location_gate_ok(&self, offer: &JobOffer) -> bool {
        let keywords = &self.profile.preferences.location_keywords;
        if keywords.is_empty() {
            return true;
        }
        let haystack = format!(
            "{} {} {}",
            offer.location.as_deref().unwrap_or("").to_lowercase(),
            offer.title.to_lowercase(),
            offer.description.to_lowercase(),
        );
        keywords.iter().any(|k| haystack.contains(&k.to_lowercase()))
    }

    pub fn rank(&self, offers: Vec<JobOffer>) -> Vec<ScoredJob> {
        let mut scored: Vec<ScoredJob> = offers
            .into_iter()
            .map(|offer| {
                let (score, breakdown) = self.score_with_breakdown(&offer);
                ScoredJob { offer, score, breakdown }
            })
            .collect();

        scored.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        scored
    }

    /// Bonus compétences : matched / min(profile_skills.len(), 6), plafonné à 1.0, *0.30.
    /// Plafond à 6 quelle que soit la taille du profil (25 en prod aujourd'hui) — une offre
    /// qui matche 6 compétences du profil ou plus obtient le bonus plein, au lieu de diluer
    /// sur la totalité de la liste.
    fn skill_bonus(&self, offer: &JobOffer) -> f64 {
        let haystack = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
        let profile_skills = self.profile.skills.all();

        if profile_skills.is_empty() {
            return 0.15; // neutre (mi-chemin du bonus max) si le profil ne déclare aucune compétence
        }

        let matched = profile_skills
            .iter()
            .filter(|skill| haystack.contains(&skill.to_lowercase()))
            .count();
        let denom = profile_skills.len().min(6) as f64;
        (matched as f64 / denom).min(1.0) * 0.30
    }

    /// Bonus pays préféré : jamais de malus, juste +0.10 si le pays de l'offre est dans la liste.
    fn country_bonus(&self, offer: &JobOffer) -> f64 {
        match &offer.country {
            Some(country) if self.profile.preferences.countries.iter().any(|c| c == country) => 0.10,
            _ => 0.0,
        }
    }

    /// Ajustement salaire relatif au profil (preferences.salary_min/salary_target) : cible →
    /// +0.10, mi-chemin min/cible → +0.05, min → 0.0, en dessous du min → -0.20, inconnu → 0.0.
    /// Jamais de malus pour absence de donnée — seulement pour un salaire connu et sous le min.
    fn salary_adjustment(&self, offer: &JobOffer) -> f64 {
        let offered = offer.salary_max.or(offer.salary_min);
        let min = self.profile.preferences.salary_min as f64;
        let target = self.profile.preferences.salary_target as f64;
        let mid = min + (target - min) / 2.0;

        match offered.map(|s| s as f64) {
            Some(s) if s >= target => 0.10,
            Some(s) if s >= mid => 0.05,
            Some(s) if s >= min => 0.0,
            Some(_) => -0.20,
            None => 0.0,
        }
    }
}

/// Détecte une offre freelance/contract (l'utilisateur veut un CDI, sans statut freelance).
/// Heuristique par mots-clés sur titre + description. Rapatriée depuis ranker.rs (K-F0x scoring rework).
// ponytail: schéma-libre ; migrer vers un champ employment_type structuré si l'imprécision gêne.
fn is_freelance(offer: &JobOffer) -> bool {
    let h = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
    const MARKERS: [&str; 7] = [
        "freelance", "freelancer", "contractor", "c2c", "corp-to-corp", "corp to corp", "self-employed",
    ];
    MARKERS.iter().any(|m| h.contains(m))
}

/// Full remote DUR (exigence utilisateur) : gate, pas un score. Flag explicite s'il existe ;
/// sinon mot-clé remote dans le texte ET pas d'« hybride ». Rapatriée depuis ranker.rs.
/// Strict par choix : mieux vaut rater une offre non taggée que polluer avec de l'on-site.
fn is_remote(offer: &JobOffer) -> bool {
    match offer.remote {
        Some(v) => v,
        None => {
            let h = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
            let hybride = h.contains("hybrid") || h.contains("hybride");
            let remote = h.contains("remote") || h.contains("télétravail")
                || h.contains("teletravail") || h.contains("home office") || h.contains("work from home");
            remote && !hybride
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Profile;
    use chrono::Utc;

    // config/profile.example.toml skills: Node.js, Python, React, Vue.js, PostgreSQL, SQLite, Docker (7 total)
    fn test_profile() -> Profile {
        Profile::from_file("config/profile.example.toml").unwrap()
    }

    fn make_offer(title: &str, desc: &str, remote: Option<bool>, country: Option<&str>,
                  sal_min: Option<u32>, sal_max: Option<u32>) -> JobOffer {
        JobOffer {
            id: "test".into(),
            source: "test".into(),
            title: title.into(),
            company: "Test".into(),
            description: desc.into(),
            url: "https://test.com".into(),
            location: None,
            country: country.map(String::from),
            salary_min: sal_min,
            salary_max: sal_max,
            currency: Some("EUR".into()),
            remote,
            published_at: None,
            collected_at: Utc::now(),
        }
    }

    #[test]
    fn offre_realiste_depasse_le_seuil() {
        let matcher = Matcher::new(test_profile());
        // 6/7 compétences du profil (pas SQLite) + remote confirmé + CDI + salaire >=48k + pays préféré
        let offer = make_offer(
            "Senior Full-Stack Developer",
            "Node.js, Python, React, Vue.js, PostgreSQL, Docker. Permanent position, fully remote.",
            Some(true), Some("FR"), None, Some(55000),
        );
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert!((score - 0.90).abs() < 1e-9, "attendu 0.90 (0.40+0.30+0.10+0.10), obtenu {score}");
        assert_eq!(breakdown.remote, 1.0);
    }

    #[test]
    fn gate_remote_echoue_score_nul() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Dev", "On-site position in the Paris office", Some(false), Some("FR"), None, Some(60000));
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert_eq!(score, 0.0);
        assert_eq!(breakdown.remote, 0.0);
    }

    #[test]
    fn gate_freelance_echoue_score_nul() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Freelance React Developer", "Fully remote contractor role", Some(true), Some("FR"), None, Some(60000));
        let (score, _) = matcher.score_with_breakdown(&offer);
        assert_eq!(score, 0.0);
    }

    #[test]
    fn salaire_quatre_paliers_relatifs_au_profil() {
        // profile.example.toml : salary_min=35000, salary_target=50000, mid=42500
        let matcher = Matcher::new(test_profile());
        let bonus = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(55000));
        let mi_chemin = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(45000));
        let neutre = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(38000));
        let malus = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(30000));

        let (s_bonus, _) = matcher.score_with_breakdown(&bonus);
        let (s_mi, _) = matcher.score_with_breakdown(&mi_chemin);
        let (s_neutre, _) = matcher.score_with_breakdown(&neutre);
        let (s_malus, _) = matcher.score_with_breakdown(&malus);

        assert!((s_bonus - 0.50).abs() < 1e-9, "0.40+0.10 (>= cible 50000), obtenu {s_bonus}");
        assert!((s_mi - 0.45).abs() < 1e-9, "0.40+0.05 (>= mi-chemin 42500), obtenu {s_mi}");
        assert!((s_neutre - 0.40).abs() < 1e-9, "0.40 neutre (>= min 35000, < mi-chemin), obtenu {s_neutre}");
        assert!((s_malus - 0.20).abs() < 1e-9, "0.40-0.20 malus (< min 35000), obtenu {s_malus}");
    }

    #[test]
    fn remote_gate_desactive_si_profil_onsite() {
        let mut profile = test_profile();
        profile.preferences.remote = "on-site".to_string();
        let matcher = Matcher::new(profile);
        // Offre clairement non-remote : passe quand même, le gate remote est sauté pour ce profil.
        let offer = make_offer("Dev", "On-site position in the office", Some(false), None, None, None);
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert!(score > 0.0, "gate remote sauté pour un profil on-site, obtenu {score}");
        assert_eq!(breakdown.remote, 1.0);
    }

    #[test]
    fn location_gate_vide_ne_bloque_rien() {
        let matcher = Matcher::new(test_profile()); // location_keywords vide par défaut (profile.example.toml ne le déclare pas)
        let offer = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, None);
        let (score, _) = matcher.score_with_breakdown(&offer);
        assert!(score > 0.0);
    }

    #[test]
    fn location_gate_exclut_hors_zone() {
        let mut profile = test_profile();
        profile.preferences.location_keywords = vec!["Toulouse".into()];
        let matcher = Matcher::new(profile);
        let hors_zone = make_offer("Dev", "Poste basé à Lyon", Some(true), None, None, None);
        let (score, _) = matcher.score_with_breakdown(&hors_zone);
        assert_eq!(score, 0.0);
    }

    #[test]
    fn location_gate_accepte_zone_matchee() {
        let mut profile = test_profile();
        profile.preferences.location_keywords = vec!["Toulouse".into()];
        let matcher = Matcher::new(profile);
        let dans_zone = make_offer("Dev", "Poste basé à Toulouse", Some(true), None, None, None);
        let (score, _) = matcher.score_with_breakdown(&dans_zone);
        assert!(score > 0.0);
    }

    #[test]
    fn salaire_inconnu_neutre() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, None);
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert_eq!(breakdown.salary, 0.0);
        assert!((score - 0.40).abs() < 1e-9);
    }

    #[test]
    fn pays_hors_liste_pas_de_malus() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Dev", "Permanent position, fully remote", Some(true), Some("JP"), None, None);
        let (_, breakdown) = matcher.score_with_breakdown(&offer);
        assert_eq!(breakdown.location, 0.0);
    }

    #[test]
    fn blacklist_entreprise_et_mot_cle() {
        let mut profile = test_profile();
        profile.filters.blacklist_companies = vec!["EvilCorp".into()];
        profile.filters.blacklist_keywords = vec!["gambling".into()];
        let matcher = Matcher::new(profile);

        let mut o = make_offer("Dev", "great job", Some(true), Some("CH"), None, None);
        assert!(!matcher.is_blacklisted(&o), "rien ne matche");
        o.company = "EvilCorp GmbH".into();
        assert!(matcher.is_blacklisted(&o), "entreprise (sous-chaîne, casse)");
        o.company = "Nice Co".into();
        o.description = "online GAMBLING platform".into();
        assert!(matcher.is_blacklisted(&o), "mot-clé secteur");
    }

    #[test]
    fn blacklist_vide_nexclut_rien() {
        let mut profile = test_profile();
        profile.filters.blacklist_companies = vec!["".into()];
        let matcher = Matcher::new(profile);
        let o = make_offer("Dev", "x", Some(true), Some("CH"), None, None);
        assert!(!matcher.is_blacklisted(&o));
    }

    #[test]
    fn test_ranking_order() {
        let matcher = Matcher::new(test_profile());
        let good = make_offer("Symfony React", "Symfony React Docker", Some(true), Some("CH"), None, Some(110000));
        let bad = make_offer("Cleaner", "Cleaning, on-site only", Some(false), Some("JP"), None, None);

        let ranked = matcher.rank(vec![bad, good]);
        assert_eq!(ranked.len(), 2);
        assert!(ranked[0].score > ranked[1].score);
    }
}
