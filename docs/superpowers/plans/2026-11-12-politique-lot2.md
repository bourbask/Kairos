# Espace Politique — lot 2 (refonte native) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Porter en Compose, fidèlement, la maquette Politique v3 validée, nourrie par le module `politique` étendu d'une livraison paginée des fichiers.

**Architecture:** Le module ajoute `app_politique_fichier` (fichier brut découpé en pages ≤ 60 000 caractères). L'application charge tous les fichiers à l'ouverture de l'espace (pages en parallèle, en mémoire seulement), les expose via `PolData` (modèles typés, résolution des identités et des évaluateurs), et rend chaque écran de la maquette en Compose. L'ancien espace générique (`ui/politique/PolitiqueScreen.kt`, `PolitiqueState.kt`) est remplacé.

**Tech Stack:** Rust 2024 (rmcp, serde_json) ; Kotlin, Jetpack Compose, kotlinx.serialization.

**Spec:** `docs/superpowers/specs/2026-11-12-politique-lot2-design.md`. **Référence d'interface (fait foi pour le rendu) :** `private/maquettes/politique-v3/index.html` (dossier privé du dépôt principal, hors versionnement) (lignes citées par tâche) et `NOTES.md` du même dossier (sections « Compose » par écran). Ouvrir la maquette dans un navigateur : `python3 -m http.server -d <dossier>` puis `index.html`. Captures de référence : `captures/` du même dossier.

## Global Constraints

- **Dépôt public : aucun nom réel** (personne, parti, organisme, média, ville, site) dans le code, les commentaires, les tests, les fixtures, les messages de commit. Tout libellé nominatif vient des données à l'exécution. Les intitulés de la maquette qui en contiennent deviennent génériques : « Face à la ligne <président> » → « Face au bilan sortant » ; « Villes <parti> » → sections générées par identité depuis les données ; liens codés en dur vers des organismes → supprimés (sources des données seulement).
- Fixtures fictives (« Candidat A », « Parti Bleu », `example.org`), dans `tentacles/politique/tests/donnees/` et `mobile/app/src/test/resources/politique/`.
- Rien n'est écrit sur l'appareil au-delà du cache commun ; les données vivent en mémoire le temps de l'espace.
- Pas de barre de recherche ni de champ de saisie dans l'espace.
- Sources : action nommée, jamais d'URL brute ; seules les URL `https://` s'ouvrent (navigateur externe) ; seul `<b>` est interprété dans les textes reçus (gras), tout le reste est du texte.
- Couleur d'identité (`couleur` de `identites`) pour les acteurs ; couleurs d'état neutres pour verdicts, votes, niveaux d'indépendance — jamais une couleur de parti.
- Libellés français, hiérarchie titre → contenu, états vides qui expliquent pourquoi et quoi faire.
- Accessibilité : `contentDescription`/`semantics` sur tout élément actionnable, cibles ≥ 48 dp, animations coupées si l'animation système est désactivée.
- Mêmes motifs que les autres espaces : `SpaceScaffold`, flèche bas pour fermer, `BackHandler` (vue précédente puis fermeture), menu « Ouvrir le site » / « Accès partagés » / partage conservés (`ShareDialog`, `SharesPanel` de `ui/mimir/MimirShares.kt`).
- `git commit --no-gpg-sign`, messages conventionnels en français, aucune attribution. Commentaires = intention seule, en français.
- Vérification : `cargo test -p kairos-politique` ; `cd mobile && ./gradlew --no-daemon testDebugUnitTest assembleDebug` (JDK 21).

## Review Focus

1. Fichier modifié sur le serveur entre deux pages d'un même chargement : jamais de mélange de versions (Task 1 `fichier_pages_portent_la_version`, Task 2 `loader_restarts_when_version_changes`).
2. Donnée absente ou mal formée dans un fichier (champ manquant, type inattendu, fichier entier absent) : l'écran concerné montre un état vide explicatif, jamais un plantage (Task 2 `models_tolerate_missing_and_wrong_types`, chaque écran : rendu avec `PolData` vide compilé et testé via sa fonction d'état).
3. Libellé d'identité ambigu ou inconnu (alias partagé, casse, accents) : rattaché à toutes ses identités ou à aucune, jamais à une au hasard (Task 2 `id_of_folds_and_shared_alias`).
4. Couleur d'identité très claire ou très sombre (blanc, jaune) : texte posé dessus lisible (Task 3 `text_on_identity_color_is_readable`).
5. URL non `https` ou malformée dans une source : affichée comme libellé seul, non cliquable (Task 3 `only_https_sources_open`).

