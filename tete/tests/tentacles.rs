use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use serde_json::json;
use tete::config::TentacleEntry;
use tete::tentacles::{TOOL_RESULT_MAX_CHARS, Tentacles};

#[derive(Clone)]
struct Fake {
    label: &'static str,
    tool_router: ToolRouter<Self>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct Any {
    #[serde(default)]
    x: Option<i64>,
}

#[tool_router]
impl Fake {
    fn new(label: &'static str) -> Self {
        Self { label, tool_router: Self::tool_router() }
    }

    #[tool(description = "Section de briefing.")]
    fn brief_section(&self, Parameters(_): Parameters<Any>) -> Result<String, String> {
        Ok(format!("section {}", self.label))
    }

    #[tool(description = "Écho.")]
    fn echo(&self, Parameters(a): Parameters<Any>) -> Result<String, String> {
        Ok(format!("{}:{}", self.label, a.x.unwrap_or(0)))
    }

    #[tool(description = "Gros résultat.")]
    fn big(&self, Parameters(_): Parameters<Any>) -> Result<String, String> {
        Ok("x".repeat(20_000))
    }

    #[tool(description = "Échec.")]
    fn fail(&self, Parameters(_): Parameters<Any>) -> Result<String, String> {
        Err("boom".into())
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for Fake {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

async fn spawn(label: &'static str, token: &str) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, token.into(), move || Fake::new(label)));
    url
}

fn entry(name: &str, url: &str, env: &str) -> TentacleEntry {
    TentacleEntry { name: name.into(), url: url.into(), token_env: env.into(), private: false }
}

fn set(env: &str, v: &str) {
    unsafe { std::env::set_var(env, v) };
}

#[tokio::test]
async fn specs_and_calls() {
    set("TT_OK", "secret");
    let (ua, ub) = (spawn("a", "secret").await, spawn("b", "secret").await);
    let t = Tentacles::connect(&[entry("a", &ua, "TT_OK"), entry("b", &ub, "TT_OK")]).await;
    let names: Vec<_> = t.tool_specs().into_iter().map(|s| s.name).collect();
    assert!(names.contains(&"a__brief_section".to_string()));
    assert!(names.contains(&"b__brief_section".to_string()));
    let spec = t.tool_specs().into_iter().find(|s| s.name == "a__echo").unwrap();
    assert_eq!(spec.description, "Écho.");
    assert!(spec.input_schema.is_object());

    assert_eq!(t.call("a__echo", json!({"x": 1})).await.unwrap(), "a:1");
    assert!(t.call("a__nope", json!({})).await.is_err());
    assert!(t.call("zz__echo", json!({})).await.is_err());
    assert!(t.call("a__fail", json!({})).await.is_err());

    let r = t.call_conventional("brief_section", json!({})).await;
    let got: Vec<_> = r.iter().map(|(n, r)| (n.as_str(), r.clone().unwrap())).collect();
    assert_eq!(got, vec![("a", "section a".to_string()), ("b", "section b".to_string())]);
}

#[tokio::test]
async fn unreachable_and_bad_token_are_absent() {
    set("TT_OK2", "secret");
    set("TT_BAD", "wrong");
    let good = spawn("a", "secret").await;
    let bad_tok = spawn("c", "secret").await;
    let closed = {
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        format!("http://{}/mcp", l.local_addr().unwrap())
    };
    let t = Tentacles::connect(&[
        entry("a", &good, "TT_OK2"),
        entry("closed", &closed, "TT_OK2"),
        entry("c", &bad_tok, "TT_BAD"),
        entry("d", &good, "TT_UNSET_VAR"),
    ])
    .await;
    let names: Vec<_> = t.tool_specs().into_iter().map(|s| s.name).collect();
    assert!(names.iter().all(|n| n.starts_with("a__")) && !names.is_empty());
}

#[tokio::test]
async fn refresh_reconnects_absent() {
    set("TT_OK3", "secret");
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    drop(l);
    let url = format!("http://{addr}/mcp");
    let mut t = Tentacles::connect(&[entry("a", &url, "TT_OK3")]).await;
    assert!(t.tool_specs().is_empty());
    let l = tokio::net::TcpListener::bind(addr).await.unwrap();
    tokio::spawn(tentacle_common::serve_listener(l, "secret".into(), || Fake::new("a")));
    t.refresh().await;
    assert!(!t.tool_specs().is_empty());
}

#[tokio::test]
async fn big_result_is_truncated() {
    set("TT_OK4", "secret");
    let u = spawn("a", "secret").await;
    let t = Tentacles::connect(&[entry("a", &u, "TT_OK4")]).await;
    let out = t.call("a__big", json!({})).await.unwrap();
    assert!(out.ends_with("… [tronqué]"));
    assert!(out.chars().count() <= TOOL_RESULT_MAX_CHARS + "… [tronqué]".chars().count());
}

#[tokio::test]
async fn private_tentacle_tools_hidden_but_callable() {
    set("TT_PRIV", "secret");
    let (ua, ub) = (spawn("a", "secret").await, spawn("p", "secret").await);
    let p = TentacleEntry { private: true, ..entry("p", &ub, "TT_PRIV") };
    let t = Tentacles::connect(&[entry("a", &ua, "TT_PRIV"), p]).await;
    let names: Vec<_> = t.tool_specs().into_iter().map(|s| s.name).collect();
    assert!(names.contains(&"a__echo".to_string()));
    assert!(!names.iter().any(|n| n.starts_with("p__")), "{names:?}");
    assert_eq!(t.private_names(), vec!["p".to_string()]);
    assert_eq!(t.call("p__echo", json!({"x": 2})).await.unwrap(), "p:2");
}
