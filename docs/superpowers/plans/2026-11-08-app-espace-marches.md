# Espace Marchés — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Espace Marchés dédié : questions suivies avec probabilité, variation et mini-courbe, détail avec historique, scénarios, alertes, propositions à trier.

**Architecture:** Le module marchés expose des outils `app_*` en JSON (passe-plat de la tête inchangé), dont l'historique des probabilités déjà stocké (30 jours) mais jamais exposé. L'application ajoute `ui/marches/` sur la boîte à outils `ui/kit/` (LineChart pour les courbes, mini-courbe).

**Tech Stack:** Rust (tentacle marches, SQLite), Kotlin/Compose.

**Spec:** `docs/superpowers/specs/2026-11-08-app-espaces-design.md` (section « Espace Marchés »).

## Global Constraints

- `CLAUDE.md`, `CONTRIBUTING.md` ; branche `feat/app-marches`, PR vers `develop` ; release ensuite par la seule PR « Release X.Y.Z ».
- Textes des marchés : données externes non fiables, nettoyées côté module (caractères de contrôle, longueur bornée) avant l'application.
- Aucune adresse de l'API des marchés dans l'application : tout passe par la tête.
- Gestes : → positif, ← négatif, annulation 4 s, bouton visible pour chaque geste ; système de mouvement `ui/theme/Motion.kt`.
- Rust : `cargo test -p kairos-marches` vert. Android : `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.

### Contrat des outils `app_*` du module marchés

| Outil | Arguments | Résultat |
|---|---|---|
| `app_markets` | — | `{"followed":[{"id","question","outcome","prob"\|null,"d1"?,"d7"?,"spark":[p…],"end"?,"closed"}],"scenarios":[{"name","links":[{"id","sign"}]}],"alerts":[{"id","text","at"}]}` ; `prob` et `spark` entre 0 et 1 ; `prob` null tant qu'aucun relevé n'existe ; `spark` = une valeur par jour UTC (dernier relevé du jour) sur 30 jours au plus ; `d1`/`d7` omis sans relevé comparable ou si `closed` |
| `app_market` | `{"id"}` | `{"id","question","outcomes":[{"name","prob"}],"history":[{"at","prob"}],"volume":[{"day","value"}],"projection":{"up"?,"down"?},"scenarios":[…]}` ; `outcomes` = la seule issue suivie (seule relevée) ; `history` un point par jour UTC ; `volume` = écart du volume cumulé d'un jour relevé au précédent (premier jour absent) |
| `app_market_proposals` | — | `[{"id","question","outcome","prob"\|null,"volume"?,"end"?}]` (propositions de la semaine ISO en cours, hors questions déjà suivies ; `prob` null pour une liste antérieure à son enregistrement) |
| `app_market_follow` | `{"id"}` | `{"message"}` ; suit une question proposée cette semaine (identifiant, plus de numéro de liste) ; déjà suivie → message, sans erreur |
| `app_market_unfollow` | `{"id"}` | `{"message"}` |

## Review Focus

1. Question sans historique (tout juste suivie) : mini-courbe absente proprement, variations omises (Task 1 : `no_history_omits_deltas` ; Task 3 : rendu sans courbe).
2. Question à échéance passée (`closed`, pas encore tranchée) : affichée « échéance passée, plus de variations » ; tranchée, elle quitte la liste (Task 1 : `closed_market_shape`).
3. Texte externe avec caractères de contrôle ou très long : nettoyé et borné (Task 1 : `external_text_cleaned`).
4. Suivre une proposition déjà suivie, ou ne plus suivre une question inconnue : message clair, aucun doublon (Task 1 : `follow_idempotent`).

---

### Task 1: Module marchés — outils `app_*`

**Files:** `tentacles/marches/src/tools.rs`, `tentacles/marches/src/store.rs` (lecture de l'historique `snapshots` agrégé par jour, variations 24 h / 7 j, propositions par identifiant), tests du crate.

- [ ] Tests d'abord : `app_markets_json_shape`, `spark_one_point_per_day`, `deltas_from_history`, `no_history_omits_deltas`, `closed_market_shape`, `app_market_detail_history_and_volume`, `external_text_cleaned`, `follow_by_id_from_proposals`, `follow_idempotent`, `unfollow_unknown_is_clear_error`.
- [ ] Implémenter selon le contrat ; réutiliser la logique existante de `follow`/`unfollow`/`projection`/`alerts`. Commit `feat(marches): outils de l'espace Marchés de l'application`.

### Task 2: Android — espace Marchés

**Files:** `ui/marches/MarchesScreen.kt`, `ui/marches/MarchesState.kt` (pur), `ui/kit/` (mini-courbe : variante compacte de `LineChart` si besoin, sans axes), `data/Models.kt`, `data/Repository.kt` (cache hors ligne, fraîcheur), `ui/spaces/SpacesScreen.kt` (carte Marchés : plus forte variation du jour).

Liste des questions suivies (pourcentage, variation, mini-courbe, échéance ; tri par variation) ; détail en panneau (courbe 30 j, volume en barres, issues, projection, scénarios) ; scénarios ; alertes ; propositions en pile (réutiliser la mécanique de la pile Décider : → suivre, ← ignorer, annulation) ; glisser ← sur une question suivie = ne plus suivre (annulable 4 s).

- [ ] Tests d'abord (`MarchesStateTest`) : `sorted_by_abs_variation`, `percent_and_delta_format` (« 63 % », « +5 pts », « −12 pts »), `closed_shows_outcome`, `unfollow_undo_window`, `proposals_deck_follow_and_ignore`, `offline_keeps_last_state`.
- [ ] Implémenter. Build + tests verts. Commit `feat(mobile): espace Marchés`.
