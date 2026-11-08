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
    /// Ménage et maison : jamais un aliment.
    Entretien,
    /// Soin du corps : jamais un aliment.
    Hygiene,
}

use Nature::*;

pub const ALL: [Nature; 13] =
    [Proteine, Legume, Fruit, Feculent, Laitier, Sucre, Condiment, Epice, Boisson, Base, Plat, Entretien, Hygiene];

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
            Entretien => "entretien",
            Hygiene => "hygiene",
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
            Entretien => "Entretien",
            Hygiene => "Hygiène",
        }
    }

    /// Se mange ou se boit : seuls ces produits entrent dans repas, urgences et briefings.
    pub fn is_food(self) -> bool {
        !matches!(self, Entretien | Hygiene)
    }
}

const RULES: &[(&[&str], Nature)] = &[
    (
        &[
            "liquide vaisselle", "vaisselle", "lessive", "eponge", "eponges", "javel", "nettoyant", "detergent",
            "detartrant", "desinfectant", "adoucissant", "sac poubelle", "sacs poubelle", "papier toilette",
            "essuie-tout", "essuie tout", "balai", "serpillere", "lingette", "lingettes", "degraissant",
        ],
        Entretien,
    ),
    (
        &[
            "shampooing", "shampoing", "gel douche", "dentifrice", "brosse a dents", "savon", "deodorant",
            "apres-shampooing", "coton", "cotons", "rasoir", "mousse a raser", "serviette hygienique", "tampon",
            "mouchoir", "mouchoirs", "demaquillant", "gel hydroalcoolique",
        ],
        Hygiene,
    ),
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
            "sauce", "vinaigre", "vinaigrette", "condiment", "huile", "moutarde", "bouillon", "pesto", "olivade",
            "tartinade", "tapenade", "ketchup", "mayonnaise",
        ],
        Condiment,
    ),
    (
        &[
            "sucre", "cacao", "poudre", "amande", "noix", "noisette", "graine", "graines", "fruits secs",
        ],
        Base,
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
            "hache", "steak", "boeuf", "veau", "porc", "poulet", "dinde", "canard", "agneau", "coq", "escalope", "saucisse",
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
    (&["cleaning", "detergents", "dishwashing", "household", "laundry", "entretien", "droguerie"], Entretien),
    (&["hygiene", "soaps", "shampoos", "toothpastes", "deodorants", "shower gels", "toilet paper", "cosmetics"], Hygiene),
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

/// Expressions dont le sens diffère de leurs mots : lues avant tout le reste.
const PHRASES: &[(&str, Nature)] = &[
    ("eau de javel", Entretien),
    ("sel lave-vaisselle", Entretien),
    ("sel lave vaisselle", Entretien),
    ("lave-vaisselle", Entretien),
    ("pate dentifrice", Hygiene),
    ("lait corporel", Hygiene),
    ("lait demaquillant", Hygiene),
    ("creme hydratante", Hygiene),
    ("eau micellaire", Hygiene),
    ("lait de coco", Base),
    ("creme de coco", Base),
    ("coconut milk", Base),
    ("fruits secs", Base),
    ("pain au chocolat", Sucre),
    ("pains au chocolat", Sucre),
    ("pain au lait", Sucre),
    ("pains au lait", Sucre),
    ("sirop d'erable", Sucre),
    ("pate a tartiner", Sucre),
    ("creme dessert", Sucre),
    ("fruits de mer", Proteine),
    ("noix de saint", Proteine),
    ("plat de cote", Proteine),
    ("plat de cotes", Proteine),
    ("foie gras", Proteine),
    ("pois chiche", Proteine),
    ("pois chiches", Proteine),
    ("feuilles de lasagne", Feculent),
    ("pomme de terre", Feculent),
    ("pommes de terre", Feculent),
    ("petits pois", Legume),
    ("haricot vert", Legume),
    ("haricots verts", Legume),
];

/// Position du mot dans le nom, en début de mot. Un mot court (« sel »,
/// « vin », « eau ») doit être entier, au pluriel près : « selle » n'est pas
/// du sel, « vinaigre » n'est pas du vin.
fn position(name: &str, word: &str) -> Option<usize> {
    let w = fold(word);
    name.match_indices(w.as_str())
        .map(|(i, _)| i)
        .filter(|&i| i == 0 || name[..i].ends_with([' ', '-', '\'', '(']))
        .find(|&i| {
            if w.chars().count() > 4 {
                return true;
            }
            let rest = &name[i + w.len()..];
            let rest = rest.strip_prefix('s').unwrap_or(rest);
            rest.chars().next().is_none_or(|c| !c.is_alphanumeric())
        })
}

/// L'aliment principal vient en tête du nom (« thon à l'huile », « beurre
/// demi-sel ») : le mot reconnu le plus tôt l'emporte ; à égalité, l'ordre
/// des règles.
fn earliest(rules: &[(&[&str], Nature)], folded: &str) -> Option<Nature> {
    let mut best: Option<(usize, Nature)> = None;
    for &(words, n) in rules {
        for w in words {
            if let Some(p) = position(folded, w)
                && best.is_none_or(|(b, _)| p < b)
            {
                best = Some((p, n));
            }
        }
    }
    best.map(|(_, n)| n)
}

fn phrase(folded: &str) -> Option<Nature> {
    PHRASES.iter().find(|(p, _)| has(folded, p)).map(|&(_, n)| n)
}

pub fn from_name(name: &str) -> Option<Nature> {
    let f = fold(name);
    phrase(&f).or_else(|| earliest(RULES, &f))
}

/// Lue comme une étiquette de catégorie, pas comme un nom. `None` si elle
/// n'est pas reconnue : elle ne masque alors pas le nom.
pub fn from_category(cat: &str) -> Option<Nature> {
    let f = fold(cat);
    phrase(&f).or_else(|| earliest(CATEGORY_TAGS, &f))
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
        Condiment | Epice | Boisson | Base | Entretien | Hygiene => Attrs { a_cuire: false, froid_ok: false, transportable: false },
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
        assert_eq!(from_name("Sauce soja sucrée"), Some(Nature::Condiment));
        assert_eq!(from_name("Objet mystère"), None);
    }

    #[test]
    fn cleaning_products_get_entretien() {
        for n in ["Liquide vaisselle citron", "Lessive liquide", "Éponges x3", "Eau de Javel 2L", "Sac poubelle 30L",
            "Papier toilette", "Essuie-tout", "Nettoyant multi-surfaces", "Sel lave-vaisselle"] {
            assert_eq!(from_name(n), Some(Nature::Entretien), "{n}");
        }
        for n in ["Shampooing doux", "Gel douche", "Dentifrice menthe", "Savon de Marseille", "Déodorant"] {
            assert_eq!(from_name(n), Some(Nature::Hygiene), "{n}");
        }
        assert_eq!(resolve(None, Some("cleaning products"), "Produit X"), Some(Nature::Entretien));
        assert_eq!(resolve(None, Some("shampoos"), "Produit X"), Some(Nature::Hygiene));
        assert_eq!(from_name("Selle d'agneau"), Some(Nature::Proteine));
        assert!(!Nature::Entretien.is_food() && !Nature::Hygiene.is_food() && Nature::Plat.is_food());
    }

    #[test]
    fn main_food_beats_its_seasoning() {
        for (name, n) in [
            ("Beurre demi-sel", Nature::Laitier),
            ("Yaourt vanille", Nature::Laitier),
            ("Fromage frais ail et fines herbes", Nature::Laitier),
            ("Saucisson au poivre", Nature::Proteine),
            ("Poulet curry", Nature::Proteine),
            ("Selle d'agneau", Nature::Proteine),
            ("Thon à l'huile d'olive", Nature::Proteine),
            ("Sardines à l'huile", Nature::Proteine),
            ("Raviolis sauce tomate", Nature::Feculent),
            ("Pêches au sirop", Nature::Fruit),
            ("Coq au vin", Nature::Proteine),
            ("Jambon au miel", Nature::Proteine),
            ("Pain aux céréales", Nature::Feculent),
            ("Noix de Saint-Jacques", Nature::Proteine),
            ("Yaourts nature sucrés", Nature::Laitier),
            ("Jus d'orange sans sucres ajoutés", Nature::Boisson),
            ("Eau plate", Nature::Boisson),
            ("Sauce chili douce", Nature::Condiment),
            ("Emmental râpé spécial gratin", Nature::Laitier),
            ("Feuilles de lasagne", Nature::Feculent),
            ("Plat de côtes", Nature::Proteine),
            ("Vinaigre balsamique", Nature::Condiment),
            ("Mélange fruits secs", Nature::Base),
        ] {
            assert_eq!(from_name(name), Some(n), "{name}");
        }
    }

    #[test]
    fn category_tags_are_read_as_categories() {
        assert_eq!(resolve(None, Some("coconut milks"), "Lait de coco"), Some(Nature::Base));
        assert_eq!(resolve(None, Some("orange juices"), "Jus"), Some(Nature::Boisson));
        assert_eq!(resolve(None, Some("fruit yogurts"), "Yaourt"), Some(Nature::Laitier));
        assert_eq!(resolve(None, Some("balsamic vinegars"), "X"), Some(Nature::Condiment));
        assert_eq!(resolve(None, Some("sweet chili sauces"), "X"), Some(Nature::Condiment));
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
