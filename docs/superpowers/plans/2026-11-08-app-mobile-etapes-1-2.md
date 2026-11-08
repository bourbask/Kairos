# Application mobile — étapes 1 et 2 — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Livrer l'application Android Kairos (socle, Parler, Aujourd'hui, Décider) et ce que la tête et les tentacules doivent exposer pour elle.

**Architecture:** La tête (Rust, axum) ouvre une seconde écoute HTTP réservée à l'application, authentifiée par jeton d'appareil. Elle journalise la conversation, relaie les messages de l'application dans la même file que la passerelle, et agrège trois outils conventionnels des tentacules (`cards`, `decisions`, `decide`), masqués du moteur de langage. L'application (Kotlin, Jetpack Compose) affiche ce que le serveur décrit et ne contient aucune logique métier.

**Tech Stack:** Rust 2024 (tokio, axum 0.8, rusqlite, rmcp) ; Android : Kotlin, Jetpack Compose, minSdk 34, compileSdk/targetSdk 36, OkHttp, kotlinx.serialization, CameraX + ML Kit (lecture de code-barres sur l'appareil), SpeechRecognizer / TextToSpeech du système, WorkManager.

**Spec:** `docs/superpowers/specs/2026-11-08-app-mobile-design.md` (maquettes : `docs/maquettes/app-mobile-ecrans.html`).

## Global Constraints

- Dépôt public : aucun nom d'hôte, adresse, compte, fournisseur ou donnée personnelle dans le code, les commentaires, les exemples ou les messages de commit. Commentaires = intention seule.
- Commits : `git commit --no-gpg-sign`, aucune mention d'IA ni `Co-Authored-By`. Travail sur la branche `feature/app-mobile` uniquement. Pas de rebase, pas de push forcé.
- Interdit sans accord humain : merge, déploiement, toute commande sur le serveur distant, suppression de fichiers hors de ce dépôt.
- Rust : `cargo test -p <crate>` vert et `cargo clippy -p <crate> -- -D warnings` sans erreur pour chaque crate touché.
- Android : JDK `JAVA_HOME=$HOME/.local/jdk/jdk-21.0.13+9`, SDK `ANDROID_HOME=$HOME/Android/Sdk` (plateforme 36, build-tools 36.0.0 installés). Commande de vérification : `cd mobile && ./gradlew --no-daemon testDebugUnitTest assembleDebug`. Pas d'émulateur : la logique testable vit dans des classes Kotlin pures, testées en JVM.
- Identifiant d'application : `app.kairos.mobile`. Code sous `mobile/`.
- Langue de l'interface : français.
- Palette (clair / sombre) : Fougère `#4F7942`/`#7FAF6E`, Sous-bois `#2F4A28`/`#B9D8AA`, Pousse `#E3EDDA`/`#243324`, Papier `#F7F5EF`/`#121712`, Surface `#FFFFFF`/`#1B221B`, Encre `#1F2620`/`#E6EBE3`, Ambre `#D9952B`/`#E8AE4E`, Brique `#B8463A`/`#E07A6C`. Thème clair par défaut.
- Gestes : → positif, ← négatif, ↑ plus tard, ↓ fermer ; appui long sur Kairos = parler. Pas de double/triple tap, pas de glissement depuis le bord. Chaque geste a un bouton équivalent visible.
- Une demande non traitée expire, jamais de relance.

### Contrat JSON partagé (tentacules → tête → application)

Outil `cards` d'un tentacule, argument `{"day": "AAAA-MM-JJ"}`, résultat texte = tableau JSON :

```json
[{"id": "repas-2026-11-08-midi", "kind": "info", "time": "12:15",
  "title": "Midi · Soupe de carcasse", "body": "Frigo, réchauffer 5 min",
  "chips": [{"text": "champignons à finir", "tone": "attention"}],
  "detail": "texte long optionnel (recette…)"}]
```

`kind` ∈ `info` (étape 1 : seul utilisé). `time` optionnel (`HH:MM`), `tone` ∈ `neutre|attention|urgent`, `chips`/`detail`/`body` optionnels.

Outil `decisions`, sans argument, résultat = tableau JSON :

```json
[{"id": "op-123", "title": "Dépense à classer", "headline": "12,34 €",
  "subtitle": "libellé · 06/11", "body": "texte optionnel",
  "proposal": {"id": "Alimentation", "label": "Alimentation", "reason": "optionnel"},
  "alternatives": [{"id": "Loisirs", "label": "Loisirs"}], "expires": "2026-12-06"}]
```

`proposal` peut être `null`. Outil `decide`, argument `{"id": "...", "choice": "<id d'option>"}`, résultat texte lisible ; erreur d'outil si refusé.

La tête ajoute `"module": "<nom du tentacule>"` à chaque carte et décision qu'elle relaie.

### API de l'application (tête), préfixe `/app/v1`, `Authorization: Bearer <jeton d'appareil>`

| Méthode | Chemin | Corps / réponse |
|---|---|---|
| GET | `/status` | `{"busy": bool, "decisions": n}` |
| POST | `/chat` | `{"text": "..."}` → 202 ; 400 si vide ; 503 si file pleine |
| GET | `/chat?after=<id>&limit=<n>` | `[{"id", "at", "role": "user"|"kairos", "source": "app"|"salon"|"kairos", "text"}]` (sans `after` : les `limit` derniers, défaut 50, max 200, ordre chronologique) |
| GET | `/days?from=AAAA-MM-JJ&days=<n>` | `[{"day", "work": "sur site"|"télétravail"|"repos", "cards": [...]}]` (défaut aujourd'hui, 1 jour, max 7) |
| GET | `/decisions` | tableau de décisions (avec `module`), triées par `expires` croissant, expirées exclues |
| POST | `/decisions/{module}/{id}` | `{"choice": "..."}` → 200 `{"message": "..."}` ; 422 `{"message": "..."}` si l'outil refuse |

## Review Focus

1. Jeton d'appareil révoqué ou inconnu : toute route `/app/v1` répond 401, sans indiquer si l'appareil existe (Task 2/3 : test `revoked_device_is_rejected`).
2. Texte de carte venant d'un tentacule contenant du JSON invalide ou une erreur : la tête ignore ce tentacule et sert les autres (Task 3 : test `broken_tentacle_cards_are_skipped`).
3. Message de l'application pendant qu'une réponse longue est en cours : il est mis en file, journalisé, et `busy` reste vrai jusqu'à la dernière réponse (Task 1 : test `app_message_is_logged_and_mirrored`).
4. Serveur injoignable dans l'application : écrans en lecture seule avec le dernier état connu et un bandeau, actions désactivées, aucun plantage (Task 8 : test `today_keeps_last_state_when_offline`).
5. Libellé bancaire générique (« CB 06/11 PAIEMENT ») : `decide` refuse proprement, la carte reste, message affiché (Task 6 : test `decide_rejects_generic_label`).

---

### Task 1: Tête — journal de conversation et messages de l'application

**Files:**
- Modify: `tete/src/state.rs` (table `chat_log`), `tete/src/gateway.rs` (type `Inbound`), `tete/src/main.rs` (journalisation, recopie), `tete/src/jobs.rs` (journaliser chaque envoi sortant)
- Test: tests unitaires dans `tete/src/state.rs` et `tete/src/gateway.rs` (style existant)

**Interfaces:**
- Produces:
  - `pub struct Inbound { pub text: String, pub from_app: bool }` dans `gateway.rs` ; le canal devient `Sender<Inbound>` ; `inbound_router` envoie `from_app: false`.
  - `State::log_chat(&self, role: &str, source: &str, text: &str, at: NaiveDateTime) -> Result<i64>` ; `role` ∈ `user|kairos`, `source` ∈ `app|salon|kairos`.
  - `pub struct ChatEntry { pub id: i64, pub at: String, pub role: String, pub source: String, pub text: String }` (Serialize).
  - `State::chat_since(&self, after: Option<i64>, limit: u32) -> Result<Vec<ChatEntry>>` (sans `after` : les `limit` derniers ; ordre chronologique ; `limit` borné à 200).
  - `pub const FROM_APP_PREFIX: &str = "📱 ";`
  - `pub busy: Arc<AtomicBool>` partagé : vrai pendant le traitement d'un message entrant.

- [ ] **Step 1: Tests** — `chat_log_roundtrip` (3 entrées, `chat_since(None, 2)` rend les 2 dernières dans l'ordre, `chat_since(Some(id1), 50)` rend les 2 suivantes) ; `chat_limit_is_capped` (`limit` 1000 → au plus 200) ; `app_message_is_logged_and_mirrored` : extraire la logique de traitement d'un `Inbound` dans une fonction testable `fn mirror_text(m: &Inbound) -> Option<String>` qui rend `Some("📱 texte")` pour `from_app`, `None` sinon.
- [ ] **Step 2:** `cargo test -p tete` → échec attendu (symboles absents).
- [ ] **Step 3: Implémenter.** Dans la boucle de `main.rs` : journaliser le message entrant (`user`, source `app` ou `salon`) ; si `from_app`, envoyer d'abord `mirror_text` par la sortie ; poser `busy` à vrai, traiter, journaliser l'accusé et la réponse (`kairos`, source `kairos`), remettre `busy` à faux. Dans `jobs.rs`, tout texte envoyé par la sortie (briefs, suggestions, alertes, vidage de file) est journalisé `kairos` — un seul point de passage, par exemple une fonction `say(ctx, text)` utilisée partout où `ctx.outbound.send` l'était. Un échec de journalisation est loggé, jamais bloquant.
- [ ] **Step 4:** `cargo test -p tete && cargo clippy -p tete -- -D warnings` → vert.
- [ ] **Step 5: Commit** `feat(tete): journal de conversation et messages de l'application`.

### Task 2: Tête — appairage des appareils

**Files:**
- Modify: `tete/src/state.rs` (table `devices`), `tete/src/main.rs` (sous-commandes), `tete/Cargo.toml` (`sha2`, `qrcode`, `rand` ou `getrandom` — versions récentes stables)
- Create: `tete/src/devices.rs`
- Test: unitaires dans `tete/src/devices.rs`

**Interfaces:**
- Produces:
  - `State::add_device(&self, name: &str, token_hash: &str, at: NaiveDateTime) -> Result<()>` (nom déjà actif → erreur) ; `State::revoke_device(&self, name: &str) -> Result<bool>` ; `State::device_for(&self, token_hash: &str) -> Result<Option<String>>` (appareils non révoqués seulement) ; `State::devices(&self) -> Result<Vec<(String, String, bool)>>` (nom, date, révoqué).
  - `devices::new_token() -> String` (32 octets aléatoires, hex) ; `devices::hash(token: &str) -> String` (SHA-256 hex) ; `devices::pairing_uri(url: &str, token: &str) -> String` = `kairos://pair?url=<url encodée>&token=<jeton>`.
  - Sous-commandes du binaire `tete` : `tete pair <nom>`, `tete revoke <nom>`, `tete devices`. `pair` lit l'adresse publique de l'API dans `TETE_APP_PUBLIC_URL` (refus explicite si absente), stocke l'empreinte, affiche l'URI et un QR code en caractères dans le terminal, puis quitte. Sans argument : comportement actuel (serveur).

- [ ] **Step 1: Tests** — `hash_is_stable_and_hex` ; `pairing_uri_encodes_url` (`http://h:1/x?y` → `url=http%3A%2F%2Fh%3A1%2Fx%3Fy`) ; `revoked_device_is_rejected` (ajout, `device_for` → `Some`, révocation, `device_for` → `None`) ; `duplicate_active_name_refused`.
- [ ] **Step 2:** `cargo test -p tete` → échec.
- [ ] **Step 3: Implémenter.** Le jeton en clair n'est jamais stocké ni journalisé.
- [ ] **Step 4:** tests + clippy verts.
- [ ] **Step 5: Commit** `feat(tete): appairage des appareils par jeton`.

### Task 3: Tête — API de l'application

**Files:**
- Create: `tete/src/app_api.rs`
- Modify: `tete/src/tentacles.rs` (masquer `cards`, `decisions`, `decide` de `tool_specs`), `tete/src/config.rs` (`TETE_APP_BIND`, optionnel : sans lui, pas d'API), `tete/src/main.rs` (seconde écoute), `tete/src/lib.rs`, `deploy/docker/docker-compose.yml` (port de l'API publié sur l'adresse du réseau privé (variable de substitution), variable `TETE_APP_PORT` défaut 8100, conteneur 8091), `deploy/docker/README.md` (section « Application mobile » : variables `TETE_APP_BIND`, `TETE_APP_PUBLIC_URL`, appairage `docker compose exec tete /app/tete pair <nom>`), `config/*.example*` si une variable y figure.
- Test: unitaires dans `app_api.rs` (axum `Router` testé avec `tower::ServiceExt::oneshot` ; des tentacules simulés via un trait ou une fonction injectée)

**Interfaces:**
- Consumes: Task 1 (`Inbound`, `chat_since`, `busy`), Task 2 (`device_for`, `hash`).
- Produces: `pub fn app_router(deps: AppDeps) -> Router` ; routes et formats exactement comme la table « API de l'application » des Global Constraints. L'API ouvre **sa propre** connexion `State` (même fichier SQLite, WAL) et **ses propres** `Tentacles` (`Arc<tokio::sync::RwLock<Tentacles>>`, rafraîchis toutes les 5 min dans une tâche), pour ne jamais attendre la boucle principale. `work` de `/days` vient du rythme de travail (même logique que la ligne « Rythme » de la consigne système). `decisions` du `/status` = nombre de décisions non expirées. Le corps des requêtes est borné (64 Kio), le texte de chat comme `MAX_INPUT_CHARS`.
- Le résultat d'un tentacule passe par la tête comme donnée : JSON validé contre le contrat, champs inconnus ignorés, textes tronqués (titre 120, corps 500, détail 8000 caractères).

- [ ] **Step 1: Tests** — `unauthorized_without_token` (401) ; `revoked_device_is_rejected` via HTTP (401) ; `chat_post_enqueues_from_app` ; `chat_get_after_cursor` ; `days_merges_modules_and_sorts_by_time` (deux tentacules simulés, cartes avec et sans `time` : celles avec heure triées, celles sans à la fin) ; `broken_tentacle_cards_are_skipped` ; `decisions_exclude_expired_and_sort` ; `decide_unknown_module_404` ; `decide_tool_error_is_422` ; `app_tools_hidden_from_engine` (`tool_specs` ne contient aucun outil `cards`, `decisions`, `decide`).
- [ ] **Step 2:** `cargo test -p tete` → échec.
- [ ] **Step 3: Implémenter.**
- [ ] **Step 4:** tests + clippy verts ; `docker compose -f deploy/docker/docker-compose.yml config -q` sans erreur si docker est disponible localement (sinon le noter dans le rapport).
- [ ] **Step 5: Commit** `feat(tete): API de l'application mobile`.

### Task 4: Alimentation — outil `cards` et emplacement étagère

**Files:**
- Modify: `tentacles/alimentation/src/tools.rs` (outil `cards`), `tentacles/alimentation/src/store.rs` (`LOCATIONS` + `etagere`, alias « étagère », « etagere », « etag… »), `tentacles/alimentation/src/shelf.rs` si les durées dépendent de l'emplacement (étagère = placard)
- Test: unitaires existants du crate

**Interfaces:**
- Consumes: plan de repas (`store.plan`), `BOX_NOTE`, carnet de recettes (`recipe_text`), logique d'urgence de `use_first`.
- Produces: outil MCP `cards(day: Option<String>)` → tableau JSON du contrat. Une carte par repas planifié du jour (`id` = `repas-<jour>-<midi|soir>`, `time` 12:15 / 19:00, `title` = « Midi · <plat> » / « Soir · <plat> », `body` = « reste » ou durée de préparation si connue, `detail` = texte de la recette si connue). Si la note `BOX_NOTE` est posée sur le soir : une carte `box-<jour>` à 20:00, titre « Box de demain ». Si des produits sont à consommer sous 1 jour : une carte `a-finir-<jour>` sans heure, titre « À finir vite », `body` = noms, chip `urgent`. Jour vide → `[]`.

- [ ] **Step 1: Tests** — `cards_for_planned_day` (plan midi+soir, recette connue pour le soir → 2 cartes, `detail` présent sur le soir) ; `cards_box_note` ; `cards_empty_day_is_empty_array` ; `etagere_location_accepted` (`location("Étagère")` → `etagere`).
- [ ] **Step 2:** `cargo test -p alimentation` (nom du paquet à vérifier dans `Cargo.toml`) → échec.
- [ ] **Step 3: Implémenter.** Mettre à jour les descriptions d'outils qui énumèrent les emplacements.
- [ ] **Step 4:** tests + clippy verts.
- [ ] **Step 5: Commit** `feat(alimentation): cartes du jour pour l'application et emplacement étagère`.

### Task 5: Agenda — outil `cards`

**Files:**
- Modify: `tentacles/agenda/src/tools.rs`
- Test: unitaires du crate

**Interfaces:**
- Produces: outil `cards(day: Option<String>)` → une carte par événement du jour (`id` = `agenda-<uid ou hash>`, `time` = début `HH:MM` ou absent si journée entière, `title` = résumé, `body` = « HH:MM–HH:MM · lieu » quand connus) et une par tâche due ce jour-là (sans heure, chip `attention`). Calendrier « Repas » exclu s'il est lu par ce tentacule (les repas viennent de l'alimentation) — vérifier comment les calendriers sont configurés et exclure par configuration, pas par nom en dur.

- [ ] **Step 1: Tests** — `cards_lists_events_of_day` ; `all_day_event_has_no_time` ; `cards_empty_day`.
- [ ] **Step 2:** échec. **Step 3:** implémenter. **Step 4:** tests + clippy verts.
- [ ] **Step 5: Commit** `feat(agenda): cartes du jour pour l'application`.

### Task 6: Finances — outils `decisions` et `decide`

**Files:**
- Modify: `tentacles/finances/src/tools.rs` (+ `render.rs` si besoin)
- Test: unitaires du crate (API simulée comme dans les tests existants)

**Interfaces:**
- Consumes: `api.unclassified()`, `api.cycle()` (postes = clés de `envelopes`), `api.create_rule`, `clean_payee`, `is_generic`, `is_generic_token`.
- Produces:
  - `decisions` : une décision par **commerçant** (opérations sans catégorie regroupées par motif dérivé du libellé : libellé nettoyé, jetons génériques retirés). `id` = motif, `title` = « Dépense à classer », `headline` = montant total formaté `12,34 €`, `subtitle` = « <motif> · <n> opération(s) · <date la plus récente JJ/MM> », `alternatives` = tous les postes, `proposal` = `null` (pas de deviner à l'aveugle), `expires` = date la plus récente + 30 jours. Motif générique ou vide → pas de carte pour ce groupe.
  - `decide(id, choice)` : `choice` doit être un poste existant ; crée la règle `id → choice` par le même chemin que `classify` (mêmes garde-fous). Erreur d'outil sinon.

- [ ] **Step 1: Tests** — `decisions_group_by_merchant` (3 opérations dont 2 du même commerçant → 2 décisions, totaux corrects) ; `decisions_skip_generic_labels` ; `decide_creates_rule` ; `decide_rejects_unknown_poste` ; `decide_rejects_generic_label`.
- [ ] **Step 2:** échec. **Step 3:** implémenter. **Step 4:** tests + clippy verts.
- [ ] **Step 5: Commit** `feat(finances): décisions de classement pour l'application`.

### Task 7: Android — socle, thème, personnage, navigation

**Files:**
- Create: `mobile/` (projet Gradle avec wrapper commité, `settings.gradle.kts`, `app/build.gradle.kts`, catalogue de versions `gradle/libs.versions.toml`), `mobile/app/src/main/java/app/kairos/mobile/` : `MainActivity.kt`, `ui/theme/` (couleurs, typographie, `KairosTheme(mode)`), `ui/mascot/Mascot.kt`, `ui/Shell.kt` ; `mobile/.gitignore` ; `mobile/README.md` (compilation, installation par `adb install`, appairage).
- Test: `mobile/app/src/test/.../MascotTest.kt`

**Interfaces:**
- Produces:
  - `enum class MascotState { Repos, Ecoute, Reflechit, Agit, Attend }` ; `@Composable fun Mascot(state: MascotState, size: Dp, onBackground: Color)` dessiné sur `Canvas` à partir d'une grille 16×16 (corps carré, deux yeux, mèche en avant, petites ailes aux pieds — voir le symbole `kai` des maquettes) ; animations par état comme dans la spec.
  - `object MascotSprite { fun pixels(state: MascotState, frame: Int): List<Pixel> }` (pur, testable) avec `data class Pixel(val x: Int, val y: Int, val role: PixelRole)`.
  - `enum class ThemeMode { Clair, Sombre, Auto }` ; `@Composable fun KairosTheme(dark: Boolean, content: @Composable () -> Unit)`.
  - `Shell` : deux onglets (Aujourd'hui, Espaces) et le bouton Kairos central ; le bouton expose `onTap` et `onPressStart/onPressEnd/onDrag` pour la Task 9. Espaces = liste vide « bientôt » pour l'instant.
- Obtenir Gradle : télécharger une distribution Gradle stable récente dans `$HOME/.local/gradle`, générer le wrapper, commiter le wrapper. Versions AGP/Kotlin/Compose : les plus récentes stables compatibles JDK 21 et compileSdk 36.

- [ ] **Step 1: Tests** — `sprite_has_forelock_and_wings` (pixels de rôle `Meche` au-dessus du corps, `Aile` de part et d'autre des pieds) ; `eyes_change_with_state` (yeux de `Ecoute` ≠ `Repos`) ; `sprite_within_grid` (tous les pixels dans 0..15).
- [ ] **Step 2:** `./gradlew testDebugUnitTest` → échec.
- [ ] **Step 3: Implémenter.**
- [ ] **Step 4:** `./gradlew --no-daemon testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(mobile): socle de l'application, thème et personnage`.

### Task 8: Android — appairage, client de l'API, Aujourd'hui

**Files:**
- Create: `data/Pairing.kt`, `data/SecureStore.kt`, `data/Api.kt`, `data/Models.kt`, `data/Repository.kt`, `ui/pairing/PairingScreen.kt`, `ui/today/TodayScreen.kt`, `ui/today/WeekScreen.kt`
- Test: `PairingTest.kt`, `ApiTest.kt` (MockWebServer), `RepositoryTest.kt`

**Interfaces:**
- Consumes: contrat JSON et API (Global Constraints), Task 7 (`Shell`, thème).
- Produces:
  - `object Pairing { fun parse(uri: String): Result<Credentials> }` ; `data class Credentials(val baseUrl: String, val token: String)`. Refuse un schéma autre que `kairos://pair`, une URL non `http(s)`, un jeton vide.
  - `SecureStore` : jeton chiffré par une clé AES-GCM de l'Android Keystore, stocké dans DataStore ; `save(Credentials)`, `load(): Credentials?`, `clear()`.
  - `class KairosApi(baseUrl, token, client: OkHttpClient)` : `status()`, `postChat(text)`, `chat(after: Long?, limit: Int)`, `days(from: LocalDate, days: Int)`, `decisions()`, `decide(module, id, choice)`. Erreurs typées : `sealed class ApiError { Unauthorized; Offline; Server(code, message) }`.
  - `Repository` : garde le dernier état connu de `days` et `decisions` (en mémoire + fichier dans le stockage privé) et l'expose en `StateFlow<Snapshot>` avec `offline: Boolean`.
  - Écran d'appairage au premier lancement : scanner le QR (CameraX + ML Kit) ou coller l'URI ; 401 plus tard → retour à l'appairage avec message.
  - Aujourd'hui (voir maquette) : salutation selon l'heure, ligne du lendemain (`work` du jour suivant), aperçu de la pile de décisions (nombre + « ≈ 1 min ») qui ouvre Décider (Task 10), fil chronologique ; tap = déplier `detail` ; glisser → « fait », ← « plus tard » : état local par jour (fichier privé), l'élément passe en bas grisé, bouton **Annuler** quelques secondes. Pincer = vue semaine (`days=7`), écarter = retour ; bouton « Semaine » équivalent. Tirer vers le bas = actualiser.
- [ ] **Step 1: Tests** — `parse_valid_uri`, `parse_rejects_other_scheme`, `parse_rejects_empty_token` ; `api_sends_bearer` ; `api_maps_401_to_unauthorized` ; `api_parses_days_contract` (exemple du contrat) ; `today_keeps_last_state_when_offline` (Repository : succès puis échec réseau → snapshot conservé, `offline = true`).
- [ ] **Step 2:** échec. **Step 3:** implémenter. **Step 4:** `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.
- [ ] **Step 5: Commit** `feat(mobile): appairage, client de l'API et écran Aujourd'hui`.

### Task 9: Android — Parler

**Files:**
- Create: `ui/talk/ChatScreen.kt`, `ui/talk/TalkController.kt`, `voice/Speech.kt`, `voice/Tts.kt`, `work/ReplyWatcher.kt`
- Test: `TalkControllerTest.kt`, `ChatMergeTest.kt`

**Interfaces:**
- Consumes: Task 7 (bouton Kairos, `MascotState`), Task 8 (`KairosApi`, `Repository`).
- Produces:
  - `class TalkController` (pur, testable) : machine d'états du bouton : `Idle → Recording` (appui long), `Recording → Locked` (glissement ↑ au-delà de 80 dp), `Recording → Cancelled` (glissement ← au-delà de 100 dp), relâchement en `Recording` = envoyer la transcription, `Locked` = transcription modifiable + bouton envoyer. Expose l'état du personnage (`Ecoute` pendant l'enregistrement, `Reflechit` quand `status.busy`, `Attend` quand des décisions attendent, `Repos` sinon).
  - Reconnaissance vocale du système, `fr-FR`, hors ligne préférée ; transcription partielle affichée en direct ; refus de permission micro → message et bascule sur le clavier.
  - Conversation écrite (tap sur Kairos) : historique depuis `/chat`, bulles utilisateur/Kairos, messages venant du salon et de l'application dans le même fil, saisie texte ; pendant une réponse attendue, sondage de `/chat?after=` toutes les 2 s.
  - `ReplyWatcher` (WorkManager) : si l'utilisateur quitte l'écran avec une réponse attendue, sondage borné à 10 minutes puis notification locale avec le début de la réponse ; aucun service permanent.
  - Lecture à voix haute (TextToSpeech `fr-FR`) : réglage, automatique en mode verrouillé mains libres.
  - Retour haptique aux seuils.
- [ ] **Step 1: Tests** — `long_press_starts_recording` ; `slide_up_locks` ; `slide_left_cancels` ; `release_sends_transcript` ; `empty_transcript_not_sent` ; `chat_merge_dedups_by_id` (fusion de pages de `/chat` sans doublon, ordre conservé).
- [ ] **Step 2:** échec. **Step 3:** implémenter. **Step 4:** build + tests verts.
- [ ] **Step 5: Commit** `feat(mobile): parler à Kairos, à la voix ou à l'écrit`.

### Task 10: Android — Décider

**Files:**
- Create: `ui/decide/DeckScreen.kt`, `ui/decide/DeckState.kt`
- Test: `DeckStateTest.kt`

**Interfaces:**
- Consumes: Task 8 (`decisions()`, `decide()`), Task 7.
- Produces:
  - `class DeckState(cards: List<Decision>)` (pur) : `swipeRight()` → choix = `proposal.id` (si `proposal` nul : ouvre les alternatives) ; `swipeLeft()` → ouvre les alternatives ; `choose(optionId)` ; `later()` → carte en fin de pile, expiration inchangée ; `close()` ; `undo()` (annule la dernière action locale tant que l'appel serveur n'est pas parti, délai 4 s) ; refus serveur (422) → la carte revient en tête avec le message.
  - Écran pile (maquette « Décider ») : carte inclinée pendant le glissement, tampon OUI/NON, seuil de validation à 35 % de la largeur, vibration au seuil, alternatives en puces sous le pouce, barre de progression, boutons visibles équivalents (← autre, ↑ plus tard, oui →, fermer, annuler). Fin de pile → écran « C'est fait » avec le personnage `Agit` puis retour.
- [ ] **Step 1: Tests** — `swipe_right_accepts_proposal` ; `swipe_right_without_proposal_opens_alternatives` ; `later_moves_card_to_end_keeps_expiry` ; `undo_restores_card` ; `server_refusal_puts_card_back_with_message`.
- [ ] **Step 2:** échec. **Step 3:** implémenter. **Step 4:** build + tests verts.
- [ ] **Step 5: Commit** `feat(mobile): pile de décisions`.

### Task 11: Android — thème automatique, réglages, finitions

**Files:**
- Create: `ui/theme/AutoTheme.kt`, `ui/settings/SettingsScreen.kt`
- Modify: `mobile/README.md`
- Test: `AutoThemeTest.kt`

**Interfaces:**
- Produces:
  - `class LuxHysteresis(darkBelow: Float = 15f, lightAbove: Float = 40f, holdMillis: Long = 8000)` (pur) : `fun update(lux: Float, nowMillis: Long): Boolean` (vrai = sombre) ; ne bascule qu'après `holdMillis` de mesures du même côté du seuil ; sans capteur → suivre le thème système.
  - Réglages (accès : icône en haut d'Aujourd'hui) : thème Clair / Sombre / Auto (défaut Clair), lecture vocale des réponses, appareil appairé (adresse masquée en partie) et « Se déconnecter » (efface le jeton).
  - Icône d'application : le personnage pixel sur fond fougère (icône adaptative).
- [ ] **Step 1: Tests** — `stays_light_on_brief_shadow` ; `goes_dark_after_hold` ; `returns_light_with_hysteresis` (entre 15 et 40 lux, l'état ne change pas).
- [ ] **Step 2:** échec. **Step 3:** implémenter. **Step 4:** build + tests verts ; APK debug produit dans `mobile/app/build/outputs/apk/debug/`.
- [ ] **Step 5: Commit** `feat(mobile): thème automatique et réglages`.
