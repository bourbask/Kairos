//! Stock alimentaire, commandes, repas et préférences (SQLite).
//!
//! Un produit (nom, code-barres, famille d'unité) a des lots (quantité,
//! emplacement, date limite). Toute entrée ou sortie laisse un mouvement :
//! achat, repas, perte, correction. La consommation puise d'abord dans les lots
//! entamés, puis dans ceux qui périment le plus tôt.
//!
//! Tout lot a une date : celle imprimée quand on la donne, sinon une estimation
//! d'après le produit et l'emplacement (voir `shelf`), marquée comme telle.
//! L'ouverture et la décongélation raccourcissent la date ; la congélation
//! l'allonge.

use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};

use crate::nature::Nature;
use crate::shelf::{self, Kind};
use crate::units::{Family, fold, parse_unit, show};

const SCHEMA: &str = "
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS products (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    folded TEXT NOT NULL,
    barcode TEXT UNIQUE,
    brand TEXT,
    family TEXT NOT NULL,
    category TEXT,
    nutriscore TEXT,
    nova INTEGER,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS lots (
    id INTEGER PRIMARY KEY,
    product_id INTEGER NOT NULL REFERENCES products(id),
    qty REAL NOT NULL,
    location TEXT NOT NULL,
    expiry TEXT,
    opened INTEGER NOT NULL DEFAULT 0,
    added_at INTEGER NOT NULL,
    order_id INTEGER,
    price REAL
);
CREATE TABLE IF NOT EXISTS movements (
    id INTEGER PRIMARY KEY,
    product_id INTEGER NOT NULL REFERENCES products(id),
    delta REAL NOT NULL,
    reason TEXT NOT NULL,
    meal_id INTEGER,
    at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS meals (
    id INTEGER PRIMARY KEY,
    day TEXT NOT NULL,
    slot TEXT NOT NULL,
    name TEXT NOT NULL,
    place TEXT NOT NULL,
    notes TEXT,
    at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS orders (
    id INTEGER PRIMARY KEY,
    store TEXT NOT NULL,
    reference TEXT,
    pickup TEXT,
    total REAL,
    status TEXT NOT NULL,
    lines TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    received_at INTEGER
);
CREATE TABLE IF NOT EXISTS recipes (
    name TEXT PRIMARY KEY,
    data TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS meal_plan (
    day TEXT NOT NULL,
    slot TEXT NOT NULL,
    dish TEXT NOT NULL,
    kind TEXT NOT NULL,
    cooked TEXT NOT NULL,
    note TEXT,
    PRIMARY KEY (day, slot)
);
CREATE TABLE IF NOT EXISTS order_lines (
    order_id INTEGER NOT NULL,
    idx INTEGER NOT NULL,
    state TEXT NOT NULL,
    at INTEGER NOT NULL,
    PRIMARY KEY (order_id, idx)
);
CREATE TABLE IF NOT EXISTS prefs (k TEXT PRIMARY KEY, v TEXT NOT NULL, updated_at INTEGER NOT NULL);
";

/// Emplacements reconnus (le reste est refusé, pour garder un inventaire lisible).
pub const LOCATIONS: [&str; 7] = ["frigo", "congelateur", "placard", "etagere", "corbeille", "evier", "sdb"];

/// Emplacement normalisé (« congélo », « Congélateur », « fruits » → valeur connue).
pub fn location(s: &str) -> Result<String, String> {
    let f = fold(s.trim());
    let l = match f.as_str() {
        x if x.starts_with("frig") || x == "refrigerateur" => "frigo",
        x if x.starts_with("congel") || x == "freezer" => "congelateur",
        x if x.starts_with("plac") || x == "epicerie" || x == "cellier" => "placard",
        x if x.starts_with("etag") => "etagere",
        x if x.starts_with("corb") || x.starts_with("fruit") => "corbeille",
        x if x.contains("evier") => "evier",
        x if x == "sdb" || x.starts_with("salle de bain") || x == "bain" || x == "toilettes" => "sdb",
        _ => return Err(format!("Emplacement inconnu « {s} » : {}.", LOCATIONS.join(", "))),
    };
    Ok(l.to_string())
}

/// Ligne saisie (achat, ajout, consommation).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
pub struct Line {
    /// Nom du produit (« œufs plein air », « poulet entier », « carottes »).
    #[serde(default)]
    pub name: String,
    /// Quantité dans l'unité donnée.
    pub qty: f64,
    /// Unité : g, kg, ml, cl, l, pièce (défaut), douzaine.
    #[serde(default)]
    pub unit: Option<String>,
    /// frigo, congelateur, placard, etagere, corbeille (fruits et légumes à l'air libre),
    /// evier (sous l'évier : entretien) ou sdb (salle de bain : hygiène).
    #[serde(default)]
    pub location: Option<String>,
    /// Date imprimée (AAAA-MM-JJ), si connue ; sinon estimée d'après le produit et l'emplacement.
    #[serde(default)]
    pub expiry: Option<String>,
    /// Code-barres EAN, si connu.
    #[serde(default)]
    pub barcode: Option<String>,
    /// Prix payé pour la ligne, en euros.
    #[serde(default)]
    pub price: Option<f64>,
}

/// Produit déjà vu : nom, nature, emplacement habituel.
pub type Known = (String, Option<Nature>, Option<String>);

/// Produit déjà connu, vu par l'application (`Store::usual`).
pub struct Usual {
    pub name: String,
    pub family: Family,
    pub nature: Option<Nature>,
    pub location: Option<String>,
    /// Dernier apport, dans l'unité de base.
    pub pack: Option<f64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Product {
    pub id: i64,
    pub name: String,
    pub family: Family,
    pub barcode: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Lot {
    pub id: i64,
    pub product: String,
    pub family: Family,
    pub qty: f64,
    pub location: String,
    pub expiry: Option<String>,
    /// Nature de la date (DLC ou DDM), connue pour les lots récents.
    pub kind: Option<Kind>,
    /// Date estimée plutôt que lue sur l'emballage.
    pub estimated: bool,
    pub opened: bool,
    /// Entrée en stock (horodatage).
    pub added_at: i64,
    /// Nature culinaire ; `None` = à qualifier.
    pub nature: Option<Nature>,
}

impl Lot {
    pub fn show(&self) -> String {
        show(self.qty, self.family)
    }
}

/// Produit dont la fiche reste à compléter (`Store::incomplete`).
#[derive(Debug, Clone, PartialEq)]
pub struct Incomplete {
    pub id: i64,
    pub name: String,
    pub family: Family,
    pub nature: Option<Nature>,
    /// Contenance encore inconnue (compté en pièces faute de mieux).
    pub size_unknown: bool,
    /// En stock, dans l'unité de base.
    pub stock: f64,
    /// Dernière entrée en stock, sinon création (horodatage).
    pub added_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Order {
    pub id: i64,
    pub store: String,
    pub reference: Option<String>,
    pub pickup: Option<String>,
    pub total: Option<f64>,
    pub status: String,
    pub lines: Vec<Line>,
}

/// Ingrédient pesé : quantité et unité facultatives (« sel », « 1 pincée »).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
pub struct Ingredient {
    /// Nom tel qu'en stock (« lentilles corail »).
    pub name: String,
    #[serde(default)]
    pub qty: Option<f64>,
    /// g, kg, ml, l, pièce, c. à soupe, c. à café, pincée…
    #[serde(default)]
    pub unit: Option<String>,
}

impl Ingredient {
    /// « 300 g lentilles corail », « 0,4 l lait de coco », « sel ».
    pub fn show(&self) -> String {
        let qty = self.qty.map(|q| {
            let s = if q.fract() == 0.0 { format!("{q:.0}") } else { format!("{q}") };
            s.replace('.', ",")
        });
        match (qty, self.unit.as_deref().filter(|u| !u.trim().is_empty())) {
            (Some(q), Some(u)) => format!("{q} {u} {}", self.name),
            (Some(q), None) => format!("{q} {}", self.name),
            (None, _) => self.name.clone(),
        }
    }
}

/// Recette du carnet : écrite une fois, réutilisée à chaque plan.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Recipe {
    pub title: String,
    pub portions: u32,
    #[serde(default)]
    pub prep_minutes: Option<u32>,
    #[serde(default)]
    pub cook_minutes: Option<u32>,
    #[serde(default)]
    pub ingredients: Vec<Ingredient>,
    pub steps: Vec<String>,
}

impl Recipe {
    /// « Préparation : 10 min · cuisson : 25 min ».
    pub fn timing(&self) -> Option<String> {
        let parts: Vec<String> = [("Préparation", self.prep_minutes), ("cuisson", self.cook_minutes)]
            .into_iter()
            .filter_map(|(k, m)| m.map(|m| format!("{k} : {m} min")))
            .collect();
        (!parts.is_empty()).then(|| {
            let mut t = parts.join(" · ");
            if let Some(f) = t.get_mut(0..1) {
                f.make_ascii_uppercase();
            }
            t
        })
    }

    pub fn ingredients_list(&self) -> String {
        self.ingredients.iter().map(|i| format!("- {}", i.show())).collect::<Vec<_>>().join("\n")
    }

    pub fn steps_list(&self) -> String {
        self.steps.iter().enumerate().map(|(i, s)| format!("{}. {}", i + 1, s.trim())).collect::<Vec<_>>().join("\n")
    }
}

pub struct Store {
    db: Mutex<Connection>,
}

fn now() -> i64 {
    chrono::Utc::now().timestamp()
}

fn today() -> chrono::NaiveDate {
    chrono::Utc::now().date_naive()
}

fn in_days(n: i64) -> String {
    (today() + chrono::Duration::days(n)).to_string()
}

/// Colonnes ajoutées après la première version du schéma.
fn migrate(c: &Connection) -> rusqlite::Result<()> {
    let cols: Vec<String> =
        c.prepare("PRAGMA table_info(lots)")?.query_map([], |r| r.get(1))?.collect::<rusqlite::Result<_>>()?;
    let pcols: Vec<String> =
        c.prepare("PRAGMA table_info(products)")?.query_map([], |r| r.get(1))?.collect::<rusqlite::Result<_>>()?;
    if !pcols.iter().any(|c| c == "nature") {
        // Correction manuelle seulement : sinon la nature est déduite à la lecture.
        c.execute_batch("ALTER TABLE products ADD COLUMN nature TEXT;")?;
    }
    if !pcols.iter().any(|c| c == "location") {
        // Dernier emplacement de rangement, gardé quand le stock est épuisé.
        c.execute_batch("ALTER TABLE products ADD COLUMN location TEXT;")?;
    }
    if !pcols.iter().any(|c| c == "size_unknown") {
        // Créé en pièces sans fiche : la contenance reste à demander.
        c.execute_batch("ALTER TABLE products ADD COLUMN size_unknown INTEGER NOT NULL DEFAULT 0;")?;
    }
    if !cols.iter().any(|c| c == "expiry_kind") {
        c.execute_batch(
            "ALTER TABLE lots ADD COLUMN expiry_kind TEXT;
             ALTER TABLE lots ADD COLUMN expiry_estimated INTEGER NOT NULL DEFAULT 0;",
        )?;
    }
    Ok(())
}

/// « jusqu'au 2026-11-20 (DLC estimée) ».
fn date_note(exp: &str, kind: Kind, estimated: bool) -> String {
    let k = match kind {
        Kind::Dlc => "DLC",
        Kind::Ddm => "DDM",
    };
    format!("jusqu'au {exp} ({k}{})", if estimated { " estimée" } else { "" })
}

/// Date d'un nouveau lot : (date, nature, estimée).
pub(crate) fn dating(name: &str, loc: &str, given: Option<String>) -> (String, Kind, bool) {
    let (days, kind) = shelf::estimate(name, loc);
    match given {
        Some(d) => (d, kind, false),
        None => (in_days(days), kind, true),
    }
}

/// Date AAAA-MM-JJ valide, ou erreur.
pub fn date(s: &str) -> Result<String, String> {
    chrono::NaiveDate::parse_from_str(s.trim(), "%Y-%m-%d")
        .map(|d| d.to_string())
        .map_err(|_| format!("Date « {s} » invalide (AAAA-MM-JJ attendu)."))
}

fn qty_in(l: &Line) -> Result<(Family, f64), String> {
    if !(l.qty.is_finite() && l.qty > 0.0) {
        return Err(format!("{} : quantité positive attendue.", l.name));
    }
    let u = l.unit.as_deref().unwrap_or("");
    let (f, k) =
        parse_unit(u).ok_or_else(|| format!("{} : unité « {u} » inconnue (g, kg, ml, cl, l, pièce).", l.name))?;
    Ok((f, l.qty * k))
}

impl Store {
    pub fn open(path: &str) -> anyhow::Result<Store> {
        let c = Connection::open(path)?;
        c.execute_batch(SCHEMA)?;
        migrate(&c)?;
        Ok(Store { db: Mutex::new(c) })
    }

    pub fn memory() -> Store {
        let c = Connection::open_in_memory().expect("base en mémoire");
        c.execute_batch(SCHEMA).expect("schéma");
        migrate(&c).expect("migration");
        Store { db: Mutex::new(c) }
    }

    fn product_row(r: &rusqlite::Row) -> rusqlite::Result<Product> {
        let fam: String = r.get(2)?;
        Ok(Product {
            id: r.get(0)?,
            name: r.get(1)?,
            family: Family::parse(&fam).unwrap_or(Family::Piece),
            barcode: r.get(3)?,
        })
    }

    /// Produits dont le nom contient tous les mots demandés (sans accents).
    pub fn find(&self, query: &str) -> rusqlite::Result<Vec<Product>> {
        let words: Vec<String> = fold(query).split_whitespace().map(str::to_string).collect();
        let db = self.db.lock().unwrap();
        let mut st = db.prepare("SELECT id, name, family, barcode, folded FROM products ORDER BY name")?;
        let all: Vec<(Product, String)> =
            st.query_map([], |r| Ok((Self::product_row(r)?, r.get(4)?)))?.collect::<rusqlite::Result<_>>()?;
        let exact: Vec<Product> = all.iter().filter(|(_, f)| *f == words.join(" ")).map(|x| x.0.clone()).collect();
        if !exact.is_empty() {
            return Ok(exact);
        }
        Ok(all.into_iter().filter(|(_, f)| words.iter().all(|w| f.contains(w.as_str()))).map(|x| x.0).collect())
    }

    fn stock_of(db: &Connection, product: i64) -> rusqlite::Result<f64> {
        db.query_row("SELECT COALESCE(SUM(qty), 0) FROM lots WHERE product_id = ?1", [product], |r| r.get(0))
    }

    /// Un seul produit pour ce nom : exact, sinon unique, sinon le seul en stock.
    pub fn resolve(&self, query: &str) -> Result<Product, String> {
        let found = self.find(query).map_err(|e| e.to_string())?;
        match found.len() {
            0 => Err(format!("Aucun produit « {} » dans l'inventaire.", query.trim())),
            1 => Ok(found[0].clone()),
            _ => {
                let db = self.db.lock().unwrap();
                let stocked: Vec<&Product> =
                    found.iter().filter(|p| Self::stock_of(&db, p.id).unwrap_or(0.0) > 0.0).collect();
                if stocked.len() == 1 {
                    return Ok(stocked[0].clone());
                }
                let names: Vec<_> = found.iter().map(|p| p.name.clone()).collect();
                Err(format!("« {} » est ambigu : {}.", query.trim(), names.join(", ")))
            }
        }
    }

    /// Fixe la nature d'un produit existant, prioritaire sur toute déduction.
    pub fn set_nature(&self, query: &str, n: Nature) -> Result<String, String> {
        let p = self.resolve(query)?;
        let db = self.db.lock().unwrap();
        db.execute("UPDATE products SET nature = ?2 WHERE id = ?1", params![p.id, n.as_str()])
            .map_err(|e| e.to_string())?;
        Ok(format!("Nature de {} : {}.", p.name, n.as_str()))
    }

    /// Produits à compléter : en stock sans nature, ou en attente de contenance.
    pub fn incomplete(&self) -> rusqlite::Result<Vec<Incomplete>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(
            "SELECT p.id, p.name, p.family, p.nature, p.category, p.size_unknown,
                    COALESCE((SELECT SUM(qty) FROM lots WHERE product_id = p.id AND qty > 0), 0),
                    COALESCE((SELECT MAX(added_at) FROM lots WHERE product_id = p.id), p.created_at)
             FROM products p ORDER BY p.id",
        )?;
        let rows = st.query_map([], |r| {
            let name: String = r.get(1)?;
            let manual = r.get::<_, Option<String>>(3)?.as_deref().and_then(Nature::parse);
            Ok(Incomplete {
                id: r.get(0)?,
                family: Family::parse(&r.get::<_, String>(2)?).unwrap_or(Family::Piece),
                nature: crate::nature::resolve(manual, r.get::<_, Option<String>>(4)?.as_deref(), &name),
                name,
                size_unknown: r.get::<_, i64>(5)? != 0,
                stock: r.get(6)?,
                added_at: r.get(7)?,
            })
        })?;
        let all: Vec<Incomplete> = rows.collect::<rusqlite::Result<_>>()?;
        Ok(all.into_iter().filter(|p| (p.nature.is_none() && p.stock > 0.0) || p.size_unknown).collect())
    }

    /// Nature d'un produit désigné par son identifiant.
    pub fn set_nature_of(&self, id: i64, n: Nature) -> rusqlite::Result<()> {
        self.db.lock().unwrap().execute("UPDATE products SET nature = ?2 WHERE id = ?1", params![id, n.as_str()])?;
        Ok(())
    }

    /// Marque un produit compté en pièces : sa contenance sera demandée.
    pub fn flag_size_unknown(&self, name: &str) -> rusqlite::Result<()> {
        self.db.lock().unwrap().execute(
            "UPDATE products SET size_unknown = 1 WHERE folded = ?1 AND family = 'piece'",
            [fold(name.trim())],
        )?;
        Ok(())
    }

    /// Fixe la contenance d'un produit compté en pièces. `None` : il reste à
    /// l'unité. Sinon il passe dans la famille de la contenance et ses lots et
    /// mouvements sont convertis (n pièces → n × contenance), pour que le stock
    /// ne change pas de sens.
    pub fn set_size(&self, id: i64, size: Option<(Family, f64)>) -> Result<(), String> {
        let mut db = self.db.lock().unwrap();
        let tx = db.transaction().map_err(|e| e.to_string())?;
        let (fam, folded): (String, String) = tx
            .query_row("SELECT family, folded FROM products WHERE id = ?1 AND size_unknown = 1", [id], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .optional()
            .map_err(|e| e.to_string())?
            .ok_or("Contenance déjà connue.")?;
        if let Some((f, k)) = size {
            if fam != Family::Piece.as_str() || !(k.is_finite() && k > 0.0) {
                return Err("Contenance impossible pour ce produit.".into());
            }
            let twin: bool = tx
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM products WHERE folded = ?1 AND family = ?2 AND id != ?3)",
                    params![folded, f.as_str(), id],
                    |r| r.get(0),
                )
                .map_err(|e| e.to_string())?;
            if twin {
                return Err(format!("Un produit du même nom est déjà compté en {}.", f.base()));
            }
            tx.execute("UPDATE lots SET qty = qty * ?2 WHERE product_id = ?1", params![id, k]).map_err(|e| e.to_string())?;
            tx.execute("UPDATE movements SET delta = delta * ?2 WHERE product_id = ?1", params![id, k])
                .map_err(|e| e.to_string())?;
            tx.execute("UPDATE products SET family = ?2 WHERE id = ?1", params![id, f.as_str()])
                .map_err(|e| e.to_string())?;
        }
        tx.execute("UPDATE products SET size_unknown = 0 WHERE id = ?1", [id]).map_err(|e| e.to_string())?;
        tx.commit().map_err(|e| e.to_string())
    }

    /// Produits en stock dont la nature reste inconnue, par nom.
    pub fn unqualified(&self) -> rusqlite::Result<Vec<String>> {
        let mut names: Vec<String> =
            self.lots(None, None)?.into_iter().filter(|l| l.nature.is_none()).map(|l| l.product).collect();
        names.sort();
        names.dedup();
        Ok(names)
    }

    /// Produit existant (code-barres, sinon nom exact), ou créé.
    pub fn product(
        &self,
        name: &str,
        family: Family,
        barcode: Option<&str>,
        brand: Option<&str>,
    ) -> rusqlite::Result<Product> {
        let db = self.db.lock().unwrap();
        let by_code = match barcode {
            Some(b) => db
                .query_row("SELECT id, name, family, barcode FROM products WHERE barcode = ?1", [b], Self::product_row)
                .optional()?,
            None => None,
        };
        if let Some(p) = by_code {
            return Ok(p);
        }
        let by_name = db
            .query_row(
                "SELECT id, name, family, barcode FROM products WHERE folded = ?1 AND family = ?2",
                params![fold(name.trim()), family.as_str()],
                Self::product_row,
            )
            .optional()?;
        if let Some(p) = by_name {
            if p.barcode.is_none() && barcode.is_some() {
                db.execute("UPDATE products SET barcode = ?2 WHERE id = ?1", params![p.id, barcode])?;
            }
            return Ok(p);
        }
        db.execute(
            "INSERT INTO products (name, folded, barcode, brand, family, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![name.trim(), fold(name.trim()), barcode, brand, family.as_str(), now()],
        )?;
        Ok(Product {
            id: db.last_insert_rowid(),
            name: name.trim().to_string(),
            family,
            barcode: barcode.map(str::to_string),
        })
    }

    pub fn set_details(&self, id: i64, category: Option<&str>, nutriscore: Option<&str>, nova: Option<i64>) {
        let _ = self.db.lock().unwrap().execute(
            "UPDATE products SET category = COALESCE(?2, category), nutriscore = COALESCE(?3, nutriscore), nova = COALESCE(?4, nova) WHERE id = ?1",
            params![id, category, nutriscore, nova],
        );
    }

    /// Contrôle une ligne d'entrée en stock sans rien écrire : (famille, quantité de base, emplacement, date).
    pub fn check_in(l: &Line) -> Result<(Family, f64, String, Option<String>), String> {
        if l.name.trim().is_empty() {
            return Err("Nom de produit manquant.".to_string());
        }
        let (f, q) = qty_in(l)?;
        let loc = location(l.location.as_deref().filter(|x| !x.trim().is_empty()).unwrap_or("placard"))?;
        let exp = l.expiry.as_deref().filter(|e| !e.trim().is_empty()).map(date).transpose()?;
        Ok((f, q, loc, exp))
    }

    /// Entrée en stock (achat ou ajout manuel).
    pub fn add(&self, l: &Line, reason: &str, order: Option<i64>) -> Result<String, String> {
        let (f, q, loc, exp) = Self::check_in(l)?;
        let p = self.product(&l.name, f, l.barcode.as_deref(), None).map_err(|e| e.to_string())?;
        if p.family != f {
            return Err(format!("{} est compté en {} : unité incompatible.", p.name, p.family.as_str()));
        }
        self.stock_lot(&p, q, &loc, exp, reason, order, l.price)
    }

    /// Nouveau lot d'un produit connu, et son mouvement d'entrée.
    #[allow(clippy::too_many_arguments)]
    fn stock_lot(
        &self,
        p: &Product,
        q: f64,
        loc: &str,
        exp: Option<String>,
        reason: &str,
        order: Option<i64>,
        price: Option<f64>,
    ) -> Result<String, String> {
        let f = p.family;
        let (exp, kind, est) = dating(&p.name, loc, exp);
        let db = self.db.lock().unwrap();
        db.execute(
            "INSERT INTO lots (product_id, qty, location, expiry, expiry_kind, expiry_estimated, added_at, order_id, price)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![p.id, q, loc, exp, kind.as_str(), est, now(), order, price],
        )
        .map_err(|e| e.to_string())?;
        db.execute(
            "INSERT INTO movements (product_id, delta, reason, at) VALUES (?1, ?2, ?3, ?4)",
            params![p.id, q, reason, now()],
        )
        .map_err(|e| e.to_string())?;
        db.execute("UPDATE products SET location = ?2 WHERE id = ?1", params![p.id, loc]).map_err(|e| e.to_string())?;
        Ok(format!("{} : +{} ({loc}, {})", p.name, show(q, f), date_note(&exp, kind, est)))
    }

    /// Sortie de stock : lots entamés d'abord, puis ceux qui périment le plus tôt.
    /// Rend (ligne lisible, quantité manquante dans l'unité de base).
    pub fn take(&self, l: &Line, reason: &str, meal: Option<i64>) -> Result<(String, f64), String> {
        let p = self.resolve(&l.name)?;
        let (f, mut q) = qty_in(l)?;
        if p.family != f {
            return Err(format!("{} est compté en {} : unité incompatible.", p.name, p.family.as_str()));
        }
        let asked = q;
        let mut db = self.db.lock().unwrap();
        let tx = db.transaction().map_err(|e| e.to_string())?;
        let opened_days = shelf::after_opening(&p.name);
        let lots: Vec<(i64, f64, bool, Option<String>)> = {
            let mut st = tx
                .prepare(
                    "SELECT id, qty, opened, expiry FROM lots WHERE product_id = ?1 AND qty > 0
                     ORDER BY opened DESC, expiry IS NULL, expiry, added_at",
                )
                .map_err(|e| e.to_string())?;
            st.query_map([p.id], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? != 0, r.get(3)?)))
                .map_err(|e| e.to_string())?
                .collect::<rusqlite::Result<_>>()
                .map_err(|e| e.to_string())?
        };
        for (id, have, opened, expiry) in lots {
            if q <= 0.0 {
                break;
            }
            let used = have.min(q);
            q -= used;
            Self::draw(&tx, id, have, used, opened, expiry.as_deref(), opened_days).map_err(|e| e.to_string())?;
        }
        let taken = asked - q.max(0.0);
        if taken > 0.0 {
            tx.execute(
                "INSERT INTO movements (product_id, delta, reason, meal_id, at) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![p.id, -taken, reason, meal, now()],
            )
            .map_err(|e| e.to_string())?;
        }
        tx.commit().map_err(|e| e.to_string())?;
        let left = Self::stock_of(&db, p.id).unwrap_or(0.0);
        let mut s = format!("{} : -{} (reste {})", p.name, show(taken, f), show(left, f));
        if q > 1e-9 {
            s.push_str(&format!(" — {} de plus que le stock connu", show(q, f)));
        }
        Ok((s, q.max(0.0)))
    }

    /// Retire `used` d'un lot qui en contient `have` : supprimé s'il est vide,
    /// sinon marqué entamé, et sa date raccourcie à la première ouverture.
    fn draw(
        db: &Connection,
        id: i64,
        have: f64,
        used: f64,
        opened: bool,
        expiry: Option<&str>,
        opened_days: Option<i64>,
    ) -> rusqlite::Result<()> {
        if have - used <= 1e-9 {
            db.execute("DELETE FROM lots WHERE id = ?1", [id])?;
            return Ok(());
        }
        db.execute("UPDATE lots SET qty = qty - ?2, opened = 1 WHERE id = ?1", params![id, used])?;
        if let (false, Some(d)) = (opened, opened_days) {
            let limit = in_days(d);
            if expiry.is_none_or(|e| e > limit.as_str()) {
                db.execute(
                    "UPDATE lots SET expiry = ?2, expiry_kind = 'dlc', expiry_estimated = 1 WHERE id = ?1",
                    params![id, limit],
                )?;
            }
        }
        Ok(())
    }

    /// Lot en stock et son produit, lus dans la transaction qui va l'écrire.
    fn lot_in(db: &Connection, id: i64) -> Result<(Lot, i64), String> {
        db.query_row(
            "SELECT l.qty, l.opened, l.expiry, l.location, p.id, p.name, p.family, l.expiry_kind, l.expiry_estimated, l.added_at
             FROM lots l JOIN products p ON p.id = l.product_id WHERE l.id = ?1 AND l.qty > 0",
            [id],
            |r| {
                let lot = Lot {
                    id,
                    qty: r.get(0)?,
                    opened: r.get::<_, i64>(1)? != 0,
                    expiry: r.get(2)?,
                    location: r.get(3)?,
                    product: r.get(5)?,
                    family: Family::parse(&r.get::<_, String>(6)?).unwrap_or(Family::Piece),
                    kind: r.get::<_, Option<String>>(7)?.as_deref().and_then(Kind::parse),
                    estimated: r.get::<_, i64>(8)? != 0,
                    added_at: r.get(9)?,
                    nature: None,
                };
                Ok((lot, r.get(4)?))
            },
        )
        .optional()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Ce produit n'est plus en stock.".to_string())
    }

    /// Consomme un lot précis, en entier ou `qty` dans son unité de base.
    pub fn use_lot(&self, id: i64, qty: Option<f64>) -> Result<String, String> {
        if qty.is_some_and(|q| !(q.is_finite() && q > 0.0)) {
            return Err("Quantité positive attendue.".to_string());
        }
        let mut db = self.db.lock().unwrap();
        let tx = db.transaction().map_err(|e| e.to_string())?;
        let (l, p) = Self::lot_in(&tx, id)?;
        let used = qty.unwrap_or(l.qty).min(l.qty);
        Self::draw(&tx, id, l.qty, used, l.opened, l.expiry.as_deref(), shelf::after_opening(&l.product))
            .map_err(|e| e.to_string())?;
        tx.execute(
            "INSERT INTO movements (product_id, delta, reason, at) VALUES (?1, ?2, 'consommation', ?3)",
            params![p, -used, now()],
        )
        .map_err(|e| e.to_string())?;
        tx.commit().map_err(|e| e.to_string())?;
        Ok(format!("{} : -{} (reste {} dans ce lot)", l.product, show(used, l.family), show(l.qty - used, l.family)))
    }

    /// Corrige un lot précis : emplacement, quantité (unité de base), date imprimée.
    pub fn update_lot(
        &self,
        id: i64,
        to: Option<&str>,
        qty: Option<f64>,
        expiry: Option<&str>,
    ) -> Result<String, String> {
        let loc = to.filter(|x| !x.trim().is_empty()).map(location).transpose()?;
        let given = expiry.filter(|e| !e.trim().is_empty()).map(date).transpose()?;
        if qty.is_some_and(|q| !(q.is_finite() && q >= 0.0)) {
            return Err("Quantité positive attendue.".to_string());
        }
        let mut db = self.db.lock().unwrap();
        let tx = db.transaction().map_err(|e| e.to_string())?;
        let (l, p) = Self::lot_in(&tx, id)?;
        let mut done = Vec::new();
        if let Some(loc) = &loc {
            Self::move_lot(&tx, id, &l.location, l.expiry.clone(), loc, &given).map_err(|e| e.to_string())?;
            tx.execute("UPDATE products SET location = ?2 WHERE id = ?1", params![p, loc]).map_err(|e| e.to_string())?;
            done.push(format!("→ {loc}"));
        } else if let Some(e) = &given {
            tx.execute("UPDATE lots SET expiry = ?2, expiry_estimated = 0 WHERE id = ?1", params![id, e])
                .map_err(|e| e.to_string())?;
        }
        if let Some(e) = &given {
            done.push(format!("jusqu'au {e}"));
        }
        if let Some(q) = qty.filter(|q| (q - l.qty).abs() > 1e-9) {
            if q <= 1e-9 {
                tx.execute("DELETE FROM lots WHERE id = ?1", [id]).map_err(|e| e.to_string())?;
            } else {
                tx.execute("UPDATE lots SET qty = ?2 WHERE id = ?1", params![id, q]).map_err(|e| e.to_string())?;
            }
            tx.execute(
                "INSERT INTO movements (product_id, delta, reason, at) VALUES (?1, ?2, 'correction', ?3)",
                params![p, q - l.qty, now()],
            )
            .map_err(|e| e.to_string())?;
            done.push(format!("{} → {}", show(l.qty, l.family), show(q, l.family)));
        }
        tx.commit().map_err(|e| e.to_string())?;
        if done.is_empty() {
            return Ok(format!("{} : rien à changer", l.product));
        }
        Ok(format!("{} : {}", l.product, done.join(", ")))
    }

    /// Change un lot d'emplacement. Sans date donnée : la congélation allonge,
    /// la décongélation raccourcit. Rend la date retenue, le cas échéant.
    fn move_lot(
        db: &Connection,
        id: i64,
        from: &str,
        old: Option<String>,
        loc: &str,
        given: &Option<String>,
    ) -> rusqlite::Result<Option<(String, Kind, bool)>> {
        let (exp, kind, est) = match (given, from == "congelateur", loc == "congelateur") {
            (Some(e), ..) => (Some(e.clone()), None, false),
            (None, false, true) => (Some(in_days(90)), Some(Kind::Ddm), true),
            (None, true, false) => {
                let limit = in_days(shelf::THAWED_DAYS);
                (Some(old.filter(|o| *o < limit).unwrap_or(limit)), Some(Kind::Dlc), true)
            }
            (None, ..) => (None, None, false),
        };
        db.execute(
            "UPDATE lots SET location = ?2,
                expiry = COALESCE(?3, expiry),
                expiry_kind = COALESCE(?4, expiry_kind),
                expiry_estimated = CASE WHEN ?3 IS NULL THEN expiry_estimated ELSE ?5 END
             WHERE id = ?1",
            params![id, loc, exp, kind.map(Kind::as_str), est],
        )?;
        Ok(match (exp, kind) {
            (Some(e), Some(k)) => Some((e, k, est)),
            _ => None,
        })
    }

    /// Fixe la quantité totale d'un produit (inventaire), en ajustant le lot le plus ancien.
    pub fn set_total(&self, l: &Line) -> Result<String, String> {
        let p = self.resolve(&l.name)?;
        let (f, target) = if l.qty == 0.0 { (p.family, 0.0) } else { qty_in(l)? };
        if p.family != f {
            return Err(format!("{} est compté en {} : unité incompatible.", p.name, p.family.as_str()));
        }
        let have = Self::stock_of(&self.db.lock().unwrap(), p.id).map_err(|e| e.to_string())?;
        let diff = target - have;
        if diff.abs() < 1e-9 {
            return Ok(format!("{} : déjà {}", p.name, show(have, f)));
        }
        if diff > 0.0 {
            let loc = match l.location.as_deref().filter(|x| !x.trim().is_empty()) {
                Some(x) => location(x)?,
                None => self
                    .db
                    .lock()
                    .unwrap()
                    .query_row(
                        "SELECT location FROM lots WHERE product_id = ?1 ORDER BY added_at DESC LIMIT 1",
                        [p.id],
                        |r| r.get(0),
                    )
                    .optional()
                    .map_err(|e| e.to_string())?
                    .unwrap_or_else(|| "placard".to_string()),
            };
            let (exp, kind, est) = dating(&p.name, &loc, None);
            let db = self.db.lock().unwrap();
            db.execute(
                "INSERT INTO lots (product_id, qty, location, expiry, expiry_kind, expiry_estimated, added_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![p.id, diff, loc, exp, kind.as_str(), est, now()],
            )
            .map_err(|e| e.to_string())?;
            db.execute(
                "INSERT INTO movements (product_id, delta, reason, at) VALUES (?1, ?2, 'correction', ?3)",
                params![p.id, diff, now()],
            )
            .map_err(|e| e.to_string())?;
        } else {
            let fam_unit = match f {
                Family::Masse => "g",
                Family::Volume => "ml",
                Family::Piece => "pièce",
            };
            let down = Line { name: p.name.clone(), qty: -diff, unit: Some(fam_unit.into()), ..Default::default() };
            self.take(&down, "correction", None)?;
        }
        Ok(format!("{} : {} → {}", p.name, show(have, f), show(target, f)))
    }

    /// Déplace (tout ou partie) d'un produit, ex. au congélateur ; nouvelle date limite possible.
    pub fn relocate(&self, name: &str, to: &str, expiry: Option<&str>) -> Result<String, String> {
        let p = self.resolve(name)?;
        let loc = location(to)?;
        let given = expiry.filter(|e| !e.trim().is_empty()).map(date).transpose()?;
        let db = self.db.lock().unwrap();
        let lots: Vec<(i64, String, Option<String>)> = db
            .prepare("SELECT id, location, expiry FROM lots WHERE product_id = ?1 AND qty > 0")
            .and_then(|mut st| st.query_map([p.id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect())
            .map_err(|e| e.to_string())?;
        if lots.is_empty() {
            return Err(format!("{} : rien en stock à déplacer.", p.name));
        }
        let mut note = given.as_ref().map(|e| format!(", à consommer avant le {e}"));
        for (id, from, old) in lots {
            let set = Self::move_lot(&db, id, &from, old, &loc, &given).map_err(|e| e.to_string())?;
            if note.is_none()
                && let Some((e, k, est)) = set
            {
                note = Some(format!(", {}", date_note(&e, k, est)));
            }
        }
        db.execute("UPDATE products SET location = ?2 WHERE id = ?1", params![p.id, loc]).map_err(|e| e.to_string())?;
        Ok(format!("{} → {loc}{}", p.name, note.unwrap_or_default()))
    }

    /// Lots en stock, filtrés par emplacement ou nom ; les plus urgents d'abord.
    pub fn lots(&self, loc: Option<&str>, query: Option<&str>) -> rusqlite::Result<Vec<Lot>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(
            "SELECT l.id, p.name, p.family, l.qty, l.location, l.expiry, l.opened, p.folded,
                    l.expiry_kind, l.expiry_estimated, l.added_at, p.nature, p.category
             FROM lots l JOIN products p ON p.id = l.product_id WHERE l.qty > 0
             ORDER BY l.location, l.expiry IS NULL, l.expiry, p.name",
        )?;
        let words: Vec<String> = query.map(fold).unwrap_or_default().split_whitespace().map(str::to_string).collect();
        let rows = st.query_map([], |r| {
            let fam: String = r.get(2)?;
            Ok((
                Lot {
                    id: r.get(0)?,
                    product: r.get(1)?,
                    family: Family::parse(&fam).unwrap_or(Family::Piece),
                    qty: r.get(3)?,
                    location: r.get(4)?,
                    expiry: r.get(5)?,
                    kind: r.get::<_, Option<String>>(8)?.as_deref().and_then(Kind::parse),
                    estimated: r.get::<_, i64>(9)? != 0,
                    opened: r.get::<_, i64>(6)? != 0,
                    added_at: r.get(10)?,
                    nature: crate::nature::resolve(
                        r.get::<_, Option<String>>(11)?.as_deref().and_then(Nature::parse),
                        r.get::<_, Option<String>>(12)?.as_deref(),
                        &r.get::<_, String>(1)?,
                    ),
                },
                r.get::<_, String>(7)?,
            ))
        })?;
        let mut out = Vec::new();
        for x in rows {
            let (lot, folded) = x?;
            if loc.is_some_and(|l| l != lot.location) || !words.iter().all(|w| folded.contains(w.as_str())) {
                continue;
            }
            out.push(lot);
        }
        Ok(out)
    }

    pub fn add_meal(
        &self,
        day: &str,
        slot: &str,
        name: &str,
        place: &str,
        notes: Option<&str>,
    ) -> rusqlite::Result<i64> {
        let db = self.db.lock().unwrap();
        db.execute(
            "INSERT INTO meals (day, slot, name, place, notes, at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![day, slot, name, place, notes, now()],
        )?;
        Ok(db.last_insert_rowid())
    }

    /// Note un repas sauf s'il en existe déjà un ce jour-là à ce moment : `None` alors.
    pub fn add_meal_once(
        &self,
        day: &str,
        slot: &str,
        name: &str,
        place: &str,
        notes: Option<&str>,
    ) -> rusqlite::Result<Option<i64>> {
        let db = self.db.lock().unwrap();
        let n = db.execute(
            "INSERT INTO meals (day, slot, name, place, notes, at)
             SELECT ?1, ?2, ?3, ?4, ?5, ?6 WHERE NOT EXISTS (SELECT 1 FROM meals WHERE day = ?1 AND slot = ?2)",
            params![day, slot, name, place, notes, now()],
        )?;
        Ok((n > 0).then(|| db.last_insert_rowid()))
    }

    /// Portions d'un plat cuisiné mises au frigo. Le plat est un produit à part,
    /// de nature `plat` : un ingrédient du même nom n'est jamais retagué.
    pub fn add_dish(&self, dish: &str, portions: f64) -> Result<String, String> {
        let name = dish.trim();
        let p = {
            let db = self.db.lock().unwrap();
            let plat = Nature::Plat.as_str();
            let found = db
                .query_row(
                    "SELECT id, name, family, barcode FROM products WHERE folded = ?1 AND family = ?2 AND nature = ?3",
                    params![fold(name), Family::Piece.as_str(), plat],
                    Self::product_row,
                )
                .optional()
                .map_err(|e| e.to_string())?;
            match found {
                Some(p) => p,
                None => {
                    db.execute(
                        "INSERT INTO products (name, folded, family, nature, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
                        params![name, fold(name), Family::Piece.as_str(), plat, now()],
                    )
                    .map_err(|e| e.to_string())?;
                    Product { id: db.last_insert_rowid(), name: name.to_string(), family: Family::Piece, barcode: None }
                }
            }
        };
        self.stock_lot(&p, portions, "frigo", None, "reste", None, None)
    }

    /// Repas depuis une date : (jour, moment, plat, lieu).
    pub fn meals_since(&self, day: &str) -> rusqlite::Result<Vec<(String, String, String, String)>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(
            "SELECT day, slot, name, place FROM meals WHERE day >= ?1
             ORDER BY day, CASE slot WHEN 'petit-dejeuner' THEN 0 WHEN 'midi' THEN 1 WHEN 'gouter' THEN 2 ELSE 3 END, at",
        )?;
        st.query_map([day], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?.collect()
    }

    pub fn add_order(
        &self,
        store: &str,
        reference: Option<&str>,
        pickup: Option<&str>,
        total: Option<f64>,
        lines: &[Line],
    ) -> rusqlite::Result<i64> {
        let db = self.db.lock().unwrap();
        db.execute(
            "INSERT INTO orders (store, reference, pickup, total, status, lines, created_at) VALUES (?1, ?2, ?3, ?4, 'a_retirer', ?5, ?6)",
            params![store, reference, pickup, total, serde_json::to_string(lines).unwrap_or_default(), now()],
        )?;
        Ok(db.last_insert_rowid())
    }

    /// Commande déjà enregistrée sous cette référence, s'il y en a une.
    pub fn order_by_reference(&self, reference: &str) -> rusqlite::Result<Option<i64>> {
        let db = self.db.lock().unwrap();
        db.query_row("SELECT id FROM orders WHERE reference = ?1 ORDER BY id LIMIT 1", [reference], |r| r.get(0))
            .optional()
    }

    pub fn orders(&self, only_pending: bool) -> rusqlite::Result<Vec<Order>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(&format!(
            "SELECT id, store, reference, pickup, total, status, lines FROM orders {} ORDER BY id DESC LIMIT 20",
            if only_pending { "WHERE status = 'a_retirer'" } else { "" }
        ))?;
        st.query_map([], |r| {
            let lines: String = r.get(6)?;
            Ok(Order {
                id: r.get(0)?,
                store: r.get(1)?,
                reference: r.get(2)?,
                pickup: r.get(3)?,
                total: r.get(4)?,
                status: r.get(5)?,
                lines: serde_json::from_str(&lines).unwrap_or_default(),
            })
        })?
        .collect()
    }

    /// Produit connu par code-barres : (nom, nature, emplacement habituel).
    pub fn known(&self, barcode: &str) -> rusqlite::Result<Option<Known>> {
        let db = self.db.lock().unwrap();
        db.query_row(
            "SELECT p.name, p.nature, p.category, COALESCE(p.location,
                (SELECT location FROM lots WHERE product_id = p.id ORDER BY added_at DESC LIMIT 1))
             FROM products p WHERE p.barcode = ?1",
            [barcode],
            |r| {
                let name: String = r.get(0)?;
                let manual = r.get::<_, Option<String>>(1)?.as_deref().and_then(Nature::parse);
                let n = crate::nature::resolve(manual, r.get::<_, Option<String>>(2)?.as_deref(), &name);
                Ok((name, n, r.get(3)?))
            },
        )
        .optional()
    }

    /// Produit déjà connu (code-barres, sinon nom exact, le plus récent) : sa famille,
    /// sa zone habituelle et son dernier apport, pour proposer la même quantité.
    pub fn usual(&self, name: &str, barcode: Option<&str>) -> rusqlite::Result<Option<Usual>> {
        let db = self.db.lock().unwrap();
        db.query_row(
            "SELECT p.name, p.family, p.nature, p.category, COALESCE(p.location,
                (SELECT location FROM lots WHERE product_id = p.id ORDER BY added_at DESC LIMIT 1)),
                (SELECT delta FROM movements WHERE product_id = p.id AND delta > 0 ORDER BY at DESC, id DESC LIMIT 1)
             FROM products p WHERE p.barcode = ?2 OR p.folded = ?1 ORDER BY p.barcode = ?2 DESC, p.id DESC LIMIT 1",
            params![fold(name.trim()), barcode],
            |r| {
                let name: String = r.get(0)?;
                let manual = r.get::<_, Option<String>>(2)?.as_deref().and_then(Nature::parse);
                let nature = crate::nature::resolve(manual, r.get::<_, Option<String>>(3)?.as_deref(), &name);
                Ok(Usual {
                    family: Family::parse(&r.get::<_, String>(1)?).unwrap_or(Family::Piece),
                    name,
                    nature,
                    location: r.get(4)?,
                    pack: r.get(5)?,
                })
            },
        )
        .optional()
    }

    /// Emplacement habituel d'un produit (code-barres, sinon nom exact), s'il a déjà été rangé.
    pub fn usual_location(&self, name: &str, barcode: Option<&str>) -> rusqlite::Result<Option<String>> {
        let db = self.db.lock().unwrap();
        db.query_row(
            "SELECT COALESCE(p.location,
                (SELECT location FROM lots WHERE product_id = p.id ORDER BY added_at DESC LIMIT 1))
             FROM products p WHERE p.barcode = ?2 OR p.folded = ?1 ORDER BY p.barcode = ?2 DESC LIMIT 1",
            params![fold(name.trim()), barcode],
            |r| r.get(0),
        )
        .optional()
        .map(Option::flatten)
    }

    /// Lignes d'une commande déjà traitées (rangées ou en rupture), par index.
    pub fn lines_done(&self, order: i64) -> rusqlite::Result<Vec<usize>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare("SELECT idx FROM order_lines WHERE order_id = ?1")?;
        st.query_map([order], |r| r.get::<_, i64>(0).map(|i| i as usize))?.collect()
    }

    /// Marque une ligne traitée ; false si elle l'était déjà.
    pub fn claim_line(&self, order: i64, idx: usize, state: &str) -> rusqlite::Result<bool> {
        let n = self.db.lock().unwrap().execute(
            "INSERT OR IGNORE INTO order_lines (order_id, idx, state, at) VALUES (?1, ?2, ?3, ?4)",
            params![order, idx as i64, state, now()],
        )?;
        Ok(n == 1)
    }

    pub fn release_line(&self, order: i64, idx: usize) -> rusqlite::Result<()> {
        self.db
            .lock()
            .unwrap()
            .execute("DELETE FROM order_lines WHERE order_id = ?1 AND idx = ?2", params![order, idx as i64])?;
        Ok(())
    }

    pub fn mark_received(&self, id: i64) -> rusqlite::Result<()> {
        self.db
            .lock()
            .unwrap()
            .execute("UPDATE orders SET status = 'retiree', received_at = ?2 WHERE id = ?1", params![id, now()])?;
        Ok(())
    }

    /// Crée ou remplace une recette (même nom, accents et casse ignorés).
    pub fn set_recipe(&self, r: &Recipe) -> rusqlite::Result<()> {
        let data = serde_json::to_string(r).expect("recette sérialisable");
        self.db.lock().unwrap().execute(
            "INSERT INTO recipes (name, data, updated_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(name) DO UPDATE SET data = ?2, updated_at = ?3",
            params![fold(r.title.trim()), data, now()],
        )?;
        Ok(())
    }

    pub fn recipes(&self) -> rusqlite::Result<Vec<Recipe>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare("SELECT data FROM recipes ORDER BY name")?;
        let rows = st.query_map([], |r| r.get::<_, String>(0))?;
        // Une ligne illisible est ignorée plutôt que de bloquer tout le carnet.
        Ok(rows.filter_map(|d| d.ok().and_then(|d| serde_json::from_str(&d).ok())).collect())
    }

    /// Nom exact, sinon seule recette contenant tous les mots demandés.
    pub fn recipe(&self, query: &str) -> rusqlite::Result<Option<Recipe>> {
        let q = fold(query.trim());
        let all = self.recipes()?;
        if let Some(r) = all.iter().find(|r| fold(&r.title) == q) {
            return Ok(Some(r.clone()));
        }
        // Mots entiers : « riz » ne doit pas trouver « chorizo ».
        let split = |s: &str| s.split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).map(str::to_string).collect::<Vec<_>>();
        let words = split(&q);
        let hits: Vec<&Recipe> =
            all.iter().filter(|r| { let t = split(&fold(&r.title)); words.iter().all(|w| t.contains(w)) }).collect();
        Ok(if hits.len() == 1 { Some(hits[0].clone()) } else { None })
    }

    /// Remplace le plan de la période [from, to]. Les restes hors période d'un
    /// plat cuisiné dans la période disparaissent avec lui : renvoyés (jour,
    /// créneau) pour retirer leurs événements.
    pub fn replace_plan(&self, from: &str, to: &str, meals: &[crate::plan::Meal]) -> rusqlite::Result<Vec<(String, String)>> {
        let mut db = self.db.lock().unwrap();
        let tx = db.transaction()?;
        let orphans: Vec<(String, String)> = {
            let mut st = tx.prepare(
                "SELECT day, slot FROM meal_plan WHERE cooked >= ?1 AND cooked <= ?2 AND (day < ?1 OR day > ?2)",
            )?;
            st.query_map(params![from, to], |r| Ok((r.get(0)?, r.get(1)?)))?.collect::<rusqlite::Result<_>>()?
        };
        tx.execute(
            "DELETE FROM meal_plan WHERE (day >= ?1 AND day <= ?2) OR (cooked >= ?1 AND cooked <= ?2)",
            params![from, to],
        )?;
        for m in meals {
            tx.execute(
                "INSERT INTO meal_plan (day, slot, dish, kind, cooked, note) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![m.day.to_string(), m.slot.as_str(), m.dish, m.kind.as_str(), m.from.to_string(), m.note],
            )?;
        }
        tx.commit()?;
        Ok(orphans)
    }

    pub fn set_plan_note(&self, day: &str, slot: &str, note: Option<&str>) -> rusqlite::Result<()> {
        self.db.lock().unwrap().execute(
            "UPDATE meal_plan SET note = ?3 WHERE day = ?1 AND slot = ?2",
            params![day, slot, note],
        )?;
        Ok(())
    }

    /// Plan enregistré sur [from, to], dans l'ordre.
    pub fn plan(&self, from: &str, to: &str) -> rusqlite::Result<Vec<crate::plan::Meal>> {
        use crate::nature::Slot;
        use crate::plan::{Meal, MealKind};
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(
            "SELECT day, slot, dish, kind, cooked, note FROM meal_plan WHERE day >= ?1 AND day <= ?2
             ORDER BY day, CASE slot WHEN 'midi' THEN 0 ELSE 1 END",
        )?;
        let d = |s: String| chrono::NaiveDate::parse_from_str(&s, "%Y-%m-%d").unwrap_or_default();
        let rows = st.query_map(params![from, to], |r| {
            Ok(Meal {
                day: d(r.get(0)?),
                slot: Slot::parse(&r.get::<_, String>(1)?).unwrap_or(Slot::Soir),
                dish: r.get(2)?,
                kind: MealKind::parse(&r.get::<_, String>(3)?).unwrap_or(MealKind::Cook),
                from: d(r.get(4)?),
                note: r.get(5)?,
            })
        })?;
        rows.collect()
    }

    pub fn set_pref(&self, k: &str, v: &str) -> rusqlite::Result<()> {
        let db = self.db.lock().unwrap();
        if v.trim().is_empty() {
            db.execute("DELETE FROM prefs WHERE k = ?1", [k])?;
        } else {
            db.execute(
                "INSERT OR REPLACE INTO prefs (k, v, updated_at) VALUES (?1, ?2, ?3)",
                params![k, v.trim(), now()],
            )?;
        }
        Ok(())
    }

    pub fn prefs(&self) -> rusqlite::Result<Vec<(String, String)>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare("SELECT k, v FROM prefs ORDER BY k")?;
        st.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?.collect()
    }

    /// Dépenses de courses enregistrées depuis une date (somme des commandes).
    pub fn spent_since(&self, ts: i64) -> rusqlite::Result<f64> {
        self.db.lock().unwrap().query_row(
            "SELECT COALESCE(SUM(total), 0) FROM orders WHERE created_at >= ?1",
            [ts],
            |r| r.get(0),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(name: &str, qty: f64, unit: &str, loc: &str) -> Line {
        Line { name: name.into(), qty, unit: Some(unit.into()), location: Some(loc.into()), ..Default::default() }
    }

    #[test]
    fn locations_are_normalised() {
        assert_eq!(location("Sous l'évier").unwrap(), "evier");
        assert_eq!(location("salle de bain").unwrap(), "sdb");
        assert_eq!(location("SDB").unwrap(), "sdb");
        assert_eq!(location("Congélo").unwrap(), "congelateur");
        assert_eq!(location("fruits et légumes").unwrap(), "corbeille");
        assert!(location("cave").is_err());
    }

    #[test]
    fn etagere_location_accepted() {
        assert_eq!(location("Étagère").unwrap(), "etagere");
        assert_eq!(location("etagere").unwrap(), "etagere");
        assert_eq!(location("etageres").unwrap(), "etagere");
    }

    #[test]
    fn take_uses_opened_then_earliest_expiry_and_reports_shortfall() {
        let s = Store::memory();
        let mut a = line("Œufs plein air", 6.0, "pièce", "frigo");
        a.expiry = Some("2026-11-20".into());
        s.add(&a, "achat", None).unwrap();
        let mut b = line("oeufs plein air", 1.0, "douzaine", "frigo");
        b.expiry = Some("2026-11-15".into());
        s.add(&b, "achat", None).unwrap();
        let lots = s.lots(Some("frigo"), Some("oeufs")).unwrap();
        assert_eq!(lots.len(), 2, "même produit, deux lots");
        assert_eq!(lots[0].expiry.as_deref(), Some("2026-11-15"));

        let (t, short) = s.take(&line("œufs", 3.0, "", "frigo"), "repas", None).unwrap();
        assert_eq!(t, "Œufs plein air : -3 pièces (reste 15 pièces)");
        assert_eq!(short, 0.0);
        let lots = s.lots(None, Some("oeufs")).unwrap();
        assert!(lots[0].opened && lots[0].qty == 9.0, "{lots:?}");

        let (t, short) = s.take(&line("oeufs", 20.0, "", "frigo"), "repas", None).unwrap();
        assert!(t.contains("reste 0 pièce") && t.contains("5 pièces de plus que le stock connu"), "{t}");
        assert_eq!(short, 5.0);
        assert!(s.lots(None, None).unwrap().is_empty());
    }

    #[test]
    fn units_must_match_the_product_family() {
        let s = Store::memory();
        s.add(&line("Carottes", 1.0, "kg", "corbeille"), "achat", None).unwrap();
        let (t, _) = s.take(&line("carottes", 250.0, "g", ""), "repas", None).unwrap();
        assert_eq!(t, "Carottes : -250 g (reste 750 g)");
        assert!(s.take(&line("carottes", 2.0, "pièce", ""), "repas", None).unwrap_err().contains("compté en masse"));
        assert!(s.take(&line("navets", 1.0, "kg", ""), "repas", None).unwrap_err().contains("Aucun produit"));
    }

    #[test]
    fn inventory_correction_and_relocation() {
        let s = Store::memory();
        s.add(&line("Cuisses de poulet", 4.0, "pièce", "frigo"), "achat", None).unwrap();
        assert_eq!(s.set_total(&line("cuisses", 3.0, "pièce", "")).unwrap(), "Cuisses de poulet : 4 pièces → 3 pièces");
        assert!(s.set_total(&line("cuisses", 5.0, "", "")).unwrap().ends_with("5 pièces"));
        let t = s.relocate("cuisses", "congélateur", Some("2027-02-01")).unwrap();
        assert_eq!(t, "Cuisses de poulet → congelateur, à consommer avant le 2027-02-01");
        assert!(s.lots(Some("frigo"), None).unwrap().is_empty());
        assert_eq!(s.lots(Some("congelateur"), None).unwrap().iter().map(|l| l.qty).sum::<f64>(), 5.0);
    }

    #[test]
    fn every_lot_gets_a_date_estimated_or_given() {
        let s = Store::memory();
        let t = s.add(&line("Riz basmati", 1.0, "kg", "placard"), "achat", None).unwrap();
        assert_eq!(t, format!("Riz basmati : +1 kg (placard, jusqu'au {} (DDM estimée))", in_days(365)));
        let mut yaourt = line("Yaourts nature", 8.0, "pièce", "frigo");
        yaourt.expiry = Some("2030-01-15".into());
        let t = s.add(&yaourt, "achat", None).unwrap();
        assert!(t.ends_with("jusqu'au 2030-01-15 (DLC))"), "{t}");
        let lots = s.lots(None, None).unwrap();
        assert!(lots.iter().all(|l| l.expiry.is_some()));
        assert!(lots.iter().any(|l| l.estimated && l.kind == Some(Kind::Ddm)));
        assert!(lots.iter().any(|l| !l.estimated && l.kind == Some(Kind::Dlc)));
    }

    #[test]
    fn opening_shortens_and_freezing_extends() {
        let s = Store::memory();
        s.add(&line("Lait de coco", 800.0, "ml", "placard"), "achat", None).unwrap();
        s.take(&line("lait coco", 200.0, "ml", ""), "repas", None).unwrap();
        let l = &s.lots(None, None).unwrap()[0];
        assert_eq!((l.expiry.clone(), l.kind, l.opened), (Some(in_days(3)), Some(Kind::Dlc), true));
        s.take(&line("lait coco", 200.0, "ml", ""), "repas", None).unwrap();
        assert_eq!(s.lots(None, None).unwrap()[0].expiry, Some(in_days(3)), "pas raccourci deux fois");

        s.add(&line("Riz basmati", 1.0, "kg", "placard"), "achat", None).unwrap();
        s.take(&line("riz", 100.0, "g", ""), "repas", None).unwrap();
        assert_eq!(s.lots(None, Some("riz")).unwrap()[0].expiry, Some(in_days(365)), "le riz ne tourne pas");

        s.add(&line("Escalopes de dinde", 4.0, "pièce", "frigo"), "achat", None).unwrap();
        assert_eq!(s.lots(None, Some("dinde")).unwrap()[0].expiry, Some(in_days(3)));
        let t = s.relocate("dinde", "congélo", None).unwrap();
        assert_eq!(t, format!("Escalopes de dinde → congelateur, jusqu'au {} (DDM estimée)", in_days(90)));
        let t = s.relocate("dinde", "frigo", None).unwrap();
        assert_eq!(t, format!("Escalopes de dinde → frigo, jusqu'au {} (DLC estimée)", in_days(2)));
    }

    #[test]
    fn ambiguous_names_prefer_the_one_in_stock() {
        let s = Store::memory();
        s.add(&line("Lait demi-écrémé", 1.0, "l", "placard"), "achat", None).unwrap();
        s.add(&line("Lait de coco", 400.0, "ml", "placard"), "achat", None).unwrap();
        assert!(s.resolve("lait").unwrap_err().contains("ambigu"));
        assert_eq!(s.resolve("lait coco").unwrap().name, "Lait de coco");
        s.take(&line("lait coco", 400.0, "ml", ""), "repas", None).unwrap();
        assert_eq!(s.resolve("lait").unwrap().name, "Lait demi-écrémé", "seul encore en stock");
    }

    fn movements(s: &Store) -> (i64, f64) {
        s.db.lock().unwrap().query_row("SELECT COUNT(*), COALESCE(SUM(delta), 0) FROM movements WHERE reason != 'achat'", [], |r| Ok((r.get(0)?, r.get(1)?))).unwrap()
    }

    #[test]
    fn lot_use_twice_logs_one_movement() {
        let s = Store::memory();
        s.add(&line("Yaourts nature", 4.0, "pièce", "frigo"), "achat", None).unwrap();
        let id = s.lots(None, None).unwrap()[0].id;
        s.use_lot(id, None).unwrap();
        assert_eq!(s.use_lot(id, None).unwrap_err(), "Ce produit n'est plus en stock.");
        assert_eq!(movements(&s), (1, -4.0));
    }

    #[test]
    fn concurrent_partial_uses_never_go_below_zero() {
        let s = std::sync::Arc::new(Store::memory());
        s.add(&line("Yaourts nature", 20.0, "pièce", "frigo"), "achat", None).unwrap();
        let id = s.lots(None, None).unwrap()[0].id;
        let hs: Vec<_> = (0..40)
            .map(|_| {
                let s = s.clone();
                std::thread::spawn(move || {
                    let _ = s.use_lot(id, Some(1.0));
                    let _ = s.update_lot(id, None, None, Some("2030-01-01"));
                })
            })
            .collect();
        hs.into_iter().for_each(|h| h.join().unwrap());
        let left: f64 = s.db.lock().unwrap().query_row("SELECT COALESCE(SUM(qty), 0) FROM lots", [], |r| r.get(0)).unwrap();
        assert_eq!(left, 0.0);
        assert_eq!(movements(&s).1, -20.0, "une sortie par unité réellement prise");
    }
}
