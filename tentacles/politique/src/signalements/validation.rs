//! Validation et nettoyage d'un signalement reçu d'un lecteur. Rien n'est visité ni exécuté.

use serde::Deserialize;
use unicode_normalization::UnicodeNormalization;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Brut {
    pub claim: String,
    pub kind: String,
    pub target: Option<String>,
    pub what: String,
    pub basis: String,
    pub url: Option<String>,
    pub ref_who: Option<String>,
    pub ref_when: Option<String>,
    pub ref_what: Option<String>,
    pub ref_in: Option<String>,
    pub fix: String,
    pub oath: bool,
}

#[derive(Debug, PartialEq)]
pub struct Signalement {
    pub claim: String,
    pub kind: String,
    pub target: Option<String>,
    pub what: String,
    pub basis: String,
    pub source: Source,
    pub fix: String,
}

#[derive(Debug, PartialEq)]
pub enum Source {
    Lien(String),
    Ref { who: String, when: String, what: String },
    Carte(String),
}

#[derive(Debug, PartialEq)]
pub struct Refus {
    pub champ: &'static str,
    pub message: &'static str,
}

/// Nombre de faits et de sources de la carte d'une affirmation, si elle existe.
pub trait Cartes {
    fn bornes(&self, claim: &str) -> Option<(usize, usize)>;
}

fn refus(champ: &'static str, message: &'static str) -> Refus {
    Refus { champ, message }
}

/// Caractères invisibles ou de direction du texte (bidi, largeur nulle, BOM).
fn invisible(c: char) -> bool {
    matches!(c, '\u{061C}' | '\u{200B}'..='\u{200F}' | '\u{202A}'..='\u{202E}' | '\u{2060}'..='\u{2064}' | '\u{2066}'..='\u{2069}' | '\u{FEFF}')
}

/// NFC, sans caractères de contrôle (sauf saut de ligne) ni invisibles, sans espaces en bord.
pub fn nettoyer(s: &str) -> String {
    let nfc: String = s.nfc().filter(|c| *c == '\n' || !(c.is_control() || invisible(*c))).collect();
    nfc.trim().to_string()
}

fn borne(
    s: &str,
    min: usize,
    max: usize,
    champ: &'static str,
    message: &'static str,
) -> Result<String, Refus> {
    let s = nettoyer(s);
    let n = s.chars().count();
    if n < min || n > max {
        return Err(refus(champ, message));
    }
    Ok(s)
}

fn requis(
    o: &Option<String>,
    min: usize,
    max: usize,
    champ: &'static str,
    message: &'static str,
) -> Result<String, Refus> {
    borne(o.as_deref().unwrap_or(""), min, max, champ, message)
}

/// Numéro 1-based d'une cible `<prefixe><n>`.
fn numero(cible: &str, prefixe: char) -> Option<usize> {
    let n = cible.strip_prefix(prefixe)?;
    if n.is_empty() || n.len() > 6 || !n.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    n.parse().ok().filter(|n| *n >= 1)
}

/// Adresse https, nom de domaine public, sans identifiants, port absent ou 443.
fn url_valide(u: &str) -> bool {
    if u.chars().count() > 300 || u.chars().any(|c| c.is_whitespace() || c.is_control() || invisible(c)) {
        return false;
    }
    let Some(reste) = u.strip_prefix("https://") else {
        return false;
    };
    let autorite = reste.split(['/', '?', '#']).next().unwrap_or("");
    if autorite.contains('@') || autorite.starts_with('[') {
        return false;
    }
    let (hote, port) = match autorite.split_once(':') {
        Some((h, p)) => (h, Some(p)),
        None => (autorite, None),
    };
    if port.is_some_and(|p| p != "443") {
        return false;
    }
    let hote = hote.trim_end_matches('.').to_lowercase();
    let etiquettes: Vec<&str> = hote.split('.').collect();
    let (Some(tld), true) = (etiquettes.last(), etiquettes.len() >= 2) else {
        return false;
    };
    let bien_forme = etiquettes.iter().all(|e| {
        !e.is_empty()
            && !e.starts_with('-')
            && !e.ends_with('-')
            && e.chars().all(|c| c.is_alphanumeric() || c == '-')
    });
    // un TLD sans lettre est une adresse IP (décimale)
    let tld_ip = !tld.chars().any(|c| c.is_alphabetic());
    let local = matches!(*tld, "local" | "internal" | "lan" | "localhost");
    bien_forme && !tld_ip && !local
}

