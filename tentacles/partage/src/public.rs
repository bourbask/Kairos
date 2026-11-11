//! Écouteur réservé au serveur web du site partagé : échange d'un jeton contre une
//! session (`POST /cle`) et contrôle d'une session (`GET /verifier`). Aucune réponse ne
//! dit pourquoi un jeton est refusé ; rien n'est journalisé.

use std::sync::Arc;

use axum::{
    Router,
    extract::State,
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};

use crate::store::{Store, Ts};

/// Cookie de session : `__Host-` impose `Secure`, `Path=/` et l'absence de `Domain`.
pub const COOKIE: &str = "__Host-acces";
/// En-tête qui porte le jeton, posé par la page d'entrée du site.
pub const KEY_HEADER: &str = "x-partage-cle";

#[derive(Clone)]
pub struct PublicState {
    pub store: Arc<Store>,
    pub session_ttl: Ts,
    /// Appareils qu'un même lien peut ouvrir.
    pub max_devices: i64,
    pub now: fn() -> Ts,
}

/// La limite de débit est posée par le serveur web, par client : ici, tous les appels
/// arrivent de la même adresse.
pub fn router(state: PublicState) -> Router {
    Router::new().route("/cle", post(redeem)).route("/verifier", get(verify)).with_state(state)
}

fn status(code: StatusCode) -> Response {
    (code, [(header::CACHE_CONTROL, "no-store")]).into_response()
}

async fn redeem(State(s): State<PublicState>, headers: HeaderMap) -> Response {
    let token = headers.get(KEY_HEADER).and_then(|v| v.to_str().ok()).unwrap_or_default();
    match s.store.redeem(token, (s.now)(), s.session_ttl, s.max_devices) {
        Ok(Some(session)) => {
            let cookie = format!("{COOKIE}={session}; Path=/; Max-Age={}; HttpOnly; Secure; SameSite=Lax", s.session_ttl);
            let mut r = status(StatusCode::NO_CONTENT);
            // Valeur hexadécimale émise ici : toujours un en-tête valide.
            r.headers_mut().insert(header::SET_COOKIE, HeaderValue::from_str(&cookie).expect("cookie hexadécimal"));
            r
        }
        _ => status(StatusCode::NOT_FOUND),
    }
}

/// Valeur du cookie de session, s'il est présent.
fn session_of(headers: &HeaderMap) -> Option<&str> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .find_map(|kv| kv.trim().strip_prefix(COOKIE)?.strip_prefix('='))
}

async fn verify(State(s): State<PublicState>, headers: HeaderMap) -> Response {
    let ok = session_of(&headers).is_some_and(|v| s.store.verify(v, (s.now)()).unwrap_or(false));
    status(if ok { StatusCode::NO_CONTENT } else { StatusCode::UNAUTHORIZED })
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request;
    use tower::ServiceExt;

    const TTL: Ts = 30 * 24 * 3600;

    fn state() -> PublicState {
        PublicState { store: Arc::new(Store::memory().unwrap()), session_ttl: TTL, max_devices: 3, now: || 1_000 }
    }

    async fn cle(s: &PublicState, token: Option<&str>) -> Response {
        let mut r = Request::post("/cle");
        if let Some(t) = token {
            r = r.header("X-Partage-Cle", t);
        }
        router(s.clone()).oneshot(r.body(Body::empty()).unwrap()).await.unwrap()
    }

    async fn verifier(s: &PublicState, cookie: Option<&str>) -> StatusCode {
        let mut r = Request::get("/verifier");
        if let Some(c) = cookie {
            r = r.header("cookie", c);
        }
        router(s.clone()).oneshot(r.body(Body::empty()).unwrap()).await.unwrap().status()
    }

    fn cookie_value(r: &Response) -> String {
        let c = r.headers().get(header::SET_COOKIE).unwrap().to_str().unwrap();
        assert!(c.starts_with("__Host-acces="));
        for attr in ["Path=/", "Max-Age=2592000", "HttpOnly", "Secure", "SameSite=Lax"] {
            assert!(c.contains(attr), "{attr}");
        }
        assert!(!c.contains("Domain"));
        c.split(';').next().unwrap().to_string()
    }

    #[tokio::test]
    async fn each_device_gets_its_cookie_until_cap_then_404() {
        let s = state();
        let (_, tok, _) = s.store.create("a", 900, 3600).unwrap();
        let mut cookies = Vec::new();
        for _ in 0..3 {
            let r = cle(&s, Some(&tok)).await;
            assert_eq!(r.status(), StatusCode::NO_CONTENT);
            cookies.push(cookie_value(&r));
        }
        cookies.dedup();
        assert_eq!(cookies.len(), 3, "une session par appareil");
        for c in &cookies {
            assert_eq!(verifier(&s, Some(&format!("autre=1; {c}"))).await, StatusCode::NO_CONTENT);
        }
        let again = cle(&s, Some(&tok)).await;
        assert_eq!(again.status(), StatusCode::NOT_FOUND);
        assert!(again.headers().get(header::SET_COOKIE).is_none());
    }

    #[tokio::test]
    async fn bad_or_missing_token_404() {
        let s = state();
        assert_eq!(cle(&s, None).await.status(), StatusCode::NOT_FOUND);
        assert_eq!(cle(&s, Some("abc")).await.status(), StatusCode::NOT_FOUND);
        let (_, tok, _) = s.store.create("a", 0, 10).unwrap();
        assert_eq!(cle(&s, Some(&tok)).await.status(), StatusCode::NOT_FOUND, "expiré");
    }

    #[tokio::test]
    async fn verifier_refuses_without_valid_session() {
        let s = state();
        assert_eq!(verifier(&s, None).await, StatusCode::UNAUTHORIZED);
        assert_eq!(verifier(&s, Some("__Host-acces=nope")).await, StatusCode::UNAUTHORIZED);
        let (id, tok, _) = s.store.create("a", 900, 3600).unwrap();
        let c1 = cookie_value(&cle(&s, Some(&tok)).await);
        let c2 = cookie_value(&cle(&s, Some(&tok)).await);
        s.store.revoke(id, 950).unwrap();
        assert_eq!(verifier(&s, Some(&c1)).await, StatusCode::UNAUTHORIZED);
        assert_eq!(verifier(&s, Some(&c2)).await, StatusCode::UNAUTHORIZED);
    }
}
