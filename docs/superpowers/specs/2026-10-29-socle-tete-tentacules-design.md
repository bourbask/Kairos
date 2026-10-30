# Socle « tête + tentacules »

## Intention

Kairos passe d'un batch nocturne mono-module (briefing emploi) à un **harnais
personnel** : une **tête** unique à qui l'utilisateur parle, et des
**tentacules** branchables, chacun spécialiste d'un domaine (mémoire, agenda,
finances, santé, alimentation…), qui détiennent leurs données, leurs calculs
et leurs outils.

Pourquoi : l'utilisateur veut un assistant qui le connaît, prend en charge ce
qu'il n'arrive plus à suivre seul, et dont les fonctionnalités naissent du
**croisement** des domaines (« puis-je me permettre une sortie samedi ? »
mobilise finances + agenda + météo + véhicule) sans coder chaque combinaison
à la main. Le projet est au long cours : chaque nouveau tentacule doit
pouvoir s'ajouter sans toucher au cœur, et le moteur LLM doit rester
remplaçable.

Ce document couvre **le socle uniquement**. Le module finances fait l'objet
du document suivant ; il est le premier consommateur du socle et en valide
le contrat.

## Décisions actées

### Forme

- **Un tentacule = un serveur MCP écrit en Rust.** La tête est un client MCP.
  Le même tentacule est utilisable par tout client MCP (assistant de
  développement sur le poste, futur client mobile), sans passer par la tête.
- **Le cœur ne connaît aucun tentacule par son nom.** La liste des tentacules
  (nom, adresse, jeton) vient de la configuration ; en ajouter ou en retirer
  ne demande aucune modification de code de la tête.
- **Les calculs sont déterministes et vivent dans le tentacule.** Le LLM
  appelle un outil qui renvoie un résultat **déjà digéré** (un chiffre, une
  liste courte, un verdict), jamais des données brutes à faire calculer au
  modèle. Deux raisons : un LLM se trompe en arithmétique, et chaque token
  renvoyé au modèle coûte.
- **Le LLM propose, le code décide** dès qu'une écriture touche la base à
  partir d'un contenu externe : schéma strict, validation, provenance.

### La tête

Responsabilités, et seulement celles-ci :

1. **Conversation** sur un canal de messagerie auto-hébergé (protocole
   ouvert), salon privé utilisateur ↔ bot. Le bot n'accepte que les
   messages de l'identifiant utilisateur configuré (provenance stricte,
   fail-closed).
2. **Boucle d'agent** : message utilisateur → LLM avec les outils de tous les
   tentacules → appels d'outils → réponse.
3. **Choix du modèle par usage** via le routeur existant (`llm.rs`), étendu
   (voir plus bas).
4. **Politique de canal** (silence / écrit / voix) — garde-fou codé.
5. **Planification** : brief écrit du matin, brief écrit du soir, évaluation
   périodique des suggestions spontanées.
6. **Budget d'interruptions** quotidien.
7. **Référentiel de conversation** : la tête écrit elle-même, dans le
   tentacule mémoire, la fiche de chaque conversation.

### Politique de canal — garde-fou codé, pas jugement du LLM

Deux questions : *quelqu'un d'autre peut-il entendre ?* et *l'utilisateur
est-il occupé ?*

| Situation | Voix | Écrit |
|---|---|---|
| Écouteurs branchés, pas en appel | oui | oui |
| Seul au domicile, hors réunion et hors appel | oui | oui |
| Heures de travail sur site | non | non (mis en attente) |
| Travail à distance, en réunion | non | non (mis en attente) |
| Travail à distance, hors réunion | oui | oui |
| Hors domicile sans écouteurs | non | oui |
| En appel | non | oui |
| **Signal inconnu** | **non** | oui |

- **Signal inconnu → jamais de voix.** Dans le socle, aucun signal
  téléphone n'existe encore (écouteurs, appel, localisation) : toute sortie
  est écrite. La voix arrive avec le client mobile, hors périmètre.
