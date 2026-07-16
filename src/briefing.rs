use std::path::PathBuf;
use chrono::NaiveDate;
use crate::models::{ScoredJob, EnrichedCompany, Task, Routine, RoutineLogEntry, CalendarEvent};

/// Neutralise un champ externe non fiable avant rendu Markdown : retire les
/// caractères de contrôle, remplace les métacaractères (liens, mentions, code,
/// emphase) par des espaces, borne la longueur. Anti-injection de liens/mentions.
fn sanitize(s: &str) -> String {
    s.chars()
        .filter(|c| !c.is_control())
        .map(|c| match c {
            '[' | ']' | '(' | ')' | '`' | '*' | '_' | '@' | '<' | '>' | '\\' | '#' | '|' | '~' => ' ',
            other => other,
        })
        .take(500)
        .collect::<String>()
        .trim()
        .to_string()
}

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
        self.generate_with_planning(date, jobs, enrichment, None, None, None, None)
    }

    pub fn generate_with_planning(
        &self,
        date: NaiveDate,
        jobs: &[ScoredJob],
        enrichment: &[(String, EnrichedCompany)],
        today_tasks: Option<&[Task]>,
        pending_tasks: Option<&[Task]>,
        today_routine: Option<&[(Routine, RoutineLogEntry)]>,
        today_events: Option<&[CalendarEvent]>,
    ) -> anyhow::Result<String> {
        let mut content = String::new();
        content.push_str(&format!("# Briefing — {}\n\n", date.format("%Y-%m-%d")));

        // --- Calendar events section ---
        if let Some(events) = today_events {
            if !events.is_empty() {
                content.push_str("## Agenda\n\n");
                for e in events {
                    let time = if e.all_day {
                        "🌞".into()
                    } else {
                        format!("{}–{}",
                            e.start_time.format("%H:%M"),
                            e.end_time.format("%H:%M"))
                    };
                    let title = e.summary.as_deref().unwrap_or("(sans titre)");
                    content.push_str(&format!("- **{}** {}\n", time, title));
                    if let Some(ref loc) = e.location {
                        content.push_str(&format!("  _{}_\n", loc));
                    }
                }
                content.push('\n');
            }
        }

        // --- Planning section ---
        if let Some(routine) = today_routine {
            if !routine.is_empty() {
                content.push_str("## Routine matinale\n\n");
                for (step, log) in routine {
                    let status = if log.completed { "✓" } else { " " };
                    content.push_str(&format!("- [{}] {} ", status, step.step_name));
                    if let Some(mins) = step.estimated_minutes {
                        content.push_str(&format!("({} min)", mins));
                    }
                    content.push('\n');
                }
                content.push('\n');
            }
        }

        if let Some(tasks) = today_tasks {
            if !tasks.is_empty() {
                content.push_str("## Tâches du jour\n\n");
                for t in tasks {
                    let prio = match t.priority.as_str() {
                        "high" => "🔴",
                        _ => "",
                    };
                    content.push_str(&format!("- [ ] {} {}\n", prio, t.title));
                }
                content.push('\n');
            }
        }

        if let Some(tasks) = pending_tasks {
            let upcoming: Vec<_> = tasks.iter().filter(|t| !t.completed).collect();
            if !upcoming.is_empty() {
                content.push_str("## Tâches en attente\n\n");
                for t in &upcoming {
                    let due = t.due_date.map(|d| d.to_string()).unwrap_or_else(|| "pas de date".into());
                    content.push_str(&format!("- {} (priorité {}, échéance {})\n", t.title, t.priority, due));
                }
                content.push('\n');
            }
        }

        // --- Job offers section ---
        content.push_str(&format!("## Offres d'emploi\n\n"));

        if jobs.is_empty() {
            content.push_str("_Aucune nouvelle offre pertinente aujourd'hui._\n");
            content.push_str(&format!("\n---\n_Généré par Kairos — {date}_\n", date = date.format("%Y-%m-%d")));
            return Ok(content);
        }

        for (i, job) in jobs.iter().enumerate() {
            let o = &job.offer;

            content.push_str(&format!("### {}. {} — {}\n\n", i + 1, sanitize(&o.title), sanitize(&o.company)));

            if let Some(ref enr) = enrichment.iter().find(|(id, _)| id == &o.id).map(|(_, e)| e) {
                if let Some(ref site) = enr.website {
                    content.push_str(&format!("**Site :** {}\n", site));
                }
                if let Some(ref desc) = enr.description {
                    content.push_str(&format!("**À propos :** {}\n", sanitize(desc)));
                }
                if let Some(ref rev) = enr.revenue {
                    content.push_str(&format!("**CA :** {}\n", rev));
                }
                if let Some(emp) = enr.employees {
                    content.push_str(&format!("**Effectifs :** {}\n", emp));
                }
                if let Some(ref exec) = enr.executive {
                    content.push_str(&format!("**Dirigeant :** {}\n", sanitize(exec)));
                }
            }

            content.push_str(&format!(
                "**Salaire :** {} {} — **Localisation :** {}\n",
                o.salary_min
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| "? ".into()),
                o.currency.as_deref().unwrap_or("EUR"),
                sanitize(o.location.as_deref().unwrap_or("Full remote")),
            ));

            content.push_str(&format!("**Candidature :** {}\n", o.url));

            // Gates (remote/CDI/blacklist) déjà validées pour apparaître ici — la base 40 + bonus
            // affichés sont les points de la formule (matching.rs), pas des pourcentages relatifs.
            let score_pct = (job.score * 100.0) as u8;
            content.push_str(&format!(
                "**Score :** {}% (base 40 + compétences {:+.0} + pays {:+.0} + salaire {:+.0})\n\n",
                score_pct,
                job.breakdown.skills * 100.0,
                job.breakdown.location * 100.0,
                job.breakdown.salary * 100.0,
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

    #[test]
    fn test_generate_with_planning_section() {
        let generator = BriefingGenerator::new(PathBuf::from("/tmp"));

        let task = Task {
            id: 1,
            title: "Buy groceries".into(),
            description: None,
            due_date: Some(NaiveDate::from_ymd_opt(2026, 7, 9).unwrap()),
            priority: "high".into(),
            completed: false,
            created_at: NaiveDate::from_ymd_opt(2026, 7, 8).unwrap(),
            completed_at: None,
        };

        let routine_step = Routine {
            id: 1,
            step_name: "Lecture briefing".into(),
            step_order: 1,
            estimated_minutes: Some(5),
            enabled: true,
        };

        let routine_log = RoutineLogEntry {
            id: 1,
            date: NaiveDate::from_ymd_opt(2026, 7, 9).unwrap(),
            routine_step_id: 1,
            completed: false,
            completed_at: None,
        };

        let scored = make_scored_job(0.85);
        let content = generator.generate_with_planning(
            NaiveDate::from_ymd_opt(2026, 7, 9).unwrap(),
            &[scored],
            &[],
            Some(&[task]),
            Some(&[]),
            Some(&[(routine_step, routine_log)]),
            None,
        ).unwrap();

        assert!(content.contains("Routine matinale"));
        assert!(content.contains("Lecture briefing"));
        assert!(content.contains("Tâches du jour"));
        assert!(content.contains("Buy groceries"));
        assert!(content.contains("Offres d'emploi"));
    }
}
