//! Nature culinaire d'un produit et règles de repas. Le module trie, le moteur
//! compose : seuls des candidats pertinents pour un créneau et un contexte lui
//! sont proposés.
//!
//! Les règles sont lues dans l'ordre ; la première qui correspond l'emporte,
//! d'où les mots précis avant les mots généraux (« lait de coco » avant
//! « lait », « pain au chocolat » avant « pain »).

use crate::shelf::has;
use crate::units::fold;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Nature {
    Proteine,
    Legume,
    Fruit,
    Feculent,
    Laitier,
    Sucre,
    Condiment,
    Epice,
    Boisson,
    Base,
    Plat,
}

use Nature::*;

pub const ALL: [Nature; 11] = [Proteine, Legume, Fruit, Feculent, Laitier, Sucre, Condiment, Epice, Boisson, Base, Plat];

impl Nature {
    pub fn as_str(self) -> &'static str {
        match self {
            Proteine => "proteine",
            Legume => "legume",
            Fruit => "fruit",
            Feculent => "feculent",
            Laitier => "laitier",
            Sucre => "sucre",
            Condiment => "condiment",
            Epice => "epice",
            Boisson => "boisson",
            Base => "base",
            Plat => "plat",
        }
    }

    /// Accepte la valeur stockée ou sa forme accentuée (« légume », « protéine »).
    pub fn parse(s: &str) -> Option<Nature> {
        let f = fold(s.trim());
        ALL.into_iter().find(|n| n.as_str() == f)
    }

    /// Titre de section affiché.
    pub fn label(self) -> &'static str {
        match self {
            Proteine => "Protéines",
            Legume => "Légumes",
            Fruit => "Fruits",
            Feculent => "Féculents",
            Laitier => "Produits laitiers",
            Sucre => "Sucré",
            Condiment => "Condiments",
            Epice => "Épices",
            Boisson => "Boissons",
            Base => "Bases de cuisine",
            Plat => "Plats et restes",
        }
    }
}

