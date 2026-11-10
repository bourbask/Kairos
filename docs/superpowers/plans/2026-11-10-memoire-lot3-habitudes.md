# Mémoire — lot 3 : les habitudes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chaque nuit, Kairos apprend les habitudes de l'utilisateur à partir des événements structurés déjà notés par les modules, voit qu'une habitude change, laisse l'ancienne s'estomper ou revenir vite, et dit une fois ce qu'il a remarqué pour que l'utilisateur confirme ou rejette.

**Architecture:** Chaque module source expose un outil caché `nuit_observations {jour}` qui rend, dans son propre vocabulaire fermé, les observations déjà contextualisées d'une journée. Le planificateur de la tête collecte ces observations jour par jour (outil conventionnel, tous modules confondus) et les remet au module mémoire par un outil caché `nuit_habitudes`, qui applique le comptage `m ← m + a(x − m)` de façon déterministe et idempotente, gère naissance, changement, retour et annonce, et possède la table des habitudes et leurs souvenirs (famille Habitude). Aucun modèle de langue ; la mémoire ne connaît aucun vocabulaire de module, la tête non plus.

**Tech Stack:** Rust 2024, SQLite (rusqlite), rmcp ; crates `kairos-memoire`, `kairos-alimentation`, `tete`.

**Spec:** `docs/superpowers/specs/2026-11-09-memoire-humaine-design.md` (§3.7 ; valeurs du §5 ; lot 3 du §6 ; risques du §7). Lots 1 et 2 livrés : `tentacles/memoire/src/force.rs` (`Famille::Habitude`, `S₀` 14 j), table `souvenirs`, `rappel`, étapes de nuit (`nuit_etat`, `nuit_noter`, `memory_night` dans `tete/src/jobs.rs`), questions « Confirmer : … » (intentions `proposee`, durée `QUESTION_JOURS` = 7). Plan de référence : `docs/superpowers/plans/2026-11-09-memoire-lot2-sommeil.md`.

## Sources retenues

| Source | Retenue | Raison |
|---|---|---|
| Repas notés (alimentation, `meals`) | **oui** : midi et soir | un repas par jour et par créneau (`add_meal_once`), lieu connu ; un créneau non noté = aucune observation (inconnu, pas « rien ») |
| Heure des repas | non | aucune heure de repas enregistrée (`at` = heure de saisie) |
| Petit-déjeuner, goûter | non | plusieurs repas possibles par créneau : pas de valeur unique par contexte |
| Agenda (événements) | non | récurrences non dépliées et aucune date de dernière synchronisation conservée : un créneau « libre » n'est pas fiable ; à reprendre quand les deux existent |
| Tâches (agenda) | non | signal pauvre (faite ou non, par jour) ; à reprendre si l'usage le demande |
| Rythme de travail (tête) | non | planning déclaré par jour de semaine, aucun relevé daté de ce qui s'est passé |
| Gestes dans l'application | non | aucun journal de gestes |

Ajouter une source plus tard = exposer `nuit_observations` dans son module, sans toucher ni la mémoire ni la tête.

## Global Constraints

