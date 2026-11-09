# Application — refonte UX et accessibilité, écran par écran

Complète `2026-11-08-app-mobile-design.md`, `2026-11-08-app-0.4.0-design.md` et
`2026-11-08-app-espaces-design.md`. Audit du 2026-11-09 sur l'émulateur
(serveur factice, toutes les données de démonstration) et sur le code de
`mobile/app/src/main/java/app/kairos/mobile/`. **Refonte libre accordée**,
à valider avant réalisation. Les écrans marqués « (code) », le thème sombre et
la grande police ont été évalués sur le code et la palette : l'émulateur est
devenu injoignable en cours d'audit ; à confirmer à l'écran pendant la
réalisation.

## Intention

L'application a trouvé son identité (personnage, fougère, deux onglets et
Kairos au centre, grammaire de gestes). Elle a grandi espace par espace, chacun
à peu près juste en soi ; l'ensemble perd en cohérence et en accessibilité :

- chaque geste a bien un bouton visible, **sauf sur Aujourd'hui** (il faut
  d'abord déplier la carte) ; et **aucun** glissement n'est exposé aux lecteurs
  d'écran (une seule action d'accessibilité dans toute l'application : « Parler ») ;
- l'ambre sert de texte et de fond de texte blanc, à un contraste de 2,3 à 2,5:1 ;
- l'Agenda dessine perso et travail **de la même couleur** (les deux rôles
  Material pointent sur Pousse) ;
- six hôtes de snackbars, trois styles de bandeau hors ligne, quatre façons de
  fermer un panneau, deux composants « Ajouter » différents ;
- un module en erreur s'affiche comme vide (« 0 produit », zones « vide ») et la
  carte Espaces ne le signale pas.

Objectif : que chaque écran réponde en **un coup d'œil et un geste** à la
question pour laquelle on l'ouvre, au pouce, en public comme en cuisine, avec un
lecteur d'écran comme sans, en grande police comme en petite.

## Principes transverses

1. **Une question par écran.** Le haut de l'écran répond à la question
   principale (ce qui est en cours, ce qui reste, ce que je peux regarder) ;
   le reste vient dessous, par ordre de fréquence d'usage.
2. **Action principale sous le pouce.** Une seule action principale par espace,
   toujours au même endroit : bouton étendu en bas à droite, au-dessus de la
   barre, composant commun. Les actions secondaires vont dans l'en-tête de
   section concernée.
3. **Tout geste a son bouton, et son action d'accessibilité.** Visible sans
   déplier pour l'action positive. Chaque élément glissable expose ses actions
   (`customActions`) : « Fait », « Plus tard », « Consommé », « Vu »…
4. **Trois états toujours dessinés** : chargement (squelette ou phrase),
   erreur (bandeau commun avec « Réessayer » et âge des données), vide (phrase +
   personnage au repos). Jamais d'erreur déguisée en vide.
5. **La couleur n'est jamais seule.** Toute information portée par une couleur
   (urgence, dépassement, tendance, calendrier) est doublée d'un mot, d'une
   flèche ou d'une forme.
6. **Contraste AA partout** (texte 4,5:1, éléments d'interface 3:1), dans les
   deux thèmes.
7. **Une fermeture, une place.** Espace plein écran : chevron retour en haut à
   gauche. Panneau né du bouton Kairos ou d'une carte (conversation, Décider,
   Ranger, Ajouter, Réglages) : bouton « Fermer » (icône chevron bas, 48 dp) en
   haut à gauche aussi, plus le retour système. Le geste ↓ reste un raccourci.
8. **Une seule file de messages** (snackbars) pour toute l'application, au
   même endroit (au-dessus de la barre), annulation comprise.
9. **Texte qui grandit, mise en page qui suit.** Aucune ligne de contrôle
   n'est figée en largeur ; à partir de 1,3× les rangées de boutons passent sur
   deux lignes. Aucun `maxLines = 1` sur un chiffre clé sans repli.
10. **Vocabulaire de l'utilisateur.** Pas de « module », « réponse illisible »,
    « source » à l'écran ; on parle de l'espace (« Inventaire indisponible »).

## Navigation (modèle d'ensemble)

**Constat.** La coque garde deux onglets et Kairos au centre : c'est juste et
reste. Les espaces sont des superpositions (`Overlay`) qui naissent de leur
carte et y retournent, retour prédictif compris : bon. Mais :

- Réglages ne s'ouvre que depuis l'engrenage d'Aujourd'hui ;
- le masquage des montants ne se règle que dans Finances alors qu'il vaut pour
  tous les espaces (et pour le journal sensible de Mémoire) ;
- l'en-tête d'Aujourd'hui porte trois commandes (↻, Semaine, ⚙) dont une
  redondante (actualisation automatique au retour + tirer pour actualiser) ;
- le bouton Kairos n'annonce pas son état aux lecteurs d'écran (« Kairos »
  seul, alors que le personnage dit « réfléchit », « attend »…).

**Refonte.**

- P1 — `Shell.kt`, `KairosButton` : `stateDescription` = état du personnage
  en mots (« à l'écoute », « réfléchit », « réponse arrivée », « 3 décisions
  en attente »).
- P2 — Réglages accessibles aussi depuis l'onglet Espaces (ligne « Réglages »
  en bas de la liste) ; l'engrenage d'Aujourd'hui reste.
