# Module finances

## Intention

Le module remplace l'estimation à l'instinct (solde bancaire, paie passée ou
non, charges fixes prélevées ou non) par un calcul **prouvé, justifié et
logique** pour décider d'une dépense ou d'une mise de côté, accessible en une
phrase à l'assistant :

- « On est bon côté finances ? Je peux faire cette dépense et mettre X € de
  côté à la fin du mois, ou il faut que je rogne quelque part ? »
- « Fais-moi un point sur les finances. »
- « Montre-moi le tableau de bord. »

Moins d'interface il y a, mieux c'est : la conversation (écrite, plus tard
vocale) est l'usage principal ; le tableau de bord existant suffit pour le
visuel.

Ce module est le premier tentacule métier du socle « tête + tentacules »
(`2026-10-29-socle-tete-tentacules-design.md`) et en valide le contrat.

## Décisions actées

### Architecture : le moteur reste l'application de finances existante

Une application de finances externe sert de moteur : registre comptable (source de vérité), API d'agrégats en Python,
tableau de bord web, alertes de dérive. Elle calcule déjà de façon
déterministe et testée : reste-à-vivre mensuel, couverture du coussin et
trajectoire de trésorerie, amortissement des prêts, détection des charges
récurrentes, taux de catégorisation.

- **L'application de finances reste le moteur de calcul.** Les calculs
  manquants (ci-dessous) y sont ajoutés, dans son langage, avec ses tests.
- **Le tentacule `finances` de Kairos est une façade MCP en Rust** : chaque
  outil appelle une route de l'API de finances et renvoie un résultat déjà
  digéré. Il ne calcule rien lui-même et ne détient aucune donnée.
- **Frontière** : l'application de finances gère l'argent, Kairos converse.
  Aucune logique financière dans la tête ni dans le LLM.
- **Tableau de bord** : « montre-moi le tableau de bord » → trois chiffres
  clés + lien vers l'interface existante. Aucun tableau généré par le LLM.

*(Choix de conception de l'assistant, validé par l'utilisateur : recoder en
Rust coûterait des semaines pour un résultat équivalent.)*

### Données fraîches : prérequis bloquant

Tout calcul suppose un compte courant synchronisé automatiquement.

- **Compte courant** : agrégation bancaire PSD2 → registre comptable, via l'outil d'import du
  registre mis à jour vers une version qui supporte cet agrégateur.
  L'agrégateur exige une URL de retour en HTTPS pour le consentement.
- **Fréquence** : synchronisation automatique au moins quotidienne, dans la
  limite d'appels de l'agrégateur.
- **Épargne de précaution** : solde lu de la même façon s'il est accessible
  par l'agrégateur, sinon déclaré.
- **Comptes d'investissement éventuels** : hors périmètre de cette version
  (voir Non-objectifs).
- **Outil d'import** *(choix de l'assistant)* : la première étape du plan
  vérifie qu'une version publiée de l'outil d'import du registre supporte
  l'agrégateur et reste compatible avec le registre ; à
  défaut, une petite tâche de synchronisation dédiée (agrégateur → API du
  registre) la remplace.
