# Socle — plan 1 : tentacules mémoire et agenda — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deux tentacules MCP en Rust (mémoire, agenda), servis sur le réseau privé et utilisables tout de suite depuis un client MCP tiers sur le poste, avec envoi garanti des transcriptions de session.

**Architecture:** Le repo devient un workspace Cargo. Le crate `kairos` expose une bibliothèque (le binaire batch reste inchangé). Un crate commun fournit l'amorçage HTTP + jeton porteur et la convention `brief_section`. Chaque tentacule est un binaire `rmcp` en HTTP « streamable » sans état, avec sa propre logique déterministe testée hors MCP.

**Tech Stack:** Rust 2024, `rmcp` 3.8 (`server`, `macros`, `transport-streamable-http-server`, `schemars`), `axum` (via rmcp), `rusqlite` bundled (FTS5), `tokio`, `serde`, `chrono`.

**Spec:** `docs/superpowers/specs/2026-10-29-socle-tete-tentacules-design.md`

Ce plan est le **1 sur 3** du socle. Plan 2 : extension du routeur LLM (moteurs par usage, appel d'outils, protocole natif, plafond de dépense). Plan 3 : la tête (messagerie, boucle d'agent, politique de canal, briefs, suggestions, résumé différé des transcriptions).

## Global Constraints

- Rust 2024 ; toute la suite `cargo test --workspace` verte à chaque commit.
- Toolchain : si `cargo` répond « no default is configured », lancer `rustup default stable` une fois.
- Aucun hôte, IP, nom de fournisseur, identifiant ou donnée personnelle dans un fichier versionné : tout par variables d'environnement ; fichiers `*.example` neutres.
- Commentaires en français, intention seulement (règles `CLAUDE.md`).
- Commits : `git commit --no-gpg-sign`, sans ligne d'attribution.
- Horodatages : heure murale locale naïve (`calendar::USER_TZ`), convention existante.
- Fail-closed : un tentacule sans jeton configuré refuse de démarrer.
- Mémoire vive : chaque tentacule doit tourner sous `mem_limit: 64m` en conteneur.
- Les commandes batch existantes (`scrape`, `briefing`, `calendar sync`, `plan …`) gardent leur comportement.

## Review Focus

1. Requête de recherche plein texte contenant guillemets, tirets, `*`, `AND` → aucun plantage, recherche littérale (test : Task 3 `search_with_fts_operators_is_literal`).
2. Jeton absent, vide ou faux → 401 ; jeton non configuré → refus de démarrer (Task 2).
3. Transcription énorme → le hook tronque, le serveur refuse au-delà de la limite avec une erreur claire, sans stocker de demi-transcription (Tasks 4 et 8).
4. Événement « journée entière » dans l'agenda travail → ne rend pas l'utilisateur « occupé » toute la journée (Task 5 `all_day_work_event_is_not_busy`).
5. Synchronisation de l'agenda personnel → n'efface pas les événements de l'agenda travail, et inversement (Task 5 `sync_by_source_keeps_other_collection`).

---

### Task 1: Workspace Cargo et bibliothèque `kairos`

**Files:**
- Modify: `Cargo.toml` (ajout `[workspace]`, `[lib]`)
- Create: `src/lib.rs`
- Modify: `src/main.rs:1-13` (les `mod x;` deviennent `use kairos::…`)

**Interfaces:**
- Produces: crate `kairos` exposant `pub mod {collectors, config, models, matching, ranker, storage, enrichment, llm, briefing, planning, calendar, signals, notify}` ; workspace avec `members = [".", "crates/tentacle-common", "tentacles/memoire", "tentacles/agenda"]` (les trois derniers créés par les tâches suivantes ; ajouter chaque membre dans la tâche qui le crée).

- [ ] **Step 1: Créer `src/lib.rs`** avec les `pub mod` listés ; `main.rs` importe depuis `kairos::`. Aucune autre modification de code.
- [ ] **Step 2: Vérifier**
  Run: `cargo build && cargo test`
  Expected: build OK, même nombre de tests qu'avant, tous verts. `cargo run -- --help` affiche les mêmes commandes.
- [ ] **Step 3: Commit** — `refactor: exposer kairos en bibliothèque et passer en workspace`

---

### Task 2: Crate commun des tentacules

