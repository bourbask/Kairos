# Module Politique — lot 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un module `politique` sert en lecture seule les données publiques du site d'information politique à un espace natif de l'application, avec la scène de personnage « Urne ».

**Architecture:** Les fichiers JSON du site (dépôt privé) restent la source ; le module les lit depuis un dossier monté en lecture seule, les rattache à des identités (`identites.json`) et décrit les thèmes comparables (`comparaisons.json`), deux fichiers ajoutés au dépôt privé. Cinq outils `app_politique_*` passent par le passe-plat de la tête. L'application remplace la vue web par un espace natif ; la scène Urne rejoint le système de scènes.

**Tech Stack:** Rust 2024 (rmcp, axum, serde), Kotlin/Compose ; dépôt privé du site pour les données.

**Spec:** `docs/superpowers/specs/2026-11-12-module-politique-design.md`

## Global Constraints

- Lecture seule, aucun moteur de langue, outils `app_*` uniquement ; tentacule `private = true` côté tête.
- Dépôt public : aucun nom réel de candidat, parti, site ; aucune opinion ; fixtures de test fictives (« Candidat A », « Parti Bleu », `example.org`). Les vrais fichiers `identites.json` et `comparaisons.json` vont dans le dépôt privé du site.
- Chaque valeur affichée garde sa source ; une entrée non rattachée à une identité reste visible dans sa rubrique.
- Rechargement à chaud : un fichier dont la date de modification change est relu au prochain appel ; un fichier illisible garde la dernière version valide et le signale (sans contenu) dans les journaux.
- Résultats bornés (64 000 caractères par réponse), erreurs claires en français, aucun contenu dans les journaux.
- Conteneur : lecture seule, `cap_drop: ALL`, `no-new-privileges`, `mem_limit` et `pids_limit`, réseau interne seulement, dossier de données en lecture seule.
- `git commit --no-gpg-sign`, messages conventionnels en français, sans attribution ; commentaires d'intention en français.
- Vérification : `cargo test --workspace` ; `mobile/` : `./gradlew --no-daemon testDebugUnitTest assembleDebug` (JAVA_HOME `~/.local/jdk/jdk-21.0.13+9`).

## Review Focus

1. Un alias qui figure dans plusieurs identités, ou une entrée qui en cite deux : rattachée aux deux, jamais à une seule au hasard (Task 1 : `entry_with_two_identities_belongs_to_both`).
2. Fichier de données modifié ou cassé pendant le service : nouvelle version prise, version cassée ignorée sans couper le service (Task 1 : `broken_file_keeps_last_good_version`).
3. Comparaison avec une identité absente d'un indicateur : « non renseigné », jamais zéro (Task 1 : `missing_value_is_not_zero`).
4. Recherche avec accents, majuscules, ponctuation : trouvée (Task 1 : `search_ignores_accents_and_case`).
5. Module absent ou ancien (application plus récente) : l'espace reste utilisable, message clair, « Ouvrir le site » toujours possible (Task 3 : `politique_unavailable_keeps_site_access`).

---

### Task 1: Module `politique` (Rust)

**Files:** Create `tentacles/politique/` (Cargo, `src/main.rs`, `src/donnees.rs`, `src/tools.rs`, tests), add to workspace `Cargo.toml`.

