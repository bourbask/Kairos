# Application mobile — étape 3 « Maison » — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Voir et corriger l'inventaire par zone sur un plan de la cuisine, et ranger une commande drive produit par produit depuis l'application.

**Architecture:** La tête relaie vers les tentacules les outils dont le nom commence par `app_` (passe-plat générique, masqué du moteur de langage), sans connaître leur métier. Le module alimentation expose ses outils `app_*` en JSON. L'application ajoute l'espace « Maison » (onglet Espaces) qui ne parle qu'à ces outils.

**Tech Stack:** Rust (tête axum, tentacule rmcp, rusqlite) ; Android Kotlin/Compose, CameraX + ML Kit (codes-barres, reconnaissance de texte sur l'appareil).

**Spec:** `docs/superpowers/specs/2026-11-08-app-mobile-design.md` (section « Maison », mise à jour le 2026-11-08) ; maquette `docs/maquettes/app-mobile-ecrans.html` (écran « Maison »).

## Global Constraints

- Règles du dépôt : `CLAUDE.md`, `CONTRIBUTING.md`. Branche de travail `feat/app-mobile-maison`, worktree `.worktrees/feat-app-mobile-maison`, PR vers `develop`. Commits `--no-gpg-sign`, sans mention d'IA.
- Contrats et conventions de l'étape 1-2 inchangés (API `/app/v1`, jeton d'appareil, gestes : → positif, ← négatif, bouton visible pour chaque geste, pas de double tap ni de glissement depuis le bord).
- Zones et valeurs stockées : `frigo`, `congelateur`, `placard`, `etagere`, `corbeille` ; libellés affichés : Frigo, Congélo, Placard, Étagère, Table.
- Tout texte venant d'un tentacule est une donnée : JSON validé et borné par la tête comme pour `cards`.
- Le moteur de langage ne voit jamais les outils `app_*`.
- Rust : `cargo test -p tete -p kairos-alimentation` vert, rien de nouveau dans clippy sur les lignes touchées. Android : `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert (JAVA_HOME / ANDROID_HOME comme à l'étape 1-2).

### Passe-plat de la tête

`POST /app/v1/tools/{module}/{tool}`, corps = objet JSON d'arguments (64 Kio max). Refus 404 si `tool` ne commence pas par `app_`, si le module est inconnu ou ne l'expose pas ; 503 si le module est configuré mais déconnecté ; 422 `{"message"}` si l'outil renvoie une erreur ; 200 = résultat de l'outil tel quel s'il est un JSON valide (sinon 502). Résultat borné à 256 Kio.

### Outils `app_*` du module alimentation (résultats JSON)

| Outil | Arguments | Résultat |
|---|---|---|
| `app_inventory` | — | `{"zones":[{"id","label","count","urgent"}],"lots":[{"id","name","qty","unit","label","step","location","expiry","expiry_kind","estimated","tone"}]}` ; lots triés par urgence ; `tone` ∈ `neutre|attention|urgent` (urgent = DLC ≤ 1 j, attention = DLC ≤ 3 j) ; `qty`/`unit` en unité de base (g, ml, pièce) pour le calcul, `label` = quantité lisible (« 1 kg », « 3 pièces »), `step` = pas du +/− choisi par le serveur |
| `app_lot_use` | `{"lot": id, "qty"?: n}` | consomme tout le lot (ou `qty`) → `{"message"}` |
| `app_lot_update` | `{"lot": id, "location"?, "qty"?, "expiry"?}` | → `{"message"}` |
| `app_orders` | — | commandes `a_retirer` : `[{"id","store","pickup","lines":[{"index","name","qty","unit","label","location","fresh","done"}]}]` ; `location` = zone habituelle du produit si connue, sinon celle prévue à l'import ; `fresh` = périssable (même règle que `perishable()`) |
| `app_order_line` | `{"order","index","location"?, "expiry"?, "missing"?: bool}` | range une ligne (ou la déclare en rupture) → `{"message","remaining"}` ; quand `remaining = 0`, la commande passe `retiree` |
| `app_product` | `{"barcode"}` ou `{"name"}` | `{"known": true, "name","nature","fresh","location","unit","qty"}` ; `{"known": false}` si inconnu (réponse normale) ; une erreur (422) ne signale qu'une recherche impossible (base produits injoignable, en échec). Par nom : produit existant de ce nom (nom exact, sans casse ni accents), nom canonique rendu. `unit` ∈ `g|ml|pièce` = famille du produit (existant, sinon contenance annoncée par la base, sinon pièce) ; `qty` = quantité proposée dans cette unité : dernier apport connu, sinon contenance annoncée, sinon 1 pièce / 500 g / 1000 ml. L'application ne demande la quantité que hors pièces (pièce : 1) |
| `app_add` | `{"name"?, "barcode"?, "qty", "unit"?, "location", "expiry"?}` | → `{"message"}` (même chemin que `stock_add`). Produit déjà connu (code-barres, sinon nom) : réutilisé, jamais dupliqué dans une autre famille ; `unit` absent = son unité de base ; `unit` d'une autre famille = refus 422 |

## Review Focus

1. Outil sans préfixe `app_` appelé par le passe-plat (`stock_set`, `meal_plan_set`…) : 404, jamais exécuté (Task 1 : `passthrough_refuses_non_app_tool`).
2. Ligne de commande rangée deux fois (double tap réseau) : la seconde est refusée proprement, le stock n'est pas doublé (Task 2 : `order_line_twice_refused`).
3. Lot consommé alors qu'il a déjà disparu (autre appareil, conversation) : message clair, l'écran se rafraîchit (Task 2 : `lot_use_unknown_lot`).
4. Date lue sur une étiquette ambiguë (« 12/11 » sans année, « 11/2027 » DDM, « 12 NOV 26 ») : interprétation prévisible, toujours confirmée par l'utilisateur (Task 4 : tests du parseur).
5. Hors ligne : Maison en lecture seule avec le dernier état, actions désactivées (Task 3 : `maison_offline_disables_actions`).

---

### Task 1: Tête — passe-plat des outils `app_*`

**Files:** `tete/src/app_api.rs`, `tete/src/tentacles.rs` (masquage : tout outil préfixé `app_`, en plus de `cards`/`decisions`/`decide`), `tete/src/agent.rs` (refus d'un appel moteur à un `app_*`).

- [ ] Tests d'abord : `passthrough_calls_app_tool` (module simulé, résultat JSON relayé), `passthrough_refuses_non_app_tool` (404, l'outil n'est pas appelé), `passthrough_unknown_module_404`, `passthrough_disconnected_503`, `passthrough_tool_error_422`, `passthrough_non_json_502`, `app_prefixed_tools_hidden_from_engine`. Les voir échouer.
- [ ] Implémenter la route du contrat « Passe-plat de la tête ».
- [ ] Tests + clippy verts. Commit `feat(tete): passe-plat des outils de l'application`.