---

### Task 1: Module — livraison paginée des fichiers

**Files:**
- Modify: `tentacles/politique/src/tools.rs` (outil, titres), `tentacles/politique/src/donnees.rs` (fichier de service `carte`)
- Create: `tentacles/politique/tests/donnees/dit-vote.json`, `dossiers.json`, `evaluateurs.json`, `carte.json` (fictifs ; `dit-vote.json` assez gros pour faire ≥ 3 pages avec une borne de test)
- Test: module `tests` de `tools.rs`

**Interfaces — Produces:**
- `app_politique_fichier { "id": String, "page": u32 (défaut 0) }` → `{ "id", "page", "pages", "version", "contenu" }`. `version` = empreinte stable du fichier lu (date de modification + taille, en hexadécimal). `contenu` : objet de même forme que le fichier ; quand le fichier sérialisé dépasse `BUDGET_PAGE = 60_000` caractères, la plus grande liste (clé de premier niveau, ou `clé.sous_clé` au second niveau, comme `listes()`) est découpée en tranches consécutives ; page 0 porte toutes les autres clés, les pages suivantes ne portent que la clé découpée avec sa tranche. Un élément seul plus gros que le budget fait sa page à lui seul. Fichier inconnu ou page hors bornes : erreur claire en français.
- Fichiers servis : tous ceux du dossier, y compris `identites`, `meta`, `carte` (fichier de service, jamais listé en rubrique, comme `meta`).
- Titres ajoutés à `RUBRIQUES` : `("dit-vote", "Dit / voté", "Votes croisés avec les déclarations publiques.")`, `("dossiers", "Dossiers", "Contenus qui circulent, vérifiés affirmation par affirmation.")`, `("evaluateurs", "Qui chiffre ?", "Les producteurs des chiffres et leur indépendance.")`.

