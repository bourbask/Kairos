//! Lecture d'un bon de commande de drive (texte extrait d'un PDF par
//! `pdftotext -layout`) : référence, date de retrait, total et lignes, avec un
//! emplacement de rangement déduit du rayon.
//!
//! Le format attendu : un en-tête « Retrait le JJ/MM/AA », une ligne
//! « … commande n°N du JJ/MM/AAAA … », des rayons en capitales suivis de
//! « (k produits) », puis une ligne par article : désignation, quantité,
//! prix unitaire et total, séparés par des blancs.

use crate::store::Line;
use crate::units::fold;

#[derive(Debug, Clone, PartialEq)]
pub struct Bon {
    pub reference: Option<String>,
    pub pickup: Option<String>,
    pub total: Option<f64>,
    /// Lignes alimentaires, prêtes pour `order_record`.
    pub lines: Vec<Line>,
    /// Articles écartés (hygiène, entretien…), pour information.
    pub skipped: Vec<String>,
}

/// Rayons sans nourriture : ignorés.
const NON_FOOD: [&str; 8] =
    ["hygiene", "entretien", "nettoyage", "bebe", "animal", "maison", "parapharmacie", "papeterie"];

/// Rangés au placard même s'ils viennent d'un rayon frais.
const SHELF: [&str; 9] =
    ["potimarron", "courge", "butternut", "patate", "pomme de terre", "pommes de terre", "oignon", "banane", "uht"];

/// Rangés au frigo même s'ils viennent d'un rayon sec.
const COLD: [&str; 2] = ["frais", "refrigere"];

fn rayon(line: &str) -> Option<String> {
    let t = line.trim();
    let (head, tail) = t.rsplit_once(" (")?;
    let n = tail.strip_suffix(')')?;
    let ok = n.split_whitespace().next()?.chars().all(|c| c.is_ascii_digit())
        && n.contains("produit")
        && head.chars().any(|c| c.is_alphabetic())
        && head.chars().filter(|c| c.is_alphabetic()).all(|c| c.is_uppercase());
    ok.then(|| fold(head))
}

/// « désignation   quantité   prix   total » ; None si la ligne n'est pas un article.
fn article(line: &str) -> Option<(String, f64, f64)> {
    // Les colonnes sont séparées par de larges marges (au moins deux blancs).
    let cols: Vec<&str> = line.split("  ").map(str::trim).filter(|s| !s.is_empty()).collect();
    if cols.len() < 4 {
        return None;
    }
    let n = cols.len();
    let qty: f64 = cols[n - 3].parse().ok()?;
    num(cols[n - 2])?;
    let total = num(cols[n - 1])?;
    let name = cols[..n - 3].join(" ");
    if qty <= 0.0 || qty.is_nan() || !name.chars().any(char::is_alphabetic) {
        return None;
    }
    Some((name.split_whitespace().collect::<Vec<_>>().join(" "), qty, total))
}

fn num(s: &str) -> Option<f64> {
    s.replace(',', ".").parse().ok()
}

/// Contenance lue dans la désignation : (quantité, unité) pour un article.
fn size(designation: &str, fresh_meat: bool) -> (f64, &'static str) {
    let f = fold(designation);
    let words: Vec<&str> = f.split(|c: char| c.is_whitespace() || c == '-').filter(|w| !w.is_empty()).collect();
    let unit_of = |u: &str| match u {
        "kg" => Some("kg"),
        "g" => Some("g"),
        "l" => Some("l"),
        "cl" => Some("cl"),
        "ml" => Some("ml"),
        _ => None,
    };
    let split_num = |w: &str| -> Option<(f64, String)> {
        let i = w.find(|c: char| !(c.is_ascii_digit() || c == '.' || c == ','))?;
        Some((num(&w[..i])?, w[i..].to_string()))
    };
    // Du dernier mot au premier : la contenance est en général en fin de désignation.
    for (i, w) in words.iter().enumerate().rev() {
        // 6x1l, 12x10g : lot.
        if let Some((n, rest)) = w.split_once('x').and_then(|(a, b)| Some((a.parse::<f64>().ok()?, b)))
            && let Some((q, u)) = split_num(rest)
            && let Some(u) = unit_of(&u)
        {
            return (n * q, u);
        }
        // x12 : nombre de pièces.
        if let Some(n) = w.strip_prefix('x').and_then(|n| n.parse::<f64>().ok()) {
            return (n, "pièce");
        }
        // 500g, 1.5kg, 1l.
        if let Some((q, u)) = split_num(w) {
            if let Some(u) = unit_of(&u) {
                return (q, u);
            }
            // 1p : une pièce.
            if u == "p" {
                return (q, "pièce");
            }
        }
        // 500 g : nombre puis unité.
        if let (Some(q), Some(u)) = (num(w), words.get(i + 1).and_then(|u| unit_of(u))) {
            return (q, u);
        }
    }
    // Viande au poids : « … 1,25 » en fin de désignation = kilos.
    if fresh_meat
        && let Some(q) = words.last().and_then(|w| num(w))
        && q > 0.0
        && q < 10.0
        && designation.trim_end().ends_with(|c: char| c.is_ascii_digit())
    {
        return (q, "kg");
    }
    (1.0, "pièce")
}

