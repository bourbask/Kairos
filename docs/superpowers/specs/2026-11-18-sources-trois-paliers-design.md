# Sources en trois paliers, sur le site et dans l'application

Spec du 2026-11-18. Site Mimir et écran Politique de l'application Kairos, à
parité.

## 1. Intention

Chaque chiffre du Crible porte une étiquette de source : qui l'a produit, avec
une pastille de 1 à 4 qui dit à quel point ce producteur est indépendant. C'est
le cœur de la méthode, et c'est aujourd'hui la partie la moins lisible :

- **le chiffre de la pastille n'a pas de légende** à l'endroit où on le voit ;
- l'application a trois paliers (étiquette → fiche du producteur → explication
  de tous les niveaux), mais le passage du deuxième au troisième se fait par un
  bouton tout en bas d'une longue fiche : on ne le trouve pas ;
- le site n'a que deux paliers : sa fiche est plus pauvre que celle de
  l'application (pas de jauge, pas de dirigeants, d'historique ni de
  controverses), et aucune explication des niveaux n'existe nulle part.

Le lecteur doit pouvoir, en deux gestes au plus, passer de « un 3 dans une
pastille » à « je comprends ce que veut dire 3 et pourquoi ce producteur l'a ».

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**

- Mêmes trois paliers, même contenu, même ordre sur le site et dans l'application.
- Le palier 3 est atteignable **depuis le haut** de la fiche, à côté de la jauge.
- Les libellés et définitions des niveaux viennent **d'une seule source de
  données**, lue par le site et par l'application.
- Les sources sans fiche (« ? ») suivent le même chemin.

**Non-objectifs**

- Pas de nouvelle fiche d'évaluateur (les 22 sources « pas encore analysées »
  sont un autre chantier).
- Pas de changement des niveaux attribués ni de leurs critères.
- Pas de refonte des étiquettes elles-mêmes au-delà de ce qui est dit au §3.1.

**Anti-objectifs**

- Une étiquette alourdie qui casse les rangées de sources (largeur, hauteur).
- Un palier 3 qui fait quitter la page ou perdre la fiche en cours.
- Une explication qui laisse croire que le niveau note la qualité d'un chiffre :
  il décrit les **liens** du producteur (statut, financement, dirigeants).

## 3. Les trois paliers

### 3.1 Palier 1 — l'étiquette

- Inchangée dans sa forme : pastille du niveau (1 à 4, « ? » sans fiche) + nom
  court du producteur, à gauche ; lien vers l'article, à droite.
- Ajout : une **infobulle** au survol ou au focus de la partie gauche (site) et
  la description accessible (site et application) donnent le libellé du niveau
  — « Niveau 3 · Lié à un intérêt économique ou à un camp » — puis « Ouvrir sa
  fiche ». Sur téléphone, pas d'infobulle : le toucher ouvre la fiche, qui la
  remplace.

### 3.2 Palier 2 — la fiche du producteur

Même contenu et même ordre sur le site et dans l'application :

1. type de producteur, nom ;
2. **bloc Indépendance** : libellé du niveau, **jauge pixel de 4 cases** (la case
   du niveau pleine, repères « 1 · statutaire » et « 4 · militant »), la
   justification du niveau, et juste dessous le bouton **« Comprendre les
   niveaux »** (palier 3) ;
3. statut, financement, dirigeants, orientation, tendance observée selon les
   camps, historique, controverses, sources de la fiche (chaque bloc n'apparaît
   que s'il a du contenu ; « tendance » affiche sa mention « non mesurée »
   comme dans l'application) ;
4. en bas, « Tous les évaluateurs » (application : l'écran existant ; site : le
   palier 3 ouvert sur la liste).

Source sans fiche : même fenêtre, nom, jauge vide « Indépendance non
qualifiée », texte actuel (« pas encore analysée… »), et le même bouton
« Comprendre les niveaux ».

### 3.3 Palier 3 — comprendre les niveaux

S'ouvre **dans la même fenêtre** (site : même `<dialog>` ; application : même
feuille), avec un bouton **« ‹ Retour à la fiche »** qui revient au palier 2 à
la même position. Échap / geste retour : revient d'abord au palier 2, puis
ferme.