pub fn valider(b: Brut, cartes: &impl Cartes) -> Result<Signalement, Refus> {
    if !b.oath {
        return Err(refus("oath", "Engagement requis."));
    }
    let Some((faits, sources)) = cartes.bornes(&b.claim) else {
        return Err(refus("claim", "Affirmation inconnue."));
    };
    let kind = b.kind.as_str();
    if !matches!(kind, "verdict" | "fait" | "source" | "formulation" | "classement") {
        return Err(refus("kind", "Type inconnu."));
    }
    let target = match (kind, b.target.as_deref()) {
        ("fait" | "source", None) => return Err(refus("target", "Cible requise.")),
        ("fait", Some(t)) => match numero(t, 'f') {
            Some(n) if n <= faits => Some(t.to_string()),
            _ => return Err(refus("target", "Cible inconnue.")),
        },
        ("source", Some(t)) => match numero(t, 's') {
            Some(n) if n <= sources => Some(t.to_string()),
            _ => return Err(refus("target", "Cible inconnue.")),
        },
        (_, Some(_)) => return Err(refus("target", "Cible non permise.")),
        (_, None) => None,
    };
    let what = borne(&b.what, 30, 400, "what", "Description de 30 à 400 caractères.")?;
    let fix = borne(&b.fix, 10, 300, "fix", "Correction de 10 à 300 caractères.")?;
    let source = match b.basis.as_str() {
        "lien" => {
            let u = b.url.as_deref().unwrap_or("").trim();
            if !url_valide(u) {
                return Err(refus("url", "Adresse https invalide."));
            }
            Source::Lien(u.to_string())
        }
        "ref" => Source::Ref {
            who: requis(&b.ref_who, 2, 80, "ref_who", "Auteur de 2 à 80 caractères.")?,
            when: requis(&b.ref_when, 0, 30, "ref_when", "Date de 30 caractères au plus.")?,
            what: requis(&b.ref_what, 4, 160, "ref_what", "Titre de 4 à 160 caractères.")?,
        },
        "carte" => Source::Carte(requis(&b.ref_in, 4, 160, "ref_in", "Passage de 4 à 160 caractères.")?),
        _ => return Err(refus("basis", "Fondement inconnu.")),
    };
    Ok(Signalement { claim: b.claim, kind: b.kind, target, what, basis: b.basis, source, fix })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct C;
    impl Cartes for C {
        fn bornes(&self, claim: &str) -> Option<(usize, usize)> {
            (claim == "a1").then_some((2, 2))
        }
    }

    fn brut() -> Brut {
        Brut {
            claim: "a1".into(),
            kind: "verdict".into(),
            target: None,
            what: "x".repeat(30),
            basis: "lien".into(),
            url: Some("https://example.com/a".into()),
            ref_who: None,
            ref_when: None,
            ref_what: None,
            ref_in: None,
            fix: "y".repeat(10),
            oath: true,
        }
    }

    fn champ(b: Brut) -> &'static str {
        valider(b, &C).unwrap_err().champ
    }

    fn avec_url(u: &str) -> Brut {
        Brut { url: Some(u.into()), ..brut() }
    }

    #[test]
    fn valide_minimal_lien() {
        let s = valider(brut(), &C).unwrap();
        assert_eq!(s.source, Source::Lien("https://example.com/a".into()));
    }

    #[test]
    fn what_29_refuse() {
        assert_eq!(champ(Brut { what: "x".repeat(29), ..brut() }), "what");
    }

    #[test]
    fn what_400_accepte() {
        assert!(valider(Brut { what: "x".repeat(400), ..brut() }, &C).is_ok());
        assert_eq!(champ(Brut { what: "x".repeat(401), ..brut() }), "what");
    }

    #[test]
    fn emoji_compte_en_caracteres() {
        // 30 émojis = 120 octets mais 30 caractères
        assert!(valider(Brut { what: "😀".repeat(30), ..brut() }, &C).is_ok());
        assert_eq!(champ(Brut { what: "😀".repeat(29), ..brut() }), "what");
    }

    #[test]
    fn controle_retire() {
        assert_eq!(nettoyer("a\u{0007}b"), "ab");
        assert_eq!(nettoyer("  a\nb  "), "a\nb");
        assert_eq!(nettoyer("e\u{0301}"), "\u{e9}");
    }

    #[test]
    fn bidi_retire() {
        assert_eq!(nettoyer("a\u{202E}b\u{2066}c\u{061C}d"), "abcd");
    }

    #[test]
    fn zero_largeur_retire() {
        assert_eq!(nettoyer("a\u{200B}b\u{FEFF}c\u{2060}d"), "abcd");
    }

    #[test]
    fn invisibles_hors_longueur() {
        let what = format!("{}{}", "x".repeat(29), "\u{200B}".repeat(5));
        assert_eq!(champ(Brut { what, ..brut() }), "what");
        let what = format!("{}{}", "x".repeat(30), "\u{202E}".repeat(400));
        assert!(valider(Brut { what, ..brut() }, &C).is_ok());
    }

    #[test]
    fn url_bidi_refusee() {
        for c in ["\u{202E}", "\u{200B}", "\u{FEFF}", "\u{2066}"] {
            assert_eq!(champ(avec_url(&format!("https://example.com/a{c}b"))), "url", "{c:?}");
        }
    }

    #[test]
    fn kind_inconnu() {
        assert_eq!(champ(Brut { kind: "autre".into(), ..brut() }), "kind");
    }

    #[test]
    fn target_requis_pour_fait() {
        assert_eq!(champ(Brut { kind: "fait".into(), ..brut() }), "target");
        let b = Brut { kind: "fait".into(), target: Some("f2".into()), ..brut() };
        assert!(valider(b, &C).is_ok());
        let b = Brut { kind: "source".into(), target: Some("f1".into()), ..brut() };
        assert_eq!(champ(b), "target");
    }

    #[test]
    fn target_interdit_pour_verdict() {
        assert_eq!(champ(Brut { target: Some("f1".into()), ..brut() }), "target");
    }

    #[test]
    fn cible_hors_carte() {
        let b = Brut { kind: "fait".into(), target: Some("f3".into()), ..brut() };
        assert_eq!(champ(b), "target");
        let b = Brut { kind: "fait".into(), target: Some("f0".into()), ..brut() };
        assert_eq!(champ(b), "target");
    }

    #[test]
    fn affirmation_inconnue() {
        assert_eq!(champ(Brut { claim: "zz".into(), ..brut() }), "claim");
    }

    #[test]
    fn oath_faux_refuse() {
        assert_eq!(champ(Brut { oath: false, ..brut() }), "oath");
    }

    #[test]
    fn url_http_refusee() {
        assert_eq!(champ(avec_url("http://example.com/a")), "url");
    }

    #[test]
    fn url_ip_refusee() {
        assert_eq!(champ(avec_url("https://192.0.2.1/x")), "url");
        assert_eq!(champ(avec_url("https://[::1]/x")), "url");
    }

    #[test]
    fn url_localhost_refusee() {
        assert_eq!(champ(avec_url("https://localhost/x")), "url");
        assert_eq!(champ(avec_url("https://localhost./x")), "url");
    }

    #[test]
    fn url_suffixe_local_refuse() {
        for h in ["a.local", "a.internal", "a.lan", "a.LAN"] {
            assert_eq!(champ(avec_url(&format!("https://{h}/x"))), "url", "{h}");
        }
    }

    #[test]
    fn url_identifiants_refuses() {
        assert_eq!(champ(avec_url("https://a:b@example.com")), "url");
    }

    #[test]
    fn url_port_refuse() {
        assert_eq!(champ(avec_url("https://x.fr:8443/a")), "url");
    }

    #[test]
    fn url_443_acceptee() {
        assert!(valider(avec_url("https://x.fr:443/a"), &C).is_ok());
    }

    #[test]
    fn url_trop_longue_ou_blanche_refusee() {
        assert_eq!(champ(avec_url(&format!("https://x.fr/{}", "a".repeat(300)))), "url");
        assert_eq!(champ(avec_url("https://x.fr/a b")), "url");
        assert_eq!(champ(Brut { url: None, ..brut() }), "url");
    }

    #[test]
    fn ref_sans_titre_refusee() {
        let b = Brut {
            basis: "ref".into(),
            ref_who: Some("Auteur".into()),
            ..brut()
        };
        assert_eq!(champ(b), "ref_what");
    }

    #[test]
    fn ref_complete_et_carte() {
        let b = Brut {
            basis: "ref".into(),
            ref_who: Some("Auteur".into()),
            ref_what: Some("Titre".into()),
            ..brut()
        };
        assert!(matches!(valider(b, &C).unwrap().source, Source::Ref { .. }));
        let b = Brut { basis: "carte".into(), ref_in: Some("Passage".into()), ..brut() };
        assert!(matches!(valider(b, &C).unwrap().source, Source::Carte(_)));
        assert_eq!(champ(Brut { basis: "autre".into(), ..brut() }), "basis");
    }

    #[test]
    fn champ_inconnu_refuse() {
        let j = r#"{"claim":"a1","kind":"verdict","what":"x","basis":"lien","fix":"y","oath":true,"cle":"k"}"#;
        assert!(serde_json::from_str::<Brut>(j).is_err());
    }
}