- P2 — « Masquer les montants » devient un réglage global (interrupteur dans
  Réglages) ; l'œil reste dans l'en-tête de Finances comme raccourci, et
  apparaît aussi dans Mémoire (journal sensible).
- P2 — Retirer « ↻ » de l'en-tête d'Aujourd'hui (le tirer-pour-actualiser
  et le bandeau hors ligne « Réessayer » suffisent).
- P3 — Badge numérique sur l'onglet Espaces quand un espace attend une action
  (commande à ranger, intention échue bientôt, propositions à trier).

---

## Appairage

**Rôle.** Une fois par appareil (ou après révocation) : scanner un QR code
affiché sur le serveur. Contexte : assis, deux mains, téléphone devant l'écran.

**Constats.**
- Parcours clair : scanner d'abord, coller l'adresse en repli, messages
  d'erreur rédigés pour l'utilisateur (« Code refusé… Génère-en un nouveau »).
- L'indicateur de chargement (48 dp) n'est pas annoncé ; le message d'erreur
  n'est pas une région vivante.
- Le champ de repli est libellé par un exemple de schéma d'adresse : technique.

**Refonte.**
- P1 — Message d'erreur en `liveRegion = Polite` ; bouton « Appairer » qui
  devient « Vérification… » pendant l'appel (état lisible, pas seulement un
  rond qui tourne).
- P3 — Personnage au repos en tête (identité dès le premier écran) ; libellé
  du champ « Code d'appairage (si le scan échoue) ».

## Aujourd'hui — fil du jour

**Rôle.** L'écran le plus ouvert : plusieurs fois par jour, souvent d'une main,
debout (cuisine, transport). Questions : « qu'est-ce qui vient maintenant ? »,
« ai-je quelque chose à décider ? », et noter « fait » en un geste.

**Constats.**
- En-tête surchargé : salutation + date + lendemain sur la moitié gauche, trois
  commandes à droite ; la ligne « Jour J · Demain : mode de travail » passe sur
  deux lignes dès la taille normale ; à 1,3× la colonne de gauche se réduit
  encore (largeur fixe des trois boutons). « ↻ » et « ⚙ » sont des caractères
  de texte, pas des icônes (rendu variable, lus « ↻ » par certains lecteurs).
- La carte en cours n'est distinguée que par un point plein sur le rail ;
  l'heure est en gris (`onSurfaceVariant`) au même poids pour toutes.
- **« Fait ✓ » / « Plus tard » ne sont visibles qu'après un tap qui déplie la
  carte** : 2 taps pour l'action principale, et le principe « un bouton pour
  chaque geste » n'est tenu qu'à moitié.
- Aucune action d'accessibilité sur les cartes glissables (`SwipeRow`).
- Glissement « Plus tard » : fond ambre, texte blanc → 2,53:1 (échec AA).
- Pastilles d'attention : ambre assombri par calcul (`darken()`), correct en
  clair, mais la règle vit dans `TodayScreen.kt` au lieu du thème.
- Carte « marquée » à `alpha 0.55` : le texte descend sous 3:1.
- L'écran a son propre hôte de snackbars **en plus** de l'hôte global : un
  « Fait · … Annuler » local et un « Repas noté » global peuvent s'afficher en
  même temps, l'un sur l'autre, à des hauteurs différentes (112 dp contre
  hauteur de barre).
- Bandeau de mise à jour et bandeau hors ligne bien placés (en tête), mais de
  styles différents du `StatusBanner` des espaces.
- Vide : personnage + « Rien de prévu aujourd'hui. » : bon.

**Refonte.**
- P1 — **Bouton « Fait » visible** sur chaque carte actionnable : cercle de
  coche 48 dp à droite de la carte (à la place de l'heure, qui passe sous le
  titre), libellé TalkBack « Marquer fait : <titre> ». « Plus tard » et
  « Remettre dans le fil » restent dans la carte dépliée.
  (`TodayScreen.kt`, `ItemCard`.)
- P1 — `SwipeRow` (partagé) expose `customActions` avec `rightLabel` et
  `leftLabel` ; une carte non glissable n'en expose pas.
  (`today/TodayScreen.kt` → déplacé dans `ui/kit/SwipeRow.kt`, voir
  Composants.)
- P1 — Couleur du glissement négatif : fond `attention` avec texte `Encre`
  (6,1:1) au lieu de blanc ; l'alpha d'une carte marquée passe de 0,55 à 0,7 et
  le libellé « fait » / « plus tard » devient une pastille, pas un gris pâle.
- P1 — Snackbars : suppression de l'hôte local, passage par la file commune
  (voir Composants, `Notifier`).
- P2 — **En-tête recomposé** : ligne 1 « Bonsoir » + engrenage (icône dessinée
  48 dp) ; ligne 2 date ; ligne 3 « Demain : sur site » en pastille. La commande
  « Semaine » descend à droite de l'étiquette « FIL DU JOUR » (bascule
  Jour / Semaine en `SegmentedButton` à deux choix, 40 dp de haut, cible 48).
- P2 — **Carte « maintenant » mise en avant** : fond `primaryContainer`, heure
  en `primary` gras, libellé « maintenant » ou « dans 25 min » ; les cartes
  passées repliées sous une ligne « 3 plus tôt · afficher » en tête du fil.
