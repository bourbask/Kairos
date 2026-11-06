# Tentacule marchés de prédiction — plan d'implémentation

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** un tentacule MCP `marches` qui suit des questions de marchés de prédiction validées par l'utilisateur, les résume au brief du matin et alerte sur les bascules, sans qu'aucune donnée de sélection n'atteigne un LLM.

**Architecture:** crate `tentacles/marches` (Rust, socle `tentacle-common`) : client de l'API publique, base SQLite (suivis, scénarios, instantanés, propositions, alertes émises), calculs purs de variation et d'alerte, relevé périodique en tâche de fond, outils MCP. Côté tête : un drapeau `private` par tentacule (outils cachés au LLM, section de brief jamais reformulée) et des commandes directes reconnues par du code.

**Tech Stack:** Rust 2024, rmcp 3.8, axum (via `tentacle-common`), rusqlite 0.32 (bundled), reqwest 0.12, chrono, tokio.

**Spec:** `docs/superpowers/specs/2026-11-06-module-marches-prediction-design.md`

## Global Constraints

- Aucun pari, aucun compte, aucune écriture vers l'API : lecture seule (`GET`).
- Chiffres (probabilités, variations, seuils, budget) calculés dans le tentacule, jamais par un LLM.
- Texte venu de l'API = donnée non fiable : caractères de contrôle retirés, longueur bornée (`MAX_TITLE = 200` caractères), jamais interprété.
- Tentacule `private` : ses outils n'apparaissent pas dans `tool_specs()` de la tête ; sa section de brief est jointe telle quelle, hors reformulation.
- Commandes de sélection reconnues par du code dans la tête, sans appel LLM.
- Budget d'alertes : `weekly_budget` (défaut 4) sur 7 jours glissants ; au-delà, la bascule va au brief suivant ; chaque bascule figure aussi dans le brief suivant.
- Sport et crypto exclus des propositions ; volume minimal `min_volume` (défaut 100 000 $).
- Sujets, mots-clés, seuils en configuration (env / fichier gitignoré) ; le dépôt ne contient qu'un exemple neutre.
- Image `mem_limit: 64m`, comme les autres tentacules.
- Commits `--no-gpg-sign`, aucune attribution IA ; messages génériques.

## Review Focus

1. Question à plusieurs issues (ex. un candidat parmi N) : chaque issue suivie a sa propre probabilité ; suivre « 1 » d'une proposition multi-issues suit l'issue proposée, pas la première du tableau.
2. Champs de l'API sous forme de chaîne JSON (`outcomes`, `outcomePrices`) mal formés ou de longueurs différentes : la question est ignorée, pas de panique ni de probabilité inventée.
3. Question résolue ou fermée entre deux relevés : dénouement au brief suivant puis sortie du suivi ; jamais d'alerte sur une question fermée.
4. API injoignable pendant plusieurs relevés : aucune alerte fabriquée par écart avec un vieil instantané ; la section de brief signale l'ancienneté des données.
5. Commandes mal formées (« suivre », « suivre 9 » hors liste, « suivre 1,1 ») : réponse d'erreur courte et claire, rien d'enregistré.

---

### Task 1: crate, client de l'API et modèle de question

**Files:**
- Create: `tentacles/marches/Cargo.toml`, `tentacles/marches/src/lib.rs`, `tentacles/marches/src/api.rs`
- Modify: `Cargo.toml` (ajouter `"tentacles/marches"` à `members`)
- Test: `tentacles/marches/src/api.rs` (module `tests`)

**Interfaces:**
- Produces:
  - `pub struct Outcome { pub label: String, pub prob: f64 }`
  - `pub struct Market { pub id: String, pub question: String, pub outcomes: Vec<Outcome>, pub volume: f64, pub end: Option<DateTime<Utc>>, pub closed: bool, pub tags: Vec<String> }`
  - `pub fn parse_market(v: &serde_json::Value) -> Option<Market>`
  - `pub fn clean(s: &str) -> String` (contrôles retirés, `MAX_TITLE` caractères)
  - `pub struct Api { base: String, http: reqwest::Client }` avec `Api::new(base: &str)`, `async fn markets(&self, ids: &[String]) -> anyhow::Result<Vec<Market>>` (`GET /markets?id=…&id=…`), `async fn search(&self, q: &str) -> anyhow::Result<Vec<Market>>` (`GET /public-search?q=…&events_status=active&limit_per_type=20`, marchés des `events[].markets`, `tags` = `events[].tags[].slug`).

