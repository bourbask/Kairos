# Socle — plan 2 : extension du routeur LLM — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Le routeur choisit un moteur nommé par usage, le client LLM sait appeler des outils sur deux protocoles, masque les identifiants avant tout envoi externe, et refuse de dépasser un plafond quotidien de tokens.

**Architecture:** Tout reste dans `src/llm.rs` (+ un module `src/llm/redact.rs` si `llm.rs` devient un dossier) et `src/config.rs`. Les nouveautés sont additives : la configuration actuelle (`[self_hosted]`, `[external]`, `strategic_concessions`) continue de fonctionner à l'identique. Les fonctions de construction / analyse des requêtes restent pures et testées sur des JSON figés, sans réseau.

**Tech Stack:** Rust 2024, `reqwest`, `serde_json`, `rusqlite` (comptage), `regex` (masquage — nouvelle dépendance, seule ajoutée).

**Spec:** `docs/superpowers/specs/2026-10-29-socle-tete-tentacules-design.md` (section « Extension du routeur LLM », « Sensibilité de la conversation », « Sécurité »)

**Base d'exécution :** branche partant de `feature/llm-routing` (commit `9cdb0ec`, PR #16 ouverte) — le routeur à étendre n'existe que là. Ne pas partir de `develop`.

## Global Constraints

- Rust 2024 ; `cargo test` vert à chaque commit ; sortie sans nouvel avertissement.
- **Aucun nom de fournisseur dans le code, les tests, les commentaires ou les exemples.** Le second protocole s'appelle `messages`. Tout ce qui est propre à un fournisseur (en-tête d'authentification, en-têtes de version, URL) vient de la configuration gitignorée.
- Configuration existante inchangée en comportement : un `llm.toml` sans les nouvelles sections route exactement comme avant (tests existants verts sans modification).
- Une génération `Strategic` ne part vers un moteur externe que si son usage figure dans `strategic_concessions` (règle existante, journalisée).
- Tout texte envoyé à un moteur externe passe par le masquage ; jamais vers un moteur auto-hébergé.
- Commentaires en français, intention seule. Commits `--no-gpg-sign`, sans attribution.

## Review Focus

1. Usage associé à un moteur externe mais déclaré `Strategic` sans concession → refus, pas d'envoi (Task 1 `mapped_external_strategic_without_concession_is_refused`).
2. Masquage : un IBAN ou un numéro de sécurité sociale coupé par des espaces est quand même masqué ; un nombre ordinaire (montant, date) ne l'est pas (Task 4).
3. Réponse du moteur avec plusieurs appels d'outils dans un même tour → tous rendus, dans l'ordre (Tasks 2 et 3).
4. Plafond atteint pile à la limite → l'appel suivant est refusé avant envoi, pas après (Task 5 `cap_reached_refuses_before_call`).
5. Arguments d'outil renvoyés par le moteur en JSON invalide → erreur explicite, pas de panique (Tasks 2 et 3).

---

### Task 1: Moteurs nommés et association usage → moteur

**Files:**
- Modify: `src/config.rs` (`LlmRoutingConfig`)
- Modify: `src/llm.rs` (`LlmRouter::route`, `LlmRouter::client`)
- Modify: `config/llm.example.toml`
- Test: `src/llm.rs` (module `tests`)

**Interfaces:**
- Produces:
  - `LlmRoutingConfig` gagne `#[serde(default)] pub engines: HashMap<String, EngineConfig>` et `#[serde(default)] pub usages: HashMap<String, String>` (usage → nom de moteur).
  - `pub struct EngineConfig { #[serde(flatten)] pub llm: LlmConfig, pub placement: Placement, #[serde(default)] pub daily_token_cap: Option<u64> }` ; `pub enum Placement { SelfHosted, External }` (serde `snake_case`).
  - `LlmRouter::route(&self, usage, sensitivity) -> anyhow::Result<RouteChoice>` ; `pub struct RouteChoice<'a> { pub config: &'a LlmConfig, pub engine: Engine, pub engine_name: String, pub daily_token_cap: Option<u64> }`. Pour les voies historiques, `engine_name` vaut `"self_hosted"` / `"external"` et `daily_token_cap` vaut `None`.
  - Règle : usage présent dans `usages` → le moteur nommé (erreur si le nom n'existe pas ou n'est pas utilisable) ; si ce moteur est `External` et la sensibilité `Strategic`, il faut une concession, sinon repli sur la voie auto-hébergée historique, sinon erreur. Usage absent de `usages` → comportement actuel inchangé.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn mapped_usage_uses_named_engine() { /* engines.fort External, usages.conversation="fort", Common → engine_name=="fort", Engine::External */ }
