# Espace Politique et finances — finitions, second essai — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corriger les défauts relevés au second essai de l'application (0.20.1) et élargir les catégories proposées pour classer une dépense.

**Tech Stack:** Kotlin, Jetpack Compose ; Rust (module finances).

## Retours de l'utilisateur (font foi)

1. Les compteurs (pastilles numérotées : niveau d'indépendance des évaluateurs, et toute pastille du même genre) restent décalés : le numéro n'est pas centré.
2. Vrai ou faux : à la dernière carte (5/5), la barre de progression n'arrive pas au bout, car elle compte aussi la page de résultat. La barre doit atteindre le bout à la dernière réponse ; une petite icône « résultat » accolée à droite de la barre se remplit quand on arrive à la page de résultat.
3. Duel, choix des adversaires : réduire le grand écart entre le groupe « Au hasard » / « Lancer le duel » et les actions générales du bas.
4. Photos de profil (avatars) des identités, partout (en-tête du duel avec le « VS », onglets des volets, puces…) : le fond est plus arrondi que le contour coloré de l'identité ; formes à aligner.
5. Duel, rounds chiffrés (dette publique, déficit public) : une valeur négative s'affiche sans signe (« 8,3 % » au lieu de « −8,3 % »), on ne distingue pas les deux côtés ; l'effet net, lui, est bien signé. Afficher le signe partout où la valeur est signée.
6. Classement d'une dépense : les choix proposés se limitent aux enveloppes du budget (courses, restaurants, loisirs…) ; il faut une palette de catégories courantes plus large.

## Global Constraints

- Dépôt public : aucun nom réel ni donnée personnelle dans le code, les tests, les fixtures, les commits ; jamais de chemin `/home/...` versionné.
- **Vérification visuelle obligatoire** pour les points 1 à 5 : émulateur Android (AVD `kairos-test`, `-memory 2048`), faux serveur local (hors dépôt), captures `adb exec-out screencap -p` relues ; thème clair et sombre.
- `git commit --no-gpg-sign`, sans attribution ; tests : `cargo test --workspace` (si Rust touché) et `cd mobile && ./gradlew --no-daemon testDebugUnitTest assembleDebug`.

---

### Task 1: Espace Politique — compteurs, avatars, signes, écarts (points 1, 3, 4, 5)
- [ ] Test de logique pure : `signed_values_show_sign` (valeur négative et positive des rounds chiffrés du duel, unité conservée).
- [ ] Correctifs ; captures avant/après sur émulateur avec les données de la maquette servies par le faux serveur.
- [ ] Commit `fix(mobile): compteurs centrés, avatars alignés, valeurs signées du duel`.

### Task 2: Vrai ou faux — barre de progression (point 2)
- [ ] Test : `progress_reaches_end_on_last_answer` (fonction pure : fraction de la barre et état de l'icône de résultat selon l'étape).
- [ ] Barre pleine à la dernière réponse ; icône résultat accolée, remplie sur la page de résultat ; capture émulateur.
- [ ] Commit `fix(mobile): progression du vrai ou faux jusqu'au bout`.

### Task 3: Finances — catégories de classement (point 6)
- [ ] Vérifier dans le module finances et l'API du système de budget (projet séparé, seulement lire son contrat d'API dans le dépôt Kairos : `tentacles/finances/src/api.rs`) qu'un classement vers une catégorie sans enveloppe est accepté ; sinon le dire et s'arrêter là (BLOCKED).
- [ ] Liste de catégories courantes génériques (logement, énergie, transports, carburant, santé, assurances, abonnements, vêtements, maison, cadeaux, enfants, animaux, voyages, impôts, épargne, divers…) ajoutée aux alternatives de `decisions`, fusionnée sans doublon avec les enveloppes, triée ; surchargeable par configuration ; test.
- [ ] Commit `feat(finances): catégories courantes proposées au classement`.

### Task 4: Espace de conversation avec Kairos — bulles et texte (point 7)
Retour de l'utilisateur : dans l'espace de conversation, le texte est beaucoup trop gros, illisible, et les bulles prennent trop de place.
- [ ] Taille de texte des messages au niveau du corps de texte des autres espaces ; bulles plus compactes (marges, largeur max, espacement entre messages) ; cohérence avec la typographie de l'app ; respect de l'échelle de police système (pas de taille figée qui ignore l'accessibilité).
- [ ] Captures émulateur avant/après (clair, sombre, police système agrandie 1,3).
- [ ] Commit `fix(mobile): conversation plus compacte et lisible`.
