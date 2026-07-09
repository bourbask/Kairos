use std::path::PathBuf;
use chrono::NaiveDate;
use crate::models::{ScoredJob, EnrichedCompany};

pub struct BriefingGenerator {
    output_dir: PathBuf,
}

impl BriefingGenerator {
    pub fn new(output_dir: PathBuf) -> Self {
        Self { output_dir }
    }

    pub fn generate(
        &self,
        date: NaiveDate,
        jobs: &[ScoredJob],
        enrichment: &[(String, EnrichedCompany)],
    ) -> anyhow::Result<String> {
        let mut content = String::new();
        content.push_str(&format!("# Briefing emploi — {}\n\n", date.format("%Y-%m-%d")));

        if jobs.is_empty() {
            content.push_str("_Aucune nouvelle offre pertinente aujourd'hui._\n");
            return Ok(content);
        }

        for (i, job) in jobs.iter().enumerate() {
            let o = &job.offer;

            content.push_str(&format!("## {}. {} — {}\n\n", i + 1, o.title, o.company));

            if let Some(ref enr) = enrichment.iter().find(|(id, _)| id == &o.id).map(|(_, e)| e) {
                if let Some(ref site) = enr.website {
                    content.push_str(&format!("**Site :** {}\n", site));
                }
                if let Some(ref desc) = enr.description {
                    content.push_str(&format!("**À propos :** {}\n", desc));
                }
                if let Some(ref rev) = enr.revenue {
                    content.push_str(&format!("**CA :** {}\n", rev));
                }
                if let Some(emp) = enr.employees {
                    content.push_str(&format!("**Effectifs :** {}\n", emp));
                }
                if let Some(ref exec) = enr.executive {
                    content.push_str(&format!("**Dirigeant :** {}\n", exec));
                }
            }

            content.push_str(&format!(
                "**Salaire :** {} {} — **Localisation :** {}\n",
                o.salary_min
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| "? ".into()),
                o.currency.as_deref().unwrap_or("EUR"),
                o.location.as_deref().unwrap_or("Full remote"),
            ));

            content.push_str(&format!("**Candidature :** {}\n", o.url));

            let score_pct = (job.score * 100.0) as u8;
            content.push_str(&format!(
                "**Score :** {}% (skills {:.0}%, remote {:.0}%, salaire {:.0}%, localisation {:.0}%)\n\n",
                score_pct,
                job.breakdown.skills * 100.0,
                job.breakdown.remote * 100.0,
                job.breakdown.salary * 100.0,
                job.breakdown.location * 100.0,
            ));
        }

        content.push_str(&format!("---\n_Généré par Kairos — {date}_\n", date = date.format("%Y-%m-%d")));

        Ok(content)
    }

    pub fn write(&self, date: NaiveDate, content: &str) -> anyhow::Result<PathBuf> {
        std::fs::create_dir_all(&self.output_dir)?;
        let path = self.output_dir.join(format!("{}.md", date.format("%Y-%m-%d")));
        std::fs::write(&path, content)?;
        Ok(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{JobOffer, ScoreBreakdown};
    use chrono::Utc;

    fn make_scored_job(score: f64) -> ScoredJob {
        ScoredJob {
            offer: JobOffer {
                id: "test-1".into(),
                source: "test".into(),
                title: "Senior Full-Stack Developer".into(),
                company: "Tech Corp".into(),
                description: "Symfony React Docker".into(),
                url: "https://apply.com".into(),
                location: Some("Zurich".into()),
                country: Some("CH".into()),
                salary_min: Some(80000),
                salary_max: Some(100000),
                currency: Some("CHF".into()),
                remote: Some(true),
                published_at: None,
                collected_at: Utc::now(),
            },
            score,
            breakdown: ScoreBreakdown {
                skills: 0.8,
                remote: 1.0,
                salary: 1.0,
                location: 1.0,
            },
        }
    }

    #[test]
    fn test_generate_empty() {
        let generator = BriefingGenerator::new(PathBuf::from("/tmp"));
        let content = generator.generate(NaiveDate::from_ymd_opt(2026, 7, 1).unwrap(), &[], &[]).unwrap();
        assert!(content.contains("Aucune nouvelle offre"));
    }

    #[test]
    fn test_generate_with_jobs() {
        let generator = BriefingGenerator::new(PathBuf::from("/tmp"));
        let scored = make_scored_job(0.95);
        let content = generator.generate(
            NaiveDate::from_ymd_opt(2026, 7, 1).unwrap(),
            &[scored],
            &[],
        ).unwrap();
        assert!(content.contains("Senior Full-Stack Developer"));
        assert!(content.contains("Tech Corp"));
        assert!(content.contains("95%"));
        assert!(content.contains("Zurich"));
    }
}
