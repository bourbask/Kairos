//! Durées de conservation types, pour dater un produit dont on ne connaît pas
//! la date imprimée. Tout lot reçoit ainsi une date, réelle ou estimée, et le
//! même mécanisme de priorité s'applique du yaourt aux conserves : ce qui
//! approche de sa date remonte, y compris au fond du placard.
//!
//! Les règles sont lues dans l'ordre ; la première qui correspond l'emporte.
//! Les mots sont comparés en début de mot, sans accents (« œufs » ne correspond
//! pas à « bœuf »).

use crate::units::fold;

/// Nature de la date.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Date limite de consommation : au-delà, on jette.
    Dlc,
    /// Date de durabilité minimale (« de préférence avant ») : au-delà, le
    /// produit est souvent encore bon ; on vérifie avant de jeter.
    Ddm,
}

impl Kind {
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Dlc => "dlc",
            Kind::Ddm => "ddm",
        }
    }

    pub fn parse(s: &str) -> Option<Kind> {
        match s {
            "dlc" => Some(Kind::Dlc),
            "ddm" => Some(Kind::Ddm),
            _ => None,
        }
    }
}

type Rule = (&'static [&'static str], i64, Kind);

use Kind::{Ddm, Dlc};

const POTATOES: &[&str] = &["pomme de terre", "pommes de terre", "patate"];

const FRIDGE: &[Rule] = &[
    (POTATOES, 30, Ddm),
    (&["lait de coco", "creme de coco"], 5, Dlc),
    (&["poisson", "saumon", "cabillaud", "colin", "merlu", "truite", "crevette", "moule", "fruits de mer"], 2, Dlc),
    (
        &[
            "pate fraiche",
            "pates fraiches",
            "gnocchi",
            "ravioli",
            "tortellini",
            "pate brisee",
            "pate feuilletee",
            "pate a pizza",
        ],
        10,
        Dlc,
    ),
    (
        &[
            "hache", "steak", "boeuf", "veau", "porc", "poulet", "dinde", "canard", "agneau", "escalope", "saucisse",
            "merguez", "viande", "cuisse", "roti", "volaille",
        ],
        3,
        Dlc,
    ),
    (&["jambon", "lardon", "bacon", "blanc de"], 6, Dlc),
    (&["chorizo", "saucisson"], 30, Ddm),
    (
        &[
            "salade",
            "laitue",
            "mache",
            "roquette",
            "epinard",
            "pousse",
            "basilic",
            "persil",
            "coriandre",
            "ciboulette",
            "menthe",
            "champignon",
            "fraise",
            "framboise",
        ],
        5,
        Ddm,
    ),
    (
        &[
            "yaourt",
            "yogourt",
            "skyr",
            "fromage blanc",
            "petit suisse",
            "petits suisses",
            "creme dessert",
            "dessert lacte",
            "compote",
        ],
        21,
        Dlc,
    ),
    (&["mozzarella", "burrata", "ricotta", "mascarpone", "feta"], 10, Dlc),
    (&["creme"], 14, Dlc),
    (&["beurre", "margarine"], 45, Ddm),
    (&["oeuf"], 28, Ddm),
    (&["lait"], 7, Dlc),
    (
        &[
            "fromage",
            "emmental",
            "comte",
            "gruyere",
            "parmesan",
            "cheddar",
            "raclette",
            "camembert",
            "brie",
            "chevre",
            "reblochon",
        ],
        21,
        Dlc,
    ),
    (&["jus", "smoothie", "soupe", "veloute", "gaspacho"], 7, Dlc),
    (&["tofu", "houmous", "tzatziki", "guacamole", "quiche", "pizza", "sandwich", "taboule", "traiteur"], 5, Dlc),
    (
        &[
            "carotte",
            "poireau",
            "chou",
            "celeri",
            "navet",
            "betterave",
            "radis",
            "brocoli",
            "courgette",
            "aubergine",
            "poivron",
            "concombre",
            "tomate",
            "haricot vert",
            "haricots verts",
            "endive",
            "fenouil",
            "panais",
        ],
        10,
        Ddm,
    ),
    (&["pomme", "poire", "orange", "clementine", "mandarine", "citron", "kiwi", "raisin", "prune"], 14, Ddm),
];

