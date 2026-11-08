//! API HTTP de l'application mobile : conversation, fil du jour, décisions.
//! Authentification par jeton d'appareil ; les résultats des tentacules sont
//! traités comme des données non fiables (validés, bornés).

use std::future::Future;
use std::path::{Path as FsPath, PathBuf};
use std::pin::Pin;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, Query, Request, State as Deps},
    body::Body,
    http::{StatusCode, header::{AUTHORIZATION, CONTENT_LENGTH, CONTENT_TYPE}},
    middleware::{Next, from_fn_with_state},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use chrono::{Datelike, NaiveDate, NaiveTime, TimeDelta, Utc};
use kairos::calendar::USER_TZ;
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::{Value, json};

use crate::config::WorkRhythm;
use crate::devices;
use crate::gateway::{Inbound, Inbox, MAX_INPUT_CHARS};
use crate::state::State;
use crate::tentacles::Tentacles;

pub type CallResult = Result<String, String>;
pub type BoxFut<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// Borne d'un résultat de tentacule lu par l'API (au-delà : JSON coupé, donc ignoré).
const MODULE_RESULT_MAX_CHARS: usize = 256 * 1024;
const BODY_MAX_BYTES: usize = 64 * 1024;
const TITLE_MAX: usize = 120;
const BODY_MAX: usize = 500;
const DETAIL_MAX: usize = 8000;
const DAYS_MAX: u32 = 7;
const CHAT_DEFAULT: u32 = 50;
/// Taille maximale des arguments d'une action « fait » (JSON sérialisé).
const DONE_ARGS_MAX: usize = 2048;
/// Nombre maximal de valeurs proposées par le choix d'une action « fait ».
const CHOICES_MAX: usize = 20;
/// Durée de validité du nombre de décisions servi par le statut.
const COUNT_TTL: Duration = Duration::from_secs(60);

/// Accès aux tentacules depuis l'API.
pub trait Modules: Send + Sync {
    /// Appelle `tool` sur chaque tentacule qui l'expose : (nom, résultat).
    fn call_all<'a>(&'a self, tool: &'a str, args: Value) -> BoxFut<'a, Vec<(String, CallResult)>>;
    /// Appelle `tool` sur le tentacule `module`.
    fn call_one<'a>(&'a self, module: &'a str, tool: &'a str, args: Value) -> BoxFut<'a, Result<CallResult, Unreachable>>;
}

/// Pourquoi un tentacule ne peut pas recevoir l'appel.
#[derive(Debug, PartialEq, Eq)]
pub enum Unreachable {
    /// Non configuré, ou n'expose pas l'outil.
    Unknown,
    /// Configuré mais déconnecté.
    Down,
}

// Tentacules privés inclus : ces données vont à l'appareil de l'utilisateur, pas à un moteur.
// Le verrou ne protège que l'échange de l'`Arc` : un appel en cours ne retarde jamais le rafraîchissement.
impl Modules for tokio::sync::RwLock<Arc<Tentacles>> {
    fn call_all<'a>(&'a self, tool: &'a str, args: Value) -> BoxFut<'a, Vec<(String, CallResult)>> {
        Box::pin(async move {
            let t = self.read().await.clone();
            t.call_conventional_limited(tool, args, MODULE_RESULT_MAX_CHARS).await
        })
    }
    fn call_one<'a>(&'a self, module: &'a str, tool: &'a str, args: Value) -> BoxFut<'a, Result<CallResult, Unreachable>> {
        Box::pin(async move {
            let t = self.read().await.clone();
            let name = format!("{module}__{tool}");
            if !t.exposes(&name) {
                return Err(if t.connected(module) == Some(false) { Unreachable::Down } else { Unreachable::Unknown });
            }
            Ok(t.call_limited(&name, args, MODULE_RESULT_MAX_CHARS).await)
        })
    }
}

#[derive(Clone)]
pub struct AppDeps {
    /// Connexion propre à l'API (même base) : n'attend jamais la boucle principale.
    pub state: Arc<Mutex<State>>,
    pub modules: Arc<dyn Modules>,
    pub inbox: Inbox,
    pub rhythm: Arc<WorkRhythm>,
    /// Nombre de décisions en cours et date du calcul (cache du statut).
    pub decision_count: Arc<Mutex<Option<(Instant, usize)>>>,
    /// Dossier de l'APK servi à l'application ; absent = pas de mise à jour proposée.
    pub apk_dir: Option<PathBuf>,
}

