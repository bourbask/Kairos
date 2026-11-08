# Application mobile Kairos

## Intention

Kairos est un assistant personnel : il doit vivre dans le quotidien de
l'utilisateur, agir seul quand il le peut, et réduire au minimum la friction
quand il a besoin de lui. Aujourd'hui, l'interaction est éclatée : la
conversation passe par un client de messagerie généraliste (ouvrir le
navigateur, le client, retrouver le salon), les données de santé exigent un
export manuel de milliers de fichiers, l'inventaire se saisit en conversation,
et les tâches de décision (classer des dépenses, valider un produit) ne sont
jamais faites parce qu'elles sont pénibles dans ce format.

L'application Android devient **la porte d'entrée unique vers Kairos** et
**son identité visuelle**. Elle n'est pas un assistant de plus : c'est la
télécommande et les capteurs de Kairos. L'intelligence, les données et les
règles restent sur le serveur ; l'application affiche, capte et transmet.

Le chat n'est pas le centre de l'application : le moteur de langage est lent
(environ 10 s par réponse au mieux) et limité. Le centre est ce que Kairos sait
déjà et ce qu'il attend de l'utilisateur, accessible en un geste.

## Objectifs

1. **Parler** à Kairos en un geste depuis n'importe quel écran, à la voix ou à
   l'écrit, dans la même conversation que celle du client de messagerie
   existant (historique commun).
2. **Aujourd'hui** : un écran d'accueil qui montre le fil du jour (repas,
   agenda, rappels utiles) et ce que Kairos attend de l'utilisateur.
3. **Décider** : traiter les demandes de Kairos (dépenses à classer, produits
   à qualifier…) en pile de cartes à glisser, en moins d'une minute.
4. **Maison** : voir et corriger l'inventaire alimentaire par zone sur un plan
   de la cuisine, et saisir les courses vite (scan en continu, photo de date).
5. **Santé en fond** : pas, sommeil, séances d'activité et tracés remontent
   seuls depuis le téléphone, sans export manuel.
6. **Identité** : un personnage reconnaissable dont l'état (repos, écoute,
   réfléchit, agit, attend) est toujours visible, dans l'application puis
   hors d'elle.
7. **Modularité** : ajouter ou retirer un module côté serveur ne demande
   aucune mise à jour de l'application.

## Non-objectifs

- iOS. Publication sur un magasin d'applications (installation directe de
  l'APK).
- Mot d'éveil (« Hé Kairos ») et écoute permanente.
- Reconnaissance ou synthèse vocale dans le cloud.
- Mode hors ligne complet : sans le serveur, l'application affiche le dernier
  état connu en lecture seule et refuse les actions.
- Remplacer le client de messagerie sur ordinateur.
- Un écran dédié par module (sauf Maison et, plus tard, Santé).
- Calcul ou logique métier dans l'application.

## Anti-objectifs

- Une application qui relance l'utilisateur en boucle : une demande non
  traitée **expire**, elle n'est pas répétée (règle déjà appliquée par
  Kairos aux intentions).
- Une action accessible **seulement** par un geste : chaque geste a un
  équivalent visible (bouton, libellé).
- Des données qui sortent du réseau privé : l'application ne parle qu'au
  serveur, par le réseau privé déjà en place, jamais à un service tiers
  (hors bibliothèques embarquées qui travaillent sur l'appareil).
- Un gadget : le personnage sert à dire ce que fait Kairos, pas à décorer.
- Un tableau de bord de graphiques à fouiller.

## Contraintes

- **Langage** : Kotlin natif (décision antérieure du projet), Jetpack Compose
  pour l'interface (choix du constructeur s'il a une raison forte).
- **Réseau** : le téléphone joint le serveur par le réseau privé déjà
  utilisé par Kairos. Aucun port public nouveau.
- **Serveur** : ressources comptées. La tête tourne avec une
  limite de 64 Mo ; tout ajout côté serveur reste léger.