- Calcul **sans modèle de langue**, déterministe ; aucune donnée de module ne quitte l'hôte.
- Habitude = `(source, quoi, contexte, valeur)` ; codes `quoi`, `contexte`, `valeur` en vocabulaire fermé propre à la source (`[a-z0-9._-]`, 1 à 64 caractères) ; `libelle` lisible fourni par la source (1 à 120 caractères, sans caractère de contrôle) ; `cadence` ∈ {`quotidien`, `hebdomadaire`}.
- Contexte = créneau × type de jour : `<creneau>.<ouvre|weekend>` pour une source quotidienne, `<creneau>.<lundi…dimanche>` pour une source hebdomadaire. Jour ouvré = lundi à vendredi (jours fériés ignorés).
- Comptage : à chaque occurrence d'un contexte (un jour où la source l'observe), chaque valeur connue de ce contexte reçoit `x = 1` si observée, `0` sinon : `m ← m + a × (x − m)` ; `m` part de 0 ; `a` = **0,1** quotidien, **0,25** hebdomadaire ; une valeur jamais vue est créée avec `m = a`, `positifs = 1`.
- Retour (« réapprentissage rapide ») : une observation positive d'une habitude `ancienne` utilise `2 × a`.
- Naissance : `m ≥ 0,5` **et** au moins **3** observations positives. Une habitude née devient `active` quand elle est la valeur non rejetée de plus grand `m` du contexte, avec `m ≥ 0,5` et `m` strictement supérieur à celui de l'active courante ; l'active courante devient `ancienne` avec `ancienne_depuis` = jour observé. Au plus une `active` non rejetée par `(source, quoi, contexte)`.
- Force d'une habitude = `m` (pas la courbe d'oubli) : active `m ≥ 0,5` ; estompée `m < 0,3` ; oubliée (suppression réelle) `m < 0,05` si non confirmée. Une habitude confirmée n'est jamais oubliée automatiquement.
- Annonce : une seule fois par habitude, à sa naissance : intention `proposee` « Confirmer : habitude — <libellé> ? » (« … (avant : <libellé de l'ancienne>) ? » si elle en remplace une), durée 7 jours ; un retour n'est jamais réannoncé. Acceptée → `confirmee`, confiance 1. Refusée → `rejetee` : plus rappelée, plus jamais réannoncée, son comptage continue jusqu'à l'oubli.
- Idempotence par `(source, jour)` : un jour déjà appliqué pour une source ne compte plus.
- **Aucun trou de mémoire** : étape de nuit `habitudes` dans `nuit_etat` ; jours traités depuis le dernier jour traité jusqu'à l'avant-veille (un jour de grâce pour un repas noté le lendemain ; l'avant-veille seule au premier passage, pas de reprise d'historique), au plus 7 par nuit, plus anciens d'abord ; un jour où une source attendue (déjà connue, ou exposant `nuit_observations`) n'a pas répondu reste en attente (statut `source_indisponible`) et apparaît dans le briefing du matin ; une source muette depuis plus de 14 nuits réelles (`vu_le`) est retirée des sources connues (ses habitudes restent, figées).
- Outils `nuit_*` et `app_*` cachés au moteur (règle existante de `is_app_tool`). Aucun libellé ni valeur d'habitude dans les journaux.
- Dépôt public : commentaires d'intention, exemples fictifs (valeurs `maison`, `bureau`, `restaurant`, `ailleurs`). `git commit --no-gpg-sign`. Vérification : `cargo test -p kairos-memoire -p kairos-alimentation -p tete` puis `cargo test --workspace`.

## Review Focus

1. Source injoignable ou réponse illisible pendant la nuit : la journée n'est pas comptée comme « rien observé », elle attend et le briefing le dit (Task 1 : `missing_known_source_holds_day` ; Task 6 : `missing_source_holds_the_day`).
2. Nuit relancée ou jour rejoué : aucun double comptage, aucune question en double (Task 1 : `same_day_twice_counts_once` ; Task 2 : `birth_asks_once` ; Task 6 : `habits_night_twice_is_harmless`).
3. Habitude rejetée : jamais réannoncée ni rappelée, même si le comportement continue (Task 2 : `rejected_habit_never_asked_again`).
4. Habitude confirmée puis abandonnée : estompée, jamais supprimée par la nuit (Task 3 : `confirmed_habit_survives_forget`).
5. Repas non noté : ce n'est pas un repas sauté ni un repas ailleurs (Task 5 : `unlogged_slot_is_no_observation`).

---

### Task 1: Comptage des observations

**Files:** Create `tentacles/memoire/src/store/habitudes.rs` (déclaré `mod habitudes;` dans `tentacles/memoire/src/store.rs` : sous-module, accès à `conn` ; tests dans ce fichier). Modify `tentacles/memoire/src/store.rs` (`SCHEMA`).

**Interfaces:**
- Produces:
  - Tables :
    `habitudes(id INTEGER PRIMARY KEY, source TEXT NOT NULL, quoi TEXT NOT NULL, contexte TEXT NOT NULL, valeur TEXT NOT NULL, libelle TEXT NOT NULL, cadence TEXT NOT NULL CHECK(cadence IN ('quotidien','hebdomadaire')), m REAL NOT NULL, positifs INTEGER NOT NULL, etat TEXT NOT NULL DEFAULT 'candidate' CHECK(etat IN ('candidate','active','ancienne')), nee_le TEXT, ancienne_depuis TEXT, question INTEGER, annoncee_le TEXT, confirmee INTEGER NOT NULL DEFAULT 0, rejetee INTEGER NOT NULL DEFAULT 0, UNIQUE(source, quoi, contexte, valeur))` ;
    `habitudes_sources(source TEXT PRIMARY KEY, dernier_jour TEXT NOT NULL)`.
  - `#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)] pub struct Observation { pub quoi: String, pub contexte: String, pub valeur: String, pub libelle: String, pub cadence: String }`.
  - `#[derive(Debug, Clone, Default, PartialEq, Serialize)] pub struct BilanHabitudes { pub complet: bool, pub appliquees: u32, pub nees: u32, pub changements: u32, pub retours: u32, pub ignorees: u32 }`.
  - `pub fn appliquer_observations(&self, jour: NaiveDate, sources: &BTreeMap<String, Vec<Observation>>, maintenant: NaiveDateTime) -> Result<BilanHabitudes>` — une transaction. Par source présente : ignorée en entier si `jour <= habitudes_sources.dernier_jour` ; sinon chaque observation valide met à jour son contexte (règle de comptage des Global Constraints, `libelle` rafraîchi), puis `dernier_jour = jour`. Observation invalide, doublon `(quoi, contexte)` dans la même journée, ou au-delà de 50 par source → `ignorees += 1`, le reste s'applique. Nom de source vide ou de plus de 64 caractères → source ignorée. `complet` = toute source connue est présente, sauf celles dont `dernier_jour < jour − 14 j`, retirées de `habitudes_sources` au passage.
  - Constantes : `A_QUOTIDIEN = 0.1`, `A_HEBDO = 0.25`, `OBSERVATIONS_MAX = 50`, `SOURCE_ABSENTE_JOURS = 14`.

- [ ] **Step 1: Tests** (dans `store/habitudes.rs`, fabrique `obs(contexte, valeur)` avec `quoi = "repas.lieu"`, `cadence = "quotidien"`, `libelle` fictif) :
  - `daily_counting_matches_spec` : 7 jours de `maison` sur `soir.ouvre` → `m` = `1 − 0.9^7` (≈ 0,5217, à 1e-9) et `positifs == 7` ; puis un jour `restaurant` → `maison.m` = `0.9 × (1 − 0.9^7)`, `restaurant.m == 0.1`.
  - `weekly_counting_uses_quarter` : cadence `hebdomadaire`, 3 occurrences → `m` = `1 − 0.75^3` (0,578125).
  - `same_day_twice_counts_once` : même jour appliqué deux fois → `m` et `positifs` inchangés au second passage, `appliquees == 0`.
  - `missing_known_source_holds_day` : source `a` connue, jour suivant sans `a` → `complet == false` ; avec `a` → `true` ; `a` absente 15 jours après son dernier jour → `complet == true` et `a` retirée de `habitudes_sources`, ses lignes `habitudes` conservées.
  - `bad_observations_ignored` : code vide, `valeur` de 65 caractères, `cadence = "mensuel"`, `libelle` avec `\n`, doublon `(quoi, contexte)` → chacune `ignorees`, l'observation valide du même lot appliquée ; 51 observations valides → 50 appliquées, 1 ignorée.
- [ ] **Step 2:** `cargo test -p kairos-memoire habitudes` → échec (fonction absente).
- [ ] **Step 3:** Implémenter `appliquer_observations` (comptage seul ; transitions à la Task 2).
- [ ] **Step 4:** `cargo test -p kairos-memoire` vert.
- [ ] **Step 5:** Commit `feat(memoire): comptage des habitudes observées`.

### Task 2: Naissance, changement, retour, annonce et réponse

**Files:** Modify `tentacles/memoire/src/store/habitudes.rs`, `tentacles/memoire/src/store.rs` (`intention_set_state`).

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - Après le comptage de chaque contexte touché, dans la même transaction : transitions des Global Constraints. Naissance (`candidate` → `active`) : `nee_le = jour`, ligne `souvenirs` (nature `'habitude'`, ref = `id` en texte, stabilité `stabilite_initiale(Famille::Habitude, 0.0)`, `dernier_rappel = maintenant`, importance 0, confiance = `m`), intention `proposee` (texte des Global Constraints, `expires_at = maintenant + 7 j`), `question` = son id, `annoncee_le = maintenant` ; `nees += 1`, et `changements += 1` si une active est devenue `ancienne`. Retour (`ancienne` → `active`) : aucune question, `ancienne_depuis = NULL`, `retours += 1`. Une observation positive d'une habitude née met `souvenirs.dernier_rappel = maintenant` ; la confiance du souvenir suit `m` tant que l'habitude n'est pas confirmée.
  - `intention_set_state(id, state)` (chemin commun de l'application et du moteur) : dans la même transaction, `Acceptee` → `confirmee = 1`, confiance du souvenir 1 ; `Refusee` → `rejetee = 1`, souvenir supprimé. Une habitude rejetée n'est plus candidate au rôle d'active ; l'active non rejetée reste seule active.

- [ ] **Step 1: Tests :**
  - `habit_born_after_seven_evenings` : 6 jours `maison` → `etat == "candidate"`, aucune intention ; 7ᵉ → `"active"`, `nee_le` = 7ᵉ jour, souvenir `habitude` présent, une intention ouverte dont `what == "Confirmer : habitude — <libellé> ?"`.
  - `birth_asks_once` : 7ᵉ jour rejoué, puis 10 jours de plus → toujours une seule intention pour cette habitude.
  - `change_marks_old_habit` : 22 jours `maison`, puis `restaurant` ; au 7ᵉ jour `restaurant` → `restaurant` active, `maison` `ancienne` avec `ancienne_depuis` = ce jour, question qui contient `"(avant : "`, `changements == 1`.
  - `old_habit_fades_in_two_to_three_weeks` : même scénario ; `maison.m >= 0.3` au 10ᵉ jour `restaurant` (≈ 0,3143), `< 0.3` au 11ᵉ (≈ 0,2829).
  - `return_is_fast` : 22 jours `maison`, 12 jours `restaurant`, puis `maison` : pas active après 2 jours (`m` ≈ 0,5230 < `restaurant` ≈ 0,5812), active au 3ᵉ (`m` ≈ 0,6184), `restaurant` `ancienne`, aucune nouvelle intention, `retours == 1`.
  - `confirmation_sets_confidence_one` : `intention_set_state(question, Acceptee)` → `confirmee == 1`, `force("habitude", id).confiance == 1.0`.
  - `rejected_habit_never_asked_again` : refus → souvenir absent ; 20 jours de plus du même comportement → aucune nouvelle intention, toujours sans souvenir.
- [ ] **Step 2:** `cargo test -p kairos-memoire habitudes` → échecs attendus.
- [ ] **Step 3:** Implémenter les transitions et le crochet de `intention_set_state`.
- [ ] **Step 4:** `cargo test -p kairos-memoire` vert.
- [ ] **Step 5:** Commit `feat(memoire): naissance, changement et retour des habitudes`.

### Task 3: Rappel, oubli et effacement

**Files:** Modify `tentacles/memoire/src/store.rs` (`rappel`, `oublier`, `migrate_souvenirs`), `tentacles/memoire/src/store/habitudes.rs`.

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  - `rappel` : nouvelle branche de l'union pour les habitudes nées non rejetées (jointure `souvenirs` nature `'habitude'`) ; texte `"[je crois que ]habitude : <libellé>[ (ancienne habitude depuis le JJ/MM/AAAA)]"` (préfixe si non confirmée) ; l'union gagne une 8ᵉ colonne `force` (`NULL` sauf habitudes = `m`) : pour une habitude, la facilité d'accès vaut `m` et `estompe = m < 0.3` ; pas de recherche plein texte (pertinence 0), donc une habitude estompée ne remonte pas.
  - `oublier` : supprime aussi les habitudes non confirmées de `m < 0.05` (candidates, nées ou rejetées), avec leur souvenir et leur question encore ouverte ; compté dans le total.
  - `pub fn habitude_oublier(&self, id: i64) -> Result<bool>` : suppression réelle de la ligne, du souvenir et de la question ; `false` si inconnue.
  - `migrate_souvenirs` : souvenirs `habitude` orphelins retirés ; habitude née non rejetée sans souvenir → souvenir créé (même valeurs qu'à la naissance).

- [ ] **Step 1: Tests :**
  - `recall_shows_habit_with_hedge` : habitude active non confirmée → une entrée `nature == "habitude"`, texte qui commence par `"je crois que habitude : "` ; confirmée → commence par `"habitude : "`.
  - `recall_marks_old_habit` : après changement, l'ancienne (`m ≥ 0.3`) rappelée avec `"(ancienne habitude depuis le "` ; à `m < 0.3` → absente du rappel général.
  - `recall_skips_candidates_and_rejected` : candidate et rejetée → aucune entrée `habitude`.
  - `forget_drops_faded_deduced_habits` : habitude née puis 30 jours d'une autre valeur dans le même contexte (`m < 0.05` ; sans observation du contexte, `m` ne bouge pas) → `oublier` la supprime avec souvenir et question ; candidate à `m < 0.05` supprimée aussi.
  - `confirmed_habit_survives_forget` : habitude confirmée, puis 60 jours d'une autre valeur → `oublier` la conserve.
  - `habit_forget_is_real` : `habitude_oublier(id)` → `true`, plus aucune ligne `habitudes`, `souvenirs` ni intention liée ; second appel → `false`.
- [ ] **Step 2:** Tests en échec.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `cargo test -p kairos-memoire` vert.
- [ ] **Step 5:** Commit `feat(memoire): habitudes rappelées et oubliées`.

### Task 4: Outils et briefing de la mémoire

**Files:** Modify `tentacles/memoire/src/store.rs` (`ETAPES_NUIT`, `STATUTS_NUIT`), `tentacles/memoire/src/store/habitudes.rs`, `tentacles/memoire/src/tools.rs`, `tentacles/memoire/tests/http.rs`.

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `ETAPES_NUIT` : `["extraction", "habitudes", "condensation", "oubli"]` ; `STATUTS_NUIT` gagne `"source_indisponible"` ; `raison("source_indisponible") == "source indisponible"`. (Les tests existants qui comptent les étapes passent à 4.)
  - Outil `nuit_habitudes {jour, sources: {<nom>: [Observation]}}` → `BilanHabitudes` (JSON).
  - Outil `app_habitude_forget {id}` → `{"message": "Habitude oubliée."}`, erreur `"habitude inconnue"` sinon (l'interface arrive au lot 4).
  - `pub fn habitudes_annoncees(&self, depuis: NaiveDateTime) -> Result<Vec<String>>` : libellés des habitudes annoncées depuis `depuis` dont la question est encore `proposee`.
  - `brief_at(now, period)` : le matin seulement, une ligne `"- Habitude remarquée : <libellé> — à confirmer"` par habitude annoncée dans les 24 h ; le soir, inchangé.
- [ ] **Step 1: Tests :**
  - `night_habits_tool_shape` (tools.rs) : `nuit_habitudes` rend les clés `complet`, `appliquees`, `nees`, `changements`, `retours`, `ignorees`.
  - `habits_step_pending_shows_in_brief` : `nuit_noter("habitudes", "source_indisponible", …)` → la section contient `"habitudes en attente depuis le"` et `"source indisponible"`.
  - `new_habit_announced_in_morning_brief_only` : habitude née → section du matin contient `"Habitude remarquée : "`, celle du soir non ; question refusée → plus de ligne.
  - `night_and_app_habit_tools_hidden` (`tete/tests/tentacles.rs`, à côté de l'existant) : `is_app_tool("nuit_habitudes")`, `is_app_tool("nuit_observations")` et `is_app_tool("app_habitude_forget")` vrais.
  - `http.rs` : `nuit_habitudes` appelable en HTTP brut avec jeton.
- [ ] **Step 2:** Tests en échec.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `cargo test -p kairos-memoire -p tete` vert.
- [ ] **Step 5:** Commit `feat(memoire): outils et briefing des habitudes`.

### Task 5: Observations des repas

**Files:** Modify `tentacles/alimentation/src/tools.rs` (outil et tests du module `tests`).

**Interfaces:**
- Produces: outil `nuit_observations {jour}` (caché au moteur par son préfixe) → JSON `[Observation]` (mêmes champs que la Task 1, sérialisés à la main : pas de dépendance vers `kairos-memoire`) :
  - lit `Store::meals_since(jour)` filtré sur `jour` ; créneaux `midi` et `soir` seulement ;
  - `quoi = "repas.lieu"`, `contexte = "<midi|soir>.<ouvre|weekend>"`, `cadence = "quotidien"` ;
  - `valeur` : lieu replié (`fold`) → `maison`, `bureau`, `restaurant` (`restaurant` ou `resto`), sinon `ailleurs` ;
  - `libelle = "<déjeuner|dîner> <en semaine|le week-end> : <à la maison|au bureau|au restaurant|ailleurs>"` ;
  - jamais le nom du plat ni les notes ;
  - `jour` illisible, aujourd'hui ou futur → erreur (journée non terminée).
- [ ] **Step 1: Tests :**
  - `meal_observations_closed_vocabulary` : un mercredi fictif, midi au `bureau`, soir à `chez des amis` → deux observations, `contexte` `"midi.ouvre"` / `"soir.ouvre"`, `valeur` `"bureau"` / `"ailleurs"`, libellé `"déjeuner en semaine : au bureau"` ; un samedi → `".weekend"` et `"le week-end"`.
  - `unlogged_slot_is_no_observation` : seul le midi noté → une seule observation ; aucun repas → `[]`.
  - `meal_observations_hide_dish` : la sortie ne contient ni le nom du plat ni les notes.
  - `meal_observations_refuse_unfinished_day` : aujourd'hui → erreur.
- [ ] **Step 2:** Tests en échec.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `cargo test -p kairos-alimentation` vert.
- [ ] **Step 5:** Commit `feat(alimentation): observations des repas pour les habitudes`.

### Task 6: Les habitudes dans la nuit de la tête

**Files:** Modify `tete/src/jobs.rs` (`memory_night`), `tete/tests/jobs.rs` (tentacule factice : `nuit_observations`, `nuit_habitudes`).

**Interfaces:**
- Consumes: Tasks 4–5.
- Produces:
  - `const SOURCE_MISSING: &str = "source_indisponible";`
  - `async fn habits(name: &str, last: Option<NaiveDate>, now: NaiveDateTime, ctx: &Ctx) -> &'static str` appelée dans `memory_night` après l'extraction, avant la condensation, sans moteur. Jours : du lendemain de `last` (sinon l'avant-veille seule) jusqu'à l'avant-veille, au plus `NIGHT_DAYS`. Par jour : `call_conventional_limited("nuit_observations", {jour}, NIGHT_MAX_CHARS)` ; chaque réponse lisible comme tableau JSON entre dans `sources` sous le nom du tentacule, une erreur ou une réponse illisible en est simplement absente ; puis `nuit_habitudes {jour, sources}` sur le tentacule mémoire. `complet` → `note(name, "habitudes", OK, Some(jour))` et jour suivant ; `complet == false` → arrêt, `SOURCE_MISSING` ; échec d'outil → `FAILED`. Le statut final est noté comme les autres étapes. Journal : nombres seulement.
- [ ] **Step 1: Tests :**
  - `habits_step_feeds_observations_day_by_day` : dernier jour = J−3 → `nuit_observations` puis `nuit_habitudes` appelés pour J−2 et J−1 dans l'ordre, `sources` contient le nom du tentacule factice, état noté `ok` avec `dernier_jour` = J−1.
  - `habits_run_without_model` : aucun usage de modèle configuré → l'étape `habitudes` passe quand même (`ok`).
  - `missing_source_holds_the_day` : `nuit_habitudes` rend `complet: false` pour J−2 → statut `source_indisponible`, `dernier_jour` inchangé, J−1 non demandé ; nuit suivante avec `complet: true` → J−2 et J−1 rattrapés.
  - `habits_night_twice_is_harmless` : deuxième `run` de la même nuit → aucun nouvel appel `nuit_habitudes`.
- [ ] **Step 2:** Tests en échec.
- [ ] **Step 3:** Implémenter.
- [ ] **Step 4:** `cargo test -p tete` puis `cargo test --workspace` verts.
- [ ] **Step 5:** Commit `feat(tete): apprentissage nocturne des habitudes`.

**À la livraison :** rien à configurer (aucun usage de modèle). Mesures de la période d'essai (§6) : habitudes nées, confirmées, rejetées — lisibles par `SELECT etat, confirmee, rejetee, COUNT(*) FROM habitudes GROUP BY 1,2,3`.
