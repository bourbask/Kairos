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
    /// Delete a task
    Delete {
        id: i64,
    },
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
    /// Seed default routine steps
    Seed,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    let cli = Cli::parse();
    let profile = Profile::from_file("config/profile.toml")?;
    let storage = Arc::new(Storage::open("data/kairos.db")?);

    match cli.command {
        Command::Scrape => {
            let collectors: Vec<Box<dyn Collector>> = vec![
                Box::new(collectors::arbeitnow::ArbeitnowCollector::new()),
                Box::new(collectors::jooble::JoobleCollector::new(
                    std::env::var("JOOBLE_API_KEY").unwrap_or_default(),
                    profile.preferences.countries.clone(),
                )),
                Box::new(collectors::remotive::RemotiveCollector::new()),
            ];

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
        Command::Briefing { top_n } => {
            let matcher = Matcher::new(profile);
            let ranker = Ranker::new(matcher, Arc::clone(&storage));
            let top_jobs = ranker.top_unpresented(top_n);

            let enricher = Enricher::new();
            let offers: Vec<_> = top_jobs.iter().map(|j| j.offer.clone()).collect();
            let enrichment = enricher.enrich_many(&offers).await;

            let briefings_dir = PathBuf::from(std::env::var("HOME").unwrap_or_default()).join("briefings");
            let generator = BriefingGenerator::new(briefings_dir);

            let planner = Planner::new(Storage::open("data/kairos.db")?);
            let today_tasks = planner.today_tasks().ok();
            let pending_tasks = planner.pending_tasks().ok();
            let today_routine = planner.today_routine().ok();

            let today = Utc::now().date_naive();
            let content = generator.generate_with_planning(
                today, &top_jobs, &enrichment,
                today_tasks.as_deref(), pending_tasks.as_deref(),
                today_routine.as_deref(),
            )?;
            let path = generator.write(today, &content)?;

            for job in &top_jobs {
                let _ = storage.mark_presented(&job.offer.id);
            }

            println!("Briefing generated: {}", path.display());
        }
        Command::Prompt { text, model } => {
            let prompt = text.join(" ");
            let client = llm::LlmClient::new("http://localhost:11434".into(), model);
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
            let planner = Planner::new(Storage::open("data/kairos.db")?);
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
                PlanAction::Delete { id } => {
                    planner.delete_task(id)?;
                    println!("Task {} deleted.", id);
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
                    }
                }
            }
        }
    }

    Ok(())
}
