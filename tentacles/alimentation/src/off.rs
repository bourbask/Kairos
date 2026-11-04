//! Fiche produit par code-barres, depuis la base ouverte Open Food Facts.

use std::time::Duration;

use serde::Deserialize;

#[derive(Debug, Clone, Default, PartialEq)]
pub struct OffProduct {
    pub barcode: String,
    pub name: String,
    pub brand: Option<String>,
    /// Contenance annoncée (« 500 g », « 6 x 1 l »…).
    pub quantity: Option<String>,
    pub category: Option<String>,
    pub nutriscore: Option<String>,
    pub nova: Option<i64>,
}

#[derive(Deserialize)]
struct Resp {
    status: Option<i64>,
    product: Option<P>,
}

#[derive(Deserialize)]
struct P {
    product_name_fr: Option<String>,
    product_name: Option<String>,
    brands: Option<String>,
    quantity: Option<String>,
    categories_tags: Option<Vec<String>>,
    nutriscore_grade: Option<String>,
    nova_group: Option<i64>,
}

fn non_empty(s: Option<String>) -> Option<String> {
    s.map(|x| x.trim().to_string()).filter(|x| !x.is_empty())
}

/// Code-barres plausible : 8 à 14 chiffres.
pub fn valid_barcode(b: &str) -> bool {
    (8..=14).contains(&b.len()) && b.chars().all(|c| c.is_ascii_digit())
}

pub struct Off {
    base: String,
    http: reqwest::Client,
}

impl Off {
    pub fn new(base: &str) -> Off {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .user_agent("Kairos/0.1 (assistant personnel)")
            .build()
            .expect("client HTTP");
        Off { base: base.trim_end_matches('/').to_string(), http }
    }

    pub async fn lookup(&self, barcode: &str) -> Result<Option<OffProduct>, String> {
        if !valid_barcode(barcode) {
            return Err(format!("Code-barres invalide « {barcode} » (8 à 14 chiffres)."));
        }
        let url = format!(
            "{}/api/v2/product/{barcode}.json?fields=product_name_fr,product_name,brands,quantity,categories_tags,nutriscore_grade,nova_group",
            self.base
        );
        let r = self.http.get(url).send().await.map_err(|_| "base produits injoignable".to_string())?;
        if r.status().as_u16() == 404 {
            return Ok(None);
        }
        if !r.status().is_success() {
            return Err(format!("base produits en échec ({})", r.status().as_u16()));
        }
        let v: Resp = r.json().await.map_err(|_| "réponse illisible".to_string())?;
        let Some(p) = v.product.filter(|_| v.status == Some(1)) else { return Ok(None) };
        let Some(name) = non_empty(p.product_name_fr).or(non_empty(p.product_name)) else { return Ok(None) };
        Ok(Some(OffProduct {
            barcode: barcode.to_string(),
            name,
            brand: non_empty(p.brands).map(|b| b.split(',').next().unwrap_or(&b).trim().to_string()),
            quantity: non_empty(p.quantity),
            category: p
                .categories_tags
                .and_then(|c| c.into_iter().rev().find(|t| t.starts_with("fr:") || t.starts_with("en:")))
                .map(|t| t.split_once(':').map(|x| x.1.replace('-', " ")).unwrap_or(t)),
            nutriscore: non_empty(p.nutriscore_grade).filter(|n| n.len() == 1).map(|n| n.to_uppercase()),
            nova: p.nova_group,
        }))
    }
}