**Files:**
- Create: `crates/tentacle-common/Cargo.toml` (package `tentacle-common`), `crates/tentacle-common/src/lib.rs`
- Modify: `Cargo.toml` (membre du workspace)
- Test: `crates/tentacle-common/src/lib.rs` (module `tests`)

**Interfaces:**
- Produces:
  - `pub enum BriefPeriod { Matin, Soir }` — serde `rename_all = "lowercase"`, `JsonSchema`.
  - `pub struct TentacleEnv { pub bind: SocketAddr, pub token: String }` et `pub fn TentacleEnv::from_env(prefix: &str) -> anyhow::Result<TentacleEnv>` — lit `{PREFIX}_BIND` (obligatoire, adresse:port) et `{PREFIX}_TOKEN` (obligatoire, non vide) ; l'un absent ou vide → `Err`.
  - `pub fn with_bearer(router: axum::Router, token: String) -> axum::Router` — couche qui renvoie 401 sans `Authorization: Bearer <token>` exact (comparaison à temps constant).
  - `pub async fn serve<S>(env: TentacleEnv, factory: impl Fn() -> S) -> anyhow::Result<()>` — monte le service rmcp en HTTP streamable **sans état, réponses JSON** sur `/mcp`, enveloppé par `with_bearer`.
  - `pub const MAX_BODY_BYTES: usize = 2 * 1024 * 1024;` — limite de corps de requête appliquée par `serve`.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn env_without_token_is_refused() { /* {P}_BIND set, {P}_TOKEN unset → from_env(P).is_err() */ }