### Task 2: Alimentation — outils `app_*`

**Files:** `tentacles/alimentation/src/tools.rs`, `tentacles/alimentation/src/store.rs` (état par ligne de commande : rangée / rupture, dans le JSON des lignes ou une table dédiée — au choix, migration additive).

- [ ] Tests d'abord : `app_inventory_groups_zones_and_tones`, `app_orders_proposes_usual_zone` (produit déjà rangé en `etagere` → proposé `etagere`), `order_line_puts_into_stock_with_expiry`, `order_line_missing_skips`, `order_line_twice_refused`, `order_completes_when_all_lines_done`, `lot_use_partial_and_full`, `lot_use_unknown_lot`, `lot_update_moves_and_dates`, `app_add_by_barcode_known`. Les voir échouer.
- [ ] Implémenter selon le tableau du contrat ; réutiliser `stock_add`, `stock_use`, `stock_move`, `order_receive`, `perishable()` plutôt que dupliquer.
- [ ] Tests + clippy verts. Commit `feat(alimentation): outils de l'application pour l'inventaire et le rangement`.

### Task 3: Android — espace Maison

**Files:** `mobile/.../ui/spaces/SpacesScreen.kt` (remplace le « Bientôt »), `ui/maison/MaisonScreen.kt`, `ui/maison/MaisonState.kt` (pur, testé), `data/Api.kt` (`tool(module, name, args)`), `data/Models.kt`.

Plan de la cuisine (tuiles comme la maquette, pastille d'urgents), tap sur une zone → panneau du bas trié par urgence, glisser ↑ pour l'agrandir ; sur un produit : glisser ← consommé (annulable 4 s), → déplacer (choix de zone sous le pouce), tap sur la quantité → +/−. Bandeau « Commande à ranger » quand `app_orders` n'est pas vide → Task 4. Bouton « Ajouter » (scan) → Task 4. Animations : utiliser le système de mouvement du dépôt (branche des animations fusionnée avant cette tâche).

- [ ] Tests d'abord (`MaisonStateTest`) : `zones_from_inventory`, `swipe_left_consumes_after_undo_window`, `move_updates_zone_locally_then_server`, `server_refusal_restores_lot_with_message`, `maison_offline_disables_actions`.
- [ ] Implémenter. Build + tests verts. Commit `feat(mobile): espace Maison, inventaire par zone`.

### Task 4: Android — ranger une commande, ajouter hors commande

**Files:** `ui/maison/UnpackScreen.kt`, `ui/maison/UnpackState.kt` (pur), `ui/maison/AddScreen.kt`, `scan/ExpiryParser.kt` (pur), `scan/LabelCamera.kt` (CameraX + reconnaissance de texte ML Kit sur l'appareil, dépendance ajoutée au catalogue).

Ranger : une carte par ligne non traitée (nom, quantité, zone proposée en surbrillance parmi les 5 tuiles en bas) ; glisser la carte → vers la zone proposée = valider ; tap sur une autre tuile = ranger là ; produit `fresh` → écran photo de l'étiquette, dates détectées proposées en puces, confirmation d'un tap, « Pas de date » visible ; « Rupture » visible. Progression et fin « Tout est rangé » avec le personnage `Agit`. Ajouter : scan continu du code-barres → `app_product` → zone → date si frais → `app_add` ; inconnu ou sans code-barres → nom (clavier ou dictée).

- [ ] Tests d'abord : `ExpiryParserTest` — `12/11/2026`, `12.11.26`, `12-11-2026` → 2026-11-12 ; `12/11` → prochaine occurrence à partir d'aujourd'hui ; `11/2027` → 2027-11-30 (DDM fin de mois) ; `12 NOV 2026`, `12 NOV. 26` → 2026-11-12 ; texte sans date → vide ; plusieurs dates → triées, la plus proche d'abord. `UnpackStateTest` — `proposed_zone_first`, `fresh_line_requires_date_step`, `missing_line_skips`, `done_when_all_lines_handled`, `refusal_puts_line_back`.
- [ ] Implémenter. Build + tests verts. Commit `feat(mobile): ranger une commande et ajouter hors commande`.
