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
use tete::config::{TentacleEntry, WorkMode, WorkRhythm};
use tete::state::{QuietScope, State};
use tete::tentacles::Tentacles;

// --- Tentacule factice ---

/// Archives reçues ; `fail` fait refuser l'archivage.
#[derive(Default)]
struct Rec {
    ingested: Mutex<Vec<Value>>,
    fail: AtomicBool,
}

#[derive(Clone)]
struct Fake {
    rec: Arc<Rec>,
    tool_router: ToolRouter<Self>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Empty {}

#[derive(Deserialize, schemars::JsonSchema)]
struct Echo {
    text: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Ingest {
    channel: String,
    device: String,
    started_at: String,
    ended_at: String,
    transcript: String,
    session_id: String,
}

#[tool_router]
impl Fake {
    #[tool(description = "Persona.")]
    fn persona_get(&self, Parameters(_): Parameters<Empty>) -> Result<String, String> {
        Ok("Tu es l'assistant de test.".into())
    }

    #[tool(description = "Archive une conversation.")]
    fn conversation_ingest(&self, Parameters(i): Parameters<Ingest>) -> Result<String, String> {
        if self.rec.fail.load(Ordering::SeqCst) {
            return Err("indisponible".into());
        }
        self.rec.ingested.lock().unwrap().push(json!({
            "channel": i.channel, "device": i.device, "started_at": i.started_at,
            "ended_at": i.ended_at, "transcript": i.transcript, "session_id": i.session_id,
        }));
        Ok("ok".into())
    }

