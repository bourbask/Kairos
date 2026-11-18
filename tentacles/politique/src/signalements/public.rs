//! Écouteur public : dépôt d'un signalement par un lecteur en session. Ni le corps, ni les
//! en-têtes, ni le cookie ne sont journalisés ; les erreurs ne renvoient jamais le contenu reçu.

use std::sync::Arc;

use axum::{
    Router,
    body::Bytes,
    extract::{DefaultBodyLimit, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::post,
    Json,
};
use serde_json::json;

use super::limite::Limiteur;
use super::store::{Depot, Store};
use super::validation::{Brut, valider};
use crate::donnees::Donnees;

/// Cookie de session posé par le site partagé.
pub const COOKIE: &str = "__Host-acces";
const MAX_CORPS: usize = 4 * 1024;

#[derive(Clone)]
pub struct PublicState {
    pub store: Arc<Store>,
    pub limiteur: Arc<Limiteur>,
    pub donnees: Arc<Donnees>,
    pub plafond_jour: u32,
    pub now: fn() -> i64,
}

pub fn router(etat: PublicState) -> Router {
    Router::new()
        .route("/api/signalements", post(deposer))
        .layer(DefaultBodyLimit::max(MAX_CORPS))
        .layer(axum::middleware::map_response(|mut r: Response| async move {
            r.headers_mut().insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            r
        }))
        .with_state(etat)
}

fn session(headers: &HeaderMap) -> Option<&str> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .find_map(|kv| kv.trim().strip_prefix(COOKIE)?.strip_prefix('='))
        .filter(|v| !v.is_empty())
}