#[test] fn env_with_empty_token_is_refused() { /* {P}_TOKEN="" → is_err() */ }
#[tokio::test] async fn missing_bearer_is_401() { /* with_bearer(Router::new().route("/", get(|| async {"ok"})), "t") ; oneshot sans header → 401 */ }
#[tokio::test] async fn wrong_bearer_is_401() { /* "Bearer x" → 401 */ }
#[tokio::test] async fn right_bearer_passes() { /* "Bearer t" → 200 "ok" */ }
#[test] fn brief_period_serde() { assert_eq!(serde_json::from_str::<BriefPeriod>("\"matin\"").unwrap(), BriefPeriod::Matin); }
```

Utiliser des préfixes de variables distincts par test (tests parallèles).

- [ ] **Step 2:** `cargo test -p tentacle-common` → FAIL (symboles absents).
- [ ] **Step 3: Implémenter** les signatures ci-dessus ; config rmcp du transport : mode sans état + réponse JSON (champs de `StreamableHttpServerConfig`, voir docs.rs rmcp 3.8).
- [ ] **Step 4:** `cargo test -p tentacle-common` → PASS.
- [ ] **Step 5: Commit** — `feat(tentacles): amorçage HTTP commun avec jeton porteur`

---

### Task 3: Stockage du tentacule mémoire

**Files:**
- Create: `tentacles/memoire/Cargo.toml` (package `kairos-memoire`), `tentacles/memoire/src/store.rs`, `tentacles/memoire/src/main.rs` (vide pour l'instant : `fn main() {}`)
- Modify: `Cargo.toml` (membre)
- Test: `tentacles/memoire/src/store.rs` (module `tests`, base `:memory:`)

**Interfaces:**
- Produces (`store.rs`) :
  - `pub struct MemoryStore` ; `pub fn open(path: &str) -> anyhow::Result<MemoryStore>` (crée le schéma).
  - Journal : `pub fn journal_write(&self, e: &NewJournalEntry) -> Result<i64>` ; `pub fn journal_search(&self, query: &str, limit: u32) -> Result<Vec<JournalEntry>>`.
    `NewJournalEntry { source: String, kind: String, content: serde_json::Value, sensitivity: Sensitivity, provenance: String }` ; `JournalEntry` = idem + `id: i64, at: NaiveDateTime`.
    `pub enum Sensitivity { Commune, Strategique }` (serde lowercase).
  - Faits : `pub fn fact_set(&self, key: &str, value: &str, provenance: &str) -> Result<()>` (conserve l'ancienne valeur dans un historique) ; `pub fn fact_get(&self, key: &str) -> Result<Option<Fact>>` ; `pub fn fact_history(&self, key: &str) -> Result<Vec<Fact>>` ; `pub fn facts_list(&self, prefix: &str) -> Result<Vec<Fact>>`. `Fact { key, value, provenance, updated_at: NaiveDateTime }`.
  - Personnalité : clé réservée `pub const PERSONA_KEY: &str = "persona";` (lue via `fact_get`).
  - Intentions : `pub fn intention_create(&self, what: &str, constraints: &str, expires_at: NaiveDateTime) -> Result<i64>` ; `pub fn intention_set_state(&self, id: i64, state: IntentionState) -> Result<bool>` ; `pub fn intentions_open(&self, now: NaiveDateTime) -> Result<Vec<Intention>>` — passe d'abord en `Expiree` toute intention ouverte ou proposée dont `expires_at <= now`, puis renvoie celles en `Ouverte | Proposee`. `pub enum IntentionState { Ouverte, Proposee, Acceptee, Refusee, Expiree }`.
  - Conversations : `pub fn conversation_ingest(&self, c: &NewConversation) -> Result<i64>` ; `pub fn conversation_search(&self, query: &str, limit: u32) -> Result<Vec<ConversationCard>>` ; `pub fn conversations_unsummarized(&self, limit: u32) -> Result<Vec<ConversationCard>>` ; `pub fn conversation_set_summary(&self, id: i64, summary: &str, topics: &[String], decisions: &[String]) -> Result<bool>`. `NewConversation { channel, device, started_at: NaiveDateTime, ended_at: NaiveDateTime, transcript: String }` ; `ConversationCard` = tout sauf la transcription + `id`, `summary: Option<String>`.
  - Leçons : `pub fn lesson_record(&self, l: &NewLesson) -> Result<i64>` ; `NewLesson { context, qualification, debrief, conclusion, actions: Vec<String> }`.
  - `pub const MAX_TRANSCRIPT_BYTES: usize = 1_500_000;` — `conversation_ingest` renvoie `Err` au-delà, sans rien écrire.
- Recherche : FTS5 sur `journal.content` (texte JSON) et sur `conversations.summary || transcript`. La requête utilisateur est **toujours** traitée comme littérale : chaque terme entouré de guillemets doubles, guillemets internes doublés.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn journal_roundtrip_and_search() { /* write kind="note", content {"texte":"appelé grand-mère"} ; search("grand-mère") → 1 résultat, même id */ }
#[test] fn search_with_fts_operators_is_literal() { /* search("\"AND* -x"), search("NEAR(") → Ok, pas d'Err */ }
#[test] fn fact_set_keeps_history() { /* set k=a puis k=b ; get=b ; history = [a, b] dans l'ordre */ }
#[test] fn intentions_expire_on_read() { /* create expires_at=T ; intentions_open(T - 1min) → 1 ; intentions_open(T) → 0 ; l'état stocké est Expiree */ }
#[test] fn refused_intention_is_not_open() { /* set_state Refusee → intentions_open vide */ }
#[test] fn conversation_ingest_search_and_summary() { /* ingest transcript "parlé de la rando" ; unsummarized → 1 ; search("rando") → 1 ; set_summary ; unsummarized → 0 */ }
#[test] fn oversized_transcript_is_rejected_without_write() { /* transcript MAX_TRANSCRIPT_BYTES+1 → Err ; unsummarized → 0 */ }
#[test] fn lesson_roundtrip() { /* record → id > 0 */ }
```

- [ ] **Step 2:** `cargo test -p kairos-memoire` → FAIL.
- [ ] **Step 3: Implémenter** `store.rs` selon les signatures. Tableaux (`actions`, `topics`, `decisions`) stockés en JSON texte.
- [ ] **Step 4:** `cargo test -p kairos-memoire` → PASS.
- [ ] **Step 5: Commit** — `feat(memoire): stockage journal, faits, intentions, conversations, leçons`

---

### Task 4: Serveur MCP du tentacule mémoire

**Files:**
- Create: `tentacles/memoire/src/tools.rs`
- Modify: `tentacles/memoire/src/main.rs`
- Test: `tentacles/memoire/tests/http.rs`

