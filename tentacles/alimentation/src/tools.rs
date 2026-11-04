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

use crate::off::Off;
use crate::store::{Line, Lot, Store, date};
use crate::units::show;

type Out = Result<String, String>;

pub struct Settings {
    pub tz: chrono_tz::Tz,
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
    /// frigo, congelateur, placard ou corbeille.
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
    /// frigo, congelateur, placard ou corbeille.
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
struct BriefArgs {
    /// Contrat commun des sections de brief.
    #[allow(dead_code)]
    period: tentacle_common::BriefPeriod,
}

impl FoodServer {
    pub fn new(store: Arc<Store>, off: Arc<Off>, settings: Arc<Settings>) -> Self {
        Self { store, off, settings, tool_router: Self::tool_router() }
    }

    fn today(&self) -> chrono::NaiveDate {
        chrono::Utc::now().with_timezone(&self.settings.tz).date_naive()
    }

    fn lot_line(&self, l: &Lot) -> String {
        let when = match l.expiry.as_deref().and_then(|e| chrono::NaiveDate::parse_from_str(e, "%Y-%m-%d").ok()) {
            Some(d) => match (d - self.today()).num_days() {
                n if n < 0 => format!(", périmé depuis {} j", -n),
                0 => ", à finir aujourd'hui".to_string(),
                1 => ", à finir demain".to_string(),
                n => format!(", avant le {} ({n} j)", d.format("%d/%m")),
            },
            None => String::new(),
        };
        format!("- {} : {}{}{}", l.product, l.show(), if l.opened { ", entamé" } else { "" }, when)
    }

    fn urgent(&self, days: i64) -> Result<Vec<Lot>, String> {
        let limit = self.today() + chrono::Duration::days(days);
        Ok(self
            .store
            .lots(None, None)
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter(|l| {
                l.expiry
                    .as_deref()
                    .and_then(|e| chrono::NaiveDate::parse_from_str(e, "%Y-%m-%d").ok())
                    .is_some_and(|d| d <= limit)
            })
            .collect())
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

    #[tool(description = "Déplacer un produit (ex. viande au congélateur) et fixer sa nouvelle date limite.")]
    async fn stock_move(&self, Parameters(a): Parameters<MoveArgs>) -> Out {
        self.store.relocate(&a.name, &a.to, a.expiry.as_deref())
    }

    #[tool(
        description = "Lister le stock du logement par emplacement (frigo, congélateur, placard, corbeille), les plus urgents d'abord. Base pour proposer des recettes avec ce qui reste."
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
        description = "Produits à consommer en priorité : date limite dans l'horizon donné ou dépassée. À utiliser pour ordonner les repas et éviter le gaspillage."
    )]
    async fn use_first(&self, Parameters(a): Parameters<UseFirstArgs>) -> Out {
        let days = a.days.unwrap_or(4).clamp(0, 30);
        let lots = self.urgent(days)?;
        if lots.is_empty() {
            return Ok(format!("Rien à finir dans les {days} prochains jours."));
        }
        Ok(format!(
            "À consommer en priorité :\n{}",
            lots.iter().map(|l| self.lot_line(l)).collect::<Vec<_>>().join("\n")
        ))
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
