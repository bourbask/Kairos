# Parité de l'application pour les affirmations : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** amener l'écran Politique de l'application Android au niveau du site pour les affirmations : justification au verso, confiance, barème, partage, signalement par la tête, et voyants latéraux de « Vrai ou faux ? ».

**Architecture:** les nouveaux champs et `bareme.json` arrivent par le chargeur existant (`app_politique_fichier`). La feuille de détail d'une phrase devient la carte recto/verso ; le retournement des tracts passe dans le kit et sert aux deux. Le signalement appelle `app_signalement_deposer` par `Repository.lookup`, comme les autres outils `app_*`. Toute décision testable vit dans des fonctions pures (`*Logic.kt`).

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, JUnit 4 ; module Rust `kairos-politique` pour une ligne de service.

**Spec:** `docs/superpowers/specs/2026-11-18-affirmations-pourquoi-signalements-design.md` (§3, §4, §5.2, §6, §7). Maquette de rendu : `private/maquettes/crible-pourquoi/carte.html` (hors dépôt). Rendu web de référence : `site/assets/app.js` (`carte`, `toucheVerdict`, `ouvrirBareme`, `partager`) et `site/assets/pol/vrai-faux.js` + `pol.css` (`.vf-leds`), dépôt du site.

Abréviations de chemins :
- `P/` = `mobile/app/src/main/java/app/kairos/mobile/ui/politique/`
- `T/` = `mobile/app/src/test/java/app/kairos/mobile/ui/politique/`

Commande de tests (depuis `mobile/`) : `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.<Classe>'` ; toute la suite : `./gradlew testDebugUnitTest`. Module Rust : `cargo test -p kairos-politique`.

## Global Constraints

- Texte du barème : lu dans `bareme.json` (`VERDICTS`, `CONFIANCE`, `PRINCIPE`), jamais en dur. `VERDICT_DEFS` (`P/screens/ClaimsLogic.kt`) est supprimé.
- Champs facultatifs : une affirmation sans `id`, `pourquoi`, `confiance`, `appui` ou `camp_note` s'affiche, sans verso. `bareme` absent = touche « Notre barème » et définitions masquées, libellés repris de `verdictLabel`.
- Identifiant d'affirmation valide : `^[a-z0-9-]{3,60}$`. Invalide ou vide : ni partage ni signalement.
- Lien partagé : origine du lien du site enregistré sur l'appareil + `#a-<id>`. Jamais le chemin, le fragment ni le jeton du lien enregistré.
- Signalement : outil `app_signalement_deposer` du module `politique`, champs `claim, kind, target, what, basis, url, ref_who, ref_when, ref_what, ref_in, fix, oath, idem`. Bornes (points de code, après NFC, retrait des caractères de contrôle sauf `\n`, des invisibles et des espaces de bord) : `what` 30–400, `fix` 10–300, `ref_who` 2–80, `ref_when` 0–30, `ref_what` 4–160, `ref_in` 4–160, `url` ≤ 300. `kind` ∈ `verdict, fait, source, formulation, classement` ; `target` `f<n>`/`s<n>` (1-based), seulement pour `fait`/`source` ; `basis` ∈ `lien, ref, carte` ; `idem` 16–64 `[A-Za-z0-9-]`.
- Textes fixes (copie exacte) :
  - mention : « Anonyme : ni adresse IP, ni compte, ni identifiant ne sont gardés. Seuls le contenu, l'affirmation visée et l'heure d'envoi sont enregistrés. »
  - après envoi : « Il sera relu avec ses sources. »
  - limite : « Chaque signalement est relu à la main, source par source. Au-delà de 10 par heure, personne ne pourrait les vérifier dans un délai raisonnable, et la limite protège aussi le formulaire des envois automatiques. »
  - plafond : « Les signalements sont momentanément fermés. »
  - engagement : « Je signale une erreur vérifiable, pas un désaccord d'opinion. »
  - source sans fiche : « Nous n'avons pas encore analysé cette source : son statut, son financement et son orientation restent à documenter. »
  - aucune promesse de journal des modifications.
- Aucun texte de lecteur n'est journalisé (`Log`, `println`) ni gardé sur l'appareil.
- Aucune nouvelle dépendance. Valeurs de test fictives (`example.com`, `phrase-exemple`).
- Commits en français, génériques, `git commit --no-gpg-sign`, sans mention d'IA.

## Review Focus

1. Lien du site enregistré qui porte encore son jeton dans le fragment (session pas encore ouverte) → le lien partagé ne contient que l'origine et `#a-<id>`. Test : tâche 3, `share_link_keeps_only_origin_and_anchor`.
2. `id` mal formé dans les données (majuscules, espace, `../`, trop long) → pas de touche de partage ni de signalement, aucune exception. Test : tâche 3, même test.
3. `appui` hors des faits (`0`, `9`, doublon) ou `confiance` hors 1–3 → ignorés ; le verso reste lisible. Test : tâche 1, `justification_fields_decode_with_fallback`.
4. Envoi resté sans réponse (`NoReply`) puis renvoyé → même clé `idem`, donc même référence, pas de double compte. Test : tâche 5, `idem_is_stable_and_server_shaped` et `outcomes_from_server_replies`.
5. Saisie avec émojis, caractères invisibles ou de direction, espaces de bord → le compteur et l'activation de la touche d'envoi suivent le compte du serveur. Test : tâche 5, `lengths_count_code_points_after_cleaning`.

---

### Task 1: Données des affirmations et barème

**Files:**
- Modify: `P/model/PolModels.kt` (`Claim`, nouvelles classes du barème)
- Modify: `P/model/PolLoader.kt` (`POL_FILES`, `POL_OPTIONAL_FILES`)
- Modify: `P/screens/ClaimsLogic.kt` (suppression de `VERDICT_DEFS`, fonctions du barème)
- Modify: `P/screens/Claims.kt` (panneau « Que veulent dire les verdicts ? » et ligne « Verdict : … » de `ClaimSheet`)
- Modify: `tentacles/politique/src/donnees.rs` (`BAREME` fichier de service)
- Create: `tentacles/politique/tests/donnees/bareme.json` (fictif)
- Test: `T/ClaimsTest.kt`, `tentacles/politique/src/tools.rs` (module `tests`)

