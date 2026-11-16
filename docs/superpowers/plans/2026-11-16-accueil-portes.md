# Espace Politique — accueil par portes, gravité des promesses, cartes « Ça vient d'où ? » — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ranger l'accueil de l'espace Politique en quatre portes, ajouter une échelle de gravité des promesses et des cartes courtes « Ça vient d'où ? ».

**Tech Stack:** Rust (module `politique`), Kotlin / Compose (application).

**Maquette validée (variante A) :** hors dépôt ; décrite ci-dessous.

## Accueil (variante A)

1. En tête, inchangés : texte d'intro, Duel, Vrai ou faux, « Les visages ».
2. Puis section « Explorer » : quatre portes, cartes pleine largeur, chacune avec sa teinte propre (neutre, jamais une couleur de parti ; clair et sombre), un sur-titre court, une question en titre, ses rubriques en pastilles (avec compteur quand il existe), et une accroche « du jour » tirée des données (choix stable sur la journée).
   - **Vérifier** — « Cette phrase, vraie ? » : Phrases vérifiées, Paroles publiques, Dossiers, Rhétorique, Ça vient d'où ?
   - **Chiffrer** — « Ce programme, sérieux ? » : Budgets, Gravité des promesses, Niveau de vie, Qui chiffre ?
   - **Bilan** — « Ce qu'ils ont fait » : Bilans, Votes, Dit / voté, Revirements, Résultats locaux.
   - **Pouvoirs** — « Qui tire les ficelles » : Argent, Probité, Médias.
   - Toute rubrique existante non citée va dans la porte la plus proche ; le rapport liste ces choix.
3. Toucher une porte ouvre sa page : titre de la porte, puis ses rubriques en tuiles (réutiliser `Tile` et ses mini-visualisations). Toucher une pastille ouvre directement la rubrique (raccourci, pas de clic supplémentaire).
4. Plus de grille « Rubriques » ni de liste « Lire entre les lignes » sur l'accueil.

## Gravité des promesses (fichier `promesses`, nouveau)

`{ "PROMESSES": [{ id, qui (id d'identité), theme, promesse, gravite: 1|2|3|4, pourquoi, fondement?: [libellé, url], src: [[libellé, url, ev?]] }] }`

| gravite | libellé | sens |
|---|---|---|
| 1 | Désaccord normal | arbitrage de valeurs, chiffrage contesté |
| 2 | Promesse creuse | non financée, irréaliste, ou déjà tentée sans résultat |
| 3 | Contraire au droit | exige une révision constitutionnelle ou la sortie / violation d'un traité ou de la CEDH |
| 4 | Atteinte aux droits fondamentaux | touche aux libertés fondamentales ou à l'État de droit |

Écran : échelle en tête (quatre marches, couleurs d'état neutres croissantes), filtres en pilules (identité, gravité, thème), cartes : identité (puce), promesse, gravité, pourquoi, fondement juridique visible, sources. Fiche identité : onglet « Promesses » si elle en a.

## Ça vient d'où ? (sans nouveau fichier)

Paquet de cartes tiré des phrases vérifiées qui ont un champ `origine` : recto = la phrase ; retourner = d'où vient l'idée (`origine`), ce qu'elle cache (`cache`), verdict, une source. Glisser pour la suivante, ordre aléatoire stable par session. Trente secondes par carte : pas de texte au-delà de ces champs.

## Global Constraints

- Dépôt public : aucun nom réel dans le code, les tests, les fixtures, les commits.
- Couleurs d'état neutres ; couleur d'identité seulement pour les acteurs.
- Accessibilité : rôles et libellés sur les cartes cliquables, cibles ≥ 48 dp, animations coupées si le système les coupe.
- `git commit --no-gpg-sign`, sans attribution ; tests : `cargo test -p kairos-politique` ; `cd mobile && ./gradlew --no-daemon testDebugUnitTest assembleDebug`.
- Vérification visuelle sur émulateur (clair, sombre), captures relues.

---

### Task 1: Module
- [ ] Titre de rubrique `("promesses", "Gravité des promesses", "Promesses classées de la simple divergence à l'atteinte aux droits.")`, fixture fictive, test.

### Task 2: Application
- [ ] `"promesses"` dans `POL_FILES` ; modèles tolérants ; logique pure testée : `doors_cover_every_rubric` (chaque rubrique dans exactement une porte), `daily_teaser_stable_for_a_day`, `promises_sorted_and_filtered`, `origin_deck_only_claims_with_origine`.
- [ ] Accueil à portes, page de porte, écran Gravité, paquet « Ça vient d'où ? », onglet de fiche ; captures émulateur.
- [ ] Commits thématiques.
