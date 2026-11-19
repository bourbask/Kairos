//! Validation et nettoyage d'un signalement reçu d'un lecteur. Rien n'est visité ni exécuté.

use serde::Deserialize;
use unicode_normalization::UnicodeNormalization;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Brut {
    #[serde(default)]
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
    pub cible_type: Option<String>,
    pub cible_ref: Option<String>,
    pub extrait: Option<String>,
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
    /// `affirmation`, ou un type de la liste blanche [`TYPES_INFO`].
    pub cible_type: String,
    /// Absents pour une affirmation.
    pub cible_ref: Option<String>,
    pub extrait: Option<String>,
}

/// Types d'information du site signalables hors affirmation.
pub const TYPES_INFO: &[&str] = &[
    "declaration", "promesse", "evaluateur", "scrutin", "manche", "vote", "coherence", "bilan",
    "budget", "ecart", "niveau-de-vie", "revirement", "parti", "camp", "comparaison", "media",
    "argent", "probite", "ville", "rhetorique", "dossier", "aide", "theme", "bareme",
];

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

/// Identifiant `^[a-z0-9][a-z0-9._:-]*$`, 1 à 120 caractères.
fn reference_valide(r: &str) -> bool {
    let premier = r.bytes().next().is_some_and(|b| b.is_ascii_lowercase() || b.is_ascii_digit());
    premier
        && r.len() <= 120
        && r.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b".:_-".contains(&b))
}