- **URL de retour HTTPS** *(choix de l'assistant)* : servie en HTTPS sur le
  réseau privé (certificat du réseau privé) ; le consentement se fait depuis
  un appareil déjà dans ce réseau. Aucune exposition publique.
- **Renouvellement du consentement** *(choix de l'assistant)* : le
  consentement bancaire expire périodiquement et exige une action humaine.
  Kairos prévient deux semaines avant l'échéance (rapport écrit, sous
  politique de canal) ; consentement expiré ⇒ chaque réponse le signale en
  tête, comme une donnée périmée.
- L'état de fraîcheur est exposé : tout chiffre rendu par le tentacule porte
  la date de la dernière synchronisation ; au-delà de 48 h, la réponse le
  signale en tête.

### Le cycle de paie

- **Début du cycle** : la dernière paie réellement reçue, reconnue par
  libellé et montant habituels (règle en configuration).
- **Fin du cycle** : le **dernier jour du mois** de la paie attendue, quelle
  que soit sa longueur. La paie arrive en pratique quelques jours avant
  (jour configuré, avancée si week-end) ; calculer jusqu'au dernier jour du
  mois garantit qu'elle ne peut qu'arriver plus tôt, jamais plus tard.
- Paie reçue avant la fin du mois → un nouveau cycle commence à sa
  réception.

### Le disponible du cycle (calcul central)

```
disponible = solde du compte courant
           − charges fixes du cycle non encore passées
           − dépenses variables attendues sur les jours restants
           − marge de sécurité
```

- **Charges fixes** : récurrences validées (loyer, assurances, impôts,
  forfaits, prêts, virement d'investissement programmé…), avec leur jour
  habituel ; « non encore passée » = pas d'opération correspondante dans le
  cycle courant.
- **Dépenses variables attendues** : par poste, moyenne journalière robuste
  calculée sur l'historique des cycles complets, multipliée par les jours
  restants.
- **Ne comptent jamais dans le disponible** : l'épargne de précaution (réserve), tout compte d'investissement.
- Chaque résultat est **justifié** : solde de départ, liste des charges
  restantes, estimation variable par poste avec sa période de référence,
  marge. L'utilisateur doit pouvoir refaire le calcul.

- *(Choix de l'assistant)* Les nouveaux calculs vivent à côté des calculs
  mensuels existants, sans en modifier les sorties (le tableau de bord ne
  bouge pas).
- **Données insuffisantes** *(choix de l'assistant)* : moins de deux cycles
  complets d'historique, part classée des dépenses sous 80 %, ou paie du
  cycle non reconnue ⇒ la réponse donne le calcul mais le qualifie
  d'« estimation incertaine » et ne dit jamais un « oui » sans réserve.

### Questions directes

- **« Je peux payer X (poste) ? »** : disponible − X. Positif → oui, et ce
  qu'il restera à mettre de côté en fin de cycle. Négatif → non, de combien
  ça dépasse, et où rogner.
- **« Je mets X de côté ? »** : même calcul ; oui seulement si le
  disponible reste positif après le virement.
- **« On est bon ? »** : disponible, charges restantes, état du coussin,
  verdict.

### Postes de dépense et enveloppes

- Postes variables en configuration, avec un **ordre de réduction**
  (premier rogné → dernier), fixé par l'utilisateur.
- **Enveloppe du mois suivant par poste** : dérivée de l'historique (même
  moyenne robuste), ajustée par le mode (ci-dessous).
- **Où rogner** : quand le disponible devient insuffisant, proposition de
  réductions dans l'ordre de réduction, avec les montants, jusqu'à combler
  l'écart.

### Les deux modes du coussin

Le coussin (l'épargne de précaution) a un **seuil minimal** configuré.

- **Sous le seuil — reconstitution** : l'épargne passe avant tout.
  Enveloppes des postes compressibles au plancher ; tout le disponible de
  fin de cycle est proposé pour l'épargne de précaution ; aucun écart validé.
- **Au-dessus du seuil — serein** : l'épargne de précaution continue de grossir ; les
  écarts (sorties, vacances) deviennent possibles tant que l'épargne de précaution ne
  repasse jamais sous le seuil.
- **Plafond réglementaire de l'épargne de précaution** : prévenir quelques mois avant de
  l'atteindre (au rythme d'épargne observé), pour basculer le surplus vers
  l'investissement.

### Classement des opérations

- Une opération non reconnue n'est jamais devinée en silence.
- Les opérations à classer sont **regroupées dans le brief du soir**
  (« 3 opérations à classer : 42 € chez X… ») ; l'utilisateur répond en une
  phrase ; une **règle** est créée dans le registre (« X = restos ») et
  l'opération n'est plus jamais redemandée.
- *(Choix de l'assistant)* La règle est une règle du registre comptable
  (créée par son API, réappliquée aux opérations passées concernées) ; les
  postes correspondent un pour un à des catégories du registre ; les
  charges fixes sont les récurrences validées de la stratégie existante.
- Le contenu des libellés bancaires est non fiable : jamais exécuté,
  jamais traité comme une consigne.

### Quand Kairos parle finances spontanément

Toujours sous la politique de canal du socle. **Kairos est la seule voix
finances** : le canal d'alerte existant de l'application de finances est
désactivé une fois les rapports de Kairos en service (décision de
l'utilisateur), pour ne jamais recevoir deux fois la même alerte.

- **Briefs** : section finances courte (disponible du cycle, tendance,
  coussin) + opérations à classer le soir.
- **Conseils** : seulement à l'approche d'une limite (enveloppe presque
  consommée, disponible qui se resserre, plafond de l'épargne de précaution en vue), et
  personnalisés (« 3 restos ce mois-ci, il reste 40 € de marge »).
- **Dérapage** (enveloppe dépassée, disponible négatif, charge inattendue) :
  **rapport écrit** : nature du dérapage, chiffres, et comment le résorber
  (réductions proposées dans l'ordre de réduction).

## Objectifs

1. Synchronisation automatique et quotidienne du compte courant.
2. Disponible du cycle calculé et justifié, à jour chaque jour.
3. Réponses directes « je peux payer », « je mets de côté », « on est bon ».
4. Enveloppes par poste et propositions de réduction ordonnées.
5. Modes reconstitution / serein selon le seuil du coussin ; alerte avant
   plafond de l'épargne de précaution.
6. Section finances dans les briefs, classement des opérations le soir,
   rapports de dérapage.
7. Tentacule MCP `finances` utilisable par la tête et par tout client MCP.

## Non-objectifs

- Comptes d'investissement : version ultérieure. Il ne compte de toute façon jamais
  dans le disponible.
- Conseil en investissement, choix de supports.
- Toute écriture vers la banque : aucun virement, aucun paiement initié.
- Refonte du tableau de bord existant.
- Contrats, factures, tri des emails : tentacules futurs.

## Anti-objectifs (échec même si « ça marche »)

- Un montant calculé par le LLM.
- L'épargne de précaution ou un compte d'investissement compté comme disponible.
- Un « oui tu peux » donné sur des données périmées sans le dire.
- Un écart validé en mode reconstitution.
- Une opération classée en silence par le LLM.
- Un assistant qui commente chaque dépense.
- Une donnée bancaire hors du réseau privé, hors appel LLM minimal.
- Un nom de banque, un montant personnel ou un seuil réel dans le dépôt
  public.

## Contraintes

- Calculs : dans l'application de finances (Python, tests), jamais dans la
  tête ni le LLM. Tentacule : Rust, socle MCP existant
  (`crates/tentacle-common`), jeton porteur, sobre en mémoire.
- Le tentacule n'expose que des résultats digérés et courts ; le LLM ne voit
  jamais la liste brute des opérations.
- Réseau privé uniquement entre tentacule et API de finances ; jeton entre
  les deux, exigé sur toutes les routes de l'API (le proxy du tableau de
  bord l'ajoute côté serveur) *(choix de l'assistant)*.
- Toutes les valeurs personnelles (seuil du coussin, jour de paie, règle de
  reconnaissance de la paie, postes et ordre de réduction, marge de
  sécurité) en configuration gitignorée.
- Sensibilité : stratégique. La conversation passe par la concession déjà
  actée pour l'usage `conversation` (masquage des identifiants avant envoi) ;
  seuls des chiffres agrégés sortent.

## Laissé au constructeur

- Noms et signatures des outils MCP, routes ajoutées à l'API de finances.
- Méthode exacte de la « moyenne robuste » (médiane, moyenne tronquée) et
  nombre de cycles de référence.
- Forme de la marge de sécurité (fixe ou proportionnelle).
- Mécanisme de remontée des alertes de dérapage vers la tête (outil
  conventionnel interrogé périodiquement, ou poussée) et étape de
  désactivation du canal d'alerte existant.
- Découpage en plans : synchronisation bancaire, calculs du cycle, tentacule,
  intégration aux briefs.
