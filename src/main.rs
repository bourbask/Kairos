mod collectors;
mod config;
mod models;
mod matching;
mod ranker;
mod storage;
mod enrichment;
mod llm;
mod briefing;

use std::path::PathBuf;
use std::sync::Arc;
use clap::Parser;
use chrono::Utc;
use collectors::Collector;
use config::Profile;
use matching::Matcher;
use ranker::Ranker;
use storage::Storage;
use enrichment::Enricher;
use briefing::BriefingGenerator;

#[derive(Parser)]
#[command(name = "kairos", about = "Assistant personnel Kairos")]
enum Cli {
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
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    let cli = Cli::parse();
    let profile = Profile::from_file("config/profile.toml")?;
    let storage = Arc::new(Storage::open("data/kairos.db")?);

    match cli {
        Cli::Scrape => {
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
        Cli::Briefing { top_n } => {
            let matcher = Matcher::new(profile);
            let ranker = Ranker::new(matcher, Arc::clone(&storage));
            let top_jobs = ranker.top_unpresented(top_n);

            let enricher = Enricher::new();
            let offers: Vec<_> = top_jobs.iter().map(|j| j.offer.clone()).collect();
            let enrichment = enricher.enrich_many(&offers).await;

            let briefings_dir = PathBuf::from(std::env::var("HOME").unwrap_or_default()).join("briefings");
            let generator = BriefingGenerator::new(briefings_dir);
            let today = Utc::now().date_naive();
            let content = generator.generate(today, &top_jobs, &enrichment)?;
            let path = generator.write(today, &content)?;

            for job in &top_jobs {
                let _ = storage.mark_presented(&job.offer.id);
            }

            println!("Briefing generated: {}", path.display());
        }
        Cli::Prompt { text, model } => {
            let prompt = text.join(" ");
            let client = llm::LlmClient::new("http://localhost:11434".into(), model);
            println!("→ Envoi à Ollama...");
            match client.generate(&prompt).await {
                Ok(response) => println!("{}", response),
                Err(e) => eprintln!("Erreur Ollama : {}", e),
            }
        }
        Cli::Status => {
            let stats = storage.get_stats()?;
            println!("Kairos status:");
            println!("  Total offers: {}", stats.total);
            println!("  Presented: {}", stats.presented);
            println!("  Unscored: {}", stats.unscored);
        }
    }

    Ok(())
}