- **Moteur de langage** : réponses rapides quand l'accélérateur est
  disponible, bien plus lentes sinon. L'interface doit le rendre supportable (état
  « réfléchit », réponse qui arrive en notification si on quitte l'écran).
- **Dépôt public** : règles OSINT du projet. Aucun nom d'hôte, d'adresse, de
  compte ou de fournisseur dans le code ou la configuration versionnée de
  l'application ; adresse du serveur et jetons saisis au premier lancement
  (ou par QR code), stockés chiffrés sur l'appareil (Android Keystore).
- **Données de santé** : sensibles, jamais envoyées ailleurs qu'au serveur
  Kairos.

## Ce qui a été décidé

### Identité visuelle

- **Personnage** : créature en pixel art (grille 16×16), corps carré, deux
  yeux, une **mèche en avant** sur la tête et de **petites ailes aux pieds**,
  les deux attributs du Kairos grec. Le mythe sert au lore (pourquoi ce nom),
  pas à la palette.
- **États animés** : repos (respiration lente, clignement), écoute (yeux
  grands ouverts, ondes), réfléchit (yeux plissés, petites bulles), agit
  (sautille), attend une réponse (regarde l'utilisateur, mèche qui bouge).
  L'état se lit par l'animation et l'expression, pas seulement par la couleur.
- **Palette**, thème **clair par défaut** :

  | Rôle | Nom | Clair | Sombre |
  |---|---|---|---|
  | Couleur principale | Fougère | `#4F7942` | `#7FAF6E` |
  | Texte sur teinte | Sous-bois | `#2F4A28` | `#B9D8AA` |
  | Surface teintée | Pousse | `#E3EDDA` | `#243324` |
  | Fond | Papier | `#F7F5EF` | `#121712` |
  | Surface | Blanc | `#FFFFFF` | `#1B221B` |
  | Texte | Encre | `#1F2620` | `#E6EBE3` |
  | Attention | Ambre | `#D9952B` | `#E8AE4E` |
  | Urgent | Brique | `#B8463A` | `#E07A6C` |

- **Thème** : réglage clair / sombre / automatique. Automatique = selon la
  luminosité ambiante (capteur de lumière), avec hystérésis et délai pour
  éviter le clignotement ; repli sur le thème du système si le capteur
  manque. Désactivable.
- Maquettes de référence : `docs/maquettes/app-mobile-ecrans.html` (fil du
  jour, pile de décisions, plan de la maison, parler, thème sombre). Elles
  fixent la structure et l'esprit, pas les pixels.

### Navigation et gestes

- Deux onglets en bas : **Aujourd'hui** et **Espaces** (liste des espaces de
  modules : Maison, puis Santé…). **Kairos au centre**, sous le pouce, sur
  tous les écrans.
- Grammaire de gestes, identique partout :

  | Geste | Effet |
  |---|---|
  | Tap | ouvrir, déplier |
  | Glisser → sur un élément | positif : fait, oui, valider |
  | Glisser ← sur un élément | négatif : plus tard, non, consommé (selon contexte, libellé visible pendant le glissement) |
  | Glisser ↑ / ↓ sur pile ou panneau | plus tard / fermer ; agrandir / réduire un panneau |
  | Appui long sur Kairos | parler (talkie-walkie) |
  | Pincer / écarter | zoom sémantique : jour ↔ semaine sur Aujourd'hui ; zoom sur le plan de Maison |

