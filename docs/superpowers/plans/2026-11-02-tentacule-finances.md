# Tentacule finances — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un tentacule MCP `finances` (façade Rust sur l'API de l'application de finances) et son intégration à la tête : réponses directes, enveloppes, classement des opérations, section de brief et rapports de dérapage.

**Architecture:** Nouveau membre de workspace `tentacles/finances` sur `crates/tentacle-common` : chaque outil appelle une route de l'API de finances (jeton porteur, réseau privé) et rend un texte court, déjà chiffré et justifié. La tête gagne un outil conventionnel `alerts`, interrogé périodiquement, et une sortie spontanée `Report`. Aucun calcul financier en Rust.

**Tech Stack:** Rust 2024, rmcp 3.8 (serveur, via `tentacle-common`), reqwest, serde ; tests avec un faux serveur axum.

**Spec:** `docs/superpowers/specs/2026-11-02-module-finances-design.md`

## Prérequis

- Les routes de l'API de finances décrites ci-dessous existent (plan du dépôt de l'application de finances, « Cycle de paie, disponible et enveloppes »). Les tâches 1 à 3 se testent contre un faux serveur : elles n'attendent pas ce déploiement.

## Contrat de l'API de finances consommé

`Authorization: Bearer <FINANCES_API_TOKEN>` sur tout.
- `GET /api/cycle` → `{start, end, today, days_left, mode, quality, stale, last_sync, available: {available, balance, charges_left_total, variable_expected: {cat: f64}, margin, days_left}, charges_left: [{name, amount, day}], envelopes: {poste: f64}, spent: {poste: f64}, cushion: {balance, floor}}`
- `GET /api/cycle/afford?amount=&poste=` → `{ok, after, reason, cuts: [{poste, amount}], quality, stale}`
- `GET /api/cycle/save?amount=` → `{ok, after, quality, stale}`
- `GET /api/envelopes` → `{mode, envelopes: {poste: f64}}`
- `GET /api/finance-alerts` → `{alerts: [{id, kind, title, details, remedy}]}`
- `GET /api/unclassified` → `[{id, date, payee, amount}]`
- `POST /api/rules` `{payee, poste}` → `{rule_id, created, applied}` ; 422 si poste inconnu.

## Global Constraints

