//! Masquage des identifiants avant tout envoi vers un moteur externe.

use regex::{Captures, Regex};

pub struct Redactor {
    terms: Option<Regex>,
    iban: Regex,
    card: Regex,
    nir: Regex,
    phone: Regex,
    email: Regex,
}

impl Redactor {
    pub fn new(extra_terms: &[String]) -> Redactor {
        let alternatives: Vec<String> = extra_terms
            .iter()
            .filter(|t| !t.trim().is_empty())
            .map(|t| regex::escape(t.trim()))
            .collect();
        let re = |p: &str| Regex::new(p).expect("motif de masquage valide");
        Redactor {
            terms: (!alternatives.is_empty()).then(|| re(&format!("(?i)(?:{})", alternatives.join("|")))),
            iban: re(r"(?i)\b[A-Z]{2}\d{2}(?:[ -]?[A-Z0-9]){11,30}\b"),
            card: re(r"\b\d(?:[ -]?\d){12,18}\b"),
            nir: re(r"(?i)\b[12](?:[ .-]?\d){4}[ .-]?(?:\d{2}|2[AB])(?:[ .-]?\d){8}\b"),
            phone: re(r"(?:(?:\+|\b00)33[ .-]?(?:\(0\)[ .-]?)?[1-9]|\b0[1-9])(?:[ ./-]?\d{2}){4}\b"),
            email: re(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
        }
    }

    /// Balayage manuel : après un masquage, la lecture reprend juste derrière
    /// le segment masqué, pour que la suite (un second IBAN) soit rescannée.
    fn mask_ibans(&self, text: &str) -> String {
        let mut out = String::new();
        let mut pos = 0;
        while let Some(m) = self.iban.find_at(text, pos) {
            out.push_str(&text[pos..m.start()]);
            let cand = m.as_str();
            match iban_span(cand) {
                Some(end) => {
                    out.push_str("[IBAN]");
                    pos = m.start() + end;
                }
                None => {
                    // Rejeté : on avance d'un seul mot, un IBAN peut suivre.
                    let skip = cand.find([' ', '-']).unwrap_or(cand.len());
                    out.push_str(&cand[..skip]);
                    pos = m.start() + skip;
                }
            }
        }
        out.push_str(&text[pos..]);
        out
    }

    pub fn redact(&self, text: &str) -> String {
        // Espaces insécables, fines, tabulations : tous ramenés à l'espace
        // ASCII pour que les séparateurs exotiques ne contournent pas les motifs.
        let text = text.replace(|c: char| c != '\n' && c.is_whitespace(), " ");
        let mut out = match &self.terms {
            Some(r) => r.replace_all(&text, "[MASQUE]").into_owned(),
            None => text,
        };
        out = self.mask_ibans(&out);
        out = self
            .card
            .replace_all(&out, |c: &Captures| mask_card(&c[0]))
            .into_owned();
        out = self.nir.replace_all(&out, "[NIR]").into_owned();
        out = self.phone.replace_all(&out, "[TEL]").into_owned();
        self.email.replace_all(&out, "[EMAIL]").into_owned()
    }
}

/// Longueur masquable du candidat : plus long préfixe IBAN valide (mod 97),
/// sinon tout le candidat (échec fermé). Dans les deux cas il faut au moins
/// 12 chiffres, et la coupe tombe en fin de mot.
fn iban_span(m: &str) -> Option<usize> {
    let digits = |s: &str| s.chars().filter(char::is_ascii_digit).count();
    let ends: Vec<usize> = m
        .char_indices()
        .filter(|(_, c)| c.is_ascii_alphanumeric())
        .map(|(i, _)| i + 1)
        .collect();
    for k in (15..=ends.len()).rev() {
        let end = ends[k - 1];
        let at_boundary = m[end..].chars().next().is_none_or(|c| !c.is_ascii_alphanumeric());
        if at_boundary && digits(&m[..end]) >= 12 && iban_valid(&m[..end]) {
            return Some(end);
        }
    }
    (digits(m) >= 12).then_some(m.len())
}

/// Plus longue fenêtre de 13 à 19 chiffres valide Luhn dans le candidat ;
/// aucune : inchangé (les longues suites de chiffres sont courantes).
fn mask_card(m: &str) -> String {
    let pos: Vec<usize> = m.char_indices().filter(|(_, c)| c.is_ascii_digit()).map(|(i, _)| i).collect();
    // Jusqu'à 16 chiffres, seule la suite entière compte (une fenêtre y
    // validerait Luhn par hasard) ; au-delà, du bruit a pu s'y accrocher.
    let min = if pos.len() > 16 { 13 } else { pos.len() };
    for len in (min..=pos.len().min(19)).rev() {
        for start in 0..=pos.len() - len {
            let (from, to) = (pos[start], pos[start + len - 1] + 1);
            if luhn_valid(&m[from..to]) {
                return format!("{}[CARTE]{}", &m[..from], &m[to..]);
            }
        }
    }
    m.to_string()
}

fn luhn_valid(s: &str) -> bool {
    let digits: Vec<u32> = s.chars().filter_map(|c| c.to_digit(10)).collect();
    let sum: u32 = digits
        .iter()
        .rev()
        .enumerate()
        .map(|(i, &d)| if i % 2 == 1 { let x = d * 2; if x > 9 { x - 9 } else { x } } else { d })
        .sum();
    sum % 10 == 0
}

/// Contrôle modulo 97 : les quatre premiers caractères passent en fin, les
/// lettres valent 10..35.
fn iban_valid(s: &str) -> bool {
    let compact: Vec<char> = s.chars().filter(|c| c.is_ascii_alphanumeric()).collect();
    if compact.len() < 15 {
        return false;
    }
    let mut rem = 0u32;
    for c in compact[4..].iter().chain(&compact[..4]) {
        let Some(v) = c.to_digit(36) else { return false };
        rem = if v >= 10 { (rem * 100 + v) % 97 } else { (rem * 10 + v) % 97 };
    }
    rem == 1
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r() -> Redactor {
        Redactor::new(&["12 rue X".to_string()])
    }

    #[test]
    fn redacts_iban_with_spaces() {
        let out = r().redact("virement vers FR76 3000 6000 0112 3456 7890 189 merci");
        assert_eq!(out, "virement vers [IBAN] merci");
    }

    #[test]
    fn redacts_nir_with_spaces() {
        assert_eq!(r().redact("nir 1 85 05 78 006 084 36 ok"), "nir [NIR] ok");
    }

    #[test]
    fn redacts_valid_card_only() {
        assert_eq!(r().redact("carte 4111 1111 1111 1111"), "carte [CARTE]");
        assert_eq!(r().redact("4111 1111 1111 1112"), "4111 1111 1111 1112");
    }

    #[test]
    fn keeps_amounts_and_dates() {
        let s = "reste 1 234,56 € le 2026-10-30";
        assert_eq!(r().redact(s), s);
    }

    #[test]
    fn ten_digit_order_number_is_not_a_phone() {
        let s = "commande 1234567890 expédiée";
        assert_eq!(r().redact(s), s);
    }

    #[test]
    fn iban_followed_by_tokens_is_still_masked() {
        let i = "FR76 3000 6000 0112 3456 7890 189";
        assert_eq!(r().redact(&format!("IBAN {i} BIC BANKFRPP")), "IBAN [IBAN] BIC BANKFRPP");
        assert_eq!(r().redact(&format!("{i} EUR")), "[IBAN] EUR");
        assert_eq!(r().redact(&format!("{i} 2026")), "[IBAN] 2026");
    }

    #[test]
    fn iban_lowercase_dashed_and_invalid_shape() {
        assert_eq!(r().redact("iban fr76 3000 6000 0112 3456 7890 189"), "iban [IBAN]");
        assert_eq!(r().redact("FR76-3000-6000-0112-3456-7890-189"), "[IBAN]");
        // clé fausse : masqué quand même (échec fermé)
        assert_eq!(r().redact("FR76 3000 6000 0112 3456 7890 188"), "[IBAN]");
    }

    #[test]
    fn two_ibans_are_both_masked() {
        let i = "FR76 3000 6000 0112 3456 7890 189";
        let g = "GB82 WEST 1234 5698 7654 32";
        assert_eq!(r().redact(&format!("{i} et {g}")), "[IBAN] et [IBAN]");
        assert_eq!(r().redact(&format!("{i} {i}")), "[IBAN] [IBAN]");
    }

    #[test]
    fn prose_with_iban_like_prefix_is_untouched() {
        for s in [
            "FR24 actualites du jour sur la ville",
            "RN12 vers Ville-A et Ville-B demain",
            "GT86 sport coupe rouge",
            "ab12 the quick brown fox jumps",
            "MP34 pour tous les gens ici",
            "facture FR12345678901 total",
        ] {
            assert_eq!(r().redact(s), s);
        }
    }

    #[test]
    fn card_followed_by_expiry_is_masked() {
        assert_eq!(r().redact("carte 4111 1111 1111 1111 12/27"), "carte [CARTE] 12/27");
    }

    #[test]
    fn phone_formats() {
        for p in ["+33 (0)6 12 34 56 78", "0033 6 12 34 56 78", "06/12/34/56/78"] {
            assert_eq!(r().redact(&format!("tel {p}.")), "tel [TEL].", "{p}");
        }
    }

    #[test]
    fn unicode_separators_do_not_bypass() {
        for sp in ['\u{a0}', '\u{202f}', '\t'] {
            let iban = ["FR76", "3000", "6000", "0112", "3456", "7890", "189"].join(&sp.to_string());
            assert_eq!(r().redact(&format!("a {iban} b")), "a [IBAN] b");
            let card = ["4111", "1111", "1111", "1111"].join(&sp.to_string());
            assert_eq!(r().redact(&format!("a {card} b")), "a [CARTE] b");
            let tel = ["06", "12", "34", "56", "78"].join(&sp.to_string());
            assert_eq!(r().redact(&format!("a {tel} b")), "a [TEL] b");
        }
    }

    #[test]
    fn nir_with_dashes_or_dots() {
        assert_eq!(r().redact("nir 1-85-05-78-006-084-36 ok"), "nir [NIR] ok");
        assert_eq!(r().redact("nir 1.85.05.78.006.084.36 ok"), "nir [NIR] ok");
    }

    #[test]
    fn corsican_nir_is_masked() {
        assert_eq!(r().redact("nir 1 85 05 2A 006 084 36 ok"), "nir [NIR] ok");
    }

    #[test]
    fn redacts_email_phone_and_extra_terms() {
        let out = r().redact("a.b@c.fr, 06 12 34 56 78, +33 6 12 34 56 78, habite 12 RUE x");
        assert_eq!(out, "[EMAIL], [TEL], [TEL], habite [MASQUE]");
    }
}