#[test] fn mapped_external_strategic_without_concession_is_refused() { /* idem, Strategic, aucune concession, aucune voie self_hosted → Err */ }
#[test] fn mapped_external_strategic_with_concession_goes_external() { /* strategic_concessions=["conversation"] → engine_name=="fort" */ }
#[test] fn mapped_to_unknown_engine_is_error() { /* usages.x="absent" → Err mentionnant "absent" */ }
#[test] fn unmapped_usage_keeps_legacy_routing() { /* config historique seule → mêmes résultats que les tests existants */ }
#[test] fn engines_from_toml() { /* parse un TOML avec [engines.fort] placement="external" daily_token_cap=1000 et [usages] → valeurs attendues */ }
```

- [ ] **Step 2:** `cargo test llm::` → FAIL.
- [ ] **Step 3: Implémenter.** Les appelants existants (`client()`, `signals`) passent par `RouteChoice` sans changement de comportement. `llm.example.toml` : sections `[engines.<nom>]` et `[usages]` commentées, valeurs vides, sans fournisseur.
- [ ] **Step 4:** `cargo test` → PASS (tests existants inchangés).
- [ ] **Step 5: Commit** — `feat(llm): moteurs nommés et association par usage`

---

### Task 2: Conversation avec outils — protocole compatible OpenAI

**Files:**
- Modify: `src/llm.rs`
- Test: `src/llm.rs` (module `tests`)

**Interfaces:**
- Produces (types neutres, partagés par les deux protocoles) :
  - `pub enum ChatTurn { User(String), Assistant { text: Option<String>, tool_calls: Vec<ToolCall> }, ToolResult { call_id: String, content: String, is_error: bool } }`
  - `pub struct ToolSpec { pub name: String, pub description: String, pub input_schema: serde_json::Value }`
  - `pub struct ToolCall { pub id: String, pub name: String, pub arguments: serde_json::Value }`
  - `pub struct TokenUsage { pub input: u64, pub output: u64 }`
  - `pub struct ChatReply { pub text: Option<String>, pub tool_calls: Vec<ToolCall>, pub usage: TokenUsage }`
  - `impl LlmClient { pub async fn chat(&self, system: &str, turns: &[ChatTurn], tools: &[ToolSpec]) -> anyhow::Result<ChatReply> }`
  - Fonctions pures testées : `fn build_chat_request(&self, system, turns, tools) -> anyhow::Result<(String /*chemin*/, serde_json::Value)>` et `fn parse_chat_response(&self, status, body) -> anyhow::Result<ChatReply>`.
  - Protocole `Ollama` (natif) : `chat` renvoie `Err("appel d'outils non pris en charge par ce protocole")`.

- [ ] **Step 1: Tests qui échouent** (JSON figés, format chat/completions : `tools[].function{name,description,parameters}`, `tool_calls[].function.arguments` en **chaîne** JSON, rôle `tool` avec `tool_call_id`, `usage.prompt_tokens/completion_tokens`)

```rust
#[test] fn openai_chat_request_carries_system_tools_and_tool_results() { /* system en premier message ; un ToolResult devient role "tool" + tool_call_id */ }
#[test] fn openai_parse_two_tool_calls_in_order() { /* deux tool_calls → Vec de 2, ordre conservé, arguments parsés en Value */ }
#[test] fn openai_parse_text_and_usage() { /* content texte, usage 12/34 → TokenUsage{12,34} */ }
#[test] fn openai_invalid_tool_arguments_is_error() { /* arguments "{pas du json" → Err, pas de panique */ }
#[test] fn native_protocol_refuses_chat() { /* Ollama → Err */ }
```

- [ ] **Step 2:** `cargo test llm::` → FAIL.
- [ ] **Step 3: Implémenter** `chat`, `build_chat_request`, `parse_chat_response` pour `OpenaiCompatible`.
- [ ] **Step 4:** `cargo test` → PASS.
- [ ] **Step 5: Commit** — `feat(llm): conversation avec appel d'outils (protocole compatible)`

