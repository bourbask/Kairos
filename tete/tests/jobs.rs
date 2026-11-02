use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::{Json, Router, extract::State as AxState, http::StatusCode, routing::post};
use chrono::{NaiveDate, NaiveDateTime, NaiveTime, Weekday};
use kairos::config::{EngineConfig, LlmConfig, LlmProtocol, LlmRoutingConfig, Placement};
use kairos::llm::LlmRouter;
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use serde_json::{Value, json};
use tete::agent::Agent;
use tete::config::{GatewayEnv, HeadConfig, TentacleEntry, WorkMode};
use tete::gateway::Outbound;
use tete::jobs::{Ctx, Job, due, run};
use tete::state::{QuietScope, State};
use tete::tentacles::Tentacles;

// --- Tentacule factice ---

#[derive(Default)]
struct Rec {
    unsummarized: Mutex<String>,
    busy_args: Mutex<Vec<String>>,
    summaries: Mutex<Vec<Value>>,
}

#[derive(Clone)]
struct Fake {
    rec: Arc<Rec>,
    tool_router: ToolRouter<Self>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Period {
    period: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct At {
    at: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Limit {
    #[allow(dead_code)]
    limit: u32,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct SetSummary {
    id: i64,
    summary: String,
    topics: Vec<String>,
    decisions: Vec<String>,
}

#[tool_router]
impl Fake {
    #[tool(description = "Section de brief.")]
    fn brief_section(&self, Parameters(p): Parameters<Period>) -> Result<String, String> {
        Ok(format!("- rendez-vous ({}) à 10:00", p.period))
    }

    #[tool(description = "Occupation.")]
    fn busy_at(&self, Parameters(a): Parameters<At>) -> Result<String, String> {
        self.rec.busy_args.lock().unwrap().push(a.at);
        Ok(r#"{"busy":false,"work":false}"#.into())
    }

    #[tool(description = "Conversations à résumer.")]
    fn conversations_unsummarized(&self, Parameters(_): Parameters<Limit>) -> Result<String, String> {
        Ok(self.rec.unsummarized.lock().unwrap().clone())
    }

    #[tool(description = "Pose un résumé.")]
    fn conversation_set_summary(&self, Parameters(s): Parameters<SetSummary>) -> Result<String, String> {
        self.rec.summaries.lock().unwrap().push(json!({
            "id": s.id, "summary": s.summary, "topics": s.topics, "decisions": s.decisions,
        }));
        Ok(r#"{"updated":true}"#.into())
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for Fake {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

async fn tentacle(env: &str) -> (Tentacles, Arc<Rec>) {
    unsafe { std::env::set_var(env, "secret") };
    let rec = Arc::new(Rec::default());
    *rec.unsummarized.lock().unwrap() = "[]".into();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    let r = rec.clone();
    tokio::spawn(tentacle_common::serve_listener(listener, "secret".into(), move || Fake {
        rec: r.clone(),
        tool_router: Fake::tool_router(),
    }));
    let t = Tentacles::connect(&[TentacleEntry { name: "t".into(), url, token_env: env.into() }]).await;
    (t, rec)
}

// --- LLM factice ---

#[derive(Default)]
struct Script {
    replies: VecDeque<Value>,
    requests: Vec<Value>,
}

type Shared = Arc<Mutex<Script>>;

async fn completions(AxState(s): AxState<Shared>, Json(body): Json<Value>) -> (StatusCode, Json<Value>) {
    let mut s = s.lock().unwrap();
    s.requests.push(body);
    match s.replies.pop_front() {
        Some(message) => (StatusCode::OK, Json(json!({"choices": [{"message": message}]}))),
        None => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({}))),
    }
}

fn text(t: &str) -> Value {
    json!({"content": t})
}

/// Routeur dont seuls `usages` ont un moteur.
async fn llm(usages: &[&str], replies: Vec<Value>) -> (LlmRouter, Shared) {
    let shared: Shared = Arc::new(Mutex::new(Script { replies: replies.into(), ..Default::default() }));
    let app = Router::new().route("/v1/chat/completions", post(completions)).with_state(shared.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let endpoint = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let engine = EngineConfig {
        llm: LlmConfig { endpoint, model: "m".into(), protocol: LlmProtocol::OpenaiCompatible, ..Default::default() },
        placement: Placement::SelfHosted,
        daily_token_cap: None,
    };
    let config = LlmRoutingConfig {
        engines: HashMap::from([("e".into(), engine)]),
        usages: usages.iter().map(|u| (u.to_string(), "e".to_string())).collect(),
        ..Default::default()
    };
    (LlmRouter::from_config(config), shared)
}

// --- Passerelle factice ---

#[derive(Default)]
struct Gw {
    sent: Mutex<Vec<String>>,
    fail: AtomicBool,
}

async fn gateway() -> (Outbound, Arc<Gw>) {
    let gw = Arc::new(Gw::default());
    let g = gw.clone();
    let app = Router::new().route(
        "/out",
        post(move |Json(b): Json<Value>| async move {
            if g.fail.load(Ordering::SeqCst) {
                return StatusCode::INTERNAL_SERVER_ERROR;
            }
            g.sent.lock().unwrap().push(b["text"].as_str().unwrap().to_string());
            StatusCode::OK
        }),
    );
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(l, app).await.unwrap() });
    let env = GatewayEnv {
        bind: "127.0.0.1:0".parse().unwrap(),
        inbound_token: "in".into(),
        outbound_url: format!("http://{addr}/out"),
        outbound_token: "jeton".into(),
    };
    (Outbound::new(&env), gw)
}

// --- Contexte ---

fn t(h: u32, m: u32) -> NaiveTime {
    NaiveTime::from_hms_opt(h, m, 0).unwrap()
}

/// 2026-11-02 lundi (sur site), 2026-11-07 samedi.
fn at(d: u32, h: u32, m: u32) -> NaiveDateTime {
    NaiveDate::from_ymd_opt(2026, 11, d).unwrap().and_time(t(h, m))
}

fn cfg() -> HeadConfig {
    let mut c = HeadConfig::default();
    c.work.start = t(9, 0);
    c.work.end = t(17, 30);
    c.work.days = HashMap::from([(Weekday::Mon, WorkMode::OnSite)]);
    c
}

struct Fx {
    ctx: Ctx,
    rec: Arc<Rec>,
    gw: Arc<Gw>,
    llm: Shared,
}

async fn fixture(env: &str, usages: &[&str], replies: Vec<Value>) -> Fx {
    let (tentacles, rec) = tentacle(env).await;
    let (router, llm) = llm(usages, replies).await;
    let (outbound, gw) = gateway().await;
    let ctx = Ctx {
        cfg: cfg(),
        state: State::open_in_memory().unwrap(),
        tentacles,
        agent: Agent::new(router, Duration::from_secs(1800)),
        outbound,
        last_suggestion: None,
    };
    Fx { ctx, rec, gw, llm }
}

fn sent(gw: &Gw) -> Vec<String> {
    gw.sent.lock().unwrap().clone()
}

// --- due ---

#[test]
fn due_schedule() {
    let c = cfg();
    let s = State::open_in_memory().unwrap();
    let day = at(2, 0, 0).date();
    s.set_last_run("resumes", day).unwrap();
    // Suggestion neutralisée : dernière à l'instant.
    let quiet = |now| Some(now);

    assert!(due(at(2, 7, 29), &c, &s, quiet(at(2, 7, 29))).is_empty());
    assert_eq!(due(at(2, 7, 30), &c, &s, quiet(at(2, 7, 30))), vec![Job::MorningBrief]);
    s.set_last_run("brief_matin", day).unwrap();
    assert!(due(at(2, 8, 0), &c, &s, quiet(at(2, 8, 0))).is_empty());
    // Rattrapage le jour même seulement : le lendemain matin, le brief est de nouveau dû.
    s.set_last_run("resumes", at(3, 0, 0).date()).unwrap();
    assert_eq!(due(at(3, 9, 0), &c, &s, quiet(at(3, 9, 0))), vec![Job::MorningBrief]);

    // Résumés dus à leur heure.
    let s2 = State::open_in_memory().unwrap();
    s2.set_last_run("brief_matin", day).unwrap();
    assert!(due(at(2, 2, 59), &c, &s2, quiet(at(2, 2, 59))).is_empty());
    assert_eq!(due(at(2, 3, 0), &c, &s2, quiet(at(2, 3, 0))), vec![Job::Summaries]);
}

#[test]
fn due_suggestion_budget_and_flush() {
    let c = cfg();
    let s = State::open_in_memory().unwrap();
    let day = at(2, 0, 0).date();
    for job in ["brief_matin", "resumes"] {
        s.set_last_run(job, day).unwrap();
    }
    let now = at(2, 12, 0);
    assert_eq!(due(now, &c, &s, None), vec![Job::Suggestion]);
    assert!(due(now, &c, &s, Some(at(2, 10, 1))).is_empty());
    assert_eq!(due(now, &c, &s, Some(at(2, 10, 0))), vec![Job::Suggestion]);

    s.record_interruption(at(2, 8, 0)).unwrap();
    s.record_interruption(at(2, 9, 0)).unwrap();
    assert!(due(now, &c, &s, None).is_empty());

    s.enqueue("brief", "x", now).unwrap();
    assert_eq!(due(now, &c, &s, Some(now)), vec![Job::Flush]);
}

// --- run ---

#[tokio::test]
async fn held_briefs_then_single_flush() {
    let mut fx = fixture("JB_T1", &[], vec![]).await;
    fx.ctx.cfg.schedule.morning = t(10, 0);
    fx.ctx.cfg.schedule.evening = t(17, 0);
    run(Job::MorningBrief, at(2, 10, 0), &mut fx.ctx).await.unwrap();
    run(Job::EveningBrief, at(2, 17, 0), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(fx.rec.busy_args.lock().unwrap()[0], "2026-11-02 10:00");
    let day = at(2, 0, 0).date();
    assert_eq!(fx.ctx.state.last_run("brief_matin").unwrap(), Some(day));
    assert_eq!(fx.ctx.state.last_run("brief_soir").unwrap(), Some(day));

    // Toujours en plage bloquée : la file attend.
    run(Job::Flush, at(2, 17, 15), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());

    let end = at(2, 17, 30);
    fx.ctx.state.set_last_run("resumes", day).unwrap();
    assert_eq!(due(end, &fx.ctx.cfg, &fx.ctx.state, Some(end)), vec![Job::Flush]);
    run(Job::Flush, end, &mut fx.ctx).await.unwrap();
    assert_eq!(
        sent(&fx.gw),
        vec![
            "Brief du matin\n\n- rendez-vous (matin) à 10:00\n\n\
             Brief du soir\n\n- rendez-vous (soir) à 10:00"
                .to_string()
        ]
    );
    assert!(fx.ctx.state.take_pending().unwrap().is_empty());
}

#[tokio::test]
async fn muted_brief_not_sent_but_marked() {
    let mut fx = fixture("JB_T2", &[], vec![]).await;
    fx.ctx.state.add_quiet(at(7, 6, 0), at(7, 12, 0), QuietScope::Briefs).unwrap();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert!(fx.ctx.state.take_pending().unwrap().is_empty());
    assert_eq!(fx.ctx.state.last_run("brief_matin").unwrap(), Some(at(7, 0, 0).date()));
}

#[tokio::test]
async fn brief_reworded_when_engine_and_kept_on_send_failure() {
    let mut fx = fixture("JB_T3", &["brief"], vec![text("Un rendez-vous à 10:00.")]).await;
    fx.gw.fail.store(true, Ordering::SeqCst);
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(
        fx.ctx.state.take_pending().unwrap(),
        vec![("retry".to_string(), "Un rendez-vous à 10:00.".to_string())]
    );
    let s = fx.llm.lock().unwrap();
    let user = s.requests[0]["messages"][1]["content"].as_str().unwrap();
    assert!(user.contains("Brief du matin") && user.contains("rendez-vous (matin)"));
}

#[tokio::test]
async fn suggestion_rien_sends_nothing() {
    let mut fx = fixture("JB_T4", &["suggestion"], vec![text("  rien \n")]).await;
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 0);
    assert_eq!(fx.ctx.last_suggestion, Some(at(7, 15, 0)));
}

#[tokio::test]
async fn suggestion_sent_counts_budget() {
    let mut fx = fixture("JB_T5", &["suggestion"], vec![text("Une idée.")]).await;
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Une idée.".to_string()]);
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 1);
}

#[tokio::test]
async fn invalid_summary_is_not_stored() {
    let mut fx = fixture("JB_T6", &["resume_conversation"], vec![text("Voici le résumé : ...")]).await;
    *fx.rec.unsummarized.lock().unwrap() = r#"[{"id": 7, "transcript": "Utilisateur : salut"}]"#.into();
    run(Job::Summaries, at(2, 3, 0), &mut fx.ctx).await.unwrap();
    assert!(fx.rec.summaries.lock().unwrap().is_empty());
    assert_eq!(fx.ctx.state.last_run("resumes").unwrap(), Some(at(2, 0, 0).date()));
}

#[tokio::test]
async fn valid_summary_is_stored() {
    let reply = r#"{"summary": "Salutations.", "topics": ["accueil"], "decisions": []}"#;
    let mut fx = fixture("JB_T7", &["resume_conversation"], vec![text(reply)]).await;
    // Entrée sans transcription ignorée.
    *fx.rec.unsummarized.lock().unwrap() =
        r#"[{"id": 6}, {"id": 7, "transcript": "Utilisateur : salut"}]"#.into();
    run(Job::Summaries, at(2, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(
        *fx.rec.summaries.lock().unwrap(),
        vec![json!({"id": 7, "summary": "Salutations.", "topics": ["accueil"], "decisions": []})]
    );
    let s = fx.llm.lock().unwrap();
    assert_eq!(s.requests.len(), 1);
    assert!(s.requests[0]["messages"][1]["content"].as_str().unwrap().contains("Utilisateur : salut"));
}
