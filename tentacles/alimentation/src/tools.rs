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

/// Se gâte vraiment : DLC, produit gardé au frais (œufs, beurre), frais sans
/// date lue (estimation courte) ou nature de date inconnue (prudence). Une DDM
/// imprimée hors du frigo, ou estimée à long terme (riz, pâtes), n'est jamais urgente.
fn perishable(l: &Lot) -> bool {
    let short = || {
        let added = chrono::DateTime::from_timestamp(l.added_at, 0).map(|t| t.date_naive());
        matches!((expiry(l), added), (Some(e), Some(a)) if (e - a).num_days() <= FRESH_SPAN_DAYS)
    };
    l.kind != Some(Kind::Ddm) || l.location == "frigo" || (l.estimated && short())
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
            cards.push(c);
            if m.slot == Slot::Soir && m.note.as_deref() == Some(crate::plan::BOX_NOTE) {
                cards.push(json!({
                    "id": format!("box-{d}"), "kind": "info", "time": "20:00",
                    "title": "Box de demain", "body": crate::plan::BOX_NOTE,
                }));
            }
        }
        // ponytail: l'urgence se calcule depuis aujourd'hui, donc seulement pour le jour courant.
        if d == self.today() {
            let urgent = self.urgent(1)?;
            if !urgent.is_empty() {
                let names = urgent.iter().map(|l| l.product.as_str()).collect::<Vec<_>>().join(", ");
                cards.push(json!({
                    "id": format!("a-finir-{d}"), "kind": "info", "title": "À finir vite",
                    "body": names, "chips": [{"text": "urgent", "tone": "urgent"}],
                }));
            }
        }
        serde_json::to_string(&cards).map_err(|e| e.to_string())
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
            .filter(|l| !perishable(l) && expiry(l).is_some_and(|d| d < today))
            .collect();
        lots.sort_by(|a, b| expiry(a).cmp(&expiry(b)).then_with(|| a.product.cmp(&b.product)));
        lots.truncate(STALE_MAX);
        Ok(lots)
    }

    /// Complète une ligne depuis la base produits quand seul le code-barres est connu.
    async fn complete(&self, mut l: Line) -> Result<Line, String> {
        if l.name.trim().is_empty() {
            let code = l.barcode.clone().ok_or("Ni nom ni code-barres.")?;
            let p =
                self.off.lookup(&code).await?.ok_or_else(|| format!("Code-barres {code} inconnu : donner le nom."))?;
            l.name = match p.brand {
                Some(b) => format!("{} ({b})", p.name),
                None => p.name,
            };
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
        let (out, keep): (Vec<&Line>, Vec<&Line>) = o.lines.iter().partition(|l| is_missing(l));
        if !a.confirm.unwrap_or(false) {
            let mut s = format!("Commande {} : {} ligne(s) à mettre en stock. {PREVIEW}", o.id, keep.len());
            for l in &out {
                s.push_str(&format!("\n- rupture : {}", l.name));
            }
            return Ok(s);
        }
        let mut done = Vec::new();
        for l in keep {
            done.push(self.store.add(l, "achat", Some(o.id))?);
        }
        self.store.mark_received(o.id).map_err(|e| e.to_string())?;
        let mut s =
            format!("Commande {} réceptionnée : {} produit(s) en stock.\n- {}", o.id, done.len(), done.join("\n- "));
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
        let id =
            self.store.add_meal(&day, slot, a.name.trim(), &place, a.notes.as_deref()).map_err(|e| e.to_string())?;
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
}