- **Écartés** : double et triple tap (invisibles ; le triple tap est pris par
  la loupe d'accessibilité), glisser depuis le bord (geste « retour »
  d'Android), secouer (déclenchements accidentels).
- Retour haptique au franchissement de chaque seuil de glissement.
  **Annuler** la dernière action toujours disponible quelques secondes.

### Parler

- Kairos (bouton central) : **appui long** = enregistrer ; relâcher = envoyer ;
  glisser ↑ pendant l'appui = verrouiller en mains libres ; glisser ← =
  annuler. **Tap** = ouvrir la conversation écrite.
- Reconnaissance vocale **sur l'appareil**, en français, hors ligne quand le
  téléphone le permet ; transcription affichée en direct et modifiable avant
  envoi en mode verrouillé.
- Lecture à voix haute des réponses par la synthèse vocale du système :
  activable dans les réglages, et automatique quand la question a été posée à
  la voix en mode mains libres.
- La conversation passe **par la tête**, seul serveur que l'application
  connaît : `POST` d'un message vers l'API de l'application, qui l'injecte
  dans la même file que les messages de la passerelle. La tête recopie dans
  le salon de messagerie le message reçu de l'application (marqué comme
  venant du téléphone) puis sa réponse, comme d'habitude : l'historique reste
  commun avec l'ordinateur. L'application lit la conversation depuis la tête
  (derniers échanges, puis nouveaux messages depuis un curseur).
- Notification de réponse : tant qu'une réponse est attendue, l'application
  interroge la tête à intervalle court, y compris quelques minutes après
  avoir quitté l'écran (tâche d'arrière-plan bornée), puis affiche une
  notification locale. Aucune connexion permanente, aucun service de push.
  Les notifications générales arrivent à l'étape 5 ; d'ici là, le client de
  messagerie existant continue de notifier.

### Aujourd'hui

- En haut : salutation et contexte du lendemain (bureau / maison, météo si
  disponible).
- **Pile de décisions** en aperçu (« 4 décisions · ≈ 1 min »), qui ouvre
  Décider.
- **Fil du jour** chronologique, l'élément en cours en tête : repas, rappels
  d'action (sortir une boîte du congélateur), agenda. Glisser → = fait ;
  glisser ← = plus tard ; tap = détail (par exemple la recette).
- Pincer = vue semaine (repas planifiés, agenda), écarter = retour.

### Décider

- Une carte = une question. Elle porte : module, titre, contexte (montant,
  libellé, date, image éventuelle), **la proposition de Kairos**, et au plus
  quelques alternatives.