pub fn valider(b: Brut, cartes: &impl Cartes) -> Result<Signalement, Refus> {
    if !b.oath {
        return Err(refus("oath", "Engagement requis."));
    }
    let (target, cible_type, cible_ref, extrait) = match b.cible_type.as_deref() {
        None | Some("affirmation") => (affirmation(&b, cartes)?, "affirmation".to_string(), None, None),
        Some(t) => {
            if !TYPES_INFO.contains(&t) {
                return Err(refus("cible_type", "Type d'information inconnu."));
            }
            let r = b.cible_ref.as_deref().filter(|r| reference_valide(r));
            let r = r.ok_or(refus("cible_ref", "Référence de l'information invalide."))?;
            let extrait = requis(&b.extrait, 4, 300, "extrait", "Extrait de l'information manquant ou trop long.")?;
            if !b.claim.is_empty() {
                return Err(refus("claim", "Affirmation non permise."));
            }
            if !matches!(b.kind.as_str(), "fait" | "source" | "formulation" | "autre") {
                return Err(refus("kind", "Type inconnu."));
            }
            if b.target.is_some() {
                return Err(refus("target", "Cible non permise."));
            }
            (None, t.to_string(), Some(r.to_string()), Some(extrait))
        }
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
    Ok(Signalement {
        claim: b.claim,
        kind: b.kind,
        target,
        what,
        basis: b.basis,
        source,
        fix,
        cible_type,
        cible_ref,
        extrait,
    })
}

/// Affirmation connue, type et cible (fait ou source) dans les bornes de sa carte.
/// `cible_ref` et `extrait` éventuels sont ignorés.
fn affirmation(b: &Brut, cartes: &impl Cartes) -> Result<Option<String>, Refus> {
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
    Ok(target)
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
            cible_type: None,
            cible_ref: None,
            extrait: None,
        }
    }

    /// Signalement d'une information hors affirmation, valide.
    fn info() -> Brut {
        Brut {
            claim: String::new(),
            kind: "fait".into(),
            cible_type: Some("promesse".into()),
            cible_ref: Some("p-x-3".into()),
            extrait: Some("Texte affiché".into()),
            ..brut()
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

    #[test]
    fn affirmation_explicite_comme_absente() {
        let a = valider(brut(), &C).unwrap();
        let b = valider(Brut { cible_type: Some("affirmation".into()), ..brut() }, &C).unwrap();
        assert_eq!(a, b);
        assert_eq!((a.cible_type.as_str(), a.cible_ref, a.extrait), ("affirmation", None, None));
        assert_eq!(champ(Brut { cible_type: Some("affirmation".into()), claim: "zz".into(), ..brut() }), "claim");
    }

    #[test]
    fn information_acceptee() {
        let s = valider(info(), &C).unwrap();
        assert_eq!(s.cible_type, "promesse");
        assert_eq!(s.cible_ref.as_deref(), Some("p-x-3"));
        assert_eq!(s.extrait.as_deref(), Some("Texte affiché"));
        assert_eq!((s.claim.as_str(), s.target), ("", None));
        for k in ["fait", "source", "formulation", "autre"] {
            assert!(valider(Brut { kind: k.into(), ..info() }, &C).is_ok(), "{k}");
        }
        for t in ["declaration", "manche", "niveau-de-vie", "bareme"] {
            assert!(valider(Brut { cible_type: Some(t.into()), ..info() }, &C).is_ok(), "{t}");
        }
    }

    #[test]
    fn information_claim_absent_en_json() {
        let j = r#"{"cible_type":"ville","cible_ref":"75056","extrait":"Paris ville","kind":"autre","what":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx","basis":"carte","ref_in":"Passage","fix":"yyyyyyyyyy","oath":true}"#;
        let b: Brut = serde_json::from_str(j).unwrap();
        assert!(valider(b, &C).is_ok());
    }

    #[test]
    fn cible_type_hors_liste() {
        for t in ["inconnu", "", "Promesse", "affirmation "] {
            let r = valider(Brut { cible_type: Some(t.into()), ..info() }, &C).unwrap_err();
            assert_eq!(r, Refus { champ: "cible_type", message: "Type d'information inconnu." }, "{t:?}");
        }
    }

    #[test]
    fn cible_ref_invalide() {
        let attendu = Refus { champ: "cible_ref", message: "Référence de l'information invalide." };
        let long = "a".repeat(121);
        for r in [None, Some(""), Some("-a"), Some("A1"), Some("a b"), Some("a/b"), Some("é"), Some(long.as_str())] {
            let b = Brut { cible_ref: r.map(String::from), ..info() };
            assert_eq!(valider(b, &C).unwrap_err(), attendu, "{r:?}");
        }
        for r in ["a", "0", "quiz:m-12", "an-17-4438", "vote:loi.x_1:2", &"a".repeat(120)] {
            assert!(valider(Brut { cible_ref: Some(r.into()), ..info() }, &C).is_ok(), "{r}");
        }
    }

    #[test]
    fn extrait_borne_et_nettoye() {
        let attendu = Refus { champ: "extrait", message: "Extrait de l'information manquant ou trop long." };
        for e in [None, Some("abc".to_string()), Some("x".repeat(301)), Some(format!("abc{}", "\u{200B}".repeat(5)))] {
            assert_eq!(valider(Brut { extrait: e.clone(), ..info() }, &C).unwrap_err(), attendu, "{e:?}");
        }
        assert!(valider(Brut { extrait: Some("x".repeat(300)), ..info() }, &C).is_ok());
        let s = valider(Brut { extrait: Some("  e\u{0301}t\u{202E}\u{0007}é!  ".into()), ..info() }, &C).unwrap();
        assert_eq!(s.extrait.as_deref(), Some("\u{e9}t\u{e9}!"));
    }

    #[test]
    fn information_kind_restreint() {
        for k in ["verdict", "classement", "inconnu"] {
            let r = valider(Brut { kind: k.into(), ..info() }, &C).unwrap_err();
            assert_eq!(r, Refus { champ: "kind", message: "Type inconnu." }, "{k}");
        }
    }

    #[test]
    fn information_sans_target_ni_claim() {
        assert_eq!(champ(Brut { target: Some("f1".into()), ..info() }), "target");
        assert_eq!(champ(Brut { claim: "a1".into(), ..info() }), "claim");
    }

    #[test]
    fn information_regles_communes() {
        assert_eq!(champ(Brut { oath: false, ..info() }), "oath");
        assert_eq!(champ(Brut { what: "x".repeat(29), ..info() }), "what");
        assert_eq!(champ(Brut { fix: "y".repeat(9), ..info() }), "fix");
        assert_eq!(champ(Brut { url: Some("http://example.com".into()), ..info() }), "url");
        assert_eq!(champ(Brut { basis: "autre".into(), ..info() }), "basis");
    }
}