const RULES: &[(&[&str], Nature)] = &[
    (
        &[
            "reste", "restes", "plat", "lasagne", "gratin", "hachis", "quiche", "pizza", "taboule", "sandwich", "wrap",
            "croque", "soupe", "veloute", "gaspacho", "chili",
        ],
        Plat,
    ),
    // Avant le sucré : « farine pain multi-céréales » est une farine.
    (&["farine", "lait de coco", "creme de coco", "fecule", "maizena", "levure", "chapelure"], Base),
    (
        &[
            "pain au chocolat", "pains au chocolat", "croissant", "brioche", "viennoiserie", "chocolat", "biscuit",
            "gateau", "compote", "confiture", "miel", "cereales", "madeleine", "cookie", "bonbon", "creme dessert",
            "dessert", "pate a tartiner", "sirop d'erable",
        ],
        Sucre,
    ),
    (
        &[
            "sucre", "cacao", "poudre", "amande", "noix", "noisette", "graine", "graines", "fruits secs",
        ],
        Base,
    ),
    (
        &[
            "sauce", "vinaigre", "vinaigrette", "condiment", "huile", "moutarde", "bouillon", "pesto", "olivade",
            "tartinade", "tapenade", "ketchup", "mayonnaise",
        ],
        Condiment,
    ),
    (
        &[
            "jus", "soda", "sprite", "coca", "sirop", "cafe", "dosette", "the", "infusion", "rhum", "vin", "biere",
            "whisky", "eau", "smoothie",
        ],
        Boisson,
    ),
    (
        &[
            "sel", "poivre", "paprika", "cannelle", "gingembre", "laurier", "origan", "curry", "herbes", "thym", "cumin",
            "curcuma", "muscade", "vanille", "basilic", "persil", "ciboulette", "coriandre", "menthe", "sesame",
        ],
        Epice,
    ),
    (
        &[
            "lait", "yaourt", "yogourt", "skyr", "fromage", "emmental", "comte", "gruyere", "parmesan", "cheddar",
            "mozzarella", "burrata", "feta", "chevre", "camembert", "brie", "raclette", "reblochon", "beurre", "creme",
            "petit suisse", "petits suisses", "ricotta", "mascarpone",
        ],
        Laitier,
    ),
    (
        &[
            "hache", "steak", "boeuf", "veau", "porc", "poulet", "dinde", "canard", "agneau", "escalope", "saucisse",
            "merguez", "viande", "cuisse", "roti", "volaille", "jambon", "lardon", "bacon", "saucisson", "chorizo",
            "foie gras", "blanc de", "poisson", "saumon", "cabillaud", "colin", "merlu", "truite", "thon", "sardine",
            "maquereau", "crevette", "moule", "fruits de mer", "oeuf", "tofu", "lentille", "lentilles", "pois chiche",
            "pois chiches", "haricots rouges", "haricots blancs",
        ],
        Proteine,
    ),
    (
        &[
            "pomme de terre", "pommes de terre", "patate", "pates", "pate", "spaghetti", "linguine", "farfalle", "penne",
            "tagliatelle", "coquillette", "nouilles", "riz", "semoule", "quinoa", "boulgour", "polenta", "gnocchi",
            "ravioli", "pain", "baguette", "tortilla", "galette",
        ],
        Feculent,
    ),
    (
        &[
            "legume", "tomate", "carotte", "champignon", "potimarron", "courge", "butternut", "citrouille", "potiron",
            "oignon", "ail", "echalote", "poireau", "chou", "celeri", "navet", "betterave", "radis", "fenouil", "panais",
            "epinard", "salade", "laitue", "mache", "roquette", "courgette", "aubergine", "poivron", "concombre",
            "haricot vert", "haricots verts", "petits pois", "brocoli", "asperge",
        ],
        Legume,
    ),
    (
        &[
            "pomme", "poire", "orange", "clementine", "mandarine", "citron", "kiwi", "raisin", "prune", "banane",
            "avocat", "peche", "nectarine", "abricot", "mangue", "fraise", "framboise", "ananas", "fruit",
        ],
        Fruit,
    ),
];

/// Tags de catégorie anglais de la base produits, en complément des règles.
const CATEGORY_TAGS: &[(&[&str], Nature)] = &[
    (&["pastries", "biscuits", "chocolates", "sweet spreads", "breakfast cereals"], Sucre),
    (&["breads", "pastas", "rices", "potatoes"], Feculent),
    (&["meats", "poultry", "chickens", "fishes", "seafood", "eggs", "legumes"], Proteine),
    (&["cheeses", "milks", "yogurts", "dairies"], Laitier),
    (&["beverages", "juices", "sodas", "coffees", "teas"], Boisson),
    (&["sauces", "condiments", "oils", "vinegars"], Condiment),
    (&["spices", "herbs", "salts"], Epice),
    (&["flours", "sugars", "baking"], Base),
    (&["vegetables"], Legume),
    (&["fruits"], Fruit),
    (&["meals", "prepared"], Plat),
];

fn first(rules: &[(&[&str], Nature)], folded: &str) -> Option<Nature> {
    rules.iter().find(|(words, _)| words.iter().any(|w| has(folded, w))).map(|&(_, n)| n)
}

pub fn from_name(name: &str) -> Option<Nature> {
    first(RULES, &fold(name))
}

/// `None` si la catégorie n'est pas reconnue : elle ne masque alors pas le nom.
pub fn from_category(cat: &str) -> Option<Nature> {
    let f = fold(cat);
    first(RULES, &f).or_else(|| first(CATEGORY_TAGS, &f))
}