/// Désignation sans la contenance ni le conditionnement.
fn clean(designation: &str) -> Vec<String> {
    designation
        .split(" - ")
        .map(|s| s.trim().to_string())
        .filter(|s| {
            let f = fold(s);
            !s.is_empty() && size(s, false) == (1.0, "pièce") && !f.starts_with("bouteille") && !f.starts_with("sachet")
        })
        .collect()
}

fn location(rayon: &str, name: &str) -> &'static str {
    let n = fold(name);
    if rayon.contains("surgel") {
        return "congelateur";
    }
    if COLD.iter().any(|k| n.contains(k)) {
        return "frigo";
    }
    if SHELF.iter().any(|k| n.contains(k)) {
        return "placard";
    }
    let fresh = ["viande", "poisson", "fruit", "legume", "laitier", "fromage", "cremerie", "charcuterie", "traiteur"];
    if fresh.iter().any(|k| rayon.contains(k)) { "frigo" } else { "placard" }
}

/// JJ/MM/AA ou JJ/MM/AAAA → AAAA-MM-JJ.
fn day(s: &str) -> Option<String> {
    let s = s.trim_matches(|c: char| !c.is_ascii_digit());
    let fmt = if s.len() == 8 { "%d/%m/%y" } else { "%d/%m/%Y" };
    chrono::NaiveDate::parse_from_str(s, fmt).ok().map(|d| d.to_string())
}