**Interfaces:**
- Produces :
  - `Claim` gagne `id: String = ""`, `pourquoi: String = ""`, `confiance: Int? = null`, `confiance_pourquoi: String = ""`, `appui: List<Int> = emptyList()`, `camp_note: String = ""`.
  - `@Serializable data class BaremeVerdict(val id: String = "", val libelle: String = "", val famille: String = "", val definition: String = "")`
  - `@Serializable data class BaremeConfiance(val niveau: Int = 0, val libelle: String = "", val definition: String = "")`
  - `class Bareme(val verdicts: List<BaremeVerdict>, val confiance: List<BaremeConfiance>, val principe: String)`
  - Dans `ClaimsLogic.kt` : `fun baremeOf(data: PolData): Bareme?` (null si le fichier manque ou si `VERDICTS` est vide ; `CONFIANCE` filtrée à `niveau in 1..3`) ; `fun verdictName(b: Bareme?, v: String): String` (libellé du barème, sinon `verdictLabel(v)`) ; `fun verdictDefinition(b: Bareme?, v: String): String?` ; `fun verdictThirds(b: Bareme?, v: String): Int` (famille `solide` 3, `partiel` 2, autre 1 ; sans barème : `vrai` 3, `plutot-vrai` 2, autre 1) ; `fun hasWhy(c: Claim): Boolean` ; `fun confidenceLevel(c: Claim): Int?` (1..3 sinon null) ; `fun supportFacts(c: Claim): List<Int>` (dans `1..facts.size`, sans doublon, ordre gardé).
  - Toutes les recherches dans le barème passent par `normalizeVerdict(v)`.

- [ ] **Step 1: Write the failing tests** dans `T/ClaimsTest.kt`

```kotlin
private val BAREME = """{"VERDICTS":[
  {"id":"vrai","libelle":"Vrai (barème)","famille":"solide","definition":"Déf vrai"},
  {"id":"faux","libelle":"Faux","famille":"partiel","definition":"Déf faux"}],
  "CONFIANCE":[{"niveau":1,"libelle":"Basse","definition":"D1"},{"niveau":9,"libelle":"X","definition":"X"}],
  "PRINCIPE":"Principe fictif."}"""

@Test fun justification_fields_decode_with_fallback() {
    val d = PolData(mapOf("affirmations" to file("""{"CLAIMS":[
      {"id":"phrase-exemple","q":"Q0","v":"vrai","facts":["F1","F2"],"pourquoi":"P","confiance":2,
       "confiance_pourquoi":"C","appui":[2,0,9,2],"camp":null,"camp_note":"Note"},
      {"q":"Q1","v":"faux","confiance":7}]}""")))
    val (a, b) = claimsOf(d)
    assertEquals("phrase-exemple", a.id); assertTrue(hasWhy(a)); assertEquals(2, confidenceLevel(a))
    assertEquals(listOf(2), supportFacts(a)); assertEquals("", a.camp); assertEquals("Note", a.camp_note)
    assertEquals("", b.id); assertFalse(hasWhy(b)); assertNull(confidenceLevel(b)); assertTrue(supportFacts(b).isEmpty())
}

@Test fun bareme_comes_from_file_or_is_absent() {
    val b = baremeOf(PolData(mapOf("bareme" to file(BAREME))))!!
    assertEquals("Vrai (barème)", verdictName(b, "solide"))          // ancien id normalisé
    assertEquals("Déf faux", verdictDefinition(b, "faux"))
    assertEquals(2, verdictThirds(b, "faux"))                         // famille lue, pas déduite
    assertEquals(listOf(1), b.confiance.map { it.niveau })
    assertEquals("Principe fictif.", b.principe)
    assertNull(baremeOf(PolData(emptyMap())))
    assertEquals("Plutôt vrai", verdictName(null, "plutot-vrai"))
    assertNull(verdictDefinition(null, "vrai"))
    assertEquals(listOf(3, 2, 1, 1), listOf("vrai", "plutot-vrai", "trompeur", "non-documente").map { verdictThirds(null, it) })
}
```

Dans `tools.rs` (tests) : `bareme_est_un_fichier_de_service` — `fichier(&s, "bareme", 0)` rend `contenu.VERDICTS` en tableau ; `app_politique_accueil` ne liste pas `bareme` dans `rubriques` ; `app_politique_rubrique("bareme")` est une erreur. Le test existant `home_lists_identities_rubrics_themes` garde sa liste inchangée.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.ClaimsTest'` puis `cargo test -p kairos-politique bareme`
Expected: FAIL (références non résolues ; `bareme` listé comme rubrique une fois la fixture ajoutée)

- [ ] **Step 3: Implement** les champs et fonctions ci-dessus. `"bareme"` ajouté à `POL_FILES` et à `POL_OPTIONAL_FILES`. Côté Rust : `pub const BAREME: &str = "bareme";` ajouté à la liste d'exclusion de `Etat::rubriques()`, et une fixture `tests/donnees/bareme.json` à trois verdicts fictifs. Dans `Claims.kt`, le panneau des définitions lit `baremeOf(data)` (ordre du fichier, `VerdictChip(v.id)` + `definition`) et disparaît sans barème ; la ligne « Verdict : … » de `ClaimSheet` passe par `verdictDefinition(bareme, claim.v)`.

