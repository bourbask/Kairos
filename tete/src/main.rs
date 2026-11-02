use std::path::Path;
use std::time::Duration;

use chrono::{NaiveDateTime, Utc};
use kairos::calendar::USER_TZ;
use kairos::llm::LlmRouter;
use tete::agent::Agent;
use tete::config::HeadConfig;
use tete::gateway::{Outbound, inbound_router};
use tete::jobs::{Ctx, due, run};
use tete::state::State;
use tete::tentacles::Tentacles;

const LLM_ROUTING_PATH: &str = "config/llm.toml";
const REFRESH_EVERY: Duration = Duration::from_secs(300);

fn now() -> NaiveDateTime {
    Utc::now().with_timezone(&USER_TZ).naive_local()
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().init();
    let path = std::env::var("TETE_CONFIG").unwrap_or_else(|_| "config/tete.toml".into());
    let db = std::env::var("TETE_DB").unwrap_or_else(|_| "data/tete.db".into());
    let cfg = HeadConfig::load(Path::new(&path))?;
    let state = State::open(Path::new(&db))?;
    let tentacles = Tentacles::connect(&cfg.tentacles).await;
    let router = LlmRouter::load(LLM_ROUTING_PATH);
    let agent = Agent::new(router, Duration::from_secs(u64::from(cfg.session_idle_minutes) * 60));
    let outbound = Outbound::new(&cfg.gateway);

    let (tx, mut rx) = tokio::sync::mpsc::channel::<String>(16);
    let listener = tokio::net::TcpListener::bind(cfg.gateway.bind).await?;
    let app = inbound_router(tx, cfg.gateway.inbound_token.clone());
    let mut server = tokio::spawn(async move { axum::serve(listener, app).await });

    let mut ctx = Ctx { cfg, state, tentacles, agent, outbound, last_suggestion: None };
    let mut tick = tokio::time::interval(Duration::from_secs(60));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last_refresh = tokio::time::Instant::now();
    tracing::info!(tentacules = ctx.cfg.tentacles.len(), "tête prête");

    loop {
        tokio::select! {
            Some(msg) = rx.recv() => {
                let answer = ctx.agent.reply(&msg, now(), &ctx.tentacles, &ctx.state).await;
                if let Err(e) = ctx.outbound.send(&answer).await {
                    tracing::warn!("réponse non envoyée, mise en attente : {e}");
                    if let Err(e) = ctx.state.enqueue("retry", &answer, now()) {
                        tracing::error!(longueur = answer.len(), "réponse perdue, mise en attente impossible : {e}");
                    }
                }
            }
            res = &mut server => {
                tracing::error!("serveur entrant arrêté : {res:?}");
                std::process::exit(1);
            }
            _ = tick.tick() => {
                ctx.agent.end_session_if_idle(now(), &ctx.tentacles).await;
                if last_refresh.elapsed() >= REFRESH_EVERY {
                    ctx.tentacles.refresh().await;
                    last_refresh = tokio::time::Instant::now();
                }
                for job in due(now(), &ctx.cfg, &ctx.state, ctx.last_suggestion) {
                    if let Err(e) = run(job, now(), &mut ctx).await {
                        tracing::error!(?job, "tâche en échec : {e}");
                    }
                }
            }
        }
    }
}