**Interfaces:**
- Consumes: `MemoryStore` (Task 3), `serve`, `TentacleEnv::from_env`, `BriefPeriod` (Task 2).
- Produces: binaire `kairos-memoire` ; variables `MEMOIRE_BIND`, `MEMOIRE_TOKEN`, `MEMOIRE_DB_PATH`. Outils MCP (noms exacts, arguments = champs des types de Task 3) :
  `journal_write`, `journal_search`, `fact_get`, `fact_set`, `facts_list`, `persona_get`, `intention_create`, `intention_set_state`, `intentions_open`, `conversation_ingest`, `conversation_search`, `conversations_unsummarized`, `conversation_set_summary`, `lesson_record`, `brief_section`.
  - `brief_section(period)` : texte court listant les intentions ouvertes qui expirent dans les 24 h ; chaîne vide s'il n'y en a pas.
  - Chaque outil renvoie du JSON compact ; une erreur de stockage devient une erreur d'outil (pas un plantage du serveur).
  - Descriptions d'outils en français, une phrase, orientées « quand l'utiliser ».

- [ ] **Step 1: Test d'intégration qui échoue** (`tests/http.rs`) : démarre le serveur sur `127.0.0.1:0` avec une base temporaire et le jeton `t`, puis en HTTP brut (`reqwest`) :

```rust
#[tokio::test] async fn tools_list_requires_token() { /* POST /mcp tools/list sans header → 401 */ }
#[tokio::test] async fn ingest_then_search_over_raw_http() {
    /* POST /mcp JSON-RPC {"method":"tools/call","params":{"name":"conversation_ingest","arguments":{...}}} avec Bearer t → résultat contenant un id ;
       puis tools/call conversation_search {"query":"rando"} → contient cet id */
}
#[tokio::test] async fn oversized_ingest_returns_tool_error() { /* transcript > MAX_TRANSCRIPT_BYTES → réponse d'erreur, serveur toujours vivant (tools/list OK après) */ }
```

Les en-têtes requis par le transport (Accept, version de protocole) sont ceux documentés pour rmcp 3.8 en mode sans état ; ce test fige la forme exacte que le hook de Task 8 réutilisera.

- [ ] **Step 2:** `cargo test -p kairos-memoire --test http` → FAIL.
- [ ] **Step 3: Implémenter** `tools.rs` (macros `#[tool_router]` / `#[tool]` / `#[tool_handler]` de rmcp, arguments typés `schemars`) et `main.rs` (`TentacleEnv::from_env("MEMOIRE")`, ouverture de `MEMOIRE_DB_PATH`, `serve`). Le `MemoryStore` est partagé derrière un `Mutex` (accès SQLite sérialisé).
- [ ] **Step 4:** `cargo test -p kairos-memoire` → PASS.
- [ ] **Step 5: Commit** — `feat(memoire): serveur MCP du tentacule mémoire`

---

### Task 5: Agenda travail et requêtes agenda dans `kairos`

**Files:**
- Modify: `src/main.rs` (branche `CalendarAction::Sync`)
- Modify: `src/storage.rs` (nouvelles requêtes)
- Modify: `src/calendar/mod.rs` (`build_event` reçoit la source)
- Modify: `.env.example`, `deploy/kairos.env.example`
- Test: `src/storage.rs`, `src/calendar/mod.rs` (modules `tests`)