- P2 — Aperçu de décisions : le sous-titre « glisse pour trier » devient « ≈ 1
  min » seul (le geste n'est pas la seule voie) ; `contentDescription`
  « 3 décisions en attente, environ 1 minute. Ouvrir. »
- P3 — Pincer/écarter conservé, mais l'indice visuel (bascule segmentée)
  suffit ; aucune autre aide.

## Aujourd'hui — vue semaine

**Rôle.** Coup d'œil du dimanche soir ou du matin : repas planifiés et
rendez-vous des 7 jours, rythme de travail. Lecture seule.

**Constats.**
- Lecture claire (jour, mode de travail, heure + titre), cartes par jour.
- Les jours ne mènent nulle part : impossible d'ouvrir l'Agenda à ce jour.
- Le mode de travail est en `primary` sur blanc (4,65:1, correct) mais sans
  forme : rien ne le distingue d'un titre.
- Un élément sans heure s'affiche « — » (lu « tiret » par TalkBack).

**Refonte.**
- P2 — Tap sur un jour = ouvre l'espace Agenda sur ce jour (même superposition
  que depuis Espaces). Ligne `contentDescription` fusionnée par élément
  (« 15 h 30, Rendez-vous »), « sans heure » au lieu de « — ».
- P3 — Mode de travail en pastille (même composant que dans Agenda).

## Décider (pile de cartes)

**Rôle.** Vider la pile en moins d'une minute, une à quelques fois par jour,
d'une main, parfois en public. Le geste → doit suffire ; les boutons doivent
être aussi rapides que le geste.

**Constats.**
- La carte occupe 92 % de la hauteur alors que son contenu en remplit la
  moitié : grande surface vide, et les boutons sont repoussés tout en bas,
  éloignés de ce qu'ils valident.
- Les cinq actions sont des `TextButton` de même poids : « oui → » est en gras
  mais pas plus gros ni rempli ; « ← autre » ne dit pas ce qui se passera ;
  le libellé de la proposition (« Loisirs ») n'est pas sur le bouton.
- Les marges latérales des gestes système rétrécissent la carte (bon pour
  éviter le geste retour, mais la carte paraît flotter).
- Libellé de module en tête : identifiant technique mis en capitale
  (« Finances », « Marchés ») — lisible ici, mais dépend de l'identifiant.
- Aucune action d'accessibilité sur la carte ; TalkBack lit la carte puis cinq
  boutons, l'ordre est correct.
- Message de refus en `error` sous la proposition : bien, mais non annoncé.
- Hors ligne : texte d'avertissement en brique sous la carte, mais la carte
  reste glissable vers le haut (plus tard) — cohérent.

**Refonte.**
- P1 — **Barre d'actions sous le pouce** : bouton rempli pleine largeur 56 dp
  « Oui · <proposition> » (ou « Choisir… » sans proposition) ; en dessous, deux
  boutons contour 48 dp « Autre choix » et « Plus tard ». « Fermer » passe en
  haut à gauche (principe 7), « Annuler » reste en haut à droite à côté du
  compteur « 2 / 3 ».
- P1 — Carte à la hauteur de son contenu (`wrapContentHeight`, minimum 50 % de
  l'aire), centrée verticalement ; les alternatives s'ouvrent juste sous elle.
- P1 — `customActions` sur la carte : « Accepter <proposition> »,
  « Autre choix », « Plus tard », « Fermer la pile » ; message de refus en
  `liveRegion`.
- P2 — En-tête : nom d'espace fourni par la tête (libellé humain) quand il
  existe, sinon l'identifiant mis en forme ; barre de progression avec
  `contentDescription` « 1 sur 3 ».
- P3 — Fin de pile : « C'est fait » + nombre de décisions prises (« 3 classées »).

## Parler — bouton Kairos, conversation, dictée

**Rôle.** Demander quelque chose à Kairos de n'importe où : talkie-walkie
(appui long) dans la cuisine ou en marchant, écrit (tap) en public. Réponse
lente (~10 s) : l'attente doit être lisible et quittable.

**Constats.**
- Bouton central 68 dp, ombre fougère, personnage animé : identité forte, cible
  généreuse, action « Parler » exposée à TalkBack. Très bien.
- Panneau de dictée : ondes, transcription en direct, « ‹ glisser pour
  annuler », verrou ↑ : bonne grammaire. La transcription n'est pas une région
  vivante ; son `contentDescription` « Transcription : » est lu vide au début.
- Conversation : en-tête avec personnage et état (« réfléchit… »), bulles
  lisibles, saisie avec micro qui devient « Envoyer » : bon. « Fermer » en
  chevron bas à droite (ailleurs : « Fermer » texte, ou chevron retour à
  gauche).
- Messages en attente à `alpha 0.6` + « envoi… » : un échec d'envoi n'a pas
  d'état propre (pas de « Réessayer » sur la bulle).
- Snackbars en haut quand la conversation est ouverte, en bas sinon : choix
  défendable (le clavier), mais à formaliser dans la file commune.
- Vide : « Écris-moi, ou garde le doigt sur le micro pour parler. » : bon.

**Refonte.**
- P1 — Transcription en `liveRegion = Polite`, description vide tant que rien
  n'est reconnu (« Je t'écoute »).
- P2 — Bulle en échec : liseré brique, « Non envoyé · Réessayer » (bouton 48 dp)
  au lieu de disparaître dans une notification.
- P2 — Fermeture à gauche (principe 7), état « réfléchit » doublé d'une
  estimation quand elle existe (« réfléchit… ~10 s »).
