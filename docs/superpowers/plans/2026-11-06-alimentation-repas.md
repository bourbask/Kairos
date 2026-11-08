# Alimentation — qualification des produits et propositions de repas — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Le module alimentation qualifie chaque produit (nature, attributs) et propose, pour un repas et un contexte, des ingrédients pertinents triés par urgence ; la tête indique au moteur le rythme du jour.

**Architecture:** Un module pur `nature.rs` (règles par mots-clés, attributs, règles de repas) consommé par `store.rs` (nature calculée à la lecture, correction manuelle stockée) et `tools.rs` (nouveaux outils `cook_options`, `product_set`, `use_first` scindé). Côté tête, une ligne de consigne système dérivée de `WorkRhythm` + `State::work_mode`.

**Tech Stack:** Rust 2024, rusqlite, rmcp (macros `#[tool]`), tokio, chrono.

**Spec:** `docs/superpowers/specs/2026-11-06-alimentation-repas-design.md`

## Global Constraints

- Repo public : aucun nom de service, hôte, fournisseur ni donnée personnelle dans code, commentaires, tests, commits (`CLAUDE.md` racine).
- Commits `git commit --no-gpg-sign`, sans mention d'IA ni `Co-Authored-By`.
- Natures, valeurs exactes : `proteine`, `legume`, `fruit`, `feculent`, `laitier`, `sucre`, `condiment`, `epice`, `boisson`, `base`, `plat`.
- Créneaux : `petit-dejeuner`, `midi`, `gouter`, `soir` (valeurs existantes de `meal_log`, normalisées par la même fonction). Contextes : `maison` (défaut), `boulot`, `rando`.
- Nature = manuel > catégorie OFF reconnue > règles du nom > inconnue. Seul le manuel est stocké.
- `cook_options` : 8 produits max par nature ; restes (`plat`) d'abord ; ⚠ pour DLC ≤ 2 jours ; ligne « Dans le placard aussi » (condiments, épices : noms seuls).
- `use_first` : « Vieux, à vérifier » plafonné à 5, les plus anciens d'abord.
- Format attendu du moteur (dans la description de `cook_options`) : 2 propositions max, 6 lignes max, jamais l'inventaire.
- Rythme : `OnSite` → « sur site », `Remote` → « télétravail », `Off`/absent → « repos ».
- Commentaires = intention seule, en français, comme le code existant.

## Review Focus

