//! Jetons d'appareils : générés ici, seule leur empreinte est conservée.

use percent_encoding::{NON_ALPHANUMERIC, utf8_percent_encode};
use sha2::{Digest, Sha256};

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// 32 octets aléatoires, en hexadécimal.
pub fn new_token() -> String {
    let mut buf = [0u8; 32];
    getrandom::fill(&mut buf).expect("source d'aléa système indisponible");
    hex(&buf)
}

/// Empreinte SHA-256 (hex) du jeton, seule forme stockée.
pub fn hash(token: &str) -> String {
    hex(&Sha256::digest(token.as_bytes()))
}

/// Adresse publique de l'API à remettre à l'appareil : refusée si l'API est désactivée
/// ou si l'adresse n'est pas une URL http(s) avec hôte.
pub fn pairing_url(public_url: Option<&str>, app_enabled: bool) -> anyhow::Result<String> {
    if !app_enabled {
        anyhow::bail!("API de l'application désactivée (TETE_APP_BIND absente ou invalide) : appairage inutile");
    }
    let url = public_url.map(str::trim).filter(|u| !u.is_empty())
        .ok_or_else(|| anyhow::anyhow!("TETE_APP_PUBLIC_URL absente : adresse publique de l'API requise"))?;
    match reqwest::Url::parse(url) {
        Ok(u) if matches!(u.scheme(), "http" | "https") && u.host().is_some() => Ok(url.to_string()),
        _ => anyhow::bail!("TETE_APP_PUBLIC_URL invalide : URL http(s) attendue"),
    }
}

/// Adresse d'appairage lue par l'application.
pub fn pairing_uri(url: &str, token: &str) -> String {
    format!("kairos://pair?url={}&token={token}", utf8_percent_encode(url, NON_ALPHANUMERIC))
}

/// QR code en caractères pour le terminal.
pub fn qr_text(data: &str) -> anyhow::Result<String> {
    use qrcode::{QrCode, render::unicode};
    Ok(QrCode::new(data.as_bytes())?.render::<unicode::Dense1x2>().build())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::State;
    use chrono::NaiveDateTime;

    fn at() -> NaiveDateTime {
        NaiveDateTime::parse_from_str("2026-11-08 10:00:00", "%Y-%m-%d %H:%M:%S").unwrap()
    }

    #[test]
    fn hash_is_stable_and_hex() {
        let h = hash("abc");
        assert_eq!(h, hash("abc"));
        assert_eq!(h.len(), 64);
        assert!(h.chars().all(|c| c.is_ascii_hexdigit()));
        assert_eq!(h, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(new_token().len(), 64);
        assert_ne!(new_token(), new_token());
    }

    #[test]
    fn pairing_uri_encodes_url() {
        assert_eq!(
            pairing_uri("http://h:1/x?y", "tok"),
            "kairos://pair?url=http%3A%2F%2Fh%3A1%2Fx%3Fy&token=tok"
        );
    }

    #[test]
    fn pairing_url_requires_app_api_and_http_url() {
        assert_eq!(pairing_url(Some(" https://h.invalid:8100 "), true).unwrap(), "https://h.invalid:8100");
        assert!(pairing_url(Some("http://h.invalid"), true).is_ok());
        assert!(pairing_url(Some("http://h.invalid"), false).is_err());
        for bad in [None, Some(""), Some("h.invalid:8100"), Some("ftp://h.invalid"), Some("javascript:alert(1)"), Some("http://")] {
            assert!(pairing_url(bad, true).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn revoked_device_is_rejected() {
        let s = State::open_in_memory().unwrap();
        let h = hash("t");
        s.add_device("tel", &h, at()).unwrap();
        assert_eq!(s.device_for(&h).unwrap().as_deref(), Some("tel"));
        assert!(s.revoke_device("tel").unwrap());
        assert_eq!(s.device_for(&h).unwrap(), None);
        assert!(!s.revoke_device("tel").unwrap());
    }

    #[test]
    fn duplicate_active_name_refused() {
        let s = State::open_in_memory().unwrap();
        s.add_device("tel", &hash("a"), at()).unwrap();
        let err = s.add_device("tel", &hash("b"), at()).unwrap_err().to_string();
        assert!(err.contains("déjà"), "{err}");
        s.revoke_device("tel").unwrap();
        s.add_device("tel", &hash("c"), at()).unwrap();
        assert_eq!(s.devices().unwrap().len(), 2);
    }
}