/// Lit un bon de commande ; erreur si le texte n'en a pas la forme.
pub fn parse(text: &str) -> Result<Bon, String> {
    let mut bon = Bon { reference: None, pickup: None, total: None, lines: Vec::new(), skipped: Vec::new() };
    let mut current: Option<String> = None;
    let mut raw: Vec<(String, String, f64, f64)> = Vec::new();
    for line in text.lines() {
        let f = fold(line);
        if bon.pickup.is_none()
            && let Some(i) = f.find("retrait le ")
        {
            bon.pickup = f[i + 11..].split_whitespace().next().and_then(day);
        }
        if bon.reference.is_none()
            && let Some(i) = f.find("commande n")
        {
            let tail = &f[i + 10..];
            let digits: String = tail
                .trim_start_matches(|c: char| !c.is_ascii_digit())
                .chars()
                .take_while(|c| c.is_ascii_digit())
                .collect();
            if !digits.is_empty() {
                bon.reference = Some(digits);
            }
        }
        if let Some(i) = f.find("total de la commande") {
            bon.total = f[i..].split(':').nth(1).and_then(|v| num(v.split_whitespace().next()?));
        }
        if let Some(r) = rayon(line) {
            current = Some(r);
            continue;
        }
        if let (Some(r), Some((name, qty, total))) = (current.as_ref(), article(line)) {
            raw.push((r.clone(), name, qty, total));
        }
    }
    if raw.is_empty() {
        return Err("Aucun article reconnu : ce texte ne ressemble pas à un bon de commande.".to_string());
    }
    // Nom court (premier segment), allongé quand deux articles le partagent.
    let short = |d: &str| clean(d).into_iter().next().unwrap_or_else(|| d.to_string());
    let firsts: Vec<String> = raw.iter().map(|(_, d, _, _)| fold(&short(d))).collect();
    for (k, (r, designation, qty, total)) in raw.into_iter().enumerate() {
        if NON_FOOD.iter().any(|n| r.contains(n)) {
            bon.skipped.push(designation);
            continue;
        }
        let segs = clean(&designation);
        let mut name = segs.first().cloned().unwrap_or_else(|| designation.clone());
        if firsts.iter().filter(|x| **x == firsts[k]).count() > 1
            && let Some(second) = segs.get(1)
        {
            name = format!("{name}, {second}");
        }
        let (q, unit) = size(&designation, r.contains("viande"));
        bon.lines.push(Line {
            name,
            qty: q * qty,
            unit: Some(unit.to_string()),
            location: Some(location(&r, &designation).to_string()),
            price: Some(total),
            ..Default::default()
        });
    }
    Ok(bon)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Bon fictif au format attendu (mise en page de `pdftotext -layout`).
    const BON: &str = "                                                  Retrait le 14/03/27 à 18H30
  BON DE COMMANDE
                                                                     12345678
 Drive Exemple                                       Adresse de retrait
Détail de votre commande n°12345678 du 12/03/2027 à 10h05 (14 produits)
Désignation                                              Quantité   Prix unitaire TTC   Total TTC

VIANDES POISSONS (1 produit)

  Poulet fermier - Volailles Exemple 1,40                           1                9.90        9.90

FRUITS LÉGUMES (2 produits)

  Potimarron - 1p                                                   1                1.70        1.70
  Origine : FRANCE • Variété : Potimarron

  Carottes - Sachet 2kg                                             1                1.99        1.99

LAITIER OEUFS VÉGÉTAL (3 produits)

  Oeufs frais - Plein Air - x12                                     1                2.90        2.90

  Lait demi-écrémé UHT - Bouteille - 6x1L                           1                6.30        6.30
                                                       1/2
\u{c}Désignation                                              Quantité   Prix unitaire TTC   Total TTC

  Beurre doux - 250g                                                2                2.50        5.00

SURGELÉS (1 produit)

  Epinards hachés - 600g                                            1                1.89        1.89

EPICERIE SALÉE (3 produits)

  Soupe Veloutée - 10 légumes - 1L                                  1                1.19        1.19

  Soupe Veloutée - Tomates - 1L                                     1                1.09        1.09

  Bouillon de volaille - Cubes - 12x10g                             1                0.80        0.80

BOISSONS (1 produit)

  Jus frais - orange 1.5L                                           1                3.26        3.26

HYGIÈNE BEAUTÉ (1 produit)

  Mouchoirs - x9                                                    1                1.16        1.16

                                       Total de la commande :       36.18
";

    #[test]
    fn reads_header_and_lines() {
        let b = parse(BON).unwrap();
        assert_eq!(b.reference.as_deref(), Some("12345678"));
        assert_eq!(b.pickup.as_deref(), Some("2027-03-14"));
        assert_eq!(b.total, Some(36.18));
        assert_eq!(b.skipped, vec!["Mouchoirs - x9".to_string()]);
        let got: Vec<(String, f64, String, String)> = b
            .lines
            .iter()
            .map(|l| (l.name.clone(), l.qty, l.unit.clone().unwrap(), l.location.clone().unwrap()))
            .collect();
        let want = [
            ("Poulet fermier", 1.4, "kg", "frigo"),
            ("Potimarron", 1.0, "pièce", "placard"),
            ("Carottes", 2.0, "kg", "frigo"),
            ("Oeufs frais", 12.0, "pièce", "frigo"),
            ("Lait demi-écrémé UHT", 6.0, "l", "placard"),
            ("Beurre doux", 500.0, "g", "frigo"),
            ("Epinards hachés", 600.0, "g", "congelateur"),
            ("Soupe Veloutée, 10 légumes", 1.0, "l", "placard"),
            ("Soupe Veloutée, Tomates", 1.0, "l", "placard"),
            ("Bouillon de volaille", 120.0, "g", "placard"),
            ("Jus frais", 1.5, "l", "frigo"),
        ];
        let want: Vec<(String, f64, String, String)> =
            want.iter().map(|(n, q, u, l)| (n.to_string(), *q, u.to_string(), l.to_string())).collect();
        assert_eq!(got, want);
        assert_eq!(b.lines[5].price, Some(5.0));
    }

    /// Bon réel, hors dépôt : `BON_FILE=chemin.txt cargo test -p kairos-alimentation -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn reads_a_real_bon() {
        let text = std::fs::read_to_string(std::env::var("BON_FILE").unwrap()).unwrap();
        let b = parse(&text).unwrap();
        println!("{:?} {:?} {:?}", b.reference, b.pickup, b.total);
        for l in &b.lines {
            println!(
                "{} | {} {} | {} | {:?}",
                l.name,
                l.qty,
                l.unit.as_deref().unwrap(),
                l.location.as_deref().unwrap(),
                l.price
            );
        }
        println!("écartés : {:?}", b.skipped);
    }

    #[test]
    fn rejects_other_documents() {
        assert!(parse("Facture d'électricité\nMontant : 42,00 €").is_err());
    }
}
