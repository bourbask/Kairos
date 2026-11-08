//! Outils MCP du module alimentation.
//!
//! La tête converse (plats, recettes, batch cooking) et se sert de ces outils
//! pour tenir l'inventaire à jour : commande de drive enregistrée puis reçue,
//! consommation au fil des repas, corrections d'inventaire.

use std::sync::Arc;

use chrono::Datelike;
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;

use crate::nature::Nature;
use crate::off::Off;
use crate::shelf::Kind;
use crate::store::{Line, Lot, Store, date};
use crate::units::show;

type Out = Result<String, String>;

pub struct Settings {
    pub tz: chrono_tz::Tz,
    /// Calendrier des repas ; absent : le plan est seulement enregistré.
    pub caldav: Option<Arc<crate::caldav::CalDav>>,
}

#[derive(Clone)]
pub struct FoodServer {
    store: Arc<Store>,
    off: Arc<Off>,
    settings: Arc<Settings>,
    tool_router: ToolRouter<Self>,
}

const PREVIEW: &str = "Aperçu — rien n'a été enregistré. Relancer avec confirm=true pour valider.";

fn euros(x: f64) -> String {
    format!("{:.2} €", x).replace('.', ",")
}

#[derive(Deserialize, schemars::JsonSchema)]
struct LinesArgs {
    /// Produits.
    items: Vec<Line>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct OrderArgs {
    /// Enseigne (ex. « drive »).
    store: String,
    /// Numéro de commande.
    reference: Option<String>,
    /// Retrait prévu (AAAA-MM-JJ).
    pickup: Option<String>,
    /// Montant total payé, en euros.
    total: Option<f64>,
    /// Lignes commandées, avec l'emplacement où elles seront rangées.
    items: Vec<Line>,
    /// false (défaut) : aperçu ; true : enregistrer.
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ImportArgs {
    /// Texte du bon de commande.
    text: String,
    /// Enseigne (« drive » par défaut).
    store: Option<String>,
    /// false (défaut) : aperçu ; true : enregistrer.
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ReceiveArgs {
    /// Commande à réceptionner ; absente = la plus récente à retirer.
    order_id: Option<i64>,
    /// Produits absents de la commande (ruptures), par nom.
    missing: Option<Vec<String>>,
    /// false (défaut) : aperçu ; true : mettre en stock.
    confirm: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct StockArgs {
    /// frigo, congelateur, placard, etagere ou corbeille.
    location: Option<String>,
    /// Mots du nom de produit.
    query: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct UseFirstArgs {
    /// Horizon en jours (4 par défaut).
    days: Option<i64>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct MoveArgs {
    /// Produit à déplacer.
    name: String,
    /// frigo, congelateur, placard, etagere ou corbeille.
    to: String,
    /// Nouvelle date limite (AAAA-MM-JJ), ex. après congélation.
    expiry: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct MealArgs {
    /// petit-dejeuner, midi, gouter ou soir.
    slot: String,
    /// Plat (« poulet rôti, carottes », « pizza »).
    name: String,
    /// maison (défaut), bureau, restaurant, ailleurs.
    place: Option<String>,
    /// Jour (AAAA-MM-JJ) ; aujourd'hui par défaut.
    day: Option<String>,
    /// Ingrédients pris dans le stock (déduits de l'inventaire).
    ingredients: Option<Vec<Line>>,
    /// Remarque libre (portions restantes, ressenti…).
    notes: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct MealsArgs {
    /// Nombre de jours en arrière (7 par défaut, 60 au plus).
    days: Option<i64>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct CookArgs {
    /// petit-dejeuner, midi, gouter ou soir.
    slot: String,
    /// maison (défaut), boulot ou rando.
    context: Option<String>,
    /// Jour du repas (AAAA-MM-JJ) ; aujourd'hui par défaut.
    day: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct PlanItem {
    /// AAAA-MM-JJ.
    day: String,
    /// midi ou soir.
    slot: String,
    /// Plat cuisiné ce jour-là (nom de la recette du carnet de préférence).
    dish: String,
    /// Portions cuisinées (1 par défaut) : les suivantes deviennent des restes.
    portions: Option<u32>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct PlanArgs {
    /// Premier jour du plan (AAAA-MM-JJ).
    start: String,
    /// Durée en jours (7 par défaut, 14 au plus).
    days: Option<u32>,
    /// Plats cuisinés seulement ; les restes sont placés automatiquement.
    meals: Vec<PlanItem>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct PlanReadArgs {
    /// Premier jour (AAAA-MM-JJ) ; aujourd'hui par défaut.
    day: Option<String>,
    /// Nombre de jours (7 par défaut).
    days: Option<u32>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct RecipeArgs {
    /// Nom du plat (« Dhal de lentilles corail ») ; un nom existant est remplacé.
    name: String,
    /// Portions produites (2 par défaut).
    portions: Option<u32>,
    /// Préparation, en minutes.
    prep_minutes: Option<u32>,
    /// Cuisson, en minutes.
    cook_minutes: Option<u32>,
    /// Ingrédients pesés : nom tel qu'en stock, quantité, unité.
    #[serde(default)]
    ingredients: Vec<crate::store::Ingredient>,
    /// Étapes, une par élément, dans l'ordre (sans numéro).
    steps: Vec<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct RecipesArgs {
    /// Recette à lire ; absent : liste du carnet.
    name: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct NatureArgs {
    /// Produit à corriger ; absent avec `nature` absente : liste des produits à qualifier.
    name: Option<String>,
    /// proteine, legume, fruit, feculent, laitier, sucre, condiment, epice, boisson, base ou plat.
    nature: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct PrefArgs {
    /// Sujet (ex. « n'aime pas », « équipement », « budget », « batch cooking », « objectifs »).
    key: String,
    /// Valeur en texte libre ; vide = supprimer.
    value: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct LookupArgs {
    /// Code-barres EAN (8 à 14 chiffres).
    barcode: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppProductArgs {
    /// Code-barres EAN (8 à 14 chiffres).
    barcode: Option<String>,
    /// Nom, quand il n'y a pas de code-barres.
    name: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppLotArgs {
    /// Identifiant du lot (app_inventory).
    lot: i64,
    /// Quantité consommée, unité du lot ; absente : tout le lot.
    qty: Option<f64>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppMealDoneArgs {
    /// AAAA-MM-JJ.
    day: String,
    /// midi ou soir.
    slot: String,
    /// Portions mangées (1 par défaut).
    portions: Option<u32>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppLotUpdateArgs {
    lot: i64,
    location: Option<String>,
    /// Quantité restante, unité du lot.
    qty: Option<f64>,
    /// AAAA-MM-JJ.
    expiry: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppOrderLineArgs {
    order: i64,
    index: usize,
    /// Zone de rangement ; absente : celle proposée par app_orders.
    location: Option<String>,
    /// AAAA-MM-JJ.
    expiry: Option<String>,
    /// true : rupture, rien en stock.
    missing: Option<bool>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct AppAddArgs {
    name: Option<String>,
    barcode: Option<String>,
    qty: f64,
    unit: Option<String>,
    location: String,
    /// AAAA-MM-JJ.
    expiry: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct DecideArgs {
    /// Identifiant de la décision (`decisions`).
    id: String,
    /// Identifiant de l'option choisie.
    choice: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct CardsArgs {
    /// Jour (AAAA-MM-JJ) ; aujourd'hui par défaut.
    day: Option<String>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct BriefArgs {
    /// Contrat commun des sections de brief.
    #[allow(dead_code)]
    period: tentacle_common::BriefPeriod,
}

/// Date dans l'horizon, ou dernier sixième de la durée de conservation entamé.
fn pressing(expiry: chrono::NaiveDate, added: chrono::NaiveDate, today: chrono::NaiveDate, days: i64) -> bool {
    let window = days.max((expiry - added).num_days() / 6);
    (expiry - today).num_days() <= window
}

fn expiry(l: &Lot) -> Option<chrono::NaiveDate> {
    l.expiry.as_deref().and_then(|e| chrono::NaiveDate::parse_from_str(e, "%Y-%m-%d").ok())
}

/// Au-delà, une durée estimée décrit de l'épicerie sèche, pas du frais.
const FRESH_SPAN_DAYS: i64 = 30;

/// Entretien et hygiène : ni repas, ni urgence, ni briefing.
fn non_food(l: &Lot) -> bool {
    // La nature prime : des oignons rangés sous l'évier restent de l'alimentaire.
    match l.nature {
        Some(n) => !n.is_food(),
        None => matches!(l.location.as_str(), "evier" | "sdb"),
    }
}

/// Se gâte vraiment : DLC, produit gardé au frais (œufs, beurre), frais sans
/// date lue (estimation courte) ou nature de date inconnue (prudence). Une DDM
/// imprimée hors du frigo, ou estimée à long terme (riz, pâtes), n'est jamais urgente.
fn perishable(l: &Lot) -> bool {
    if non_food(l) {
        return false;
    }
    let short = || {
        let added = chrono::DateTime::from_timestamp(l.added_at, 0).map(|t| t.date_naive());
        matches!((expiry(l), added), (Some(e), Some(a)) if (e - a).num_days() <= FRESH_SPAN_DAYS)
    };
    l.kind != Some(Kind::Ddm) || l.location == "frigo" || (l.estimated && short())
}

/// Zones du plan de la cuisine : valeur stockée, libellé affiché.
const ZONES: [(&str, &str); 7] = [
    ("frigo", "Frigo"),
    ("congelateur", "Congélo"),
    ("placard", "Placard"),
    ("etagere", "Étagère"),
    ("corbeille", "Table"),
    ("evier", "Sous l'évier"),
    ("sdb", "Salle de bain"),
];

/// Un produit rangé là, à cette date (ou sans), se gâte-t-il vraiment ?
fn fresh(name: &str, loc: &str, given: Option<String>) -> bool {
    let (exp, kind, estimated) = crate::store::dating(name, loc, given);
    perishable(&Lot {
        id: 0,
        product: name.to_string(),
        family: crate::units::Family::Piece,
        qty: 0.0,
        location: loc.to_string(),
        expiry: Some(exp),
        kind: Some(kind),
        estimated,
        opened: false,
        added_at: chrono::Utc::now().timestamp(),
        nature: crate::nature::from_name(name),
    })
}

/// Contenances usuelles selon la nature : liquides en ml, le reste en g.
fn sizes(n: Nature) -> &'static [&'static str] {
    match n {
        Nature::Boisson | Nature::Entretien | Nature::Hygiene => &["250ml", "500ml", "750ml", "1000ml", "1500ml"],
        Nature::Condiment => &["250ml", "500ml", "250g", "500g"],
        _ => &["125g", "250g", "500g", "1000g"],
    }
}

/// Nom d'un produit de la base ouverte, marque comprise.
fn off_name(p: &crate::off::OffProduct) -> String {
    match &p.brand {
        Some(b) => format!("{} ({b})", p.name),
        None => p.name.clone(),
    }
}

fn message(m: String) -> Out {
    Ok(serde_json::json!({ "message": m }).to_string())
}

/// « - lun. 09/11 midi : Farfalle (boîte) ».
fn plan_line(m: &crate::plan::Meal) -> String {
    use crate::plan::MealKind;
    const DAYS: [&str; 7] = ["lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."];
    let suffix = match m.kind {
        MealKind::Cook => "",
        MealKind::Leftover => " (reste)",
        MealKind::Box => " (boîte)",
    };
    format!(
        "- {} {} {} : {}{suffix}",
        DAYS[m.day.weekday().num_days_from_monday() as usize],
        m.day.format("%d/%m"),
        m.slot.as_str(),
        m.dish
    )
}

const STALE_MAX: usize = 5;
/// Cartes « à finir » du jour, une par produit.
const URGENT_CARDS: usize = 3;
const PER_GROUP: usize = 8;

impl FoodServer {
    pub fn new(store: Arc<Store>, off: Arc<Off>, settings: Arc<Settings>) -> Self {
        Self { store, off, settings, tool_router: Self::tool_router() }
    }

    fn today(&self) -> chrono::NaiveDate {
        chrono::Utc::now().with_timezone(&self.settings.tz).date_naive()
    }

    /// Cartes du jour pour l'application : repas planifiés, rappel de boîte, produits à finir.
    fn cards_json(&self, day: &str) -> Result<String, String> {
        use crate::nature::Slot;
        use serde_json::json;
        let d = chrono::NaiveDate::parse_from_str(&date(day)?, "%Y-%m-%d").map_err(|e| e.to_string())?;
        let meals = self.store.plan(&d.to_string(), &d.to_string()).map_err(|e| e.to_string())?;
        let mut cards = Vec::new();
        for m in &meals {
            let (slot, label, time) = match m.slot {
                Slot::Midi => ("midi", "Midi", "12:15"),
                Slot::Soir => ("soir", "Soir", "19:00"),
                _ => continue,
            };
            let recipe = self.store.recipe(&m.dish).map_err(|e| e.to_string())?;
            let body = match m.kind {
                crate::plan::MealKind::Leftover => Some("reste".to_string()),
                _ => recipe.as_ref().and_then(|r| r.timing()),
            };
            let mut c = json!({
                "id": format!("repas-{d}-{slot}"), "kind": "info", "time": time,
                "title": format!("{label} · {}", m.dish),
            });
            if let Some(b) = body {
                c["body"] = b.into();
            }
            if let Some(r) = &recipe {
                c["detail"] = crate::plan::recipe_text(r, None).into();
            }
            let mut done = json!({"tool": "app_meal_done", "args": {"day": d.to_string(), "slot": slot}});
            let options: Vec<u32> = match (m.kind, &recipe) {
                (crate::plan::MealKind::Cook, Some(r)) => (1..=r.portions).collect(),
                (crate::plan::MealKind::Cook, None) => vec![],
                _ => vec![1, 2, 3],
            };
            if options.len() > 1 {
                done["choices"] = json!({"label": "Portions mangées", "key": "portions", "options": options, "default": 1});
            }
            // Un repas à venir ne se déclare pas mangé.
            if d <= self.today() {
                c["done"] = done;
            }
            cards.push(c);
            if m.slot == Slot::Soir && m.note.as_deref() == Some(crate::plan::BOX_NOTE) {
                // Sans action : la portion est déjà au frigo, la carte reste une note locale.
                cards.push(json!({
                    "id": format!("box-{d}"), "kind": "info", "time": "20:00",
                    "title": "Box de demain", "body": crate::plan::BOX_NOTE,
                }));
            }
        }
        // ponytail: l'urgence se calcule depuis aujourd'hui, donc seulement pour le jour courant.
        if d == self.today() {
            let mut seen = std::collections::HashSet::new();
            for l in self.urgent(1)?.iter().filter(|l| seen.insert(l.product.clone())).take(URGENT_CARDS) {
                cards.push(json!({
                    "id": format!("a-finir-{d}-{}", l.id), "kind": "info", "title": "À finir vite",
                    "body": format!("{} ({})", l.product, l.show()), "chips": [{"text": "urgent", "tone": "urgent"}],
                    "done": {"tool": "app_lot_use", "args": {"lot": l.id}},
                }));
            }
        }
        serde_json::to_string(&cards).map_err(|e| e.to_string())
    }

    /// Lots de nature `plat` de ce nom exact : frigo d'abord, puis les plus anciens.
    fn dish_lots(&self, dish: &str) -> Result<Vec<Lot>, String> {
        use crate::units::fold;
        let want = fold(dish.trim());
        let mut lots: Vec<Lot> = self
            .store
            .lots(None, Some(dish))
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter(|l| l.nature == Some(Nature::Plat) && fold(&l.product) == want)
            .collect();
        lots.sort_by_key(|l| (l.location != "frigo", l.added_at, l.id));
        Ok(lots)
    }

    fn lot_line(&self, l: &Lot) -> String {
        self.lot_line_at(l, self.today())
    }

    /// Ligne d'un lot, échéance comptée depuis `day`.
    fn lot_line_at(&self, l: &Lot, day: chrono::NaiveDate) -> String {
        let ddm = l.kind == Some(Kind::Ddm);
        let when = match expiry(l) {
            Some(d) => match (d - day).num_days() {
                n if n < 0 && ddm => format!(", date dépassée depuis {} j (souvent encore bon : vérifier)", -n),
                n if n < 0 => format!(", périmé depuis {} j", -n),
                0 => ", à finir aujourd'hui".to_string(),
                1 => ", à finir demain".to_string(),
                n => format!(", avant le {} ({n} j)", d.format("%d/%m")),
            },
            None => String::new(),
        };
        let est = if l.estimated && l.expiry.is_some() { ", date estimée" } else { "" };
        format!("- {} : {}{}{when}{est}", l.product, l.show(), if l.opened { ", entamé" } else { "" })
    }

    /// Lots à consommer en priorité, les plus pressés d'abord. Même règle pour
    /// tout le stock : la date tombe dans l'horizon, ou il reste moins d'un
    /// sixième de la durée de conservation (une conserve d'un an remonte deux
    /// mois avant sa date, un produit de six mois un mois avant).
    fn urgent(&self, days: i64) -> Result<Vec<Lot>, String> {
        let today = self.today();
        let mut lots: Vec<Lot> = self
            .store
            .lots(None, None)
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter(perishable)
            .filter(|l| {
                let added = chrono::DateTime::from_timestamp(l.added_at, 0).map(|t| t.date_naive()).unwrap_or(today);
                expiry(l).is_some_and(|d| pressing(d, added, today, days))
            })
            .collect();
        lots.sort_by(|a, b| expiry(a).cmp(&expiry(b)).then_with(|| a.product.cmp(&b.product)));
        Ok(lots)
    }

    /// DDM imprimées dépassées, les plus anciennes d'abord : à vérifier, pas à jeter.
    fn stale(&self) -> Result<Vec<Lot>, String> {
        let today = self.today();
        let mut lots: Vec<Lot> = self
            .store
            .lots(None, None)
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter(|l| !non_food(l) && !perishable(l) && expiry(l).is_some_and(|d| d < today))
            .collect();
        lots.sort_by(|a, b| expiry(a).cmp(&expiry(b)).then_with(|| a.product.cmp(&b.product)));
        lots.truncate(STALE_MAX);
        Ok(lots)
    }

    /// `neutre`, `attention` (≤ 3 j) ou `urgent` (≤ 1 j) ; seuls les périssables pressent.
    fn tone(&self, l: &Lot) -> &'static str {
        match expiry(l).filter(|_| perishable(l)).map(|d| (d - self.today()).num_days()) {
            Some(n) if n <= 1 => "urgent",
            Some(n) if n <= 3 => "attention",
            _ => "neutre",
        }
    }

    /// Décisions « produit à compléter » (nom du produit, décision) : nature d'abord, contenance ensuite.
    fn product_decisions(&self) -> Result<Vec<(String, serde_json::Value)>, String> {
        use crate::units::Family;
        use serde_json::json;
        let opt = |id: &str, label: &str| json!({"id": id, "label": label});
        let mut out = Vec::new();
        for p in self.store.incomplete().map_err(|e| e.to_string())? {
            let added = chrono::DateTime::from_timestamp(p.added_at, 0).unwrap_or_default();
            let expires = added.with_timezone(&self.settings.tz).date_naive() + chrono::Duration::days(30);
            let stock = if p.stock > 0.0 { format!("{} en stock", show(p.stock, p.family)) } else { "épuisé".into() };
            let (kind, question, alternatives) = match p.nature {
                None => {
                    let mut alts: Vec<_> = crate::nature::ALL
                        .into_iter()
                        .filter(|n| n.is_food())
                        .map(|n| opt(&format!("alimentaire:{}", n.as_str()), &format!("Alimentaire · {}", n.label())))
                        .collect();
                    alts.extend([Nature::Entretien, Nature::Hygiene].map(|n| opt(n.as_str(), n.label())));
                    ("nature", "c'est quoi ?", alts)
                }
                // Contenance demandée seulement pour un produit encore compté en pièces.
                Some(n) if p.size_unknown && p.family == Family::Piece => {
                    let mut alts: Vec<_> = sizes(n)
                        .iter()
                        .filter_map(|s| crate::units::pack(s).map(|(f, q)| opt(s, &show(q, f))))
                        .collect();
                    alts.push(opt("piece", "À l'unité"));
                    ("contenance", "quelle contenance ?", alts)
                }
                Some(_) => continue,
            };
            out.push((p.name.clone(), json!({
                "id": format!("produit-{}-{kind}", p.id),
                "title": "Produit à compléter",
                "headline": format!("{} : {question}", p.name),
                "subtitle": stock,
                "proposal": null,
                "alternatives": alternatives,
                "expires": expires.format("%Y-%m-%d").to_string(),
            })));
        }
        Ok(out)
    }

    /// Complète une ligne depuis la base produits quand seul le code-barres est connu.
    async fn complete(&self, mut l: Line) -> Result<Line, String> {
        if l.name.trim().is_empty() {
            let code = l.barcode.clone().ok_or("Ni nom ni code-barres.")?;
            if let Some((name, ..)) = self.store.known(&code).map_err(|e| e.to_string())? {
                l.name = name;
                return Ok(l);
            }
            let p =
                self.off.lookup(&code).await?.ok_or_else(|| format!("Code-barres {code} inconnu : donner le nom."))?;
            l.name = off_name(&p);
        }
        Ok(l)
    }
}

#[tool_router]
impl FoodServer {
    #[tool(
        description = "Enregistrer une commande de courses (drive) à retirer : lignes avec quantité, unité et emplacement de rangement prévu. En deux temps : aperçu, puis confirm=true. Le stock n'est alimenté qu'à la réception (order_receive)."
    )]
    async fn order_record(&self, Parameters(a): Parameters<OrderArgs>) -> Out {
        let mut lines = Vec::new();
        for l in a.items {
            let l = self.complete(l).await?;
            Store::check_in(&l)?;
            lines.push(l);
        }
        if lines.is_empty() {
            return Err("Commande vide.".to_string());
        }
        let pickup = a.pickup.as_deref().map(date).transpose()?;
        let shown: Vec<String> = lines
            .iter()
            .map(|l| {
                let (f, q, loc, exp) = Store::check_in(l).unwrap();
                format!(
                    "- {} : {} → {loc}{}{}",
                    l.name.trim(),
                    show(q, f),
                    exp.map(|e| format!(", DLC {e}")).unwrap_or_default(),
                    l.price.map(|p| format!(", {}", euros(p))).unwrap_or_default()
                )
            })
            .collect();
        let head = format!(
            "Commande {}{}{}{} — {} ligne(s)",
            a.store.trim(),
            a.reference.as_deref().map(|r| format!(" n° {r}")).unwrap_or_default(),
            pickup.as_deref().map(|p| format!(", retrait le {p}")).unwrap_or_default(),
            a.total.map(|t| format!(", {}", euros(t))).unwrap_or_default(),
            lines.len()
        );
        if !a.confirm.unwrap_or(false) {
            return Ok(format!("{head}. {PREVIEW}\n{}", shown.join("\n")));
        }
        let id = self
            .store
            .add_order(a.store.trim(), a.reference.as_deref(), pickup.as_deref(), a.total, &lines)
            .map_err(|e| e.to_string())?;
        Ok(format!("{head} : enregistrée (commande {id}), à réceptionner au retrait.\n{}", shown.join("\n")))
    }

    #[tool(
        description = "Importer un bon de commande de drive à partir de son texte (PDF passé par pdftotext -layout, ou page copiée) : référence, retrait, total, lignes et rangement déduit du rayon. Les articles non alimentaires sont écartés. Une commande déjà enregistrée sous la même référence est refusée. En deux temps : aperçu, puis confirm=true."
    )]
    async fn order_import(&self, Parameters(a): Parameters<ImportArgs>) -> Out {
        let b = crate::bon::parse(&a.text)?;
        if let Some(r) = b.reference.as_deref()
            && let Some(id) = self.store.order_by_reference(r).map_err(|e| e.to_string())?
        {
            return Ok(format!("Commande n° {r} déjà enregistrée (commande {id}) : rien à faire."));
        }
        let skipped = if b.skipped.is_empty() {
            String::new()
        } else {
            format!("\nÉcartés (non alimentaires) : {}.", b.skipped.join(" ; "))
        };
        let out = self
            .order_record(Parameters(OrderArgs {
                store: a.store.unwrap_or_else(|| "drive".to_string()),
                reference: b.reference,
                pickup: b.pickup,
                total: b.total,
                items: b.lines,
                confirm: a.confirm,
            }))
            .await?;
        Ok(format!("{out}{skipped}"))
    }

    #[tool(description = "Lister les commandes de courses (à retirer d'abord) avec leur contenu.")]
    async fn orders(&self) -> Out {
        let all = self.store.orders(false).map_err(|e| e.to_string())?;
        if all.is_empty() {
            return Ok("Aucune commande enregistrée.".to_string());
        }
        let mut out = Vec::new();
        for o in all.iter().filter(|o| o.status == "a_retirer").chain(all.iter().filter(|o| o.status != "a_retirer")) {
            let state = if o.status == "a_retirer" { "à retirer" } else { "retirée" };
            out.push(format!(
                "Commande {} — {}{}{}{} — {state}, {} ligne(s)",
                o.id,
                o.store,
                o.reference.as_deref().map(|r| format!(" n° {r}")).unwrap_or_default(),
                o.pickup.as_deref().map(|p| format!(", retrait {p}")).unwrap_or_default(),
                o.total.map(|t| format!(", {}", euros(t))).unwrap_or_default(),
                o.lines.len()
            ));
            if o.status == "a_retirer" {
                for l in &o.lines {
                    if let Ok((f, q, loc, _)) = Store::check_in(l) {
                        out.push(format!("  - {} : {} → {loc}", l.name, show(q, f)));
                    }
                }
            }
        }
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Réceptionner une commande retirée : ses lignes entrent en stock (moins les ruptures signalées). En deux temps : aperçu, puis confirm=true."
    )]
    async fn order_receive(&self, Parameters(a): Parameters<ReceiveArgs>) -> Out {
        let pending = self.store.orders(true).map_err(|e| e.to_string())?;
        let o = match a.order_id {
            Some(id) => pending.into_iter().find(|o| o.id == id).ok_or(format!("Commande {id} : pas à retirer."))?,
            None => pending.into_iter().next().ok_or("Aucune commande à retirer.")?,
        };
        let missing: Vec<String> = a.missing.unwrap_or_default().iter().map(|m| crate::units::fold(m)).collect();
        let is_missing = |l: &Line| {
            let n = crate::units::fold(&l.name);
            missing.iter().any(|m| m.split_whitespace().all(|w| n.contains(w)))
        };
        // Lignes déjà traitées depuis l'application : ni rangées ni comptées deux fois.
        let done = self.store.lines_done(o.id).map_err(|e| e.to_string())?;
        let (out, keep): (Vec<&Line>, Vec<&Line>) = o
            .lines
            .iter()
            .enumerate()
            .filter(|(i, _)| !done.contains(i))
            .map(|(_, l)| l)
            .partition(|l| is_missing(l));
        if !a.confirm.unwrap_or(false) {
            let mut s = format!("Commande {} : {} ligne(s) à mettre en stock. {PREVIEW}", o.id, keep.len());
            for l in &out {
                s.push_str(&format!("\n- rupture : {}", l.name));
            }
            return Ok(s);
        }
        let mut stocked = Vec::new();
        for (i, l) in o.lines.iter().enumerate().filter(|(i, _)| !done.contains(i)) {
            let missing = is_missing(l);
            // Même réservation que l'application : une ligne n'entre en stock qu'une fois.
            if self.store.claim_line(o.id, i, if missing { "rupture" } else { "rangee" }).map_err(|e| e.to_string())?
                && !missing
            {
                stocked.push(self.stock_line(o.id, i, l)?);
            }
        }
        self.store.mark_received(o.id).map_err(|e| e.to_string())?;
        let mut s =
            format!("Commande {} réceptionnée : {} produit(s) en stock.\n- {}", o.id, stocked.len(), stocked.join("\n- "));
        for l in out {
            s.push_str(&format!("\n- rupture, non stockée : {}", l.name));
        }
        Ok(s)
    }

    #[tool(
        description = "Ajouter au stock des produits hors commande (marché, boulangerie, reste d'un plat, inventaire initial). Un code-barres seul suffit si le produit est connu de la base ouverte."
    )]
    async fn stock_add(&self, Parameters(a): Parameters<LinesArgs>) -> Out {
        let mut done = Vec::new();
        for l in a.items {
            let l = self.complete(l).await?;
            done.push(self.store.add(&l, "ajout", None)?);
        }
        Ok(format!("Ajouté :\n- {}", done.join("\n- ")))
    }

    #[tool(
        description = "Retirer du stock ce qui a été consommé, jeté ou donné hors repas enregistré (ex. « 3 œufs », « 200 g de riz »). Les lots entamés et les plus proches de leur date partent d'abord."
    )]
    async fn stock_use(&self, Parameters(a): Parameters<LinesArgs>) -> Out {
        let mut done = Vec::new();
        for l in &a.items {
            done.push(self.store.take(l, "consommation", None)?.0);
        }
        Ok(done.join("\n"))
    }

    #[tool(
        description = "Corriger l'inventaire : fixe la quantité réelle restante d'un produit (0 pour « il n'y en a plus »)."
    )]
    async fn stock_set(&self, Parameters(a): Parameters<LinesArgs>) -> Out {
        let mut done = Vec::new();
        for l in &a.items {
            done.push(self.store.set_total(l)?);
        }
        Ok(done.join("\n"))
    }

    #[tool(
        description = "Déplacer un produit (ex. viande au congélateur). Sans date donnée : congeler l'estime à 3 mois, décongeler la ramène à 2 jours au plus."
    )]
    async fn stock_move(&self, Parameters(a): Parameters<MoveArgs>) -> Out {
        self.store.relocate(&a.name, &a.to, a.expiry.as_deref())
    }

    #[tool(
        description = "Lister le stock du logement par emplacement (frigo, congélateur, placard, étagère, corbeille), les plus urgents d'abord. Base pour proposer des recettes avec ce qui reste."
    )]
    async fn stock(&self, Parameters(a): Parameters<StockArgs>) -> Out {
        let loc = a.location.as_deref().map(crate::store::location).transpose()?;
        let lots = self.store.lots(loc.as_deref(), a.query.as_deref()).map_err(|e| e.to_string())?;
        if lots.is_empty() {
            return Ok("Rien en stock pour ce filtre.".to_string());
        }
        let mut out = Vec::new();
        let mut current = "";
        for l in &lots {
            if l.location != current {
                current = &l.location;
                out.push(format!("{} :", l.location));
            }
            out.push(self.lot_line(l));
        }
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Produits qui se gâtent bientôt (date limite ou frais, dans l'horizon donné ou dépassée), puis produits à date indicative dépassée, à vérifier. Pour une idée de repas, préférer cook_options."
    )]
    async fn use_first(&self, Parameters(a): Parameters<UseFirstArgs>) -> Out {
        let days = a.days.unwrap_or(4).clamp(0, 30);
        let lots = self.urgent(days)?;
        let stale = self.stale()?;
        if lots.is_empty() && stale.is_empty() {
            return Ok(format!("Rien à finir dans les {days} prochains jours."));
        }
        let lines = |ls: &[Lot]| ls.iter().map(|l| self.lot_line(l)).collect::<Vec<_>>().join("\n");
        let mut out = Vec::new();
        if !lots.is_empty() {
            out.push(format!("À consommer :\n{}", lines(&lots)));
        }
        if !stale.is_empty() {
            out.push(format!("Vieux, à vérifier :\n{}", lines(&stale)));
        }
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Ingrédients du stock utilisables pour un repas, triés par urgence, restes d'abord. À appeler pour toute question de repas (« on mange quoi », menu, idée de plat). slot : petit-dejeuner, midi, gouter ou soir. context : maison (défaut), boulot (midi au travail : plat à préparer la veille), rando (à emporter, mangeable froid) ; le déduire du message, sinon du rythme du jour donné en consigne (sur site → boulot pour midi). day : AAAA-MM-JJ, aujourd'hui par défaut. Répondre ensuite par 2 propositions au plus : nom du plat, ingrédients du stock utilisés (⚠ sur les urgents), temps approximatif, ce qui manque ; 6 lignes maximum, jamais la liste de l'inventaire. Pour boulot : un plat à préparer ce soir. Pour rando : une liste à emporter."
    )]
    async fn cook_options(&self, Parameters(a): Parameters<CookArgs>) -> Out {
        use crate::nature::{Context, Slot, attrs, fits, groups};
        let slot = Slot::parse(&a.slot)
            .ok_or_else(|| format!("Créneau « {} » inconnu : petit-dejeuner, midi, gouter ou soir.", a.slot))?;
        let ctx = match a.context.as_deref().filter(|c| !c.trim().is_empty()) {
            Some(c) => Context::parse(c).ok_or_else(|| format!("Contexte « {c} » inconnu : maison, boulot ou rando."))?,
            None => Context::Maison,
        };
        let day = match a.day.as_deref().filter(|d| !d.trim().is_empty()) {
            Some(d) => chrono::NaiveDate::parse_from_str(&date(d)?, "%Y-%m-%d").map_err(|e| e.to_string())?,
            None => self.today(),
        };
        let lots = self.store.lots(None, None).map_err(|e| e.to_string())?;
        // Périssables par date, puis DDM imprimées, puis sans date.
        let rank = |l: &Lot| (expiry(l).is_none(), !perishable(l), expiry(l));
        let mut sections = Vec::new();
        for &n in groups(slot, ctx) {
            let mut here: Vec<&Lot> = lots
                .iter()
                .filter(|l| l.nature == Some(n) && fits(slot, ctx, n, attrs(n, &l.product)))
                .filter(|l| !(perishable(l) && expiry(l).is_some_and(|d| d < day)))
                .collect();
            if here.is_empty() {
                continue;
            }
            here.sort_by_key(|l| rank(l));
            here.truncate(PER_GROUP);
            let lines: Vec<String> = here
                .iter()
                .map(|l| {
                    let line = self.lot_line_at(l, day);
                    let soon = perishable(l) && expiry(l).is_some_and(|d| (d - day).num_days() <= 2);
                    if soon { line.replacen("- ", "- ⚠ ", 1) } else { line }
                })
                .collect();
            sections.push(format!("{} :\n{}", n.label(), lines.join("\n")));
        }
        if sections.is_empty() {
            return Ok(format!("Rien d'utilisable dans le stock pour {} ({}).", slot.label(), ctx.as_str()));
        }
        let ahead = if slot == Slot::Midi && ctx == Context::Boulot { ", à préparer la veille au soir" } else { "" };
        let mut out = vec![format!("Pour {} ({}{ahead}), le {} :", slot.label(), ctx.as_str(), day.format("%d/%m"))];
        out.extend(sections);
        let mut extras: Vec<&str> = lots
            .iter()
            .filter(|l| matches!(l.nature, Some(Nature::Condiment | Nature::Epice)))
            .map(|l| l.product.as_str())
            .collect();
        extras.sort();
        extras.dedup();
        if !extras.is_empty() {
            out.push(format!("Dans le placard aussi : {}.", extras.join(", ")));
        }
        let prefs = self.store.prefs().map_err(|e| e.to_string())?;
        if !prefs.is_empty() {
            out.push(format!(
                "Préférences : {}.",
                prefs.iter().map(|(k, v)| format!("{k} : {v}")).collect::<Vec<_>>().join(" ; ")
            ));
        }
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Noter un repas (habitudes alimentaires) ; les ingrédients donnés sont retirés du stock. Repas pris au bureau ou au restaurant : sans ingrédients."
    )]
    async fn meal_log(&self, Parameters(a): Parameters<MealArgs>) -> Out {
        let slot = match crate::units::fold(a.slot.trim()).as_str() {
            s if s.starts_with("petit") || s == "matin" => "petit-dejeuner",
            "midi" | "dejeuner" => "midi",
            "gouter" | "encas" | "collation" => "gouter",
            "soir" | "diner" | "souper" => "soir",
            _ => return Err("Moment : petit-dejeuner, midi, gouter ou soir.".to_string()),
        };
        let day = match a.day.as_deref() {
            Some(d) => date(d)?,
            None => self.today().to_string(),
        };
        let place = a.place.as_deref().map(|p| crate::units::fold(p.trim())).unwrap_or_else(|| "maison".into());
        // Midi et soir : un seul repas par créneau, comme le « fait » de l'application.
        let id = if matches!(slot, "midi" | "soir") {
            self.store
                .add_meal_once(&day, slot, a.name.trim(), &place, a.notes.as_deref())
                .map_err(|e| e.to_string())?
                .ok_or_else(|| format!("Repas du {day} ({slot}) déjà noté : rien n'a été déduit."))?
        } else {
            self.store.add_meal(&day, slot, a.name.trim(), &place, a.notes.as_deref()).map_err(|e| e.to_string())?
        };
        let mut out = vec![format!("Repas noté : {} — {}, {slot}, {place}", day, a.name.trim())];
        for l in a.ingredients.unwrap_or_default() {
            match self.store.take(&l, "repas", Some(id)) {
                Ok((s, _)) => out.push(format!("- {s}")),
                Err(e) => out.push(format!("- non déduit : {e}")),
            }
        }
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Historique des repas sur N jours, avec un bilan (repas à la maison ou dehors, plats qui reviennent). Sert à repérer les habitudes."
    )]
    async fn meals(&self, Parameters(a): Parameters<MealsArgs>) -> Out {
        let days = a.days.unwrap_or(7).clamp(1, 60);
        let since = (self.today() - chrono::Duration::days(days - 1)).to_string();
        let rows = self.store.meals_since(&since).map_err(|e| e.to_string())?;
        if rows.is_empty() {
            return Ok(format!("Aucun repas noté depuis le {since}."));
        }
        let home = rows.iter().filter(|r| r.3 == "maison").count();
        let mut counts: std::collections::BTreeMap<String, usize> = Default::default();
        for r in &rows {
            *counts.entry(crate::units::fold(&r.2)).or_default() += 1;
        }
        let mut top: Vec<_> = counts.into_iter().filter(|(_, n)| *n > 1).collect();
        top.sort_by_key(|x| std::cmp::Reverse(x.1));
        let mut out =
            vec![format!("{} repas depuis le {since} ({home} à la maison, {} dehors).", rows.len(), rows.len() - home)];
        if !top.is_empty() {
            out.push(format!(
                "Reviennent souvent : {}.",
                top.iter().take(5).map(|(n, c)| format!("{n} ×{c}")).collect::<Vec<_>>().join(", ")
            ));
        }
        for (d, s, n, p) in rows {
            let wd = chrono::NaiveDate::parse_from_str(&d, "%Y-%m-%d")
                .map(|x| ["lun", "mar", "mer", "jeu", "ven", "sam", "dim"][x.weekday().num_days_from_monday() as usize])
                .unwrap_or("");
            out.push(format!("- {wd} {d} {s} : {n}{}", if p == "maison" { String::new() } else { format!(" ({p})") }));
        }
        Ok(out.join("\n"))
    }

    #[tool(
        description = "Enregistrer une préférence ou un contexte durable (goûts, aliments à éviter, équipement, budget, créneau de batch cooking, objectifs). Valeur vide pour supprimer."
    )]
    async fn pref_set(&self, Parameters(a): Parameters<PrefArgs>) -> Out {
        let k = a.key.trim().to_lowercase();
        if k.is_empty() {
            return Err("Sujet vide.".to_string());
        }
        self.store.set_pref(&k, &a.value).map_err(|e| e.to_string())?;
        Ok(if a.value.trim().is_empty() {
            format!("Préférence « {k} » supprimée.")
        } else {
            format!("Préférence « {k} » enregistrée.")
        })
    }

    #[tool(
        description = "Enregistrer le plan de repas d'une période (7 jours par défaut) et l'écrire dans l'agenda, recette pas à pas pour chaque plat cuisiné. Pour « planifie la semaine » : lire cook_options (midi et soir) et le carnet (recipes), choisir les plats en finissant d'abord les produits urgents, cuisiner en plusieurs portions pour couvrir les midis ; ne donner que les plats cuisinés, les restes et les boîtes des jours de bureau sont placés automatiquement. Signale les plats sans recette et les ingrédients absents du stock."
    )]
    async fn meal_plan_set(&self, Parameters(a): Parameters<PlanArgs>) -> Out {
        use crate::nature::Slot;
        use crate::plan::{Cook, MealKind, build, event, office_days};
        let parse_day = |s: &str| -> Result<chrono::NaiveDate, String> {
            chrono::NaiveDate::parse_from_str(&date(s)?, "%Y-%m-%d").map_err(|e| e.to_string())
        };
        let start = parse_day(&a.start)?;
        let days = a.days.unwrap_or(7).clamp(1, 14);
        let mut cooks = Vec::new();
        for m in &a.meals {
            let slot = match Slot::parse(&m.slot) {
                Some(s @ (Slot::Midi | Slot::Soir)) => s,
                _ => return Err(format!("Créneau « {} » : midi ou soir.", m.slot)),
            };
            cooks.push(Cook { day: parse_day(&m.day)?, slot, dish: m.dish.trim().to_string(), portions: m.portions.unwrap_or(1).clamp(1, 12) });
        }
        let prefs = self.store.prefs().map_err(|e| e.to_string())?;
        let office = prefs.iter().find(|(k, _)| crate::units::fold(k.trim()) == "bureau").map(|(_, v)| office_days(v)).unwrap_or_default();
        let (meals, mut warnings) = build(start, days, &cooks, &office)?;

        let mut recipes = std::collections::HashMap::new();
        for c in &cooks {
            match self.store.recipe(&c.dish).map_err(|e| e.to_string())? {
                None => warnings.push(format!("{} : pas de recette au carnet (recipe_set).", c.dish)),
                Some(r) => {
                    let missing: Vec<&str> = r
                        .ingredients
                        .iter()
                        .filter(|i| self.store.lots(None, Some(&i.name)).map(|l| l.is_empty()).unwrap_or(true))
                        .map(|i| i.name.as_str())
                        .collect();
                    if !missing.is_empty() {
                        warnings.push(format!("{} : manque {}.", c.dish, missing.join(", ")));
                    }
                    recipes.insert(c.dish.clone(), r);
                }
            }
        }
        let end = start + chrono::Duration::days(i64::from(days) - 1);
        let orphans =
            self.store.replace_plan(&start.to_string(), &end.to_string(), &meals).map_err(|e| e.to_string())?;
        // Rappel de boîte sur la veille du premier jour, hors période : à remettre d'accord.
        let eve = start - chrono::Duration::days(1);
        let wants_note = meals.iter().any(|m| m.day == start && m.slot == Slot::Midi && m.kind == MealKind::Box);
        let mut eve_meal = self
            .store
            .plan(&eve.to_string(), &eve.to_string())
            .map_err(|e| e.to_string())?
            .into_iter()
            .find(|m| m.slot == Slot::Soir)
            .filter(|m| (m.note.as_deref() == Some(crate::plan::BOX_NOTE)) != wants_note);
        if let Some(m) = &mut eve_meal {
            m.note = wants_note.then(|| crate::plan::BOX_NOTE.to_string());
            self.store.set_plan_note(&eve.to_string(), "soir", m.note.as_deref()).map_err(|e| e.to_string())?;
        }

        let calendar = match &self.settings.caldav {
            None => "Agenda non configuré : plan enregistré seulement.".to_string(),
            Some(cal) => {
                let uid = |day: chrono::NaiveDate, slot: &str| format!("kairos-repas-{}-{slot}", day.format("%Y%m%d"));
                // Écritures dans l'ordre ; arrêt à la première erreur (agenda en panne : pas d'attente en série).
                let mut ops: Vec<(String, Option<String>)> = Vec::new();
                for i in 0..days {
                    let day = start + chrono::Duration::days(i64::from(i));
                    for slot in [Slot::Midi, Slot::Soir] {
                        match meals.iter().find(|m| m.day == day && m.slot == slot) {
                            Some(m) => {
                                let r = if m.kind == MealKind::Cook { recipes.get(&m.dish) } else { None };
                                let after: Vec<&crate::plan::Meal> =
                                    meals.iter().filter(|o| o.kind != MealKind::Cook && o.from == m.day && o.dish == m.dish).collect();
                                let (id, ics) = event(m, r, &after, self.settings.tz);
                                ops.push((id, Some(ics)));
                            }
                            None => ops.push((uid(day, slot.as_str()), None)),
                        }
                    }
                }
                for (day, slot) in &orphans {
                    if let Ok(d) = chrono::NaiveDate::parse_from_str(day, "%Y-%m-%d") {
                        ops.push((uid(d, slot), None));
                    }
                }
                if let Some(m) = &eve_meal {
                    let r = if m.kind == MealKind::Cook { self.store.recipe(&m.dish).ok().flatten() } else { None };
                    let (id, ics) = event(m, r.as_ref(), &[], self.settings.tz);
                    ops.push((id, Some(ics)));
                }
                let mut done = 0;
                let mut failed = None;
                for (id, ics) in &ops {
                    let res = match ics {
                        Some(ics) => cal.put(id, ics).await,
                        None => cal.delete(id).await,
                    };
                    match res {
                        Ok(()) => done += 1,
                        Err(e) => {
                            failed = Some(e);
                            break;
                        }
                    }
                }
                match failed {
                    None => format!("Agenda mis à jour ({done} créneaux)."),
                    Some(e) => format!("Agenda incomplet : {e} ({done}/{} créneaux à jour) ; relancer le plan plus tard.", ops.len()),
                }
            }
        };
        let mut out = vec![format!("Plan du {} au {} :", start.format("%d/%m"), end.format("%d/%m"))];
        out.extend(meals.iter().map(plan_line));
        if !warnings.is_empty() {
            out.push(format!("À noter :\n{}", warnings.iter().map(|w| format!("- {w}")).collect::<Vec<_>>().join("\n")));
        }
        out.push(calendar);
        Ok(out.join("\n"))
    }

    #[tool(description = "Plan de repas enregistré, à partir d'un jour (aujourd'hui par défaut).")]
    async fn meal_plan(&self, Parameters(a): Parameters<PlanReadArgs>) -> Out {
        let from = match a.day.as_deref().filter(|d| !d.trim().is_empty()) {
            Some(d) => chrono::NaiveDate::parse_from_str(&date(d)?, "%Y-%m-%d").map_err(|e| e.to_string())?,
            None => self.today(),
        };
        let to = from + chrono::Duration::days(i64::from(a.days.unwrap_or(7).clamp(1, 14)) - 1);
        let meals = self.store.plan(&from.to_string(), &to.to_string()).map_err(|e| e.to_string())?;
        if meals.is_empty() {
            return Ok("Aucun repas planifié sur cette période.".to_string());
        }
        Ok(meals.iter().map(plan_line).collect::<Vec<_>>().join("\n"))
    }

    #[tool(
        description = "Ajouter ou corriger une recette du carnet (nom, portions, ingrédients principaux, étapes numérotées). Le carnet sert au plan de la semaine : une recette écrite une fois est réutilisée."
    )]
    async fn recipe_set(&self, Parameters(a): Parameters<RecipeArgs>) -> Out {
        let steps: Vec<String> = a.steps.into_iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if a.name.trim().is_empty() || steps.is_empty() {
            return Err("Nom et étapes requis.".into());
        }
        let r = crate::store::Recipe {
            title: a.name.trim().to_string(),
            portions: a.portions.unwrap_or(2).clamp(1, 12),
            prep_minutes: a.prep_minutes,
            cook_minutes: a.cook_minutes,
            ingredients: a.ingredients.into_iter().filter(|i| !i.name.trim().is_empty()).collect(),
            steps,
        };
        self.store.set_recipe(&r).map_err(|e| e.to_string())?;
        Ok(format!("Recette enregistrée : {} ({} portions).", r.title, r.portions))
    }