- [ ] **Step 4: Run tests to verify they pass** (mêmes commandes, plus `./gradlew testDebugUnitTest` pour vérifier qu'aucun autre test ne dépendait de `VERDICT_DEFS`). Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/app/src tentacles/politique
git commit --no-gpg-sign -m "feat(politique): justification des affirmations et barème dans l'application"
```

---

### Task 2: Retournement mis en commun

**Files:**
- Create: `P/kit/Retourne.kt`
- Modify: `P/screens/QuizLogic.kt` (retrait de `TURN_KEYS`, `TurnFace`, `TurnInk`, `TurnRun`, `TurnFrame`, `TURN_PAD`, `pattern`, `turnFrame`)
- Modify: `P/screens/Quiz.kt` (retrait de `TurnCanvas`, appel du kit)
- Test: `T/QuizTest.kt`

**Interfaces:**
- Produces (package `app.kairos.mobile.ui.politique.kit`, déplacés tels quels sauf mention) :
  - `TURN_KEYS`, `enum class TurnFace { Res, Det }` (Res = recto, Det = verso), `enum class TurnInk`, `TurnRun`, `TurnFrame`, `TURN_PAD`
  - `typealias TurnMotif = (face: TurnFace, u: Double, v: Double, h: Double) -> TurnInk`
  - `val TRACT_MOTIF: TurnMotif` (l'ancien `pattern`) et `val CARD_MOTIF: TurnMotif`
  - `fun turnFrame(t: Double, w: Int, h: Int, from: TurnFace, ear: Int = 27, motif: TurnMotif = TRACT_MOTIF): TurnFrame` : `ear` remplace les deux `27` du coin corné ; `ear = 0` le supprime.
  - `@Composable fun TurnCanvas(t: Double, w: Int, h: Int, s: Int, from: TurnFace, ink: (TurnInk) -> Color, ear: Int = 27, motif: TurnMotif = TRACT_MOTIF)` : `w`, `h` en cellules de 3 dp, comme aujourd'hui.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun card_turn_has_no_dog_ear() {
    val tract = turnFrame(.5, 300, 420, TurnFace.Res)
    val card = turnFrame(.5, 300, 420, TurnFace.Res, ear = 0, motif = CARD_MOTIF)
    assertTrue(tract.runs.any { it.ink == TurnInk.Ear })
    assertTrue(card.runs.none { it.ink == TurnInk.Ear })
    assertEquals(tract.cols to tract.rows, card.cols to card.rows)
}
```

Les tests existants de `QuizTest` (`turn_lands_flat_without_bounce`…) changent seulement d'import.

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.QuizTest'` — Expected: FAIL (`CARD_MOTIF`, paramètre `ear` inconnus)

- [ ] **Step 3: Implement.** `CARD_MOTIF`, pictogramme de la carte (`u`, `v` en fractions) : recto, bandeau `Line` (v .04–.07, u .07–.45), touche verdict `Acc` (v .04–.08, u .62–.93), phrase `Line` (deux rangées entre v .12 et .20), jauge `Box` (v .24–.27, u .07–.93), faits en rangées `Line` de v .32 à .82 comme `TRACT_MOTIF`, sources `Box` (v .88–.93, u .07–.40 et .43–.70) ; verso, titre `Line` (v .04–.07), phrase `Line` (v .12–.18), justification en rangées (v .22–.55), confiance `Box` (v .60–.80), touches `Box` (v .88–.93, u .07–.35 et .60–.93) ; ailleurs `Paper`. Dans `Quiz.kt`, l'appel passe `ink = { when (it) { … } }` avec les encres de `TractInks`, comme le `when` actuel.

- [ ] **Step 4: Run** la même commande — Expected: PASS

- [ ] **Step 5: Commit** — `git commit --no-gpg-sign -m "refactor(politique): retournement commun aux tracts et aux cartes"`

---

### Task 3: Carte d'affirmation recto/verso, barème et partage

**Files:**
- Create: `P/kit/Touches.kt` (touche pixel, jauge pixel, blocs de confiance)
- Modify: `P/kit/Retourne.kt` (`TwoFaces`)
- Modify: `P/screens/ClaimsLogic.kt` (lien, texte de partage, libellés de la touche)
- Modify: `P/screens/Claims.kt` (`ClaimSheet` devient la carte ; `BaremeSheet`)
- Modify: `P/PolNav.kt` (`PolSheetState.Bareme`, `PolNavigator.siteLink()`)
- Modify: `P/PolitiqueScreen.kt` (paramètre `siteLink: String?`, branche `Bareme` de `SheetHost`)
- Modify: `mobile/app/src/main/java/app/kairos/mobile/MainActivity.kt` (passe `model.mimirLink`)
- Test: `T/ClaimsTest.kt`

**Interfaces:**
- Consumes : tâche 1 (`Bareme`, `baremeOf`, `verdictName`, `verdictThirds`, `hasWhy`, `confidenceLevel`, `supportFacts`), tâche 2 (`TurnCanvas`, `CARD_MOTIF`, `TURN_KEYS`), `mimirOrigin` (`ui/mimir/MimirState.kt`), `reducedMotion()`.
- Produces :
  - `fun validClaimId(id: String): Boolean` (regex des contraintes globales)
  - `fun claimLink(siteLink: String?, id: String): String?` = `mimirOrigin(siteLink)` + `"#a-$id"` ; null si lien absent ou illisible, ou si l'id est invalide
  - `fun claimShareText(c: Claim, verdict: String, link: String): String` = `"« ${q} » : ${verdict}\n$link"` (phrase passée par `frTypo`)
  - `fun verdictKeyLabel(verdict: String, hasWhy: Boolean, back: Boolean): String` : « Verdict : X. Pourquoi ? » / « X : revenir aux faits » / « Verdict : X. Justification en cours »
  - kit : `val StepCut: Shape` (coins en marche de 3 dp, comme `--st-cut`) ; `@Composable fun PixelKey(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, fg: Color = onSurface, bg: Color = mix(surface, onSurface, .08f), focusRequester: FocusRequester? = null, content: @Composable RowScope.() -> Unit)` ; `@Composable fun PixelGauge(fraction: Float, color: Color, label: String?, modifier: Modifier = Modifier)` ; `@Composable fun ConfidenceBars(level: Int, small: Boolean = false)` ; `@Composable fun TwoFaces(back: Boolean, front: @Composable () -> Unit, rear: @Composable () -> Unit, modifier: Modifier = Modifier)`
  - `PolSheetState.Bareme` (data object) ; `PolNavigator.siteLink(): String?`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun share_link_keeps_only_origin_and_anchor() {
    assertEquals("https://site.example/#a-phrase-exemple", claimLink("https://site.example/entrer#jeton-fictif", "phrase-exemple"))
    assertNull(claimLink(null, "phrase-exemple"))
    assertNull(claimLink("pas une adresse", "phrase-exemple"))
    listOf("", "ab", "Phrase", "a b c", "../x", "x".repeat(61)).forEach { assertNull(it, claimLink("https://site.example/", it)) }
}

@Test fun verdict_key_labels() {
    assertEquals("Verdict : Vrai. Pourquoi ?", verdictKeyLabel("Vrai", hasWhy = true, back = false))
    assertEquals("Vrai : revenir aux faits", verdictKeyLabel("Vrai", hasWhy = true, back = true))
    assertEquals("Verdict : Vrai. Justification en cours", verdictKeyLabel("Vrai", hasWhy = false, back = false))
}
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.ClaimsTest'` — Expected: FAIL

- [ ] **Step 3: Implement the pure functions** dans `ClaimsLogic.kt`. Run : PASS.

- [ ] **Step 4: Implement the kit** (`Touches.kt`, `TwoFaces`), valeurs de la maquette :
  - `PixelKey` : hauteur visible 30 dp, cible tactile 48 dp (`minimumInteractiveComponentSize`), forme `StepCut`, relief bas de 2 dp `mix(bg, onSurface, .22f)`. Survol (`collectIsHoveredAsState`) : descend de 1 dp, fond `mix(surface, onSurface, .15f)`, relief 1 dp ; appui : descend de 2 dp, sans relief ; jamais d'élévation. Focus : contour intérieur de 2 dp en `colorScheme.primary`. `enabled = false` : inerte, `disabled()` en sémantique, teinte gardée.
  - `PixelGauge` (jauge du Duel, comme `.meter` et `.vf-prog`) : 16 dp ; creux `mix(surface, onSurface, .07f)`, anneau intérieur 2 dp `.20f`, ombre haute 4 dp `.13f` ; remplissage à 3 dp du bord, couleur `color`, liseré intérieur 2 dp `mix(color, Black, .45f)`, ombre basse 3 dp noir 18 %, graduations de 2 dp tous les 6 dp noir 14 %. `label` null = décoratif (`clearAndSetSemantics`), sinon `progressBarRangeInfo`.
  - `ConfidenceBars` : trois barres de 9 dp, hauteurs 6/12/18, écart 3 (`small` : 4 dp, 3/6/9, écart 2) ; allumées `onSurface`, éteintes `mix(surface, onSurface, .18f)` ; décoratif.
  - `TwoFaces` : `Layout` qui mesure les deux faces, prend la plus haute, et ne place que la face visible. La face cachée ne dessine rien, ne reçoit ni toucher ni focus et sort de la sémantique.

- [ ] **Step 5: Rebuild `ClaimSheet`** (même signature, mêmes appelants) :
  - **Recto** : bandeau `Row` = `Text("${theme} · ${campLabel(c)}")` qui passe à la ligne, poids 1 ; à droite, touche de partage (`PixelKey` 30×30, icône `KairosIcons.Share`, label « Partager cette affirmation », présente seulement si `claimLink(nav.siteLink(), c.id) != null`), puis touche verdict. Touche verdict : `PixelKey(fg = verdictLook(v).fg, bg = verdictLook(v).bg)` avec carré 6 dp, libellé `verdictName` en mono majuscules, et carré 16 dp « ? » ; sans `pourquoi` : `enabled = false`, pas de carré « ? », et sous le bandeau une ligne discrète « justification en cours ». Puis la puce d'identité existante si `idOf(camp)` existe ; `camp_note` en texte discret si `camp` est vide ; la phrase ; `PixelGauge(verdictThirds / 3f, verdictLook(v).fg, label = null)` ; la ligne « Verdict : définition » ; « Ce que la phrase cache », faits, origine, évaluateurs, balance, bouton « À table » ; `SourceList` reste le dernier élément.
  - **Verso** : `Kicker("Pourquoi ce verdict")` + touche verdict (glyphe « ← ») ; titre « « q » est <libellé en minuscules, couleur du verdict> » ; `pourquoi` ; panneau de confiance (fond `mix(surface, onSurface, .06f)`, `StepCut`), affiché si `confidenceLevel` n'est pas null : « Confiance » + `ConfidenceBars` + libellé du barème (rien sans barème), `confiance_pourquoi`, puis « Appuyé sur » et une étiquette « Fait n » par `supportFacts` ; pied : `PixelKey("Notre barème")`, présente si `baremeOf(data) != null`, qui ouvre `PolSheetState.Bareme`.
  - **Retournement** : `back` par `rememberSaveable(claim.id)`. Touche verdict : sans mouvement réduit, `TURN_KEYS` joué sur `TurnCanvas(…, ear = 0, motif = CARD_MOTIF)` posé sur la carte (taille relevée par `onSizeChanged`, divisée par `cellPx(3.dp)`), faces masquées par `alpha(0f)` pendant l'animation ; ensuite `back` bascule. Avec mouvement réduit, la face change directement. Ensuite, `requestFocus()` sur la touche verdict de la face visible. Un seul retournement à la fois. Encres : `Paper` surface, `Edge` outlineVariant, `Line` `mix(onSurface, surface, .62f)`, `Acc` couleur du verdict, `Box` `mix(surface, onSurface, .10f)`, `Shadow` onSurface.
  - **Partage** : `Intent.ACTION_SEND` `text/plain` avec `claimShareText`, `createChooser`. Sur `ActivityNotFoundException`, copie dans le presse-papiers et `Toast` « Lien copié ».
  - **`BaremeSheet(data, onDismiss)`** (`PolSheet(full = false)`) : titre « Notre barème », `principe`, verdicts dans l'ordre du fichier (`VerdictChip(id)` + définition), « Indice de confiance », niveaux du plus haut au plus bas (`ConfidenceBars(n, small = true)` + libellé + définition).
  - `PolitiqueScreen` reçoit `siteLink: String?` ; `MainActivity` passe `model.mimirLink`. Le navigateur le lit par `rememberUpdatedState`.

- [ ] **Step 6: Verify** : `./gradlew testDebugUnitTest assembleDebug` PASS ; à l'écran, en clair et en sombre, à la largeur d'un téléphone : retournement, mouvement réduit (animations désactivées dans les options développeur), focus au clavier, bandeau long, carte sans `pourquoi`, partage.

- [ ] **Step 7: Commit** — `git commit --no-gpg-sign -m "feat(politique): carte d'affirmation retournable, barème et partage"`

---

### Task 4: Étiquettes de sources harmonisées (§6)

**Files:**
- Modify: `P/kit/PolKit.kt` (`IndTag`, `SourceButton`, nouvelle `NoFicheSheet`, `noFicheA11y`)
- Modify: `P/kit/Bits.kt` (`LocalNoFiche`)
- Modify: `P/PolNav.kt` (`PolSheetState.NoFiche(name: String)`)
- Modify: `P/PolitiqueScreen.kt` (fournit `LocalNoFiche`, branche `NoFiche` de `SheetHost`)
- Test: `T/SourcesTest.kt`

**Interfaces:**
- Produces : `val LocalNoFiche = compositionLocalOf<(String) -> Unit> { {} }` ; `fun noFicheA11y(name: String): String` = `"$name : source pas encore analysée. Ouvrir sa fiche"` ; `const val NO_FICHE_TEXT` (texte fixe des contraintes globales) ; `@Composable fun NoFicheSheet(name: String, onDismiss: () -> Unit)`.
- Les 42 appelants de `SourceList`/`SourceButton`/`SourceFoot` ne changent pas : la feuille passe par `LocalNoFiche`.

- [ ] **Step 1: Write the failing test** : `no_fiche_label()` vérifie `noFicheA11y("Source fictive") == "Source fictive : source pas encore analysée. Ouvrir sa fiche"`, et que `sourceParts` d'une source sans évaluateur rend `ev == null` et `who == "Source fictive"`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.SourcesTest'` — FAIL
- [ ] **Step 3: Implement** :
  - Partie gauche d'une source sans fiche : `IndTag(0, who, …)`, pastille « ? », même style qu'une source avec fiche. Elle devient cliquable : `LocalNoFiche.current(who)`, description `noFicheA11y(who)`.
  - Étiquettes : partie visible de 24 dp (`IndTag` et partie droite passent de `heightIn(min = 28.dp)` à `24.dp`), zone tactile de 32 dp (`heightIn(min = 32.dp)` sur la `Row`, à la place de `minimumInteractiveComponentSize`). Les deux moitiés gardent `fillMaxHeight` sous `IntrinsicSize.Min`.
  - `NoFicheSheet` : nom en titre, puis `NO_FICHE_TEXT`.
- [ ] **Step 4: Run** — PASS ; vérification à l'écran d'une rangée de sources mêlant les deux cas.
- [ ] **Step 5: Commit** — `git commit --no-gpg-sign -m "feat(politique): étiquettes de sources harmonisées, sources sans fiche"`

---

### Task 5: Signalement, logique et transport

**Files:**
- Create: `P/screens/ReportLogic.kt`
- Modify: `P/PolNav.kt` (`PolNavigator.report`, `PolNavigator.reportQuota`)
- Modify: `P/PolitiqueScreen.kt` (implémentation par `repository.lookup`)
- Test: `T/ReportTest.kt`

**Interfaces:**
- Consumes : `Claim`, `sourcesOf`, `sourceParts`, `ApiError`, `politiqueIssue`, `validClaimId` (tâche 3).
- Produces :
  - `val REPORT_KINDS: List<Pair<String, String>>` = `verdict` « Le verdict », `fait` « Un fait », `source` « Une source », `formulation` « La formulation de l'affirmation », `classement` « Le classement (thème ou camp) »
  - `val REPORT_BASES: List<Pair<String, String>>` = `lien` « Une source, avec son lien », `ref` « Une source, sans lien », `carte` « Une contradiction dans la carte elle-même »
  - `data class ReportForm(val kind: String = "", val target: String = "", val what: String = "", val basis: String = "", val url: String = "", val refWho: String = "", val refWhen: String = "", val refWhat: String = "", val refIn: String = "", val fix: String = "", val oath: Boolean = false, val idem: String = UUID.randomUUID().toString())`
  - `fun cleanReport(s: String): String` (NFC, retire les caractères de contrôle sauf `\n`, retire les invisibles U+061C, U+200B–U+200F, U+202A–U+202E, U+2060–U+2064, U+2066–U+2069, U+FEFF, puis `trim`) ; `fun reportLength(s: String): Int` (points de code de `cleanReport(s)`)
  - `fun urlProblem(u: String): String?`, qui reprend la règle de `url_valide` du module sur le texte brut, avec les messages : vide « Indiquez l'adresse de la source. » ; plus de 300 caractères, blanc, contrôle ou invisible : « Cette adresse n'est pas valide. » ; schéma autre que https : « Seules les adresses en https sont acceptées. » ; `@` dans l'autorité : « L'adresse ne doit pas contenir d'identifiants. » ; port autre que 443 : « L'adresse ne doit pas préciser de port. » ; `[`, moins de deux étiquettes, TLD sans lettre, étiquette mal formée, TLD `local`/`internal`/`lan`/`localhost` : « Indiquez l'adresse publique d'un site (pas une adresse IP ni un nom local). »
  - `fun reportTargets(c: Claim, kind: String, data: PolData): List<Pair<String, String>>` : `fait` → `f1..fN`, libellé « Fait n : » + 70 premiers caractères ; `source` → `s1..sM`, libellé `sourceParts(…).text`, avec `M = min(sourcesOf(c.src).size, (c.src as? JsonArray)?.size ?: 0)`, le décompte du serveur ; sinon vide
  - `fun reportReady(f: ReportForm, c: Claim, data: PolData): Boolean` : toutes les bornes, cible présente dans `reportTargets` si exigée, champs de la base choisie, `oath`
  - `fun reportArgs(f: ReportForm, claimId: String): JsonObject` : textes passés par `cleanReport` ; `target` seulement pour `fait`/`source` ; seulement les champs de la base choisie ; `oath = true` ; `idem`
  - `@Serializable data class ReportReceipt(val reference: String = "", val recu_a: Long = 0, val restant: Int? = null)`
  - `sealed interface ReportOutcome { data class Received(val ref: String, val at: Long, val left: Int?); data class Limited(val minutes: Int); data object Closed; data class Refused(val message: String); data class Failed(val message: String) }`
  - `fun reportOutcome(e: ApiError): ReportOutcome` : 422 `Limite atteinte : réessayer dans N minute(s).` → `Limited(N)` ; 422 `Plafond du jour atteint.` ou `signalements non configurés` → `Closed` ; autre 422 → `Refused(message)` ; `NoReply` → `Failed("Pas de réponse du serveur. Renvoyer garde la même référence.")` ; le reste → `Failed(politiqueIssue(e))` (`Unauthorized` n'arrive jamais ici)
  - `data class ReportQuota(val left: Int? = null, val until: Long = 0)` ; `fun ReportQuota.after(o: ReportOutcome, nowMs: Long): ReportQuota` (`Received` → `left`, `until = 0` ; `Limited(m)` → `until = nowMs + m * 60_000`) ; `fun ReportQuota.waitMinutes(nowMs: Long): Int?` (minutes arrondies au supérieur, null si l'envoi est possible)
  - `fun receiptLine(ref: String, at: Long, zone: ZoneId): String` = `"Référence $ref, reçu le d MMMM yyyy à HH:mm."` (locale française)
  - `PolNavigator` : `suspend fun report(args: JsonObject): ReportReceipt` (lève `ApiError`) ; `var reportQuota: ReportQuota` (état de `PolitiqueScreen`, gardé quand la feuille se ferme)

- [ ] **Step 1: Write the failing tests** (`T/ReportTest.kt`, carte fictive `Claim(id = "phrase-exemple", facts = listOf("F1", "F2"), src = [[…],[…]])`, formulaire valide de base `ok` : `kind = "verdict"`, `what` de 30 « x », `basis = "lien"`, `url = "https://example.com/a"`, `fix` de 10 « y », `oath = true`)

```kotlin
@Test fun lengths_count_code_points_after_cleaning() {
    assertEquals(30, reportLength("😀".repeat(30)))
    assertEquals(2, reportLength("  a​b‮  "))
    assertEquals(1, reportLength("é"))
    assertEquals("a\nb", cleanReport(" a\u0007\nb "))
}
@Test fun url_rules_match_server() {
    assertNull(urlProblem("https://example.com/a")); assertNull(urlProblem("https://x.fr:443/a"))
    assertEquals("Seules les adresses en https sont acceptées.", urlProblem("http://example.com/a"))
    listOf("https://192.0.2.1/x", "https://[::1]/x", "https://localhost/x", "https://localhost./x", "https://a.local/x",
        "https://a.LAN/x", "https://a:b@example.com", "https://x.fr:8443/a", "https://x.fr/a b",
        "https://x.fr/" + "a".repeat(300), "https://example.com/a‮b", "").forEach { assertNotNull(it, urlProblem(it)) }
}
@Test fun ready_only_when_every_bound_holds() {
    assertTrue(reportReady(ok, claim, data))
    assertFalse(reportReady(ok.copy(what = "x".repeat(29)), claim, data))
    assertFalse(reportReady(ok.copy(kind = "fait"), claim, data))
    assertFalse(reportReady(ok.copy(kind = "fait", target = "f3"), claim, data))
    assertTrue(reportReady(ok.copy(kind = "fait", target = "f2"), claim, data))
    assertFalse(reportReady(ok.copy(basis = "ref", refWho = "A", refWhat = "Titre"), claim, data))
    assertFalse(reportReady(ok.copy(oath = false), claim, data))
}
@Test fun args_carry_only_fields_of_the_chosen_basis() {
    val a = reportArgs(ok.copy(basis = "ref", refWho = " Auteur ", refWhat = "Titre", target = "f1"), "phrase-exemple")
    assertEquals(setOf("claim", "kind", "what", "basis", "ref_who", "ref_when", "ref_what", "fix", "oath", "idem"), a.keys)
    assertEquals("Auteur", a["ref_who"]!!.jsonPrimitive.content)
}
@Test fun idem_is_stable_and_server_shaped() {
    val f = ReportForm()
    assertTrue(f.idem.length in 16..64 && f.idem.all { it.isLetterOrDigit() || it == '-' })
    assertEquals(f.idem, f.copy(what = "x").idem)
}
@Test fun outcomes_from_server_replies() {
    assertEquals(ReportOutcome.Limited(12), reportOutcome(ApiError.Server(422, "Limite atteinte : réessayer dans 12 minute(s).")))
    assertEquals(ReportOutcome.Closed, reportOutcome(ApiError.Server(422, "Plafond du jour atteint.")))
    assertEquals(ReportOutcome.Closed, reportOutcome(ApiError.Server(422, "signalements non configurés")))
    assertEquals(ReportOutcome.Refused("what : Description de 30 à 400 caractères."), reportOutcome(ApiError.Server(422, "what : Description de 30 à 400 caractères.")))
    assertTrue(reportOutcome(ApiError.NoReply) is ReportOutcome.Failed)
}
@Test fun quota_follows_receipts_and_limit() {
    val q = ReportQuota().after(ReportOutcome.Received("S-1", 0, 9), 0)
    assertEquals(9, q.left); assertNull(q.waitMinutes(0))
    val l = q.after(ReportOutcome.Limited(12), 1_000)
    assertEquals(12, l.waitMinutes(1_000)); assertNull(l.waitMinutes(1_000 + 12 * 60_000))
}
@Test fun targets_follow_server_counting() {
    assertEquals(listOf("f1", "f2"), reportTargets(claim, "fait", data).map { it.first })
    assertTrue(reportTargets(claim.copy(src = buildJsonObject { put("url", "https://example.com") }), "source", data).isEmpty())
}
@Test fun receipt_line_in_french() =
    assertEquals("Référence S-261118-001, reçu le 18 novembre 2026 à 14:05.", receiptLine("S-261118-001", 1795010700, ZoneId.of("UTC")))
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.ReportTest'` — FAIL
- [ ] **Step 3: Implement** `ReportLogic.kt`, puis le transport dans `PolitiqueScreen` : `report = { repository.lookup(POLITIQUE, "app_signalement_deposer", ReportReceipt.serializer(), it) }` ; sur `ApiError.Unauthorized`, appel de `unauthorized()` puis nouvelle levée. `reportQuota` vient d'un `mutableStateOf(ReportQuota())` de `PolitiqueScreen`.
- [ ] **Step 4: Run** — PASS
- [ ] **Step 5: Commit** — `git commit --no-gpg-sign -m "feat(politique): logique et envoi des signalements depuis l'application"`

---

### Task 6: Formulaire de signalement

**Files:**
- Create: `P/screens/Report.kt`
- Modify: `P/screens/ReportLogic.kt` (deux textes calculés)
- Modify: `P/PolNav.kt` (`PolSheetState.Report(claimId: String)`)
- Modify: `P/PolitiqueScreen.kt` (branche `Report` de `SheetHost`)
- Modify: `P/screens/Claims.kt` (touche « Signaler une erreur » au pied du verso)
- Test: `T/ReportTest.kt`

**Interfaces:**
- Consumes : tout ce que produit la tâche 5 ; `PixelKey`, `StepCut` (tâche 3).
- Produces : `fun counterText(n: Int, min: Int, max: Int): String` = `"$n / $max"`, suivi de `" · encore ${min - n} caractères au moins"` si `0 < n < min` ; `fun anonymityNote(left: Int?): String` = mention fixe + `" $left envoi(s) possible(s) encore cette heure."` (accord au singulier pour 1, et pour 0), ou `" 10 envois par heure au plus."` si `left == null` ; `@Composable fun ReportSheet(data: PolData, claimId: String, nav: PolNavigator, onDismiss: () -> Unit)`.

- [ ] **Step 1: Write the failing tests** : `counterText(12, 30, 400) == "12 / 400 · encore 18 caractères au moins"`, `counterText(0, 30, 400) == "0 / 400"`, `counterText(31, 30, 400) == "31 / 400"` ; `anonymityNote(null)` se termine par « 10 envois par heure au plus. », `anonymityNote(1)` par « 1 envoi possible encore cette heure. », `anonymityNote(9)` par « 9 envois possibles encore cette heure. ».
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.ReportTest'` — FAIL
- [ ] **Step 3: Implement** `ReportSheet` (`PolSheet(full = true)`), dans l'ordre de la maquette :
  - En-tête : « Signaler une erreur », puis la phrase entre guillemets.
  - Si `nav.reportQuota.waitMinutes(now) != null` : le texte de limite, puis « Vous pourrez en envoyer un nouveau dans N min. », et rien d'autre. Après `Closed` : le texte de plafond.
  - « Qu'est-ce qui pose problème ? » : choix en touches (`PixelKey` ; touche choisie en encre pleine, enfoncée de 2 dp) sur `REPORT_KINDS`. Pour `fait`/`source` : « Lequel ? », choix parmi `reportTargets`.
  - « Qu'est-ce qui est inexact ? », aide « Citez le passage concerné, puis dites précisément ce qui ne va pas. Pas votre avis sur le sujet : l'erreur à corriger. », champ creusé multiligne (fond `mix(surface, onSurface, .05f)`, ombre intérieure haute de 2 dp, `StepCut`) et `counterText`.
  - « Sur quoi vous appuyez-vous ? » sur `REPORT_BASES`, puis les sous-champs de la base choisie. `lien` : « Lien vers la source », aide « Adresse en https. Elle n'est pas ouverte automatiquement : elle sera consultée à la relecture. », clavier `KeyboardType.Uri`, `urlProblem` affiché sous le champ dès qu'il n'est plus vide. `ref` : « Média ou auteur », « Date, même approximative », « Titre ou mots-clés ». `carte` : « Quels éléments de la carte se contredisent ? ».
  - « Que faudrait-il écrire à la place, d'après vous ? », aide « Votre proposition sera relue et vérifiée ; elle ne remplace pas le texte automatiquement. », compteur.
  - Case pixel 18 dp avec la phrase d'engagement ; puis `anonymityNote(nav.reportQuota.left)`.
  - « Annuler » et « Envoyer le signalement ». L'envoi reste inactif tant que `!reportReady(...)` ; au premier appui, il passe à « Envoi… », inactif. Ensuite :
    - `Received` : écran « Merci, c'est noté », `receiptLine(..., ZoneId.systemDefault())`, « Il sera relu avec ses sources. », « Fermer » ;
    - `Refused` / `Failed` : message sous la touche, qui redevient active, avec le même `idem` ;
    - `Limited` / `Closed` : écran correspondant ;
    - chaque issue met à jour `nav.reportQuota` par `after`.
  - Le formulaire vit dans un `rememberSaveable` : un renvoi garde son `idem`.
  - Au pied du verso (`Claims.kt`) : `PixelKey` « Signaler une erreur » avec l'icône drapeau en `PixelGrid` (grille du site : `"#.......", "######..", "#.....#.", "#......#", "#.....#.", "######..", "#.......", "#.......", "#......."`). Elle n'est présente que si `validClaimId(claim.id)` et ouvre `PolSheetState.Report(claim.id)`. Si l'id ne correspond plus à aucune affirmation chargée : `EmptyState` « Phrase introuvable », comme `ClaimSheetAt`.
- [ ] **Step 4: Run** — PASS ; puis vérification à l'écran : formulaire complet, chaque base, URL refusée, limite atteinte (11ᵉ envoi), renvoi après coupure réseau (même référence).
- [ ] **Step 5: Commit** — `git commit --no-gpg-sign -m "feat(politique): formulaire de signalement dans l'application"`

---

### Task 7: « Vrai ou faux ? », voyants sur les côtés et jauge de progression

**Files:**
- Modify: `P/screens/QuizLogic.kt` (`sideFit` remplace `deckFit` et `ledOffset` ; `LED_LEFT`, `LED_RIGHT`)
- Modify: `P/screens/Quiz.kt` (colonnes de voyants, plaques, `QuizBar`)
- Test: `T/QuizTest.kt`

**Interfaces:**
- Consumes : `PixelGauge` (tâche 3), `LedButton` existant.
- Produces : `class SideFit(val cw: Int, val ch: Int, val x0: Int, val y0: Int, val side: Int, val ledTop: Int)` (en cellules de 3 dp) ; `fun sideFit(w: Int, h: Int, reserve: Int): SideFit` ; `val LED_LEFT = listOf(Guess.PlutotFaux, Guess.Faux)` ; `val LED_RIGHT = listOf(Guess.PlutotVrai, Guess.Vrai)`.

- [ ] **Step 1: Write the failing tests** (remplacent `deck_stays_below_score_on_low_screens`)

```kotlin
@Test fun leds_flank_the_card() {
    val wide = sideFit(200, 260, 12)
    assertEquals(26, wide.side); assertEquals(TRACT_W, wide.cw); assertTrue(wide.x0 >= wide.side + 7)
    val narrow = sideFit(120, 260, 12)
    assertEquals(15, narrow.side); assertEquals(120 - 2 * (15 + 7), narrow.cw); assertEquals(22, narrow.x0)
    val low = sideFit(200, 80, 12)
    assertTrue(low.y0 + low.ch <= 80 && low.cw >= 40 && low.y0 >= 12)
    assertEquals(wide.y0 + Math.round(wide.ch * .06f), wide.ledTop)
}
@Test fun led_columns_read_like_the_site() {
    assertEquals(listOf(Guess.PlutotFaux, Guess.Faux), LED_LEFT)
    assertEquals(listOf(Guess.PlutotVrai, Guess.Vrai), LED_RIGHT)
}
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests 'app.kairos.mobile.ui.politique.QuizTest'` — FAIL
- [ ] **Step 3: Implement `sideFit`** (règle de `fit()` dans `vrai-faux.js`) : `side = if (w >= 150) 26 else 15` ; `area = h - reserve` ; `cw = min(TRACT_W, w - 2 * (side + 7), ((area - 4) * TRACT_W / TRACT_H.toFloat()).toInt()).coerceAtLeast(40)` ; `ch = jsRound(cw * TRACT_H / TRACT_W.toDouble())` ; `x0 = jsRound((w - cw) / 2.0)` ; `y0 = reserve + max(0, jsRound((area - ch) / 2.0))` ; `ledTop = y0 + jsRound(ch * .06)`.
- [ ] **Step 4: Implement the screen** :
  - La réserve du haut ne compte plus que le score : `reserve = (BAR_H / cd).toInt() + 3`. La rangée de voyants, `LED_RAISE` et `ledOffset` disparaissent.
  - Deux colonnes de largeur `side * cd` : à gauche au bord de l'écran, `LED_LEFT` de haut en bas ; à droite au bord opposé, `LED_RIGHT`. Elles commencent à `ledTop * cd`, avec 10 dp entre les deux voyants. Colonne étroite (`side == 15`) : lentille de 30 dp, libellé centré sur plusieurs lignes.
  - Derrière chaque colonne, une plaque de métal pixel suspendue (`Canvas`, valeurs de `.vf-leds::before`) :
    - fils de 2 dp à 12 dp de chaque bord, du haut de la zone de jeu jusqu'à la plaque ;
    - plaque de 6 dp au-dessus du premier voyant à 12 dp sous le second ;
    - fond relief `pr = mix(surface, onSurface, .34f)` ;
    - dessus `pl = mix(surface, onSurface, .13f)`, en retrait de 2 dp en haut et sur les côtés, de 6 dp en bas ;
    - liseré clair de 2 dp en haut du dessus, `ph = mix(surface, White, .55f)` en clair et `mix(surface, onSurface, .30f)` en sombre ;
    - quatre rivets de 2 × 2 dp en `pr`, à 6 dp des bords sur les côtés, à 6 dp du haut et à 10 dp du bas ;
    - couleur des fils : `pw = mix(surface, onSurface, .42f)`.
  - Les colonnes sont dessinées après la pile et la carte (`zIndex`), donc au-dessus d'une feuille en mouvement, comme sur le site. Elles restent hors de la course de la carte au repos. Les directions d'envoi (`VEC`) ne changent pas.
  - `QuizBar` : la piste devient `PixelGauge(p.fraction, onSurface, label = "Progression")` ; le drapeau reste à droite.
- [ ] **Step 5: Run** `./gradlew testDebugUnitTest assembleDebug` — PASS ; vérification à l'écran à 360 dp et à 600 dp de large, en clair et en sombre : voyants et plaques de part et d'autre, carte jamais recouverte au repos, toucher d'un voyant, glissé dans les quatre directions.
- [ ] **Step 6: Commit** — `git commit --no-gpg-sign -m "feat(politique): voyants de Vrai ou faux sur les côtés, jauge de progression pixel"`