Format réel de l'API (relevé le 2026-11-06) : `outcomes` et `outcomePrices` sont des **chaînes** contenant un tableau JSON (`"[\"Yes\", \"No\"]"`, `"[\"0.375\", \"0.625\"]"`) ; `volumeNum` (nombre), `endDate` (ISO 8601), `closed` (bool), `id` (chaîne).

- [ ] **Step 1: tests de `parse_market`**

```rust
#[test] fn parses_string_encoded_arrays() {
    let v = json!({"id":"42","question":"Q ?","outcomes":"[\"Yes\",\"No\"]","outcomePrices":"[\"0.375\",\"0.625\"]",
                   "volumeNum":150000.0,"endDate":"2027-01-01T00:00:00Z","closed":false});
    let m = parse_market(&v).unwrap();
    assert_eq!(m.outcomes[0].label, "Yes"); assert!((m.outcomes[0].prob - 0.375).abs() < 1e-9);
    assert_eq!(m.volume, 150000.0); assert!(!m.closed);
}
#[test] fn rejects_mismatched_lengths() { /* outcomes 2, outcomePrices 1 → None */ }
#[test] fn rejects_out_of_range_price() { /* "1.5" → None */ }
#[test] fn clean_strips_controls_and_truncates() {
    assert_eq!(clean("a\u{0007}b\nc"), "ab c"); assert_eq!(clean(&"x".repeat(500)).chars().count(), MAX_TITLE);
}
```

- [ ] **Step 2:** `cargo test -p kairos-marches api` → FAIL (non défini).
- [ ] **Step 3:** implémenter (`serde_json::from_str::<Vec<String>>` sur les deux chaînes ; prix hors `[0,1]` ou non numérique → `None` ; saut de ligne → espace dans `clean`).
- [ ] **Step 4: test HTTP de `Api`** avec un faux serveur local (même procédé que `tentacles/alimentation/tests/http.rs`) servant `/markets` et `/public-search` ; `markets(&["42".into()])` renvoie 1 question ; réponse 500 → `Err`.
- [ ] **Step 5:** `cargo test -p kairos-marches` → PASS ; commit `feat(marches): client de lecture et modèle de question`.

### Task 2: base locale

**Files:** Create `tentacles/marches/src/store.rs` ; Test : module `tests`.

