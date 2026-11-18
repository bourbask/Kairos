# Aides de lecture : la mascotte explique les termes, phrases et graphiques

Spec du 2026-11-18. Site Mimir d'abord, écran Politique de l'application ensuite.
S'appuie sur la DA pixel (branche `feat/da-pixel`), dont elle réutilise la mascotte
et le médaillon. Maquette validée : `private/maquettes/crible-aides/aides.html`
(hors dépôt) ; elle fait foi pour le rendu. Choix retenus : la mascotte, détourée
d'une case sombre hors de sa tuile, se pose dans l'en-tête de la bulle (jamais sur
le texte lu) ; médaillon actif enfoncé avec voyant vert ; élément expliqué surligné
légèrement.

## 1. Intention

Le site sert à apprendre. Or un lecteur sans bagage en politique, en économie ou en
statistiques bute sur une partie du contenu :

- des **termes** : sigles et notions (OQTF, PIB, 49.3, déficit structurel…) ;
- des **phrases** : tournures denses, raisonnements en plusieurs étapes ;
- des **graphiques** : ce qu'on lit sur un axe, ce que montre une courbe (par
  exemple, la dette en % du PIB projetée à 2032 selon chaque candidat).

Les aides de lecture donnent, pour ces éléments, une explication simple, et un
détail pour qui veut aller plus loin. C'est la mascotte qui les donne.

**Lecteur visé** : un adulte curieux, sans connaissance particulière en politique,
en maths ni en sciences sociales. On ne suppose **pas** de culture générale large :
pas de référence historique, juridique ou savante non expliquée. Niveau de langue :
fin de collège.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**

- Tout élément technique repéré a une aide : définition simple (1 à 2 phrases),
  détail facultatif, source quand l'aide énonce un fait.
- Les aides sont **cachées par défaut**. On les affiche à la demande, depuis la
  mascotte, et le choix est mémorisé.
- Même usage au doigt, à la souris et au clavier.
- Un nouveau lecteur découvre la fonction sans qu'on le dérange.

**Non-objectifs**

- Pas d'encyclopédie : une aide explique le terme **dans le contexte du site**,
  pas tout ce qu'on peut en dire.
- Pas d'aide générée à la volée : tout est rédigé, relu et versionné.
- Pas de glisser-déposer du médaillon sur un élément (difficile à découvrir,
  impraticable au clavier). Écarté.

**Anti-objectifs**

- Une page criblée de soulignements. Un lecteur qui n'utilise pas la fonction ne
  doit rien voir de plus qu'aujourd'hui.
- Une aide qui prend parti. Elle explique, elle ne juge pas : même exigence de
  neutralité que les verdicts.
- Une bulle qui bloque la lecture ou le jeu.

## 3. Comportement

### 3.1 La mascotte, point d'entrée unique

- **Premier chargement de la session** : la mascotte apparaît en grand dans le hero
  avec une bulle unique : « Un mot vous échappe ? Je suis là. »
- Au premier défilement, après environ 2,5 s ou au premier clic, elle rejoint le
  **médaillon** flottant, où elle reste sur tout le site jusqu'à la fin de la session.
- Le médaillon est un bouton : un appui **affiche ou masque les aides** de la page
  (`aria-pressed`). Raccourci clavier : `?` (inactif dans un champ de saisie).
- L'état affiché/masqué est mémorisé sur l'appareil. Défaut : masqué.
- **Rappel discret** : tant que le lecteur n'a jamais activé les aides, la mascotte
  fait un petit sursaut à l'arrivée sur une page riche en aides. Trois fois au plus,
  puis plus jamais. Aucune bulle.
- Réglages : « Aides de lecture : masquées par défaut / toujours visibles ».

### 3.2 Repérage des éléments quand les aides sont affichées

Deux marques, selon la taille de ce qu'on explique :

| Élément | Marque | Règle de densité |
|---|---|---|
| Terme, sigle | soulignement pointillé pixel | première occurrence par carte ou section ; trois marques au plus par carte |
| Phrase, paragraphe, graphique | pastille « ? » pixel en fin de phrase ou au coin du bloc | une par bloc |

Quand les aides sont masquées, aucune marque n'est rendue visible : le texte est
identique à aujourd'hui.

### 3.3 Afficher une aide

- Activer une marque (clic, toucher, Entrée ou Espace) : la mascotte quitte le
  médaillon, se pose à côté de l'élément, le « regarde », puis ouvre une bulle :
  explication simple, lien « En savoir plus » vers le détail, source si présente.
- Fermer : Échap, clic à côté, nouvel appui sur la marque. La mascotte rentre dans
  le médaillon. Le focus revient à la marque.
- Sur téléphone, la bulle s'ancre en bas de l'écran (feuille), la mascotte au-dessus.
- Animations : 150 à 350 ms, en paliers ; `prefers-reduced-motion` → pas de
  déplacement, la bulle s'ouvre directement.
- Pendant une partie (Jeux), les aides restent accessibles mais la mascotte ne
  quitte pas le jeu si elle y est intégrée : la bulle s'ouvre seule.

### 3.4 Graphiques

Chaque graphique a une aide « Comment lire ce graphique » (2 à 4 phrases) : ce que
représente chaque axe, chaque couleur, et la lecture principale sans conclusion
partisane.

## 4. Données

Fichier `site/data/aides.json` :

```json
{
  "termes": [
    {
      "id": "oqtf",
      "formes": ["OQTF", "obligation de quitter le territoire français"],
      "simple": "…",
      "detail": "…",
      "source": ["libellé", "https://…"]
    }
  ],
  "blocs": [
    { "cible": "graphique:dette-2032", "simple": "…", "detail": "…" },
    { "cible": "affirmation:<id>", "simple": "…" }
  ]
}
```

- **Termes** : repérés automatiquement dans le texte rendu (formes exactes, insensible
  à la casse, frontières de mot), avec les règles de densité du §3.2. Jamais dans un
  titre, un lien ou un bouton.
- **Blocs** : rattachés par un identifiant stable (`graphique:…`, `affirmation:<id>`,
  `section:…`).
- Contrôle `scripts/controle-aides.py` : identifiants uniques, cibles existantes,
  formes non ambiguës (une forme ne pointe que vers un terme), longueurs bornées
  (`simple` ≤ 280 caractères, `detail` ≤ 900), source obligatoire si l'aide contient
  un chiffre.

## 5. Rédaction et relecture

1. Inventaire : repérage des candidats dans les affirmations, les rubriques et les
   graphiques. Estimation : 80 à 150 termes, une quarantaine de blocs.
2. Rédaction au niveau du §1, puis relecture indépendante : exactitude, neutralité,
   niveau de langue.
3. Relecture humaine d'un échantillon et de tout ce que la relecture indépendante
   a marqué en doute, comme pour les justifications.

## 6. Application

Même fichier servi par l'API du module Politique ; même logique dans l'écran
natif (mascotte, médaillon, marques). Hors périmètre de la première livraison.

## 7. Laissé au constructeur

- Détection des termes : côté rendu (JS) plutôt que balisage manuel dans les données.
- Positionnement exact de la bulle et de la mascotte selon la place disponible.
- Visuel des marques, dans la grammaire pixel existante.