1. Produit au nom ambigu (« lait de coco », « pain au chocolat », « sauce tomate », « jus d'orange », « farine pain ») classé selon le mot le plus général → mauvais repas. Test : `nature_rules_prefer_specific_words` (Task 1).
2. Lot sans `expiry_kind` (anciens lots) : doit être traité comme une DLC (prudent), jamais ignoré. Test : `legacy_lot_without_kind_counts_as_urgent` (Task 3).
3. `day` invalide (« demain » passé tel quel) : erreur lisible demandant AAAA-MM-JJ, pas de panique. Test dans `cook_options_rejects_bad_day` (Task 3).
4. Stock vide ou aucun candidat : message court (« Rien d'utilisable pour … »), pas de sections vides. Test dans `cook_options_empty_stock` (Task 3).
5. Correction manuelle sur un produit inconnu (`product_set` avec un nom absent) : erreur claire, rien créé. Test : `product_set_unknown_product` (Task 2).

## Précision de la spec (ruling du planificateur)

La spec dit « une DDM dans l'horizon n'est pas urgente ». Or `shelf.rs` classe en DDM *estimée* des produits frais (champignons, légumes, pain, fruits). Règle retenue : une DDM **estimée** se comporte comme une DLC (périssable) ; seule une DDM **imprimée** (non estimée) n'est jamais urgente. Coût si faux : des produits frais réapparaissent ou disparaissent de l'urgence, corrigible par un ajustement de cette seule fonction (`perishable`, Task 3).

---

### Task 1: Module `nature.rs` + correctif des dates

**Files:**
- Create: `tentacles/alimentation/src/nature.rs`
- Modify: `tentacles/alimentation/src/lib.rs` (déclarer `pub mod nature;`)
- Modify: `tentacles/alimentation/src/shelf.rs` (`estimate`, rendre `has` `pub(crate)`)

**Interfaces:**
- Produces:
  - `pub enum Nature { Proteine, Legume, Fruit, Feculent, Laitier, Sucre, Condiment, Epice, Boisson, Base, Plat }` avec `as_str(self) -> &'static str`, `parse(&str) -> Option<Nature>`, `label(self) -> &'static str` (pluriel affiché : « Protéines », « Légumes »…).
  - `pub struct Attrs { pub a_cuire: bool, pub froid_ok: bool, pub transportable: bool }`
  - `pub fn from_name(name: &str) -> Option<Nature>` (règles, nom replié via `units::fold`).
  - `pub fn from_category(cat: &str) -> Option<Nature>` (mêmes règles + quelques mots anglais des tags OFF ; `None` si non reconnu).
  - `pub fn resolve(manual: Option<Nature>, category: Option<&str>, name: &str) -> Option<Nature>` : manuel > catégorie reconnue > nom.
  - `pub fn attrs(n: Nature, name: &str) -> Attrs`
  - `pub enum Slot { PetitDejeuner, Midi, Gouter, Soir }` ; `pub enum Context { Maison, Boulot, Rando }` avec `parse`.
  - `pub fn groups(slot: Slot, ctx: Context) -> &'static [Nature]` : natures candidates **dans l'ordre d'affichage**.
  - `pub fn fits(slot: Slot, ctx: Context, n: Nature, a: Attrs) -> bool`
  - `pub fn is_pantry(folded_name: &str) -> bool` : farine, conserve, pelée(s), concentré, boîte, sec/sèche(s), UHT.

Tables (spec §2, ordre d'affichage) :
- petit-déjeuner : `Laitier, Sucre, Fruit, Feculent` ; contrainte `!a_cuire`.
- goûter : `Fruit, Sucre, Laitier` ; `!a_cuire` ; rando : en plus `transportable`.
- midi maison : `Plat, Proteine, Legume, Feculent, Laitier`.
- midi boulot : mêmes groupes que maison (plats d'abord ; le reste se cuisine la veille).
- midi rando : `Plat, Proteine, Legume, Fruit, Feculent, Laitier, Sucre` ; `froid_ok && transportable`.
- soir (tous contextes) : `Plat, Legume, Proteine, Feculent, Laitier`.
- `Condiment, Epice, Boisson, Base` : jamais dans `groups`.

- [ ] **Step 1: Write the failing tests** (module `#[cfg(test)]` de `nature.rs`)

```rust
#[test]
fn nature_rules_prefer_specific_words() {
    assert_eq!(from_name("Farine pain multi-céréales"), Some(Nature::Base));
    assert_eq!(from_name("Tomates entières pelées"), Some(Nature::Legume));
    assert_eq!(from_name("Pains au chocolat x4"), Some(Nature::Sucre));
    assert_eq!(from_name("Produit exemple G (viande)"), Some(Nature::Proteine));
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
```

Et dans `shelf.rs` (module de tests existant) :

```rust
#[test]
fn pantry_goods_are_not_fresh() {
    assert_eq!(estimate("Farine pain multi-céréales", "placard"), (365, Ddm));
    assert_eq!(estimate("Tomates entières pelées", "placard"), (365, Ddm));
    assert_eq!(estimate("Baguette tradition", "placard"), (3, Ddm));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test -p kairos-alimentation --lib nature shelf`
Expected: FAIL (module `nature` absent ; `pantry_goods_are_not_fresh` : `(3, Ddm)` reçu).

- [ ] **Step 3: Implement**

`nature.rs` : règles `&[(&[&str], Nature)]`, première qui correspond (même mécanique que `shelf::has` : mot en début de mot sur le nom replié). Mots spécifiques avant généraux (« lait de coco » avant « lait », « pain au chocolat » avant « pain », « jus » avant les fruits, « sauce »/« concentre » avant « tomate », « farine » avant « pain », « compote » avant « pomme »). Listes de mots-clés laissées au constructeur, à partir des règles de `shelf.rs` et des cas des tests. `attrs` : valeurs par nature, ajustées par listes de mots (crudités et charcuteries non `a_cuire`, fromages à pâte dure et saucisson `transportable`, `plat` `froid_ok` seulement si salade/taboulé/sandwich/wrap/quiche/froid).
`shelf::estimate` : hors frigo/congélateur, `is_pantry(n)` → `(365, Ddm)` avant les règles `SHELF`.

- [ ] **Step 4: Run tests**

Run: `cargo test -p kairos-alimentation`
Expected: PASS, aucune régression de `estimates_by_location_and_name`.

- [ ] **Step 5: Commit**

```bash
git add tentacles/alimentation/src/nature.rs tentacles/alimentation/src/lib.rs tentacles/alimentation/src/shelf.rs
git commit --no-gpg-sign -m "feat(alimentation): nature des produits et règles de repas"
```

---

### Task 2: Nature en base, sur les lots, outil `product_set`

**Files:**
- Modify: `tentacles/alimentation/src/store.rs` (migration, `Lot`, `lots()`, nouvelles méthodes)
- Modify: `tentacles/alimentation/src/tools.rs` (outil `product_set`)
- Test: `tentacles/alimentation/tests/http.rs`

**Interfaces:**
- Consumes: `nature::{Nature, resolve}` (Task 1).
- Produces:
  - Migration additive : `ALTER TABLE products ADD COLUMN nature TEXT` (même garde que `expiry_kind`).
  - `Lot` gagne `pub nature: Option<Nature>` (calculé dans `lots()` par `resolve(manuel, category, name)`).
  - `Store::set_nature(&self, query: &str, n: Nature) -> Result<String, String>` (via `resolve` existant du store, nom → produit unique ; message « Nature de X : légume. »).
  - `Store::unqualified(&self) -> rusqlite::Result<Vec<String>>` : produits **en stock** de nature inconnue, triés par nom.
  - Outil `product_set { name?: String, nature?: String }` : les deux → correction ; aucun → liste « À qualifier : … » (ou « Tous les produits en stock ont une nature. ») ; un seul → erreur d'usage. Nature invalide → erreur listant les 11 valeurs.

- [ ] **Step 1: Write the failing tests** (`tests/http.rs`)

```rust
#[tokio::test]
async fn product_set_qualifies_and_lists_unknowns() {
    let url = start().await;
    call(&url, "stock_add", json!({"items": [
        {"name": "Objet mystère", "qty": 1, "location": "placard"},
        {"name": "Carottes", "qty": 1, "unit": "kg", "location": "frigo"}
    ]})).await;
    let (out, err) = call(&url, "product_set", json!({})).await;
    assert!(!err && out.contains("Objet mystère") && !out.contains("Carottes"), "{out}");
    let (out, err) = call(&url, "product_set", json!({"name": "objet mystère", "nature": "plat"})).await;
    assert!(!err, "{out}");
    let (out, _) = call(&url, "product_set", json!({})).await;
    assert!(!out.contains("Objet mystère"), "{out}");
    let (out, err) = call(&url, "product_set", json!({"name": "carottes", "nature": "légume-racine"})).await;
    assert!(err && out.contains("proteine"), "{out}");
}

#[tokio::test]
async fn product_set_unknown_product() {
    let url = start().await;
    let (out, err) = call(&url, "product_set", json!({"name": "licorne", "nature": "plat"})).await;
    assert!(err, "{out}");
}
```

- [ ] **Step 2: Run** `cargo test -p kairos-alimentation --test http product_set` → FAIL (outil inconnu).

- [ ] **Step 3: Implement** migration, champ, méthodes, outil. Description de l'outil : « Corriger la nature d'un produit (protéine, légume…) quand une proposition de repas le classe mal ; sans argument, liste les produits en stock à qualifier. »

- [ ] **Step 4: Run** `cargo test -p kairos-alimentation` → PASS.

- [ ] **Step 5: Commit** `git commit --no-gpg-sign -m "feat(alimentation): nature calculée par lot et correction manuelle"`

---

### Task 3: `cook_options`, `use_first` scindé, brief

**Files:**
- Modify: `tentacles/alimentation/src/tools.rs`
- Test: `tentacles/alimentation/tests/http.rs`

**Interfaces:**
- Consumes: `Lot.nature` (Task 2), `nature::{attrs, fits, groups, Slot, Context}` (Task 1).
- Produces:
  - `fn perishable(l: &Lot) -> bool` : `kind != Some(Ddm) || l.estimated` (voir « Précision de la spec »).
  - `urgent(days)` ne retient que les lots `perishable` ; nouvelle `fn stale(&self) -> Result<Vec<Lot>, String>` : DDM imprimées dépassées, plus anciennes d'abord, 5 max.
  - `use_first` : section « À consommer : » (lignes `lot_line`), puis « Vieux, à vérifier : » si non vide. Aucun des deux : message actuel.
  - `brief_section` : utilise `urgent(2)` (déjà périssables seulement).
  - Outil `cook_options { slot: String, context: Option<String>, day: Option<String> }`.

Sortie de `cook_options` (une ligne par produit, `lot_line` réutilisé avec date de référence `day`) :

```
Pour le soir (maison), le 06/11 :
Plats et restes :
- …
Légumes :
- Produit exemple H (légume) : 250 g, avant le 11/11 (5 j), date estimée
Protéines :
- ⚠ … (DLC ≤ 2 j)
Dans le placard aussi : sauce soja, curry, huile d'olive…
Préférences : <clé> : <valeur> ; …
```

Ligne d'en-tête pour midi + boulot : « Pour midi (boulot, à préparer la veille au soir), le JJ/MM : ». Lots dont la date (périssable) précède `day` : exclus. Tri dans chaque groupe : périssables par date croissante, puis DDM imprimées, puis sans date. Groupe vide : omis. Aucun groupe : « Rien d'utilisable dans le stock pour <créneau> (<contexte>). »

Description de l'outil (exacte, à reprendre) :

« Ingrédients du stock utilisables pour un repas, triés par urgence, restes d'abord. À appeler pour toute question de repas (« on mange quoi », menu, idée de plat). slot : petit-dejeuner, midi, gouter ou soir. context : maison (défaut), boulot (midi au travail : plat à préparer la veille), rando (à emporter, mangeable froid) ; le déduire du message, sinon du rythme du jour donné en consigne (sur site → boulot pour midi). day : AAAA-MM-JJ, aujourd'hui par défaut. Répondre ensuite par 2 propositions au plus : nom du plat, ingrédients du stock utilisés (⚠ sur les urgents), temps approximatif, ce qui manque ; 6 lignes maximum, jamais la liste de l'inventaire. Pour boulot : un plat à préparer ce soir. Pour rando : une liste à emporter. »

- [ ] **Step 1: Write the failing tests** (`tests/http.rs`), avec un inventaire construit par `stock_add` à des dates relatives (`today + n` jours, format AAAA-MM-JJ, calculées dans le test en heure de Paris) :

| Produit | qty / unit | location | expiry |
|---|---|---|---|
| Produit exemple G (viande) | 1.25 kg | frigo | J+7 |
| Produit exemple H (légume) | 250 g | frigo | (estimée) |
| Produit exemple I (légume) | 1.5 kg | frigo | (estimée) |
| Produit exemple J (légume) | 1 kg | frigo | (estimée) |
| Produit exemple K (viennoiserie) | 2 | placard | J+3 |
| Produit exemple L (condiment) | 1 | placard | J+4 |
| Produit exemple A | 350 ml | placard | J−700 |
| Produit exemple B | 1 | placard | J−800 |
| Produit exemple C | 400 ml | placard | J−300 |
| Produit exemple D | 1 | placard | J−600 |
| Produit exemple E | 1 | placard | J−200 |
| Produit exemple F | 1 | placard | J−100 |
| Produit exemple M (boisson) | 1 l | frigo | J+1 |

```rust
#[tokio::test]
async fn cook_options_dinner_proposes_real_food() {
    // inventaire ci-dessus
    let (out, err) = call(&url, "cook_options", json!({"slot": "soir"})).await;
    assert!(!err, "{out}");
    assert!(out.contains("Poulet") && out.contains("Carottes"), "{out}");
    assert!(!out.contains("Pains au chocolat"), "{out}");
    let head = out.split("Dans le placard aussi").next().unwrap();
    assert!(!head.contains("Sauce soja"), "{out}");
    assert!(out.find("Légumes").unwrap() < out.find("Protéines").unwrap(), "soir : légumes d'abord");
}

#[tokio::test]
async fn cook_options_breakfast_and_hike() {
    let (out, _) = call(&url, "cook_options", json!({"slot": "petit-dejeuner"})).await;
    assert!(out.contains("Pains au chocolat") && !out.contains("Poulet"), "{out}");
    let (out, _) = call(&url, "cook_options", json!({"slot": "midi", "context": "rando"})).await;
    assert!(!out.contains("Poulet") && !out.contains("Patates"), "{out}");
    let (out, _) = call(&url, "cook_options", json!({"slot": "midi", "context": "boulot"})).await;
    assert!(out.contains("à préparer la veille"), "{out}");
}

#[tokio::test]
async fn cook_options_day_excludes_expired_by_then() {
    // lait J+1 : présent pour aujourd'hui, absent pour J+3
    let (out, _) = call(&url, "cook_options", json!({"slot": "soir", "day": j(3)})).await;
    assert!(!out.contains("Lait"), "{out}");
}

#[tokio::test]
async fn cook_options_rejects_bad_day() {
    let (out, err) = call(&url, "cook_options", json!({"slot": "soir", "day": "demain"})).await;
    assert!(err && out.contains("AAAA-MM-JJ"), "{out}");
}

#[tokio::test]
async fn cook_options_empty_stock() {
    let url = start().await;
    let (out, err) = call(&url, "cook_options", json!({"slot": "soir"})).await;
    assert!(!err && out.starts_with("Rien d'utilisable"), "{out}");
}

#[tokio::test]
async fn use_first_splits_urgent_and_stale() {
    let (out, _) = call(&url, "use_first", json!({"days": 4})).await;
    let (urgent, stale) = out.split_once("Vieux, à vérifier").unwrap();
    assert!(urgent.contains("Lait") && !urgent.contains("Sauce soja"), "{out}");
    assert!(stale.lines().filter(|l| l.starts_with("- ")).count() == 5, "{out}");
    assert!(stale.find("Bouillon").unwrap() < stale.find("Condiment").unwrap(), "plus anciens d'abord");
}

```

Fixture commune : `async fn stocked() -> String` (lance `start()`, ajoute l'inventaire ci-dessus par `stock_add`, renvoie l'URL) et `fn j(n: i64) -> String` (date du jour à Paris + n, AAAA-MM-JJ) ; chaque test ci-dessus commence par `let url = stocked().await;` sauf `cook_options_empty_stock`.

Test unitaire dans le module `tests` de `tools.rs` :

```rust
#[test]
fn legacy_lot_without_kind_counts_as_urgent() {
    // Lot construit à la main : kind None, estimated false.
    assert!(perishable(&lot(None, false)));
    assert!(perishable(&lot(Some(Kind::Ddm), true)));
    assert!(!perishable(&lot(Some(Kind::Ddm), false)));
}
```

- [ ] **Step 2: Run** `cargo test -p kairos-alimentation --test http` → FAIL (outil `cook_options` inconnu, `use_first` sans sections).

- [ ] **Step 3: Implement** selon Interfaces. Normalisation du créneau : extraire la logique de `meal_log` en `fn slot(s: &str) -> Result<Slot, String>` partagée. Contexte invalide → erreur listant `maison, boulot, rando`. `lot_line` reçoit la date de référence (`today` ou `day`) en paramètre.

- [ ] **Step 4: Run** `cargo test -p kairos-alimentation` → PASS ; `cargo clippy -p kairos-alimentation --all-targets` sans nouvel avertissement.

- [ ] **Step 5: Commit** `git commit --no-gpg-sign -m "feat(alimentation): propositions par repas et produits à finir scindés"`

---

### Task 4: Tête — rythme du jour dans la consigne

**Files:**
- Modify: `tete/src/config.rs` (`#[derive(Clone)]` sur `WorkRhythm` et `WorkMode` si absent)
- Modify: `tete/src/agent.rs` (`Agent`, `system_prompt`)
- Modify: `tete/src/main.rs` (`Agent::new(...).with_rhythm(cfg.work.clone())`)
- Test: `tete/tests/agent.rs`

**Interfaces:**
- Consumes: `State::work_mode(day: Weekday, cfg: &WorkRhythm) -> WorkMode` (existant).
- Produces: `Agent::with_rhythm(self, w: WorkRhythm) -> Agent` ; champ `rhythm: WorkRhythm` (défaut `WorkRhythm::default()` dans `new`) ; `system_prompt(persona, now, rhythm_line: &str)`.
  Ligne exacte : `Rythme : aujourd'hui {a}, demain {b}.` avec {a}/{b} ∈ « sur site », « télétravail », « repos », placée après la ligne de date.

- [ ] **Step 1: Write the failing test** (`tete/tests/agent.rs`)

```rust
#[tokio::test]
async fn system_prompt_states_work_rhythm() {
    let (t, _) = tentacle("AG_RY").await;
    let (router, shared) = llm(vec![text("ok")]).await;
    let state = State::open_in_memory().unwrap();
    let rhythm = WorkRhythm {
        days: HashMap::from([(Weekday::Mon, WorkMode::OnSite), (Weekday::Tue, WorkMode::Remote)]),
        ..Default::default()
    };
    let mut agent = Agent::new(router, IDLE).with_rhythm(rhythm);
    agent.reply("salut", at(9, 0), &t, &state).await; // 2026-11-02 = lundi
    let s = shared.lock().unwrap();
    let system = s.requests[0]["messages"][0]["content"].as_str().unwrap();
    assert!(system.contains("Rythme : aujourd'hui sur site, demain télétravail."), "{system}");
}
```

- [ ] **Step 2: Run** `cargo test -p tete --test agent system_prompt_states_work_rhythm` → FAIL (`with_rhythm` absent).
- [ ] **Step 3: Implement.** `set_work_day` modifiant la semaine est pris en compte via `State::work_mode`.
- [ ] **Step 4: Run** `cargo test -p tete` → PASS.
- [ ] **Step 5: Commit** `git commit --no-gpg-sign -m "feat(tete): rythme du jour dans la consigne de conversation"`

---

## Après les tâches

- `cargo test --workspace` vert.
- Sync `git pull origin develop` (merge), revue OSINT du diff, push, PR vers `develop`.
- Après merge : déploiement serveur (procédure habituelle) ; aucune migration manuelle (nature calculée à la lecture). Vérifier `product_set` sans argument sur le vrai stock et qualifier les inconnus avec l'utilisateur.
