//! Seul test de ce binaire : l'abonné de journal global n'entre en concurrence avec aucun autre.

use std::io::Write;
use std::sync::{Arc, Mutex};

use axum::body::Body;
use axum::http::{Request, StatusCode};
use kairos_politique::donnees::Donnees;
use kairos_politique::signalements::{limite::Limiteur, public, store::Store};
use serde_json::json;
use tower::ServiceExt;

#[derive(Clone, Default)]
struct Capture(Arc<Mutex<Vec<u8>>>);

impl Write for Capture {
    fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(b);
        Ok(b.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[tokio::test]
async fn journal_sans_contenu() {
    let cap = Capture::default();
    let w = cap.clone();
    tracing_subscriber::fmt().with_writer(move || w.clone()).with_ansi(false).init();

    let dir = std::env::temp_dir().join(format!("politique-journal-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let a = json!({"CLAIMS": [{"id": "a1", "facts": ["f"], "src": [["s", "https://example.org/s"]]}]});
    std::fs::write(dir.join("affirmations.json"), a.to_string()).unwrap();
    let etat = public::PublicState {
        store: Arc::new(Store::open(":memory:").unwrap()),
        limiteur: Arc::new(Limiteur::new(10)),
        donnees: Arc::new(Donnees::new(&dir)),
        plafond_jour: 200,
        now: || 1_700_000_000,
        comptes: Arc::default(),
    };
    let envoyer = |what: &str, cle: &str| {
        let corps = json!({"claim": "a1", "kind": "verdict", "what": what, "basis": "lien",
                           "url": "https://example.com/a", "fix": "y".repeat(10), "oath": true});
        let r = Request::post("/api/signalements")
            .header("content-type", "application/json")
            .header("cookie", format!("{}=cookie-secret", public::COOKIE))
            .header("idempotency-key", cle)
            .body(Body::from(corps.to_string()))
            .unwrap();
        public::router(etat.clone()).oneshot(r)
    };

    assert_eq!(envoyer("MARQUEUR-ECHO court", "cle-idempotence-0001").await.unwrap().status(), StatusCode::BAD_REQUEST);
    let long = "MARQUEUR-ECHO assez long pour passer la borne";
    assert_eq!(envoyer(long, "cle-idempotence-0002").await.unwrap().status(), StatusCode::CREATED);

    let log = String::from_utf8(cap.0.lock().unwrap().clone()).unwrap();
    assert!(log.contains("refuses=1"), "{log}");
    assert!(log.contains("deposes=1"), "{log}");
    for secret in ["MARQUEUR-ECHO", "cookie-secret", "cle-idempotence", "example.com"] {
        assert!(!log.contains(secret), "{secret} dans le journal");
    }
}
