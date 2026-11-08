# Refonte UI/UX de l'application — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chaque écran plus accessible et plus efficace selon son rôle, sur des composants communs.

**Architecture:** D'abord les fondations (`ui/kit/`, thème, file de messages unique), puis chaque groupe d'écrans les adopte. Comportements existants conservés ; seuls la présentation, l'accessibilité et le nombre de gestes changent.

**Tech Stack:** Kotlin/Compose (Material 3), minSdk 34.

**Spec:** `docs/superpowers/specs/2026-11-09-app-ux-refonte-design.md` (sections par écran, « Composants communs à créer », « Découpage de réalisation », « Compléments »).

## Global Constraints

- Identité conservée : mascotte, vert fougère, thème clair par défaut ; onglets Aujourd'hui / Espaces et bouton Kairos central ; gestes → positif / ← négatif ; ni double tap ni glissement de bord ; un bouton visible pour chaque geste.
- P1 obligatoires ; P2 si le temps le permet dans la tâche ; P3 hors de ce plan.
- Contrastes AA (4,5:1 texte, 3:1 éléments graphiques), cibles tactiles ≥ 48 dp, chaque glissement exposé en action d'accessibilité, police 1,3× sans texte coupé, thème sombre par jetons uniquement.
- Aucun nom de produit tiers ni donnée personnelle dans le code et les textes.
- Tests existants verts ; nouvelle logique pure testée (fichiers `*State.kt`, `ui/kit` sans Compose quand c'est possible).
- Vérification : `cd mobile && ./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.

## Review Focus

1. Lecteur d'écran : chaque élément glissable a ses actions (→ / ←), libellés contextuels (pas « Vu, bouton » répété).
2. Annulation : une seule file ; aucune fenêtre perdue en enchaînant les gestes ; délai allongé avec un service d'accessibilité.
3. Erreurs honnêtes : un espace en erreur ne s'affiche jamais comme « vide » ou « 0 ».
4. Police 1,3× et thème sombre : aucun texte coupé, aucune couleur codée en dur.
5. Comportements inchangés : mêmes appels serveur, mêmes caches, mêmes gestes.

---

### Task 1: Fondations du kit et du thème
Spec : « Découpage de réalisation » 1, « Composants communs à créer », « Snackbars et annulation », « Compléments ».
- [ ] Tests d'abord pour la logique pure (délai d'annulation accessible, file du `Notifier` avec les tests de `UndoWindowsTest` conservés ou repris).
- [ ] Implémenter ; commit `feat(mobile): fondations communes de l'interface`.

### Task 2: Aujourd'hui, semaine et mise à jour
Spec : « Découpage » 2 et sections Aujourd'hui, Mise à jour. Commit `feat(mobile): Aujourd'hui plus direct`.

### Task 3: Décider et Ranger
Spec : « Découpage » 3 et sections Décider, Ranger une commande. Commit `feat(mobile): actions sous le pouce pour Décider et Ranger`.

### Task 4: Inventaire et Ajouter
Spec : « Découpage » 4 et sections Inventaire, Ajouter. Commit `feat(mobile): Inventaire refondu`.

### Task 5: Finances, Agenda, Marchés
Spec : « Découpage » 5 et sections correspondantes. Commit `feat(mobile): espaces chiffrés refondus`.

### Task 6: Espaces, Mémoire, Médias, Documents, Réglages, Conversation
Spec : « Découpage » 6, « Compléments » et sections correspondantes. Commit `feat(mobile): espaces de liste, réglages et conversation refondus`.