---

### Task 3: Protocole `messages` avec mise en cache du préfixe

**Files:**
- Modify: `src/config.rs` (`LlmProtocol::Messages`, champs d'en-têtes)
- Modify: `src/llm.rs`
- Modify: `config/llm.example.toml`
- Test: `src/llm.rs`

**Interfaces:**
- Consumes: types de Task 2.
- Produces:
  - `LlmProtocol::Messages` (serde `"messages"`).
  - `LlmConfig` gagne `#[serde(default)] pub api_key_header: Option<String>` (absent → `Authorization: Bearer`) et `#[serde(default)] pub headers: HashMap<String, String>` (en-têtes fixes envoyés tels quels, ex. version d'API). Rien de spécifique à un fournisseur dans le code.
  - Format `messages` : chemin `/v1/messages` ; corps `{model, max_tokens, system: [{type:"text", text, cache_control:{type:"ephemeral"}}], tools: [{name, description, input_schema}], messages: [...]}` ; le **dernier** outil porte aussi `cache_control` (préfixe consignes + outils mis en cache) ; un `Assistant` avec appels → blocs `tool_use {id,name,input}` ; un `ToolResult` → message `user` avec bloc `tool_result {tool_use_id, content, is_error}` ; plusieurs `ToolResult` consécutifs regroupés dans **un seul** message `user`. Réponse : blocs `text` concaténés, blocs `tool_use` → `ToolCall` (input déjà objet), `usage.input_tokens + cache_read_input_tokens + cache_creation_input_tokens` → `TokenUsage.input`, `output_tokens` → `output`. `max_tokens` : `pub const CHAT_MAX_TOKENS: u32 = 4096;`.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn messages_request_marks_system_and_last_tool_for_cache() { /* system[0].cache_control présent ; seul le dernier tool porte cache_control */ }
#[test] fn messages_groups_consecutive_tool_results() { /* deux ToolResult → un seul message user avec 2 blocs tool_result */ }
#[test] fn messages_parse_text_tool_use_and_usage() { /* blocs text + 2 tool_use → text concaténé, 2 ToolCall ordonnés ; usage avec cache_read → input sommé */ }
#[test] fn messages_custom_auth_header_and_fixed_headers() { /* api_key_header="x-cle", headers {"x-version":"1"} → la requête construite porte ces en-têtes et pas Authorization */ }
#[test] fn messages_protocol_from_toml() { /* protocol="messages" + api_key_header + [headers] parsés */ }
```

Pour tester les en-têtes sans réseau : extraire `fn auth_and_headers(&self) -> Vec<(String, String)>` (pure) utilisée par `chat` et `generate`.

- [ ] **Step 2:** `cargo test llm::` → FAIL.
- [ ] **Step 3: Implémenter.** `generate` (texte simple) fonctionne aussi en `messages` (un seul tour utilisateur, sans outils).
- [ ] **Step 4:** `cargo test` → PASS.
- [ ] **Step 5: Commit** — `feat(llm): protocole messages avec mise en cache du préfixe`

---

### Task 4: Masquage des identifiants avant envoi externe

**Files:**
- Create: `src/redact.rs` (et `pub mod redact;` dans `src/lib.rs`)
- Modify: `src/llm.rs` (application dans `chat` et `generate` quand la voie est externe)
- Modify: `src/config.rs` (`LlmRoutingConfig.redact_terms: Vec<String>`), `config/llm.example.toml`
- Modify: `Cargo.toml` (`regex = "1"`)
- Test: `src/redact.rs`, `src/llm.rs`

**Interfaces:**
- Produces:
  - `pub struct Redactor` ; `pub fn Redactor::new(extra_terms: &[String]) -> Redactor` ; `pub fn redact(&self, text: &str) -> String`.
  - Remplacements (jeton fixe, pas de valeur partielle) : IBAN → `[IBAN]` ; numéro de sécurité sociale français (13 chiffres + clé 2 chiffres, espaces tolérés) → `[NIR]` ; numéro de carte (13–19 chiffres, espaces/tirets tolérés, **validé par Luhn**) → `[CARTE]` ; adresse email → `[EMAIL]` ; numéro de téléphone français (`0X` ou `+33`, 10 chiffres, séparateurs tolérés) → `[TEL]` ; chaque terme de `redact_terms` (insensible à la casse, littéral) → `[MASQUE]`.
  - `LlmClient` reçoit un `Option<Arc<Redactor>>` depuis le routeur : `Some` si la voie choisie est `External`, `None` sinon. Masquage appliqué à `system`, à chaque texte de tour et à chaque `ToolResult.content` ; pas aux `input_schema`.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn redacts_iban_with_spaces() { /* "FR76 3000 6000 0112 3456 7890 189" → contient "[IBAN]", plus aucun chiffre de l'IBAN */ }
#[test] fn redacts_nir_with_spaces() { /* "1 85 05 78 006 084 36" → "[NIR]" */ }
#[test] fn redacts_valid_card_only() { /* "4111 1111 1111 1111" → "[CARTE]" ; "4111 1111 1111 1112" (Luhn faux) inchangé */ }
#[test] fn keeps_amounts_and_dates() { /* "reste 1 234,56 € le 2026-10-30" inchangé */ }
#[test] fn redacts_email_phone_and_extra_terms() { /* "a.b@c.fr", "06 12 34 56 78", terme "12 rue X" → trois jetons */ }
#[test] fn external_route_redacts_and_self_hosted_does_not() { /* build_chat_request d'un client externe contient "[IBAN]" ; d'un client auto-hébergé contient l'IBAN brut */ }
```

- [ ] **Step 2:** `cargo test redact:: llm::` → FAIL.
- [ ] **Step 3: Implémenter.** Ordre d'application : termes configurés, IBAN, carte, NIR, téléphone, email (le plus spécifique d'abord).
- [ ] **Step 4:** `cargo test` → PASS.
- [ ] **Step 5: Commit** — `feat(llm): masquage des identifiants avant envoi externe`

---

### Task 5: Comptage des tokens et plafond quotidien

**Files:**
- Modify: `src/storage.rs` (table + deux méthodes)
- Modify: `src/llm.rs` (garde dans `chat` / `generate`)
- Modify: `config/llm.example.toml`
- Test: `src/storage.rs`, `src/llm.rs`

**Interfaces:**
- Consumes: `RouteChoice.daily_token_cap`, `engine_name` (Task 1) ; `TokenUsage` (Task 2).
- Produces:
  - Table `llm_usage(day TEXT, engine TEXT, usage TEXT, input_tokens INTEGER, output_tokens INTEGER)` ; `Storage::record_llm_usage(&self, day: NaiveDate, engine: &str, usage: &str, t: &TokenUsage) -> SqlResult<()>` ; `Storage::llm_tokens_on(&self, day: NaiveDate, engine: &str) -> SqlResult<u64>` (entrée + sortie).
  - `pub struct SpendGuard { db_path: PathBuf }` ; `pub fn check(&self, engine: &str, cap: Option<u64>, today: NaiveDate) -> anyhow::Result<()>` (refus si `consommé >= cap`) ; `pub fn record(&self, engine, usage, today, t: &TokenUsage)`. Ouvre une connexion courte à chaque appel. `LlmRouter::load` reçoit le chemin de la base (`DATABASE_PATH` existant) ; sans chemin, pas de garde (comportement actuel).
  - `chat` / `generate` : `check` **avant** l'envoi, `record` après une réponse réussie. Le protocole natif sans comptage de tokens enregistre 0.

- [ ] **Step 1: Tests qui échouent**

```rust
#[test] fn usage_is_summed_per_day_and_engine() { /* record 10/5 puis 3/2 même jour/moteur → 20 ; autre moteur → 0 ; autre jour → 0 */ }
#[test] fn cap_reached_refuses_before_call() { /* cap 20, consommé 20 → check Err ; consommé 19 → Ok */ }
#[test] fn no_cap_never_refuses() { /* cap None, consommé énorme → Ok */ }
```

- [ ] **Step 2:** `cargo test` → FAIL.
- [ ] **Step 3: Implémenter.** Le jour est la date locale (`calendar::USER_TZ`).
- [ ] **Step 4:** `cargo test` → PASS.
- [ ] **Step 5: Commit** — `feat(llm): plafond quotidien de tokens par moteur`