**Interfaces:**
- Consumes: `Market`, `Outcome` (Task 1).
- Produces (`pub struct Store`, `Store::open(path) -> Result<Store>`, `Store::memory() -> Store`) :
  - `fn follow(&self, market: &str, outcome: &str, title: &str) -> Result<()>` / `fn unfollow(&self, market: &str) -> Result<bool>` / `fn followed(&self) -> Result<Vec<Followed>>` où `Followed { market, outcome, title }`
  - `fn set_scenario(&self, name: &str, links: &[(String, String, i8)]) -> Result<()>` (market, outcome, sens ±1 ; remplace l'existant) / `fn scenarios(&self) -> Result<Vec<Scenario>>` où `Scenario { name, links: Vec<Link{market, outcome, sign}> }`
  - `fn snapshot(&self, market: &str, outcome: &str, at: DateTime<Utc>, prob: f64, volume: f64) -> Result<()>` / `fn prob_at(&self, market: &str, outcome: &str, at: DateTime<Utc>) -> Result<Option<f64>>` (dernier instantané ≤ `at`) / `fn last_snapshot(&self) -> Result<Option<DateTime<Utc>>>`
  - `fn set_proposals(&self, list: &[Proposal]) -> Result<()>` / `fn proposal(&self, n: u32) -> Result<Option<Proposal>>` où `Proposal { n: u32, market, outcome, title }`
  - `fn record_alert(&self, key: &str, at: DateTime<Utc>, budgeted: bool) -> Result<()>` / `fn alert_known(&self, key: &str) -> Result<bool>` / `fn budgeted_since(&self, since: DateTime<Utc>) -> Result<u32>`
  - `fn resolve(&self, market: &str, summary: &str, at) -> Result<()>` (retire du suivi et des scénarios, garde le dénouement) / `fn take_resolutions(&self) -> Result<Vec<String>>` (une seule fois)
  - `fn mark_briefed(&self, at) -> Result<()>` / `fn last_brief(&self) -> Result<Option<DateTime<Utc>>>`
  - `fn set_projection(&self, target: &str, up: &str, down: &str) -> Result<()>` (`target` = identifiant de question ou `sc:<nom>` ; textes passés par `clean`) / `fn projection(&self, target: &str) -> Result<Option<(String, String)>>`

- [ ] **Step 1: tests** : projection enregistrée puis relue, remplacée par une seconde saisie, supprimée avec la question résolue ; suivi puis retrait ; `prob_at` renvoie l'instantané le plus récent antérieur ; `set_scenario` remplace ; `budgeted_since` ne compte que les `budgeted` dans la fenêtre ; `take_resolutions` vide la liste ; instantanés de plus de 30 jours purgés par `snapshot`.
- [ ] **Step 2:** FAIL. **Step 3:** schéma SQLite (tables `followed`, `scenario_links`, `snapshots` indexée `(market, outcome, at)`, `proposals`, `alerts`, `resolutions`, `meta`). **Step 4:** PASS. **Step 5:** commit `feat(marches): base locale`.

### Task 3: calculs de variation et d'alerte (purs)

**Files:** Create `tentacles/marches/src/signals.rs`.

**Interfaces:**
- Produces:
  - `pub struct Settings { pub threshold: f64, pub scenario_threshold: f64, pub window_hours: i64, pub min_volume: f64, pub weekly_budget: u32, pub stale_hours: i64 }` avec `Default` = `{0.10, 0.06, 24, 100_000.0, 4, 6}`
  - `pub struct Move { pub market: String, pub outcome: String, pub title: String, pub from: f64, pub to: f64, pub volume: f64 }` et `fn delta(&self) -> f64`
  - `pub struct AlertOut { pub id: String, pub text: String, pub counts_budget: bool }`
  - `pub fn single_alerts(moves: &[Move], s: &Settings) -> Vec<(String, Move)>` : `|delta| >= threshold` et `volume >= min_volume` ; clé `"{market}:{outcome}:{+|-}:{bucket}"` où `bucket = (to*10).round()` (un même mouvement ne réalerte pas)
  - `pub fn scenario_alerts(sc: &Scenario, moves: &[Move], s: &Settings) -> Option<(String, Vec<Move>)>` : au moins 2 maillons dont `sign * delta >= scenario_threshold`, tous du même signe après application du sens ; clé `"sc:{name}:{+|-}:{date ISO}"`
  - `pub fn pct(p: f64) -> String` (« 37 % ») et `pub fn pts(d: f64) -> String` (« +12 pts »)

- [ ] **Step 1: tests**

```rust
#[test] fn single_alert_needs_threshold_and_volume() { /* 0.30→0.42 vol 2e5 → 1 ; 0.30→0.38 → 0 ; 0.30→0.45 vol 5e4 → 0 */ }
#[test] fn same_move_same_key() { /* 0.30→0.42 puis 0.30→0.43 → même clé */ }
#[test] fn scenario_respects_sign() {
    // escalade (+1) 0.10→0.18 et cessez-le-feu (−1) 0.50→0.40 : même sens → alerte
    // escalade +0.08 et cessez-le-feu +0.10 : sens opposés → aucune
}
#[test] fn scenario_needs_two_links() { /* un seul maillon au-dessus du seuil → None */ }
#[test] fn formats() { assert_eq!(pct(0.375), "38 %"); assert_eq!(pts(0.12), "+12 pts"); assert_eq!(pts(-0.05), "−5 pts"); }
```

- [ ] **Step 2:** FAIL. **Step 3:** implémenter. **Step 4:** PASS. **Step 5:** commit `feat(marches): calculs de bascule`.

### Task 4: relevé périodique, propositions et dénouements

**Files:** Create `tentacles/marches/src/poll.rs`.

**Interfaces:**
- Consumes: `Api` (T1), `Store` (T2), `Settings` (T3).
- Produces:
  - `pub struct Topics { pub keywords: Vec<String>, pub excluded_tags: Vec<String> }` (défaut `excluded_tags = ["sports", "crypto"]`)
  - `pub async fn poll_once(api: &Api, store: &Store, now: DateTime<Utc>) -> anyhow::Result<()>` : `markets()` sur les identifiants suivis ; instantané par issue suivie ; question `closed` ou échue → `store.resolve(market, "<titre> : <issue gagnante ou dernière probabilité>")`
  - `pub async fn propose(api: &Api, store: &Store, topics: &Topics, s: &Settings) -> anyhow::Result<Vec<Proposal>>` : `search()` par mot-clé, exclusion des tags exclus, des fermées, des suivies et des `volume < min_volume` ; dédoublonnage ; tri par volume ; 5 au plus, numérotées à partir de 1 ; enregistrées par `set_proposals`. Pour une question à 2 issues « Yes/No », l'issue proposée est « Yes » ; sinon l'issue la plus probable.
  - `pub fn spawn_poller(api: Arc<Api>, store: Arc<Store>, every: Duration)` (boucle `tokio::time::interval`, erreurs journalisées, jamais de panique)

- [ ] **Step 1: tests** (faux serveur de T1) : `poll_once` écrit un instantané ; une question `closed` part dans `take_resolutions` et sort de `followed()` ; `propose` exclut une question taguée `sports` et une sous `min_volume`, renvoie au plus 5 numérotées 1..n ; API en erreur → `Err`, aucun instantané écrit.
- [ ] **Step 2–4:** FAIL → implémenter → PASS. **Step 5:** commit `feat(marches): relevé et propositions`.

### Task 5: outils MCP et binaire

**Files:** Create `tentacles/marches/src/tools.rs`, `tentacles/marches/src/main.rs` ; Test : `tentacles/marches/tests/http.rs`.

**Interfaces:**
- Consumes: T1–T4.
- Produces (outils MCP de `MarketServer`) :
  - `brief_section { period }` : `soir` → chaîne vide. `matin` → dans l'ordre : avertissement si `last_snapshot` plus vieux que `stale_hours` (« ⚠ Données de marché du JJ/MM HH:MM ») ; scénarios (nom, puis maillons « titre — 38 % (+12 pts / 24 h, −3 pts / 7 j) ») ; questions suivies hors scénario, même format ; « Bascules depuis le dernier brief » (alertes enregistrées depuis `last_brief`) ; sous chaque élément qui a bougé d'au moins `scenario_threshold`, la projection du sens observé (« → si ça monte : … ») ; dénouements (`take_resolutions`) ; le lundi, propositions (`propose`) numérotées avec la ligne « Répondre : suivre 1, 3 ». Puis `mark_briefed`.
  - `alerts {}` : JSON `[{id, text, counts_budget}]`, `text` suivi de la projection du sens observé si elle existe ; candidats = `single_alerts` + `scenario_alerts` calculés entre `prob_at(now − window)` et la dernière valeur ; clés déjà connues ignorées ; tant que `budgeted_since(now − 7 j) < weekly_budget`, l'alerte est émise avec `counts_budget: true` et enregistrée `budgeted` ; au-delà, enregistrée non budgetée et **non émise** (elle ressort au brief). Aucun calcul si les données sont plus vieilles que `stale_hours`.
  - `follow { numbers: Vec<u32> }` → suit les propositions ; numéro inconnu ou doublon → erreur, rien d'enregistré.
  - `projection { number: u32, up: String, down: String }` (numéro de `followed_list`, ou nom de scénario via `scenario_name: Option<String>`) ;
  - `unfollow { market: String }`, `scenario { name: String, links: String }` (format « 1+, 4- » sur les numéros de `followed_list`), `followed_list {}` (lignes numérotées).
- `main.rs` : `TentacleEnv::from_env("MARCHES")`, `MARCHES_DB` (défaut `/data/marches.db`), `MARCHES_API_URL` (obligatoire, aucun défaut), `MARCHES_KEYWORDS` (liste séparée par des virgules), `MARCHES_POLL_MINUTES` (défaut 30) ; lance `spawn_poller` puis `tentacle_common::serve`.

- [ ] **Step 1: tests HTTP** (serveur MCP réel + faux serveur API) : projection posée sur une question qui monte de 15 pts → l'alerte et le brief contiennent le texte « hausse », pas le texte « baisse » ; `follow {numbers:[1]}` après une proposition → `followed_list` la contient ; `follow {numbers:[9]}` → erreur, rien enregistré ; deux relevés à 0.30 puis 0.45 → `alerts` émet 1 alerte `counts_budget: true`, un second appel n'émet rien ; cinq bascules dans la semaine → 4 émises, la 5ᵉ apparaît dans `brief_section matin` ; `brief_section soir` vide ; données vieilles de 7 h → `alerts` vide et brief préfixé « ⚠ ».
- [ ] **Step 2–4:** FAIL → implémenter → PASS (`cargo test -p kairos-marches`).
- [ ] **Step 5:** commit `feat(marches): outils MCP et service`.

### Task 6: tête — tentacule privé et commandes directes

**Files:**
- Modify: `tete/src/config.rs` (`TentacleEntry` : `#[serde(default)] pub private: bool`), `tete/src/tentacles.rs` (`tool_specs` exclut les tentacules `private` ; `pub fn private_names(&self) -> Vec<String>`), `tete/src/jobs.rs` (`send_brief` : sections des tentacules privés exclues de `reword` et ajoutées telles quelles après le texte reformulé), `tete/src/main.rs` (avant `agent.reply`).
- Create: `tete/src/commands.rs`.
- Test: `tete/tests/commands.rs`, cas ajoutés à `tete/tests/jobs.rs` et `tete/tests/tentacles.rs`.

**Interfaces:**
- Produces:
  - `pub enum Command { Follow(Vec<u32>), Unfollow(String), Scenario { name: String, links: String }, Projection { target: String, up: String, down: String }, List }`
  - `pub fn parse(msg: &str) -> Option<Command>` : insensible à la casse et aux espaces ; `suivre 1, 3` → `Follow([1,3])` ; `ne plus suivre <id>` → `Unfollow` ; `scénario <nom> : 1+, 4-` (accepte « scenario ») → `Scenario` ; `projection <n|nom> : hausse = … ; baisse = …` → `Projection` (les deux parties obligatoires) ; `marchés` / `marches` → `List` ; tout le reste → `None`.
  - `pub async fn run(cmd: Command, tentacles: &Tentacles, prefix: &str) -> String` : appelle `{prefix}__follow` / `__unfollow` / `__scenario` / `__followed_list` et renvoie le texte du tentacule (ou l'erreur) sans LLM.
  - `main.rs` : si `parse(&msg)` est `Some` et qu'un tentacule privé nommé dans `cfg.commands_tentacle` (défaut `"marches"`) existe, `run(...)` remplace `agent.reply` ; sinon chemin normal.

- [ ] **Step 1: tests** : `parse("projection 2 : hausse = faire le plein ; baisse = attendre")` → `Projection` ; sans « baisse » → `None` ; `parse("Suivre 1,3")` → `Follow([1,3])` ; `parse("je vais suivre ça")` → `None` ; `parse("suivre")` → `None` ; `tool_specs()` ne contient aucun outil d'un tentacule `private` ; un brief avec un moteur `brief` simulé ne transmet jamais la section privée au moteur (le faux moteur enregistre ses entrées) et l'envoie verbatim.
- [ ] **Step 2–4:** FAIL → implémenter → PASS (`cargo test -p tete`).
- [ ] **Step 5:** commit `feat(tete): tentacules privés et commandes directes`.

### Task 7: déploiement et exemples

**Files:**
- Modify: `deploy/docker/Dockerfile` (`-p kairos-marches` au build, binaire copié), `deploy/docker/docker-compose.yml` (service `marches` calqué sur `alimentation` : volume de données, `MARCHES_BIND: 0.0.0.0:8099`, port `${TENTACLES_BIND_ADDR:-127.0.0.1}:${MARCHES_BIND_PORT:-8099}:8099`, `mem_limit: 64m`), `deploy/kairos.env.example` (`MARCHES_TOKEN=`, `MARCHES_KEYWORDS=` vides), `config/tete.example.toml` (entrée `marches` commentée avec `private = true`), `deploy/docker/README.md` (une ligne).

- [ ] **Step 1:** `docker compose -f deploy/docker/docker-compose.yml config -q` → OK.
- [ ] **Step 2:** `cargo test --workspace` → vert ; `cargo clippy --workspace` sans nouvel avertissement.
- [ ] **Step 3:** commit `feat(deploy): service marches`.

## Hors plan (opérateur, après fusion)

`MARCHES_TOKEN`, `MARCHES_API_URL` (obligatoire) et `MARCHES_KEYWORDS` dans `kairos.env` sur l'hôte ; entrée `marches` `private = true` dans `config/tete.toml` ; premier brief du lundi pour les propositions.