fn idempotence(headers: &HeaderMap) -> Option<&str> {
    let k = headers.get("idempotency-key")?.to_str().ok()?;
    ((16..=64).contains(&k.len()) && k.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')).then_some(k)
}

fn refus(champ: &str, message: &str) -> Response {
    (StatusCode::BAD_REQUEST, Json(json!({"champ": champ, "message": message}))).into_response()
}

async fn deposer(State(e): State<PublicState>, headers: HeaderMap, corps: Bytes) -> Response {
    let Some(session) = session(&headers) else { return StatusCode::UNAUTHORIZED.into_response() };
    let Some(idem) = idempotence(&headers) else {
        return refus("idempotency_key", "En-tête Idempotency-Key requis : 16 à 64 caractères [A-Za-z0-9-].");
    };
    // Texte de l'erreur de décodage non renvoyé : il peut citer le contenu reçu.
    let Ok(brut) = serde_json::from_slice::<Brut>(&corps) else { return refus("corps", "Formulaire illisible.") };
    let s = match valider(brut, &e.donnees.etat()) {
        Ok(s) => s,
        Err(r) => return refus(r.champ, r.message),
    };
    let maintenant = (e.now)();
    let restant = match e.limiteur.verifier(session, maintenant) {
        Ok(r) => r,
        Err(minutes) => return (StatusCode::TOO_MANY_REQUESTS, Json(json!({"attente_min": minutes}))).into_response(),
    };
    match e.store.deposer(&s, idem, maintenant, e.plafond_jour) {
        Ok(recu) => {
            // Un renvoi du même formulaire ne consomme pas de quota.
            let restant = if recu.nouveau {
                e.limiteur.noter(session, maintenant);
                restant - 1
            } else {
                restant
            };
            let corps = json!({"reference": recu.reference, "recu_a": recu.recu_a, "restant": restant});
            (StatusCode::CREATED, Json(corps)).into_response()
        }
        Err(Depot::Plafond) => (StatusCode::SERVICE_UNAVAILABLE, Json(json!({"ferme": true}))).into_response(),
        Err(Depot::Erreur(_)) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::{Arc, OnceLock};

    use axum::body::{Body, to_bytes};
    use axum::http::{Request, StatusCode, header};
    use axum::response::Response;
    use serde_json::{Value, json};
    use tower::ServiceExt;

    use crate::donnees::Donnees;
    use crate::signalements::{limite::Limiteur, store::Store};

    const T0: i64 = 1_700_000_000;

    fn dossier() -> &'static PathBuf {
        static D: OnceLock<PathBuf> = OnceLock::new();
        D.get_or_init(|| {
            let d = std::env::temp_dir().join(format!("politique-signalements-{}", std::process::id()));
            std::fs::create_dir_all(&d).unwrap();
            let a = json!({"CLAIMS": [{"id": "a1", "facts": ["f"], "src": [["s", "https://example.org/s"]]}]});
            std::fs::write(d.join("affirmations.json"), a.to_string()).unwrap();
            d
        })
    }

    fn etat(plafond: u32) -> PublicState {
        PublicState {
            store: Arc::new(Store::open(":memory:").unwrap()),
            limiteur: Arc::new(Limiteur::new(10)),
            donnees: Arc::new(Donnees::new(dossier())),
            plafond_jour: plafond,
            now: || T0,
        }
    }

    fn corps() -> Value {
        json!({"claim": "a1", "kind": "verdict", "what": "x".repeat(30), "basis": "lien",
               "url": "https://example.com/a", "fix": "y".repeat(10), "oath": true})
    }

    async fn envoyer(e: &PublicState, cookie: Option<&str>, idem: Option<&str>, body: String) -> Response {
        let mut r = Request::post("/api/signalements").header("content-type", "application/json");
        if let Some(c) = cookie {
            r = r.header("cookie", format!("autre=1; {COOKIE}={c}"));
        }
        if let Some(i) = idem {
            r = r.header("idempotency-key", i);
        }
        router(e.clone()).oneshot(r.body(Body::from(body)).unwrap()).await.unwrap()
    }

    async fn json_de(r: Response) -> Value {
        serde_json::from_slice(&to_bytes(r.into_body(), 1 << 16).await.unwrap()).unwrap()
    }

    fn cle(n: usize) -> String {
        format!("cle-idempotence-{n:04}")
    }

    fn no_store(r: &Response) {
        assert_eq!(r.headers().get(header::CACHE_CONTROL).unwrap(), "no-store");
    }

    #[tokio::test]
    async fn depot_201() {
        let e = etat(200);
        let r = envoyer(&e, Some("sess"), Some(&cle(1)), corps().to_string()).await;
        assert_eq!(r.status(), StatusCode::CREATED);
        no_store(&r);
        let v = json_de(r).await;
        assert_eq!(v["reference"], "S-231114-001");
        assert_eq!(v["recu_a"], T0);
        assert_eq!(v["restant"], 9);
    }

    #[tokio::test]
    async fn sans_cookie_401() {
        let r = envoyer(&etat(200), None, Some(&cle(1)), corps().to_string()).await;
        assert_eq!(r.status(), StatusCode::UNAUTHORIZED);
        no_store(&r);
    }

    #[tokio::test]
    async fn idempotency_key_invalide_400() {
        let e = etat(200);
        for k in [None, Some("court"), Some("cle avec espaces 0123")] {
            let r = envoyer(&e, Some("sess"), k, corps().to_string()).await;
            assert_eq!(r.status(), StatusCode::BAD_REQUEST);
        }
    }

    #[tokio::test]
    async fn corps_trop_gros_413() {
        let mut b = corps();
        b["what"] = json!("x".repeat(5000));
        let r = envoyer(&etat(200), Some("sess"), Some(&cle(1)), b.to_string()).await;
        assert_eq!(r.status(), StatusCode::PAYLOAD_TOO_LARGE);
        no_store(&r);
    }

    #[tokio::test]
    async fn invalide_400_sans_echo() {
        let e = etat(200);
        let mut b = corps();
        b["what"] = json!("MARQUEUR-ECHO court");
        let r = envoyer(&e, Some("sess"), Some(&cle(1)), b.to_string()).await;
        assert_eq!(r.status(), StatusCode::BAD_REQUEST);
        no_store(&r);
        let v = json_de(r).await;
        assert_eq!(v["champ"], "what");
        assert!(!v.to_string().contains("MARQUEUR-ECHO"));
        // Champ inconnu : l'erreur du décodeur ne doit pas être renvoyée.
        let mut b = corps();
        b["MARQUEUR-ECHO"] = json!(1);
        let r = envoyer(&e, Some("sess"), Some(&cle(2)), b.to_string()).await;
        assert_eq!(r.status(), StatusCode::BAD_REQUEST);
        assert!(!json_de(r).await.to_string().contains("MARQUEUR-ECHO"));
    }

    #[tokio::test]
    async fn onzieme_429() {
        let e = etat(200);
        for n in 0..10 {
            let r = envoyer(&e, Some("sess"), Some(&cle(n)), corps().to_string()).await;
            assert_eq!(r.status(), StatusCode::CREATED);
        }
        let r = envoyer(&e, Some("sess"), Some(&cle(10)), corps().to_string()).await;
        assert_eq!(r.status(), StatusCode::TOO_MANY_REQUESTS);
        no_store(&r);
        assert_eq!(json_de(r).await["attente_min"], 60);
        // Autre session : non limitée.
        let r = envoyer(&e, Some("autre"), Some(&cle(11)), corps().to_string()).await;
        assert_eq!(r.status(), StatusCode::CREATED);
    }

    #[tokio::test]
    async fn plafond_503() {
        let e = etat(1);
        assert_eq!(envoyer(&e, Some("a"), Some(&cle(1)), corps().to_string()).await.status(), StatusCode::CREATED);
        let r = envoyer(&e, Some("b"), Some(&cle(2)), corps().to_string()).await;
        assert_eq!(r.status(), StatusCode::SERVICE_UNAVAILABLE);
        no_store(&r);
        assert_eq!(json_de(r).await, json!({"ferme": true}));
    }

    #[tokio::test]
    async fn idempotence_meme_reference() {
        let e = etat(200);
        let a = json_de(envoyer(&e, Some("sess"), Some(&cle(1)), corps().to_string()).await).await;
        let r = envoyer(&e, Some("sess"), Some(&cle(1)), corps().to_string()).await;
        assert_eq!(r.status(), StatusCode::CREATED);
        assert_eq!(json_de(r).await, a, "même corps, quota non consommé");
        assert_eq!(e.limiteur.verifier("sess", T0), Ok(9));
    }
}
