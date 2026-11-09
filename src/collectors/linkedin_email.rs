use async_trait::async_trait;
use chrono::Utc;
use mailparse::MailHeaderMap;
use crate::collectors::Collector;
use crate::models::JobOffer;

/// Ingère des alertes emploi reçues par email (IMAP). No-op si IMAP_* absent.
/// Provenance validée en fail-closed : seul l'expéditeur configuré est ingéré.
pub struct EmailAlertCollector;

impl EmailAlertCollector {
    pub fn new() -> Self { Self }
}

struct ImapCfg {
    host: String,
    port: u16,
    user: String,
    pass: String,
    folder: String,
    allowed_sender: String,
}

fn load_cfg() -> Option<ImapCfg> {
    let host = std::env::var("IMAP_HOST").ok().filter(|s| !s.is_empty())?;
    let user = std::env::var("IMAP_USER").ok().filter(|s| !s.is_empty())?;
    let pass = std::env::var("IMAP_PASSWORD").ok().filter(|s| !s.is_empty())?;
    Some(ImapCfg {
        host,
        port: std::env::var("IMAP_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(993),
        user,
        pass,
        folder: std::env::var("IMAP_FOLDER").unwrap_or_else(|_| "INBOX".into()),
        allowed_sender: std::env::var("IMAP_ALLOWED_SENDER").unwrap_or_default(),
    })
}

#[async_trait]
impl Collector for EmailAlertCollector {
    fn name(&self) -> &'static str { "linkedin" }

    async fn fetch(&self) -> anyhow::Result<Vec<JobOffer>> {
        let Some(cfg) = load_cfg() else {
            return Ok(Vec::new()); // IMAP non configuré → no-op silencieux
        };
        // imap est bloquant → on l'isole dans un thread bloquant.
        let bodies = tokio::task::spawn_blocking(move || fetch_bodies(&cfg)).await??;
        let mut offers = Vec::new();
        for b in &bodies {
            offers.extend(parse_alert(b));
        }
        tracing::info!("alertes email : {} email(s), {} offre(s)", bodies.len(), offers.len());
        Ok(offers)
    }
}

/// Connexion IMAP TLS → récupère le text/plain des mails NON LUS, les marque lus.
fn fetch_bodies(cfg: &ImapCfg) -> anyhow::Result<Vec<String>> {
    let tls = native_tls::TlsConnector::builder().build()?;
    let client = imap::connect((cfg.host.as_str(), cfg.port), cfg.host.as_str(), &tls)?;
    let mut session = client.login(&cfg.user, &cfg.pass).map_err(|e| e.0)?;
    session.select(&cfg.folder)?;

    // Fail-closed : sans expéditeur autorisé configuré, on n'ingère rien.
    if cfg.allowed_sender.is_empty() {
        tracing::warn!("alertes email : expéditeur autorisé non configuré → aucune ingestion (fail-closed)");
        let _ = session.logout();
        return Ok(Vec::new());
    }
    let seqs = session.search("UNSEEN")?;
    let mut bodies = Vec::new();
    for seq in &seqs {
        let msgs = session.fetch(seq.to_string(), "RFC822")?;
        for msg in msgs.iter() {
            let Some(raw) = msg.body() else { continue };
            let Ok(parsed) = mailparse::parse_mail(raw) else {
                tracing::warn!("alertes email : mail illisible, laissé non-lu");
                continue;
            };
            // Validation de provenance : on ne fait confiance qu'à l'expéditeur attendu.
            let froms = parsed.headers.get_all_headers("From");
            if !matches!(froms.as_slice(), [h] if sender_allowed(h, &cfg.allowed_sender)) {
                tracing::warn!("alertes email : expéditeur non autorisé, mail ignoré");
                let _ = session.store(seq.to_string(), "+FLAGS (\\Seen)"); // rejet définitif → pas re-évalué
                continue;
            }
            match extract_text_plain(&parsed) {
                Some(t) => {
                    bodies.push(t);
                    let _ = session.store(seq.to_string(), "+FLAGS (\\Seen)"); // marqué lu APRÈS ingestion
                }
                None => tracing::warn!("alertes email : text/plain absent, mail laissé non-lu"),
            }
        }
    }
    let _ = session.logout();
    Ok(bodies)
}

fn extract_text_plain(mail: &mailparse::ParsedMail) -> Option<String> {
    if mail.ctype.mimetype == "text/plain" {
        return mail.get_body().ok();
    }
    for sub in &mail.subparts {
        if let Some(t) = extract_text_plain(sub) {
            return Some(t);
        }
    }
    None
}

/// Provenance fail-closed : l'en-tête `From` porte une seule adresse, égale
/// (casse ignorée) à l'expéditeur attendu. Le nom affiché n'est pas considéré.
fn sender_allowed(from: &mailparse::MailHeader, allowed: &str) -> bool {
    let allowed = allowed.trim();
    let Ok(list) = mailparse::addrparse_header(from) else { return false };
    match list.as_slice() {
        [mailparse::MailAddr::Single(a)] => !allowed.is_empty() && a.addr.trim().eq_ignore_ascii_case(allowed),
        _ => false,
    }
}

/// Extrait l'ID numérique d'une URL LinkedIn `.../jobs/view/<id>?...`.
fn extract_job_id(url: &str) -> Option<String> {
    let after = url.split("/jobs/view/").nth(1)?;
    let id: String = after.chars().take_while(|c| c.is_ascii_digit()).collect();
    if id.is_empty() { None } else { Some(id) }
}

/// Parse le corps text/plain d'une alerte LinkedIn.
/// Bloc par offre (séparé par des tirets) : titre / entreprise / lieu / [Postulez…] / "Voir l'offre : URL".
fn parse_alert(body: &str) -> Vec<JobOffer> {
    let mut offers = Vec::new();
    for block in body.split("----") {
        // URL de l'offre dans le bloc
        let Some(raw_url) = block
            .split_whitespace()
            .find(|w| w.contains("/jobs/view/"))
        else { continue };
        let Some(id) = extract_job_id(raw_url) else { continue };

        // Lignes de contenu avant l'URL (hors vides, "Postulez…", entête d'alerte).
        let mut content: Vec<&str> = Vec::new();
        for l in block.lines() {
            let t = l.trim();
            if t.is_empty() { continue; }
            if t.contains("/jobs/view/") || t.starts_with("Voir l") { break; }
            if t.starts_with("Postulez") || t.starts_with("Votre alerte") || t.starts_with("Vous recevrez") {
                continue;
            }
            content.push(t);
        }
        if content.len() < 3 { continue; }
        let n = content.len();
        offers.push(JobOffer {
            id: format!("linkedin-{id}"),
            source: "linkedin".into(),
            title: content[n - 3].to_string(),
            company: content[n - 2].to_string(),
            description: String::new(),
            url: format!("https://www.linkedin.com/jobs/view/{id}"),
            location: Some(content[n - 1].to_string()),
            country: None,
            salary_min: None,
            salary_max: None,
            currency: None,
            remote: None,
            published_at: None,
            collected_at: Utc::now(),
        });
    }
    offers
}

#[cfg(test)]
mod tests {
    use super::{parse_alert, extract_job_id};

    #[test]
    fn extrait_id() {
        assert_eq!(extract_job_id("https://www.linkedin.com/comm/jobs/view/1000000001?a=b").as_deref(), Some("1000000001"));
        assert_eq!(extract_job_id("https://x/other"), None);
    }

    #[test]
    fn parse_deux_offres() {
        let body = "Votre alerte Emploi a été créée : X.\nVous recevrez des notifications.\n\nSoftware Engineer\nEntreprise Exemple A\nFrance\nVoir l'offre d'emploi : https://www.linkedin.com/comm/jobs/view/1000000001?foo=bar\n\n--------------------\n\nFullstack Software Engineer\nEntreprise Exemple B\nFrance\nPostulez avec un CV et un profil\nVoir l'offre d'emploi : https://www.linkedin.com/comm/jobs/view/1000000002?x=y\n";
        let offers = parse_alert(body);
        assert_eq!(offers.len(), 2);
        assert_eq!(offers[0].title, "Software Engineer");
        assert_eq!(offers[0].company, "Entreprise Exemple A");
        assert_eq!(offers[0].location.as_deref(), Some("France"));
        assert_eq!(offers[0].url, "https://www.linkedin.com/jobs/view/1000000001");
        assert_eq!(offers[1].company, "Entreprise Exemple B");
    }

    fn allowed(from: &str, allowed: &str) -> bool {
        let raw = format!("From: {from}\r\n");
        let (h, _) = mailparse::parse_header(raw.as_bytes()).unwrap();
        super::sender_allowed(&h, allowed)
    }

    #[test]
    fn provenance_fail_closed() {
        let ok = "alertes@example.com";
        assert!(allowed("Alertes <alertes@example.com>", ok));
        assert!(allowed("ALERTES@Example.COM", ok));
        assert!(!allowed("evil <a@b.c>", ok));
        assert!(!allowed("alertes@example.com", "")); // allowed vide → refus
    }

    #[test]
    fn provenance_exact_address() {
        let ok = "alertes@example.com";
        // Nom affiché usurpé.
        assert!(!allowed("\"alertes@example.com\" <x@attaquant.tld>", ok));
        assert!(!allowed("alertes@example.com <x@attaquant.tld>", ok));
        // Domaine suffixé ou préfixé.
        assert!(!allowed("alertes@example.com.attaquant.tld", ok));
        assert!(!allowed("x.alertes@example.com", ok));
        // Plusieurs expéditeurs.
        assert!(!allowed("alertes@example.com, x@attaquant.tld", ok));
    }
}
