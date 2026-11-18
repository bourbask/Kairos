# « Vrai ou faux ? » : composer son propre paquet

Spec du 2026-11-18. Site Mimir d'abord ; parité dans l'écran Politique de
l'application ensuite (hors première livraison).

## 1. Intention

Les paquets de « Vrai ou faux ? » sont fixes : un par thème, plus « Toutes les
phrases ». Or la rubrique Affirmations sait déjà filtrer par mot-clé, thème et
camp. Des lecteurs ont demandé de pouvoir **jouer exactement ce qui les
intéresse** : « tout ce qui parle d'OQTF », « les phrases sur les femmes et la
santé », « uniquement ce que dit tel camp ».

Un paquet perso se compose avec les mêmes filtres que la rubrique, se joue comme
un paquet normal, et se **partage par lien** : on joue le même paquet entre amis.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**

- Composer un paquet par mot-clé, un ou plusieurs thèmes, un ou plusieurs camps.
- Voir en direct le nombre de cartes jouables.
- Lancer le même paquet depuis la rubrique Affirmations (« Jouer ces phrases »).
- Partager un paquet par lien ; le lien reconstruit le paquet chez l'autre.
- Retrouver son dernier paquet perso au retour sur l'écran de départ.

**Non-objectifs**

- Pas de sélection carte par carte (cocher des phrases une à une). Écarté : long,
  peu utilisé, et le lien ne resterait pas court.
- Pas de filtre par verdict : il dévoilerait les réponses.
- Pas de compte, pas de sauvegarde côté serveur, pas de classement.

**Anti-objectifs**

- Un écran de départ alourdi pour ceux qui jouent les paquets existants : la
  composition se replie, les paquets actuels restent visibles d'emblée.
- Deux logiques de recherche qui divergent entre la rubrique et le jeu.
- Un paquet perso qui casse l'alternance des camps ou la neutralité du score.

## 3. Comportement

### 3.1 Écran de départ

- Nouvelle tuile en tête de la liste des paquets : **« Composer mon paquet »**.
  Elle se déplie en place (pas de nouvelle page) :
  - champ de recherche (mêmes règles que la rubrique : sans accents ni casse,
    sur la phrase, le thème, le camp et les faits) ;
  - thèmes : puces multi-sélection (aucune = tous) ;
  - camps : puces multi-sélection, plus « Camp non établi » (aucune = tous) ;
  - compteur en direct : « 12 cartes · 3 camps » ;
  - bouton **« Jouer ces 12 cartes »**, inactif sous 3 cartes avec la raison
    (« Il faut au moins 3 cartes »).
- Si un paquet perso a déjà été joué sur cet appareil, une tuile **« Mon dernier
  paquet »** le résume (« OQTF · Immigration, Justice · 9 cartes ») et le relance
  en un appui.
- Logique booléenne : thèmes entre eux = OU ; camps entre eux = OU ; thèmes, camps
  et mot-clé entre eux = ET.

### 3.2 Depuis la rubrique Affirmations

- Sous les filtres, quand au moins 3 affirmations jouables correspondent : bouton
  **« Jouer ces N phrases »**. Il ouvre « Vrai ou faux ? » avec le paquet
  correspondant (filtres de la rubrique traduits en filtres du jeu ; le filtre de
  verdict de la rubrique est ignoré, et le bouton le dit s'il est actif :
  « verdict ignoré pour ne pas dévoiler les réponses »).

### 3.3 Partie

- Ordre : la règle actuelle (camps en alternance, « camp non établi » en
  dernier), appliquée au sous-ensemble.
- Barre de jeu et récapitulatif : le nom du paquet est son résumé
  (« Paquet perso : OQTF · Immigration »).
- Fin de partie : « Rejouer », « Autre paquet », et **« Partager ce paquet »**.

### 3.4 Lien de partage

- Le paquet est décrit par ses **filtres**, pas par une liste d'identifiants :
  `#vrai-faux?q=oqtf&t=immigration,justice&c=gauche,droite` (identifiants de
  thème et de camp, mot-clé encodé). Lien court, lisible, stable quand de
  nouvelles phrases s'ajoutent (elles entrent dans le paquet).
- Jetons : thème = slug de son libellé (`themeHash`, déjà utilisé par les
  rubriques) ; camp = slug de son libellé (`gauche`, `centre`, `droite`,
  `extreme-droite`) ; « Camp non établi » = `aucun`. Renommer un thème rend
  ce jeton inconnu dans les anciens liens : il est alors ignoré (pas d'erreur).
  Pas de nouvel identifiant dans les données.
- Tout est dans le **fragment** (`#…`) : rien n'est envoyé au serveur ni journalisé.
- Routage : la vue est ce qui précède `/` ou `?` dans le fragment. Un lien de
  paquet ouvert alors que le jeu est déjà chargé (bouton de la rubrique, lien
  collé) réaffiche l'écran de départ pré-rempli ; une partie en cours est
  abandonnée sans confirmation (elle ne compte rien de durable).
- Ouvrir un tel lien : l'écran de départ s'ouvre avec la composition dépliée et
  pré-remplie, compteur à jour, prêt à jouer (pas de lancement automatique).
- Paramètre inconnu ou thème disparu : ignoré sans erreur ; s'il ne reste rien,
  composition vide.
- « Partager » utilise le partage natif du téléphone s'il existe, sinon copie le
  lien avec un retour visible (« Lien copié »).

### 3.5 Mémoire locale

- Dernier paquet perso (ses filtres) gardé dans le stockage local, protégé par
  try/catch ; absent ou illisible → la tuile n'apparaît pas.

## 4. Technique

- **Une seule fonction de correspondance**, partagée par la rubrique et le jeu :
  `correspond(claim, { q, themes, camps })`, dans un module commun ; la rubrique
  l'utilise pour son mot-clé et son thème/camp actuels (cas d'un seul thème ou
  camp), le jeu pour le cas multiple.
- `deckOf` accepte un prédicat au lieu d'un thème unique ; les paquets de thème
  existants deviennent un cas particulier.
- Lecture et écriture du fragment : une fonction qui sérialise les filtres, une
  qui les relit en ignorant l'inconnu ; même découpage que l'ancre `#a-<id>`
  existante, sans casser celle-ci.
- CSP stricte inchangée : pas de script ni de style en ligne.
- Contrôle : un test (node, sans dépendance) sur `correspond`, la sérialisation
  aller-retour et le cas « paramètre inconnu ».

## 5. Accessibilité

- Puces = boutons `aria-pressed` ; compteur en `role="status"`.
- Tout est faisable au clavier ; les raccourcis du jeu ne s'activent pas tant que
  le focus est dans le champ de recherche.
- Bouton inactif : raison affichée en texte, pas seulement grisée.

## 6. Laissé au constructeur

- Visuel de la tuile repliée/dépliée, dans la grammaire pixel existante.
- Nombre de thèmes affichés avant « voir tous » sur téléphone.
- Formulation exacte du résumé de paquet quand beaucoup de filtres sont actifs
  (troncature).