/// Correction manuelle, sinon catégorie reconnue, sinon nom.
pub fn resolve(manual: Option<Nature>, category: Option<&str>, name: &str) -> Option<Nature> {
    manual.or_else(|| category.and_then(from_category)).or_else(|| from_name(name))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Attrs {
    pub a_cuire: bool,
    pub froid_ok: bool,
    pub transportable: bool,
}

/// Se mangent sans cuisson.
const RAW_OK: &[&str] = &[
    "jambon", "saucisson", "chorizo", "foie gras", "blanc de", "thon", "sardine", "maquereau", "tomate", "salade",
    "laitue", "mache", "roquette", "concombre", "radis", "carotte", "poivron", "pain", "baguette", "tortilla",
];
/// Tiennent quelques heures dans un sac.
const STURDY: &[&str] = &[
    "saucisson", "chorizo", "thon", "sardine", "emmental", "comte", "gruyere", "parmesan", "cheddar", "tomate",
    "carotte", "concombre", "radis", "poivron",
];
/// Plats qui se mangent froids et s'emportent.
const COLD_DISHES: &[&str] = &["salade", "taboule", "sandwich", "wrap", "quiche", "froid", "froide"];

pub fn attrs(n: Nature, name: &str) -> Attrs {
    let f = fold(name);
    let any = |words: &[&str]| words.iter().any(|w| has(&f, w));
    let raw = any(RAW_OK);
    match n {
        Proteine | Legume => Attrs { a_cuire: !raw, froid_ok: raw, transportable: any(STURDY) },
        Feculent => Attrs { a_cuire: !raw, froid_ok: raw, transportable: raw },
        Fruit | Sucre => Attrs { a_cuire: false, froid_ok: true, transportable: true },
        Laitier => Attrs { a_cuire: false, froid_ok: true, transportable: any(STURDY) },
        Plat => {
            let cold = any(COLD_DISHES);
            Attrs { a_cuire: false, froid_ok: cold, transportable: cold }
        }
        Condiment | Epice | Boisson | Base => Attrs { a_cuire: false, froid_ok: false, transportable: false },
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Slot {
    PetitDejeuner,
    Midi,
    Gouter,
    Soir,
}

impl Slot {
    pub fn parse(s: &str) -> Option<Slot> {
        match fold(s.trim()).as_str() {
            x if x.starts_with("petit") || x == "matin" => Some(Slot::PetitDejeuner),
            "midi" | "dejeuner" => Some(Slot::Midi),
            "gouter" | "encas" | "collation" => Some(Slot::Gouter),
            "soir" | "diner" | "souper" => Some(Slot::Soir),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Slot::PetitDejeuner => "petit-dejeuner",
            Slot::Midi => "midi",
            Slot::Gouter => "gouter",
            Slot::Soir => "soir",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Slot::PetitDejeuner => "le petit-déjeuner",
            Slot::Midi => "midi",
            Slot::Gouter => "le goûter",
            Slot::Soir => "le soir",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Context {
    Maison,
    Boulot,
    Rando,
}

impl Context {
    pub fn parse(s: &str) -> Option<Context> {
        match fold(s.trim()).as_str() {
            "maison" | "domicile" => Some(Context::Maison),
            "boulot" | "bureau" | "travail" => Some(Context::Boulot),
            "rando" | "randonnee" | "sortie" => Some(Context::Rando),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Context::Maison => "maison",
            Context::Boulot => "boulot",
            Context::Rando => "rando",
        }
    }
}

/// Natures candidates, dans l'ordre d'affichage. Condiments, épices, boissons
/// et bases n'y figurent jamais : ils accompagnent, ils ne font pas un repas.
pub fn groups(slot: Slot, ctx: Context) -> &'static [Nature] {
    match (slot, ctx) {
        (Slot::PetitDejeuner, _) => &[Laitier, Sucre, Fruit, Feculent],
        (Slot::Gouter, _) => &[Fruit, Sucre, Laitier],
        (Slot::Midi, Context::Rando) => &[Plat, Proteine, Legume, Fruit, Feculent, Laitier, Sucre],
        (Slot::Midi, _) => &[Plat, Proteine, Legume, Feculent, Laitier],
        (Slot::Soir, _) => &[Plat, Legume, Proteine, Feculent, Laitier],
    }
}

pub fn fits(slot: Slot, ctx: Context, n: Nature, a: Attrs) -> bool {
    groups(slot, ctx).contains(&n)
        && match (slot, ctx) {
            (Slot::PetitDejeuner, _) => !a.a_cuire,
            (Slot::Gouter, Context::Rando) => !a.a_cuire && a.transportable,
            (Slot::Gouter, _) => !a.a_cuire,
            (Slot::Midi, Context::Rando) => a.froid_ok && a.transportable,
            _ => true,
        }
}

/// Produit d'épicerie à longue conservation, quel que soit l'aliment nommé
/// (« farine pain », « tomates pelées »).
pub fn is_pantry(folded_name: &str) -> bool {
    ["farine", "conserve", "pelee", "pelees", "concentre", "boite", "sec", "secs", "seche", "seches"]
        .iter()
        .any(|w| has(folded_name, w))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nature_rules_prefer_specific_words() {
        assert_eq!(from_name("Farine pain multi-céréales"), Some(Nature::Base));
        assert_eq!(from_name("Tomates entières pelées"), Some(Nature::Legume));
        assert_eq!(from_name("Pains au chocolat x4"), Some(Nature::Sucre));
        assert_eq!(from_name("Poulet fermier jaune"), Some(Nature::Proteine));
        assert_eq!(from_name("Lait de coco"), Some(Nature::Base));
        assert_eq!(from_name("Lait demi-écrémé"), Some(Nature::Laitier));
        assert_eq!(from_name("Jus d'orange"), Some(Nature::Boisson));
        assert_eq!(from_name("Sauce tomate concentrée"), Some(Nature::Condiment));
        assert_eq!(from_name("Compote de pommes"), Some(Nature::Sucre));
        assert_eq!(from_name("Lentilles corail"), Some(Nature::Proteine));
        assert_eq!(from_name("Patates douces"), Some(Nature::Feculent));
        assert_eq!(from_name("Reste de lasagnes"), Some(Nature::Plat));
        assert_eq!(from_name("Paprika fumé"), Some(Nature::Epice));
        assert_eq!(from_name("Objet mystère"), None);
    }

    #[test]
    fn manual_beats_category_beats_name() {
        assert_eq!(resolve(Some(Nature::Plat), Some("breads"), "Pain"), Some(Nature::Plat));
        assert_eq!(resolve(None, Some("pains au chocolat"), "Produit X"), Some(Nature::Sucre));
        // tag trop vague : le nom décide
        assert_eq!(resolve(None, Some("plant based foods"), "Carottes"), Some(Nature::Legume));
    }

    #[test]
    fn meal_rules() {
        let poulet = attrs(Nature::Proteine, "Poulet fermier");
        assert!(poulet.a_cuire);
        assert!(fits(Slot::Soir, Context::Maison, Nature::Proteine, poulet));
        assert!(!fits(Slot::Midi, Context::Rando, Nature::Proteine, poulet));
        let viennoiserie = attrs(Nature::Sucre, "Pains au chocolat");
        assert!(fits(Slot::PetitDejeuner, Context::Maison, Nature::Sucre, viennoiserie));
        assert!(!fits(Slot::Soir, Context::Maison, Nature::Sucre, viennoiserie));
        let pain = attrs(Nature::Feculent, "Baguette");
        assert!(!pain.a_cuire && pain.froid_ok && pain.transportable);
        assert!(!fits(Slot::Soir, Context::Maison, Nature::Condiment, attrs(Nature::Condiment, "Sauce soja")));
        assert_eq!(groups(Slot::Soir, Context::Maison)[..2], [Nature::Plat, Nature::Legume]);
    }
}