- **Jamais d'audio non sollicité** en dehors du brief parlé du matin (futur).
- Signaux disponibles dans le socle : jours et horaires de travail (sur site
  / à distance) en configuration, réunions lues dans un **agenda dédié
  « travail »** (collection CalDAV distincte, synchronisée en plus de
  l'agenda personnel ; chaque événement garde son agenda d'origine),
  exceptions dites en conversation
  (« j'ai du monde ce soir », « pas de brief demain ») et stockées dans la
  mémoire immédiate avec une échéance.
- Le rythme de travail est une **règle modifiable par la conversation**, pas
  une constante.
- **La politique ne filtre que les sorties spontanées** (briefs,
  suggestions, rappels). Une réponse à un message que l'utilisateur vient
  d'écrire part toujours, à l'écrit : s'il écrit, il est disponible pour
  lire. La voix reste soumise au tableau. *(Choix de conception.)*
- Les messages mis en attente partent à la fin de la plage bloquante,
  regroupés.

### Tentacule mémoire

Base de connaissance de l'utilisateur, accessible à la tête **et** à tout
autre client MCP. Contenu du socle :

- **Journal d'événements** en ajout seul : date, source (canal, tentacule,
  import), type, contenu structuré, sensibilité, provenance.
- **Faits et préférences** (« préfère X à Y », goûts des proches, rythme de
  travail) : révisables, avec historique et provenance.
- **Intentions** à durée de vie : quoi, échéance d'expiration, contraintes,
  état (ouverte / proposée / acceptée / refusée / expirée). À expiration :
  abandon silencieux, pas de relance.
- **Personnalité** : fiche de caractère, de ton et de réflexes, chargée par
  chaque canal au démarrage — même assistant quelle que soit la porte
  d'entrée.
- **Référentiel des conversations** : une fiche par session (date, canal,
  appareil, sujets, décisions, faits appris, lien vers la transcription
  locale). Permet « on en a parlé mardi depuis le poste ».
- **Leçons** : stockage seulement (contexte, qualification, debrief,
  conclusion, actions). Leur transformation en garde-fous exécutés est hors
  périmètre du socle.

Recherche : plein texte natif SQLite (FTS5). Pas d'embeddings.

Outils exposés (noms et signatures laissés au constructeur) : écrire /
chercher dans le journal, lire / réviser un fait, créer / mettre à jour /
lister les intentions ouvertes, lire la personnalité, ouvrir / clore une
fiche de conversation, chercher dans le référentiel.

### Tentacule agenda

Expose en lecture ce qui existe déjà (synchronisation CalDAV en lecture,
tâches et routines du module planning) : événements d'un intervalle,
créneaux libres, tâches à échéance, statut « occupé » à un instant donné
(utilisé par la politique de canal). L'écriture dans l'agenda est hors
périmètre du socle.

### Clients tiers (assistant de développement sur le poste)

- Configuration MCP fournie en exemple pour brancher les tentacules depuis
  le poste via le réseau privé.
- **Hook de fin de session** côté client : simple script, sans LLM, qui
  envoie au tentacule mémoire la transcription de la session (canal,
  appareil, horodatage, messages). La mise à jour de la mémoire est garantie
  par le harnais, pas par la bonne volonté du modèle.
- **Le résumé est produit plus tard par la tête** (traitement différé,
  usage `resume_conversation` passé par le routeur) qui remplit la fiche :
  sujets, décisions, faits appris. *(Choix de conception.)*
- Transcriptions stockées par le tentacule mémoire, sur l'hôte, jamais
  ailleurs.

### Extension du routeur LLM

Le routeur actuel connaît deux voies (auto-hébergée, externe) d'un modèle
chacune. Le socle a besoin de :

- **Moteurs nommés et association usage → moteur** en configuration
  (ex. conversation → un modèle puissant ; tri, mise en forme → un modèle
  économique). Les règles de sensibilité existantes et les concessions
  journalisées restent en vigueur.
- **Appel d'outils** (tool use) dans le client LLM, absent aujourd'hui.
- **Protocole natif du fournisseur externe** en plus du protocole compatible
  OpenAI, pour bénéficier de la mise en cache du préfixe (consignes + liste
  d'outils), principal levier de coût.
- **Plafond de dépense** : comptage des tokens par usage et par jour,
  refus au-delà d'un plafond configuré (le plafond côté fournisseur reste le
  filet ultime).

### Sensibilité de la conversation

La conversation touche la mémoire, les proches, plus tard les finances :
elle est **stratégique**. Le modèle auto-hébergé disponible sur l'hôte ne
gère pas l'appel d'outils de façon fiable ; l'utilisateur a donc acté une
**dérogation temporaire** : les usages `conversation` (et ceux qui en ont
besoin, ex. résumé de conversation) sont déclarés en **concessions** dans
`llm.toml` — mécanisme existant, journalisé à chaque application — avec
masquage des identifiants avant envoi. Retour à l'auto-hébergé = retirer la
concession. Le fournisseur externe et son protocole natif sont précisés
hors repo, dans la configuration de l'hôte.

### Contrat commun des tentacules : section de brief

