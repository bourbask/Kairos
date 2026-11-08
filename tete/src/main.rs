use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use chrono::{NaiveDateTime, Utc};
use kairos::calendar::USER_TZ;
use kairos::llm::LlmRouter;
use tete::agent::Agent;
use tete::app_api::{AppDeps, app_router};
use tete::commands;
use tete::config::HeadConfig;
use tete::devices;
use tete::gateway::{Inbound, Inbox, Outbound, inbound_router, mirror_text};
use tete::jobs::{Ctx, deliver, due, run, say};
use tete::state::State;
use tete::tentacles::Tentacles;
use tokio::sync::RwLock;

const LLM_ROUTING_PATH: &str = "config/llm.toml";
const REFRESH_EVERY: Duration = Duration::from_secs(300);
/// Au-delà, l'utilisateur est prévenu que la réponse arrive.
const ACK_AFTER: Duration = Duration::from_secs(20);
const ACK_MSG: &str = "Je regarde, ça peut prendre quelques minutes…";

fn now() -> NaiveDateTime {
    Utc::now().with_timezone(&USER_TZ).naive_local()
}

/// Sous-commandes d'administration des appareils : agissent sur la base puis quittent.
fn admin(args: &[String]) -> anyhow::Result<()> {
    let db = std::env::var("TETE_DB").unwrap_or_else(|_| "data/tete.db".into());
    let open = || State::open(Path::new(&db));
    match args {
        [cmd, name] if cmd == "pair" => {
            // Tout est vérifié et préparé avant l'écriture : aucune empreinte sans jeton affiché.
            let app_enabled = std::env::var("TETE_APP_BIND")
                .is_ok_and(|v| v.parse::<std::net::SocketAddr>().is_ok());
            let url = devices::pairing_url(std::env::var("TETE_APP_PUBLIC_URL").ok().as_deref(), app_enabled)?;
            let token = devices::new_token();
            let uri = devices::pairing_uri(&url, &token);
            let qr = devices::qr_text(&uri)?;
            open()?.add_device(name, &devices::hash(&token), now())?;
            println!("Appareil « {name} » appairé. Scanner le QR code ou ouvrir l'adresse :\n");
            println!("{qr}\n\n{uri}");
        }
        [cmd, name] if cmd == "revoke" => {
            if open()?.revoke_device(name)? {
                println!("Appareil « {name} » révoqué.");
            } else {
                anyhow::bail!("aucun appareil actif nommé « {name} »");
            }
        }
        [cmd] if cmd == "devices" => {
            for (name, at, revoked) in open()?.devices()? {
                println!("{name}\t{at}\t{}", if revoked { "révoqué" } else { "actif" });
            }
        }
        _ => anyhow::bail!("usage : tete [pair <nom> | revoke <nom> | devices]"),
    }
    Ok(())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().init();
    let args: Vec<String> = std::env::args().skip(1).collect();
    if !args.is_empty() {
        return admin(&args);
    }
    let path = std::env::var("TETE_CONFIG").unwrap_or_else(|_| "config/tete.toml".into());
    let db = std::env::var("TETE_DB").unwrap_or_else(|_| "data/tete.db".into());
    let cfg = HeadConfig::load(Path::new(&path))?;
    let state = State::open(Path::new(&db))?;
    let tentacles = Tentacles::connect(&cfg.tentacles).await;
    let router = LlmRouter::load(LLM_ROUTING_PATH);
    let agent = Agent::new(router, Duration::from_secs(u64::from(cfg.session_idle_minutes) * 60)).with_rhythm(cfg.work.clone());
    let outbound = Outbound::new(&cfg.gateway);

    let (tx, mut rx) = tokio::sync::mpsc::channel::<Inbound>(16);
    // Messages acceptés et non répondus (lus par l'API de l'application).
    let inbox = Inbox::new(tx);
    if let Some(bind) = cfg.gateway.app_bind {
        // Connexion et tentacules propres : l'API ne dépend jamais de la boucle principale.
        let modules = Arc::new(RwLock::new(Arc::new(Tentacles::connect(&cfg.tentacles).await)));
        let deps = AppDeps {
            state: Arc::new(Mutex::new(State::open(Path::new(&db))?)),
            modules: modules.clone(),
            inbox: inbox.clone(),
            rhythm: Arc::new(cfg.work.clone()),
            decision_count: Arc::default(),
        };
        let entries = cfg.tentacles.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(REFRESH_EVERY).await;
                // Reconnexion complète hors verrou, puis échange de l'`Arc` : ni un tentacule
                // injoignable ni un appel en cours ne font attendre l'application.
                // ponytail: reconnecte aussi les tentacules sains ; ne relancer que les absents si le coût se voit.
                let fresh = Tentacles::connect(&entries).await;
                *modules.write().await = Arc::new(fresh);
            }
        });
        let listener = tokio::net::TcpListener::bind(bind).await?;
        tokio::spawn(async move {
            if let Err(e) = axum::serve(listener, app_router(deps)).await {
                tracing::error!("API de l'application arrêtée : {e}");
            }
        });
    }
    let listener = tokio::net::TcpListener::bind(cfg.gateway.bind).await?;
    let app = inbound_router(inbox.clone(), cfg.gateway.inbound_token.clone());
    let mut server = tokio::spawn(async move { axum::serve(listener, app).await });

    let mut ctx = Ctx { cfg, state, tentacles, agent, outbound, last_suggestion: None, last_alerts: None };
    let mut tick = tokio::time::interval(Duration::from_secs(60));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last_refresh = tokio::time::Instant::now();
    tracing::info!(tentacules = ctx.cfg.tentacles.len(), "tête prête");

    loop {
        tokio::select! {
            Some(inbound) = rx.recv() => {
                let msg = inbound.text.clone();
                let source = if inbound.from_app { "app" } else { "salon" };
                if let Err(e) = ctx.state.log_chat("user", source, &msg, now()) {
                    tracing::warn!("journal de conversation : écriture impossible : {e}");
                }
                if let Some(mirror) = mirror_text(&inbound) {
                    // Recopie dans le salon, hors journal (le message est déjà journalisé).
                    if let Err(e) = ctx.outbound.send(&mirror).await {
                        tracing::warn!("recopie non envoyée : {e}");
                    }
                }
                // Commande directe : traitée sans moteur, si son tentacule est privé et configuré.
                let direct = commands::parse(&msg)
                    .filter(|_| ctx.tentacles.private_names().contains(&ctx.cfg.commands_tentacle));
                let answer = match direct {
                    Some(Ok(cmd)) => commands::run(cmd, &ctx.tentacles, &ctx.cfg.commands_tentacle).await,
                    Some(Err(usage)) => usage.to_string(),
                    None => {
                        let reply = ctx.agent.reply(&msg, now(), &ctx.tentacles, &ctx.state);
                        tokio::pin!(reply);
                        tokio::select! {
                            a = &mut reply => a,
                            _ = tokio::time::sleep(ACK_AFTER) => {
                                if let Err(e) = say(&ctx.outbound, &ctx.state, ACK_MSG).await {
                                    tracing::warn!("accusé de lecture non envoyé : {e}");
                                }
                                reply.await
                            }
                        }
                    }
                };
                // Journalisée avant l'envoi ; un échec la remet en file pour renvoi sans nouveau journal.
                if let Err(e) = deliver(&answer, now(), &ctx).await {
                    tracing::error!(longueur = answer.len(), "réponse perdue, mise en attente impossible : {e}");
                }
                inbox.done();
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
                for job in due(now(), &ctx.cfg, &ctx.state, ctx.last_suggestion, ctx.last_alerts) {
                    if let Err(e) = run(job, now(), &mut ctx).await {
                        tracing::error!(?job, "tâche en échec : {e}");
                    }
                }
            }
        }
    }
}