- P3 — Appui long sur une bulle = copier (menu contextuel standard, aussi
  exposé en action d'accessibilité).

## Espaces — liste

**Rôle.** Aiguillage : voir d'un coup d'œil quel espace a besoin de moi, puis y
entrer. Plusieurs fois par jour, brièvement.

**Constats.**
- Six cartes de ~120 dp de haut : la liste dépasse déjà l'écran ; avec
  Documents puis Santé, la moitié des espaces sera sous le pli.
- Les lignes d'état ne sont pas homogènes : trois cartes donnent un état vivant
  (Marchés, Mémoire, Médias), trois une description fixe quand leurs données
  manquent — y compris quand le module est **en erreur** (rien ne le dit).
- Étiquette « MODULES » : vocabulaire technique, et seule section de la page.
- Le chevron « › » est un caractère de texte lu par TalkBack.
- La ligne d'alerte (urgent, dépassement) est en brique gras : bonne
  hiérarchie, mais le vert de Marchés (« ▲ +5 pts ») a le même poids sans être
  une alerte.

**Refonte.**
- P1 — Chaque carte affiche **toujours** un état : la valeur clé, ou
  « Indisponible · Réessayer » (ton attention) quand le dernier appel a échoué,
  ou « Chargement… ». Plus jamais la description fixe une fois chargé.
- P2 — **Rangées compactes** (composant `SpaceRow`, 72 dp mini) : icône 40 dp,
  nom + une ligne d'état, à droite une pastille d'action (« 2 à finir »,
  « à ranger », « 1 à trier ») ; huit espaces tiennent sans défiler. La ligne
  d'état garde un seul niveau d'emphase (brique réservé à ce qui demande une
  action).
- P2 — Retirer « MODULES » et « D'autres espaces viendront ici » ; ligne
  « Réglages » en pied.
- P3 — Ordre fixe (mémoire musculaire) ; pas de tri dynamique.

## Inventaire

**Rôle.** En cuisine, une main mouillée ou chargée : « où est X ? », « que
dois-je finir ? », corriger une quantité, noter « consommé ». Et deux tâches
longues : ranger une commande, ajouter hors commande.

**Constats.**
- Plan en tuiles par zone, pastille rouge d'urgences, panneau du bas qui
  s'agrandit : modèle mental juste.
- **En erreur, l'écran ment** : « 0 produit » en en-tête, toutes les zones
  « vide », et un bandeau « Réponse illisible du module · lecture seule » en
  brique sur fond Pousse (vert) — trois tons pour un même message, et du
  vocabulaire technique.
- « Ajouter » désactivé : alpha 0,5 sur la fougère, texte blanc à moins de
  3:1, et un rectangle d'ombre visible sous le libellé.
- « trié par urgence » occupe la droite de l'en-tête du panneau sans action.
- Lignes produit : point de couleur + nom en brique pour l'urgent, date à
  droite, bouton de quantité ; « Actions » au tap : cohérent avec le geste
  (← consommé, → déplacer). Quantité : cible ~32 dp de haut visuellement
  (agrandie à 48 par Material, mais le contour visible trompe).
- Le « Ajouter » de l'Inventaire est un `Surface` maison ; Finances et Agenda
  utilisent `ExtendedFloatingActionButton` : deux formes et deux ombres.
- Pas de recherche : « où est le beurre ? » oblige à ouvrir les zones une à
  une.

**Refonte.**
- P1 — En erreur sans données connues : en-tête « Inventaire indisponible »
  (pas de compte), zones affichées sans compte (« — »), `StatusBanner` commun
  ton attention « Inventaire indisponible · Réessayer », détail « dernier état
  du 08/11 » quand il y en a un. Texte du panneau : « Pas encore de stock
  connu. » (plus de « Réponse illisible du module »).
- P1 — « Ajouter » via le composant commun `PrimaryAction` (désactivé =
  conteneur `surfaceVariant` + texte `onSurfaceVariant`, pas d'alpha).
- P2 — **Zone virtuelle « À finir »** en première tuile quand il y a des
  urgences : tous les produits urgents, toutes zones confondues (réponse
  directe à la question la plus fréquente). Libellé de zone sur chaque ligne.
- P2 — Champ « Chercher un produit » en tête du panneau agrandi (filtre local
  sur `lots`, toutes zones) ; résultat = ligne avec sa zone.
- P2 — Remplacer « trié par urgence » par le compte de la zone (« 4 produits ·
  2 à finir »).
- P3 — Quantité : contour à la hauteur réelle de la cible (48 dp) pour que
  l'œil voie ce que le doigt peut toucher.

### Ranger une commande

**Rôle.** Courses posées sur la table, un produit à la fois, une main tient le
produit. Vitesse maximale : la zone proposée doit se valider d'un geste ou d'un
gros bouton.

**Constats (code).** Un produit à la fois, zone proposée, tuiles des autres
zones, photo de l'étiquette pour la date, « Rupture », compteur « 2 / 4 »,
« ⟲ annuler », « Commande suivante » : la structure est la bonne. Fermeture par
« Fermer » texte à droite (principe 7 non tenu). Glissements sans actions
d'accessibilité.

**Refonte.**
- P1 — `customActions` sur la carte produit : « Ranger dans <zone proposée> »,
  « Rupture ».
- P2 — Bouton principal pleine largeur 56 dp « Ranger dans <zone> » sous la
  carte (même barre d'actions que Décider), tuiles des autres zones en dessous.
- P2 — Fermeture à gauche, compteur et annulation à droite (même en-tête que
  Décider).

### Ajouter hors commande

**Constats (code).** Scanner en continu, sinon nom (clavier ou dictée), puis
quantité si besoin, zone, date si frais ; « Plat cuisiné » en parcours
parallèle. Retours en arrière en `TextButton` « ← Scanner », « ← Autre »
placés en haut de chaque étape. Portions en `TextButton` « − » / « + ».

**Refonte.**
- P2 — Retour d'étape à un seul endroit (en-tête, chevron) ; « − / + » des
  portions avec le composant de pas de l'Inventaire (`StepButton` 48 dp).
- P3 — Indicateur d'étape (« 2 / 3 ») dans l'en-tête.

## Finances

**Rôle.** « Combien me reste-t-il ? » en deux secondes, souvent en magasin,
donc en public : montants masquables, capture d'écran bloquée tant qu'ils sont
visibles (déjà le cas). Plus rarement : enveloppes, patrimoine.

**Constats (code ; l'écran est protégé contre la capture quand les montants
sont visibles).**
- Reste à vivre en gros chiffre, jauge du cycle, enveloppes triées par
  pression, charges, coussin, alertes : bonne hiérarchie.
- « Puis-je me permettre ? » en bouton étendu : la question la plus utile en
  magasin est bien sous le pouce.
- Enveloppes : couleur ambre / brique pour 80 % / 100 % ; l'ambre en barre
  sur fond clair est sous 3:1, et rien d'autre que la couleur ne dit
  « dépassé ».
- `Metric` en `maxLines = 1` : un montant long à 1,3× est coupé sans repli.
- Bandeaux de fraîcheur et d'erreur au bon endroit (par onglet).
- « Chargement… » en texte seul.
- Les alertes (titre, détail, remède) sont des `StatusBanner` : une page avec
  trois bandeaux ressemble à une page en panne.

**Refonte.**
- P1 — Barres d'enveloppes : libellé textuel quand ≥ 80 % (« 82 % ») et
  « dépassé de 11 € » quand > 100 % ; couleur d'attention des barres assombrie
  (`attention` des barres = `#9A6414` en clair, 4,6:1 sur blanc).
- P1 — `Metric` : `autoSize` (ou passage à `headlineMedium` au-delà de la
  largeur) au lieu de la troncature.
- P2 — Alertes en liste (`AlertRow` : icône triangle, titre gras, remède en
  dessous) au lieu de bandeaux ; le bandeau reste réservé à l'état des données.
- P2 — Carte Espaces : « Reste à vivre X € · N j » (déjà prévu) + raccourci
  « Puis-je… ? » dans la carte (ouvre Finances avec le panneau ouvert).
- P3 — Squelette de chargement (trois blocs gris) au lieu de « Chargement… ».

## Agenda

**Rôle.** « Qu'est-ce que j'ai aujourd'hui / ce jour-là, quand suis-je libre ? »
le matin et avant de promettre un créneau ; ajout rapide d'un événement.

**Constats (code).**
- **Perso et travail ont la même couleur** : `primaryContainer` et
  `secondaryContainer` valent tous deux Pousse, en clair comme en sombre. La
  légende montre deux carrés identiques.
- Bandeau de 7 jours + glisser sur la vue (hors bords) + « Aujourd'hui » :
  bon. Le point « des événements » sous un jour est la seule marque (couleur +
  forme, correct) et il est annoncé.
- La frise a une hauteur fixe de 320 dp et défile **dans** une page qui défile :
  deux défilements imbriqués, difficile au pouce et en grande police.
- Tâches : « Faite » visible + glisser → : bon. « en retard depuis le… » en
  ambre (2,3:1).
- Bouton étendu « Ajouter un événement » : libellé long qui masque le bas de la
  frise.

**Refonte.**
- P1 — Couleur travail distincte : nouveau rôle `secondaryContainer`
  « Ardoise » `#DCE7F0` / texte `#23384A` en clair, `#2A3340` / `#C9D8EA` en
  sombre, et un liseré gauche différent (fougère pour perso, ardoise pour
  travail) ; libellé « travail » dans le bloc quand la hauteur le permet.
  (`theme/Color.kt`, `kit/Timeline.kt`.)
- P1 — « en retard » en couleur `attentionText` (voir Composants).
- P2 — Frise sans défilement propre : hauteur = plage utile du jour (premier
  événement − 1 h → dernier + 1 h, bornée 07 h–23 h), défilement de la page
  seul ; plages libres en hachures + texte « libre 2 h ».
- P2 — Bouton étendu « Ajouter » (court), qui se replie en bouton rond au
  défilement.
- P3 — Glisser ← sur une tâche : « Demain » (report), puisque le geste existe
  déjà vide.

## Marchés

**Rôle.** Coup d'œil hebdomadaire, au calme : ce qui a bougé, et trier les
propositions. Lecture, pas d'action urgente.

**Constats.**
- Ligne par question : pourcentage, variations 24 h / 7 j avec flèche et
  couleur, mini-courbe : la tendance est doublée d'une flèche, bien.
- Propositions en pile (réutilise Décider) : ← ignorer, ↑ plus tard, oui → ;
  voir Décider (carte à moitié vide, boutons texte).
- La puce « Propositions · 2 » vit dans l'en-tête, à côté du titre : action
  principale de l'écran en haut, hors de portée du pouce.
- Alertes récentes en `StatusBanner` : même remarque que Finances.
- Détail : courbe, volume, issues, projection, scénarios ; « Ne plus suivre »
  en bas : bon.

**Refonte.**
- P2 — « Trier 2 propositions » devient l'action principale (`PrimaryAction`
  en bas à droite), absente quand il n'y en a pas.
- P2 — Alertes en `AlertRow` datées.
- P3 — Mini-courbe : `contentDescription` « en hausse de 12 points sur 7
  jours » (aujourd'hui décorative).

## Mémoire

**Rôle.** Surtout trier les intentions (accepter / refuser / plus tard),
parfois noter dans le journal ; rarement vérifier ou corriger ce que Kairos
sait, relire une conversation.

**Constats.**
- Intentions en tête, avec le temps restant et trois boutons visibles
  (Accepter / Refuser / Plus tard) : c'est l'écran le plus fidèle au principe
  « un bouton par geste » ; mais Aujourd'hui fait l'inverse (actions repliées).
- Une longue page à quatre sections (intentions, journal + recherche, faits,
  conversations + recherche) : deux champs de recherche différents.
- « Ajouter » / « Noter » en puces dans les en-têtes de section : cohérent,
  mais l'action principale (noter) n'est pas sous le pouce.
- Entrées sensibles masquées par défaut, et toujours quand les montants le
  sont : bon.

**Refonte.**
- P2 — Une seule recherche en tête (journal + conversations), résultats
  groupés par type.
- P2 — « Noter » devient l'action principale (`PrimaryAction`), l'ajout
  d'intention reste dans l'en-tête de sa section.
- P2 — Faits et conversations repliés par défaut sous leur en-tête avec leur
  compte (« Ce que Kairos sait de toi · 12 »), dépliables : la page tient en
  un écran pour l'usage courant.
- P3 — Œil de masquage dans l'en-tête (principe navigation).

## Médias

**Rôle.** Le soir, sur le canapé : « qu'est-ce que je peux regarder
maintenant ? », puis « est-ce que quelque chose bloque ? », et parfois
demander un titre.

**Constats.**
- Ordre juste : à regarder, téléchargements, à venir, demandes.
- L'en-tête reste en place pendant que le contenu défile **dessous sans
  séparation** : la première ligne visible est coupée à moitié sous le titre.
- Téléchargement bloqué en brique avec sa raison en tête de section : bonne
  hiérarchie. Barres de progression avec indicateur de fin (point) sans
  libellé de pourcentage pour les éléments en pause / en attente.
- Résultats de recherche : « Déjà demandé » / « Déjà là » sont des boutons
  **désactivés** à très faible contraste (alpha 0,38) : un état présenté comme une
  action impossible.
- Recherche « Ajouter » en bas de page : l'action d'ajout est la plus éloignée
  du haut et sans relation avec le reste des espaces (ailleurs : bouton étendu).
- « Vu » visible + glisser → : bon.

**Refonte.**
- P1 — En-tête commun (`SpaceHeader`) : séparateur et légère élévation dès
  que le contenu défile ; plus de texte coupé sous le titre.
- P1 — Résultats : état en pastille (« déjà demandé », « déjà là ») au lieu
  d'un bouton désactivé ; seul « Demander » est un bouton.
- P2 — « Demander » devient l'action principale (`PrimaryAction`) qui ouvre une
  feuille de recherche ; la section « Demandes » ne garde que la liste.
- P3 — Libellé de progression aussi pour « En pause » (« En pause · 50 % »).

## Documents (en cours de construction)

Appliquer d'emblée : `SpaceScaffold`, `StatusBanner`, `PrimaryAction`
(« Ajouter » ou « Chercher » selon le rôle retenu), `SwipeRow` avec actions
d'accessibilité, file `Notifier`. Aucune décision d'écran ici.

## Réglages

**Rôle.** Rare : thème, lecture à voix haute, mise à jour, déconnexion.

**Constats.**
- Sections claires, déconnexion confirmée par dialogue : bon.
- Libellés de section en `labelMedium` fougère, sans sémantique de titre.
- Thème en puces « Clair / Sombre / Auto » : bon, mais « Auto » sans état
  courant (« actuellement : clair »).
- « Se déconnecter » en bouton contour au même poids que « Vérifier les mises
  à jour » ; action destructive non distinguée.
- Pas de réglage des montants masqués (voir Navigation).

**Refonte.**
- P2 — Titres de section en `heading()` (réutiliser `SectionHeader` du kit) ;
  fermeture à gauche (principe 7).
- P2 — Interrupteur « Masquer les montants » ; « Auto » affiche le thème
  appliqué.
- P3 — « Se déconnecter » en texte brique, isolé en bas.

## Mise à jour

**Rôle.** Quelques fois par mois : installer la version proposée sans quitter
l'application.

**Constats.** Bandeau discret en tête d'Aujourd'hui et bouton dans Réglages,
progression en pourcentage, reprise si la confirmation système a été quittée,
dialogue d'autorisation rédigé simplement : bon parcours. Le bandeau a son
propre style (fond fougère 10 %) et n'est pas annoncé.

**Refonte.**
- P2 — Bandeau via `StatusBanner` (ton neutre) avec action, `liveRegion` sur
  la progression (annonce à 0, 50, 100 % seulement).

## États hors ligne, erreurs, chargement

**Constats.** Trois composants : `OfflineBanner` (Aujourd'hui, fond ambre 16 %,
« Réessayer »), `OfflineBar` (Inventaire, fond Pousse, texte brique,
« Réessayer »), `StatusBanner` (kit, liseré, **sans action**). Messages
d'erreur parfois techniques (« Réponse illisible du module »). Les cartes
Espaces ignorent l'erreur.

**Refonte.**
- P1 — Un seul `StatusBanner(text, tone, detail, action)` (action facultative
  « Réessayer ») ; `OfflineBanner` et `OfflineBar` supprimés.
- P1 — Messages réécrits côté application : « <Espace> indisponible »,
  détail « dernier état : hier 18 h » ; le motif technique ne sort qu'en
  détail repliable.

## Snackbars et annulation

**Constats.** Sept `SnackbarHost` (racine, Aujourd'hui, Inventaire, Agenda,
Mémoire, Marchés, Médias), mêmes couleurs recopiées, positions différentes ;
fenêtres d'annulation de 4 s cohérentes. Deux messages simultanés peuvent se
superposer. L'annulation (4 s) est courte pour TalkBack (le temps d'atteindre le
bouton).

**Refonte.**
- P1 — `Notifier` commun (voir Composants) : une file, une position, une
  apparence ; durée d'annulation portée à 10 s quand un service
  d'accessibilité tactile est actif (`AccessibilityManager
  .getRecommendedTimeoutMillis`), 4 s sinon.

## Thème sombre

**Constats (code et palette).**
- Contrastes de texte très bons (Discret 7:1, fougère 7:1).
- Le trait de séparation `#2A332A` sur Surface `#1B221B` = 1,24:1 : les cartes
  (qui ne tiennent que par leur bordure) se fondent dans le fond `#121712`.
- `darken()` d'Aujourd'hui contourne le thème (règle « couleur déjà claire »).

**Refonte.**
- P2 — Trait sombre `#404A40` (1,96:1 sur fond, bordure lisible) ;
  `surfaceContainerHigh` sombre `#222A22` pour les cartes élevées.

## Grande police (1,3×)

**Constats (code).** Risques identifiés : en-tête d'Aujourd'hui (trois
boutons de largeur fixe), `Metric` coupé, rangée de trois boutons de Décider,
tuiles du plan (hauteur intrinsèque : prévu), frise à 320 dp fixes.

**Refonte.** Couverte par les points P1/P2 ci-dessus (en-tête recomposé,
`autoSize`, barre d'actions sur deux lignes, frise sans hauteur fixe). Règle :
toute `Row` de contrôles passe en `FlowRow` ou se replie en colonne au-delà de
`fontScale ≥ 1.3`.

## Mouvement et retour haptique

**Constats.** Superpositions qui naissent de leur ancre, fondu enchaîné,
ressort sans rebond, vibration au franchissement de seuil (`SegmentTick`,
`GestureThresholdActivate`), confirmation et refus : cohérent et sobre.

**Refonte.**
- P2 — Haptique unifiée dans `SwipeRow` et la pile : `GestureThresholdActivate`
  au seuil, `Confirm` à la validation, `Reject` au refus serveur (aujourd'hui
  `SegmentTick` dans `SwipeRow`, `GestureThresholdActivate` dans la pile).
- P3 — Animations infinies du personnage arrêtées quand « Supprimer les
  animations » est actif (échelle d'animation système à 0) : figer sur la
  pose de l'état.

---

## Composants communs à créer (`ui/kit/`)

| Composant | Rôle | Remplace |
|---|---|---|
| `SpaceScaffold(title, subtitle?, onBack, actions, banner?, primaryAction?, content)` | En-tête commun (chevron 48 dp, titre `headlineSmall` gras, sous-titre, actions à droite), séparateur au défilement, zone de bandeau, emplacement de l'action principale, marges du dock | Six `Header` privés |
| `SpaceHeader` | La partie en-tête seule, pour les écrans à onglets (Finances) | idem |
| `StatusBanner(text, tone, detail?, action?)` | Bandeau d'état unique, avec action facultative, `liveRegion` | `OfflineBanner`, `OfflineBar`, `UpdateBanner` |
| `PrimaryAction(text, onClick, enabled, expanded)` | Bouton étendu bas-droite, désactivé lisible, se replie au défilement | `AddButton` (Inventaire), FAB Finances / Agenda, puces d'en-tête Marchés / Mémoire / Médias |
| `SwipeRow` (déplacé depuis `today/`) | Glissement → / ←, libellés, haptique, **`customActions`** automatiques, couleurs AA | `SwipeRow` actuel (6 écrans) |
| `ActionBar(primary, secondary…)` | Barre sous le pouce des écrans « une carte à la fois » (Décider, Ranger) | `TextButton` épars |
| `Notifier` + `LocalNotifier` | File unique de messages et d'annulation, hôte unique au-dessus du dock, délai accessible | Sept `SnackbarHost` |
| `StatePill(text, tone)` | Pastille d'état (déjà demandé, travail, 82 %, fait) | `ChipView`, textes colorés |
| `AlertRow(title, detail?, remedy?, at?)` | Alerte de contenu (Finances, Marchés) | `StatusBanner` détourné |
| `SpaceRow` | Rangée compacte de la liste Espaces | Six cartes recopiées |
| `KairosIcons` (refresh, gear, chevrons, check, eye) | Icônes dessinées au même trait | `"↻"`, `"⚙"`, `"›"`, `"✓"` texte |

Rôles de couleur à ajouter au thème (`theme/Color.kt`) :

| Rôle | Clair | Sombre | Usage |
|---|---|---|---|
| `attentionText` | `#8A5A12` (5,4:1 sur Papier) | Ambre `#E8AE4E` | texte « en retard », « à finir vite » ton attention |
| `onTertiary` | Encre `#1F2620` (6,1:1 sur Ambre) | Papier | texte sur fond ambre (glissement « plus tard ») |
| `onSurfaceVariant` (Discret) | `#636B62` (5,1:1 sur Papier, 4,6:1 sur Pousse) | inchangé | texte discret ; l'actuel `#6B746A` fait 4,45:1 sur Papier |
| `secondaryContainer` / `onSecondaryContainer` | `#DCE7F0` / `#23384A` | `#2A3340` / `#C9D8EA` | calendrier travail |
| `outlineVariant` sombre | — | `#404A40` | bordures de cartes en sombre |

## Hors périmètre

- Pertinence métier des contenus (quels champs du profil, quelles cartes) :
  audit dédié prévu.
- Widget d'écran d'accueil, santé, notifications générales.
- Nouvelles fonctions serveur ; la seule attente côté tête est facultative
  (libellé humain du module pour l'en-tête de Décider).
- Tablette, paysage, écriture de droite à gauche au-delà de ce qui existe.
- Changement de police ou d'identité visuelle.

## Découpage de réalisation

Tâches indépendantes, dans l'ordre ; 1 doit précéder les autres (elles
consomment ses composants), 2 à 6 peuvent ensuite avancer en parallèle dans des
arbres de travail séparés.

1. **Fondations du kit et du thème** (P1)
   `ui/theme/Color.kt` (rôles ci-dessus), `ui/kit/Section.kt`
   (`StatusBanner` avec action), nouveaux `ui/kit/SwipeRow.kt`
   (déplacé + `customActions` + couleurs), `ui/kit/Notifier.kt`,
   `ui/kit/PrimaryAction.kt`, `ui/kit/SpaceScaffold.kt`, `ui/kit/StatePill.kt`,
   `ui/kit/Icons.kt` ; `MainActivity.kt` (hôte unique `Notifier`) ;
   `ui/Shell.kt` (`stateDescription` du bouton Kairos). Les anciens appels de
   `SwipeRow` changent seulement d'import.

2. **Aujourd'hui, semaine et mise à jour** (P1 puis P2)
   `ui/today/TodayScreen.kt` (bouton « Fait » visible, en-tête recomposé,
   carte « maintenant », suppression de l'hôte local, bandeaux communs),
   `ui/today/WeekScreen.kt` (jours ouvrant l'Agenda), `ui/today/Timeline.kt`,
   `MainActivity.kt` (rappel `onOpenAgenda(day)`).

3. **Décider et Ranger : barre d'actions sous le pouce** (P1)
   `ui/decide/DeckScreen.kt`, `ui/maison/UnpackScreen.kt`,
   `ui/kit/ActionBar.kt` ; actions d'accessibilité sur les cartes,
   hauteur de carte au contenu, fermeture à gauche.

4. **Inventaire et Ajouter** (P1 puis P2)
   `ui/maison/MaisonScreen.kt` (états d'erreur honnêtes, `PrimaryAction`,
   zone « À finir », recherche locale), `ui/maison/MaisonState.kt`,
   `ui/maison/AddScreen.kt` (retours d'étape, pas des portions).

5. **Espaces à données : Finances, Agenda, Marchés** (P1 puis P2)
   `ui/finances/FinancesScreen.kt` (enveloppes textuelles, `AlertRow`,
   `autoSize` de `Metric`), `ui/kit/Metric.kt`, `ui/kit/Bars.kt`,
   `ui/agenda/AgendaScreen.kt` + `ui/kit/Timeline.kt` (couleur travail, frise
   sans défilement imbriqué), `ui/marches/MarchesScreen.kt`
   (`PrimaryAction` « Trier »).

6. **Espaces de liste : Espaces, Mémoire, Médias, Réglages, Conversation** (P1 puis P2)
   `ui/spaces/SpacesScreen.kt` (`SpaceRow`, états d'erreur, ligne Réglages),
   `ui/memoire/MemoireScreen.kt` (recherche unique, sections repliées),
   `ui/medias/MediasScreen.kt` (en-tête au défilement, pastilles d'état,
   feuille « Demander »), `ui/settings/SettingsScreen.kt` (masquage global,
   titres), `ui/talk/ChatScreen.kt` (transcription vivante, bulle en échec,
   fermeture à gauche), `data/Settings.kt` si le masquage change de place.

Vérification commune à chaque tâche : TalkBack (ordre, actions de glissement),
police 1,3×, thème sombre, état hors ligne (serveur arrêté), et les tests
unitaires existants des états (`*State.kt`).

## Compléments (arbitrages de réalisation)

- `Notifier` reprend `ui/kit/UndoWindows` (déjà en place) : une file, chaque
  fenêtre d'annulation complète comptée une fois affichée, un message
  d'information écarté par une annulation ; délai porté à 10 s avec un service
  d'accessibilité tactile actif.
- L'espace Documents (désormais livré, `ui/documents/`) suit le même traitement
  que Médias dans la tâche 6 (`SpaceScaffold`, `StatusBanner`, pastilles).
- Les gestes d'effacement de la Mémoire (supprimer, oublier) passent par la
  même file d'annulation.
