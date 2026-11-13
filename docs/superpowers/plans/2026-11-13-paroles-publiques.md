# Espace Politique — rubrique « Paroles publiques face aux faits » — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Afficher dans l'espace Politique les déclarations publiques de responsables (exécutif et oppositions) confrontées aux faits.

**Tech Stack:** Rust (module `politique`), Kotlin / Compose (application).

## Contrat des données (fichier `declarations`, servi par `app_politique_fichier`)

`{ "DECLARATIONS": [{ id, qui (id d'identité ou nom), parti?, fonction, pouvoir: "gouvernement"|"opposition"|"autre", date (AAAA-MM-JJ), contexte, citation, source: [libellé, url, minutage?], verdict: "auto-contradiction"|"contredit-justice"|"contredit-document"|"contredit-preuve"|"exact"|"non-verifiable", faits: [..], preuves: [[libellé, url, ev?]], reponse?, procede? }] }`

## Exigences

1. Ordre de solidité des verdicts, du plus objectif au moins : auto-contradiction, décision de justice, document officiel, preuve matérielle publiée ; puis « exact » (déclaration contestée qui se révèle vraie) et « non vérifiable ». Couleurs d'état neutres, jamais celles d'un parti.
2. La parole du gouvernement est marquée comme telle (elle engage l'État) ; même grille, même rendu, pas de barème différent.
3. Carte : qui (puce d'identité), fonction au moment des faits, date, citation en grand, verdict, faits, preuves (sources nommées, https seulement, étiquette d'évaluateur), réponse de l'intéressé si elle existe (toujours visible, pas en infobulle), procédé lié vers sa carte de rhétorique.
4. Filtres en pilules : pouvoir (tous / gouvernement / opposition), verdict, camp ; tri par date décroissante ; plein écran (« à table ») ; ordre alphabétique des identités dans les filtres.
5. Fiche identité : onglet « Paroles » quand l'identité a des déclarations.
6. Accueil : tuile de rubrique (titre « Paroles publiques », accroche courte).
7. Dépôt public : aucun nom réel dans le code, les tests, les fixtures.

## Global Constraints

- `git commit --no-gpg-sign`, sans attribution ; tests : `cargo test -p kairos-politique` ; `cd mobile && ./gradlew --no-daemon testDebugUnitTest assembleDebug`.
- Vérification visuelle sur émulateur (procédure et faux serveur du contexte commun), captures relues.

---

### Task 1: Module et application
- [ ] Module : titre de rubrique `("declarations", "Paroles publiques", "Déclarations de responsables confrontées aux faits.")`, fixture fictive `tests/donnees/declarations.json`, test de titre ; échantillon régénéré.
- [ ] Application : `"declarations"` dans `POL_FILES` ; modèles tolérants ; logique pure testée (`declarations_sorted_and_filtered`, `verdict_strength_order`, `government_flagged`) ; écran ; onglet de la fiche ; tuile d'accueil ; captures émulateur.
- [ ] Commits thématiques.
