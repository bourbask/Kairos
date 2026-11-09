# Application — espaces des modules (Finances d'abord)

Complète `2026-11-08-app-mobile-design.md`. Décidé avec l'utilisateur le
2026-11-08.

## Intention

Chaque module de Kairos a son espace dans l'application, accessible depuis
« Espaces » : un aperçu de son état et les actions utiles, sur un écran **conçu
pour lui** (comme l'Inventaire). « Aujourd'hui » reste l'entrée principale et
agrège l'essentiel de la journée.

## Principes

- **Écran dédié par module**, construit avec une **boîte à outils commune**
  (`ui/kit/`) : chiffre clé, jauge, barres, courbe, anneau, liste, bandeau
  d'état, montant masquable. Même identité partout, développement rapide.
- Écran générique de secours seulement pour un module sans écran dédié.
- Données lues par les outils `app_*` du module (passe-plat de la tête), en
  JSON ; dernier état gardé hors ligne comme ailleurs, lecture seule sans
  réseau.
- **Montants masquables** : un geste visible (icône œil) masque tous les
  montants des espaces (utile en public) ; réglage mémorisé.
- Ordre de réalisation : **Finances**, puis Agenda, puis Médias ; ensuite
  Marchés, Documents, Mémoire.

## Espace Finances

Sources : module finances (cycle budgétaire, enveloppes, alertes, « puis-je me
permettre ») et backend de finances (patrimoine, historique mensuel, prêts,
portefeuille).

Deux onglets :

**Cycle** (ouverture par défaut)
- En tête : **reste à vivre** du cycle (gros chiffre), jours restants, jauge
  d'avancement du cycle (début → fin, aujourd'hui marqué).
- **Enveloppes** : une barre par poste, dépensé / prévu, couleur ambre au-delà
  de 80 %, brique au-delà de 100 %.
- **Charges à venir** du cycle : liste datée avec montants.
- **Coussin** : solde face au plancher.
- **Alertes** du module (titre, détail, remède).
- **« Puis-je me permettre ? »** : bouton → saisie d'un montant + choix du
  poste en puces → verdict (oui/non, solde après, coupes proposées).
- Bandeau si données périmées ou consentement bancaire expiré (date de
  dernière synchronisation).

**Patrimoine**
- Patrimoine net (gros chiffre) et sa **courbe mensuelle**.
- Dettes et prêts (restant dû, mensualité, fin prévue).
- Portefeuille : **anneau** par catégorie de risque et liste des lignes.

Hors du premier jet : saisie de stratégie, flux détaillé (Sankey), liste des
opérations.

## Non-objectifs

- Écrire dans la stratégie de finances depuis l'application.
- Graphiques interactifs avancés (zoom, comparaison).

## Espace Agenda

Sources : module agenda (événements des calendriers perso et travail, plages
libres, tâches du planning), rythme de travail de la tête (sur site,
télétravail, repos).

- **Bandeau de jours** : 7 jours à partir d'aujourd'hui (puces), le jour
  choisi ouvre sa vue ; glisser horizontalement sur la vue (jamais depuis le
  bord) passe au jour voisin ; bouton « Aujourd'hui ».
- **Vue du jour** : frise 07 h–23 h, événements en blocs (perso / travail
  distingués), **plages libres** visibles (« libre 2 h »), trait de l'heure
  actuelle, mode de travail du jour en badge.
- **Tâches** dues à ce jour : glisser → = faite (annulable 4 s).
- **À venir** : les prochains événements sur 7 jours, en liste compacte.
- **Ajouter un événement** : titre, jour, heure de début, durée en puces
  (30 min, 1 h, 2 h, journée), calendrier perso ou travail ; écrit dans le
  calendrier puis visible aussitôt.
- **Fraîcheur** : l'agenda local est resynchronisé à l'ouverture de l'espace
  (au plus une fois par minute), sans attendre la synchronisation de nuit.

Hors du premier jet : modifier ou supprimer un événement, invitations.

## Espace Marchés

Sources : module de veille des marchés de prédiction (questions suivies,
scénarios, historique des probabilités sur 30 jours, alertes de bascule et de
volume, propositions hebdomadaires, projections « si ça monte / si ça baisse »).
Lecture seule du monde : aucun pari, aucun compte.

- **Questions suivies** : pour chacune, la question, la probabilité actuelle de
  l'issue suivie (gros pourcentage), la variation sur 24 h et 7 jours (flèche
  et couleur), une **mini-courbe** des 30 derniers jours, l'échéance.
  Tri : plus forte variation récente d'abord.
- **Détail d'une question** (tap) : courbe sur 30 jours, volume, issues avec
  leur probabilité, projection (« si ça monte… / si ça baisse… »), scénarios
  liés. Glisser ← dans la liste = ne plus suivre (annulable).