**Interfaces:**
- Produces:
  - `pub const SOURCE_PERSONAL: &str = "calendar";` et `pub const SOURCE_WORK: &str = "work";` dans `calendar`.
  - `CalendarClient::sync(&self, source: &str)` — chaque événement porte `source`.
  - Sync : `CALENDAR_URL` → source `calendar` (inchangé) ; `CALENDAR_WORK_URL` optionnelle (mêmes identifiants) → source `work`. Chaque collection est effacée puis réécrite **par sa source uniquement**. `// ponytail: deux collections nommées ; liste configurable si une troisième arrive.`
  - `Storage::get_events_between(&self, from: NaiveDateTime, to: NaiveDateTime) -> SqlResult<Vec<CalendarEvent>>` (chevauchement de l'intervalle, tri par début).
  - `Storage::is_busy_at(&self, at: NaiveDateTime) -> SqlResult<BusyStatus>` ; `pub struct BusyStatus { pub busy: bool, pub work: bool }` — un événement non « journée entière » couvrant `at` rend `busy` ; `work` si l'un d'eux a la source `work`. Les événements « journée entière » sont ignorés.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn parse_ics_keeps_given_source() { /* parse avec source "work" → event.source == "work" */ }
#[test] fn sync_by_source_keeps_other_collection() { /* upsert e1 source calendar, e2 source work ; delete_calendar_events_by_source("calendar") ; e2 toujours là */ }
#[test] fn events_between_overlap() { /* 10h-11h ; between(10:30, 12:00) → 1 ; between(11:00, 12:00) → 0 */ }
#[test] fn busy_at_work_meeting() { /* work 14h-15h ; is_busy_at(14:30) == {busy:true, work:true} ; is_busy_at(15:00).busy == false */ }
#[test] fn all_day_work_event_is_not_busy() { /* work all_day ; is_busy_at(10:00).busy == false */ }
```

- [ ] **Step 2:** `cargo test` → FAIL.
- [ ] **Step 3: Implémenter** ; `parse_ics(input, source)` : mettre à jour tous les appelants existants avec `SOURCE_PERSONAL`.
- [ ] **Step 4:** `cargo test` → PASS ; `cargo run -- calendar sync` sans `CALENDAR_WORK_URL` se comporte comme avant.
- [ ] **Step 5: Commit** — `feat(calendar): agenda travail distinct et requêtes d'occupation`

---

### Task 6: Tentacule agenda

**Files:**
- Create: `tentacles/agenda/Cargo.toml` (package `kairos-agenda`), `tentacles/agenda/src/main.rs`, `tentacles/agenda/src/slots.rs`, `tentacles/agenda/src/tools.rs`
- Modify: `Cargo.toml` (membre)
- Test: `tentacles/agenda/src/slots.rs` (module `tests`)

**Interfaces:**
- Consumes: `kairos::storage::Storage`, `BusyStatus`, `get_events_between`, `get_tasks` (existant), `kairos::calendar::SOURCE_WORK` (Task 5) ; `serve`, `TentacleEnv`, `BriefPeriod` (Task 2).
- Produces: binaire `kairos-agenda` ; variables `AGENDA_BIND`, `AGENDA_TOKEN`, `DATABASE_PATH` (base `kairos.db` existante, ouverte en lecture seule). Outils : `events_between(from, to)`, `free_slots(day, min_minutes)`, `tasks_due(until)`, `busy_at(at)`, `brief_section(period)`.
  - `slots.rs` : `pub fn free_slots(events: &[CalendarEvent], day_start: NaiveDateTime, day_end: NaiveDateTime, min_minutes: i64) -> Vec<(NaiveDateTime, NaiveDateTime)>` — trous entre événements non « journée entière », fusion des chevauchements, trous plus courts que `min_minutes` écartés. Bornes de journée : `pub const DAY_START: NaiveTime = 07:00` et `DAY_END = 23:00` (`// ponytail: bornes fixes ; à passer en préférence mémoire quand la tête les lira`).
  - `brief_section(matin)` : événements du jour + tâches dues aujourd'hui ; `brief_section(soir)` : événements et tâches de demain. Une ligne par élément, heure locale.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn free_slots_between_meetings() { /* events 9-10, 14-15 sur 07-23, min 30 → [(7,9),(10,14),(15,23)] */ }
#[test] fn free_slots_merge_overlaps() { /* 9-11 et 10-12 → trou suivant commence à 12 */ }
#[test] fn free_slots_drop_short_gaps() { /* 9-10 et 10:20-11, min 30 → pas de trou 10-10:20 */ }
#[test] fn free_slots_ignore_all_day() { /* seul un all_day → [(7,23)] */ }
```

- [ ] **Step 2:** `cargo test -p kairos-agenda` → FAIL.
- [ ] **Step 3: Implémenter** `slots.rs`, puis `tools.rs` et `main.rs` sur le modèle du tentacule mémoire (Task 4).
- [ ] **Step 4:** `cargo test --workspace` → PASS.
- [ ] **Step 5: Commit** — `feat(agenda): tentacule agenda en lecture`

---

### Task 7: Déploiement des tentacules

**Files:**
- Modify: `deploy/docker/Dockerfile` (construire et copier `kairos-memoire`, `kairos-agenda`)
- Modify: `deploy/docker/docker-compose.yml` (services `memoire`, `agenda`)
- Modify: `deploy/kairos.env.example` (variables neutres, valeurs vides)
- Modify: `deploy/docker/README.md` (section « Tentacules » : génération des jetons, adresse de bind réseau privé, volume de la base mémoire)

**Interfaces:**
- Consumes: binaires et variables des Tasks 4 et 6.
- Produces: services compose `memoire` (volume dédié `memoire-data`, `MEMOIRE_DB_PATH=/app/memoire/memoire.db`) et `agenda` (base `kairos.db` montée en lecture seule), chacun `mem_limit: 64m`, `restart: unless-stopped`, ports publiés sur `${TENTACLES_BIND_ADDR:-127.0.0.1}` uniquement (même convention que `radicale`), jetons lus depuis `kairos.env`.

- [ ] **Step 1: Modifier** Dockerfile, compose, exemples, README.
- [ ] **Step 2: Vérifier**
  Run: `docker compose -f deploy/docker/docker-compose.yml config -q && docker build -f deploy/docker/Dockerfile .`
  Expected: aucune erreur ; l'image contient `/app/kairos`, `/app/kairos-memoire`, `/app/kairos-agenda`.
- [ ] **Step 3: Vérifier le refus sans jeton** : `docker run --rm --entrypoint /app/kairos-memoire kairos:local` → sortie non nulle, message indiquant `MEMOIRE_TOKEN` manquant.
- [ ] **Step 4: Commit** — `feat(deploy): services des tentacules mémoire et agenda`

---

### Task 8: Branchement d'un client MCP tiers et hook de fin de session

**Files:**
- Create: `deploy/client/mcp.example.json`
- Create: `deploy/client/session-end-hook.sh`
- Create: `deploy/client/README.md`
- Test: `deploy/client/test-session-end-hook.sh`, fixture `deploy/client/fixtures/transcript.jsonl`

**Interfaces:**
- Consumes: forme de requête HTTP figée par `tests/http.rs` (Task 4), outil `conversation_ingest`, `MAX_TRANSCRIPT_BYTES`.
- Produces:
  - `mcp.example.json` : deux serveurs HTTP `kairos-memoire`, `kairos-agenda`, URL et jeton par expansion de variables d'environnement (`${KAIROS_MEMOIRE_URL}`, `${KAIROS_MEMOIRE_TOKEN}`, idem agenda). Aucune valeur réelle.
  - `session-end-hook.sh` : lit sur stdin le JSON du hook (`transcript_path`, `session_id`), extrait avec `jq` **uniquement le texte des messages utilisateur et assistant** (pas les sorties d'outils), tronque à 1 400 000 octets en conservant la fin, puis appelle `conversation_ingest` (`channel="client-mcp"`, `device=$(hostname)`, horodatages du premier et du dernier message). Variables : `KAIROS_MEMOIRE_URL`, `KAIROS_MEMOIRE_TOKEN`. Toujours `exit 0` (ne bloque jamais la fermeture de session) ; échec journalisé dans `${XDG_STATE_HOME:-~/.local/state}/kairos/hook.log`. `KAIROS_HOOK_DRY_RUN=1` : imprime la requête au lieu de l'envoyer.
  - `README.md` : ajout du hook `SessionEnd` dans les réglages utilisateur du client, copie de `mcp.example.json`, variables à définir.

- [ ] **Step 1: Test qui échoue** (`test-session-end-hook.sh`, bash + jq) :
  - fixture avec un message utilisateur, un message assistant, une sortie d'outil contenant `SECRET_TOOL_OUTPUT` ;
  - `KAIROS_HOOK_DRY_RUN=1` → la requête imprimée contient le texte utilisateur et assistant, **ne contient pas** `SECRET_TOOL_OUTPUT`, contient `"name":"conversation_ingest"` ;
  - fixture gonflée à 3 Mo → la transcription imprimée fait ≤ 1 400 000 octets ;
  - `KAIROS_MEMOIRE_URL` pointant vers un port fermé → code de sortie 0 et une ligne ajoutée au journal.
- [ ] **Step 2:** `bash deploy/client/test-session-end-hook.sh` → FAIL.
- [ ] **Step 3: Implémenter** `session-end-hook.sh`, `mcp.example.json`, `README.md`.
- [ ] **Step 4:** `bash deploy/client/test-session-end-hook.sh` → PASS.
- [ ] **Step 5: Vérification de bout en bout locale** : lancer `kairos-memoire` en local (`MEMOIRE_BIND=127.0.0.1:7801`, jeton de test), exécuter le hook sur la fixture sans dry-run, puis `conversation_search` en HTTP brut → la conversation est retrouvée.
- [ ] **Step 6: Commit** — `feat(client): configuration MCP et hook de fin de session`