impl AppDeps {
    fn db(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn set_count(&self, n: Option<usize>) {
        *self.decision_count.lock().unwrap_or_else(|e| e.into_inner()) = n.map(|n| (Instant::now(), n));
    }

    fn cached_count(&self) -> Option<usize> {
        let c = *self.decision_count.lock().unwrap_or_else(|e| e.into_inner());
        c.filter(|(at, _)| at.elapsed() < COUNT_TTL).map(|(_, n)| n)
    }
}

pub fn app_router(deps: AppDeps) -> Router {
    let api = Router::new()
        .route("/status", get(status))
        .route("/chat", get(chat_get).post(chat_post))
        .route("/days", get(days))
        .route("/decisions", get(decisions))
        .route("/decisions/{module}/{id}", post(decide))
        .route("/tools/{module}/{tool}", post(passthrough))
        .route("/app", get(app_version))
        .route("/app.apk", get(app_apk))
        .layer(from_fn_with_state(deps.clone(), auth))
        .layer(DefaultBodyLimit::max(BODY_MAX_BYTES))
        .with_state(deps);
    Router::new().nest("/app/v1", api)
}

/// Même 401 pour un jeton absent, inconnu ou révoqué.
async fn auth(Deps(d): Deps<AppDeps>, req: Request, next: Next) -> Response {
    let token = req.headers().get(AUTHORIZATION).and_then(|v| v.to_str().ok()).and_then(|v| v.strip_prefix("Bearer "));
    let known = token.is_some_and(|t| matches!(d.db().device_for(&devices::hash(t)), Ok(Some(_))));
    if known { next.run(req).await } else { StatusCode::UNAUTHORIZED.into_response() }
}

fn today() -> NaiveDate {
    Utc::now().with_timezone(&USER_TZ).date_naive()
}

fn cut(s: &mut String, max: usize) {
    if let Some((i, _)) = s.char_indices().nth(max) {
        s.truncate(i);
    }
}

fn cut_opt(s: &mut Option<String>, max: usize) {
    if let Some(s) = s {
        cut(s, max);
    }
}

fn internal(e: anyhow::Error) -> Response {
    tracing::error!("API de l'application : {e}");
    StatusCode::INTERNAL_SERVER_ERROR.into_response()
}

async fn status(Deps(d): Deps<AppDeps>) -> Json<Value> {
    let n = match d.cached_count() {
        Some(n) => n,
        None => fetch_decisions(&d).await.len(),
    };
    Json(json!({"busy": d.inbox.busy(), "decisions": n}))
}

#[derive(Deserialize)]
struct ChatIn {
    text: String,
}

async fn chat_post(Deps(d): Deps<AppDeps>, Json(m): Json<ChatIn>) -> StatusCode {
    if m.text.trim().is_empty() {
        return StatusCode::BAD_REQUEST;
    }
    let text: String = m.text.chars().take(MAX_INPUT_CHARS).collect();
    if d.inbox.push(Inbound { text, from_app: true }) { StatusCode::ACCEPTED } else { StatusCode::SERVICE_UNAVAILABLE }
}

#[derive(Deserialize)]
struct ChatQuery {
    after: Option<i64>,
    limit: Option<u32>,
}

async fn chat_get(Deps(d): Deps<AppDeps>, Query(q): Query<ChatQuery>) -> Response {
    match d.db().chat_since(q.after, q.limit.unwrap_or(CHAT_DEFAULT)) {
        Ok(rows) => Json(rows).into_response(),
        Err(e) => internal(e),
    }
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum Kind {
    Info,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum Tone {
    Neutre,
    Attention,
    Urgent,
}

#[derive(Deserialize, Serialize)]
struct Chip {
    text: String,
    tone: Tone,
}

#[derive(Deserialize, Serialize)]
struct Card {
    id: String,
    kind: Kind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    time: Option<String>,
    title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    body: Option<String>,
    #[serde(default)]
    chips: Vec<Chip>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
    /// Plat d'une carte repas, pour le proposer ailleurs sans lire le titre.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    dish: Option<String>,
    /// Lu brut : une action hors contrat est retirée sans écarter la carte.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    done: Option<Value>,
    /// État connu du module : seul « fait » est transmis, toute autre valeur est retirée.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    state: Option<Value>,
    #[serde(skip_deserializing)]
    module: String,
}

/// Action « fait » d'une carte : un outil `app_*` du module émetteur.
#[derive(Deserialize, Serialize)]
struct Done {
    tool: String,
    args: serde_json::Map<String, Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    choices: Option<DoneChoices>,
}

/// Valeur demandée à l'utilisateur, ajoutée à `args` sous `key`.
#[derive(Deserialize, Serialize)]
struct DoneChoices {
    label: String,
    key: String,
    options: Vec<Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    default: Option<Value>,
}

/// Action conservée seulement si elle vise un outil `app_*` avec des arguments bornés.
fn valid_done(v: Value) -> Option<Value> {
    let mut d: Done = serde_json::from_value(v).ok()?;
    let name_ok = d.tool.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_');
    if !name_ok || !d.tool.starts_with("app_") || d.tool.len() > TITLE_MAX || serde_json::to_string(&d.args).ok()?.len() > DONE_ARGS_MAX {
        return None;
    }
    if let Some(c) = &mut d.choices {
        cut(&mut c.label, TITLE_MAX);
        cut(&mut c.key, TITLE_MAX);
        c.options.truncate(CHOICES_MAX);
        if serde_json::to_string(&(&c.options, &c.default)).ok()?.len() > DONE_ARGS_MAX {
            return None;
        }
    }
    serde_json::to_value(d).ok()
}

#[derive(Deserialize, Serialize)]
struct Choice {
    id: String,
    label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    reason: Option<String>,
}

#[derive(Deserialize, Serialize)]
struct Decision {
    id: String,
    title: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    headline: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    subtitle: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    body: Option<String>,
    proposal: Option<Choice>,
    #[serde(default)]
    alternatives: Vec<Choice>,
    expires: NaiveDate,
    #[serde(skip_deserializing)]
    module: String,
}

fn tidy_choice(c: &mut Choice) {
    cut(&mut c.id, TITLE_MAX);
    cut(&mut c.label, TITLE_MAX);
    cut_opt(&mut c.reason, BODY_MAX);
}

/// Éléments valides de chaque tentacule ; un résultat en erreur ou illisible
/// écarte ce tentacule, un élément hors contrat est écarté seul.
fn gather<T: DeserializeOwned>(results: Vec<(String, CallResult)>, mut tidy: impl FnMut(&mut T, &str)) -> Vec<T> {
    let mut out = Vec::new();
    for (module, res) in results {
        let Some(items) = res.ok().and_then(|t| serde_json::from_str::<Vec<Value>>(&t).ok()) else {
            tracing::warn!(tentacule = %module, "résultat ignoré : erreur ou JSON hors contrat");
            continue;
        };
        for v in items {
            if let Ok(mut it) = serde_json::from_value::<T>(v) {
                tidy(&mut it, &module);
                out.push(it);
            }
        }
    }
    out
}

#[derive(Deserialize)]
struct DaysQuery {
    from: Option<NaiveDate>,
    days: Option<u32>,
}

async fn days(Deps(d): Deps<AppDeps>, Query(q): Query<DaysQuery>) -> Json<Vec<Value>> {
    let from = q.from.unwrap_or_else(today);
    let days: Vec<NaiveDate> = (0..q.days.unwrap_or(1).clamp(1, DAYS_MAX)).map(|i| from + TimeDelta::days(i64::from(i))).collect();
    // Jours en parallèle : la réponse attend un délai d'appel, pas un par jour.
    let fetched = futures::future::join_all(
        days.iter().map(|day| d.modules.call_all("cards", json!({"day": day.format("%Y-%m-%d").to_string()}))),
    )
    .await;
    let mut out = Vec::new();
    for (day, results) in days.into_iter().zip(fetched) {
        let mut cards = gather(results, |c: &mut Card, m| {
            c.module = m.to_string();
            cut(&mut c.id, TITLE_MAX);
            // Heure hors format : la carte rejoint celles sans heure.
            c.time = c.time.as_deref().and_then(|t| NaiveTime::parse_from_str(t, "%H:%M").ok()).map(|t| t.format("%H:%M").to_string());
            cut(&mut c.title, TITLE_MAX);
            cut_opt(&mut c.body, BODY_MAX);
            cut_opt(&mut c.detail, DETAIL_MAX);
            cut_opt(&mut c.dish, TITLE_MAX);
            c.chips.iter_mut().for_each(|ch| cut(&mut ch.text, TITLE_MAX));
            c.done = c.done.take().and_then(valid_done);
            c.state = c.state.take().filter(|v| v == "fait");
        });
        cards.sort_by(|a, b| (a.time.is_none(), &a.time).cmp(&(b.time.is_none(), &b.time)));
        let work = d.db().work_mode(day.weekday(), &d.rhythm).label();
        out.push(json!({"day": day.format("%Y-%m-%d").to_string(), "work": work, "cards": cards}));
    }
    Json(out)
}

async fn fetch_decisions(d: &AppDeps) -> Vec<Decision> {
    let today = today();
    let results = d.modules.call_all("decisions", json!({})).await;
    let mut out: Vec<Decision> = gather(results, |x: &mut Decision, m| {
        x.module = m.to_string();
        cut(&mut x.id, TITLE_MAX);
        cut(&mut x.title, TITLE_MAX);
        // Blanc seul = absent : omis à la sortie.
        x.headline = x.headline.trim().to_string();
        x.subtitle = x.subtitle.trim().to_string();
        cut(&mut x.headline, TITLE_MAX);
        cut(&mut x.subtitle, TITLE_MAX);
        cut_opt(&mut x.body, BODY_MAX);
        x.proposal.iter_mut().chain(x.alternatives.iter_mut()).for_each(tidy_choice);
    });
    out.retain(|x| x.expires >= today);
    out.sort_by_key(|x| x.expires);
    d.set_count(Some(out.len()));
    out
}

async fn decisions(Deps(d): Deps<AppDeps>) -> Json<Vec<Decision>> {
    Json(fetch_decisions(&d).await)
}

#[derive(Deserialize)]
struct DecideIn {
    choice: String,
}

fn unreachable_reply(u: Unreachable) -> (StatusCode, String) {
    match u {
        Unreachable::Unknown => (StatusCode::NOT_FOUND, "module inconnu".to_string()),
        Unreachable::Down => (StatusCode::SERVICE_UNAVAILABLE, "module momentanément injoignable".to_string()),
    }
}

/// Relaie un outil `app_*` d'un tentacule : seuls ces outils sont atteignables, et leur
/// résultat n'est servi que s'il est un JSON complet (donc non tronqué).
async fn passthrough(
    Deps(d): Deps<AppDeps>,
    Path((module, tool)): Path<(String, String)>,
    Json(args): Json<serde_json::Map<String, Value>>,
) -> Response {
    let reply = |code: StatusCode, mut message: String| {
        cut(&mut message, BODY_MAX);
        (code, Json(json!({"message": message}))).into_response()
    };
    if !tool.starts_with("app_") {
        return reply(StatusCode::NOT_FOUND, "outil inconnu".into());
    }
    match d.modules.call_one(&module, &tool, Value::Object(args)).await {
        Err(u) => {
            let (code, m) = unreachable_reply(u);
            reply(code, m)
        }
        Ok(Err(m)) => reply(StatusCode::UNPROCESSABLE_ENTITY, m),
        Ok(Ok(text)) => match serde_json::from_str::<Value>(&text) {
            Ok(v) => Json(v).into_response(),
            Err(_) => reply(StatusCode::BAD_GATEWAY, "réponse du module illisible".into()),
        },
    }
}

/// APK déposé : `kairos-vX.Y.Z.apk` ; plusieurs = la plus haute version.
fn find_apk(dir: &FsPath) -> Option<(String, PathBuf, u64)> {
    std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().into_string().ok()?;
            let version = name.strip_prefix("kairos-v")?.strip_suffix(".apk")?.to_string();
            let parts: Vec<u64> = version.split('.').map(|p| p.parse().ok()).collect::<Option<_>>()?;
            let meta = e.metadata().ok().filter(|m| m.is_file())?;
            (parts.len() == 3).then(|| (parts, version, e.path(), meta.len()))
        })
        .max_by(|a, b| a.0.cmp(&b.0))
        .map(|(_, v, p, n)| (v, p, n))
}

fn current_apk(d: &AppDeps) -> Option<(String, PathBuf, u64)> {
    d.apk_dir.as_deref().and_then(find_apk)
}

async fn app_version(Deps(d): Deps<AppDeps>) -> Response {
    match current_apk(&d) {
        Some((version, _, size)) => Json(json!({"version": version, "size": size})).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn app_apk(Deps(d): Deps<AppDeps>) -> Response {
    let Some((_, path, _)) = current_apk(&d) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    // Taille du fichier ouvert : un remplacement entre le parcours et l'ouverture reste cohérent.
    let opened = async { let f = tokio::fs::File::open(&path).await?; let n = f.metadata().await?.len(); Ok::<_, std::io::Error>((f, n)) };
    match opened.await {
        Ok((f, size)) => (
            [(CONTENT_TYPE, "application/vnd.android.package-archive".to_string()), (CONTENT_LENGTH, size.to_string())],
            Body::from_stream(tokio_util::io::ReaderStream::new(f)),
        )
            .into_response(),
        Err(e) => internal(e.into()),
    }
}

async fn decide(Deps(d): Deps<AppDeps>, Path((module, id)): Path<(String, String)>, Json(b): Json<DecideIn>) -> Response {
    let (code, mut message) = match d.modules.call_one(&module, "decide", json!({"id": id, "choice": b.choice})).await {
        Err(u) => unreachable_reply(u),
        Ok(Ok(m)) => {
            d.set_count(None);
            (StatusCode::OK, m)
        }
        Ok(Err(m)) => (StatusCode::UNPROCESSABLE_ENTITY, m),
    };
    cut(&mut message, BODY_MAX);
    (code, Json(json!({"message": message}))).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use crate::config::{WorkMode, WorkRhythm};
    use axum::body::Body;
    use axum::http::Request;
    use chrono::{NaiveDateTime, Weekday};
    use serde_json::{Value, json};
    use tokio::sync::mpsc::Receiver;
    use tower::ServiceExt;

    /// Tentacules simulés : (module, outil, résultat) ; outil `DOWN` = module configuré mais déconnecté.
    /// Le compteur relève les appels à `call_all`.
    /// Le troisième champ retarde chaque `call_all`.
    struct Fake(Vec<(&'static str, &'static str, CallResult)>, Arc<AtomicUsize>, Duration);

    const DOWN: &str = "*down*";

    fn fake(v: Vec<(&'static str, &'static str, CallResult)>) -> Fake {
        Fake(v, Arc::default(), Duration::ZERO)
    }

    impl Modules for Fake {
        fn call_all<'a>(&'a self, tool: &'a str, _args: Value) -> BoxFut<'a, Vec<(String, CallResult)>> {
            self.1.fetch_add(1, Ordering::SeqCst);
            Box::pin(async move {
                tokio::time::sleep(self.2).await;
                self.0.iter().filter(|(_, t, _)| *t == tool).map(|(m, _, r)| (m.to_string(), r.clone())).collect()
            })
        }
        fn call_one<'a>(&'a self, module: &'a str, tool: &'a str, _args: Value) -> BoxFut<'a, Result<CallResult, Unreachable>> {
            Box::pin(async move {
                if self.0.iter().any(|(m, t, _)| *m == module && *t == DOWN) {
                    return Err(Unreachable::Down);
                }
                self.0.iter().find(|(m, t, _)| *m == module && *t == tool).map(|(_, _, r)| r.clone()).ok_or(Unreachable::Unknown)
            })
        }
    }

    fn at() -> NaiveDateTime {
        NaiveDateTime::parse_from_str("2026-11-08 10:00:00", "%Y-%m-%d %H:%M:%S").unwrap()
    }

    fn deps_with(fake: Fake, cap: usize) -> (AppDeps, Receiver<Inbound>) {
        let state = State::open_in_memory().unwrap();
        state.add_device("tel", &devices::hash("tok"), at()).unwrap();
        let (tx, rx) = tokio::sync::mpsc::channel(cap);
        let rhythm = WorkRhythm { days: [(Weekday::Wed, WorkMode::OnSite)].into(), ..Default::default() };
        let deps = AppDeps {
            state: Arc::new(Mutex::new(state)),
            modules: Arc::new(fake),
            inbox: Inbox::new(tx),
            rhythm: Arc::new(rhythm),
            decision_count: Arc::default(),
            apk_dir: None,
        };
        (deps, rx)
    }

    async fn send(deps: &AppDeps, method: &str, uri: &str, body: Option<Value>, token: Option<&str>) -> (StatusCode, Value) {
        let mut r = Request::builder().method(method).uri(uri);
        if let Some(t) = token {
            r = r.header("authorization", format!("Bearer {t}"));
        }
        let body = match body {
            Some(b) => {
                r = r.header("content-type", "application/json");
                Body::from(b.to_string())
            }
            None => Body::empty(),
        };
        let resp = app_router(deps.clone()).oneshot(r.body(body).unwrap()).await.unwrap();
        let status = resp.status();
        let bytes = axum::body::to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        (status, serde_json::from_slice(&bytes).unwrap_or(Value::Null))
    }

    async fn get(deps: &AppDeps, uri: &str) -> (StatusCode, Value) {
        send(deps, "GET", uri, None, Some("tok")).await
    }

    fn ids(v: &Value) -> Vec<&str> {
        v.as_array().unwrap().iter().map(|c| c["id"].as_str().unwrap()).collect()
    }

    #[tokio::test]
    async fn unauthorized_without_token() {
        let (d, _rx) = deps_with(fake(vec![]), 4);
        assert_eq!(send(&d, "GET", "/app/v1/status", None, None).await.0, StatusCode::UNAUTHORIZED);
        assert_eq!(send(&d, "GET", "/app/v1/status", None, Some("autre")).await.0, StatusCode::UNAUTHORIZED);
        let (s, _) = send(&d, "POST", "/app/v1/chat", Some(json!({"text": "x"})), None).await;
        assert_eq!(s, StatusCode::UNAUTHORIZED);
        let (s, v) = get(&d, "/app/v1/status").await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(v, json!({"busy": false, "decisions": 0}));
    }

    #[tokio::test]
    async fn revoked_device_is_rejected() {
        let (d, _rx) = deps_with(fake(vec![]), 4);
        assert_eq!(get(&d, "/app/v1/chat").await.0, StatusCode::OK);
        assert!(d.state.lock().unwrap().revoke_device("tel").unwrap());
        assert_eq!(get(&d, "/app/v1/chat").await.0, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn chat_post_enqueues_from_app() {
        let (d, mut rx) = deps_with(fake(vec![]), 1);
        let (s, _) = send(&d, "POST", "/app/v1/chat", Some(json!({"text": "  "})), Some("tok")).await;
        assert_eq!(s, StatusCode::BAD_REQUEST);
        let (s, _) = send(&d, "POST", "/app/v1/chat", Some(json!({"text": "salut"})), Some("tok")).await;
        assert_eq!(s, StatusCode::ACCEPTED);
        let (s, _) = send(&d, "POST", "/app/v1/chat", Some(json!({"text": "encore"})), Some("tok")).await;
        assert_eq!(s, StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(rx.try_recv().unwrap(), Inbound { text: "salut".into(), from_app: true });
        let long = "é".repeat(MAX_INPUT_CHARS + 10);
        let (s, _) = send(&d, "POST", "/app/v1/chat", Some(json!({"text": long})), Some("tok")).await;
        assert_eq!(s, StatusCode::ACCEPTED);
        assert_eq!(rx.try_recv().unwrap().text.chars().count(), MAX_INPUT_CHARS);
    }

    #[tokio::test]
    async fn queued_messages_keep_busy_until_all_answered() {
        let (d, _rx) = deps_with(fake(vec![]), 4);
        for t in ["un", "deux"] {
            assert_eq!(send(&d, "POST", "/app/v1/chat", Some(json!({"text": t})), Some("tok")).await.0, StatusCode::ACCEPTED);
        }
        assert_eq!(get(&d, "/app/v1/status").await.1["busy"], true);
        d.inbox.done();
        assert_eq!(get(&d, "/app/v1/status").await.1["busy"], true);
        d.inbox.done();
        assert_eq!(get(&d, "/app/v1/status").await.1["busy"], false);
    }

    #[tokio::test]
    async fn chat_get_after_cursor() {
        let (d, _rx) = deps_with(fake(vec![]), 4);
        let id1 = {
            let s = d.state.lock().unwrap();
            let id1 = s.log_chat("user", "app", "un", at()).unwrap();
            s.log_chat("kairos", "kairos", "deux", at()).unwrap();
            s.log_chat("user", "salon", "trois", at()).unwrap();
            id1
        };
        let (s, v) = get(&d, &format!("/app/v1/chat?after={id1}")).await;
        assert_eq!(s, StatusCode::OK);
        let texts: Vec<_> = v.as_array().unwrap().iter().map(|e| e["text"].as_str().unwrap()).collect();
        assert_eq!(texts, ["deux", "trois"]);
        let (_, v) = get(&d, "/app/v1/chat?limit=1").await;
        assert_eq!(v[0]["text"], "trois");
        assert_eq!(v[0]["source"], "salon");
        assert_eq!(v[0]["role"], "user");
        assert!(v[0]["at"].is_string() && v[0]["id"].is_i64());
    }

    #[tokio::test]
    async fn days_merges_modules_and_sorts_by_time() {
        let a = json!([
            {"id": "a1", "kind": "info", "time": "19:00", "title": "Soir", "extra": 1, "dish": "d".repeat(200), "state": "fait"},
            {"id": "a2", "kind": "info", "title": "À finir", "chips": [{"text": "vite", "tone": "urgent"}], "state": "bizarre"}
        ]);
        let long_id = "i".repeat(200);
        let a = json!([a[0], a[1], {"id": long_id, "kind": "info", "title": "long"}]);
        let b = json!([{"id": "b1", "kind": "info", "time": "08:30", "title": "x".repeat(200), "body": "y".repeat(600),
                        "detail": "z".repeat(9000)}]);
        let (d, _rx) = deps_with(fake(vec![("a", "cards", Ok(a.to_string())), ("b", "cards", Ok(b.to_string()))]), 4);
        // 2026-11-11 est un mercredi (sur site), le 12 un jeudi (absent = repos).
        let (s, v) = get(&d, "/app/v1/days?from=2026-11-11&days=2").await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(v.as_array().unwrap().len(), 2);
        assert_eq!(v[0]["day"], "2026-11-11");
        assert_eq!(v[0]["work"], "sur site");
        assert_eq!(v[1]["work"], "repos");
        let cards = &v[0]["cards"];
        assert_eq!(ids(cards)[..3], ["b1", "a1", "a2"]);
        assert_eq!(cards[3]["id"].as_str().unwrap().chars().count(), 120);
        assert_eq!(cards[0]["module"], "b");
        assert_eq!(cards[1]["module"], "a");
        assert!(cards[1].get("extra").is_none());
        assert_eq!(cards[1]["dish"].as_str().unwrap().chars().count(), 120);
        assert!(cards[0].get("dish").is_none());
        assert_eq!(cards[1]["state"], "fait");
        assert!(cards[2].get("state").is_none(), "état hors contrat retiré");
        assert_eq!(cards[2]["chips"][0]["tone"], "urgent");
        assert_eq!(cards[0]["title"].as_str().unwrap().chars().count(), 120);
        assert_eq!(cards[0]["body"].as_str().unwrap().chars().count(), 500);
        assert_eq!(cards[0]["detail"].as_str().unwrap().chars().count(), 8000);
        // Bornes : au plus 7 jours.
        let (_, v) = get(&d, "/app/v1/days?from=2026-11-09&days=30").await;
        assert_eq!(v.as_array().unwrap().len(), 7);
    }

    #[tokio::test]
    async fn days_are_fetched_concurrently() {
        let mut f = fake(vec![("a", "cards", Ok("[]".into()))]);
        f.2 = Duration::from_millis(300);
        let (d, _rx) = deps_with(f, 4);
        let start = Instant::now();
        let (_, v) = get(&d, "/app/v1/days?from=2026-11-09&days=7").await;
        assert_eq!(v[6]["day"], "2026-11-15");
        // Sept jours en série prendraient 2,1 s.
        assert!(start.elapsed() < Duration::from_millis(1000), "{:?}", start.elapsed());
    }

    #[tokio::test]
    async fn broken_tentacle_cards_are_skipped() {
        let ok = json!([{"id": "c1", "kind": "info", "title": "ok"}, {"id": "c2", "kind": "inconnu", "title": "?"}]);
        let (d, _rx) = deps_with(
            fake(vec![
                ("a", "cards", Ok("pas du json".into())),
                ("b", "cards", Err("boom".into())),
                ("c", "cards", Ok(ok.to_string())),
            ]),
            4,
        );
        let (s, v) = get(&d, "/app/v1/days?from=2026-11-09").await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(ids(&v[0]["cards"]), ["c1"]);
    }

    #[tokio::test]
    async fn decisions_exclude_expired_and_sort() {
        let dec = |id: &str, exp: &str| {
            json!({"id": id, "title": "Dépense à classer", "headline": "12,34 €", "subtitle": "s",
                   "proposal": null, "alternatives": [{"id": "A", "label": "A"}], "expires": exp})
        };
        let long = "i".repeat(200);
        let mut far = dec(&long, "2999-12-01");
        far["alternatives"][0]["id"] = json!(long);
        let list = json!([dec("old", "2000-01-01"), dec("late", "2999-05-01"), dec("soon", "2999-01-01"), far]);
        let (d, _rx) = deps_with(fake(vec![("f", "decisions", Ok(list.to_string()))]), 4);
        let (s, v) = get(&d, "/app/v1/decisions").await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(ids(&v)[..2], ["soon", "late"]);
        assert_eq!(v[2]["id"].as_str().unwrap().chars().count(), 120);
        assert_eq!(v[2]["alternatives"][0]["id"].as_str().unwrap().chars().count(), 120);
        assert_eq!(v[0]["module"], "f");
        assert!(v[0]["proposal"].is_null());
        assert_eq!(get(&d, "/app/v1/status").await.1["decisions"], 3);
    }

    #[tokio::test]
    async fn empty_headline_and_subtitle_are_omitted() {
        let list = json!([
            {"id": "a", "title": "t", "proposal": null, "expires": "2999-01-01"},
            {"id": "b", "title": "t", "headline": "  ", "subtitle": "", "proposal": null, "expires": "2999-01-02"},
            {"id": "c", "title": "t", "headline": "12 €", "subtitle": "s", "proposal": null, "expires": "2999-01-03"}
        ]);
        let (d, _rx) = deps_with(fake(vec![("f", "decisions", Ok(list.to_string()))]), 4);
        let (_, v) = get(&d, "/app/v1/decisions").await;
        for i in 0..2 {
            assert!(v[i].get("headline").is_none() && v[i].get("subtitle").is_none(), "{}", v[i]);
        }
        assert_eq!((&v[2]["headline"], &v[2]["subtitle"]), (&json!("12 €"), &json!("s")));
    }

    #[tokio::test]
    async fn decide_unknown_module_404() {
        let (d, _rx) = deps_with(fake(vec![]), 4);
        let (s, v) = send(&d, "POST", "/app/v1/decisions/zz/x", Some(json!({"choice": "A"})), Some("tok")).await;
        assert_eq!(s, StatusCode::NOT_FOUND);
        assert!(v["message"].is_string());
    }

    #[tokio::test]
    async fn decide_tool_error_is_422() {
        let (d, _rx) = deps_with(fake(vec![("f", "decide", Err("refusé".into())), ("g", "decide", Ok("classé".into()))]), 4);
        let (s, v) = send(&d, "POST", "/app/v1/decisions/f/x", Some(json!({"choice": "A"})), Some("tok")).await;
        assert_eq!(s, StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(v, json!({"message": "refusé"}));
        let (s, v) = send(&d, "POST", "/app/v1/decisions/g/x", Some(json!({"choice": "A"})), Some("tok")).await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(v, json!({"message": "classé"}));
    }

    #[tokio::test]
    async fn status_uses_cached_decision_count() {
        let list = json!([{"id": "x", "title": "t", "proposal": null, "expires": "2999-01-01"}]);
        let f = fake(vec![("f", "decisions", Ok(list.to_string()))]);
        let calls = f.1.clone();
        let (d, _rx) = deps_with(f, 4);
        assert_eq!(get(&d, "/app/v1/status").await.1["decisions"], 1);
        assert_eq!(get(&d, "/app/v1/status").await.1["decisions"], 1);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        // La liste complète rafraîchit le compte : le statut suivant ne rappelle pas.
        get(&d, "/app/v1/decisions").await;
        get(&d, "/app/v1/status").await;
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn decide_invalidates_count() {
        let list = json!([{"id": "x", "title": "t", "proposal": null, "expires": "2999-01-01"}]);
        let f = fake(vec![("f", "decisions", Ok(list.to_string())), ("f", "decide", Ok("ok".into())), ("g", "decide", Err("non".into()))]);
        let calls = f.1.clone();
        let (d, _rx) = deps_with(f, 4);
        get(&d, "/app/v1/status").await;
        // Un refus ne change rien : le compte reste en cache.
        send(&d, "POST", "/app/v1/decisions/g/x", Some(json!({"choice": "A"})), Some("tok")).await;
        get(&d, "/app/v1/status").await;
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        send(&d, "POST", "/app/v1/decisions/f/x", Some(json!({"choice": "A"})), Some("tok")).await;
        get(&d, "/app/v1/status").await;
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn decide_disconnected_module_503() {
        let (d, _rx) = deps_with(fake(vec![("f", DOWN, Ok(String::new()))]), 4);
        let (s, v) = send(&d, "POST", "/app/v1/decisions/f/x", Some(json!({"choice": "A"})), Some("tok")).await;
        assert_eq!(s, StatusCode::SERVICE_UNAVAILABLE);
        assert!(v["message"].is_string());
    }

    async fn tool(d: &AppDeps, module: &str, name: &str) -> (StatusCode, Value) {
        send(d, "POST", &format!("/app/v1/tools/{module}/{name}"), Some(json!({"lot": 1})), Some("tok")).await
    }

    #[tokio::test]
    async fn passthrough_calls_app_tool() {
        let (d, _rx) = deps_with(fake(vec![("f", "app_inventory", Ok(r#"{"zones":[]}"#.into()))]), 4);
        let (s, v) = tool(&d, "f", "app_inventory").await;
        assert_eq!((s, v), (StatusCode::OK, json!({"zones": []})));
    }

    #[tokio::test]
    async fn passthrough_refuses_non_app_tool() {
        let (d, _rx) = deps_with(fake(vec![("f", "stock_set", Ok("{}".into()))]), 4);
        assert_eq!(tool(&d, "f", "stock_set").await.0, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn passthrough_unknown_module_404() {
        let (d, _rx) = deps_with(fake(vec![]), 4);
        assert_eq!(tool(&d, "zz", "app_inventory").await.0, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn passthrough_disconnected_503() {
        let (d, _rx) = deps_with(fake(vec![("f", DOWN, Ok(String::new()))]), 4);
        assert_eq!(tool(&d, "f", "app_inventory").await.0, StatusCode::SERVICE_UNAVAILABLE);
    }

    #[tokio::test]
    async fn passthrough_tool_error_422() {
        let (d, _rx) = deps_with(fake(vec![("f", "app_lot_use", Err("lot inconnu".into()))]), 4);
        let (s, v) = tool(&d, "f", "app_lot_use").await;
        assert_eq!((s, v), (StatusCode::UNPROCESSABLE_ENTITY, json!({"message": "lot inconnu"})));
    }

    #[tokio::test]
    async fn passthrough_non_json_502() {
        let (d, _rx) = deps_with(fake(vec![("f", "app_inventory", Ok("pas du json".into()))]), 4);
        assert_eq!(tool(&d, "f", "app_inventory").await.0, StatusCode::BAD_GATEWAY);
    }

    fn card_with_done(done: Value) -> Value {
        json!([{"id": "c", "kind": "info", "title": "t", "done": done}])
    }

    async fn first_card(cards: Value) -> Value {
        let (d, _rx) = deps_with(fake(vec![("a", "cards", Ok(cards.to_string()))]), 4);
        let (_, v) = get(&d, "/app/v1/days?from=2026-11-09").await;
        v[0]["cards"][0].clone()
    }

    #[tokio::test]
    async fn done_is_kept_with_app_tool() {
        let done = json!({"tool": "app_meal_done", "args": {"day": "2026-11-12", "slot": "soir"},
                          "choices": {"label": "Portions mangées", "key": "portions", "options": [1, 2, 3], "default": 1}});
        let c = first_card(card_with_done(done.clone())).await;
        assert_eq!(c["done"], done);
        // Sans `choices` : omis à la sortie.
        let c = first_card(card_with_done(json!({"tool": "app_lot_use", "args": {"lot": 3}}))).await;
        assert_eq!(c["done"], json!({"tool": "app_lot_use", "args": {"lot": 3}}));
    }

    #[tokio::test]
    async fn done_with_non_app_tool_is_dropped() {
        for done in [
            json!({"tool": "stock_set", "args": {}}),
            json!({"tool": "decide", "args": {}}),
            json!({"tool": "app_x", "args": "pas un objet"}),
            json!("app_x"),
            json!({"tool": "app_X", "args": {}}),
            json!({"tool": "app_a/../b", "args": {}}),
            json!({"tool": "app_é", "args": {}}),
        ] {
            let c = first_card(card_with_done(done.clone())).await;
            assert_eq!(c["id"], "c", "carte écartée pour {done}");
            assert!(c.get("done").is_none(), "{done} conservé");
        }
    }

    #[tokio::test]
    async fn done_args_bounded() {
        let c = first_card(card_with_done(json!({"tool": "app_x", "args": {"k": "v".repeat(DONE_ARGS_MAX)}}))).await;
        assert_eq!(c["id"], "c");
        assert!(c.get("done").is_none());
        let long = "l".repeat(200);
        let opts: Vec<u32> = (0..100).collect();
        let c = first_card(card_with_done(json!({"tool": "app_x", "args": {},
            "choices": {"label": long, "key": "portions", "options": opts, "default": 1}}))).await;
        assert_eq!(c["done"]["choices"]["label"].as_str().unwrap().chars().count(), TITLE_MAX);
        assert_eq!(c["done"]["choices"]["options"].as_array().unwrap().len(), CHOICES_MAX);
        let c = first_card(card_with_done(json!({"tool": "app_x", "args": {},
            "choices": {"label": "l", "key": "k", "options": [1], "default": "d".repeat(DONE_ARGS_MAX)}}))).await;
        assert!(c.get("done").is_none());
    }

    fn apk_deps(files: &[&str]) -> (AppDeps, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        for f in files {
            std::fs::write(dir.path().join(f), b"PK-apk-bytes").unwrap();
        }
        let (mut d, _rx) = deps_with(fake(vec![]), 4);
        d.apk_dir = Some(dir.path().to_path_buf());
        (d, dir)
    }

    #[tokio::test]
    async fn app_version_from_file_name() {
        let (d, _dir) = apk_deps(&["kairos-v0.3.0.apk", "kairos-v0.10.1.apk", "kairos-vx.apk", "notes.txt"]);
        let (s, v) = get(&d, "/app/v1/app").await;
        assert_eq!(s, StatusCode::OK);
        assert_eq!(v, json!({"version": "0.10.1", "size": 12}));
    }

    #[tokio::test]
    async fn app_version_404_without_apk() {
        let (d, _rx) = deps_with(fake(vec![]), 4);
        assert_eq!(get(&d, "/app/v1/app").await.0, StatusCode::NOT_FOUND);
        assert_eq!(get(&d, "/app/v1/app.apk").await.0, StatusCode::NOT_FOUND);
        let (d, _dir) = apk_deps(&["autre.apk"]);
        assert_eq!(get(&d, "/app/v1/app").await.0, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn apk_download_requires_auth() {
        let (d, _dir) = apk_deps(&["kairos-v0.4.0.apk"]);
        assert_eq!(send(&d, "GET", "/app/v1/app.apk", None, None).await.0, StatusCode::UNAUTHORIZED);
        assert_eq!(send(&d, "GET", "/app/v1/app", None, Some("autre")).await.0, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn apk_download_streams_file() {
        let (d, _dir) = apk_deps(&["kairos-v0.4.0.apk"]);
        let req = Request::builder().uri("/app/v1/app.apk").header("authorization", "Bearer tok").body(Body::empty()).unwrap();
        let resp = app_router(d).oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        assert_eq!(resp.headers()["content-type"], "application/vnd.android.package-archive");
        assert_eq!(resp.headers()["content-length"], "12");
        let bytes = axum::body::to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        assert_eq!(&bytes[..], b"PK-apk-bytes");
    }
}
