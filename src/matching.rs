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

    /// Score a single offer against the profile.
    /// Weights: skills 40%, remote 25%, salary 20%, location 15%.
    pub fn score(&self, offer: &JobOffer) -> f64 {
        let skills = self.score_skills(offer) * 0.40;
        let remote = self.score_remote(offer) * 0.25;
        let salary = self.score_salary(offer) * 0.20;
        let location = self.score_location(offer) * 0.15;
        skills + remote + salary + location
    }

    pub fn score_with_breakdown(&self, offer: &JobOffer) -> (f64, ScoreBreakdown) {
        let s_skills = self.score_skills(offer);
        let s_remote = self.score_remote(offer);
        let s_salary = self.score_salary(offer);
        let s_location = self.score_location(offer);

        let total = s_skills * 0.40 + s_remote * 0.25 + s_salary * 0.20 + s_location * 0.15;

        (total, ScoreBreakdown {
            skills: s_skills,
            remote: s_remote,
            salary: s_salary,
            location: s_location,
        })
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

    /// Skills score: ratio of matched skills found in title + description.
    fn score_skills(&self, offer: &JobOffer) -> f64 {
        let haystack = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
        let profile_skills = self.profile.skills.all();

        if profile_skills.is_empty() {
            return 0.5;
        }

        let matched = profile_skills
            .iter()
            .filter(|skill| haystack.contains(&skill.to_lowercase()))
            .count();

        (matched as f64 / profile_skills.len() as f64).min(1.0)
    }

    /// Remote score: 1.0 if full remote, 0.5 if hybrid/partial, 0.0 if on-site.
    fn score_remote(&self, offer: &JobOffer) -> f64 {
        match offer.remote {
            Some(true) => 1.0,
            Some(false) => 0.0,
            None => {
                let haystack = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
                if haystack.contains("remote") || haystack.contains("télétravail") || haystack.contains("home office") {
                    0.8
                } else if haystack.contains("hybrid") || haystack.contains("hybride") {
                    0.5
                } else {
                    0.3
                }
            }
        }
    }

    /// Salary score: 1.0 if max >= target, 0.0 if max < min, linear ramp in between.
    fn score_salary(&self, offer: &JobOffer) -> f64 {
        let target = self.profile.preferences.salary_target as f64;
        let minimum = self.profile.preferences.salary_min as f64;

        let offered = offer.salary_max.map(|s| s as f64).or(offer.salary_min.map(|s| s as f64));

        match offered {
            Some(sal) if sal >= target => 1.0,
            Some(sal) if sal <= minimum => 0.0,
            Some(sal) => ((sal - minimum) / (target - minimum)).max(0.0),
            None => 0.5,
        }
    }

    /// Location score: 1.0 if country is in preference list, 0.5 if adjacent, 0.0 otherwise.
    fn score_location(&self, offer: &JobOffer) -> f64 {
        let Some(ref country) = offer.country else {
            return 0.3;
        };

        if self.profile.preferences.countries.iter().any(|c| c == country) {
            1.0
        } else {
            let adjacent = ["FR", "IT", "ES", "GB", "DK", "SE", "PL", "CZ"];
            if adjacent.contains(&country.as_str()) {
                0.3
            } else {
                0.0
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Profile;
    use chrono::Utc;

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
    fn test_perfect_match() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer(
            "Senior Full-Stack Symfony React Developer",
            "We are looking for Symfony, React, Express, Rust, Docker, MySQL, Redis, PostgreSQL, AWS, CI/CD, Tailwind, Node.js, Vue.js, MongoDB, GraphQL, Kubernetes",
            Some(true),
            Some("CH"),
            Some(80000),
            Some(100000),
        );
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert!(score > 0.6, "Score should be good for this match, got {}", score);
        assert!(breakdown.skills > 0.0);
        assert_eq!(breakdown.remote, 1.0);
    }

    #[test]
    fn test_bad_match() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer(
            "Junior Java Developer",
            "Spring boot, on-site in Tokyo",
            Some(false),
            Some("JP"),
            None,
            None,
        );
        let (score, _) = matcher.score_with_breakdown(&offer);
        assert!(score < 0.5, "Score should be low for bad match, got {}", score);
    }

    #[test]
    fn test_ranking_order() {
        let matcher = Matcher::new(test_profile());
        let good = make_offer("Symfony React", "Symfony React Docker", Some(true), Some("CH"), Some(90000), Some(110000));
        let bad = make_offer("Cleaner", "Cleaning", Some(false), Some("JP"), None, None);

        let ranked = matcher.rank(vec![bad, good]);
        assert_eq!(ranked.len(), 2);
        assert!(ranked[0].score > ranked[1].score);
    }
}