- Glisser → = accepter la proposition ; ← = refuser, les alternatives
  s'affichent sous le pouce ; ↑ = plus tard ; ↓ = fermer la pile. Réponse
  vocale possible (« non, c'est des loisirs »).
- Une carte a une **date d'expiration** fixée par le module ; passée cette
  date, elle disparaît sans relance.
- « Plus tard » remet la carte en fin de pile **sans repousser son
  expiration** et sans notification. Le module stocke l'état de ses cartes
  (en attente, acceptée, refusée, expirée) ; la tête ne fait qu'agréger.
- Premier module branché : **finances** (dépenses non classées). Puis
  alimentation (produits à qualifier).

### Maison

- Plan de la cuisine en zones : frigo, congélateur, placard, étagère, table.
  Pastille rouge = nombre de produits urgents.
- Tap sur une zone = panneau du bas avec son stock, trié par urgence, tel que
  Kairos le connaît. Sur un produit : glisser ← = consommé, → = déplacer
  vers une autre zone, tap sur la quantité = la modifier.
- **Déballer les courses** (décidé le 2026-11-08) : une commande en ligne
  importée et pas encore réceptionnée apparaît comme une liste « à ranger ».
  Un produit à la fois : sa zone habituelle est proposée (dernière zone
  utilisée pour ce produit, sinon celle déduite du rayon) ; on valide, ou on
  le pose sur une autre zone. Produit frais : photo de l'étiquette pour lire
  la date (reconnaissance de texte sur l'appareil, date confirmée d'un geste) ;
  sinon pas de date. « Rupture » retire la ligne. La commande est réceptionnée
  quand toutes ses lignes sont traitées.
- **Hors commande** (marché, boulangerie…) : scan du code-barres (lecture sur
  l'appareil, produit retrouvé dans la base ouverte), zone, date si frais ;
  sans code-barres : nom dicté.
- Toutes les écritures passent par les outils existants du module
  alimentation (ajout, déplacement, consommation).

### Santé en fond

- L'application lit **Health Connect** (interface de santé standard d'Android,
  alimentée par l'application santé du téléphone) : pas, sommeil, fréquence
  cardiaque, poids, séances d'activité et leurs tracés quand ils y figurent.
- Synchronisation périodique en arrière-plan (au moins quotidienne) vers le
  serveur, par différence depuis la dernière synchronisation.
- Si les tracés de sortie n'arrivent pas par Health Connect : l'application
  enregistre elle-même le GPS pendant une séance lancée d'un geste.
- Côté serveur : un **nouveau module santé**, qui stocke les mesures et les
  séances et expose des outils de lecture (comme les autres modules).
  L'export manuel existant sert **une fois**, à importer l'historique
  antérieur à ce que Health Connect fournit.

### Serveur : contrat avec l'application

- Les modules restent la source : chacun expose, à côté de `brief_section`,
  des outils **`cards`** (éléments du fil du jour) et **`decisions`**
  (cartes en attente) et une action **`decide(id, choix)`**. Un module qui ne
  les expose pas n'apparaît simplement pas.
- La tête agrège ces outils et publie une **API HTTP pour l'application**,
  sur le réseau privé, protégée par un jeton propre à l'appareil.
- `cards`, `decisions` et `decide` sont **masqués du moteur de langage** :
  ils ne servent qu'à l'API de l'application (le moteur garde les outils
  existants pour répondre en conversation).
- **Appairage** : une commande de la tête génère un jeton pour un appareil
  nommé et affiche un QR code (adresse + jeton) ; la tête ne stocke que
  l'empreinte du jeton. Plusieurs appareils possibles ; une commande révoque
  un appareil. Le jeton de la passerelle reste distinct.
- Les écrans génériques (fil, cartes) sont **décrits par le serveur** avec un
  petit nombre de types (information, décision, liste, chiffre) ;
  l'application les dessine sans connaître les modules. Seuls Maison et Santé
  ont des écrans dédiés.

### Le personnage hors de l'application

- Widget d'écran d'accueil : le personnage dans son état courant, appui long
  pour parler, nombre de décisions en attente.
- Notification ou bulle quand Kairos agit ou attend une réponse ; une réponse
  longue arrive en notification si l'utilisateur a quitté l'écran.

## Livraison par étapes

Chaque étape est utilisable seule.

1. **Socle, Parler, Aujourd'hui** : projet Android, thème, personnage animé,
   navigation, premier lancement (adresse et jetons), conversation écrite et
   vocale dans le salon existant, API de la tête et fil du jour (repas et
   agenda d'abord).
2. **Décider** : outils `decisions` / `decide`, branchement finances, pile de
   cartes.
3. **Maison** : plan, stock par zone, déballage des courses.
4. **Santé** : module serveur, synchronisation Health Connect, import unique
   de l'historique, GPS de secours.
5. **Hors de l'application** : widget, notifications, bulle.

## Choix du concepteur (à valider en relisant)

- Conversation via la tête (et recopie dans le salon) plutôt qu'un client de
  messagerie dans l'application : un seul serveur, un seul jeton, historique
  commun quand même.
- Emplacements Maison : ajout de `etagere` aux emplacements reconnus du module
  alimentation, avec les durées de conservation du placard, sans migration ;
  l'emplacement existant `corbeille` est affiché « Table ».
- Une carte refusée sans alternative choisie compte comme « plus tard ».
- Code de l'application dans ce dépôt, sous `mobile/`.

## Laissé au constructeur

- Bibliothèques Android (lecture de codes-barres et de texte sur l'appareil,
  client du protocole de messagerie ou appels HTTP directs, stockage local).
- Format exact de l'API de la tête et des outils `cards` / `decisions`, dans
  le style des outils existants.
- Seuils de luminosité et de glissement, durées d'animation.
- Dessin définitif du personnage et de ses animations, dans le cadre ci-dessus.
- Fréquence de synchronisation santé, dans la limite « au moins quotidienne ».
