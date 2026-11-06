//! Lecture seule de marchés de prédiction publics.

pub mod api;
pub mod store;
pub mod signals;
pub mod poll;
pub mod tools;

/// Faux serveur HTTP local : réponses fixes par préfixe de requête, 500 sinon.
#[cfg(test)]
pub(crate) async fn fake_server(routes: Vec<(&'static str, String)>) -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", l.local_addr().unwrap());
    tokio::spawn(async move {
        loop {
            let Ok((mut s, _)) = l.accept().await else { break };
            let mut buf = vec![0u8; 4096];
            let n = s.read(&mut buf).await.unwrap_or(0);
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            let (code, body) = match routes.iter().find(|(p, _)| req.starts_with(p)) {
                Some((_, b)) => ("200 OK", b.clone()),
                None => ("500 Internal Server Error", "{}".to_string()),
            };
            let resp = format!(
                "HTTP/1.1 {code}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = s.write_all(resp.as_bytes()).await;
        }
    });
    url
}