**Interfaces — Produces:**
- Env : `POLITIQUE_DONNEES` (dossier des JSON), `POLITIQUE_TOKEN`, adresse d'écoute (suivre le motif des autres tentacules).
- `identites.json` : `[{ "id", "nom", "parti", "couleur", "alias": [..] }]`.
- `comparaisons.json` : `[{ "id", "titre", "rubrique", "chemin" (clé de la liste dans le fichier de rubrique), "cle_identite" (champ de l'entrée qui porte le libellé de l'identité), "indicateurs": [{ "champ", "libelle", "unite" }], "source" (champ ou texte) }]`.
- Rattachement : une entrée (objet JSON d'une liste de rubrique) appartient à toute identité dont un alias est égal (sans accents ni casse, espaces normalisés) à une de ses valeurs texte de premier niveau.
- Outils (JSON) : `app_politique_accueil {}` → `{verifie_le, identites:[{id,nom,parti,couleur}], rubriques:[{id,titre,resume,entrees}], themes:[{id,titre}]}` ; `app_politique_rubrique {id}` → `{id, titre, contenu, rattachements:{<chemin>:[[ids]...]}}` ; `app_politique_fiche {id}` → `{identite, rubriques:[{id,titre,entrees:[...]}]}` ; `app_politique_comparer {ids, theme}` → `{theme, identites:[..], lignes:[{libelle, unite, valeurs:[{id, valeur|null, source}]}]}` ; `app_politique_chercher {q}` → `{q, resultats:[{rubrique, titre, entrees:[...]}]}`.
- Titres et résumés des rubriques : table fixe par nom de fichier connu (libellés génériques : « Votes », « Budgets »…) ; un fichier inconnu apparaît avec son nom.

- [ ] **Step 1: Tests** (fixtures fictives dans `tentacles/politique/tests/donnees/`) : `home_lists_identities_rubrics_themes`, `entry_attached_by_alias`, `entry_with_two_identities_belongs_to_both`, `unattached_entry_stays_in_rubric`, `fiche_groups_by_rubric_with_sources`, `compare_two_and_three`, `missing_value_is_not_zero`, `unknown_identity_or_theme_is_clear_error`, `search_ignores_accents_and_case`, `file_change_is_reloaded`, `broken_file_keeps_last_good_version`, `all_tools_are_app_prefixed`, `responses_are_bounded`.
- [ ] **Step 2:** échec attendu, puis implémenter, puis `cargo test --workspace` vert.
- [ ] **Step 3:** Commit `feat(politique): module de données publiques en lecture seule`.

### Task 2: Déploiement et configuration

**Files:** `deploy/docker/docker-compose.yml` (service `politique`, volume de données en lecture seule depuis un chemin d'hôte défini par variable, durcissement), `deploy/docker/Dockerfile` si besoin (binaire), `config/tete.example.toml` (entrée commentée, `private = true`), `deploy/docker/kairos.env.example` (`POLITIQUE_TOKEN`), `deploy/docker/deploy.sh` (service redémarré), README (section neutre).

- [ ] **Step 1:** `docker compose config` valide ; `bash -n deploy.sh`.
- [ ] **Step 2:** Commit `feat(deploy): service politique`.

### Task 3: Espace Politique natif (application)

**Files:** `mobile/.../ui/politique/` (état, écrans), `Models.kt`, `SpacesScreen.kt` / `MainActivity.kt` (l'espace remplace l'ouverture de la vue web comme écran principal ; « Ouvrir le site » et le partage existants restent accessibles depuis l'espace).

**Interfaces — Consumes:** contrat de la Task 1.

Écrans de la maquette (spec §4) : accueil, fiche à onglets, comparer (choix 2–3 identités + thème), vérifier (recherche sur la rubrique des déclarations vérifiées + autres rubriques), dit / voté, rhétorique, hors de ma bulle. Mêmes motifs que les autres espaces (ouverture ancrée, flèche bas, chargement / erreur / hors ligne, rien d'écrit sur l'appareil au-delà du cache commun).

- [ ] **Step 1: Tests d'état** : `home_maps_contract`, `compare_needs_two_to_three`, `missing_value_shown_as_not_given`, `search_debounced_and_bounded`, `politique_unavailable_keeps_site_access`.
- [ ] **Step 2:** implémenter ; gradle vert.
- [ ] **Step 3:** Commit `feat(mobile): espace Politique natif`.

### Task 4: Scène « Urne »

**Files:** maquette hors dépôt (`.superpowers/maquette/`), puis `mobile/.../ui/mascot/Scenes.kt`, données de référence `sprites-golden.json`, rattachement de la scène à l'espace Politique.

Le personnage plie son bulletin, le glisse dans une urne transparente, un voyant s'allume, petite réaction ; lisible à 2 px par case ; tous les personnages. Planches 8× et 2× vérifiées image par image (au moins deux passes), puis portage et tests de fidélité verts.

- [ ] **Step 1:** maquette + planches ; **Step 2:** portage + références ; **Step 3:** Commit `feat(mobile): scène de l'urne pour l'espace Politique`.

### Task 5: Données réelles (dépôt privé du site)

**Files:** dépôt privé du site : `site/data/identites.json`, `site/data/comparaisons.json` (rédigés depuis les données existantes ; alias complets ; au moins les thèmes budget des programmes et niveau de vie), un court test local (script) qui charge les vrais fichiers avec le binaire du module et affiche, sans contenu, le nombre d'entrées rattachées par rubrique et d'alias inconnus.

- [ ] **Step 1:** rédiger ; **Step 2:** contrôler les rattachements ; **Step 3:** commit dans le dépôt privé (identité git locale déjà réglée).

### Task 6: Revue finale

Revue de toute la branche (et du commit du dépôt privé), correctifs, PR vers `develop`. Étapes d'hôte notées pour le déploiement : jeton dans `kairos.env`, entrée dans `tete.toml`, chemin du dossier de données.