- **Scénarios** : chaque scénario avec ses questions liées (signe + / −) et
  leur tendance.
- **Alertes récentes** : bascules et pics de volume, datés.
- **Propositions** de la semaine en pile de cartes : → suivre, ← ignorer.

Hors du premier jet : créer ou modifier un scénario depuis l'application,
recherche libre de marchés.

## Espace Mémoire

Sources : module mémoire (intentions avec échéance, journal avec niveau de
sensibilité, faits connus sur l'utilisateur avec historique, conversations
résumées, leçons).

- **Intentions** en cours, l'échéance la plus proche d'abord, avec le temps
  restant : glisser → accepter, ← refuser, bouton « plus tard ». Une intention
  échue disparaît sans relance (règle existante). Ajout rapide : texte (clavier
  ou dictée) et échéance en puces (aujourd'hui, 3 jours, 1 semaine, 1 mois).
- **Journal** : les entrées récentes datées, recherche ; ajout d'une note
  (clavier ou dictée). Les entrées marquées sensibles sont masquées par défaut
  (tap pour afficher), et toujours masquées quand les montants le sont.
  « Supprimer » sur une entrée affichée (une entrée sensible se révèle d'abord).
- **Ce que Kairos sait de toi** : faits groupés par thème (préfixe de clé),
  valeur courante ; tap = historique des valeurs ; correction d'une valeur ;
  « Oublier ce fait » efface la valeur et tout son historique.
- **Conversations** : les dernières, avec leur résumé, sujets et décisions ;
  recherche ; « Supprimer ».

Les effacements sont définitifs, après la fenêtre d'annulation de 4 s des
autres gestes. Ils passent par des outils `app_*`, que le moteur ne voit pas.

Hors du premier jet : modifier la personnalité de l'assistant (seulement depuis
l'hôte), éditer une leçon.

## Espace Médias

Sources : module médias (sur la machine qui héberge la pile média, joint par la
tête comme les autres). Rôle de l'écran : **« qu'est-ce que je peux regarder
maintenant, qu'est-ce qui arrive, quelque chose bloque-t-il ? »**.

- **À regarder** (en tête) : reprises en cours (temps restant) puis prochains
  épisodes et films récents prêts, avec l'état des sous-titres (VF, ST FR,
  absents, recherche en cours). Glisser → « vu » (annulable 4 s), bouton
  visible. Les éléments sans sous-titres français sont regroupés à part.
- **Téléchargements** : avancement en barres, temps restant, éléments bloqués
  avec la raison ; un bouton pause / reprise global.
- **À venir** : sorties suivies des 7 prochains jours (épisodes, films), datées
  en heure locale, « déjà là » signalé.
- **Demandes** : demandes suivies et leur état ; **ajouter** : recherche
  (séries et films) puis « demander » sur un résultat.
- **Problèmes** : bandeau quand un service est injoignable ou que la
  bibliothèque signale des échecs ; une section dont le service est absent
  affiche sa raison sans masquer les autres.
- Carte Espaces : nombre d'éléments prêts et la prochaine sortie.

Données externes (titres, raisons) : non fiables, nettoyées et bornées côté
module. Hors du premier jet : statistiques, musique, lecture et livres audio,
obligations de partage, résumé « previously », relance et liste noire des
versions, ne plus suivre.

## Espace Documents

Sources : module documents (index des documents administratifs, faits
extraits, échéances calculées, liste de contrôle des pièces attendues). Rôle de
l'écran : **« qu'est-ce qui arrive à échéance, où est tel papier, que me
manque-t-il ? »**. Lecture seule : aucune écriture (déplacer, renommer,
corbeille) depuis l'application.

- **Échéances** (en tête) : retards d'abord, puis les 90 prochains jours,
  datées, avec le temps restant (couleur ambre sous 30 jours, brique en
  retard) ; type, organisme et motif (fin, résiliation, renouvellement,
  expiration, contrôle). Nature privée : seulement « document privé », le motif
  et la date.
- **Chercher** : champ de recherche plein texte ; résultats avec nom, nature,
  date et un court passage autour des mots cherchés. Aucun résultat d'une
  nature privée.
- **Pièces attendues** : liste de contrôle par domaine, présentes / à fournir,
  les manquantes d'abord.
- **Classement** : natures (dossiers) avec leur nombre de documents et ceux
  sans texte lisible ; avancement de l'extraction.
- Carte Espaces : prochaine échéance et nombre de retards.

Hors du premier jet : ouvrir le fichier, écritures, doublons, plan de
renommage, faits détaillés d'un contrat.
