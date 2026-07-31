mod collectors;
mod config;
mod models;
mod matching;
mod ranker;
mod storage;
mod enrichment;
mod llm;
mod briefing;
mod planning;
mod calendar;
mod signals;
mod notify;

use std::path::PathBuf;
use std::sync::Arc;
use clap::{Parser, Subcommand};
use chrono::{NaiveDate, Utc};
use collectors::Collector;
use config::Profile;
use matching::Matcher;
use ranker::Ranker;
use storage::Storage;
use enrichment::Enricher;
use briefing::BriefingGenerator;
use planning::Planner;

#[derive(Parser)]
#[command(name = "kairos", about = "Assistant personnel Kairos")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Fetch job offers from all sources
    Scrape,
    /// Recompute the score of every offer already in the database with the current formula
    Rescore,
    /// Generate today's briefing
    Briefing {
        #[arg(short, long, default_value = "3")]
        top_n: u32,
    },
    /// Show status and stats
    Status,
    /// Send a prompt to the local LLM (Ollama)
    Prompt {
        text: Vec<String>,
        #[arg(short, long, default_value = "phi3:mini")]
        model: String,
    },
    /// Planning & agenda commands
    Plan {
        #[command(subcommand)]
        action: PlanAction,
    },
    /// Calendar sync commands
    Calendar {
        #[command(subcommand)]
        action: CalendarAction,
    },
}

#[derive(Subcommand)]
enum CalendarAction {
    /// Sync events from the calendar server
    Sync,
    /// List today's events
    Today,
    /// List upcoming events
    Upcoming {
        #[arg(short, long, default_value = "10")]
        count: u32,
    },
}

#[derive(Subcommand)]
enum PlanAction {
    /// Add a new task
    Add {
        title: Vec<String>,
        #[arg(short, long)]
        desc: Option<String>,
        #[arg(short, long)]
        due: Option<String>,
        #[arg(short, long, default_value = "medium")]
        priority: String,
    },
    /// List tasks
    List {
        #[arg(long)]
        today: bool,
        #[arg(long)]
        all: bool,
    },
    /// Mark a task as done
    Done {
        id: i64,
    },
    /// Edit a task
    Edit {
        id: i64,
        #[arg(short, long)]
        title: Option<String>,
        #[arg(short, long)]
        desc: Option<String>,
        #[arg(short, long)]
        due: Option<String>,
        #[arg(short, long)]
        priority: Option<String>,
    },
    /// Delete a task
    Delete {
        id: i64,
    },
    /// Show weekly task overview
    Week,
    /// Manage morning routine
    Routine {
        #[command(subcommand)]
        action: RoutineAction,
    },
}

#[derive(Subcommand)]
enum RoutineAction {
    /// Show today's routine
    Show,
    /// Check off a routine step as done
    Check {
        id: i64,
    },
    /// Add a routine step
    Add {
        name: Vec<String>,
        #[arg(short, long)]
        minutes: Option<i32>,
    },
    /// Remove a routine step
    Remove {
        id: i64,
    },
    /// Seed default routine steps
    Seed,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    dotenvy::dotenv().ok(); // charge .env si présent (no-op sinon ; prod = systemd EnvironmentFile)
    tracing_subscriber::fmt::init();

    let cli = Cli::parse();
    let db_path = std::env::var("DATABASE_PATH").unwrap_or_else(|_| "data/kairos.db".into());
    let profile = Profile::from_file("config/profile.toml")?;
    let storage = Arc::new(Storage::open(&db_path)?);