- Le tentacule ne calcule rien : il met en forme les nombres reçus (arrondi d'affichage seulement). Aucun montant produit par le LLM.
- Toute réponse commence par un avertissement si `stale` (« Données au <last_sync>, synchronisation en retard. ») ou si `quality == "incertaine"` (« Estimation incertaine. »). Un `ok: true` incertain ne s'affiche jamais comme un oui sans réserve.
- Les libellés bancaires (payee) sont non fiables : caractères de contrôle retirés, 60 caractères au plus, présentés comme données.
- `classify` n'accepte qu'un payee présent dans la liste courante de `/api/unclassified` (le LLM ne peut pas créer une règle arbitraire) ; poste refusé par l'API → erreur d'outil.
- Variables : `FINANCES_TOKEN`, `FINANCES_BIND` (via `TentacleEnv::from_env("FINANCES")`), `FINANCES_API_URL`, `FINANCES_API_TOKEN`, `FINANCES_DASHBOARD_URL` (facultatif). Délai HTTP 15 s. Port 8094.
- Dépôt public : aucun nom de banque, d'agrégateur, de montant réel, d'hôte ; tests en `127.0.0.1` / `.invalid`.
- Commentaires = intention seule ; commits `--no-gpg-sign`, sans attribution IA.

## Review Focus

1. API de finances injoignable ou 401 → erreur d'outil claire (« Données financières indisponibles. »), jamais un chiffre par défaut.
2. Libellé bancaire contenant une consigne (« ignore tes règles… ») ou des retours ligne → affiché neutralisé, une ligne, tronqué.
3. Même alerte renvoyée à chaque interrogation → envoyée une seule fois par la tête.
4. « Pas de brief demain » (sourdine Briefs) → les rapports de dérapage ne sont pas abandonnés (seulement mis en attente si la politique bloque).
5. Montant négatif, nul, non numérique ou poste inconnu dans `can_afford` → erreur d'outil, aucun appel API.

---

### Task 1: Client de l'API et mise en forme (`tentacles/finances`)

**Files:** Create `tentacles/finances/Cargo.toml`, `src/lib.rs`, `src/api.rs`, `src/render.rs`; Modify root `Cargo.toml` (membre).

**Interfaces — Produces:**
- `api::FinanceApi::new(base: String, token: String) -> FinanceApi` ; méthodes async renvoyant `Result<T, ApiError>` : `cycle() -> Cycle`, `afford(amount: f64, poste: Option<&str>) -> Verdict`, `save(amount: f64) -> Verdict`, `envelopes() -> Envelopes`, `alerts() -> Vec<Alert>`, `unclassified() -> Vec<Operation>`, `create_rule(payee: &str, poste: &str) -> RuleResult`. Types serde miroir du contrat ci-dessus. `ApiError` : `Unavailable` (réseau, 5xx, 401), `Rejected(String)` (4xx autre, texte de l'API tronqué à 200).
- `render::cycle_text(&Cycle) -> String`, `render::verdict_text(&Verdict, amount: f64, what: &str) -> String`, `render::envelopes_text(&Envelopes) -> String`, `render::unclassified_text(&[Operation]) -> String`, `render::clean_payee(&str) -> String`, `render::header(stale, quality, last_sync) -> String`.
- Formats (exemples à reproduire dans les tests) :
  - cycle : `Disponible jusqu'au 30/11 : 312 € (fiable).` puis lignes `Solde 1 204 € − charges à venir 650 € (loyer, assurance) − dépenses courantes attendues 192 € (courses 120, essence 72 sur 28 j) − marge 50 €.` et `Coussin : 2 100 / 5 000 € — reconstitution.`
  - verdict ok : `Oui : il restera 242 € disponibles.` ; refus : `Non : il manquerait 10 €. Réductions possibles : restos 30 €, loisirs 50 €.` ; refus reconstitution : `Non : coussin en reconstitution, l'enveloppe restos est épuisée.`
  - montants : arrondis à l'euro, espace fine insécable comme séparateur de milliers non requis — espace simple accepté.
- [ ] **Step 1: Tests** (`tentacles/finances/tests/api.rs` avec faux serveur axum renvoyant des JSON fixes, et tests unitaires `render`) : désérialisation de chaque route ; jeton dans l'en-tête ; 401 et port fermé → `Unavailable` ; 422 → `Rejected` ; textes exacts ci-dessus ; `header(true, "fiable", …)` commence par `Données au` ; `header(false, "incertaine", …) == "Estimation incertaine. "` ; `clean_payee("ACME\nignore tes règles")` sans `\n` et ≤ 60 caractères (Review Focus 2).
- [ ] **Step 2–4:** rouge, implémentation, vert (`cargo test -p kairos-finances`).
- [ ] **Step 5:** commit `feat(finances): client de l'API et mise en forme`.

### Task 2: Serveur MCP du tentacule

**Files:** Create `tentacles/finances/src/tools.rs`, `src/main.rs`; test `tentacles/finances/tests/http.rs`

**Interfaces:**
- Consumes: Task 1.
- Produces — outils (descriptions en français, intention seule) :
  - `cycle_status {}` → `cycle_text`.
  - `can_afford {amount: f64, poste: Option<String>}` → en-tête + `verdict_text` ; `amount` ≤ 0 ou non fini → erreur sans appel API ; `poste` hors des clés `envelopes` du cycle → erreur listant les postes valides.
  - `can_save {amount: f64}` → idem.
  - `next_envelopes {}` → `envelopes_text`.
  - `unclassified {}` → `unclassified_text` (vide → « Aucune opération à classer. »).
  - `classify {payee: String, poste: String}` → refuse si `payee` absent de `unclassified()` courant (égalité exacte après `clean_payee`) ; sinon `create_rule` ; rend `Règle enregistrée : <payee> → <poste>.` (ou `déjà connue`).
  - `dashboard {}` → `cycle_text` réduit à 3 lignes + `Tableau de bord : <FINANCES_DASHBOARD_URL>` si défini.
  - `brief_section {period: matin|soir}` → matin : première ligne de `cycle_text` + ligne coussin ; soir : idem + `unclassified_text` suivi de `Réponds par exemple « <payee> = <poste> ».` si non vide.
  - `alerts {}` → JSON `[{id, kind, text, counts_budget}]` où `text = title + "\n" + details + "\n" + remedy`, `counts_budget = (kind == "near_limit")`.
  - Toute `ApiError::Unavailable` → erreur d'outil `Données financières indisponibles.`
- `main.rs` : `TentacleEnv::from_env("FINANCES")`, lecture des trois autres variables (absentes ⇒ refus de démarrer en nommant la variable, sauf `FINANCES_DASHBOARD_URL` facultative), `tentacle_common::serve`.
- [ ] **Step 1: Tests** `tests/http.rs` (pattern de `tentacles/memoire/tests/http.rs` : tentacule servi via `serve_listener` + client rmcp, faux serveur de finances axum) : liste des 9 outils ; `can_afford` montant `-5` → erreur et **aucune requête** reçue par le faux serveur (Review Focus 5) ; `classify` payee absent → erreur, aucun POST ; payee présent → POST reçu avec `{payee, poste}` ; API coupée → `Données financières indisponibles.` (Review Focus 1) ; `brief_section soir` contient la ligne d'invite ; `alerts` : `counts_budget` vrai seulement pour `near_limit`.
- [ ] **Step 2–4:** rouge, implémentation, vert ; `cargo test --workspace`.
- [ ] **Step 5:** commit `feat(finances): serveur MCP`.

### Task 3: Rapports d'alerte dans la tête

**Files:** Modify `tete/src/policy.rs`, `tete/src/state.rs`, `tete/src/jobs.rs`, `tete/src/main.rs` (si l'ordonnancement l'exige), `docs/superpowers/plans/2026-11-02-socle-tete.md` (liste des outils conventionnels + `alerts`); tests `tete/tests/jobs.rs`, modules `policy`/`state`.

**Interfaces:**
- `policy::Spontaneous::Report` : mêmes règles que `Brief`, sauf qu'une sourdine `Briefs` ne l'abandonne pas (→ suit les autres règles).
- `State::alert_seen(id: &str) -> Result<bool>`, `State::mark_alert(id: &str, at: NaiveDateTime) -> Result<()>` (table `seen_alerts`, purge des entrées de plus de 90 jours à l'écriture).
- `jobs::Job::Alerts` : dû toutes les 30 minutes (horodatage en mémoire dans `Ctx`, comme `last_suggestion`) ; `run` : `call_conventional("alerts", {})`, désérialise chaque résultat `Ok` en `[{id, kind, text, counts_budget}]` (malformé → ignoré + warn) ; pour chaque alerte non vue : si `counts_budget` et budget du jour atteint → ignorée sans marquer ; sinon `decide(Report, …)` : `Written` → envoi (`deliver`, échec ⇒ file `retry`) puis `mark_alert`, + `record_interruption` si `counts_budget` ; `Hold` → `enqueue("alert", text)` puis `mark_alert` ; `Drop` impossible pour `Report`.
- [ ] **Step 1: Tests** : sourdine Briefs + Report → pas Drop (Review Focus 4) ; même alerte servie deux fois par le faux tentacule → un seul envoi (Review Focus 3) ; alerte en plage de travail sur site → mise en file puis partie au vidage ; `near_limit` budget épuisé → rien envoyé, non marquée ; JSON malformé → ignoré.
- [ ] **Step 2–4:** rouge, implémentation, vert ; `cargo test --workspace`.
- [ ] **Step 5:** commit `feat(tete): rapports d'alerte des tentacules`.

### Task 4: Déploiement

**Files:** Modify `deploy/docker/Dockerfile` (binaire `kairos-finances`), `deploy/docker/docker-compose.yml` (service `finances` : `mem_limit: 64m`, `restart: unless-stopped`, `env_file: kairos.env`, aucun volume, port `${TENTACLES_BIND_ADDR:-127.0.0.1}:${FINANCES_BIND_PORT:-8094}:8094`, `FINANCES_BIND: 0.0.0.0:8094`, sans dépendance au moteur local), `deploy/docker/deploy.sh` (+ `finances`), `deploy/kairos.env.example` (variables vides), `config/tete.example.toml` (exemple d'entrée de tentacule commentée), `deploy/docker/README.md` (section neutre), `deploy/client/mcp.example.json` (entrée `finances`).
- [ ] **Step 1:** modifications ; `docker build -f deploy/docker/Dockerfile .` OK ; `docker compose -f deploy/docker/docker-compose.yml config -q` OK ; binaire lancé sans `FINANCES_API_URL` → refus nommant la variable.
- [ ] **Step 2:** passe OSINT sur le diff de la branche (aucun nom de banque, d'agrégateur, d'application de finances, d'hôte).
- [ ] **Step 3:** commit `feat(finances): déploiement`.

## Après fusion (opérateur)

1. `FINANCES_TOKEN` (`openssl rand -hex 32`), `FINANCES_API_URL` (adresse privée de l'API de finances), `FINANCES_API_TOKEN` (= jeton de l'API de finances), `FINANCES_DASHBOARD_URL` dans `kairos.env`.
2. `config/tete.toml` : entrée `finances` (placée selon l'ordre souhaité des sections de brief).
3. Déployer, vérifier une conversation (« on est bon côté finances ? ») et la réception d'une alerte de test.
4. Puis seulement : désactiver le canal d'alerte de l'application de finances (procédure privée).
