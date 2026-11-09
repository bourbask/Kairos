# Espace Documents — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Espace Documents dédié, en lecture seule : échéances, recherche, pièces attendues, classement.

**Architecture:** Le module documents expose des outils `app_*` en JSON (passe-plat de la tête inchangé), sur les mêmes lectures que les outils texte (`deadlines`, `docs_search`, `missing_documents`, `docs_overview`), règles des natures privées comprises. L'application ajoute `ui/documents/`.

**Tech Stack:** Rust (tentacle documents, SQLite FTS5), Kotlin/Compose.

**Spec:** `docs/superpowers/specs/2026-11-08-app-espaces-design.md` (section « Espace Documents »).

## Global Constraints

- `CLAUDE.md`, `CONTRIBUTING.md` ; branche `feat/app-documents` (basée sur `feat/app-medias`), PR vers `develop`.
- **Natures privées** : jamais d'organisme, de référence, de montant, de chemin ni de passage ; elles n'apparaissent ni dans la recherche ni avec leur nom de fichier.
- Aucun chemin de fichier vers l'application (seulement le nom du fichier, sans dossier) ; textes extraits nettoyés (caractères de contrôle retirés) et bornés (passage : 240 caractères).
- Lecture seule : aucun outil `app_*` n'écrit.
- Rust : `cargo test -p kairos-documents` vert (vérifier le nom du crate). Android : `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.

### Contrat des outils `app_*` du module documents

| Outil | Arguments | Résultat |
|---|---|---|
| `app_documents` | — | `{"deadlines":[{"date":"AAAA-MM-JJ","kind","what","org"?,"private":bool,"overdue":bool}],"missing":[{"domain","item","present":bool}],"natures":[{"name","count","unreadable","private":bool}],"extraction":{"done","total"}}` ; échéances : retards (90 jours en arrière au plus) puis 90 jours à venir, triées par date ; `kind` = motif (fin, résiliation, renouvellement, expiration, contrôle…) ; `what` = type de document ; `org` omis pour une nature privée |
| `app_documents_search` | `{"query"}` | `[{"name","nature","date"?,"passage"}]` (20 au plus) ; requête vide → `[]` ; natures privées exclues |

## Review Focus

1. Échéance d'une nature privée : ni organisme ni nom de fichier, « document privé » (Task 1 : `private_deadline_redacted` ; Task 2 : `private_deadline_label`).
2. Recherche qui trouverait un document privé : jamais renvoyé (Task 1 : `search_excludes_private`).
3. Recherche avec syntaxe plein texte (guillemets, opérateurs) : pas d'erreur (Task 1 : `search_escapes_fts_syntax`).
4. Index vide (extraction pas commencée) : écran explicite, pas d'erreur (Task 1 : `empty_index_shape` ; Task 2 : `empty_index_label`).
5. Passage avec chemin ou caractères de contrôle : nom seul, texte nettoyé et borné (Task 1 : `no_paths_and_clean_text`).

---

### Task 1: Module documents — outils `app_*`

**Files:** `tentacles/documents/src/tools.rs`, `tentacles/documents/src/missing.rs` (et les lectures partagées), tests du crate.

- [ ] Tests d'abord : `app_documents_json_shape`, `deadlines_overdue_then_upcoming`, `private_deadline_redacted`, `missing_present_flags`, `natures_counts_and_extraction`, `empty_index_shape`, `search_shape`, `search_excludes_private`, `search_escapes_fts_syntax`, `no_paths_and_clean_text`.
- [ ] Implémenter selon le contrat ; réutiliser les lectures existantes (refactor en éléments structurés si besoin, sorties texte inchangées). Commit `feat(documents): outils de l'espace Documents de l'application`.

### Task 2: Android — espace Documents

**Files:** `ui/documents/DocumentsScreen.kt`, `ui/documents/DocumentsState.kt` (pur), `data/Models.kt`, `data/Repository.kt` (cache hors ligne), `ui/spaces/SpacesScreen.kt` + `MainActivity.kt`, comme les espaces existants.

- [ ] Tests d'abord (`DocumentsStateTest`) : `deadlines_overdue_first_with_time_left`, `deadline_tone_by_delay`, `private_deadline_label`, `missing_first_then_present`, `empty_index_label`, `search_result_lines`, `card_next_deadline_and_overdue_count`.
- [ ] Implémenter. Build + tests verts ; vérification sur émulateur avec un faux serveur. Commit `feat(mobile): espace Documents`.