const SHELF: &[Rule] = &[
    (POTATOES, 30, Ddm),
    (&["oignon", "echalote", "ail"], 30, Ddm),
    (&["potimarron", "courge", "butternut", "citrouille", "potiron"], 60, Ddm),
    (&["pain de mie", "brioche"], 10, Ddm),
    (&["pain", "baguette", "croissant", "viennoiserie"], 3, Ddm),
    (&["banane", "avocat", "peche", "nectarine", "abricot", "mangue"], 5, Ddm),
    (&["tomate", "courgette", "aubergine", "poivron", "concombre"], 6, Ddm),
    (&["pomme", "poire", "orange", "clementine", "mandarine", "citron", "kiwi", "raisin"], 10, Ddm),
    (&["lait de coco", "creme de coco"], 365, Ddm),
    (&["lait", "creme"], 90, Ddm),
    (&["jus"], 180, Ddm),
    (&["oeuf"], 28, Ddm),
];

/// Après ouverture, une fois au frais : jours restants au plus.
const OPENED: &[(&[&str], i64)] = &[
    (&["lait de coco", "creme de coco"], 3),
    (&["lait", "creme", "soupe", "veloute"], 3),
    (&["jus"], 4),
    (&["jambon", "lardon", "bacon"], 3),
    (&["sauce", "coulis", "pulpe", "concentre", "pesto", "passata"], 5),
    (&["mozzarella", "ricotta", "mascarpone", "fromage rape"], 4),
];

/// Un produit décongelé se conserve au frais, au plus, ce nombre de jours.
pub const THAWED_DAYS: i64 = 2;

/// Le nom contient ce mot (ou cette suite de mots) en début de mot.
pub(crate) fn has(name: &str, word: &str) -> bool {
    let w = fold(word);
    name.match_indices(w.as_str()).any(|(i, _)| i == 0 || name[..i].ends_with([' ', '-', '\'', '(']))
}

fn first(rules: &[Rule], name: &str) -> Option<(i64, Kind)> {
    rules.iter().find(|(words, ..)| words.iter().any(|w| has(name, w))).map(|&(_, d, k)| (d, k))
}

/// Durée de conservation estimée (jours depuis l'entrée en stock) et nature de la date.
pub fn estimate(name: &str, location: &str) -> (i64, Kind) {
    let n = fold(name);
    match location {
        "congelateur" => (180, Ddm),
        "frigo" => first(FRIDGE, &n).unwrap_or((7, Dlc)),
        _ if crate::nature::is_pantry(&n) => (365, Ddm),
        "corbeille" => first(SHELF, &n).unwrap_or((7, Ddm)),
        _ => first(SHELF, &n).unwrap_or((365, Ddm)),
    }
}

/// Jours de conservation après ouverture, pour les produits concernés.
pub fn after_opening(name: &str) -> Option<i64> {
    let n = fold(name);
    OPENED.iter().find(|(words, _)| words.iter().any(|w| has(&n, w))).map(|&(_, d)| d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pantry_goods_are_not_fresh() {
        assert_eq!(estimate("Farine pain multi-céréales", "placard"), (365, Ddm));
        assert_eq!(estimate("Tomates entières pelées", "placard"), (365, Ddm));
        assert_eq!(estimate("Baguette tradition", "placard"), (3, Ddm));
    }

    #[test]
    fn estimates_by_location_and_name() {
        assert_eq!(estimate("Filets de saumon", "frigo"), (2, Dlc));
        assert_eq!(estimate("Steak haché 5%", "frigo"), (3, Dlc));
        assert_eq!(estimate("Œufs plein air", "frigo"), (28, Ddm), "œufs ne correspond pas à bœuf");
        assert_eq!(estimate("Bœuf bourguignon", "frigo"), (3, Dlc));
        assert_eq!(estimate("Laitue", "frigo"), (5, Ddm), "laitue n'est pas du lait");
        assert_eq!(estimate("Lait demi-écrémé", "frigo"), (7, Dlc));
        assert_eq!(estimate("Lait demi-écrémé UHT", "placard"), (90, Ddm));
        assert_eq!(estimate("Lait de coco", "placard"), (365, Ddm));
        assert_eq!(estimate("Pommes de terre", "placard"), (30, Ddm));
        assert_eq!(estimate("Pommes Gala", "corbeille"), (10, Ddm));
        assert_eq!(estimate("Potimarron", "placard"), (60, Ddm));
        assert_eq!(estimate("Riz basmati", "placard"), (365, Ddm));
        assert_eq!(estimate("Épinards hachés", "congelateur"), (180, Ddm));
        assert_eq!(estimate("Plat inconnu", "frigo"), (7, Dlc));
    }

    #[test]
    fn opened_products_shorten() {
        assert_eq!(after_opening("Lait de coco"), Some(3));
        assert_eq!(after_opening("Sauce tomate basilic"), Some(5));
        assert_eq!(after_opening("Riz basmati"), None);
        assert_eq!(after_opening("Carottes"), None);
    }
}
