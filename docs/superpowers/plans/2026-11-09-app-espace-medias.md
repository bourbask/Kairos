# Espace Médias — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Espace Médias dédié : à regarder, téléchargements, à venir, demandes (recherche + demander), problèmes.

**Architecture:** Le module médias expose des outils `app_*` en JSON (passe-plat de la tête inchangé), construits sur les mêmes lectures internes que les outils texte existants (refactor : les fonctions internes rendent des éléments structurés, le texte en dérive). L'application ajoute `ui/medias/` sur la boîte à outils.

**Tech Stack:** Rust (tentacle medias), Kotlin/Compose.

**Spec:** `docs/superpowers/specs/2026-11-08-app-espaces-design.md` (section « Espace Médias »).

## Global Constraints

- `CLAUDE.md` (aucun nom de service, d'hôte ni de fournisseur dans les commentaires, textes d'interface ou messages ajoutés : dire « lecteur », « gestionnaire », « client de téléchargement »), `CONTRIBUTING.md` ; branche `feat/app-medias` (basée sur `feat/app-memoire`), PR vers `develop`.
- Données externes non fiables : titres et raisons nettoyés (caractères de contrôle retirés, 200 caractères au plus) avant l'application.
- Les outils texte existants gardent leur sortie (tests existants verts).
- Un service absent ou en panne n'empêche jamais les autres sections : section `null` + entrée dans `errors`.
- Gestes : → positif, annulation 4 s, bouton visible pour chaque geste ; `ui/theme/Motion.kt`.
- Rust : `cargo test -p kairos-medias` vert (vérifier le nom du crate). Android : `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.

### Contrat des outils `app_*` du module médias

| Outil | Arguments | Résultat |
|---|---|---|
| `app_medias` | — | `{"continue":[{"key","title","left_min"?}]\|null,"ready":[{"key","title","kind":"episode"\|"film","subs":"vf"\|"st_fr"\|"absents"\|"recherche","minutes"?}]\|null,"downloads":{"items":[{"title","progress","eta_min"?,"state":"en_cours"\|"bloque"\|"attente"\|"pause"\|"erreur","why"?}],"paused":bool}\|null,"upcoming":[{"day":"AAAA-MM-JJ","time"?:"HH:MM","title","what","have":bool}]\|null,"requests":[{"title","state"}]\|null,"errors":[{"section","message"}]}` ; `progress` entre 0 et 1 ; heures locales du module ; `upcoming` sur 7 jours |
| `app_media_watched` | `{"key"}` | `{"message"}` ; marque vu sans aperçu (l'application a son annulation) ; clé inconnue → message clair |
| `app_downloads_control` | `{"action":"pause"\|"reprise"}` | `{"message"}` ; les deux clients |
| `app_media_search` | `{"query"}` | `[{"id","media_type":"tv"\|"movie","title","year"?,"have":bool,"requested":bool}]` (10 au plus) ; requête vide → `[]` |
| `app_media_request` | `{"id","media_type"}` | `{"message"}` ; déjà demandé ou déjà là → message, sans erreur |

`key` : identifiant interne du lecteur pour l'élément (opaque pour l'application).

## Review Focus

1. Service injoignable ou non configuré : les autres sections s'affichent, la raison est donnée (Task 1 : `section_errors_isolated` ; Task 2 : `section_error_shown_others_kept`).
2. Titre externe avec caractères de contrôle ou très long : nettoyé et borné (Task 1 : `external_text_cleaned`).
3. Demande d'un titre déjà demandé ou déjà présent : message clair, aucun doublon (Task 1 : `request_idempotent`).
4. « Vu » sur un élément déjà vu ou disparu : message clair, la carte part sans erreur bloquante (Task 1 : `watched_unknown_key_is_clear` ; Task 2 : `watched_removes_item_with_message`).
5. File de téléchargement vide et clients en pause : affichage « en pause » sans liste (Task 2 : `paused_empty_queue_label`).

---

### Task 1: Module médias — outils `app_*`

**Files:** `tentacles/medias/src/tools.rs`, `tentacles/medias/src/actions.rs` (et les fonctions internes de lecture qu'ils partagent), tests du crate (doublures de services existantes).

- [ ] Tests d'abord : `app_medias_json_shape`, `section_errors_isolated`, `external_text_cleaned`, `downloads_states_mapped`, `upcoming_local_days`, `watched_by_key`, `watched_unknown_key_is_clear`, `controls_pause_resume`, `search_shape`, `request_idempotent`.
- [ ] Implémenter selon le contrat ; réutiliser `ready`, `queues`, les appels de calendrier, `search`/`request`, `downloads_control`, `mark_watched` (refactor en éléments structurés si besoin, sortie texte inchangée). Commit `feat(medias): outils de l'espace Médias de l'application`.

### Task 2: Android — espace Médias

**Files:** `ui/medias/MediasScreen.kt`, `ui/medias/MediasState.kt` (pur), `data/Models.kt`, `data/Repository.kt` (cache hors ligne), `ui/spaces/SpacesScreen.kt` + `MainActivity.kt` (carte et ouverture), comme les espaces existants.

- [ ] Tests d'abord (`MediasStateTest`) : `ready_split_by_subtitles`, `continue_first_then_ready`, `downloads_progress_and_eta_labels`, `paused_empty_queue_label`, `upcoming_grouped_by_day_with_today_tomorrow`, `section_error_shown_others_kept`, `watched_removes_item_with_message`, `search_result_states`.
- [ ] Implémenter. Build + tests verts ; vérification sur émulateur avec un faux serveur. Commit `feat(mobile): espace Médias`.
