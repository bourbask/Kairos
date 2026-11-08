//! Unités : chaque produit est compté dans une famille (masse en g, volume en
//! ml, pièces) fixée à sa création ; les saisies sont converties.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Family {
    Masse,
    Volume,
    Piece,
}

impl Family {
    pub fn as_str(self) -> &'static str {
        match self {
            Family::Masse => "masse",
            Family::Volume => "volume",
            Family::Piece => "piece",
        }
    }

    pub fn parse(s: &str) -> Option<Family> {
        match s {
            "masse" => Some(Family::Masse),
            "volume" => Some(Family::Volume),
            "piece" => Some(Family::Piece),
            _ => None,
        }
    }
}

/// Unité saisie → (famille, facteur vers l'unité de base).
pub fn parse_unit(u: &str) -> Option<(Family, f64)> {
    let u = u.trim().to_lowercase();
    let u = u.trim_end_matches('.');
    Some(match u {
        "g" | "gr" | "gramme" | "grammes" => (Family::Masse, 1.0),
        "kg" | "kilo" | "kilos" | "kilogramme" | "kilogrammes" => (Family::Masse, 1000.0),
        "ml" | "millilitre" | "millilitres" => (Family::Volume, 1.0),
        "cl" | "centilitre" | "centilitres" => (Family::Volume, 10.0),
        "l" | "litre" | "litres" => (Family::Volume, 1000.0),
        "" | "x" | "u" | "pc" | "pcs" | "piece" | "pièce" | "pieces" | "pièces" | "unite" | "unité" | "unites"
        | "unités" => (Family::Piece, 1.0),
        "douzaine" | "douzaines" => (Family::Piece, 12.0),
        _ => return None,
    })
}

fn num(x: f64) -> String {
    let r = (x * 100.0).round() / 100.0;
    let s = if r.fract() == 0.0 { format!("{}", r as i64) } else { format!("{r}") };
    s.replace('.', ",")
}

/// Quantité lisible dans l'unité la plus naturelle.
pub fn show(qty: f64, f: Family) -> String {
    match f {
        Family::Masse if qty.abs() >= 1000.0 => format!("{} kg", num(qty / 1000.0)),
        Family::Masse => format!("{} g", num(qty)),
        Family::Volume if qty.abs() >= 1000.0 => format!("{} l", num(qty / 1000.0)),
        Family::Volume => format!("{} ml", num(qty)),
        Family::Piece => format!("{} pièce{}", num(qty), if qty.abs() > 1.0 { "s" } else { "" }),
    }
}

/// Minuscules sans accents, pour rapprocher des noms de produits.
pub fn fold(s: &str) -> String {
    s.to_lowercase()
        .chars()
        .map(|c| match c {
            'à' | 'â' | 'ä' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'î' | 'ï' => 'i',
            'ô' | 'ö' => 'o',
            'ù' | 'û' | 'ü' => 'u',
            'ç' => 'c',
            'œ' => 'o',
            c => c,
        })
        .collect::<String>()
        .replace("oe", "o")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn units_convert_and_display() {
        assert_eq!(parse_unit("Kg"), Some((Family::Masse, 1000.0)));
        assert_eq!(parse_unit("cl"), Some((Family::Volume, 10.0)));
        assert_eq!(parse_unit("douzaine"), Some((Family::Piece, 12.0)));
        assert_eq!(parse_unit(""), Some((Family::Piece, 1.0)));
        assert_eq!(parse_unit("pincée"), None);
        assert_eq!(show(1500.0, Family::Masse), "1,5 kg");
        assert_eq!(show(250.0, Family::Volume), "250 ml");
        assert_eq!(show(3.0, Family::Piece), "3 pièces");
        assert_eq!(show(1.0, Family::Piece), "1 pièce");
        assert_eq!(fold("Œufs plein air"), "oufs plein air");
        assert_eq!(fold("oeufs"), fold("Œufs"));
    }
}