Pour que la tête compose les briefs **sans connaître les tentacules par leur
nom ni dépendre d'un LLM**, chaque tentacule peut exposer un outil
conventionnel `brief_section` (période : matin / soir) qui renvoie un bloc
déjà rédigé et court. La tête concatène les sections dans l'ordre de la
configuration ; si un moteur est configuré, il peut les reformuler en un
texte unique, sinon le brief part tel quel. Un tentacule sans cet outil est
simplement absent du brief. *(Choix de conception, pas une demande
explicite.)*

Le briefing emploi actuel reste une commande batch séparée pendant le
socle ; il deviendra une section quand l'emploi deviendra un tentacule.

### Sécurité

- Tentacules et tête joignables **uniquement sur le réseau privé**,
  aucun port public. Chaque tentacule exige un jeton porteur.
- Identifiants sensibles (IBAN, numéros de contrat, numéros d'assuré,
  adresses) **masqués de façon déterministe** avant tout envoi à un moteur
  externe.
- Le LLM ne reçoit que le contexte minimal d'une requête ; aucune base n'est
  envoyée en bloc.
- Contenu externe (emails, réponses d'API) = non fiable : validation,
  assainissement, jamais d'exécution de consignes qu'il contiendrait.
- Aucune donnée personnelle, aucun hôte, aucun fournisseur dans le code
  versionné : tout passe par la configuration gitignorée et des fichiers
  `*.example` neutres.

## Objectifs

1. Un tentacule mémoire et un tentacule agenda en Rust, servis en MCP sur le
   réseau privé.
2. Une tête qui converse dans le salon de messagerie en utilisant les outils
   des tentacules configurés.
3. Briefs écrits du matin et du soir envoyés par la tête, respectant la
   politique de canal.
4. Suggestions spontanées rares, sous budget, filtrées par la politique de
   canal.
5. Continuité entre la tête et un client MCP tiers via la mémoire, la
   personnalité et le référentiel de conversation.
6. Les commandes batch existantes (`scrape`, `briefing`, `calendar sync`…)
   continuent de fonctionner pendant toute la transition.

## Non-objectifs (socle)

- Module finances (document suivant).
- Voix : synthèse, transcription, brief parlé, mot d'éveil, client mobile.
- Tri des emails, alimentation, santé, contrats, relations : tentacules
  futurs.
- Écriture dans l'agenda.
- Condensation des souvenirs, transformation des leçons en garde-fous,
  révision automatique des préférences.
- File de tâches différées vers le poste de travail.
- Délégation d'appels LLM d'un tentacule vers la tête (mécanisme MCP de
  « sampling ») : aucun tentacule du socle n'en a besoin.
- Registre de modules avec découverte automatique : la liste en
  configuration suffit.

## Anti-objectifs (échec même si « ça marche »)

- Un assistant **bavard** : plus de choses à surveiller qu'avant.
- Une interruption pendant les heures de travail sur site ou en réunion.
- Une sortie audio non sollicitée.
- Un montant calculé par le LLM.
- Une donnée qui quitte le réseau privé hors appel LLM minimal et masqué.
- Une donnée personnelle, un hôte ou un fournisseur dans le repo public.
- Un tentacule dont l'ajout exige de modifier la tête.

## Contraintes

- Rust 2024 partout. SDK MCP officiel Rust.
- Mémoire vive limitée sur l'hôte d'exécution : la tête et les tentacules
  doivent rester sobres (ordre de grandeur visé : quelques dizaines de Mo
  chacun).
- Le serveur de messagerie n'est pas déployé par le socle : il y inscrit
  seulement un compte bot.
- SQLite comme stockage.
- Déploiement Docker Compose existant (`deploy/docker/`), procédure de
  déploiement existante.
- Moteur de la tête **non fixé** : il se choisit en configuration, sur la
  base de la consommation mesurée. Le socle doit fonctionner avec n'importe
  quel moteur supportant l'appel d'outils, et dégrader proprement (briefs
  sans conversation) si aucun n'est configuré.

## Laissé au constructeur

- Découpage en crates (workspace Cargo), nommage des binaires.
- Transport MCP (HTTP « streamable » recommandé pour l'accès réseau) et
  authentification par jeton.
- Schéma SQLite précis du tentacule mémoire, une base par tentacule ou non.
- Bibliothèque cliente de messagerie et activation du chiffrement de bout en bout
  (souhaitable si le coût mémoire reste raisonnable).
- Format des fiches de conversation et du hook de fin de session.
- Fréquence de l'évaluation des suggestions spontanées et forme du budget.
- Forme de la configuration (fichiers TOML existants à étendre ou nouveaux).