    #[tool(description = "Carnet de recettes : sans nom, la liste ; avec un nom, la recette complète.")]
    async fn recipes(&self, Parameters(a): Parameters<RecipesArgs>) -> Out {
        match a.name.filter(|n| !n.trim().is_empty()) {
            Some(n) => {
                let r = self.store.recipe(&n).map_err(|e| e.to_string())?.ok_or_else(|| format!("Aucune recette « {n} » au carnet."))?;
                Ok(format!("{}\n{}", r.title, crate::plan::recipe_text(&r, None)))
            }
            None => {
                let all = self.store.recipes().map_err(|e| e.to_string())?;
                if all.is_empty() {
                    return Ok("Carnet vide.".to_string());
                }
                Ok(format!(
                    "Carnet ({}) :\n{}",
                    all.len(),
                    all.iter().map(|r| format!("- {} ({} portions)", r.title, r.portions)).collect::<Vec<_>>().join("\n")
                ))
            }
        }
    }

    #[tool(
        description = "Corriger la nature d'un produit (protéine, légume…) quand une proposition de repas le classe mal ; sans argument, liste les produits en stock à qualifier."
    )]
    async fn product_set(&self, Parameters(a): Parameters<NatureArgs>) -> Out {
        match (a.name.filter(|n| !n.trim().is_empty()), a.nature) {
            (None, None) => {
                let names = self.store.unqualified().map_err(|e| e.to_string())?;
                if names.is_empty() {
                    return Ok("Tous les produits en stock ont une nature.".to_string());
                }
                Ok(format!("À qualifier : {}.", names.join(", ")))
            }
            (Some(name), Some(n)) => {
                let all = || crate::nature::ALL.map(|n| n.as_str()).join(", ");
                let n = Nature::parse(&n).ok_or_else(|| format!("Nature inconnue « {n} » : {}.", all()))?;
                self.store.set_nature(&name, n)
            }
            _ => Err("Donner à la fois le produit et sa nature, ou rien pour lister les produits à qualifier.".into()),
        }
    }

    #[tool(
        description = "Préférences et contexte culinaire (goûts, à éviter, équipement, budget, objectifs) : à lire avant de proposer recettes, menus ou courses."
    )]
    async fn prefs(&self) -> Out {
        let p = self.store.prefs().map_err(|e| e.to_string())?;
        if p.is_empty() {
            return Ok("Aucune préférence enregistrée.".to_string());
        }
        Ok(p.iter().map(|(k, v)| format!("- {k} : {v}")).collect::<Vec<_>>().join("\n"))
    }

    #[tool(
        description = "Identifier un produit par son code-barres (base ouverte Open Food Facts) : nom, marque, contenance, catégorie, Nutri-Score, groupe NOVA (degré de transformation)."
    )]
    async fn product_lookup(&self, Parameters(a): Parameters<LookupArgs>) -> Out {
        let code = a.barcode.trim();
        let p = self.off.lookup(code).await?.ok_or_else(|| format!("Code-barres {code} absent de la base ouverte."))?;
        if let Ok(found) = self.store.find(&p.name)
            && let Some(local) = found.iter().find(|x| x.barcode.as_deref() == Some(code))
        {
            self.store.set_details(local.id, p.category.as_deref(), p.nutriscore.as_deref(), p.nova);
        }
        let mut l = vec![format!("{}{}", p.name, p.brand.as_deref().map(|b| format!(" — {b}")).unwrap_or_default())];
        if let Some(q) = p.quantity {
            l.push(format!("contenance : {q}"));
        }
        if let Some(c) = p.category {
            l.push(format!("catégorie : {c}"));
        }
        if let Some(n) = p.nutriscore {
            l.push(format!("Nutri-Score : {n}"));
        }
        if let Some(n) = p.nova {
            l.push(format!("NOVA : {n} (1 brut … 4 ultra-transformé)"));
        }
        Ok(l.join("\n"))
    }

    #[tool(description = "Application : stock par zone et lots, triés par urgence (JSON).")]
    async fn app_inventory(&self) -> Out {
        use serde_json::json;
        let mut lots = self.store.lots(None, None).map_err(|e| e.to_string())?;
        let rank = |t: &str| match t {
            "urgent" => 0,
            "attention" => 1,
            _ => 2,
        };
        lots.sort_by_key(|l| (rank(self.tone(l)), expiry(l).is_none(), expiry(l), l.product.clone()));
        let zones: Vec<_> = ZONES
            .iter()
            .map(|(id, label)| {
                let here = lots.iter().filter(|l| l.location == *id);
                let count = here.clone().count();
                let urgent = here.filter(|l| self.tone(l) == "urgent").count();
                json!({"id": id, "label": label, "count": count, "urgent": urgent})
            })
            .collect();
        use crate::units::Family;
        // Pas du +/− de l'application, dans l'unité de base.
        let step = |l: &Lot| match l.family {
            Family::Piece => 1,
            _ if l.qty >= 1000.0 => 100,
            _ if l.qty >= 100.0 => 50,
            _ => 10,
        };
        let lots: Vec<_> = lots
            .iter()
            .map(|l| {
                json!({
                    "id": l.id, "name": l.product, "qty": l.qty, "unit": l.family.base(), "label": l.show(),
                    "step": step(l), "location": l.location,
                    "expiry": l.expiry, "expiry_kind": l.kind.map(Kind::as_str), "estimated": l.estimated,
                    "tone": self.tone(l),
                })
            })
            .collect();
        Ok(json!({"zones": zones, "lots": lots}).to_string())
    }

    #[tool(description = "Application : consommer un lot, en entier ou en partie.")]
    async fn app_lot_use(&self, Parameters(a): Parameters<AppLotArgs>) -> Out {
        message(self.store.use_lot(a.lot, a.qty)?)
    }

    #[tool(
        description = "Application : repas prévu mangé. Plat cuisiné : noté, ingrédients de la recette retirés, portions restantes au frigo. Reste : portions retirées du plat le plus ancien."
    )]
    async fn app_meal_done(&self, Parameters(a): Parameters<AppMealDoneArgs>) -> Out {
        use crate::plan::MealKind;
        let day = date(&a.day)?;
        if day > self.today().to_string() {
            return Err(format!("Repas du {day} encore à venir : rien à noter."));
        }
        let slot = crate::nature::Slot::parse(&a.slot).ok_or("Créneau : midi ou soir.")?;
        let m = self
            .store
            .plan(&day, &day)
            .map_err(|e| e.to_string())?
            .into_iter()
            .find(|m| m.slot == slot)
            .ok_or_else(|| format!("Aucun repas prévu le {day} ({}).", slot.as_str()))?;
        let eaten = a.portions.unwrap_or(1).max(1);
        // Un seul repas par jour et créneau : un second « fait » (double tap, renvoi) ne décompte rien.
        let note = |place: &str| -> Result<i64, String> {
            self.store
                .add_meal_once(&day, slot.as_str(), &m.dish, place, None)
                .map_err(|e| e.to_string())?
                .ok_or_else(|| format!("{} : déjà noté le {day} ({}).", m.dish, slot.as_str()))
        };
        let mut out = vec![format!("Repas noté : {}", m.dish)];
        if m.kind == MealKind::Cook {
            let id = note("maison")?;
            if let Some(r) = self.store.recipe(&m.dish).map_err(|e| e.to_string())? {
                for i in &r.ingredients {
                    let Some(qty) = i.qty else { continue };
                    let l = Line { name: i.name.clone(), qty, unit: i.unit.clone(), ..Default::default() };
                    out.push(match self.store.take(&l, "repas", Some(id)) {
                        Ok((s, missing)) if missing > 1e-9 => format!("- non déduit en partie : {s}"),
                        Ok((s, _)) => format!("- {s}"),
                        Err(e) => format!("- {} non déduit : {e}", i.name),
                    });
                }
                let left = r.portions.saturating_sub(eaten);
                if left > 0 {
                    // Repas déjà noté et ingrédients déduits : un échec ici se signale, il n'annule rien.
                    out.push(match self.store.add_dish(&m.dish, f64::from(left)) {
                        Ok(s) => format!("- reste au frigo : {s}"),
                        Err(e) => format!("- reste non mis en stock : {e}"),
                    });
                }
            }
        } else {
            let lots = self.dish_lots(&m.dish)?;
            let have: f64 = lots.iter().map(|l| l.qty).sum();
            if lots.is_empty() {
                return Err(format!("{} : aucune portion en stock, rien n'a été retiré.", m.dish));
            }
            note(if m.kind == MealKind::Box { "bureau" } else { "maison" })?;
            let mut want = f64::from(eaten);
            for l in lots {
                if want <= 1e-9 {
                    break;
                }
                let used = want.min(l.qty);
                out.push(format!("- {}", self.store.use_lot(l.id, Some(used))?));
                want -= used;
            }
            if have < f64::from(eaten) {
                out.push(format!("- seulement {have} portion(s) disponible(s)"));
            }
        }
        message(out.join("\n"))
    }

    #[tool(description = "Application : corriger un lot (zone, quantité, date).")]
    async fn app_lot_update(&self, Parameters(a): Parameters<AppLotUpdateArgs>) -> Out {
        message(self.store.update_lot(a.lot, a.location.as_deref(), a.qty, a.expiry.as_deref())?)
    }

    /// Met en stock une ligne déjà réservée ; en cas d'échec, la réservation est rendue
    /// pour que la ligne puisse être reprise.
    fn stock_line(&self, order: i64, idx: usize, l: &Line) -> Result<String, String> {
        self.store.add(l, "achat", Some(order)).inspect_err(|_| {
            let _ = self.store.release_line(order, idx);
        })
    }

    /// Zone proposée pour une ligne de commande : habituelle, sinon prévue à l'import.
    fn proposed(&self, l: &Line) -> Result<String, String> {
        if let Some(loc) = self.store.usual_location(&l.name, l.barcode.as_deref()).map_err(|e| e.to_string())? {
            return Ok(loc);
        }
        Ok(Store::check_in(l)?.2)
    }

    #[tool(description = "Application : commandes à ranger, ligne par ligne (JSON).")]
    async fn app_orders(&self) -> Out {
        use serde_json::json;
        let mut out = Vec::new();
        for o in self.store.orders(true).map_err(|e| e.to_string())? {
            let done = self.store.lines_done(o.id).map_err(|e| e.to_string())?;
            let mut lines = Vec::new();
            for (i, l) in o.lines.iter().enumerate() {
                let loc = self.proposed(l)?;
                lines.push(json!({
                    "index": i, "name": l.name, "qty": l.qty,
                    "label": Store::check_in(l).map(|(f, q, ..)| show(q, f)).unwrap_or_else(|_| l.qty.to_string()),
                    "unit": l.unit.as_deref().filter(|u| !u.trim().is_empty()).unwrap_or("pièce"),
                    "location": loc, "fresh": fresh(&l.name, &loc, l.expiry.clone()), "done": done.contains(&i),
                }));
            }
            out.push(json!({"id": o.id, "store": o.store, "pickup": o.pickup, "lines": lines}));
        }
        Ok(serde_json::Value::Array(out).to_string())
    }

    #[tool(description = "Application : ranger une ligne de commande, ou la déclarer en rupture.")]
    async fn app_order_line(&self, Parameters(a): Parameters<AppOrderLineArgs>) -> Out {
        let pending = self.store.orders(true).map_err(|e| e.to_string())?;
        let o = pending.into_iter().find(|o| o.id == a.order).ok_or(format!("Commande {} : pas à ranger.", a.order))?;
        let line = o.lines.get(a.index).ok_or(format!("Commande {} : pas de ligne {}.", o.id, a.index))?;
        let missing = a.missing.unwrap_or(false);
        let mut l = line.clone();
        if !missing {
            l.location = Some(match a.location.as_deref().filter(|x| !x.trim().is_empty()) {
                Some(x) => crate::store::location(x)?,
                None => self.proposed(line)?,
            });
            if let Some(e) = a.expiry.as_deref().filter(|e| !e.trim().is_empty()) {
                l.expiry = Some(date(e)?);
            }
            Store::check_in(&l)?;
        }
        // La ligne est réservée avant d'écrire le stock : un second envoi est refusé.
        // ponytail: réservation et écriture du stock hors d'une même transaction ;
        // un arrêt entre les deux laisse la ligne traitée sans stock. Une seule
        // transaction si cela arrive en pratique.
        if !self.store.claim_line(o.id, a.index, if missing { "rupture" } else { "rangee" }).map_err(|e| e.to_string())? {
            return Err(format!("{} : déjà traité.", line.name));
        }
        let msg = if missing {
            format!("{} : rupture, non stocké.", line.name)
        } else {
            self.stock_line(o.id, a.index, &l)?
        };
        let remaining = o.lines.len() - self.store.lines_done(o.id).map_err(|e| e.to_string())?.len();
        if remaining == 0 {
            self.store.mark_received(o.id).map_err(|e| e.to_string())?;
        }
        Ok(serde_json::json!({"message": msg, "remaining": remaining}).to_string())
    }

    #[tool(
        description = "Application : produit par code-barres (connu localement ou de la base ouverte) ou par nom, avec son unité et une quantité proposée (JSON)."
    )]
    async fn app_product(&self, Parameters(a): Parameters<AppProductArgs>) -> Out {
        use crate::units::Family;
        let code = a.barcode.as_deref().map(str::trim).filter(|c| !c.is_empty());
        let asked = a.name.as_deref().map(str::trim).unwrap_or_default();
        if code.is_none() && asked.is_empty() {
            return Err("Ni nom ni code-barres.".to_string());
        }
        // Inconnu = réponse normale ; une erreur ne signale qu'une recherche impossible.
        let unknown = || Ok(serde_json::json!({"known": false}).to_string());
        let (name, nature, loc, family, pack) = match self.store.usual(asked, code).map_err(|e| e.to_string())? {
            Some(u) => {
                let loc = u.location.unwrap_or_else(|| crate::bon::location("", &u.name).to_string());
                (u.name, u.nature, loc, u.family, u.pack)
            }
            None => {
                let Some(code) = code else { return unknown() };
                let Some(p) = self.off.lookup(code).await? else { return unknown() };
                let name = off_name(&p);
                let cat = p.category.as_deref().map(crate::units::fold).unwrap_or_default();
                let nature = crate::nature::resolve(None, p.category.as_deref(), &p.name);
                let loc = crate::bon::location(&cat, &name).to_string();
                // Même nom déjà en stock : sa famille l'emporte sur la contenance annoncée.
                match self.store.usual(&name, None).map_err(|e| e.to_string())? {
                    Some(u) => (name, nature, loc, u.family, u.pack),
                    None => {
                        let (f, q) = p.quantity.as_deref().and_then(crate::units::pack).unzip();
                        (name, nature, loc, f.unwrap_or(Family::Piece), q)
                    }
                }
            }
        };
        let qty = pack.filter(|q| *q > 0.0).unwrap_or(match family {
            Family::Piece => 1.0,
            Family::Masse => 500.0,
            Family::Volume => 1000.0,
        });
        Ok(serde_json::json!({
            "known": true, "name": name, "nature": nature.map(Nature::as_str), "fresh": fresh(&name, &loc, None),
            "location": loc, "unit": family.base(), "qty": qty,
        })
        .to_string())
    }

    #[tool(description = "Application : ajouter au stock hors commande (comme stock_add).")]
    async fn app_add(&self, Parameters(a): Parameters<AppAddArgs>) -> Out {
        let l = Line {
            name: a.name.unwrap_or_default(),
            qty: a.qty,
            unit: a.unit,
            location: Some(a.location),
            expiry: a.expiry,
            barcode: a.barcode.map(|b| b.trim().to_string()).filter(|b| !b.is_empty()),
            price: None,
        };
        // Produit nouveau avec code-barres : la fiche de la base ouverte donne nom,
        // contenance et catégorie. Base injoignable : bloquant seulement sans nom.
        let mut sheet = None;
        if let Some(code) = l.barcode.clone()
            && self.store.usual(&l.name, Some(&code)).map_err(|e| e.to_string())?.is_none()
        {
            match self.off.lookup(&code).await {
                Ok(p) => sheet = p,
                Err(e) if l.name.trim().is_empty() => return Err(e),
                Err(_) => {}
            }
        }
        let mut l = match &sheet {
            Some(p) if l.name.trim().is_empty() => Line { name: off_name(p), ..l },
            _ => self.complete(l).await?,
        };
        let known = self.store.usual(&l.name, l.barcode.as_deref()).map_err(|e| e.to_string())?;
        let new = known.is_none();
        // Compté en pièces alors que la fiche annonce une contenance : n pièces → n × contenance.
        if new
            && let Some((f, size)) = sheet.as_ref().and_then(|p| p.quantity.as_deref()).and_then(crate::units::pack)
            && let Some((crate::units::Family::Piece, k)) = crate::units::parse_unit(l.unit.as_deref().unwrap_or(""))
        {
            l.qty *= k * size;
            l.unit = Some(f.base().to_string());
        }
        // Produit déjà connu : le même, dans sa famille, jamais un doublon compté autrement.
        if let Some(p) = known {
            let unit = l.unit.get_or_insert_with(|| p.family.base().to_string());
            if crate::units::parse_unit(unit).is_some_and(|(f, _)| f != p.family) {
                return Err(format!("{} est compté en {} : quantité en {} attendue.", p.name, p.family.as_str(), p.family.base()));
            }
            l.name = p.name;
        }
        let m = self.store.add(&l, "ajout", None)?;
        if let Some(p) = &sheet
            && let Ok(found) = self.store.find(&l.name)
            && let Some(local) = found.iter().find(|x| x.barcode == l.barcode)
        {
            self.store.set_details(local.id, p.category.as_deref(), p.nutriscore.as_deref(), p.nova);
        }
        if new {
            // Nouveau produit en pièces : sa contenance passera par la pile de décisions.
            self.store.flag_size_unknown(&l.name).map_err(|e| e.to_string())?;
        }
        message(m)
    }

    #[tool(description = "Décisions en attente pour l'application : produits à compléter (nature, contenance), au format JSON.")]
    async fn decisions(&self) -> Out {
        let items: Vec<_> = self.product_decisions()?.into_iter().map(|(_, d)| d).collect();
        serde_json::to_string(&items).map_err(|e| e.to_string())
    }

    #[tool(description = "Trancher une décision de l'application : id = décision, choice = option proposée.")]
    async fn decide(&self, Parameters(a): Parameters<DecideArgs>) -> Out {
        // Seule une décision encore en attente est acceptée : une fiche complétée
        // entre-temps n'est jamais réécrite.
        let pending = self.product_decisions()?;
        let (name, d) = pending.iter().find(|(_, d)| d["id"] == a.id).ok_or("Décision inconnue ou déjà traitée.")?;
        let alts = d["alternatives"].as_array().map(Vec::as_slice).unwrap_or_default();
        let label = alts
            .iter()
            .find(|o| o["id"] == a.choice)
            .and_then(|o| o["label"].as_str())
            .ok_or_else(|| format!("Choix « {} » non proposé.", a.choice))?;
        let mut parts = a.id.splitn(3, '-').skip(1);
        let id: i64 = parts.next().and_then(|x| x.parse().ok()).ok_or("Décision inconnue ou déjà traitée.")?;
        match parts.next() {
            Some("nature") => {
                let n = Nature::parse(a.choice.trim_start_matches("alimentaire:")).ok_or("Nature inconnue.")?;
                self.store.set_nature_of(id, n).map_err(|e| e.to_string())?;
            }
            _ => self.store.set_size(id, crate::units::pack(&a.choice))?,
        }
        Ok(format!("{name} : {label}."))
    }

    #[tool(description = "Cartes JSON du jour pour l'application (repas, boîte, produits à finir).")]
    async fn cards(&self, Parameters(a): Parameters<CardsArgs>) -> Out {
        let day = a.day.filter(|d| !d.trim().is_empty()).unwrap_or_else(|| self.today().to_string());
        self.cards_json(&day)
    }

    #[tool(description = "Section alimentation d'un briefing : produits à finir sous 2 jours et commande à retirer.")]
    async fn brief_section(&self, Parameters(_a): Parameters<BriefArgs>) -> Out {
        let mut out = Vec::new();
        let urgent = self.urgent(2)?;
        if !urgent.is_empty() {
            out.push(format!(
                "À finir vite : {}.",
                urgent.iter().map(|l| format!("{} ({})", l.product, l.show())).collect::<Vec<_>>().join(", ")
            ));
        }
        for o in self.store.orders(true).map_err(|e| e.to_string())? {
            out.push(format!(
                "Commande {} à retirer{}.",
                o.store,
                o.pickup.as_deref().map(|p| format!(" le {p}")).unwrap_or_default()
            ));
        }
        if out.is_empty() {
            return Ok("Alimentation : rien d'urgent.".to_string());
        }
        Ok(format!("Alimentation — {}", out.join(" ")))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for FoodServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[cfg(test)]
mod tests {
    use super::{FoodServer, Settings, perishable, pressing};
    use crate::nature::Slot;
    use crate::off::Off;
    use crate::plan::{BOX_NOTE, Meal, MealKind};
    use crate::store::{Ingredient, Recipe, Store};
    use std::sync::Arc;
    use crate::shelf::Kind;
    use crate::store::Lot;
    use crate::units::Family;

    /// Lot dont la date tombe `span` jours après son entrée en stock.
    fn lot(kind: Option<Kind>, estimated: bool, location: &str, span: i64) -> Lot {
        Lot {
            id: 1,
            product: "x".into(),
            family: Family::Piece,
            qty: 1.0,
            location: location.into(),
            expiry: Some("2027-01-01".into()),
            kind,
            estimated,
            opened: false,
            added_at: (chrono::NaiveDate::from_ymd_opt(2027, 1, 1).unwrap() - chrono::Duration::days(span))
                .and_hms_opt(12, 0, 0)
                .unwrap()
                .and_utc()
                .timestamp(),
            nature: None,
        }
    }

    #[test]
    fn legacy_lot_without_kind_counts_as_urgent() {
        assert!(perishable(&lot(None, false, "placard", 365)));
        assert!(perishable(&lot(Some(Kind::Dlc), false, "placard", 365)));
        assert!(perishable(&lot(Some(Kind::Ddm), true, "corbeille", 7)), "fruits sans date lue");
        assert!(perishable(&lot(Some(Kind::Ddm), false, "frigo", 28)), "œufs, beurre");
        assert!(!perishable(&lot(Some(Kind::Ddm), false, "placard", 365)));
    }

    #[test]
    fn dry_goods_with_estimated_date_are_not_urgent() {
        // Riz sans date lue : estimé à un an, ce n'est pas du frais.
        assert!(!perishable(&lot(Some(Kind::Ddm), true, "placard", 365)));
    }
    use chrono::NaiveDate;

    fn d(s: &str) -> NaiveDate {
        NaiveDate::parse_from_str(s, "%Y-%m-%d").unwrap()
    }

    #[test]
    fn long_keeping_products_surface_before_they_are_forgotten() {
        let today = d("2027-06-01");
        // Yaourt : seul l'horizon compte.
        assert!(pressing(d("2027-06-04"), d("2027-05-20"), today, 4));
        assert!(!pressing(d("2027-06-08"), d("2027-05-20"), today, 4));
        // Conserve d'un an achetée il y a dix mois : à deux mois de sa date, elle remonte.
        assert!(pressing(d("2027-07-31"), d("2026-07-31"), today, 4));
        // Même conserve achetée le mois dernier : rien à signaler.
        assert!(!pressing(d("2028-05-01"), d("2027-05-01"), today, 4));
        // Date dépassée : toujours remonté.
        assert!(pressing(d("2027-05-01"), d("2026-05-01"), today, 0));
    }

    fn server() -> FoodServer {
        let settings = Arc::new(Settings { tz: chrono_tz::UTC, caldav: None });
        FoodServer::new(Arc::new(Store::memory()), Arc::new(Off::new("http://localhost")), settings)
    }

    fn meal(day: &str, slot: Slot, dish: &str, kind: MealKind, note: Option<&str>) -> Meal {
        Meal { day: d(day), slot, dish: dish.into(), kind, from: d(day), note: note.map(Into::into) }
    }

    fn cards(s: &FoodServer, day: &str) -> Vec<serde_json::Value> {
        serde_json::from_str(&s.cards_json(day).unwrap()).unwrap()
    }

    #[test]
    fn cards_for_planned_day() {
        let s = server();
        s.store
            .set_recipe(&Recipe {
                title: "Soupe de carcasse".into(),
                portions: 2,
                prep_minutes: Some(10),
                cook_minutes: None,
                ingredients: Vec::<Ingredient>::new(),
                steps: vec!["Mijoter.".into()],
            })
            .unwrap();
        let day = "2026-11-08";
        let plan = [
            meal(day, Slot::Midi, "Pâtes", MealKind::Leftover, None),
            meal(day, Slot::Soir, "Soupe de carcasse", MealKind::Cook, None),
        ];
        s.store.replace_plan(day, day, &plan).unwrap();
        let c = cards(&s, day);
        assert_eq!(c.len(), 2);
        assert_eq!(c[0]["id"], "repas-2026-11-08-midi");
        assert_eq!(c[0]["time"], "12:15");
        assert_eq!(c[0]["title"], "Midi · Pâtes");
        assert_eq!(c[0]["body"], "reste");
        assert!(c[0].get("detail").is_none());
        assert_eq!(c[1]["id"], "repas-2026-11-08-soir");
        assert_eq!(c[1]["time"], "19:00");
        assert_eq!(c[1]["kind"], "info");
        assert!(c[1]["body"].as_str().unwrap().contains("10 min"));
        assert!(c[1]["detail"].as_str().unwrap().contains("Mijoter."));
    }

    #[test]
    fn cards_box_note() {
        let s = server();
        let day = "2026-11-08";
        s.store.replace_plan(day, day, &[meal(day, Slot::Soir, "Riz", MealKind::Cook, Some(BOX_NOTE))]).unwrap();
        let c = cards(&s, day);
        assert_eq!(c.len(), 2);
        assert_eq!(c[1]["id"], "box-2026-11-08");
        assert_eq!(c[1]["time"], "20:00");
        assert_eq!(c[1]["title"], "Box de demain");
    }

    #[test]
    fn cards_empty_day_is_empty_array() {
        assert_eq!(server().cards_json("2026-11-08").unwrap(), "[]");
    }

    #[test]
    fn cards_urgent_product_today() {
        let s = server();
        let today = s.today();
        let mut l = crate::store::Line {
            name: "Champignons".into(),
            qty: 1.0,
            unit: Some("pièce".into()),
            location: Some("frigo".into()),
            ..Default::default()
        };
        l.expiry = Some((today + chrono::Duration::days(1)).to_string());
        s.store.add(&l, "achat", None).unwrap();
        let c = cards(&s, &today.to_string());
        assert_eq!(c.len(), 1);
        assert_eq!(c[0]["title"], "À finir vite");
        assert!(c[0]["body"].as_str().unwrap().contains("hampignons"));
        assert_eq!(c[0]["chips"][0]["tone"], "urgent");
        assert!(c[0].get("time").is_none());
    }

    fn v(out: Result<String, String>) -> serde_json::Value {
        serde_json::from_str(&out.unwrap()).unwrap()
    }

    fn item(name: &str, qty: f64, unit: &str, loc: &str, expiry: Option<String>) -> crate::store::Line {
        crate::store::Line {
            name: name.into(),
            qty,
            unit: Some(unit.into()),
            location: Some(loc.into()),
            expiry,
            ..Default::default()
        }
    }

    fn lot_id(s: &FoodServer, q: &str) -> i64 {
        s.store.lots(None, Some(q)).unwrap()[0].id
    }

    /// Commande à retirer : biscuits prévus au placard, yaourts au frigo.
    fn order(s: &FoodServer) -> i64 {
        let lines = [item("Biscuits sablés", 1.0, "pièce", "placard", None), item("Yaourts nature", 8.0, "pièce", "frigo", None)];
        s.store.add_order("drive", Some("R1"), Some("2026-11-10"), None, &lines).unwrap()
    }

    fn line_args(order: i64, index: usize, extra: serde_json::Value) -> Parameters<super::AppOrderLineArgs> {
        let mut a = serde_json::json!({"order": order, "index": index});
        a.as_object_mut().unwrap().extend(extra.as_object().unwrap().clone());
        Parameters(serde_json::from_value(a).unwrap())
    }

    use rmcp::handler::server::wrapper::Parameters;

    #[tokio::test]
    async fn app_inventory_groups_zones_and_tones() {
        let s = server();
        let t = s.today();
        let at = |n: i64| Some((t + chrono::Duration::days(n)).to_string());
        s.store.add(&item("Champignons", 1.0, "pièce", "frigo", at(1)), "achat", None).unwrap();
        s.store.add(&item("Yaourts nature", 8.0, "pièce", "frigo", at(3)), "achat", None).unwrap();
        s.store.add(&item("Riz basmati", 1.0, "kg", "placard", None), "achat", None).unwrap();
        let j = v(s.app_inventory().await);
        let zones = j["zones"].as_array().unwrap();
        assert_eq!(zones.len(), 7);
        let z = |id: &str| zones.iter().find(|z| z["id"] == id).unwrap().clone();
        assert_eq!((z("frigo")["label"].clone(), z("frigo")["count"].clone(), z("frigo")["urgent"].clone()), ("Frigo".into(), 2.into(), 1.into()));
        assert_eq!(z("congelateur")["label"], "Congélo");
        assert_eq!(z("corbeille")["label"], "Table");
        assert_eq!(z("placard")["count"], 1);
        let lots = j["lots"].as_array().unwrap();
        let names: Vec<_> = lots.iter().map(|l| (l["name"].as_str().unwrap(), l["tone"].as_str().unwrap())).collect();
        assert_eq!(names, [("Champignons", "urgent"), ("Yaourts nature", "attention"), ("Riz basmati", "neutre")]);
        assert!(lots[0]["id"].is_i64());
        assert_eq!((lots[2]["qty"].as_f64(), lots[2]["unit"].as_str()), (Some(1000.0), Some("g")));
        assert_eq!((lots[2]["expiry_kind"].as_str(), lots[2]["estimated"].as_bool()), (Some("ddm"), Some(true)));
        assert_eq!((lots[0]["location"].as_str(), lots[0]["estimated"].as_bool()), (Some("frigo"), Some(false)));
    }

    #[tokio::test]
    async fn inventory_lists_new_zones() {
        let s = server();
        s.store.add(&item("Liquide vaisselle", 1.0, "pièce", "evier", None), "achat", None).unwrap();
        s.store.add(&item("Shampooing", 2.0, "pièce", "sdb", None), "achat", None).unwrap();
        let j = v(s.app_inventory().await);
        let zones = j["zones"].as_array().unwrap();
        let z = |id: &str| zones.iter().find(|z| z["id"] == id).unwrap().clone();
        assert_eq!((z("evier")["label"].clone(), z("evier")["count"].clone()), ("Sous l'évier".into(), 1.into()));
        assert_eq!((z("sdb")["label"].clone(), z("sdb")["count"].clone()), ("Salle de bain".into(), 1.into()));
    }

    #[test]
    fn food_under_sink_stays_perishable() {
        let mut onions = lot(None, false, "evier", 7);
        onions.nature = Some(crate::nature::Nature::Legume);
        assert!(perishable(&onions), "la nature prime sur la zone");
        let mut soap = lot(None, false, "frigo", 7);
        soap.nature = Some(crate::nature::Nature::Hygiene);
        assert!(!perishable(&soap));
    }

    #[test]
    fn non_food_default_zone() {
        assert_eq!(crate::bon::location("", "Liquide vaisselle"), "evier");
        assert_eq!(crate::bon::location("", "Gel douche"), "sdb");
        assert_eq!(crate::bon::location("hygiene", "Produit X"), "sdb");
        assert_eq!(crate::bon::location("", "Riz basmati"), "placard");
    }

    #[tokio::test]
    async fn non_food_never_in_meals_or_urgent() {
        let s = server();
        let t = s.today();
        let past = Some((t - chrono::Duration::days(3)).to_string());
        let soon = Some((t + chrono::Duration::days(1)).to_string());
        s.store.add(&item("Liquide vaisselle", 1.0, "pièce", "evier", soon.clone()), "achat", None).unwrap();
        s.store.add(&item("Dentifrice", 1.0, "pièce", "frigo", past), "achat", None).unwrap();
        s.store.add(&item("Éponges", 3.0, "pièce", "placard", soon), "achat", None).unwrap();
        assert!(!perishable(&lot(None, false, "evier", 365)));
        assert!(!super::fresh("Gel douche", "frigo", None));
        assert_eq!(s.urgent(30).unwrap().len(), 0);
        assert_eq!(s.stale().unwrap().len(), 0);
        let uf = s.use_first(Parameters(serde_json::from_value(serde_json::json!({"days": 30})).unwrap())).await.unwrap();
        assert!(uf.starts_with("Rien à finir"), "{uf}");
        let b = s.brief_section(Parameters(serde_json::from_value(serde_json::json!({"period": "matin"})).unwrap())).await.unwrap();
        assert!(!b.contains("vaisselle") && !b.contains("Éponges"), "{b}");
        let c = s.cards_json(&t.to_string()).unwrap();
        assert!(!c.contains("vaisselle") && !c.contains("Dentifrice"), "{c}");
        for slot in ["petit-dejeuner", "midi", "gouter", "soir"] {
            let o = s.cook_options(Parameters(serde_json::from_value(serde_json::json!({"slot": slot})).unwrap())).await.unwrap();
            assert!(!o.contains("vaisselle") && !o.contains("Éponges") && !o.contains("Dentifrice"), "{o}");
        }
    }

    #[tokio::test]
    async fn app_orders_proposes_usual_zone() {
        let s = server();
        s.store.add(&item("Biscuits sablés", 1.0, "pièce", "etagere", None), "ajout", None).unwrap();
        s.store.take(&item("biscuits", 1.0, "pièce", "", None), "consommation", None).unwrap();
        let id = order(&s);
        let j = v(s.app_orders().await);
        assert_eq!(j.as_array().unwrap().len(), 1);
        assert_eq!((j[0]["id"].as_i64(), j[0]["store"].as_str(), j[0]["pickup"].as_str()), (Some(id), Some("drive"), Some("2026-11-10")));
        let l = &j[0]["lines"];
        assert_eq!((l[0]["index"].as_u64(), l[0]["name"].as_str()), (Some(0), Some("Biscuits sablés")));
        assert_eq!(l[0]["location"], "etagere", "zone habituelle, même sans lot en stock");
        assert_eq!(l[1]["location"], "frigo", "zone prévue à l'import");
        assert_eq!((l[0]["fresh"].as_bool(), l[1]["fresh"].as_bool()), (Some(false), Some(true)));
        assert_eq!((l[1]["qty"].as_f64(), l[1]["unit"].as_str(), l[1]["done"].as_bool()), (Some(8.0), Some("pièce"), Some(false)));
    }

    #[tokio::test]
    async fn order_line_puts_into_stock_with_expiry() {
        let s = server();
        let id = order(&s);
        let j = v(s.app_order_line(line_args(id, 1, serde_json::json!({"expiry": "2030-01-15"}))).await);
        assert_eq!(j["remaining"], 1);
        assert!(j["message"].as_str().unwrap().contains("Yaourts nature"), "{j}");
        let lots = s.store.lots(None, Some("yaourts")).unwrap();
        assert_eq!((lots.len(), lots[0].expiry.as_deref(), lots[0].estimated), (1, Some("2030-01-15"), false));
        assert_eq!(v(s.app_orders().await)[0]["lines"][1]["done"], true);
        // La réception par conversation ne remet pas en stock ce qui est déjà rangé.
        s.order_receive(Parameters(serde_json::from_value(serde_json::json!({"confirm": true})).unwrap())).await.unwrap();
        assert_eq!(s.store.lots(None, Some("yaourts")).unwrap().len(), 1);
        assert_eq!(s.store.lots(None, Some("biscuits")).unwrap().len(), 1);
    }

    #[tokio::test]
    async fn order_line_missing_skips() {
        let s = server();
        let id = order(&s);
        let j = v(s.app_order_line(line_args(id, 0, serde_json::json!({"missing": true}))).await);
        assert_eq!(j["remaining"], 1);
        assert!(s.store.lots(None, None).unwrap().is_empty());
        assert_eq!(v(s.app_orders().await)[0]["lines"][0]["done"], true);
    }

    #[tokio::test]
    async fn order_line_twice_refused() {
        let s = server();
        let id = order(&s);
        s.app_order_line(line_args(id, 1, serde_json::json!({"location": "congelateur"}))).await.unwrap();
        let e = s.app_order_line(line_args(id, 1, serde_json::json!({}))).await.unwrap_err();
        assert!(e.contains("déjà"), "{e}");
        let lots = s.store.lots(None, Some("yaourts")).unwrap();
        assert_eq!((lots.len(), lots[0].qty, lots[0].location.as_str()), (1, 8.0, "congelateur"));
        assert!(s.app_order_line(line_args(id, 7, serde_json::json!({}))).await.is_err());
    }

    #[tokio::test]
    async fn order_completes_when_all_lines_done() {
        let s = server();
        let id = order(&s);
        s.app_order_line(line_args(id, 0, serde_json::json!({"missing": true}))).await.unwrap();
        let j = v(s.app_order_line(line_args(id, 1, serde_json::json!({}))).await);
        assert_eq!(j["remaining"], 0);
        assert!(s.store.orders(true).unwrap().is_empty());
        assert_eq!(s.store.orders(false).unwrap()[0].status, "retiree");
        assert_eq!(s.app_orders().await.unwrap(), "[]");
        let e = s.app_order_line(line_args(id, 1, serde_json::json!({}))).await.unwrap_err();
        assert_eq!(e, format!("Commande {id} : pas à ranger."));
    }

    #[tokio::test]
    async fn order_receive_claims_its_lines() {
        let s = server();
        let id = order(&s);
        s.order_receive(Parameters(serde_json::from_value(serde_json::json!({"confirm": true})).unwrap())).await.unwrap();
        assert_eq!(s.store.lines_done(id).unwrap().len(), 2, "chaque ligne réservée, comme depuis l'application");
    }

    #[tokio::test]
    async fn order_receive_releases_line_when_stock_fails() {
        let s = server();
        // Unité illisible : la ligne passe l'import brut mais pas l'entrée en stock.
        let lines = [item("Biscuits sablés", 1.0, "pièce", "placard", None), item("Farine", 1.0, "sachet", "placard", None)];
        let id = s.store.add_order("drive", None, None, None, &lines).unwrap();
        let confirm = || Parameters(serde_json::from_value(serde_json::json!({"confirm": true})).unwrap());
        assert!(s.order_receive(confirm()).await.is_err());
        assert_eq!(s.store.lines_done(id).unwrap(), vec![0], "la ligne en échec n'est pas réservée");
        // Corrigée depuis l'application, elle se range encore.
        s.app_order_line(line_args(id, 1, serde_json::json!({"missing": true}))).await.unwrap();
        assert!(s.store.orders(true).unwrap().is_empty());
    }

    /// Base produits simulée : chaque requête reçoit la même réponse.
    async fn off_answering(status: &'static str, body: &'static str) -> FoodServer {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", l.local_addr().unwrap());
        tokio::spawn(async move {
            while let Ok((mut c, _)) = l.accept().await {
                let mut buf = [0u8; 4096];
                let _ = c.read(&mut buf).await;
                let r = format!("HTTP/1.1 {status}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}", body.len());
                let _ = c.write_all(r.as_bytes()).await;
            }
        });
        let settings = Arc::new(Settings { tz: chrono_tz::UTC, caldav: None });
        FoodServer::new(Arc::new(Store::memory()), Arc::new(Off::new(&base)), settings)
    }

    fn product_args(a: serde_json::Value) -> Parameters<super::AppProductArgs> {
        Parameters(serde_json::from_value(a).unwrap())
    }

    #[tokio::test]
    async fn app_product_unknown_barcode_is_not_an_error() {
        let s = off_answering("404 Not Found", "{}").await;
        let j = v(s.app_product(product_args(serde_json::json!({"barcode": "3017620422003"}))).await);
        assert_eq!(j["known"], false);
    }

    #[tokio::test]
    async fn app_product_base_unreachable_is_an_error() {
        let e = server().app_product(product_args(serde_json::json!({"barcode": "3017620422003"}))).await.unwrap_err();
        assert!(e.contains("injoignable"), "{e}");
    }

    #[tokio::test]
    async fn app_product_reads_pack_size_from_base() {
        let s = off_answering("200 OK", r#"{"status":1,"product":{"product_name":"Riz long","quantity":"1 kg"}}"#).await;
        let j = v(s.app_product(product_args(serde_json::json!({"barcode": "3017620422003"}))).await);
        assert_eq!((j["known"].as_bool(), j["name"].as_str()), (Some(true), Some("Riz long")));
        assert_eq!((j["unit"].as_str(), j["qty"].as_f64()), (Some("g"), Some(1000.0)));
    }

    #[tokio::test]
    async fn app_product_by_name_finds_existing() {
        let s = server();
        s.store.add(&item("Riz basmati", 1.0, "kg", "placard", None), "achat", None).unwrap();
        let j = v(s.app_product(product_args(serde_json::json!({"name": "riz BASMATI"}))).await);
        assert_eq!((j["known"].as_bool(), j["name"].as_str(), j["location"].as_str()), (Some(true), Some("Riz basmati"), Some("placard")));
        assert_eq!((j["unit"].as_str(), j["qty"].as_f64()), (Some("g"), Some(1000.0)), "dernier apport");
        let j = v(s.app_product(product_args(serde_json::json!({"name": "Clémentines"}))).await);
        assert_eq!(j, serde_json::json!({"known": false}));
    }

    #[tokio::test]
    async fn app_add_by_name_reuses_product() {
        let s = server();
        s.store.add(&item("Riz basmati", 1.0, "kg", "placard", None), "achat", None).unwrap();
        let add = |a: serde_json::Value| Parameters::<super::AppAddArgs>(serde_json::from_value(a).unwrap());
        v(s.app_add(add(serde_json::json!({"name": "riz basmati", "qty": 500, "unit": "g", "location": "placard"}))).await);
        let e = s.app_add(add(serde_json::json!({"name": "Riz basmati", "qty": 1, "unit": "pièce", "location": "placard"}))).await.unwrap_err();
        assert!(e.contains("compté en"), "{e}");
        assert_eq!(s.store.find("riz basmati").unwrap().len(), 1, "pas de doublon dans une autre famille");
        assert_eq!(s.store.lots(None, Some("riz")).unwrap().iter().map(|l| l.qty).sum::<f64>(), 1500.0);
    }

    #[tokio::test]
    async fn app_quantities_have_label_and_step() {
        let s = server();
        s.store.add(&item("Riz basmati", 1.0, "kg", "placard", None), "achat", None).unwrap();
        s.store.add(&item("Lait demi-écrémé", 50.0, "cl", "placard", None), "achat", None).unwrap();
        s.store.add(&item("Citrons", 3.0, "pièce", "corbeille", None), "achat", None).unwrap();
        let j = v(s.app_inventory().await);
        let get = |n: &str| j["lots"].as_array().unwrap().iter().find(|l| l["name"] == n).unwrap().clone();
        assert_eq!((get("Riz basmati")["label"].clone(), get("Riz basmati")["step"].clone()), ("1 kg".into(), 100.into()));
        assert_eq!((get("Lait demi-écrémé")["label"].clone(), get("Lait demi-écrémé")["step"].clone()), ("500 ml".into(), 50.into()));
        assert_eq!((get("Citrons")["label"].clone(), get("Citrons")["step"].clone()), ("3 pièces".into(), 1.into()));
        order(&s);
        assert_eq!(v(s.app_orders().await)[0]["lines"][1]["label"], "8 pièces");
    }

    #[tokio::test]
    async fn lot_use_partial_and_full() {
        let s = server();
        s.store.add(&item("Yaourts nature", 8.0, "pièce", "frigo", None), "achat", None).unwrap();
        let id = lot_id(&s, "yaourts");
        let args = |q: Option<f64>| Parameters(super::AppLotArgs { lot: id, qty: q });
        let j = v(s.app_lot_use(args(Some(3.0))).await);
        assert!(j["message"].as_str().unwrap().contains("Yaourts nature"), "{j}");
        assert_eq!(s.store.lots(None, None).unwrap()[0].qty, 5.0);
        v(s.app_lot_use(args(None)).await);
        assert!(s.store.lots(None, None).unwrap().is_empty());
    }

    #[tokio::test]
    async fn lot_use_unknown_lot() {
        let s = server();
        let e = s.app_lot_use(Parameters(super::AppLotArgs { lot: 999, qty: None })).await.unwrap_err();
        assert!(e.contains("plus en stock"), "{e}");
    }

    #[tokio::test]
    async fn lot_update_moves_and_dates() {
        let s = server();
        s.store.add(&item("Cuisses de poulet", 4.0, "pièce", "frigo", None), "achat", None).unwrap();
        s.store.add(&item("Cuisses de poulet", 2.0, "pièce", "frigo", None), "achat", None).unwrap();
        let id = s.store.lots(None, None).unwrap().iter().find(|l| l.qty == 4.0).unwrap().id;
        let a = serde_json::json!({"lot": id, "location": "congélo", "qty": 3, "expiry": "2027-03-01"});
        v(s.app_lot_update(Parameters(serde_json::from_value(a).unwrap())).await);
        let lots = s.store.lots(None, None).unwrap();
        let moved = lots.iter().find(|l| l.id == id).unwrap();
        assert_eq!((moved.location.as_str(), moved.qty, moved.expiry.as_deref(), moved.estimated), ("congelateur", 3.0, Some("2027-03-01"), false));
        assert_eq!(lots.iter().filter(|l| l.location == "frigo").count(), 1, "l'autre lot ne bouge pas");
        let gone = serde_json::json!({"lot": 999, "qty": 1});
        assert!(s.app_lot_update(Parameters(serde_json::from_value(gone).unwrap())).await.is_err());
    }

    async fn done(s: &FoodServer, day: &str, slot: &str, portions: Option<u32>) -> Result<String, String> {
        s.app_meal_done(Parameters(super::AppMealDoneArgs { day: day.into(), slot: slot.into(), portions })).await
    }

    /// Dhal pour 4 : 300 g de lentilles, 2 oignons, sel (sans quantité).
    fn dhal(s: &FoodServer, day: &str) {
        s.store
            .set_recipe(&Recipe {
                title: "Dhal".into(),
                portions: 4,
                prep_minutes: None,
                cook_minutes: None,
                ingredients: vec![
                    Ingredient { name: "Lentilles corail".into(), qty: Some(300.0), unit: Some("g".into()) },
                    Ingredient { name: "Oignons".into(), qty: Some(2.0), unit: None },
                    Ingredient { name: "Sel".into(), qty: None, unit: None },
                ],
                steps: vec![],
            })
            .unwrap();
        s.store.replace_plan(day, day, &[meal(day, Slot::Soir, "Dhal", MealKind::Cook, None)]).unwrap();
    }

    fn dish_lots(s: &FoodServer, name: &str) -> Vec<Lot> {
        s.store.lots(None, Some(name)).unwrap().into_iter().filter(|l| l.nature == Some(crate::nature::Nature::Plat)).collect()
    }

    #[tokio::test]
    async fn cooked_meal_done_consumes_recipe_and_stores_leftovers() {
        let s = server();
        let day = "2026-11-08";
        dhal(&s, day);
        s.store.add(&item("Lentilles corail", 500.0, "g", "placard", None), "achat", None).unwrap();
        s.store.add(&item("Oignons", 1.0, "pièce", "corbeille", None), "achat", None).unwrap();
        let c = cards(&s, day);
        assert_eq!(c[0]["done"]["tool"], "app_meal_done");
        assert_eq!(c[0]["done"]["args"], serde_json::json!({"day": day, "slot": "soir"}));
        assert_eq!(c[0]["done"]["choices"]["key"], "portions");
        assert_eq!(c[0]["done"]["choices"]["options"], serde_json::json!([1, 2, 3, 4]));
        let j = v(done(&s, day, "soir", Some(1)).await);
        assert!(j["message"].as_str().unwrap().contains("non déduit"), "{j}");
        assert_eq!(s.store.lots(None, Some("lentilles")).unwrap()[0].qty, 200.0);
        assert!(s.store.lots(None, Some("oignons")).unwrap().is_empty(), "jamais négatif");
        let plats = dish_lots(&s, "dhal");
        assert_eq!(plats.len(), 1);
        assert_eq!((plats[0].qty, plats[0].location.as_str()), (3.0, "frigo"));
        assert_eq!(s.store.meals_since(day).unwrap().len(), 1);
    }

    #[tokio::test]
    async fn meal_done_twice_refused() {
        let s = server();
        let day = "2026-11-08";
        dhal(&s, day);
        s.store.add(&item("Lentilles corail", 1000.0, "g", "placard", None), "achat", None).unwrap();
        v(done(&s, day, "soir", None).await);
        let e = done(&s, day, "soir", None).await.unwrap_err();
        assert!(e.contains("déjà"), "{e}");
        assert_eq!(s.store.meals_since(day).unwrap().len(), 1);
        assert_eq!(s.store.lots(None, Some("lentilles")).unwrap()[0].qty, 700.0);
        assert_eq!(dish_lots(&s, "dhal")[0].qty, 3.0);
    }

    #[tokio::test]
    async fn leftover_done_takes_portions() {
        let s = server();
        let day = "2026-11-08";
        s.store.replace_plan(day, day, &[meal(day, Slot::Midi, "Dhal", MealKind::Leftover, None)]).unwrap();
        s.store.add_dish("Dhal", 1.0).unwrap();
        s.store.add_dish("Dhal", 3.0).unwrap();
        let c = cards(&s, day);
        assert_eq!(c[0]["done"]["args"]["slot"], "midi");
        assert!(c[0]["done"]["choices"]["options"].is_array());
        v(done(&s, day, "midi", Some(2)).await);
        let left: f64 = dish_lots(&s, "dhal").iter().map(|l| l.qty).sum();
        assert_eq!(left, 2.0);
        assert!(dish_lots(&s, "dhal").iter().all(|l| l.qty == 2.0), "le plus ancien d'abord");
        assert_eq!(s.store.meals_since(day).unwrap().len(), 1);
    }

    #[tokio::test]
    async fn future_meal_not_done() {
        let s = server();
        let day = (s.today() + chrono::Duration::days(1)).to_string();
        s.store.replace_plan(&day, &day, &[meal(&day, Slot::Midi, "Dhal", MealKind::Leftover, None)]).unwrap();
        s.store.add_dish("Dhal", 2.0).unwrap();
        assert!(cards(&s, &day)[0].get("done").is_none(), "pas de « fait » sur un repas à venir");
        let e = done(&s, &day, "midi", None).await.unwrap_err();
        assert!(e.contains("venir"), "{e}");
        assert!(s.store.meals_since(&day).unwrap().is_empty());
        assert_eq!(dish_lots(&s, "dhal")[0].qty, 2.0);
    }

    #[tokio::test]
    async fn leftover_done_without_dish() {
        let s = server();
        let day = "2026-11-08";
        s.store.replace_plan(day, day, &[meal(day, Slot::Midi, "Dhal", MealKind::Leftover, None)]).unwrap();
        let e = done(&s, day, "midi", None).await.unwrap_err();
        assert!(e.contains("Dhal"), "{e}");
        assert!(s.store.meals_since(day).unwrap().is_empty());
        assert!(s.store.lots(None, None).unwrap().is_empty());
    }

    #[test]
    fn urgent_cards_one_per_product_max_three() {
        let s = server();
        let today = s.today();
        let soon = Some((today + chrono::Duration::days(1)).to_string());
        for name in ["Champignons", "Crème", "Jambon", "Salade"] {
            s.store.add(&item(name, 1.0, "pièce", "frigo", soon.clone()), "achat", None).unwrap();
        }
        s.store.add(&item("Champignons", 2.0, "pièce", "frigo", soon.clone()), "achat", None).unwrap();
        let c = cards(&s, &today.to_string());
        assert_eq!(c.len(), 3);
        let names: Vec<_> = c.iter().map(|x| x["body"].as_str().unwrap().to_string()).collect();
        assert_eq!(names.iter().filter(|n| n.contains("Champignons")).count(), 1, "{names:?}");
        for x in &c {
            assert_eq!(x["done"]["tool"], "app_lot_use");
            let lot = x["done"]["args"]["lot"].as_i64().unwrap();
            assert!(s.store.lots(None, None).unwrap().iter().any(|l| l.id == lot));
        }
    }

    #[test]
    fn box_card_is_a_local_note() {
        let s = server();
        let day = "2026-11-08";
        let next = "2026-11-09";
        let plan = [
            meal(day, Slot::Soir, "Riz", MealKind::Cook, Some(BOX_NOTE)),
            meal(next, Slot::Midi, "Dhal", MealKind::Box, None),
        ];
        s.store.replace_plan(day, next, &plan).unwrap();
        s.store.add_dish("Dhal", 2.0).unwrap();
        let id = dish_lots(&s, "dhal")[0].id;
        s.store.update_lot(id, Some("congelateur"), None, None).unwrap();
        let c = cards(&s, day);
        assert_eq!(c[1]["id"], "box-2026-11-08");
        assert!(c[1].get("done").is_none(), "la portion est déjà au frigo : note locale seulement");
    }

    #[tokio::test]
    async fn leftover_matches_dish_exactly() {
        let s = server();
        let day = "2026-11-08";
        s.store.replace_plan(day, day, &[meal(day, Slot::Midi, "Riz", MealKind::Leftover, None)]).unwrap();
        s.store.add_dish("Riz cantonais", 2.0).unwrap();
        assert!(done(&s, day, "midi", None).await.is_err(), "« Riz » ne puise pas dans « Riz cantonais »");
        assert_eq!(dish_lots(&s, "riz cantonais")[0].qty, 2.0);
    }

    #[tokio::test]
    async fn leftover_done_prefers_fridge_over_freezer() {
        let s = server();
        let day = "2026-11-08";
        s.store.replace_plan(day, day, &[meal(day, Slot::Midi, "Dhal", MealKind::Leftover, None)]).unwrap();
        s.store.add_dish("Dhal", 2.0).unwrap();
        let frozen = dish_lots(&s, "dhal")[0].id;
        s.store.update_lot(frozen, Some("congelateur"), None, None).unwrap();
        s.store.add_dish("Dhal", 2.0).unwrap();
        v(done(&s, day, "midi", None).await);
        let lots = dish_lots(&s, "dhal");
        assert_eq!(lots.iter().find(|l| l.id == frozen).unwrap().qty, 2.0, "le congelé reste entier");
        assert_eq!(lots.iter().find(|l| l.location == "frigo").unwrap().qty, 1.0);
    }

    #[tokio::test]
    async fn leftover_shortfall_is_said() {
        let s = server();
        let day = "2026-11-08";
        s.store.replace_plan(day, day, &[meal(day, Slot::Midi, "Dhal", MealKind::Leftover, None)]).unwrap();
        s.store.add_dish("Dhal", 1.0).unwrap();
        let j = v(done(&s, day, "midi", Some(3)).await);
        assert!(j["message"].as_str().unwrap().contains("seulement 1 portion(s) disponible(s)"), "{j}");
        assert!(dish_lots(&s, "dhal").is_empty());
    }

    #[test]
    fn add_dish_keeps_raw_ingredient() {
        let s = server();
        s.store.add(&item("Riz", 2.0, "pièce", "placard", None), "achat", None).unwrap();
        let raw = s.store.lots(None, None).unwrap()[0].clone();
        s.store.add_dish("Riz", 3.0).unwrap();
        let lots = s.store.lots(None, None).unwrap();
        assert_eq!(lots.iter().find(|l| l.id == raw.id).unwrap().nature, raw.nature, "l'ingrédient n'est pas retagué");
        assert_ne!(raw.nature, Some(crate::nature::Nature::Plat));
        let plats = dish_lots(&s, "riz");
        assert_eq!((plats.len(), plats[0].qty), (1, 3.0));
    }

    #[tokio::test]
    async fn meal_log_after_app_done_not_doubled() {
        let s = server();
        let day = "2026-11-08";
        dhal(&s, day);
        s.store.add(&item("Lentilles corail", 1000.0, "g", "placard", None), "achat", None).unwrap();
        v(done(&s, day, "soir", None).await);
        let a = serde_json::json!({"slot": "dîner", "name": "Dhal", "day": day,
            "ingredients": [{"name": "Lentilles corail", "qty": 300, "unit": "g"}]});
        assert!(s.meal_log(Parameters(serde_json::from_value(a).unwrap())).await.is_err());
        assert_eq!(s.store.meals_since(day).unwrap().len(), 1);
        assert_eq!(s.store.lots(None, Some("lentilles")).unwrap()[0].qty, 700.0);
    }

    async fn add_by_name(s: &FoodServer, name: &str, qty: f64, unit: &str) {
        let a = serde_json::json!({"name": name, "qty": qty, "unit": unit, "location": "placard"});
        v(s.app_add(Parameters(serde_json::from_value(a).unwrap())).await);
    }

    async fn decisions(s: &FoodServer) -> Vec<serde_json::Value> {
        serde_json::from_str(&s.decisions().await.unwrap()).unwrap()
    }

    async fn decide(s: &FoodServer, id: &str, choice: &str) -> Result<String, String> {
        s.decide(Parameters(serde_json::from_value(serde_json::json!({"id": id, "choice": choice})).unwrap())).await
    }

    fn product_id(s: &FoodServer, name: &str) -> i64 {
        s.store.find(name).unwrap()[0].id
    }

    #[tokio::test]
    async fn unknown_product_asks_nature_then_size() {
        let s = server();
        add_by_name(&s, "Zorglub", 2.0, "pièce").await;
        let id = product_id(&s, "Zorglub");
        let d = decisions(&s).await;
        assert_eq!(d.len(), 1, "la contenance attend la nature : {d:?}");
        assert_eq!(d[0]["id"], format!("produit-{id}-nature"));
        assert!(d[0]["headline"].as_str().unwrap().contains("Zorglub"));
        assert!(d[0]["proposal"].is_null());
        let alts: Vec<&str> = d[0]["alternatives"].as_array().unwrap().iter().map(|a| a["id"].as_str().unwrap()).collect();
        assert!(alts.contains(&"alimentaire:legume") && alts.contains(&"entretien") && alts.contains(&"hygiene"), "{alts:?}");
        assert!(!alts.contains(&"alimentaire:entretien"));
        let exp = chrono::NaiveDate::parse_from_str(d[0]["expires"].as_str().unwrap(), "%Y-%m-%d").unwrap();
        assert_eq!(exp, s.today() + chrono::Duration::days(30));
        decide(&s, &format!("produit-{id}-nature"), "entretien").await.unwrap();
        let d = decisions(&s).await;
        assert_eq!(d.len(), 1);
        assert_eq!(d[0]["id"], format!("produit-{id}-contenance"));
        let alts: Vec<&str> = d[0]["alternatives"].as_array().unwrap().iter().map(|a| a["id"].as_str().unwrap()).collect();
        assert!(alts.contains(&"750ml") && alts.contains(&"piece"), "entretien : liquides + pièce {alts:?}");
        // Produit déjà nommé par une fiche ou une unité connue : rien à demander.
        s.store.add(&item("Riz basmati", 1.0, "kg", "placard", None), "achat", None).unwrap();
        assert_eq!(decisions(&s).await.len(), 1);
    }

    #[tokio::test]
    async fn decide_nature_updates_product() {
        let s = server();
        add_by_name(&s, "Zorglub", 1.0, "pièce").await;
        let id = product_id(&s, "Zorglub");
        decide(&s, &format!("produit-{id}-nature"), "alimentaire:legume").await.unwrap();
        assert_eq!(s.store.lots(None, Some("zorglub")).unwrap()[0].nature, Some(crate::nature::Nature::Legume));
        let e = decide(&s, &format!("produit-{id}-nature"), "alimentaire:pierre").await.unwrap_err();
        assert!(e.contains("déjà"), "{e}");
        add_by_name(&s, "Machin", 1.0, "pièce").await;
        let m = product_id(&s, "Machin");
        assert!(decide(&s, &format!("produit-{m}-nature"), "alimentaire:pierre").await.is_err());
        assert!(decide(&s, &format!("produit-{m}-nature"), "alimentaire:entretien").await.is_err());
        assert_eq!(s.store.lots(None, Some("machin")).unwrap()[0].nature, None);
    }

    #[tokio::test]
    async fn decide_size_sets_unit() {
        let s = server();
        add_by_name(&s, "Zorglub", 2.0, "pièce").await;
        let id = product_id(&s, "Zorglub");
        decide(&s, &format!("produit-{id}-nature"), "hygiene").await.unwrap();
        let size = format!("produit-{id}-contenance");
        assert!(decide(&s, &size, "500g").await.is_err(), "hygiène : contenances liquides seulement");
        decide(&s, &size, "250ml").await.unwrap();
        let lots = s.store.lots(None, Some("zorglub")).unwrap();
        assert_eq!((lots[0].family, lots[0].qty), (Family::Volume, 500.0), "2 pièces de 250 ml");
        assert!(decisions(&s).await.is_empty());
        // L'unité suit : un nouvel apport se compte en ml.
        assert_eq!(v(s.app_product(product_args(serde_json::json!({"name": "Zorglub"}))).await)["unit"], "ml");
        add_by_name(&s, "Bidule", 3.0, "pièce").await;
        let b = product_id(&s, "Bidule");
        decide(&s, &format!("produit-{b}-nature"), "alimentaire:fruit").await.unwrap();
        decide(&s, &format!("produit-{b}-contenance"), "piece").await.unwrap();
        let lots = s.store.lots(None, Some("bidule")).unwrap();
        assert_eq!((lots[0].family, lots[0].qty), (Family::Piece, 3.0));
        assert!(decisions(&s).await.is_empty(), "« à l'unité » est une réponse");
    }

    #[tokio::test]
    async fn completion_skips_already_known() {
        let s = server();
        add_by_name(&s, "Zorglub", 2.0, "pièce").await;
        let id = product_id(&s, "Zorglub");
        // Complété par la conversation entre-temps.
        let a = serde_json::json!({"name": "Zorglub", "nature": "boisson"});
        s.product_set(Parameters(serde_json::from_value(a).unwrap())).await.unwrap();
        let d = decisions(&s).await;
        assert!(d.iter().all(|x| x["id"] != format!("produit-{id}-nature")), "{d:?}");
        let e = decide(&s, &format!("produit-{id}-nature"), "entretien").await.unwrap_err();
        assert!(e.contains("déjà"), "{e}");
        assert_eq!(s.store.lots(None, Some("zorglub")).unwrap()[0].nature, Some(crate::nature::Nature::Boisson), "pas de régression");
        decide(&s, &format!("produit-{id}-contenance"), "500ml").await.unwrap();
        assert!(decide(&s, &format!("produit-{id}-contenance"), "250ml").await.is_err(), "pas de seconde conversion");
        assert_eq!(s.store.lots(None, Some("zorglub")).unwrap()[0].qty, 1000.0);
    }

    const SHEET: &str = r#"{"status":1,"product":{"product_name":"Xylo","quantity":"1,5 l","categories_tags":["en:laundry"]}}"#;

    #[tokio::test]
    async fn barcode_add_takes_pack_and_category_from_base() {
        // Sans nom : la fiche donne nom, contenance et catégorie.
        let s = off_answering("200 OK", SHEET).await;
        let a = serde_json::json!({"barcode": "3017620422003", "qty": 2, "unit": "pièce", "location": "placard"});
        v(s.app_add(Parameters(serde_json::from_value(a).unwrap())).await);
        let l = &s.store.lots(None, Some("xylo")).unwrap()[0];
        assert_eq!((l.family, l.qty, l.nature), (Family::Volume, 3000.0, Some(crate::nature::Nature::Entretien)));
        assert!(decisions(&s).await.is_empty(), "rien à demander : {:?}", decisions(&s).await);
        // Avec le nom repris par l'application : même fiche.
        let s = off_answering("200 OK", SHEET).await;
        let a = serde_json::json!({"name": "Xylo", "barcode": "3017620422003", "qty": 1, "unit": "pièce", "location": "placard"});
        v(s.app_add(Parameters(serde_json::from_value(a).unwrap())).await);
        let l = &s.store.lots(None, Some("xylo")).unwrap()[0];
        assert_eq!((l.family, l.qty), (Family::Volume, 1500.0));
        assert!(decisions(&s).await.is_empty());
    }
}
