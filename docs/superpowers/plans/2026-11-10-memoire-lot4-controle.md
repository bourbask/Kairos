# Mémoire — lot 4 : le contrôle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dans l'espace Mémoire de l'application, l'utilisateur voit ce que Kairos retient toujours, les habitudes apprises et ce qui s'estompe ; il pose ou retire une ancre, confirme, rejette ou oublie une habitude, et ravive d'un geste un souvenir qui s'estompe.

**Architecture:** Le module mémoire expose quatre outils `app_*` (une lecture `app_souvenirs`, trois gestes) et marque les ancres dans `app_memory` ; la tête les relaie par son passe-plat existant, sans changement. La lecture ne renforce rien ; raviver est un signal réel de l'utilisateur (§3.4) et passe par le renforcement existant. L'application ajoute une section « Ce que je retiens » à l'espace Mémoire, gardée en mémoire vive seulement, et réutilise le geste avec fenêtre d'annulation (`completeCard`).

**Tech Stack:** Rust 2024, SQLite (rusqlite), rmcp ; crate `kairos-memoire`. Kotlin/Compose, kotlinx.serialization, JUnit (`mobile/`).

**Spec:** `docs/superpowers/specs/2026-11-09-memoire-humaine-design.md` (§3.10 ; §3.3, §3.4, §3.7, §3.8 pour les règles touchées ; lot 4 du §6 ; §7). Lots 1 à 3 livrés côté mémoire : `souvenirs`, `renforcer_souvenir`, `ancrer`, `rappel` (`ANCRES_MAX` = 20), `oublier`, `condenser` (lots recalculés à l'appel), table `habitudes`, `habitudes::repondre`, `habitude_oublier`, `app_habitude_forget`. Plans de référence : `docs/superpowers/plans/2026-11-10-memoire-lot3-habitudes.md`, `docs/superpowers/plans/2026-11-08-app-espace-memoire.md`.

## Global Constraints

- **Lire ne renforce jamais** : `app_souvenirs` et `app_memory` ne modifient aucune ligne de `souvenirs` (ni `dernier_rappel`, ni `rappels`, ni `stabilite`).
- **Raviver = réactivation** (§3.4) : `stabilite ← min(S_max, stabilite × (1 + k × (1 − R)))`, `dernier_rappel ← maintenant`, `rappels + 1`, par `renforcer_souvenir` existant. Natures ravivables : `fait`, `deduction`, `journal`, `conversation`, `synthese`. Une habitude ne se ravive pas (sa force est `m`) : elle se confirme.
- **Ancre** (« retiens toujours ») : natures ancrables `fait`, `journal`, `conversation`, `synthese` ; jamais `deduction` (§3.8 : un souvenir déduit n'est jamais une ancre) ni `habitude`. Au plus **20** ancres en tout (`ANCRES_MAX`, celles que le rappel fournit), synthèses d'année comprises ; une 21ᵉ est refusée avec « 20 souvenirs au plus sont toujours retenus : retire d'abord une ancre. ». Une synthèse d'année reste ancrée (« Ce souvenir est toujours retenu. »). Retirer une ancre fait repartir le souvenir de maintenant (`dernier_rappel ← maintenant`, stabilité et `rappels` inchangés) : il ne bascule ni dans « s'estompe » ni dans la condensation de la nuit suivante.
- **Estompé** : `R < 0,5` sur la courbe d'oubli, non ancré ; instant d'estompage `dernier_rappel + stabilite × ln 2` (calculé, jamais stocké). Habitudes : section à part, estompée si `m < 0,3`.
- **Habitude, confirmer ou rejeter depuis l'application** : même effet que la réponse à sa question (`habitudes::repondre`) ; si la question est encore `proposee`, elle est close dans la même transaction (`acceptee` / `refusee`) ; sinon l'effet s'applique directement. Le dernier geste l'emporte, d'où qu'il vienne ; une question close n'est jamais rouverte. Seules les habitudes nées (`nee_le` non nul) sont listées et modifiables.
- **Listes** : `estompes` au plus **100** (les plus récemment estompés d'abord), `estompes_total` donne le nombre réel ; `habitudes` au plus 100 ; textes tronqués à 300 caractères (`TEXTE_MAX`).
- **Confidentialité** : rien que l'utilisateur ne voie déjà de sa propre mémoire. Entrée de journal non `commune` → `"sensitive": true`, masquée par défaut dans l'application comme le journal. La section « Ce que je retiens » n'est **jamais écrite sur l'appareil** (mémoire vive seulement) ; `FLAG_SECURE` et le masquage existants s'appliquent. Aucun texte de souvenir dans les journaux du serveur.
- **Gestes** : bouton visible pour chaque geste, fenêtre d'annulation existante (`completeCard`, `notifier.undo`), un second geste sur le même élément ignoré (`Pending` du dépôt). Refus du module (422) : message affiché, élément rendu, liste relue.
- Tête : aucun changement (passe-plat `app_*` et `is_app_tool` existants).
- Dépôt public : commentaires d'intention en français, exemples fictifs (`cafe.prefere`, « Exemple »). `git commit --no-gpg-sign`. Vérification : `cargo test -p kairos-memoire` puis `cargo test --workspace` ; dans `mobile/` : `./gradlew --no-daemon testDebugUnitTest assembleDebug`.

## Review Focus

1. Une question d'habitude reçoit une réponse en conversation et une autre dans l'application : le dernier geste l'emporte, la question n'est pas rouverte, la carte d'intention devenue sans objet s'efface en `stale` (Task 3 : `habit_answer_last_gesture_wins`).
2. Raviver ou ancrer un souvenir effacé entre-temps (condensé ou oublié la nuit, effacé depuis un autre appareil) : refus clair, rien n'est recréé, la liste est relue (Task 2 : `revive_or_anchor_erased_is_refused` ; Task 5 : `refused_gesture_restores_item`).
3. Raviver un épisode entre la lecture des lots de nuit et la condensation : l'épisode n'est pas supprimé (Task 2 : `revive_blocks_pending_condensation`).
4. 21ᵉ ancre, ancre posée deux fois, synthèses d'année : le plafond tient sans compter deux fois (Task 2 : `anchor_cap_is_twenty`).
5. Hors ligne, délai dépassé, module plus ancien : la section garde sa dernière liste avec la raison, rien n'est écrit sur l'appareil, un geste refusé rend l'élément (Task 5 : `controle_keeps_last_on_failure`, `refused_gesture_restores_item` ; Task 6 : étape émulateur).

---

### Task 1: Lecture de contrôle (sans renforcement)

**Files:** Modify `tentacles/memoire/src/store.rs` (extraire la lecture de `rappel`, déclarer `mod controle;`). Create `tentacles/memoire/src/store/controle.rs` (sous-module, tests dans ce fichier).

**Interfaces:**
- Produces:
  - Dans `store.rs`, `fn lire_souvenirs(&self, episodes: &str) -> Result<Vec<Lu>>` : l'union SQL et la mise en texte de `rappel`, déplacées telles quelles ; `episodes` remplace la clause de sélection des épisodes (`rappel` passe sa clause actuelle « ancrés, récents ou trouvés », le contrôle passe `"1"`). L'union gagne deux colonnes : `sensible` (journal dont `sensitivity <> 'commune'`, 0 sinon) et `devenir` (`'condensation'` pour journal, conversation, synthèse non fondue ; `'oubli'` pour déduction et synthèse fondue non annuelle ; `NULL` sinon). `struct Lu { nature, r#ref, texte, stabilite: f64, dernier: NaiveDateTime, importance: f64, ancre: bool, force: Option<f64>, sensible: bool, devenir: Option<String> }`. `rappel` garde exactement son comportement.
  - `#[derive(Debug, Clone, PartialEq, Serialize)] pub struct SouvenirVu { pub nature: String, pub r#ref: String, pub texte: String, pub sensitive: bool, pub depuis: Option<NaiveDate>, pub devenir: Option<String>, pub protege: bool }` (`r#ref` sérialisé `"ref"`, `depuis` = jour d'estompage, `protege` = synthèse d'année).
  - `#[derive(Debug, Clone, PartialEq, Serialize)] pub struct HabitudeVue { pub id: i64, pub libelle: String, pub etat: String, pub force: f64, pub confirmee: bool, pub rejetee: bool, pub estompee: bool, pub ancienne_depuis: Option<String> }`.
  - `#[derive(Debug, Clone, PartialEq, Serialize)] pub struct Controle { pub ancres_max: usize, pub ancres: Vec<SouvenirVu>, pub estompes: Vec<SouvenirVu>, pub estompes_total: usize, pub habitudes: Vec<HabitudeVue> }`.
  - `pub fn controle(&self, maintenant: NaiveDateTime) -> Result<Controle>` : `ancres` = lignes ancrées (hors habitudes), importance puis récence ; `estompes` = non ancrées, nature ≠ `habitude`, `R < 0,5`, instant d'estompage décroissant, 100 au plus ; `habitudes` = nées (rejetées comprises), `active` puis `ancienne`, `m` décroissant, 100 au plus. Ligne illisible : ignorée.
  - `pub fn ancres_app(&self) -> Result<BTreeSet<(String, String)>>` : `(nature, ref)` des ancres, pour `app_memory`.
  - Constante `CONTROLE_MAX: usize = 100`.

- [ ] **Step 1: Tests** (dans `store/controle.rs` ; souvenirs vieillis par `fact_set_at(…, t() − Δ)` ou `UPDATE souvenirs SET dernier_rappel`) :
  - `controle_lists_anchors_faded_and_habits` : fait `cafe.prefere` ancré → dans `ancres`, absent d'`estompes` ; fait `ville.exemple` vu il y a 200 j (S = 90) → dans `estompes`, `depuis == (dernier_rappel + 62,383… j).date()`, `devenir == None` ; fait frais absent des deux ; déduction estompée → `texte` commence par `"je crois que "`, `devenir == Some("oubli")` ; entrée de journal estompée → `devenir == Some("condensation")` ; entrée `Strategique` estompée → `sensitive == true` ; habitude née active → `etat == "active"`, `force` = son `m` ; candidate absente ; rejetée présente avec `rejetee == true` ; `ancres_max == 20`.
  - `controle_never_reinforces` : `force(…)` d'un fait et d'une entrée de journal identiques avant et après deux appels à `controle` et un à `ancres_app`.
  - `controle_caps_faded_list` : 120 faits estompés à des dates distinctes → `estompes.len() == 100`, `estompes_total == 120`, le premier est le plus récemment estompé.
  - `year_synthesis_is_protected_anchor` : synthèse `annee` → dans `ancres` avec `protege == true`.
- [ ] **Step 2:** `cargo test -p kairos-memoire controle` → échec (fonction absente).
- [ ] **Step 3:** Extraire `lire_souvenirs`, puis implémenter `controle` et `ancres_app`.
- [ ] **Step 4:** `cargo test -p kairos-memoire` vert (les tests de `rappel` existants inchangés).
- [ ] **Step 5:** Commit `feat(memoire): lecture des ancres, souvenirs estompés et habitudes`.

### Task 2: Ancrer et raviver

**Files:** Modify `tentacles/memoire/src/store/controle.rs`.

**Interfaces:**
- Consumes: Task 1 (`controle`).
- Produces:
  - `#[derive(Debug, Clone, Copy, PartialEq, Eq)] pub enum Ancrage { Fait, Inconnu, Plein, NonAncrable, Protege }`.
  - `pub fn marquer_ancre(&self, nature: &str, r#ref: &str, ancre: bool, maintenant: NaiveDateTime) -> Result<Ancrage>` (une transaction) : nature hors des ancrables → `NonAncrable` ; ligne absente → `Inconnu` ; retrait sur une synthèse d'année → `Protege` ; pose sur un souvenir non ancré alors que `COUNT(ancre = 1) >= ANCRES_MAX` → `Plein` ; pose sur un souvenir déjà ancré → `Fait` sans rien changer ; retrait → `ancre = 0`, `dernier_rappel = maintenant`. `ancrer` (brut) reste pour les usages internes et les tests existants.
  - `pub fn raviver(&self, nature: &str, r#ref: &str, maintenant: NaiveDateTime) -> Result<bool>` : `false` pour `habitude` ou un souvenir absent ; sinon `renforcer_souvenir`.

- [ ] **Step 1: Tests :**
  - `anchor_cap_is_twenty` : 20 faits → `Fait` ×20 ; 21ᵉ → `Plein`, `force(…).ancre == false` ; un des 20 reposé → `Fait` ; une synthèse d'année existante compte (19 faits + 1 année → 20ᵉ fait `Plein`) ; un retrait → le 21ᵉ passe.
  - `unanchor_restarts_from_now` : fait vu il y a 400 j, ancré, puis retiré à `t()` → `dernier_rappel == t()`, `stabilite` et `rappels` inchangés, absent de `controle(t()).estompes`.
  - `deduction_and_habit_not_anchorable` : `NonAncrable` pour `deduction` et `habitude` ; `Protege` au retrait d'une synthèse d'année, toujours ancrée.
  - `revive_reinforces_with_spacing` : fait S = 90, vu il y a 90 j → `raviver` vrai ; `stabilite` = `90 × (1 + 2 × (1 − e⁻¹))` (≈ 203,7817, à 1e-9), `rappels + 1`, `dernier_rappel == t()` ; absent de `controle(t()).estompes`.
  - `revive_or_anchor_erased_is_refused` : après `fact_forget` → `raviver == false`, `marquer_ancre == Inconnu`, aucune ligne `souvenirs` recréée ; `raviver("habitude", id)` → `false`.
  - `revive_blocks_pending_condensation` : semaine fictive condensable (helpers du lot 2 : `store_extrait`, épisodes vieillis) ; lire `condensables`, raviver un épisode, `condenser(…, empreinte)` → `None`, épisodes toujours présents.
  - `revived_deduction_survives_forget` : déduction à `R < 0,05` ravivée → `oublier` la garde.
- [ ] **Step 2:** `cargo test -p kairos-memoire controle` → échecs attendus.
- [ ] **Step 3:** Implémenter `marquer_ancre` et `raviver`.
- [ ] **Step 4:** `cargo test -p kairos-memoire` vert.
- [ ] **Step 5:** Commit `feat(memoire): ancrer, désancrer et raviver un souvenir`.

### Task 3: Confirmer ou rejeter une habitude depuis l'application

**Files:** Modify `tentacles/memoire/src/store/habitudes.rs`.

**Interfaces:**
- Produces:
  - `repondre` généralisé en `fn repondre_ou(c: &Connection, filtre: &str, p: impl rusqlite::Params + Copy, state: IntentionState, maintenant: NaiveDateTime) -> Result<()>` (même logique, clause sur `habitudes` comme `effacer`) ; `repondre(c, question, …)` l'appelle avec `"question = ?"`.
  - `pub fn habitude_repondre(&self, id: i64, confirmer: bool, maintenant: NaiveDateTime) -> Result<bool>` (une transaction) : `false` si l'habitude est inconnue ou pas née ; si sa question est `proposee`, l'intention passe à `acceptee` / `refusee` ; puis `repondre_ou(…, "id = ?", …)`.

- [ ] **Step 1: Tests :**
  - `habit_set_closes_open_question` : habitude née (helpers `serie` du lot 3), `habitude_repondre(id, true)` → `confirmee == 1`, confiance du souvenir 1, question absente de `intentions_open`, état `acceptee`.
  - `habit_answer_last_gesture_wins` : `intention_set_state(question, Refusee)` puis `habitude_repondre(id, true)` → `confirmee == 1`, `rejetee == 0`, souvenir recréé, question toujours `refusee` ; puis `habitude_repondre(id, false)` → `rejetee == 1`, souvenir absent ; aucune nouvelle intention.
  - `habit_reject_after_question_expired` : question échue → `habitude_repondre(id, false)` vrai, `rejetee == 1`, question toujours `expiree`.
  - `habit_set_unknown_or_unborn` : candidate → `false` ; identifiant inconnu → `false`.
- [ ] **Step 2:** `cargo test -p kairos-memoire habitudes` → échecs attendus.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `cargo test -p kairos-memoire` vert (tests du lot 3 inchangés).
- [ ] **Step 5:** Commit `feat(memoire): confirmer ou rejeter une habitude depuis l'application`.

### Task 4: Outils de l'application

**Files:** Modify `tentacles/memoire/src/tools.rs` (outils et tests du module `tests`).

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces (contrat relu par l'application, Task 5) :

| Outil | Arguments | Résultat |
|---|---|---|
| `app_souvenirs` | — | `Controle` en JSON : `{"ancres_max","ancres":[S],"estompes":[S],"estompes_total","habitudes":[{"id","libelle","etat","force","confirmee","rejetee","estompee","ancienne_depuis"}]}` ; `S` = `{"nature","ref","texte","sensitive","depuis","devenir","protege"}` |
| `app_souvenir_anchor` | `{"nature","ref","ancre"}` | `{"message"}` : « Souvenir toujours retenu. » / « Ancre retirée. » ; erreurs : `Inconnu` → « souvenir inconnu ou déjà effacé », `Plein` → message des Global Constraints, `NonAncrable` → « ce souvenir ne peut pas être ancré », `Protege` → « Ce souvenir est toujours retenu. » |
| `app_souvenir_revive` | `{"nature","ref"}` | `{"message": "Souvenir ravivé."}` ; `habitude` → erreur « une habitude se confirme, elle ne se ravive pas » ; `false` → « souvenir inconnu ou déjà effacé » |
| `app_habitude_set` | `{"id","confirmee"}` | `{"message"}` : « Habitude confirmée. » / « Habitude rejetée. » ; `false` → « habitude inconnue » |
| `app_memory` | — | inchangé, plus `"anchored": bool` sur chaque fait et chaque entrée de journal (`ancres_app`) |

  - `fact_anchor` (moteur) passe par `marquer_ancre` : messages actuels gardés ; `Plein` → message des Global Constraints ; clé persona toujours refusée. `app_souvenir_anchor` refuse aussi la clé persona (`is_persona`) quand `nature == "fait"`.
- [ ] **Step 1: Tests :**
  - `app_souvenirs_json_shape` : toutes les clés ci-dessus présentes, `ancres_max == 20`, `ref` (pas `r#ref`).
  - `app_control_tools_refuse_clearly` : persona → `Err` ; 21ᵉ ancre → `Err` contenant `"20"` ; `app_souvenir_revive` sur un fait inconnu → `Err` contenant `"inconnu"` ; sur `habitude` → `Err` ; `app_habitude_set` inconnu → `Err("habitude inconnue")`.
  - `app_memory_marks_anchored` : fait et entrée de journal ancrés → `"anchored": true`, les autres `false`.
  - `fact_anchor_respects_cap` : 20 ancres posées, `fact_anchor` sur un 21ᵉ fait → message contenant `"20"`, fait non ancré.
- [ ] **Step 2:** `cargo test -p kairos-memoire` → échecs attendus.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `cargo test -p kairos-memoire` puis `cargo test --workspace` verts.
- [ ] **Step 5:** Commit `feat(memoire): outils de contrôle pour l'application`.

### Task 5: Modèles et logique de la section (application)

**Files:** Modify `mobile/app/src/main/java/app/kairos/mobile/data/Models.kt`. Create `mobile/app/src/main/java/app/kairos/mobile/ui/memoire/ControleState.kt`, `mobile/app/src/test/java/app/kairos/mobile/ui/memoire/ControleStateTest.kt`.

**Interfaces:**
- Consumes: contrat de la Task 4.
- Produces:
  - `Models.kt` : `Fact(…, val anchored: Boolean = false)`, `JournalEntry(…, val anchored: Boolean = false)` ; `@Serializable data class Souvenir(val nature: String, val ref: String, val texte: String = "", val sensitive: Boolean = false, val depuis: String? = null, val devenir: String? = null, val protege: Boolean = false)` ; `@Serializable data class Habitude(val id: Long, val libelle: String = "", val etat: String = "", val force: Double = 0.0, val confirmee: Boolean = false, val rejetee: Boolean = false, val estompee: Boolean = false, @SerialName("ancienne_depuis") val ancienneDepuis: String? = null)` ; `@Serializable data class Controle(@SerialName("ancres_max") val ancresMax: Int = 20, val ancres: List<Souvenir> = emptyList(), val estompes: List<Souvenir> = emptyList(), @SerialName("estompes_total") val estompesTotal: Int = 0, val habitudes: List<Habitude> = emptyList())`.
  - `ControleState.kt` (logique pure, sans Compose ni réseau) :
    - `data class ControleView(val data: Controle? = null, val issue: String? = null)` ; `fun controleAfter(prev: ControleView, r: Result<Controle>): ControleView` (succès : remplace ; échec : garde `data`, `issue = issueOf(e)`).
    - `fun anchorCard(nature: String, ref: String, ancre: Boolean): Card` (id `"ancre/$nature/$ref"`, outil `app_souvenir_anchor`) ; `fun reviveCard(s: Souvenir): Card` (id `"raviver/${s.nature}/${s.ref}"`, `app_souvenir_revive`) ; `fun habitCard(h: Habitude, confirm: Boolean): Card` (id `"habitude/${h.id}"`, `app_habitude_set`, `confirmee`) ; `fun habitErasure(id: Long): Card` (id `"habitude/$id"`, `app_habitude_forget`). Module `MEMOIRE`.
    - `fun withoutControl(c: Controle, card: Card): Controle` : selon l'outil — ravivé : retiré d'`estompes` (`estompesTotal − 1`) ; ancre posée : retiré d'`estompes` (la liste `ancres` vient de la relecture) ; ancre retirée : retiré d'`ancres` ; habitude confirmée / rejetée : drapeaux mis à jour ; habitude oubliée : retirée.
    - `fun anchorsTitle(c: Controle): String` = `"Toujours retenu · ${c.ancres.size}/${c.ancresMax}"`.
    - `fun fadedHint(s: Souvenir): String` : `"Estompé depuis le JJ/MM/AAAA"` + `" · sera résumé"` (`condensation`) ou `" · sera oublié"` (`oubli`) ; sans date lisible : `"Estompé"`.
    - `fun habitStatus(h: Habitude): String` : rejetée → `"rejetée"` ; sinon, joints par `" · "` : `"active"` ou `"ancienne depuis le JJ/MM/AAAA"`, `"s'estompe"` si `estompee`, `"confirmée"` ou `"à confirmer"`.
    - `fun controlText(s: Souvenir, revealed: Set<String>, masked: Boolean): String?` : même règle que `shownText`, clé `"${s.nature}/${s.ref}"`.
    - `fun natureLabel(n: String): String` : `fait` « Fait », `journal` « Journal », `conversation` « Conversation », `synthese` « Résumé », `deduction` « Déduction », sinon `n`.
- [ ] **Step 1: Tests** (`ControleStateTest`) :
  - `controle_parses_module_json` : JSON du contrat (valeurs fictives) décodé par `KairosJson` ; champs absents → valeurs par défaut ; `Fact` sans `anchored` → `false`.
  - `controle_keeps_last_on_failure` : succès puis `Result.failure(ApiError.Offline)` → `data` gardée, `issue == "Hors ligne"` ; `ApiError.Server(404, "x", bare = true)` → `issue == ApiError.Server.OUTDATED`.
  - `gestures_target_app_tools` : chaque fabrique rend l'outil et les arguments attendus (`{"nature","ref","ancre"}`, `{"nature","ref"}`, `{"id","confirmee"}`, `{"id"}`).
  - `optimistic_updates` : `withoutControl` pour les cinq gestes (dont `estompesTotal` décrémenté).
  - `labels` : `anchorsTitle` → `"Toujours retenu · 3/20"` ; `fadedHint` avec `depuis = "2026-11-03"` et `devenir = "condensation"` → `"Estompé depuis le 03/11/2026 · sera résumé"` ; `habitStatus` d'une ancienne non confirmée estompée → `"ancienne depuis le 01/11/2026 · s'estompe · à confirmer"` ; rejetée → `"rejetée"`.
  - `sensitive_faded_hidden_by_default` : `controlText` masqué par défaut, révélé par sa clé, toujours masqué si `masked`.
  - `refused_gesture_restores_item` : sur le modèle de `refused_intention_removed_with_message`, `completeCard(reviveCard(s), …)` avec un `call` qui lève `ApiError.Server(422, "souvenir inconnu ou déjà effacé")` → `DoneOutcome.Refused` portant ce message, l'élément n'est plus caché ; avec `ApiError.Offline` → `Refused("Serveur injoignable : rien n'a été enregistré.")` ; annulé dans la fenêtre → aucun appel.
- [ ] **Step 2:** `./gradlew --no-daemon testDebugUnitTest --tests '*ControleStateTest*'` → échec de compilation.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `./gradlew --no-daemon testDebugUnitTest` vert.
- [ ] **Step 5:** Commit `feat(mobile): modèles et logique du contrôle de la mémoire`.

### Task 6: Section « Ce que je retiens » de l'espace Mémoire

**Files:** Modify `mobile/app/src/main/java/app/kairos/mobile/data/Repository.kt` (`controlPending`), `mobile/app/src/main/java/app/kairos/mobile/ui/memoire/MemoireScreen.kt` (chargement, gestes, épingle du journal, interrupteur du panneau de fait). Create `mobile/app/src/main/java/app/kairos/mobile/ui/memoire/ControleSection.kt`. 

**Interfaces:**
- Consumes: Task 5.
- Produces:
  - `Repository.controlPending = Pending<String>()` (survit à l'écran, comme `erasuresPending`).
  - Dans `MemoireScreen` : `var control by remember { mutableStateOf(ControleView()) }` (jamais écrit sur l'appareil, `ponytail:` le dit) ; chargé par `repository.lookup(MEMOIRE, "app_souvenirs", Controle.serializer(), JsonObject(emptyMap()))` à la reprise de l'écran, au tirer-pour-actualiser et après chaque geste abouti ou refusé, à travers `controleAfter`. `ApiError.Unauthorized` → `onUnauthorized`.
  - `fun control(card: Card, label: String)` dans l'écran : depuis `appScope`, `completeCard(card, null, mark = { controlPending.hide(card.id, it != null) }, undoWindow = { notifier.undo(label) }, call = repository::reply, busy = controlPending.busy)` ; `Done` → `control = control.copy(data = withoutControl(…))`, puis relecture de `app_souvenirs` et `refreshMemoire()` ; `Refused` → `notifier.show(message)`, relecture.
  - `@Composable fun ControleSection(view: ControleView, hidden: Set<String>, revealed: Set<String>, masked: Boolean, onReveal: (String) -> Unit, onAnchor: (Souvenir, Boolean) -> Unit, onRevive: (Souvenir) -> Unit, onHabit: (Habitude, Boolean) -> Unit, onForgetHabit: (Habitude) -> Unit, onRetry: () -> Unit)` dans `ControleSection.kt`, placée après les faits : trois `FoldHeader` repliés par défaut — `anchorsTitle` (chaque ligne : nature, texte, bouton « Ne plus retenir », absent si `protege`), « Habitudes apprises » (libellé, `habitStatus`, boutons « Confirmer » / « Rejeter » selon l'état, « Oublier »), « S'estompe » (`estompesTotal`, nature, texte, `fadedHint`, boutons « Raviver » et « Retenir toujours »). `view.data == null` : `Caption` « Chargement… » ou la raison avec « Réessayer » ; `issue` avec données : raison en tête de section, liste gardée. Plus de 100 estompés : ligne « 100 sur N, les plus récents ». Libellés d'annulation : « Ravivé · … », « Toujours retenu · … », « Ancre retirée · … », « Habitude confirmée · … », « Habitude rejetée · … », « Habitude oubliée · … ».
  - `FactPanel` : interrupteur « Retenir toujours » (`fact.anchored`), via `control(anchorCard("fait", key, !anchored), …)`. `EntryRow` du journal : bouton épingle (`anchored`, description accessible « Retenir toujours » / « Ne plus retenir »), même chemin.
La logique est testée à la Task 5 ; cette tâche n'est que du câblage Compose, vérifié par la compilation et à l'émulateur.

- [ ] **Step 1:** Implémenter la section, le chargement, les gestes, l'épingle et l'interrupteur.
- [ ] **Step 2:** `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.
- [ ] **Step 3:** Sur l'émulateur avec le faux serveur (réglage d'écran sécurisé désactivé pour la capture) : section repliée par défaut ; « Raviver » → annulation proposée, puis l'élément quitte « S'estompe » ; 21ᵉ ancre → message du module, élément rendu ; mode avion → raison affichée, liste gardée ; relance de l'application hors ligne → section vide avec « Réessayer » (rien sur l'appareil).
- [ ] **Step 4:** Commit `feat(mobile): ancres, habitudes et souvenirs estompés dans l'espace Mémoire`.

**À la livraison :** rien à configurer. Déployer le module mémoire avant l'application (une application plus récente face à un module ancien affiche « Module indisponible » dans la section, le reste de l'espace fonctionne). Mesures de la période d'essai (§6) : ancres posées, souvenirs ravivés, habitudes confirmées ou rejetées depuis l'application — `SELECT ancre, COUNT(*) FROM souvenirs GROUP BY 1` et la requête du lot 3.
