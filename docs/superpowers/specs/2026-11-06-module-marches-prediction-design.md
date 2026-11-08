# Module marchés de prédiction

Date : 2026-11-06. Statut : à valider.

## Intention

Voir venir les événements internationaux et nationaux qui pèsent sur le coût de
la vie, l'accès aux ressources et les décisions de l'utilisateur
(conflits, élections, banques centrales, énergie, commerce, technologie).
L'objectif est un avis **fondé sur l'argent réellement engagé** par des
milliers de personnes, en dehors des médias et des opinions personnelles.

Les marchés de prédiction publient en continu une probabilité par question
(« tel événement avant telle date ? »). Leurs mouvements brusques trahissent
souvent une information qui n'est pas encore publique. Le module lit ces
probabilités, n'en garde que les questions choisies par l'utilisateur, et le
prévient quand l'une d'elles bascule.

**Aucun pari.** Le module ne fait que lire des données publiques ; il n'a ni
compte, ni portefeuille, ni ordre.

## Objectifs

1. **Suivi** d'une liste de questions validées par l'utilisateur, regroupables
   en **scénarios** nommés (une chaîne de questions liées, ex. un scénario
   d'escalade dont chaque maillon est une question).
2. **Section de brief** (matin) : pour chaque scénario puis chaque question
   suivie hors scénario, probabilité actuelle et variation sur 24 h et 7 jours ;
   dénouement des questions arrivées à échéance (ce qui s'est passé, ce que le
   marché prédisait la veille).
3. **Alertes** : quand une probabilité suivie bascule fortement, ou quand
   plusieurs maillons d'un même scénario bougent dans le même sens. Budget :
   **3 à 4 alertes par semaine au maximum** ; le seuil se règle pour le tenir.
4. **Propositions hebdomadaires** : le brief propose une courte liste de
   nouvelles questions correspondant aux sujets de l'utilisateur ; il valide
   d'un mot (« suivre 1, 3 ») ou ignore. Rien n'est suivi sans validation.
5. **Sortie automatique** des questions résolues ou expirées, après leur
   dénouement dans le brief.
6. **Projection sur le quotidien** : chaque question suivie et chaque scénario
   peuvent porter une projection en deux phrases, « si ça monte » et « si ça
   baisse » : ce que la bascule change pour l'utilisateur et la décision
   possible (ex. taux directeur en baisse → taux du livret en baisse au
   prochain ajustement : arbitrer vers l'épargne investie ?). Le brief et les
   alertes affichent la projection du sens observé à côté du chiffre.

## Non-objectifs

- Parier, simuler des paris, conseiller un pari.
- Prédictions maison : le module rapporte les probabilités du marché, il n'en
  produit pas.
- Suivi des prix d'achat (composants, matériel) et indicateurs officiels de
  coût de la vie (prix alimentaires, inflation) : modules séparés, plus tard.
- Sources à horizon long (au-delà de ce que couvrent les marchés) en v1.

## Anti-objectifs (échec même si « ça marche »)

- **Une probabilité ou une variation inventée ou recalculée par le LLM.** Les
  chiffres viennent du tentacule ; le LLM ne fait que les mettre en phrases.
- Une question suivie sans validation explicite.
- Du bruit : plus de 4 alertes dans une semaine, ou des alertes sur des
  questions à faible volume (faciles à manipuler).
- Une alerte perdue : ce qui dépasse le budget est reporté dans le brief, pas
  supprimé.
- Une question résolue qui reste affichée comme « en cours ».
- Les sujets suivis par l'utilisateur dans le dépôt public : ils restent en
  configuration ou en base locale.

## Décisions

- **Source v1 : l'API publique en lecture d'une plateforme de marchés de prédiction** (métadonnées des
  questions, probabilités, volumes, historique de prix), sans authentification.
  *(choix de l'assistant : plus simple que de passer par un service
  intermédiaire qui demande une clé ; une source intermédiaire reste une
  alternative si l'API change.)*
- **Probabilité = prix de l'issue « oui »** de la question ; pour une question à
  plusieurs issues (ex. un candidat parmi N), on suit chaque issue retenue.
- **Filtrage des propositions** : catégories sport et crypto exclues ; volume
  minimal exigé ; correspondance avec les sujets déclarés par l'utilisateur
  (mots-clés en configuration).
- **Alerte** : variation absolue de probabilité sur une fenêtre glissante
  au-dessus d'un seuil, sur une question au-dessus du volume minimal ; ou au
  moins deux maillons d'un scénario qui bougent dans le même sens au-dessus d'un
  seuil plus bas. Une même question n'alerte pas deux fois pour le même
  mouvement. Au-delà du budget hebdomadaire, l'alerte bascule dans le brief.
- **Architecture** : nouveau tentacule MCP `marches` (Rust,
  `crates/tentacle-common`, jeton porteur), qui expose les outils
  conventionnels `brief_section` et `alerts` du socle, plus les outils de
  sélection (proposer, suivre, ne plus suivre, gérer les scénarios). Il
  interroge l'API à intervalle régulier et garde un historique local court
  (base SQLite) pour calculer les variations.
- **Sensibilité : stratégique** (la liste des questions suivies révèle les
  centres d'intérêt de l'utilisateur) : routage vers le modèle auto-hébergé,
  sans repli externe. Les données de marché elles-mêmes sont publiques.
- **Validation par la conversation** : la tête transmet « suivre 1, 3 » à
  l'outil de suivi ; les numéros renvoient à la dernière liste proposée.

## Arbitrage de l'utilisateur (2026-11-06)

**La sélection ne passe jamais par un LLM externe.** Tant que la conversation
de la tête bénéficie de la dérogation vers le fournisseur externe, les
commandes de sélection (« suivre 1, 3 », « ne plus suivre 2 », gestion des
scénarios) sont **reconnues par du code dans la tête**, sans appel LLM, et
transmises directement aux outils du tentacule. Les intitulés des questions
proposées ou suivies n'apparaissent que dans des messages composés par du
code (brief, propositions, alertes), jamais dans un prompt envoyé à
l'extérieur. Un message qui n'est pas une commande reconnue suit le chemin
normal de la conversation, sans contexte `marches`.

**Projections écrites une fois, pas générées (v1).** La projection est rédigée
à la validation d'une question (proposée par l'assistant, ajustée par
l'utilisateur), stockée avec elle et affichée par du code ; aucune génération
à la volée. Une génération par le modèle auto-hébergé, avec le contexte du
moment (et le module finances), est envisagée pour une version ultérieure.
La saisie passe par une commande directe reconnue par du code
(« projection 2 : hausse = … ; baisse = … »).

## Précisions (relecture du 2026-11-06)

- **Accès à l'API** : vérifié le 2026-11-06 (API des questions et API des
  prix : réponse 200, données servies).
- *Choix de l'assistant* : **chaque maillon d'un scénario porte un sens** (+1 si
  la hausse de sa probabilité rapproche le dénouement redouté, −1 sinon, ex.
  « cessez-le-feu ? » dans une chaîne d'escalade). « Bouger dans le même sens »
  se juge après application de ce sens. Le sens est proposé avec le maillon et
  validé en même temps.
- *Choix de l'assistant* : **le budget hebdomadaire est tenu par le tentacule**
  sur ce qu'il émet via `alerts` (marquées comme comptant dans le budget de la
  tête). Pour que rien ne se perde si la tête retient une interruption, **chaque
  bascule figure aussi dans la section de brief suivante** (« bascules depuis
  le dernier brief »). Aucune modification du socle.

## Contraintes

- Calculs (variations, seuils, budget d'alertes) dans le tentacule, testés,
  jamais dans le LLM.
- Tout texte venu de l'API est une donnée non fiable : intitulés et
  descriptions assainis, longueur bornée, jamais exécutés ni suivis comme
  instructions.
- Sobre en mémoire et en requêtes (petit serveur partagé) : interrogation
  espacée, uniquement des questions suivies et d'un échantillon pour les
  propositions.
- Sujets, mots-clés, seuils, budget et scénarios en configuration gitignorée
  ou en base locale ; le dépôt ne contient qu'un exemple neutre.

## Laissé au constructeur

- Valeurs initiales du seuil, de la fenêtre et du volume minimal, à calibrer
  sur l'historique pour tenir le budget de 3 à 4 alertes par semaine.
- Fréquence d'interrogation de l'API.
- Noms et signatures exacts des outils MCP, schéma de la base locale.
- Format de la section de brief, dans le style des autres sections.