    match cli.command {
        Command::Scrape => {
            let mut collectors: Vec<Box<dyn Collector>> = vec![
                Box::new(collectors::arbeitnow::ArbeitnowCollector::new()),
                Box::new(collectors::remotive::RemotiveCollector::new()),
                Box::new(collectors::remoteok::RemoteOkCollector::new()),
                Box::new(collectors::jobicy::JobicyCollector::new()),
                Box::new(collectors::weworkremotely::WeWorkRemotelyCollector::new()),
                Box::new(collectors::linkedin_email::LinkedInEmailCollector::new()),
            ];
            // Adzuna (UE, contrats permanents + salaire) — activé si les clés API sont présentes.
            if let (Ok(id), Ok(key)) = (std::env::var("ADZUNA_APP_ID"), std::env::var("ADZUNA_API_KEY")) {
                if !id.is_empty() && !key.is_empty() {
                    let countries = profile.preferences.countries.iter().map(|c| c.to_lowercase()).collect();
                    collectors.push(Box::new(collectors::adzuna::AdzunaCollector::new(id, key, countries)));
                }
            }

            for collector in &collectors {
                tracing::info!("Fetching from {}", collector.name());
                match collector.fetch().await {
                    Ok(offers) => {
                        let count = storage.insert_offers(&offers)?;
                        tracing::info!("{}: {} new offers stored", collector.name(), count);

                        let matcher = Matcher::new(profile.clone());
                        let ranker = Ranker::new(matcher, Arc::clone(&storage));
                        let scored = ranker.score_new_offers(offers);
                        tracing::info!("{}: {} offers scored", collector.name(), scored.len());
                    }
                    Err(e) => {
                        tracing::error!("{} fetch failed: {}", collector.name(), e);
                    }
                }
            }
        }
        Command::Rescore => {
            let matcher = Matcher::new(profile);
            let offers = storage.get_all_offers()?;
            let total = offers.len();
            let mut updated = 0;
            for offer in &offers {
                let (score, _) = matcher.score_with_breakdown(offer);
                if storage.update_score(&offer.id, score).is_ok() {
                    updated += 1;
                }
            }
            println!("Rescored {updated}/{total} offers.");
        }
        Command::Briefing { top_n } => {
            let synthesis = signals::synthesize(&profile).await;

            let matcher = Matcher::new(profile);
            let ranker = Ranker::new(matcher, Arc::clone(&storage));
            let top_jobs = ranker.top_unpresented(top_n);

            let enricher = Enricher::new();
            let offers: Vec<_> = top_jobs.iter().map(|j| j.offer.clone()).collect();
            let enrichment = enricher.enrich_many(&offers).await;

            let briefings_dir = std::env::var("BRIEFINGS_DIR").map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from(std::env::var("HOME").unwrap_or_default()).join("briefings"));
            let generator = BriefingGenerator::new(briefings_dir);

            let planner = Planner::new(Storage::open(&db_path)?);
            let today_tasks = planner.today_tasks().ok();
            let pending_tasks = planner.pending_tasks().ok();
            let today_routine = planner.today_routine().ok();
            let today_events = storage.get_today_events().ok();

            let today = Utc::now().date_naive();
            let content = generator.generate_with_planning(
                today, &top_jobs, &enrichment,
                today_tasks.as_deref(), pending_tasks.as_deref(),
                today_routine.as_deref(), today_events.as_deref(),
                synthesis.as_deref(),
            )?;
            let path = generator.write(today, &content)?;

            if let Some(ics) = pending_tasks.as_deref().and_then(calendar::tasks_to_ics) {
                let ics_path = path.with_extension("ics");
                std::fs::write(&ics_path, ics)?;
                println!("Export ICS : {}", ics_path.display());
            }

            for job in &top_jobs {
                let _ = storage.mark_presented(&job.offer.id);
            }

            println!("Briefing generated: {}", path.display());

            match notify::discord(&content).await {
                Ok(true) => println!("→ posté sur Discord."),
                Ok(false) => {} // non configuré (NOTIFY_* absents)
                Err(e) => eprintln!("→ Discord non envoyé : {e}"),
            }

            const NTFY_SCORE_THRESHOLD: f64 = 0.9;
            if let Some(best) = top_jobs.iter().max_by(|a, b| a.score.total_cmp(&b.score))
                && best.score > NTFY_SCORE_THRESHOLD
            {
                let title = format!("Offre forte : {}", best.offer.title);
                let message = format!("{} — score {:.2}\n{}", best.offer.company, best.score, best.offer.url);
                match notify::ntfy(&title, &message).await {
                    Ok(true) => println!("→ alerte ntfy envoyée."),
                    Ok(false) => {} // non configuré (NTFY_* absents)
                    Err(e) => eprintln!("→ ntfy non envoyé : {e}"),
                }
            }
        }
        Command::Prompt { text, model } => {
            let prompt = text.join(" ");
            let url = std::env::var("OLLAMA_URL").unwrap_or_else(|_| "http://localhost:11434".into());
            let client = llm::LlmClient::new(url, model);
            println!("→ Envoi à Ollama...");
            match client.generate(&prompt).await {
                Ok(response) => println!("{}", response),
                Err(e) => eprintln!("Erreur Ollama : {}", e),
            }
        }
        Command::Status => {
            let stats = storage.get_stats()?;
            println!("Kairos status:");
            println!("  Total offers: {}", stats.total);
            println!("  Presented: {}", stats.presented);
            println!("  Unscored: {}", stats.unscored);
        }
        Command::Plan { action } => {
            let planner = Planner::new(Storage::open(&db_path)?);
            match action {
                PlanAction::Add { title, desc, due, priority } => {
                    let title = title.join(" ");
                    let due_date = due.as_deref().and_then(|s| NaiveDate::parse_from_str(s, "%Y-%m-%d").ok());
                    let id = planner.add_task(&title, desc.as_deref(), due_date, &priority)?;
                    println!("Task added (id={})", id);
                }
                PlanAction::List { today, all } => {
                    let tasks = planner.list_tasks(today, all)?;
                    if tasks.is_empty() {
                        println!("No tasks.");
                        return Ok(());
                    }
                    for t in &tasks {
                        let status = if t.completed { "✓" } else { " " };
                        let due = t.due_date.map(|d| d.to_string()).unwrap_or_else(|| "-".into());
                        println!("{:>3} [{}] {} (priority={}, due={})", t.id, status, t.title, t.priority, due);
                    }
                }
                PlanAction::Done { id } => {
                    planner.complete_task(id)?;
                    println!("Task {} marked as done.", id);
                }
                PlanAction::Edit { id, title, desc, due, priority } => {
                    let due_parsed = due.as_deref().map(|s| NaiveDate::parse_from_str(s, "%Y-%m-%d").ok());
                    let desc_opt: Option<Option<&str>> = match desc.as_deref() {
                        Some(d) => Some(Some(d)),
                        None => None,
                    };
                    planner.edit_task(id, title.as_deref(), desc_opt, due_parsed, priority.as_deref())?;
                    println!("Task {} updated.", id);
                }
                PlanAction::Delete { id } => {
                    planner.delete_task(id)?;
                    println!("Task {} deleted.", id);
                }
                PlanAction::Week => {
                    let tasks = planner.week_tasks()?;
                    if tasks.is_empty() {
                        println!("No tasks scheduled for this week.");
                        return Ok(());
                    }
                    let today = Utc::now().date_naive();
                    for t in &tasks {
                        let due_str = t.due_date.map(|d| d.to_string()).unwrap_or_else(|| "?".into());
                        let is_today = t.due_date.map(|d| d == today).unwrap_or(false);
                        let mark = if is_today { " ← today" } else { "" };
                        println!("  {} [{}] {}{}", due_str, t.priority, t.title, mark);
                    }
                }
                PlanAction::Routine { action } => {
                    match action {
                        RoutineAction::Show => {
                            let log = planner.show_routine()?;
                            let steps = planner.get_routine_steps()?;
                            if steps.is_empty() {
                                println!("No routine steps defined. Run `kairos plan routine seed` first.");
                                return Ok(());
                            }
                            println!("Morning routine:");
                            for step in &steps {
                                let entry = log.iter().find(|e| e.routine_step_id == step.id);
                                let status = entry.map(|e| if e.completed { "✓" } else { " " }).unwrap_or(" ");
                                let mins = step.estimated_minutes.map(|m| format!("{}min", m)).unwrap_or_else(|| "-".into());
                                println!("  {:>3} [{}] {} ({})", step.id, status, step.step_name, mins);
                            }
                        }
                        RoutineAction::Check { id } => {
                            planner.check_routine_step(id)?;
                            println!("Routine step {} checked.", id);
                        }
                        RoutineAction::Seed => {
                            let n = planner.seed_routines()?;
                            println!("Seeded {} default routine steps.", n);
                        }
                        RoutineAction::Add { name, minutes } => {
                            let name = name.join(" ");
                            let steps = planner.get_routine_steps()?;
                            let next_order = steps.iter().map(|s| s.step_order).max().unwrap_or(0) + 1;
                            let id = planner.add_routine_step(&name, next_order, minutes)?;
                            println!("Routine step added (id={}, order={})", id, next_order);
                        }
                        RoutineAction::Remove { id } => {
                            planner.remove_routine_step(id)?;
                            println!("Routine step {} removed.", id);
                        }
                    }
                }
            }
        }
        Command::Calendar { action } => {
            let storage = Storage::open(&db_path)?;
            match action {
                CalendarAction::Sync => {
                    let calendar_url = std::env::var("CALENDAR_URL")
                        .map_err(|_| anyhow::anyhow!("CALENDAR_URL not set in .env"))?;
                    let username = std::env::var("CALENDAR_USERNAME")
                        .map_err(|_| anyhow::anyhow!("CALENDAR_USERNAME not set in .env"))?;
                    let password = std::env::var("CALENDAR_PASSWORD")
                        .map_err(|_| anyhow::anyhow!("CALENDAR_PASSWORD not set in .env"))?;

                    println!("Syncing from {}...", calendar_url);
                    let client = calendar::CalendarClient::new(calendar_url, username, password);
                    let events = client.sync().await?;

                    storage.delete_calendar_events_by_source("calendar")?;
                    for event in &events {
                        storage.upsert_calendar_event(event)?;
                    }

                    println!("Synced {} events.", events.len());
                }
                CalendarAction::Today => {
                    let events = storage.get_today_events()?;
                    if events.is_empty() {
                        println!("No events today.");
                    } else {
                        println!("Today's events:");
                        for e in &events {
                            let time = if e.all_day {
                                "🌞 Journée".into()
                            } else {
                                format!("{}–{}",
                                    e.start_time.format("%H:%M"),
                                    e.end_time.format("%H:%M"))
                            };
                            let title = e.summary.as_deref().unwrap_or("(sans titre)");
                            println!("  {}  {}", time, title);
                        }
                    }
                }
                CalendarAction::Upcoming { count } => {
                    let events = storage.get_upcoming_events(count)?;
                    if events.is_empty() {
                        println!("No upcoming events.");
                    } else {
                        println!("Upcoming events:");
                        for e in &events {
                            let day = e.start_time.format("%a %d/%m").to_string();
                            let time = if e.all_day {
                                "Journée".into()
                            } else {
                                e.start_time.format("%H:%M").to_string()
                            };
                            let title = e.summary.as_deref().unwrap_or("(sans titre)");
                            println!("  {} {}  {}", day, time, title);
                        }
                    }
                }
            }
        }
    }

    Ok(())
}