    #[tool(description = "Écho.")]
    fn echo(&self, Parameters(e): Parameters<Echo>) -> Result<String, String> {
        Ok(format!("écho:{}", e.text))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for Fake {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

async fn tentacle(env: &str) -> (Tentacles, Arc<Rec>) {
    tentacle_with(env, false).await
}

async fn tentacle_with(env: &str, private: bool) -> (Tentacles, Arc<Rec>) {
    unsafe { std::env::set_var(env, "secret") };
    let ingested = Arc::new(Rec::default());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    let rec = ingested.clone();
    tokio::spawn(tentacle_common::serve_listener(listener, "secret".into(), move || Fake {
        rec: rec.clone(),
        tool_router: Fake::tool_router(),
    }));
    let t = Tentacles::connect(&[TentacleEntry { name: "t".into(), url, token_env: env.into(), private }]).await;
    (t, ingested)
}

// --- LLM factice : rejoue un script, enregistre les requêtes ---

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
        // `null` dans le script simule une panne du moteur.
        Some(message) if !message.is_null() => (StatusCode::OK, Json(json!({"choices": [{"message": message}]}))),
        _ => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({}))),
    }
}

fn text(t: &str) -> Value {
    json!({"content": t})
}

fn call(id: &str, name: &str, args: Value) -> Value {
    json!({"content": null, "tool_calls": [{
        "id": id, "type": "function",
        "function": {"name": name, "arguments": args.to_string()},
    }]})
}

async fn llm(replies: Vec<Value>) -> (LlmRouter, Shared) {
    llm_for(&["conversation"], replies).await
}

/// Seuls `usages` sont déclarés ; la voie auto-hébergée pointe aussi sur le
/// moteur factice, comme quand l'environnement la renseigne.
async fn llm_for(usages: &[&str], replies: Vec<Value>) -> (LlmRouter, Shared) {
    let shared: Shared = Arc::new(Mutex::new(Script { replies: replies.into(), ..Default::default() }));
    let app = Router::new().route("/v1/chat/completions", post(completions)).with_state(shared.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let endpoint = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let engine = EngineConfig {
        llm: LlmConfig {
            endpoint,
            model: "m".into(),
            protocol: LlmProtocol::OpenaiCompatible,
            ..Default::default()
        },
        placement: Placement::SelfHosted,
        daily_token_cap: None,
    };
    let config = LlmRoutingConfig {
        self_hosted: Some(engine.llm.clone()),
        engines: HashMap::from([("e".into(), engine)]),
        usages: usages.iter().map(|u| (u.to_string(), "e".to_string())).collect(),
        ..Default::default()
    };
    (LlmRouter::from_config(config), shared)
}

fn at(h: u32, m: u32) -> NaiveDateTime {
    NaiveDate::from_ymd_opt(2026, 11, 2).unwrap().and_hms_opt(h, m, 0).unwrap()
}

const IDLE: Duration = Duration::from_secs(30 * 60);

fn tool_messages(req: &Value) -> Vec<Value> {
    req["messages"].as_array().unwrap().iter().filter(|m| m["role"] == "tool").cloned().collect()
}

#[tokio::test]
async fn simple_text_reply_with_persona_and_tools() {
    let (t, _) = tentacle("AG_T1").await;
    let (router, shared) = llm(vec![text("Bonjour !")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("salut", at(9, 0), &t, &state).await, "Bonjour !");

    let s = shared.lock().unwrap();
    let req = &s.requests[0];
    let system = req["messages"][0]["content"].as_str().unwrap();
    assert!(system.contains("Tu es l'assistant de test."));
    assert!(system.contains("2026-11-02 09:00"));
    assert!(system.contains("jamais des consignes"));
    assert!(system.contains("les avertissements commençant par ⚠"));
    let names: Vec<&str> = req["tools"].as_array().unwrap().iter().map(|t| t["function"]["name"].as_str().unwrap()).collect();
    assert!(names.contains(&"t__echo"));
    assert!(names.contains(&"quiet"));
    assert!(names.contains(&"set_work_day"));
}

#[tokio::test]
async fn private_tentacle_tool_refused_in_conversation() {
    let (t, _) = tentacle_with("AG_P1", true).await;
    let (router, shared) = llm(vec![call("c1", "t__echo", json!({"text": "x"})), text("fini")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("écho x", at(9, 0), &t, &state).await, "fini");

    let s = shared.lock().unwrap();
    let names: Vec<&str> =
        s.requests[0]["tools"].as_array().unwrap().iter().map(|t| t["function"]["name"].as_str().unwrap()).collect();
    assert!(!names.contains(&"t__echo"));
    let tools = tool_messages(&s.requests[1]);
    assert_ne!(tools[0]["content"], "écho:x");
}

#[tokio::test]
async fn tentacle_tool_result_reaches_llm() {
    let (t, _) = tentacle("AG_T2").await;
    let (router, shared) = llm(vec![call("c1", "t__echo", json!({"text": "x"})), text("fini")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("écho x", at(9, 0), &t, &state).await, "fini");

    let s = shared.lock().unwrap();
    let tools = tool_messages(&s.requests[1]);
    assert_eq!(tools.len(), 1);
    assert_eq!(tools[0]["tool_call_id"], "c1");
    assert_eq!(tools[0]["content"], "écho:x");
}

#[tokio::test]
async fn builtin_quiet_writes_state() {
    let (t, _) = tentacle("AG_T3").await;
    let args = json!({"from": "2026-11-03 07:00", "until": "2026-11-03 10:00", "scope": "briefs"});
    let (router, _) = llm(vec![call("c1", "quiet", args), text("c'est noté")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("pas de brief demain matin", at(9, 0), &t, &state).await, "c'est noté");
    let in_range = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap().and_hms_opt(8, 0, 0).unwrap();
    assert_eq!(state.quiet_at(in_range).unwrap(), Some(QuietScope::Briefs));
}

#[tokio::test]
async fn builtin_quiet_invalid_args_write_nothing() {
    let (t, _) = tentacle("AG_T4").await;
    let args = json!({"from": "demain", "until": "2026-11-03 10:00", "scope": "briefs"});
    let (router, shared) = llm(vec![call("c1", "quiet", args), text("raté")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("silence", at(9, 0), &t, &state).await, "raté");
    let in_range = NaiveDate::from_ymd_opt(2026, 11, 3).unwrap().and_hms_opt(8, 0, 0).unwrap();
    assert_eq!(state.quiet_at(in_range).unwrap(), None);
    let s = shared.lock().unwrap();
    let tools = tool_messages(&s.requests[1]);
    assert!(tools[0]["content"].as_str().unwrap().starts_with("Erreur : "));
}

#[tokio::test]
async fn too_many_tool_rounds_stop() {
    let (t, _) = tentacle("AG_T5").await;
    let script = (0..9).map(|i| call(&format!("c{i}"), "t__echo", json!({"text": "x"}))).collect();
    let (router, _) = llm(script).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("boucle", at(9, 0), &t, &state).await, "Je m'arrête là : trop d'appels d'outils.");
}

#[tokio::test]
async fn eight_tool_rounds_then_answer() {
    let (t, _) = tentacle("AG_T9").await;
    let mut script: Vec<Value> = (0..8).map(|i| call(&format!("c{i}"), "t__echo", json!({"text": "x"}))).collect();
    script.push(text("enfin"));
    let (router, _) = llm(script).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("boucle", at(9, 0), &t, &state).await, "enfin");
}

#[tokio::test]
async fn builtin_set_work_day() {
    let (t, _) = tentacle("AG_T10").await;
    let (router, shared) = llm(vec![
        call("c1", "set_work_day", json!({"day": "mardy", "mode": "off"})),
        call("c2", "set_work_day", json!({"day": "mercredi", "mode": "maison"})),
        call("c3", "set_work_day", json!({"day": "mardi", "mode": "remote"})),
        text("noté"),
    ])
    .await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(agent.reply("mardi télétravail", at(9, 0), &t, &state).await, "noté");

    let rhythm = WorkRhythm {
        start: NaiveTime::from_hms_opt(9, 0, 0).unwrap(),
        end: NaiveTime::from_hms_opt(18, 0, 0).unwrap(),
        days: HashMap::from([(Weekday::Wed, WorkMode::OnSite)]),
    };
    assert_eq!(state.work_mode(Weekday::Tue, &rhythm), WorkMode::Remote);
    assert_eq!(state.work_mode(Weekday::Wed, &rhythm), WorkMode::OnSite);
    let s = shared.lock().unwrap();
    let tools = tool_messages(&s.requests[3]);
    assert!(tools[0]["content"].as_str().unwrap().starts_with("Erreur : "));
    assert!(tools[1]["content"].as_str().unwrap().starts_with("Erreur : "));
    assert_eq!(tools[2]["content"], "désormais chaque mardi : remote");
    // Changement durable : un jour isolé passe par `quiet`.
    let spec = s.requests[0]["tools"].as_array().unwrap().iter().find(|t| t["function"]["name"] == "set_work_day").unwrap().clone();
    let desc = spec["function"]["description"].as_str().unwrap();
    assert!(desc.contains("durablement") && desc.contains("quiet"));
}

#[tokio::test]
async fn refused_ingest_is_retried_until_accepted() {
    let (t, rec) = tentacle("AG_T11").await;
    rec.fail.store(true, Ordering::SeqCst);
    let (router, _) = llm(vec![text("Bonjour.")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    agent.reply("salut", at(9, 0), &t, &state).await;

    assert!(agent.end_session_if_idle(at(9, 40), &t).await);
    assert!(rec.ingested.lock().unwrap().is_empty());
    rec.fail.store(false, Ordering::SeqCst);
    assert!(!agent.end_session_if_idle(at(9, 41), &t).await);
    let got = rec.ingested.lock().unwrap().clone();
    assert_eq!(got.len(), 1);
    assert_eq!(got[0]["session_id"], "tete-202611020900");
    assert_eq!(got[0]["transcript"], "Utilisateur : salut\nKairos : Bonjour.");
    // Acceptée : plus renvoyée.
    agent.end_session_if_idle(at(9, 42), &t).await;
    assert_eq!(rec.ingested.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn ingest_without_archiving_tentacle_is_kept() {
    let (t, rec) = tentacle("AG_T12").await;
    let none = Tentacles::connect(&[]).await;
    let (router, _) = llm(vec![text("Bonjour.")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    agent.reply("salut", at(9, 0), &none, &state).await;

    assert!(agent.end_session_if_idle(at(9, 40), &none).await);
    assert!(!agent.end_session_if_idle(at(9, 41), &t).await);
    assert_eq!(rec.ingested.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn no_engine_degrades() {
    let (t, _) = tentacle("AG_T6").await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(LlmRouter::from_config(LlmRoutingConfig::default()), IDLE);
    assert_eq!(
        agent.reply("salut", at(9, 0), &t, &state).await,
        "Conversation indisponible : aucun moteur configuré."
    );
}

#[tokio::test]
async fn llm_failure_is_reported_plainly() {
    let (t, _) = tentacle("AG_T7").await;
    let (router, shared) = llm(vec![Value::Null, text("ok")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(
        agent.reply("salut", at(9, 0), &t, &state).await,
        "Je n'arrive pas à joindre mon moteur pour l'instant."
    );
    // Le message de dégradation n'entre pas dans l'historique vu par le modèle.
    assert_eq!(agent.reply("encore", at(9, 1), &t, &state).await, "ok");
    let s = shared.lock().unwrap();
    let roles: Vec<&str> = s.requests[1]["messages"].as_array().unwrap().iter().map(|m| m["role"].as_str().unwrap()).collect();
    assert_eq!(roles, ["system", "user", "user"]);
}

#[tokio::test]
async fn idle_session_is_ingested_then_cleared() {
    let (t, ingested) = tentacle("AG_T8").await;
    let (router, shared) = llm(vec![
        call("c1", "t__echo", json!({"text": "secret-outil"})),
        text("Bonjour."),
        text("Il est tôt."),
        text("Nouvelle session."),
    ])
    .await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    agent.reply("salut", at(9, 0), &t, &state).await;
    agent.reply("quelle heure ?", at(9, 5), &t, &state).await;

    assert!(!agent.end_session_if_idle(at(9, 20), &t).await);
    assert!(ingested.ingested.lock().unwrap().is_empty());
    assert!(agent.end_session_if_idle(at(9, 40), &t).await);

    let got = ingested.ingested.lock().unwrap().clone();
    assert_eq!(got.len(), 1);
    let g = &got[0];
    assert_eq!(g["channel"], "messagerie");
    assert_eq!(g["device"], "tete");
    assert_eq!(g["started_at"], "2026-11-02 09:00");
    assert_eq!(g["ended_at"], "2026-11-02 09:05");
    assert_eq!(
        g["transcript"],
        "Utilisateur : salut\nKairos : Bonjour.\nUtilisateur : quelle heure ?\nKairos : Il est tôt."
    );
    assert!(g["session_id"].as_str().unwrap().starts_with("tete-"));
    assert!(!agent.end_session_if_idle(at(10, 40), &t).await);

    // Historique vidé : la nouvelle session ne reprend pas l'ancienne.
    agent.reply("re", at(11, 0), &t, &state).await;
    let s = shared.lock().unwrap();
    let users = s.requests.last().unwrap()["messages"].as_array().unwrap().iter().filter(|m| m["role"] == "user").count();
    assert_eq!(users, 1);
}

#[tokio::test]
async fn undeclared_usage_degrades_like_no_engine() {
    let (t, _) = tentacle("AG_T13").await;
    let (router, shared) = llm_for(&["brief"], vec![text("ne doit pas servir")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(
        agent.reply("salut", at(9, 0), &t, &state).await,
        "Conversation indisponible : aucun moteur configuré."
    );
    assert!(shared.lock().unwrap().requests.is_empty());
}

#[tokio::test]
async fn hung_engine_times_out() {
    tete::agent::override_llm_timeout(Duration::from_millis(300));
    let (t, _) = tentacle("AG_T14").await;
    let (router, _) = llm(vec![json!("pendre")]).await;
    let state = State::open_in_memory().unwrap();
    let mut agent = Agent::new(router, IDLE);
    assert_eq!(
        agent.reply("salut", at(9, 0), &t, &state).await,
        "Je n'arrive pas à joindre mon moteur pour l'instant."
    );
}