- [ ] **Step 1: Tests** : `fichier_petit_en_une_page`, `fichier_gros_decoupe_sans_perte` (concaténer les tranches redonne la liste exacte ; chaque page ≤ budget sauf élément isolé), `fichier_pages_portent_la_version` (même `version` sur toutes les pages ; change après modification du fichier), `fichier_inconnu_ou_page_hors_bornes_erreur_claire`, `carte_est_un_fichier_de_service` (absente de `rubriques` de l'accueil, servie par `app_politique_fichier`), `nouvelles_rubriques_titrees`. Étendre `all_tools_are_app_prefixed`.
- [ ] **Step 2:** échec attendu ; implémenter (`fn paginer(v: &Value, budget: usize) -> Vec<Value>` pure, testée directement avec un petit budget) ; `cargo test -p kairos-politique` vert ; régénérer les échantillons (`POLITIQUE_ECHANTILLONS=1 cargo test -p kairos-politique`) en ajoutant `fichier-dit-vote-p0.json`, `fichier-dit-vote-p1.json`, `fichier-evaluateurs.json`, `fichier-carte.json` dans `mobile/app/src/test/resources/politique/`.
- [ ] **Step 3:** Commit `feat(politique): livraison paginée des fichiers de données`.

### Task 2: Application — données (chargement, modèles, résolutions)

**Files:**
- Create: `mobile/app/src/main/java/app/kairos/mobile/ui/politique/model/PolData.kt`, `model/PolModels.kt`, `model/PolLoader.kt`
- Test: `mobile/app/src/test/java/app/kairos/mobile/ui/politique/PolDataTest.kt` (lit les échantillons de la Task 1)

**Interfaces — Produces (package `app.kairos.mobile.ui.politique.model`):**
- `@Serializable data class PolPage(val id: String, val page: Int = 0, val pages: Int = 1, val version: String = "", val contenu: JsonObject = JsonObject(emptyMap()))`
- `val POL_FILES: List<String>` = `meta, identites, affirmations, argent, bilan, budgets, dit-vote, dossiers, evaluateurs, carte, medias, niveau-de-vie, probite, revirements, rhetorique, villes, votes`.
- `fun mergePages(pages: List<PolPage>): JsonObject` — réassemble (concatène la liste découpée, garde les autres clés de la page 0).
- `suspend fun loadPolData(fetch: suspend (id: String, page: Int) -> PolPage, files: List<String> = POL_FILES): PolData` — fichiers en parallèle ; si `version` diffère entre pages d'un fichier, recommence ce fichier (2 essais, puis erreur) ; fichier en erreur « inconnu » = absent (l'écran le dit) ; `ApiError` réseau remonte.
- `class PolData(files: Map<String, JsonObject>)` : `file(id): JsonObject?` ; `identities: List<PolIdentity>` (type existant de `data/Models.kt`, avatar compris) ; `byId(id): PolIdentity?` ; `idsIn(label: String?): List<String>` (alias repliés via `fold`, toutes les identités qui partagent l'alias) ; `idOf(label: String?): String?` (première de `idsIn`) ; `kindOf(id): IdKind` (`Personne`, `Parti`, `Camp` — personne si `parti` ≠ `nom` et `parti` non vide, camp si `parti` absent, sinon parti) ; `partyOf(id): String?` (identité dont le `nom` ou un alias égale le `parti` de la personne) ; `evaluators: Map<String, Evaluator>` ; `evFor(label: String?, explicit: String? = null): Evaluator?` (id explicite d'abord ; sinon alias le plus long qui commence le libellé, suivi d'une fin de mot) ; `verifiedOn: String?` (`meta.verifie_le`).
- Modèles typés partagés (`@Serializable`, tous champs à défaut, `ignoreUnknownKeys`) : `Lecture(mesure, angle_mort, angle_mort_court, contrepartie, contrepartie_courte, postes: List<Poste>, ordre_grandeur, avis: List<Avis>, verdict)`, `Poste(quoi, montant?, qui?)`, `Avis(ev, dit, url)`, `Claim(theme, camp, q, v, facts, src, cache, origine, par, date, lecture?)`, `Evaluator(id, nom, type, statut, financement, dirigeants: List<Dirigeant>, orientation, independance: Int?, justification, tendance, historique: List<Historique>, controverses, alias, src)`, `SourceRef(label: String, url: String?, ev: String?)`.
- `fun sourcesOf(e: JsonElement?): List<SourceRef>` — accepte `[libellé, url]`, `[[libellé, url, ev?], …]`, `{ev, url}`, chaîne seule.
- Décodage tolérant : `PolData.decode<T>(file, path)` renvoie liste vide si absent ou invalide (élément invalide ignoré, pas toute la liste).

- [ ] **Step 1: Tests** : `merge_pages_restores_split_list` (échantillons p0 + p1), `loader_restarts_when_version_changes` (faux `fetch` dont la version change après la 1re page), `loader_marks_unknown_file_absent`, `id_of_folds_and_shared_alias` (« candidat a » et « CANDIDAT  Á » → même id ; alias partagé → deux ids), `kind_and_party_of_identities`, `ev_for_prefers_explicit_then_longest_alias` (« Institut X note 3 » → institut ; « Institut Xyz » ne matche pas « Institut X »), `sources_of_all_shapes`, `models_tolerate_missing_and_wrong_types` (champ numérique en chaîne, liste absente).
- [ ] **Step 2:** échec, implémenter, gradle vert.
- [ ] **Step 3:** Commit `feat(mobile): données de l'espace Politique chargées par fichiers paginés`.

### Task 3: Application — kit visuel et mode « À table »

**Files:**
- Create: `ui/politique/kit/PolColors.kt`, `kit/PolKit.kt`, `kit/AtTable.kt`, `kit/Sheet.kt`
- Test: `ui/politique/PolKitTest.kt`
- Référence maquette : lignes 8–63 (jetons), 110–195 (typo, puces, sources, boutons, pilules, carrousels), 661–722 (feuilles, À table), 724–790 (verdicts, balance, évaluateurs), JS 958–1046, 1265–1364.

**Interfaces — Produces (package `app.kairos.mobile.ui.politique.kit`):**
- `PolColors` (clair/sombre, suit `isSystemInDarkTheme()` et le thème Kairos) : `solide, partiel, fragile, trompeur, faux, pour, contre, low, high, ind1..ind4` + fonds ; valeurs de la maquette.
- `fun textOn(bg: Color): Color` (encre ou blanc selon contraste WCAG) ; `fun safeHttps(url: String?): String?`.
- Composables : `IdentityChip(data: PolData, id: String, large: Boolean = false, text: String? = null, onClick: (() -> Unit)?)`, `IdentityDot`, `EvaluatorTag(ev: Evaluator, onOpen: (String) -> Unit)` (pastille niveau 1–4, nom court, libellé accessible complet), `SourceButton(src: SourceRef, data: PolData, onOpenEv)`, `SourceList`, `VerdictChip(v: String)` + `VerdictMeter(v)` (6 verdicts de phrase ; libellés « Solide », « En partie vrai », « Vrai mais trompeur », « Fragile », « Faux », « Non documenté »), `PromiseChip(v)` (7 verdicts de promesse, libellés de la maquette l. 2016), `BalanceBlock(lecture: Lecture, title: String, who: String?, onOpen)` (phrase courte + postes, repli texte long tronqué 5 lignes) et `BalanceSheet`, `EmptyState(title, body, action?)`, `Pills`, `Segmented`, `SectionHeader(kicker, title, lead?)`, `Kicker`, `RichText(text)` (gras `<b>` seulement).
- `PolSheet(full: Boolean, onDismiss, content)` (feuille glissante, poignée, fermeture au glisser) ; `AtTable(cards: List<TableCard>, start: Int, onClose)` : plein écran, très gros caractères, « Retourner » (rotation 180°), balayage entre cartes voisines, « Partager » (`Intent.ACTION_SEND` texte) ; `data class TableCard(kicker, title, quote?, verdict?, lines: List<String>, source?)`.

- [ ] **Step 1: Tests** : `text_on_identity_color_is_readable` (blanc → encre ; jaune → encre ; bleu nuit → blanc), `only_https_sources_open`, `verdict_labels_cover_all_values` (valeur inconnue → libellé neutre, pas de plantage), `balance_prefers_short_form` (fonction pure `balanceSummary(lecture): BalanceSummary` : courte + postes si présents, sinon texte long).
- [ ] **Step 2:** implémenter ; gradle vert (composables compilés).
- [ ] **Step 3:** Commit `feat(mobile): kit visuel de l'espace Politique`.

### Task 4: Application — coque, accueil, fiche identité

**Files:**
- Replace: `ui/politique/PolitiqueScreen.kt` (nouvelle coque), delete `PolitiqueState.kt` (après reprise de ce qui sert : `politiqueIssue`, constantes)
- Create: `ui/politique/HomeScreen.kt`, `ui/politique/IdentitySheet.kt`, `ui/politique/PolNav.kt`
- Modify: tests `PolitiqueStateTest.kt` → `PolNavTest.kt` (anciens tests obsolètes supprimés)
- Référence maquette : 197–257 (CSS accueil), 1162–1264 (navigation, feuilles), 1365–1474 (accueil), 2480–2543 (fiche identité), 2544–2589 (démarrage, erreurs).

**Interfaces — Produces:**
- `sealed interface PolRoute` : `Home`, `Rubric(id: String)` (budgets, niveau-de-vie, bilan, votes, revirements, probite, argent, medias, phrases, bulle, dit-vote, villes, rhetorique, dossiers, evaluateurs), `Quiz`, `Duel(a: String?, b: String?)`, `Site`, `Shares`. Feuilles (identité, évaluateur, balance, scrutin, ville, procédé, phrase) = état local de la coque : `sealed interface PolSheetState`.
- `fun PolitiqueScreen(repository, padding, appScope, onClose, onUnauthorized, site)` — même signature qu'aujourd'hui (appelants inchangés) ; charge `PolData` via `loadPolData { id, page -> repository.lookup(POLITIQUE, "app_politique_fichier", PolPage.serializer(), …) }` ; états chargement / hors ligne (« Réessayer ») / prêt.
- Registre `val RUBRICS: List<RubricTile>` (id, titre, accroche, compte calculé depuis `PolData`) ; les écrans de rubrique sont fournis par les tâches suivantes via `@Composable fun RubricHost(id: String, data: PolData, nav: PolNavigator)` qui dispatch — chaque tâche ajoute ses branches. Avant leur arrivée : `EmptyState("Bientôt", …)`.
- `interface PolNavigator { fun go(r: PolRoute); fun sheet(s: PolSheetState); fun table(cards: List<TableCard>, start: Int) }`.
- `fun identityStats(data: PolData, id: String): List<Stat>` (pure) ; fiche identité : sections balayables avec onglets (Programme, Bilan sortant, Votes, Dit / voté, Phrases, Cohérence, Revirements, Justice, Argent, Villes, Procédés selon données).

- [ ] **Step 1: Tests** : `rubric_counts_from_data` (fixture), `identity_stats_skip_missing`, `back_pops_route_then_closes` (fonction pure `PolBackStack`), `home_offline_offers_retry_and_site`.
- [ ] **Step 2:** implémenter ; gradle vert.
- [ ] **Step 3:** Commit `feat(mobile): accueil et fiche identité de l'espace Politique`.

### Task 5: Rubriques chiffrées — budgets, niveau de vie, bilan

**Files:** Create `ui/politique/screens/Figures.kt` (+ modèles locaux `Program`, `Gap`, `Measure`, `BilanRow`, `MatrixRow`), Test `FiguresTest.kt`. Référence : CSS 258–380, JS 1481–1566, 2015–2055.

- Rendu : tige depuis la référence (budgets), jauge divergente par ménage + contrepartie sous chaque ligne, cartes mesures, matrice du bilan sortant (titre générique), barres OFCE/IPP génériques (libellés des données), sections « La balance » (`BalanceBlock`), écarts annoncé / crédité avec ratio.
- [ ] **Step 1: Tests** : `axis_bounds_include_reference`, `household_bar_sign_and_scale`, `gap_ratio_handles_zero_credit`.
- [ ] **Step 2:** implémenter, brancher dans `RubricHost` ; gradle vert. **Step 3:** Commit `feat(mobile): rubriques chiffrées et balance`.

### Task 6: Phrases, Hors de ma bulle, détail d'une phrase

**Files:** Create `ui/politique/screens/Claims.kt`, Test `ClaimsTest.kt`. Référence : CSS 567–578, 724–760 ; JS 2063–2161.

- Phrases : filtres en pilules (verdict, camp, thème), pagination par 20, « Ce que la phrase cache » visible ; détail en feuille ; « À table ». Bulle : parcours en 2 étapes (une phrase → les autres camps sur le même thème) et vue par thème.
- [ ] **Step 1: Tests** : `bulle_only_offers_claims_with_facing`, `filters_combine_and_paginate`, `verdict_tally_by_camp`.
- [ ] **Step 2:** implémenter ; gradle vert. **Step 3:** Commit `feat(mobile): phrases et hors de ma bulle`.

### Task 7: Vrai ou faux

**Files:** Create `ui/politique/screens/Quiz.kt`, Test `QuizTest.kt`. Référence : CSS 503–566 ; JS 1715–1867.

- Paquets par thème, pile de cartes, glisser ← faux, ↑ en partie, ↓ vrai mais…, → vrai (et 4 boutons), retournement révélant verdict + cache + faits, score, écran de fin. « Non documenté » hors jeu (dit à l'écran).
- [ ] **Step 1: Tests** : `expected_answer_per_verdict` (solide→vrai, trompeur→mais, partiel→partiel, fragile/faux→faux, non-documente→hors jeu), `swipe_direction_thresholds` (fonction pure dx/dy/vitesse → réponse ou null), `near_feedback_for_trompeur_answered_vrai`.
- [ ] **Step 2:** implémenter ; gradle vert. **Step 3:** Commit `feat(mobile): jeu vrai ou faux`.

### Task 8: Votes, revirements, dit / voté

**Files:** Create `ui/politique/screens/Votes.kt`, Test `VotesTest.kt`. Référence : CSS 400–440, 790–830 ; JS 1567–1649, 2162–2236.

- Dit / voté : sélecteur d'assemblée, vue par scrutin (filtres) et par parti ; taux « Parole publique, hors séance » ; bloc « Ligne annoncée en séance » à part ; puces « En séance » / « Hors séance » sur chaque déclaration ; feuille de scrutin.
- [ ] **Step 1: Tests** : `dv_count_separates_bases` (sans-parole jamais compté), `dv_rate_zero_when_only_seance`, `revirement_counts_by_kind`.
- [ ] **Step 2:** implémenter ; gradle vert. **Step 3:** Commit `feat(mobile): votes, revirements et dit / voté`.

### Task 9: Probité, argent, médias

**Files:** Create `ui/politique/screens/Context.kt`, Test `ContextTest.kt`. Référence : CSS 441–502 ; JS 1650–1714.
- Échelle de procédure (présomption d'innocence affichée), agenda, comptes des partis, propriétaires et audiences des médias.
- [ ] **Step 1: Tests** : `procedure_stage_index`, `money_rows_tolerate_missing_values`.
- [ ] **Step 2:** implémenter ; gradle vert. **Step 3:** Commit `feat(mobile): probité, argent et médias`.

### Task 10: Villes — carte

**Files:** Create `ui/politique/screens/Map.kt`, `screens/MapLogic.kt`, Test `MapLogicTest.kt`. Référence : CSS 860–900 ; JS 2324–2479 ; NOTES.md « Villes ».

- Fond : chemins `carte.regions[].d` (commandes `M`, `L`, `Z` seulement) parsés en `Path` ; projection `x = (lon·k − minx)·s`, `y = (maxy − lat)·s`. Vue pays : bulle par région (barycentre, nombre, anneau segmenté aux couleurs des partis). Vue région : zoom animé, repères par statut (pleine, point clair, pointillé, vidée barrée, petit anneau), fusion des repères qui se chevauchent en écran → feuille « N villes à cet endroit ». Filtre par parti, liste sous la carte, outre-mer en liste.
- [ ] **Step 1: Tests** : `parse_region_path`, `project_matches_reference_point`, `clusters_merge_overlapping_markers_only` (seuil en pixels écran selon le zoom), `region_bubbles_count_by_party`, `dom_codes_go_to_list`.
- [ ] **Step 2:** implémenter ; gradle vert. **Step 3:** Commit `feat(mobile): carte des villes`.

### Task 11: Rhétorique, dossiers, évaluateurs

**Files:** Create `ui/politique/screens/Reading.kt`, Test `ReadingTest.kt`. Référence : CSS 640–660, 830–860, 900–957 ; JS 2237–2323.
- Rhétorique : familles, cartes à retourner (définition, à repérer, exemples sourcés, question à poser). Dossiers : contenu, verdicts par affirmation, procédé lié vers sa carte. Évaluateurs : liste par niveau, légende, fiche (statut, financement, dirigeants, orientation, tendance, historique, controverses, sources).
- [ ] **Step 1: Tests** : `rhet_grouped_by_family_in_order`, `dossier_tally`, `evaluators_grouped_by_level_unknown_last`.
- [ ] **Step 2:** implémenter ; gradle vert. **Step 3:** Commit `feat(mobile): rhétorique, dossiers et évaluateurs`.

### Task 12: Duel

**Files:** Create `ui/politique/screens/Duel.kt`, `screens/DuelLogic.kt`, Test `DuelTest.kt`. Référence : CSS 579–660 ; JS 1868–2014.
- Choix de deux candidats ou deux partis (même type), HUD (couverture de données par côté), rounds : indicateurs de `comparaisons` + balance côte à côte, bilan sortant, annoncé / crédité, votes des groupes, dit / voté (parole publique), revirements. Hachures si couleurs proches. « Pas de vainqueur ».
- [ ] **Step 1: Tests** : `duel_kind_requires_comparable_data`, `rounds_availability_per_side`, `close_colors_hatch_second`.
- [ ] **Step 2:** implémenter ; gradle vert. **Step 3:** Commit `feat(mobile): duel`.

### Task 13: Nettoyage et bascule

**Files:** suppression du code et des tests obsolètes du lot 1 côté application (vues génériques, `presentation` côté app si plus utilisé), échantillons régénérés, `README` (section neutre de l'espace), spec du lot 1 annotée « remplacé par le lot 2 » pour la partie application.
- [ ] **Step 1:** `grep` des symboles morts ; suppression ; gradle et cargo verts ; contrôle « aucun nom réel » : `make hooks` puis relecture des chaînes du package `ui/politique`.
- [ ] **Step 2:** Commit `refactor(mobile): retrait de l'ancien espace Politique`.
