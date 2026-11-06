use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use serde_json::{Value, json};
use tete::commands::{Command, parse, run};
use tete::config::TentacleEntry;
use tete::tentacles::Tentacles;

#[test]
fn parses_commands() {
    let ok = |m: &str| match parse(m) {
        Some(Ok(c)) => Some(c),
        other => panic!("{m:?} : {other:?}"),
    };
    let usage = |m: &str| assert!(matches!(parse(m), Some(Err(u)) if u.starts_with("Usage")), "{m:?} : {:?}", parse(m));
    assert_eq!(
        ok("projection 2 : hausse = faire le plein ; baisse = attendre"),
        Some(Command::Projection { target: "2".into(), up: "faire le plein".into(), down: "attendre".into() })
    );
    assert_eq!(
        ok("projection 2 : hausse = a ; baisse = b ;"),
        Some(Command::Projection { target: "2".into(), up: "a".into(), down: "b".into() })
    );
    assert_eq!(ok("Suivre 1,3"), Some(Command::Follow(vec![1, 3])));
    assert_eq!(ok("  SUIVRE   1 , 3 "), Some(Command::Follow(vec![1, 3])));
    assert_eq!(parse("je vais suivre ça"), None);
    assert_eq!(parse("Suivre la recette de la tarte"), None);
    assert_eq!(ok("Ne plus suivre 4"), Some(Command::Unfollow(4)));
    assert_eq!(parse("ne plus suivre ce feuilleton"), None);
    assert_eq!(
        ok("Scénario Hiver Doux : 1+, 4-"),
        Some(Command::Scenario { name: "Hiver Doux".into(), links: "1+, 4-".into() })
    );
    assert_eq!(ok("scenario a: 1+"), Some(Command::Scenario { name: "a".into(), links: "1+".into() }));
    assert_eq!(
        ok("PROJECTION Hiver doux : baisse = B ; hausse = H"),
        Some(Command::Projection { target: "Hiver doux".into(), up: "H".into(), down: "B".into() })
    );
    assert_eq!(ok("Marchés"), Some(Command::List));
    assert_eq!(ok("marches "), Some(Command::List));
    assert_eq!(parse("les marchés"), None);
    // Mot-clé reconnu, forme incorrecte : réponse d'usage, jamais de moteur.
    for m in [
        "suivre",
        "suivre 1, deux",
        "ne plus suivre",
        "ne plus suivre 2 3",
        "scénario",
        "scénario : 1+",
        "scenario sans maillons",
        "projection",
        "projection 2 : hausse = faire le plein",
        "projection 2 hausse = a ; baisse = b",
    ] {
        usage(m);
    }
}

// --- Tentacule factice : renvoie l'outil appelé et ses arguments ---

#[derive(Clone)]
struct Fake {
    tool_router: ToolRouter<Self>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Any(serde_json::Map<String, Value>);

#[tool_router]
impl Fake {
    #[tool(description = "f")]
    fn follow(&self, Parameters(a): Parameters<Any>) -> Result<String, String> {
        Ok(json!({"follow": a.0}).to_string())
    }
    #[tool(description = "u")]
    fn unfollow(&self, Parameters(a): Parameters<Any>) -> Result<String, String> {
        Ok(json!({"unfollow": a.0}).to_string())
    }
    #[tool(description = "s")]
    fn scenario(&self, Parameters(a): Parameters<Any>) -> Result<String, String> {
        Ok(json!({"scenario": a.0}).to_string())
    }
    #[tool(description = "p")]
    fn projection(&self, Parameters(a): Parameters<Any>) -> Result<String, String> {
        Ok(json!({"projection": a.0}).to_string())
    }
    #[tool(description = "l")]
    fn followed_list(&self) -> Result<String, String> {
        Err("liste vide".into())
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for Fake {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[tokio::test]
async fn run_calls_tentacle_tools() {
    unsafe { std::env::set_var("CMD_T1", "secret") };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "secret".into(), || Fake { tool_router: Fake::tool_router() }));
    let t = Tentacles::connect(&[TentacleEntry { name: "m".into(), url, token_env: "CMD_T1".into(), private: true }]).await;

    let got = |s: String| serde_json::from_str::<Value>(&s).unwrap();
    assert_eq!(got(run(Command::Follow(vec![1, 3]), &t, "m").await), json!({"follow": {"numbers": [1, 3]}}));
    assert_eq!(got(run(Command::Unfollow(2), &t, "m").await), json!({"unfollow": {"number": 2}}));
    assert_eq!(
        got(run(Command::Scenario { name: "a".into(), links: "1+".into() }, &t, "m").await),
        json!({"scenario": {"name": "a", "links": "1+"}})
    );
    let proj = |target: &str| Command::Projection { target: target.into(), up: "H".into(), down: "B".into() };
    assert_eq!(got(run(proj("2"), &t, "m").await), json!({"projection": {"number": 2, "up": "H", "down": "B"}}));
    assert_eq!(
        got(run(proj("Hiver"), &t, "m").await),
        json!({"projection": {"scenario_name": "Hiver", "up": "H", "down": "B"}})
    );
    assert!(run(Command::List, &t, "m").await.contains("liste vide"));
}
