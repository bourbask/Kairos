use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
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
use tete::jobs::{Ctx, Job, deliver, run, say};
use tete::state::{QuietScope, State};
use tete::tentacles::Tentacles;

// --- Tentacule factice ---

#[derive(Default)]
struct Rec {
    unsummarized: Mutex<String>,
    busy_args: Mutex<Vec<String>>,
    summaries: Mutex<Vec<Value>>,
    /// Transcriptions par identifiant, rendues par `conversation_get`.
    transcripts: Mutex<HashMap<i64, String>>,
    /// Réponse brute de `alerts`.
    alerts: Mutex<String>,
    /// Section de brief imposée ; vide → section par défaut.
    brief: Mutex<String>,
    /// Appels reçus par `brief_section`.
    brief_calls: AtomicU32,
    /// Nuit : dernier jour traité et statut, par étape.
    nuit: Mutex<HashMap<String, (Option<String>, String)>>,
    /// Textes des épisodes, par jour.
    episodes: Mutex<HashMap<String, Vec<String>>>,
    /// Indices appliqués : `{jour, indices}`.
    applied: Mutex<Vec<Value>>,
    /// Lots encore condensables.
    lots: Mutex<Vec<Value>>,
    /// Synthèses enregistrées : `{periode, texte, empreinte}`.
    condensed: Mutex<Vec<Value>>,
    /// Outils de nuit appelés, dans l'ordre.
    night_calls: Mutex<Vec<String>>,
    /// Conversations encore à résumer, par jour.
    unsummarized_days: Mutex<HashMap<String, u32>>,
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
struct Id {
    id: i64,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct SetSummary {
    id: i64,
    summary: String,
    topics: Vec<String>,
    decisions: Vec<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Noter {
    etape: String,
    statut: String,
    dernier_jour: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Jour {
    jour: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Appliquer {
    jour: String,
    indices: Value,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Max {
    max: usize,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Condenser {
    niveau: String,
    periode: String,
    texte: String,
    empreinte: String,
}

impl Fake {
    fn night(&self, tool: &str) {
        self.rec.night_calls.lock().unwrap().push(tool.into());
    }
}

#[tool_router]
impl Fake {
    #[tool(description = "État de nuit.")]
    fn nuit_etat(&self) -> Result<String, String> {
        self.night("etat");
        let n = self.rec.nuit.lock().unwrap();
        let rows: Vec<Value> = ["condensation", "extraction", "oubli"]
            .iter()
            .map(|e| {
                let (d, s) = n.get(*e).cloned().unwrap_or((None, "ok".into()));
                json!({"etape": e, "dernier_jour": d, "derniere_execution": "2026-11-01T03:00:00", "statut": s, "illisible": false})
            })
            .collect();
        Ok(Value::Array(rows).to_string())
    }

    #[tool(description = "Note une étape.")]
    fn nuit_noter(&self, Parameters(a): Parameters<Noter>) -> Result<String, String> {
        self.night("noter");
        let mut n = self.rec.nuit.lock().unwrap();
        let e = n.entry(a.etape).or_default();
        if a.dernier_jour.is_some() {
            e.0 = a.dernier_jour;
        }
        e.1 = a.statut;
        Ok(r#"{"message":"État noté."}"#.into())
    }

    #[tool(description = "Épisodes d'un jour.")]
    fn nuit_episodes(&self, Parameters(a): Parameters<Jour>) -> Result<String, String> {
        self.night("episodes");
        let texts = self.rec.episodes.lock().unwrap().get(&a.jour).cloned().unwrap_or_default();
        let v: Vec<Value> =
            texts.iter().map(|t| json!({"nature": "conversation", "ref": "1", "date": a.jour, "texte": t})).collect();
        Ok(Value::Array(v).to_string())
    }

    #[tool(description = "Conversations à résumer d'un jour.")]
    fn nuit_en_attente(&self, Parameters(a): Parameters<Jour>) -> Result<String, String> {
        self.night("en_attente");
        let n = self.rec.unsummarized_days.lock().unwrap().get(&a.jour).copied().unwrap_or(0);
        Ok(json!({"conversations": n}).to_string())
    }

    #[tool(description = "Clés connues.")]
    fn nuit_cles(&self) -> Result<String, String> {
        self.night("cles");
        Ok(r#"["sport", "ville"]"#.into())
    }

    #[tool(description = "Applique des indices.")]
    fn nuit_appliquer(&self, Parameters(a): Parameters<Appliquer>) -> Result<String, String> {
        self.night("appliquer");
        self.rec.applied.lock().unwrap().push(json!({"jour": a.jour, "indices": a.indices}));
        Ok(r#"{"renforces":0,"deduits":1,"questions":0,"ignores":0}"#.into())
    }

    #[tool(description = "Lots condensables.")]
    fn nuit_condensables(&self, Parameters(a): Parameters<Max>) -> Result<String, String> {
        self.night("condensables");
        let lots: Vec<Value> = self.rec.lots.lock().unwrap().iter().take(a.max).cloned().collect();
        Ok(Value::Array(lots).to_string())
    }

    #[tool(description = "Condense un lot.")]
    fn nuit_condenser(&self, Parameters(a): Parameters<Condenser>) -> Result<String, String> {
        self.night("condenser");
        let mut lots = self.rec.lots.lock().unwrap();
        let Some(i) = lots.iter().position(|l| l["niveau"] == a.niveau && l["periode"] == a.periode && l["empreinte"] == a.empreinte) else {
            return Ok(r#"{"id":null}"#.into());
        };
        lots.remove(i);
        self.rec.condensed.lock().unwrap().push(json!({"periode": a.periode, "texte": a.texte, "empreinte": a.empreinte}));
        Ok(r#"{"id":1}"#.into())
    }

    #[tool(description = "Oubli.")]
    fn nuit_oublier(&self) -> Result<String, String> {
        self.night("oublier");
        Ok(r#"{"supprimes":2}"#.into())
    }

    #[tool(description = "Section de brief.")]
    fn brief_section(&self, Parameters(p): Parameters<Period>) -> Result<String, String> {
        self.rec.brief_calls.fetch_add(1, Ordering::SeqCst);
        let fixed = self.rec.brief.lock().unwrap().clone();
        if !fixed.is_empty() {
            return Ok(fixed);
        }
        Ok(format!("- rendez-vous ({}) à 10:00", p.period))
    }

    #[tool(description = "Occupation.")]
    fn busy_at(&self, Parameters(a): Parameters<At>) -> Result<String, String> {
        self.rec.busy_args.lock().unwrap().push(a.at);
        Ok(r#"{"busy":false,"work":false}"#.into())
    }

    #[tool(description = "Alertes.")]
    fn alerts(&self) -> Result<String, String> {
        Ok(self.rec.alerts.lock().unwrap().clone())
    }

    #[tool(description = "Conversations à résumer.")]
    fn conversations_unsummarized(&self, Parameters(_): Parameters<Limit>) -> Result<String, String> {
        Ok(self.rec.unsummarized.lock().unwrap().clone())
    }

    #[tool(description = "Transcription.")]
    fn conversation_get(&self, Parameters(a): Parameters<Id>) -> Result<String, String> {
        let t = self.rec.transcripts.lock().unwrap().get(&a.id).cloned().ok_or("inconnue")?;
        Ok(json!({"id": a.id, "channel": "c", "started_at": "2026-11-01T10:00:00", "transcript": t}).to_string())
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
    let (url, rec) = spawn_fake(env).await;
    let t = Tentacles::connect(&[TentacleEntry { name: "t".into(), url, token_env: env.into(), private: false, keywords: vec![] }]).await;
    (t, rec)
}

async fn spawn_fake(env: &str) -> (String, Arc<Rec>) {
    unsafe { std::env::set_var(env, "secret") };
    let rec = Arc::new(Rec::default());
    *rec.unsummarized.lock().unwrap() = "[]".into();
    *rec.alerts.lock().unwrap() = "[]".into();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    let r = rec.clone();
    tokio::spawn(tentacle_common::serve_listener(listener, "secret".into(), move || Fake {
        rec: r.clone(),
        tool_router: Fake::tool_router(),
    }));
    (url, rec)
}

// --- LLM factice ---

#[derive(Default)]
struct Script {
    replies: VecDeque<Value>,
    requests: Vec<Value>,
}

type Shared = Arc<Mutex<Script>>;

async fn completions(AxState(s): AxState<Shared>, Json(body): Json<Value>) -> (StatusCode, Json<Value>) {
    let reply = {
        let mut s = s.lock().unwrap();
        s.requests.push(body);
        s.replies.pop_front()
    };
    match reply {
        // `"pendre"` simule un moteur qui ne répond jamais.
        Some(Value::String(h)) if h == "pendre" => std::future::pending().await,
        Some(message) => {
            let finish = message.get("finish_reason").cloned();
            (StatusCode::OK, Json(json!({"choices": [{"message": message, "finish_reason": finish}]})))
        }
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
        fallback: None,
        max_tools: None,
    };
    let config = LlmRoutingConfig {
        // Voie auto-hébergée renseignée comme par l'environnement : un usage non déclaré ne doit pas la prendre.
        self_hosted: Some(engine.llm.clone()),
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
        app_bind: None,
        app_apk_dir: None,
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

/// 2026-11-02 lundi, 2026-11-07 samedi ; mercredi (04) sur site.
fn at(d: u32, h: u32, m: u32) -> NaiveDateTime {
    NaiveDate::from_ymd_opt(2026, 11, d).unwrap().and_time(t(h, m))
}

fn cfg() -> HeadConfig {
    let mut c = HeadConfig::default();
    c.work.start = t(9, 0);
    c.work.end = t(17, 30);
    c.work.days = HashMap::from([(Weekday::Wed, WorkMode::OnSite)]);
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
        last_alerts: None,
    };
    Fx { ctx, rec, gw, llm }
}

fn sent(gw: &Gw) -> Vec<String> {
    gw.sent.lock().unwrap().clone()
}

// --- due ---

/// Tâches dues, alertes neutralisées (relevées à l'instant).
fn due(now: NaiveDateTime, c: &HeadConfig, s: &State, last_suggestion: Option<NaiveDateTime>) -> Vec<Job> {
    tete::jobs::due(now, c, s, last_suggestion, Some(now))
}

#[test]
fn due_alerts_every_30_minutes() {
    let c = cfg();
    let s = State::open_in_memory().unwrap();
    let day = at(2, 0, 0).date();
    for job in ["brief_matin", "resumes"] {
        s.set_last_run(job, day).unwrap();
    }
    let now = at(2, 12, 0);
    let due = |last| tete::jobs::due(now, &c, &s, Some(now), last);
    assert_eq!(due(None), vec![Job::Alerts]);
    assert!(due(Some(at(2, 11, 31))).is_empty());
    assert_eq!(due(Some(at(2, 11, 30))), vec![Job::Alerts]);
}

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
fn due_catch_up_is_bounded() {
    let c = cfg();
    let quiet = |now| Some(now);
    let day = at(2, 0, 0).date();

    // Brief du matin manqué : plus rattrapé à l'heure du soir.
    let s = State::open_in_memory().unwrap();
    s.set_last_run("resumes", day).unwrap();
    assert_eq!(due(at(2, 20, 59), &c, &s, quiet(at(2, 20, 59))), vec![Job::MorningBrief]);
    assert_eq!(due(at(2, 21, 0), &c, &s, quiet(at(2, 21, 0))), vec![Job::EveningBrief]);
    // Brief du soir manqué : plus rattrapé une fois la nuit commencée.
    assert_eq!(due(at(2, 22, 29), &c, &s, quiet(at(2, 22, 29))), vec![Job::EveningBrief]);
    assert!(due(at(2, 22, 30), &c, &s, quiet(at(2, 22, 30))).is_empty());

    // Résumés : seulement dans la nuit.
    let s2 = State::open_in_memory().unwrap();
    s2.set_last_run("brief_matin", day).unwrap();
    s2.set_last_run("brief_soir", day).unwrap();
    assert_eq!(due(at(2, 6, 59), &c, &s2, quiet(at(2, 6, 59))), vec![Job::Summaries]);
    assert!(due(at(2, 7, 0), &c, &s2, quiet(at(2, 7, 0))).is_empty());
}

#[test]
fn due_windows_cross_midnight() {
    let quiet = |now| Some(now);
    let day = at(2, 0, 0).date();

    // Nuit à 00:30 : le brief du soir reste dû jusque-là, y compris après minuit.
    let mut c = cfg();
    c.schedule.quiet_start = t(0, 30);
    let s = State::open_in_memory().unwrap();
    s.set_last_run("brief_matin", day).unwrap();
    s.set_last_run("brief_matin", at(3, 0, 0).date()).unwrap();
    s.set_last_run("resumes", at(3, 0, 0).date()).unwrap();
    assert_eq!(due(at(2, 23, 0), &c, &s, quiet(at(2, 23, 0))), vec![Job::EveningBrief]);
    assert_eq!(due(at(3, 0, 10), &c, &s, quiet(at(3, 0, 10))), vec![Job::EveningBrief]);
    assert!(due(at(3, 0, 40), &c, &s, quiet(at(3, 0, 40))).is_empty());

    // Résumés à 23:00 jusqu'à 07:00.
    let mut c = cfg();
    c.schedule.summaries = t(23, 0);
    let s2 = State::open_in_memory().unwrap();
    for d in [day, at(3, 0, 0).date()] {
        s2.set_last_run("brief_matin", d).unwrap();
        s2.set_last_run("brief_soir", d).unwrap();
    }
    assert!(due(at(2, 22, 59), &c, &s2, quiet(at(2, 22, 59))).is_empty());
    assert_eq!(due(at(2, 23, 0), &c, &s2, quiet(at(2, 23, 0))), vec![Job::Summaries]);
    assert_eq!(due(at(3, 6, 59), &c, &s2, quiet(at(3, 6, 59))), vec![Job::Summaries]);
    assert!(due(at(3, 7, 0), &c, &s2, quiet(at(3, 7, 0))).is_empty());
}

#[tokio::test]
async fn window_run_before_midnight_not_repeated_after() {
    let mut fx = fixture("JB_T17", &[], vec![]).await;
    fx.ctx.cfg.schedule.summaries = t(23, 0);
    let (d2, d3) = (at(2, 0, 0).date(), at(3, 0, 0).date());
    for d in [d2, d3] {
        fx.ctx.state.set_last_run("brief_matin", d).unwrap();
        fx.ctx.state.set_last_run("brief_soir", d).unwrap();
    }
    run(Job::Summaries, at(2, 23, 0), &mut fx.ctx).await.unwrap();
    let now = at(3, 0, 10);
    assert!(due(now, &fx.ctx.cfg, &fx.ctx.state, Some(now)).is_empty());
    // L'occurrence suivante reste due le soir même.
    let now = at(3, 23, 0);
    assert_eq!(due(now, &fx.ctx.cfg, &fx.ctx.state, Some(now)), vec![Job::Summaries]);
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
    run(Job::MorningBrief, at(4, 10, 0), &mut fx.ctx).await.unwrap();
    run(Job::EveningBrief, at(4, 17, 0), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(fx.rec.busy_args.lock().unwrap()[0], "2026-11-04 10:00");
    let day = at(4, 0, 0).date();
    assert_eq!(fx.ctx.state.last_run("brief_matin").unwrap(), Some(day));
    assert_eq!(fx.ctx.state.last_run("brief_soir").unwrap(), Some(day));

    // Toujours en plage bloquée : la file attend.
    run(Job::Flush, at(4, 17, 15), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());

    let end = at(4, 17, 30);
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
    // Sans outils ; contexte = brief de la période et date locale.
    let s = fx.llm.lock().unwrap();
    let req = &s.requests[0];
    assert!(req["tools"].as_array().is_none_or(|t| t.is_empty()));
    let all = req["messages"].to_string();
    assert!(all.contains("rendez-vous (soir)") && all.contains("2026-11-07 15:00"));
}

#[tokio::test]
async fn suggestion_rien_variants_send_nothing() {
    let mut fx = fixture("JB_T8", &["suggestion"], vec![text("`RIEN`."), text("« Rien »")]).await;
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    run(Job::Suggestion, at(7, 17, 0), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 0);
    let s = fx.llm.lock().unwrap();
    assert_eq!(s.requests.len(), 2);
    assert!(!s.requests[0]["messages"][0]["content"].as_str().unwrap().contains('`'));
}

#[tokio::test]
async fn held_suggestion_calls_nothing() {
    let mut fx = fixture("JB_T9", &["suggestion"], vec![text("Une idée.")]).await;
    // Jour sur site, en journée : retenue, donc ni moteur ni envoi.
    run(Job::Suggestion, at(4, 10, 0), &mut fx.ctx).await.unwrap();
    assert!(fx.llm.lock().unwrap().requests.is_empty());
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(fx.ctx.state.interruptions_on(at(2, 0, 0).date()).unwrap(), 0);
}

#[tokio::test]
async fn dropped_brief_does_not_call_engine() {
    let mut fx = fixture("JB_T10", &["brief"], vec![text("reformulé")]).await;
    fx.ctx.state.add_quiet(at(7, 6, 0), at(7, 12, 0), QuietScope::Briefs).unwrap();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert!(fx.llm.lock().unwrap().requests.is_empty());
}

#[tokio::test]
async fn flush_send_failure_requeues_once() {
    let mut fx = fixture("JB_T11", &[], vec![]).await;
    fx.ctx.state.enqueue("brief", "un", at(7, 6, 0)).unwrap();
    fx.ctx.state.enqueue("brief", "deux", at(7, 6, 0)).unwrap();
    fx.gw.fail.store(true, Ordering::SeqCst);
    run(Job::Flush, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.ctx.state.take_pending().unwrap(), vec![("retry".to_string(), "un\n\ndeux".to_string())]);
}

#[tokio::test]
async fn unreadable_quiet_holds_brief() {
    let mut fx = fixture("JB_T12", &[], vec![]).await;
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("tete.db");
    fx.ctx.state = State::open(&path).unwrap();
    rusqlite::Connection::open(&path)
        .unwrap()
        .execute(
            "INSERT INTO quiet (from_ts, until_ts, scope) VALUES ('2026-11-07 00:00:00', '2026-11-08 00:00:00', 'inconnue')",
            [],
        )
        .unwrap();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(fx.ctx.state.take_pending().unwrap().len(), 1);
}

#[tokio::test]
async fn suggestion_sent_counts_budget() {
    let mut fx = fixture("JB_T5", &["suggestion"], vec![text("Une idée.")]).await;
    run(Job::Suggestion, at(7, 13, 59), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Une idée.".to_string()]);
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 1);
    assert!(fx.llm.lock().unwrap().requests[0]["messages"].to_string().contains("rendez-vous (matin)"));
}

#[tokio::test]
async fn invalid_summary_skipped_next_one_stored() {
    let valid = r#"{"summary": "Salutations.", "topics": ["accueil"], "decisions": []}"#;
    let replies = vec![text("Voici le résumé : ..."), text(&format!("```json\n{valid}\n```"))];
    let mut fx = fixture("JB_T6", &["resume_conversation"], replies).await;
    // Entrées mal formées et identifiant inconnu ignorés.
    *fx.rec.unsummarized.lock().unwrap() = r#"[{"id": "x"}, {}, {"id": 5}, {"id": 6}, {"id": 7}]"#.into();
    *fx.rec.transcripts.lock().unwrap() =
        HashMap::from([(6, "Utilisateur : bonjour".to_string()), (7, "Utilisateur : salut".to_string())]);
    run(Job::Summaries, at(2, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(
        *fx.rec.summaries.lock().unwrap(),
        vec![json!({"id": 7, "summary": "Salutations.", "topics": ["accueil"], "decisions": []})]
    );
    assert_eq!(fx.ctx.state.last_run("resumes").unwrap(), Some(at(2, 0, 0).date()));
    let s = fx.llm.lock().unwrap();
    assert_eq!(s.requests.len(), 2);
    assert!(s.requests[1]["messages"][1]["content"].as_str().unwrap().contains("Utilisateur : salut"));
}

#[tokio::test]
async fn fenced_summary_still_strict() {
    let extra = r#"{"summary": "s", "topics": [], "decisions": [], "mood": "x"}"#;
    let ok = r#"{"summary": "s", "topics": [], "decisions": []}"#;
    let replies = vec![text(&format!("```\n{extra}\n```")), text(&format!("```\n{ok}\n```"))];
    let mut fx = fixture("JB_T13", &["resume_conversation"], replies).await;
    *fx.rec.unsummarized.lock().unwrap() = r#"[{"id": 6}, {"id": 7}]"#.into();
    *fx.rec.transcripts.lock().unwrap() = HashMap::from([(6, "a".to_string()), (7, "b".to_string())]);
    run(Job::Summaries, at(2, 3, 0), &mut fx.ctx).await.unwrap();
    let got = fx.rec.summaries.lock().unwrap().clone();
    assert_eq!(got.len(), 1);
    assert_eq!(got[0]["id"], 7);
}

#[tokio::test]
async fn long_transcript_reaches_engine_whole() {
    let reply = r#"{"summary": "Long.", "topics": [], "decisions": []}"#;
    let mut fx = fixture("JB_T7", &["resume_conversation"], vec![text(reply)]).await;
    // Transcription déjà réduite par le tentacule (60 000 caractères).
    let long = format!("{}\n…\n{}FIN", "a".repeat(15_000), "b".repeat(44_994));
    *fx.rec.unsummarized.lock().unwrap() = r#"[{"id": 7}]"#.into();
    *fx.rec.transcripts.lock().unwrap() = HashMap::from([(7, long.clone())]);
    run(Job::Summaries, at(2, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.rec.summaries.lock().unwrap().len(), 1);
    let s = fx.llm.lock().unwrap();
    assert_eq!(s.requests[0]["messages"][1]["content"].as_str().unwrap(), long);
}

#[tokio::test]
async fn undeclared_usages_call_no_engine() {
    let mut fx = fixture("JB_T14", &[], vec![text("reformulé"), text("Une idée.")]).await;
    *fx.rec.unsummarized.lock().unwrap() = r#"[{"id": 7}]"#.into();
    *fx.rec.transcripts.lock().unwrap() = HashMap::from([(7, "a".to_string())]);
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    run(Job::Summaries, at(7, 3, 0), &mut fx.ctx).await.unwrap();
    assert!(fx.llm.lock().unwrap().requests.is_empty());
    assert_eq!(sent(&fx.gw), vec!["Brief du matin\n\n- rendez-vous (matin) à 10:00".to_string()]);
}

#[tokio::test]
async fn hung_engine_sends_raw_brief() {
    tete::agent::override_llm_timeout(Duration::from_millis(300));
    let mut fx = fixture("JB_T15", &["brief"], vec![json!("pendre")]).await;
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Brief du matin\n\n- rendez-vous (matin) à 10:00".to_string()]);
}

#[tokio::test]
async fn reworded_brief_losing_a_number_is_sent_raw() {
    let mut fx = fixture("JB_T16", &["brief"], vec![text("Un rendez-vous à 11:00.")]).await;
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Brief du matin\n\n- rendez-vous (matin) à 10:00".to_string()]);
}

// --- avertissements « ⚠ » et nombres ---

const WARNED: &str = "⚠ Estimation incertaine. Disponible : 10 €.";

#[tokio::test]
async fn reworded_brief_losing_a_warning_is_sent_raw() {
    let mut fx = fixture("JB_W1", &["brief"], vec![text("Disponible : 10 €. Estimation incertaine.")]).await;
    *fx.rec.brief.lock().unwrap() = WARNED.into();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec![format!("Brief du matin\n\n{WARNED}")]);
}

#[tokio::test]
async fn reworded_brief_adding_a_number_is_sent_raw() {
    let mut fx = fixture("JB_W2", &["brief"], vec![text("Un rendez-vous à 10:00, durée 2 h.")]).await;
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Brief du matin\n\n- rendez-vous (matin) à 10:00".to_string()]);
}

#[tokio::test]
async fn reworded_brief_keeping_warning_and_numbers_is_sent() {
    let reworded = "⚠ Estimation incertaine. Il te reste 10 €.";
    let mut fx = fixture("JB_W3", &["brief"], vec![text(reworded)]).await;
    *fx.rec.brief.lock().unwrap() = WARNED.into();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec![reworded.to_string()]);
}

#[tokio::test]
async fn suggestion_with_foreign_number_dropped() {
    let mut fx = fixture("JB_W4", &["suggestion"], vec![text("Mets 50 € de côté.")]).await;
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 0);
}

#[tokio::test]
async fn suggestion_without_context_warning_dropped() {
    let mut fx = fixture("JB_W5", &["suggestion"], vec![text("Garde 10 € pour demain.")]).await;
    *fx.rec.brief.lock().unwrap() = WARNED.into();
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
}

#[tokio::test]
async fn suggestion_led_by_context_warning_sent() {
    let reply = "⚠ Estimation incertaine. Garde 10 € pour demain.";
    let mut fx = fixture("JB_W6", &["suggestion"], vec![text(reply)]).await;
    *fx.rec.brief.lock().unwrap() = WARNED.into();
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec![reply.to_string()]);
}

#[tokio::test]
async fn reworded_brief_dropping_a_sign_is_sent_raw() {
    let raw = "Disponible : −71 €, écart -12 €.";
    let mut fx = fixture("JB_W7", &["brief"], vec![text("Il reste 71 € disponibles, écart 12 €.")]).await;
    *fx.rec.brief.lock().unwrap() = raw.into();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec![format!("Brief du matin\n\n{raw}")]);
}

#[tokio::test]
async fn emoji_variant_warning_still_recognised() {
    // « ⚠️ » = U+26A0 suivi du sélecteur de variante U+FE0F.
    let mut fx = fixture("JB_W8", &["brief"], vec![text("⚠\u{FE0F} Estimation incertaine. Il te reste 10 €.")]).await;
    *fx.rec.brief.lock().unwrap() = WARNED.into();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["⚠ Estimation incertaine. Il te reste 10 €.".to_string()]);
    let reply = "⚠\u{FE0F} Estimation incertaine. Garde 10 € pour demain.";
    let mut fx = fixture("JB_W9", &["suggestion"], vec![text(reply)]).await;
    *fx.rec.brief.lock().unwrap() = WARNED.into();
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["⚠ Estimation incertaine. Garde 10 € pour demain.".to_string()]);
}

// --- alertes ---

fn alert(id: &str, text: &str, counts_budget: bool) -> Value {
    json!({"id": id, "kind": if counts_budget { "near_limit" } else { "overdue" }, "text": text, "counts_budget": counts_budget})
}

#[tokio::test]
async fn same_alert_served_twice_sent_once() {
    let mut fx = fixture("JB_A1", &[], vec![]).await;
    *fx.rec.alerts.lock().unwrap() = json!([alert("a1", "Poste presque épuisé.", true)]).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    run(Job::Alerts, at(7, 15, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Poste presque épuisé.".to_string()]);
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 1);
    assert_eq!(fx.ctx.last_alerts, Some(at(7, 15, 30)));
}

#[tokio::test]
async fn briefs_mute_does_not_drop_report() {
    let mut fx = fixture("JB_A2", &[], vec![]).await;
    fx.ctx.state.add_quiet(at(7, 6, 0), at(7, 18, 0), QuietScope::Briefs).unwrap();
    *fx.rec.alerts.lock().unwrap() = json!([alert("a1", "Échéance dépassée.", false)]).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Échéance dépassée.".to_string()]);
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 0);
}

#[tokio::test]
async fn alert_held_on_site_then_flushed() {
    let mut fx = fixture("JB_A3", &[], vec![]).await;
    *fx.rec.alerts.lock().unwrap() = json!([alert("a1", "Échéance dépassée.", false)]).to_string();
    run(Job::Alerts, at(4, 10, 0), &mut fx.ctx).await.unwrap();
    run(Job::Alerts, at(4, 10, 30), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    run(Job::Flush, at(4, 17, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Échéance dépassée.".to_string()]);
}

#[tokio::test]
async fn budget_alert_over_budget_not_sent_nor_marked() {
    let mut fx = fixture("JB_A4", &[], vec![]).await;
    fx.ctx.state.record_interruption(at(7, 8, 0)).unwrap();
    fx.ctx.state.record_interruption(at(7, 9, 0)).unwrap();
    *fx.rec.alerts.lock().unwrap() = json!([alert("a1", "Poste presque épuisé.", true)]).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert!(fx.ctx.state.take_pending().unwrap().is_empty());
    assert!(!fx.ctx.state.alert_seen("t:a1").unwrap());
}

#[tokio::test]
async fn malformed_alerts_ignored() {
    let mut fx = fixture("JB_A5", &[], vec![]).await;
    for raw in ["pas du json", r#"[{"id": "a1"}]"#, r#"{"id": "a1"}"#] {
        *fx.rec.alerts.lock().unwrap() = raw.into();
        run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    }
    assert!(sent(&fx.gw).is_empty());
    assert!(fx.ctx.state.take_pending().unwrap().is_empty());
}

#[tokio::test]
async fn duplicate_id_in_one_response_sent_once() {
    let mut fx = fixture("JB_A6", &[], vec![]).await;
    *fx.rec.alerts.lock().unwrap() = json!([alert("a1", "Une.", false), alert("a1", "Une.", false)]).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Une.".to_string()]);
}

#[tokio::test]
async fn alerts_grouped_and_capped_per_poll() {
    let mut fx = fixture("JB_A7", &[], vec![]).await;
    let all: Vec<Value> = (0..15).map(|i| alert(&format!("a{i}"), &format!("Alerte {i}."), i == 0)).collect();
    *fx.rec.alerts.lock().unwrap() = Value::Array(all).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    let first = (0..10).map(|i| format!("Alerte {i}.")).collect::<Vec<_>>().join("\n\n");
    assert_eq!(sent(&fx.gw), vec![first.clone()]);
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 1);
    run(Job::Alerts, at(7, 15, 30), &mut fx.ctx).await.unwrap();
    let rest = (10..15).map(|i| format!("Alerte {i}.")).collect::<Vec<_>>().join("\n\n");
    assert_eq!(sent(&fx.gw), vec![first, rest]);
}

#[tokio::test]
async fn blank_and_control_chars_cleaned() {
    let mut fx = fixture("JB_A8", &[], vec![]).await;
    *fx.rec.alerts.lock().unwrap() =
        json!([alert("a1", " \u{7} \n ", false), alert("a2", "Ligne\u{1b}[31m un\nLigne deux", false)]).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Ligne[31m un\nLigne deux".to_string()]);
    assert!(!fx.ctx.state.alert_seen("t:a1").unwrap());
}

#[tokio::test]
async fn gateway_failure_queues_retry_and_marks() {
    let mut fx = fixture("JB_A9", &[], vec![]).await;
    fx.gw.fail.store(true, Ordering::SeqCst);
    *fx.rec.alerts.lock().unwrap() = json!([alert("a1", "Une.", true), alert("a2", "Deux.", true)]).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.ctx.state.take_pending().unwrap(), vec![("retry".to_string(), "Une.\n\nDeux.".to_string())]);
    assert!(fx.ctx.state.alert_seen("t:a1").unwrap() && fx.ctx.state.alert_seen("t:a2").unwrap());
    assert_eq!(fx.ctx.state.interruptions_on(at(7, 0, 0).date()).unwrap(), 1);
}

#[tokio::test]
async fn long_alert_list_not_truncated() {
    let mut fx = fixture("JB_A10", &[], vec![]).await;
    // Liste au-delà de la borne d'un appel en conversation.
    let all: Vec<Value> = (0..10).map(|i| alert(&format!("a{i}"), &format!("{i}{}", "x".repeat(900)), false)).collect();
    *fx.rec.alerts.lock().unwrap() = Value::Array(all).to_string();
    run(Job::Alerts, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw).len(), 1);
    assert!(fx.ctx.state.alert_seen("t:a9").unwrap());
}

// --- tentacule privé ---

const PRIVATE: &str = "Suivi : 42 % sur le sujet confidentiel";

/// Remplace les tentacules par « t » (public, sauf si `public` est faux) puis « p » (privé) ;
/// renvoie l'enregistreur de « p ».
async fn with_private(fx: &mut Fx, env: &str, public: bool) -> Arc<Rec> {
    let (url_p, rec_p) = spawn_fake(env).await;
    *rec_p.brief.lock().unwrap() = PRIVATE.into();
    let (url_t, rec_t) = spawn_fake(env).await;
    fx.rec = rec_t;
    let mut entries = vec![TentacleEntry { name: "p".into(), url: url_p, token_env: env.into(), private: true, keywords: vec![] }];
    if public {
        entries.insert(0, TentacleEntry { name: "t".into(), url: url_t, token_env: env.into(), private: false, keywords: vec![] });
    }
    fx.ctx.tentacles = Tentacles::connect(&entries).await;
    rec_p
}

fn requests_text(llm: &Shared) -> String {
    llm.lock().unwrap().requests.iter().map(|r| r.to_string()).collect()
}

#[tokio::test]
async fn private_section_never_reworded_and_appended_verbatim() {
    let mut fx = fixture("JB_P1", &["brief"], vec![text("Un rendez-vous à 10:00.")]).await;
    with_private(&mut fx, "JB_P1", true).await;
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.llm.lock().unwrap().requests.len(), 1);
    assert!(!requests_text(&fx.llm).contains("confidentiel"));
    assert_eq!(sent(&fx.gw), vec![format!("Un rendez-vous à 10:00.\n\n{PRIVATE}")]);
}

#[tokio::test]
async fn only_private_sections_call_no_engine() {
    let mut fx = fixture("JB_P2", &["brief"], vec![text("reformulé")]).await;
    with_private(&mut fx, "JB_P2", false).await;
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert!(fx.llm.lock().unwrap().requests.is_empty());
    assert_eq!(sent(&fx.gw), vec![format!("Brief du matin\n\n{PRIVATE}")]);
}

#[tokio::test]
async fn private_section_kept_out_of_suggestion_context() {
    let mut fx = fixture("JB_P3", &["suggestion"], vec![text("RIEN")]).await;
    with_private(&mut fx, "JB_P3", true).await;
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    let req = requests_text(&fx.llm);
    assert!(req.contains("rendez-vous") && !req.contains("confidentiel"));
}

#[tokio::test]
async fn suggestion_never_calls_private_brief_section() {
    let mut fx = fixture("JB_P4", &["suggestion"], vec![text("RIEN")]).await;
    let p = with_private(&mut fx, "JB_P4", true).await;
    run(Job::Suggestion, at(7, 15, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.rec.brief_calls.load(Ordering::SeqCst), 1);
    assert_eq!(p.brief_calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn muted_brief_reads_no_section() {
    let mut fx = fixture("JB_P5", &[], vec![]).await;
    let p = with_private(&mut fx, "JB_P5", true).await;
    fx.ctx.state.add_quiet(at(7, 6, 0), at(7, 12, 0), QuietScope::Briefs).unwrap();
    run(Job::MorningBrief, at(7, 7, 30), &mut fx.ctx).await.unwrap();
    assert!(sent(&fx.gw).is_empty());
    assert_eq!(p.brief_calls.load(Ordering::SeqCst), 0);
    assert_eq!(fx.rec.brief_calls.load(Ordering::SeqCst), 0);
}

// --- journal des réponses ---

fn kairos_log(s: &State) -> Vec<String> {
    s.chat_since(None, 200).unwrap().into_iter().filter(|e| e.role == "kairos").map(|e| e.text).collect()
}

#[tokio::test]
async fn reply_logged_once_when_sent() {
    let fx = fixture("JL_1", &[], vec![]).await;
    deliver("Réponse.", at(7, 15, 0), &fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Réponse.".to_string()]);
    assert_eq!(kairos_log(&fx.ctx.state), vec!["Réponse.".to_string()]);
}

#[tokio::test]
async fn reply_logged_once_when_send_fails_then_retried() {
    let mut fx = fixture("JL_2", &[], vec![]).await;
    fx.gw.fail.store(true, Ordering::SeqCst);
    deliver("Réponse.", at(7, 15, 0), &fx.ctx).await.unwrap();
    assert_eq!(kairos_log(&fx.ctx.state), vec!["Réponse.".to_string()]);
    // Nouvel échec au vidage : toujours une seule entrée.
    run(Job::Flush, at(7, 15, 1), &mut fx.ctx).await.unwrap();
    fx.gw.fail.store(false, Ordering::SeqCst);
    run(Job::Flush, at(7, 15, 2), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw), vec!["Réponse.".to_string()]);
    assert_eq!(kairos_log(&fx.ctx.state), vec!["Réponse.".to_string()]);
}

#[tokio::test]
async fn ack_logged_even_when_send_fails() {
    let fx = fixture("JL_3", &[], vec![]).await;
    fx.gw.fail.store(true, Ordering::SeqCst);
    assert!(say(&fx.ctx.outbound, &fx.ctx.state, "Je regarde.").await.is_err());
    assert_eq!(kairos_log(&fx.ctx.state), vec!["Je regarde.".to_string()]);
}

#[tokio::test]
async fn held_brief_logged_once_at_flush_with_retry_mixed() {
    let mut fx = fixture("JL_4", &[], vec![]).await;
    fx.ctx.cfg.schedule.morning = t(10, 0);
    // Jour sur site : le brief est retenu, pas encore journalisé.
    run(Job::MorningBrief, at(4, 10, 0), &mut fx.ctx).await.unwrap();
    assert!(kairos_log(&fx.ctx.state).is_empty());
    fx.gw.fail.store(true, Ordering::SeqCst);
    deliver("Réponse.", at(4, 10, 5), &fx.ctx).await.unwrap();
    run(Job::Flush, at(4, 17, 30), &mut fx.ctx).await.unwrap();
    fx.gw.fail.store(false, Ordering::SeqCst);
    run(Job::Flush, at(4, 17, 31), &mut fx.ctx).await.unwrap();
    assert_eq!(sent(&fx.gw).len(), 1);
    assert_eq!(kairos_log(&fx.ctx.state), vec!["Réponse.".to_string(), "Brief du matin\n\n- rendez-vous (matin) à 10:00".to_string()]);
}

// --- Nuit de la mémoire ---

const NIGHT_USAGES: &[&str] = &["memoire_extraction", "memoire_condensation"];

fn etat(rec: &Rec, etape: &str) -> (Option<String>, String) {
    rec.nuit.lock().unwrap().get(etape).cloned().unwrap_or_default()
}

fn set_etat(rec: &Rec, etape: &str, jour: &str) {
    rec.nuit.lock().unwrap().insert(etape.into(), (Some(jour.into()), "ok".into()));
}

fn episode(rec: &Rec, jour: &str, texte: &str) {
    rec.episodes.lock().unwrap().entry(jour.into()).or_default().push(texte.into());
}

fn lot(periode: &str, complement: bool) -> Value {
    json!({
        "niveau": "semaine", "periode": periode, "complement": complement, "empreinte": format!("e-{periode}"),
        "elements": [
            {"nature": "synthese", "ref": "3", "date": "2026-10-05", "texte": "Semaine calme."},
            {"nature": "conversation", "ref": "9", "date": "2026-10-06", "texte": "Course du matin décidée."}
        ]
    })
}

fn applied_days(rec: &Rec) -> Vec<String> {
    rec.applied.lock().unwrap().iter().map(|a| a["jour"].as_str().unwrap().to_string()).collect()
}

#[tokio::test]
async fn skipped_days_are_caught_up_next_night() {
    // Aucune réponse scriptée : le moteur échoue au premier appel.
    let mut fx = fixture("JB_N1", NIGHT_USAGES, vec![]).await;
    set_etat(&fx.rec, "extraction", "2026-10-30");
    episode(&fx.rec, "2026-10-31", "Je cours le samedi.");
    episode(&fx.rec, "2026-11-08", "J'habite à Ville-A.");
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert!(applied_days(&fx.rec).is_empty());
    assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-10-30".into()), "modele_indisponible".into()));

    // Nuit suivante : au plus sept jours, plus anciens d'abord.
    let hint = text(r#"[{"key": "sport", "value": "course"}]"#);
    fx.llm.lock().unwrap().replies.extend([hint.clone(), hint]);
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(applied_days(&fx.rec), vec!["2026-10-31"]);
    assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-11-06".into()), "ok".into()));
    run(Job::Summaries, at(10, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(applied_days(&fx.rec), vec!["2026-10-31", "2026-11-08"]);
    assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-11-09".into()), "ok".into()));
}

#[tokio::test]
async fn skipped_step_is_recorded() {
    let mut fx = fixture("JB_N2", &[], vec![]).await;
    set_etat(&fx.rec, "extraction", "2026-11-07");
    episode(&fx.rec, "2026-11-08", "J'habite à Ville-A.");
    fx.rec.lots.lock().unwrap().push(lot("2026-W41", false));
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-11-07".into()), "modele_indisponible".into()));
    assert_eq!(etat(&fx.rec, "condensation").1, "modele_indisponible");
    assert_eq!(etat(&fx.rec, "oubli").1, "ok");
    assert!(fx.rec.condensed.lock().unwrap().is_empty());
}

#[tokio::test]
async fn night_runs_extraction_condensation_forget() {
    let replies = vec![
        text("```json\n[{\"key\": \"ville\", \"value\": \"Ville-A\"}]\n```"),
        text("  Semaine calme, course du matin décidée le 06/10.  "),
    ];
    let mut fx = fixture("JB_N3", NIGHT_USAGES, replies).await;
    episode(&fx.rec, "2026-11-08", "J'habite à Ville-A.");
    fx.rec.lots.lock().unwrap().push(lot("2026-W41", true));
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();

    assert_eq!(
        *fx.rec.applied.lock().unwrap(),
        vec![json!({"jour": "2026-11-08", "indices": [{"key": "ville", "value": "Ville-A"}]})]
    );
    assert_eq!(
        *fx.rec.condensed.lock().unwrap(),
        vec![json!({"periode": "2026-W41", "texte": "Semaine calme, course du matin décidée le 06/10.", "empreinte": "e-2026-W41"})]
    );
    for e in ["extraction", "condensation", "oubli"] {
        assert_eq!(etat(&fx.rec, e).1, "ok", "{e}");
    }
    assert_eq!(etat(&fx.rec, "extraction").0.as_deref(), Some("2026-11-08"));
    let calls = fx.rec.night_calls.lock().unwrap().clone();
    let pos = |t: &str| calls.iter().position(|c| c == t).unwrap();
    assert!(pos("appliquer") < pos("condenser") && pos("condenser") < pos("oublier"));
    // Le moteur reçoit les épisodes, les clés connues, et la synthèse existante à reprendre.
    let reqs = requests_text(&fx.llm);
    assert!(reqs.contains("J'habite à Ville-A.") && reqs.contains("sport, ville"));
    assert!(reqs.contains("Semaine calme.") && reqs.contains("Course du matin décidée."));
}

#[tokio::test]
async fn bad_model_output_applies_nothing() {
    let replies = vec![text("Il habite à Ville-A."), text("   ")];
    let mut fx = fixture("JB_N4", NIGHT_USAGES, replies).await;
    set_etat(&fx.rec, "extraction", "2026-11-07");
    episode(&fx.rec, "2026-11-08", "J'habite à Ville-A.");
    fx.rec.lots.lock().unwrap().push(lot("2026-W41", false));
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-11-07".into()), "sortie_invalide".into()));
    assert_eq!(etat(&fx.rec, "condensation").1, "sortie_invalide");

    // Hors schéma : valeur non textuelle, champ en trop, objet au lieu d'une liste, liste trop longue.
    let many: Vec<Value> = (0..51).map(|i| json!({"key": "sport", "value": i.to_string()})).collect();
    for bad in [
        r#"[{"key": "ville", "value": 3}]"#.to_string(),
        r#"[{"key": "ville", "value": "Ville-A", "source": "x"}]"#.to_string(),
        r#"{"key": "ville", "value": "Ville-A"}"#.to_string(),
        Value::Array(many).to_string(),
    ] {
        fx.llm.lock().unwrap().replies.push_back(text(&bad));
        run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
        assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-11-07".into()), "sortie_invalide".into()), "{bad}");
    }
    assert!(fx.rec.applied.lock().unwrap().is_empty());
    assert!(fx.rec.condensed.lock().unwrap().is_empty());
}

#[tokio::test]
async fn night_without_model_still_forgets() {
    let mut fx = fixture("JB_N5", &[], vec![]).await;
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.rec.night_calls.lock().unwrap().iter().filter(|c| *c == "oublier").count(), 1);
    assert_eq!(etat(&fx.rec, "oubli").1, "ok");
    assert!(fx.llm.lock().unwrap().requests.is_empty());
}

#[tokio::test]
async fn night_twice_is_harmless() {
    let replies = vec![text(r#"[{"key": "ville", "value": "Ville-A"}]"#), text("Semaine calme.")];
    let mut fx = fixture("JB_N6", NIGHT_USAGES, replies).await;
    episode(&fx.rec, "2026-11-08", "J'habite à Ville-A.");
    fx.rec.lots.lock().unwrap().push(lot("2026-W41", false));
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.rec.applied.lock().unwrap().len(), 1);
    assert_eq!(fx.rec.condensed.lock().unwrap().len(), 1);
    assert_eq!(fx.llm.lock().unwrap().requests.len(), 2);
    for e in ["extraction", "condensation", "oubli"] {
        assert_eq!(etat(&fx.rec, e).1, "ok", "{e}");
    }
}

#[tokio::test]
async fn unsummarized_day_stays_pending() {
    let mut fx = fixture("JB_N7", NIGHT_USAGES, vec![]).await;
    set_etat(&fx.rec, "extraction", "2026-11-07");
    episode(&fx.rec, "2026-11-08", "Je cours le samedi.");
    fx.rec.unsummarized_days.lock().unwrap().insert("2026-11-08".into(), 1);
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert!(fx.rec.applied.lock().unwrap().is_empty());
    assert!(fx.llm.lock().unwrap().requests.is_empty());
    assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-11-07".into()), "modele_indisponible".into()));

    // Conversation résumée entre-temps : la journée est extraite la nuit suivante.
    fx.rec.unsummarized_days.lock().unwrap().clear();
    fx.llm.lock().unwrap().replies.push_back(text(r#"[{"key": "sport", "value": "course"}]"#));
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(applied_days(&fx.rec), vec!["2026-11-08"]);
    assert_eq!(etat(&fx.rec, "extraction"), (Some("2026-11-08".into()), "ok".into()));
}

#[tokio::test]
async fn cut_or_oversize_lot_is_not_condensed() {
    // Réponse coupée faute de place : la synthèse serait tronquée.
    let cut = json!({"content": "Semaine calme, course du", "finish_reason": "length"});
    let mut fx = fixture("JB_N8", NIGHT_USAGES, vec![cut]).await;
    fx.rec.lots.lock().unwrap().push(lot("2026-W41", false));
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert!(fx.rec.condensed.lock().unwrap().is_empty());
    assert!(!fx.rec.night_calls.lock().unwrap().contains(&"condenser".to_string()));
    assert_eq!(etat(&fx.rec, "condensation").1, "sortie_invalide");

    // Lot trop long pour le contexte du moteur : écarté sans appel au moteur.
    let mut big = lot("2026-W42", false);
    big["elements"][1]["texte"] = json!("x".repeat(20_000));
    *fx.rec.lots.lock().unwrap() = vec![big];
    fx.llm.lock().unwrap().replies.push_back(text("Synthèse."));
    let before = fx.llm.lock().unwrap().requests.len();
    run(Job::Summaries, at(9, 3, 0), &mut fx.ctx).await.unwrap();
    assert_eq!(fx.llm.lock().unwrap().requests.len(), before);
    assert!(fx.rec.condensed.lock().unwrap().is_empty());
    assert_eq!(etat(&fx.rec, "condensation").1, "sortie_invalide");
}
