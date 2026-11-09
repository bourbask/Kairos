# Espace Mémoire — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Espace Mémoire dédié : intentions à traiter, journal, faits connus, conversations résumées.

**Architecture:** Le module mémoire expose des outils `app_*` en JSON (passe-plat de la tête inchangé), en respectant la sensibilité des entrées. L'application ajoute `ui/memoire/` sur la boîte à outils.

**Tech Stack:** Rust (tentacle memoire, SQLite), Kotlin/Compose.

**Spec:** `docs/superpowers/specs/2026-11-08-app-espaces-design.md` (section « Espace Mémoire »).

## Global Constraints

- `CLAUDE.md`, `CONTRIBUTING.md` ; branche `feat/app-memoire`, PR vers `develop`, release par la seule PR « Release X.Y.Z ».
- Données personnelles : jamais journalisées côté serveur ; les entrées sensibles ne partent vers l'application qu'avec leur marque de sensibilité, l'application les masque par défaut.
- Gestes : → positif, ← négatif, annulation 4 s, bouton visible pour chaque geste ; système de mouvement `ui/theme/Motion.kt` ; dictée par la reconnaissance vocale existante.
- Rust : `cargo test -p kairos-memoire` vert. Android : `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.

### Contrat des outils `app_*` du module mémoire

| Outil | Arguments | Résultat |
|---|---|---|
| `app_memory` | — | `{"intentions":[{"id","what","expires","state","constraints"?}],"journal":[{"id","at","kind","text","sensitive"}],"facts":[{"key","value","updated"}],"conversations":[{"id","at","summary"?,"topics":[…],"decisions":[…]}]}` ; journal et conversations : les 30 plus récents |
| `app_intention_set` | `{"id","state"}` | `{"message"}` ; `state` ∈ `acceptee`, `refusee` (valeurs du module) |
| `app_intention_add` | `{"what","days"}` | `{"message","id"}` |
| `app_journal_add` | `{"text"}` | `{"message","id"}` ; provenance « application », sensibilité par défaut du module |
| `app_journal_search` | `{"query"}` | `[{"id","at","kind","text","sensitive"}]` (30 au plus) |
| `app_fact_history` | `{"key"}` | `[{"value","at"}]` |
| `app_fact_set` | `{"key","value"}` | `{"message"}` (historique conservé) |
| `app_conversation_search` | `{"query"}` | `[{"id","at","summary"?,"topics":[…]}]` |

## Review Focus

1. Entrée de journal sensible : marquée `sensitive`, masquée par défaut dans l'application, jamais en clair dans les journaux du serveur (Task 1 : `sensitive_flag_exposed` ; Task 2 : `sensitive_hidden_by_default`).
2. Intention déjà échue ou déjà traitée au moment du geste : message clair, la carte disparaît, aucune erreur bloquante (Task 1 : `set_state_on_expired_is_clear` ; Task 2 : `refused_intention_removed_with_message`).
3. Recherche avec caractères spéciaux (guillemets, opérateurs de recherche plein texte) : jamais d'erreur de requête (Task 1 : `search_escapes_fts_syntax`).
4. Correction d'un fait avec une valeur vide : refusée côté application et module (Task 1 : `fact_set_rejects_empty` ; Task 2 : `fact_edit_validation`).

---

### Task 1: Module mémoire — outils `app_*`

**Files:** `tentacles/memoire/src/tools.rs`, `tentacles/memoire/src/store.rs` (lecture des N derniers, historique d'un fait, échappement de la recherche), tests du crate.

- [ ] Tests d'abord : `app_memory_json_shape`, `sensitive_flag_exposed`, `intention_accept_and_refuse`, `set_state_on_expired_is_clear`, `intention_add_with_days`, `journal_add_from_app`, `search_escapes_fts_syntax`, `fact_history_ordered`, `fact_set_rejects_empty`, `conversation_search_shape`.
- [ ] Implémenter selon le contrat ; réutiliser `intention_create`, `intention_set_state`, `journal_write`, `fact_set`, `journal_search`, `conversation_search`. Commit `feat(memoire): outils de l'espace Mémoire de l'application`.

### Task 2: Android — espace Mémoire

**Files:** `ui/memoire/MemoireScreen.kt`, `ui/memoire/MemoireState.kt` (pur), `data/Models.kt`, `data/Repository.kt` (cache hors ligne : sans les entrées sensibles), `ui/spaces/SpacesScreen.kt` (carte Mémoire : nombre d'intentions et la plus proche de l'échéance).

Sections Intentions (glisser → accepter, ← refuser, « plus tard », ajout rapide avec dictée), Journal (recherche, ajout, sensibles masquées), Faits (groupés par préfixe ; tap = historique ; correction), Conversations (résumés, recherche).

- [ ] Tests d'abord (`MemoireStateTest`) : `intentions_sorted_by_expiry_with_time_left`, `sensitive_hidden_by_default`, `refused_intention_removed_with_message`, `facts_grouped_by_prefix`, `fact_edit_validation`, `offline_cache_excludes_sensitive`.
- [ ] Implémenter. Build + tests verts ; vérification sur émulateur avec un faux serveur. Commit `feat(mobile): espace Mémoire`.
