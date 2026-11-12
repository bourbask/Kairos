# Espace Politique — finitions après premier essai — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corriger les défauts relevés par l'utilisateur sur l'espace Politique natif (version 0.20.0) : densité, verbosité, imbrications, défauts visuels, ordre des identités.

**Architecture:** Corrections dans le kit (`ui/politique/kit`) et les écrans (`ui/politique/screens`, `HomeScreen`, `IdentitySheet`, `PolitiqueScreen`), plus la coque commune des espaces si le défaut de dégradé y prend sa source.

**Tech Stack:** Kotlin, Jetpack Compose.

**Spec:** retours de l'utilisateur ci-dessous (font foi) ; spec du lot 2 : `docs/superpowers/specs/2026-11-12-politique-lot2-design.md`.

## Retours de l'utilisateur (exigences)

1. Duel : la mise en regard verticale ne va pas pour les rounds de balance (texte long). Composant à glisser : deux onglets aux couleurs des adversaires, glisser horizontalement pour passer d'un côté à l'autre.
2. Étiquette d'évaluateur + bouton de source (partout) : le numéro de niveau n'est pas centré dans sa pastille ; les deux parties n'ont pas la même hauteur.
3. Carte « Dit / voté » : fond de section rogné en bas (coin arrondi mal géré) ; « É » de « Écart : » rogné en haut ; texte long rogné en bas.
4. Ordre des personnages : alphabétique (le plus neutre), partout où une liste d'identités est présentée.
5. Verbosité : remplacer les indications textuelles d'interface (« comment lire », « touchez… ») par des icônes avec infobulle (appui long ou appui sur l'icône) ; garder le texte seulement quand il est indispensable ; avertissements sur la portée des chiffres moins invasifs (une ligne ou une icône dépliable, pas un bloc).
6. Bandeau opaque en bas : dans les pages de l'espace (Budgets, Duel, Vrai ou faux…), le dégradé transparent au-dessus des actions du bas devient un bloc opaque ; il doit rester un dégradé comme sur l'accueil et les autres espaces.
7. Boutons « Afficher en grand, à table » → bouton icône « plein écran » sur la carte.
8. Balance (Budgets « Ce que chaque programme sacrifie » et partout ailleurs) : pas de carte imbriquée dans la carte ; libellés répétés à chaque candidat (« La balance », « Ce que ça coûte, qui paie ») remontés une seule fois en titre ou sous-titre de section ; pas de rouge d'urgence appliqué à tous ; la perte se dit par le chiffre (signe moins et/ou chiffre en couleur) ; pas de bouton « Toute la balance » : appuyer sur la carte ouvre le détail.
9. « Hors de ma bulle » : ne pas y toucher pour l'instant.

## Global Constraints

- Dépôt public : aucun nom réel dans le code, les commentaires, les tests, les fixtures, les commits.
- Couleur d'identité pour les acteurs ; couleurs d'état neutres ; la couleur d'erreur ou d'urgence n'est jamais un fond décoratif.
- Accessibilité : chaque icône porteuse de sens a une `contentDescription` et son infobulle ; cibles ≥ 48 dp ; la carte cliquable a un rôle et un libellé d'action.
- `git commit --no-gpg-sign`, sans attribution ; vérification : `cd mobile && ./gradlew --no-daemon testDebugUnitTest assembleDebug`.

---

### Task 1: Kit — sources, plein écran, infobulles, balance à plat, dégradé du bas
Points 2, 6, 7, 8 (côté kit) et le composant d'infobulle du point 5.
- [ ] Tests de logique pure : `balance_row_signs_losses` (montant de perte formaté avec signe moins), ordre et libellés inchangés sinon.
- [ ] `EvaluatorTag`/`SourceButton` : même hauteur, numéro centré ; `FullscreenButton` (icône) ; `InfoIcon(text)` (infobulle) ; `BalanceBlock` à plat (phrase courte + postes, pas de carte interne, pas de libellé répété, pas de fond rouge, carte entière cliquable vers le détail, plus de bouton « Toute la balance ») avec un en-tête de section partageable `BalanceHeader` ; corriger la cause du bloc opaque en bas (comparer avec l'accueil de l'espace et la coque commune).
- [ ] Commit `fix(mobile): kit Politique plus dense, sources alignées, dégradé du bas`.

### Task 2: Écrans — balance, « à table », verbosité, ordre
Points 4, 5, 7, 8 dans tous les écrans (Budgets, Niveau de vie, Bilan, Phrases, Votes, Contexte, Villes, Rhétorique, Dossiers, Évaluateurs, Fiche identité, Accueil).
- [ ] Test : `identities_sorted_alphabetically` (tri insensible à la casse et aux accents).
- [ ] Remplacer chaque usage par le nouveau kit ; inventaire des textes d'aide remplacés par `InfoIcon` (liste au rapport) ; avertissements raccourcis.
- [ ] Commit `fix(mobile): écrans Politique allégés`.

### Task 3: Duel et Dit / voté
Points 1 et 3.
- [ ] Test : `balance_round_uses_side_switcher` (fonction pure qui choisit la mise en page côte à côte ou à onglets selon la longueur des textes du round).
- [ ] Composant à onglets glissables pour les rounds à texte long ; correctifs de rognage de la carte Dit / voté.
- [ ] Commit `fix(mobile): duel lisible sur téléphone, carte dit / voté sans rognage`.
