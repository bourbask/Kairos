# Espace Finances — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Premier espace de module dédié hors Inventaire : Finances (cycle et patrimoine), avec la boîte à outils visuelle commune des espaces.

**Architecture:** Le module finances expose trois outils `app_*` en JSON (lus par le passe-plat de la tête, sans changement de la tête). L'application ajoute `ui/kit/` (composants réutilisables) et `ui/finances/` (écran dédié), branchés dans Espaces.

**Tech Stack:** Rust (tentacle finances, reqwest vers le backend de finances), Kotlin/Compose (Canvas pour les graphiques).

**Spec:** `docs/superpowers/specs/2026-11-08-app-espaces-design.md`.

## Global Constraints

- `CLAUDE.md`, `CONTRIBUTING.md` ; branche `feat/app-finances`, PR vers `develop`.
- Montants, libellés bancaires : données sensibles et non fiables (nettoyées côté module, jamais journalisées) ; montants masquables dans l'application.
- Rust : `cargo test -p kairos-finances` vert. Android : `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.
- Système de mouvement `ui/theme/Motion.kt`, palette du thème, clair par défaut, sombre correct.

### Contrat des outils `app_*` du module finances

| Outil | Arguments | Résultat |
|---|---|---|
| `app_finance_cycle` | — | `{"start","end","today","days_left","available","balance","envelopes":[{"poste","planned","spent"}],"charges":[{"name","amount","date"?}],"cushion":{"balance","floor"},"alerts":[{"id","title","details","remedy"?}],"postes":[…],"freshness":{"stale","consent_expired","last_sync"?}}` |
| `app_finance_wealth` | — | `{"net_worth","debt","history":[{"month","net_worth"}],"loans":[{"name","balance","payment"?,"payoff_month"?}],"portfolio":{"total","buckets":[{"name","weight","amount"}],"lines":[{"name","amount","bucket"}]},"freshness":{…}}` |
| `app_afford` | `{"amount", "poste"?}` | `{"ok","after","reason"?,"cuts":[{"poste"?,"amount"}]}` |

Montants en euros (nombres) ; l'application formate. Endpoint du backend indisponible → section absente (champ omis), jamais d'échec global.

## Review Focus

1. Backend de finances injoignable ou partiel : l'écran affiche ce qui est disponible et un bandeau, sans planter (Task 1 : `wealth_partial_when_endpoint_fails` ; Task 3 : état hors ligne).
2. Montant saisi invalide dans « Puis-je me permettre » (vide, négatif, virgule française) : refus clair côté application, jamais d'appel (Task 3 : `afford_input_parsing`).
3. Montants masqués : aucun montant visible nulle part dans l'espace, y compris graphiques (Task 2 : `masked_amount_never_shows_digits`).

---

### Task 1: Module finances — outils `app_*`

**Files:** `tentacles/finances/src/api.rs` (appels `networth`, `snapshots`, `loans`, `portfolio` du backend), `tentacles/finances/src/tools.rs`, tests dans `tentacles/finances/tests/` (API simulée comme les tests existants).

- [ ] Tests d'abord : `app_cycle_json_shape`, `app_cycle_freshness_flags`, `app_wealth_json_shape`, `wealth_partial_when_endpoint_fails`, `app_afford_maps_verdict`, `app_afford_rejects_non_positive`.
- [ ] Implémenter selon le contrat. Tests + clippy verts. Commit `feat(finances): outils de l'espace Finances de l'application`.

### Task 2: Android — boîte à outils des espaces

**Files:** `mobile/.../ui/kit/` : `Metric.kt` (gros chiffre + libellé), `Gauge.kt` (progression avec repère), `Bars.kt` (barre dépensé/prévu avec seuils), `LineChart.kt` (série mensuelle), `Donut.kt` (anneau), `Amount.kt` (format euros fr-FR + masquage), `Section.kt` (titre de section, bandeau d'état) ; `ui/kit/KitMath.kt` pur (échelles, seuils, formatage).

- [ ] Tests d'abord (`KitMathTest`) : `euro_format_french`, `masked_amount_never_shows_digits`, `bar_tone_thresholds` (≤80 % neutre, >80 % ambre, >100 % brique), `line_scale_handles_flat_and_single_point`, `donut_weights_normalized`.
- [ ] Implémenter. Build + tests verts. Commit `feat(mobile): boîte à outils visuelle des espaces`.

### Task 3: Android — espace Finances

**Files:** `ui/finances/FinancesScreen.kt`, `ui/finances/FinancesState.kt` (pur), `data/Models.kt`, `data/Repository.kt` (cache hors ligne comme l'Inventaire), `ui/spaces/SpacesScreen.kt` (carte Finances avec reste à vivre en aperçu), réglage du masquage (DataStore, partagé par les espaces).

Onglets Cycle / Patrimoine selon la spec ; « Puis-je me permettre ? » en panneau du bas (montant, puces de poste, verdict) ; tirer pour actualiser ; transitions du système de mouvement.

- [ ] Tests d'abord (`FinancesStateTest`) : `afford_input_parsing` (« 12,50 » → 12.5 ; vide/négatif/texte refusés), `offline_keeps_last_state`, `envelopes_sorted_by_pressure`, `missing_sections_hidden`.
- [ ] Implémenter. Build + tests verts. Commit `feat(mobile): espace Finances`.