Contenu :

1. une phrase de principe : « Le niveau décrit les liens du producteur (statut,
   financement, dirigeants), pas la qualité d'un chiffre. Chaque fiche justifie
   le sien. » ;
2. les **quatre niveaux**, puis « Non qualifié » : étiquette du niveau, libellé,
   définition en une ou deux phrases, critères observés ; **le niveau du
   producteur d'où l'on vient est mis en évidence** (« ← niveau de
   [nom court] ») ;
3. sous chaque niveau, repliée, la liste des producteurs de ce niveau (nom,
   ouvre sa fiche au palier 2).

Ouvert depuis « Tous les évaluateurs » (site), aucun niveau n'est mis en
évidence.

## 4. Données

Ajout à `site/data/evaluateurs.json` d'une clé `NIVEAUX`, lue par le site et
servie à l'application par le module Politique :

```json
"NIVEAUX": [
  { "n": 1, "libelle": "Indépendance statutaire et contrôlée", "definition": "…", "criteres": ["…"] },
  { "n": 2, "libelle": "Indépendant, orientation connue", "definition": "…", "criteres": ["…"] },
  { "n": 3, "libelle": "Lié à un intérêt économique ou à un camp", "definition": "…", "criteres": ["…"] },
  { "n": 4, "libelle": "Partisan ou militant", "definition": "…", "criteres": ["…"] },
  { "n": 0, "libelle": "Non qualifié", "definition": "…", "criteres": [] }
],
"PRINCIPE": "Le niveau décrit les liens du producteur (statut, financement, dirigeants), pas la qualité d'un chiffre. Chaque fiche justifie le sien."
```

- Les libellés actuels (`IND_LABEL` du site, `IND_LABELS` de l'application)
  deviennent des **replis** quand la clé manque (application qui lit des données
  plus anciennes) ; les deux codes lisent d'abord `NIVEAUX`.
- Définitions et critères : rédigés à partir des justifications existantes des
  fiches (ce que les niveaux recouvrent déjà en pratique), neutres, niveau de
  langue fin de collège ; relus avant publication.
- Contrôle : un script existant (`controle-sources.py` ou voisin) vérifie que
  `NIVEAUX` couvre exactement 0 à 4, libellés non vides, et que chaque fiche a
  un niveau couvert.

## 5. Accessibilité et rendu

- Site : un seul `<dialog>` ; palier 3 = contenu remplacé, titre focalisé ;
  retour = focus rendu au bouton « Comprendre les niveaux ». CSP stricte
  inchangée.
- Application : feuille existante ; palier 3 = état de la feuille ; retour
  système revient au palier 2.
- Jauge : décrite en texte (« Niveau 3 sur 4 »), jamais la couleur seule.
- DA pixel des deux côtés (jauge en cases, touches, étiquettes existantes).

## 6. Choix retenus sur maquette

Maquette : `private/maquettes/crible-sources/sources.html` (hors dépôt), elle
fait foi pour le rendu.

- Infobulle : « Niveau N sur 4 », libellé, « Ouvrir sa fiche » ; souris et focus
  clavier seulement.
- Jauge : seule la case du niveau est pleine, en couleur d'encre.
- « Comprendre les niveaux » : touche teintée sous la justification.
- Palier 3 : bouton « ↓ Voir le niveau N » qui fait défiler jusqu'au niveau
  mis en évidence ; marque « ← niveau de ce producteur » (pas d'élision
  automatique du nom).
- Listes de producteurs repliées sous chaque niveau.
- Définitions des niveaux : relecture indépendante, puis échantillon relu par
  un humain avant publication.
- Correctif inclus : le contour de focus des étiquettes de source est
  aujourd'hui rogné par les coins découpés ; il entoure désormais toute la
  touche.

## 7. Laissé au constructeur

- Visuel exact de l'infobulle (site) dans la grammaire pixel.
- Mise en page du palier 3 sur téléphone (liste repliée par défaut).
- Découpage du code de la fiche du site (un module dédié si `sources.js`
  grossit trop).
