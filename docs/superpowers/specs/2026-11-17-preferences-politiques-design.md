# Préférences politiques — conception

## 1. Intention

L'utilisateur se sait biaisé : il examine plus durement les propositions qu'il identifie
comme contraires à ses idées, et accorde plus facilement sa confiance à celles qui leur
ressemblent, alors qu'elles peuvent être tout aussi fausses.

Kairos ne traite pas les données différemment selon le camp : la grille reste la même
pour tous. Ce qui change, c'est l'attention portée aux **remarques de l'utilisateur**.
Quand une remarque va dans le sens de ses propres positions, Kairos lui montre ce qui
la critique. Quand elle va contre, il lui montre ce qui la soutient. C'est un
contrepoids, pas une censure.

Connaître ces positions ouvrira ensuite d'autres usages, activés un par un (§ 8).
Ces usages ne viennent qu'après la fiabilisation des modules du quotidien : des
préférences branchées sur des modules peu utilisés n'apportent rien.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**

- Un profil de positions par thème, déclaré par l'utilisateur et enrichi de propositions
  qu'il valide.
- Un contrepoids pendant les conversations : systématique, désactivable, sourcé d'abord.
- Une donnée de catégorie particulière traitée comme telle : stockée chez l'utilisateur,
  analysée uniquement par le modèle local, consultable, corrigeable, effaçable.

**Non-objectifs**

- Classer l'utilisateur dans un camp ou lui attribuer une étiquette.
- Modifier les données publiques (site, verdicts, camps) selon le profil.
- Recommander pour qui voter.

**Anti-objectifs** (échec même si « ça marche »)

- Une position enregistrée sans validation explicite.
- Une donnée du profil qui sort de l'hôte : moteur externe, journal, dépôt public,
  partage, sauvegarde non chiffrée.
- Un contrepoids inventé présenté comme un fait.
- Un contrepoids qui ne s'exerce que dans un sens.

## 3. Le profil

Une **position** porte sur un thème :

| Champ | Contenu |
|---|---|
| thème | identifiant d'un thème de Mimir (énergie, dette, Europe…) ou thème libre créé par l'utilisateur |
| échelle | de −2 (très opposé) à +2 (très favorable), sur un énoncé propre au thème (ex. « sortir du nucléaire ») |
| phrase libre | la position dans les mots de l'utilisateur, facultative |
| certitude | « j'en suis sûr », « je penche », « je ne sais pas » |
| origine | déclarée, ou proposée puis validée (avec la référence de la conversation) |
| historique | chaque changement daté, conservé ; rien n'est écrasé |

Les thèmes de Mimir viennent en premier, parce que chaque position s'y rattache
directement à des faits sourcés. Un thème libre n'a pas de contrepoids Mimir tant que le
site ne le couvre pas.

**Thèmes.** Mimir publie une liste de thèmes à identifiants stables (`themes.json`), à
laquelle se rattachent ses rubriques. Une position référence cet identifiant, jamais un
libellé. C'est un travail préalable côté Mimir.

**Stockage.** Une table `positions` dans le module mémoire, distincte des souvenirs et
des faits :

- pas de courbe d'oubli : une position ne s'estompe pas si on n'en parle plus ;
- historique propre, rien n'est écrasé ;
- marquage sensible propre ;
- **exclue** du rappel injecté dans les conversations, de `facts_list` et de
  `persona_get` ;
- lecture et écriture par des outils `app_` seulement, que la tête ne donne jamais au
  moteur de conversation.

Ce choix est celui du concepteur. Il réutilise l'hébergement, l'effacement et la passe
de nuit du module, sans exposer le profil au rappel.

## 4. Acquisition

1. **Déclaration.** Écran « Mes positions » dans l'espace Politique de l'app. On y voit
   les thèmes, puis pour chacun une échelle, une phrase et une certitude. Rien n'est
   prérempli.
2. **Proposition.** La passe de nuit du module mémoire, exécutée par la tête sur le
   modèle local (usage `preferences`), lit la **transcription** des conversations
   archivées. Leur conservation est prolongée au-delà du délai de la passe, pour pouvoir
   citer les mots exacts. Décision de l'utilisateur ; la durée est à régler, 7 jours par
   défaut. Elle y repère les positions exprimées
   sur un thème connu, puis produit des **propositions** :
   - le thème ;
   - l'échelle suggérée ;
   - la citation exacte de l'utilisateur ;
   - la conversation d'origine.
3. **Validation.** Le lendemain, les propositions arrivent en cartes dans l'app, sur le
   modèle des décisions de finances : accepter, ajuster puis accepter, ou rejeter. Une
   proposition rejetée n'est pas reproposée pour la même citation. Sans réponse, elle
   expire au bout de 14 jours (valeur à régler) et n'est jamais appliquée.

## 5. Le contrepoids

**Quand.** Pendant une conversation avec la tête, quel que soit le moteur, dès qu'une
remarque de l'utilisateur porte sur un thème de son profil :

- remarque dans le sens de sa position : Kairos ajoute ce qui la critique ;
- remarque contre sa position : Kairos ajoute ce qui la soutient ;
- plus la certitude enregistrée est forte, plus le contrepoids est présent. C'est là
  qu'on vérifie le moins.

**Sources, dans cet ordre.**

1. Faits sourcés de Mimir : affirmations et verdicts, chiffrages, votes, revirements. Ils
   sont cités avec leur source, comme sur le site.
2. Raisonnement du modèle, seulement si Mimir n'a rien, présenté explicitement comme
   « argument non sourcé ».

**Délai.** Le modèle local peut mettre plusieurs minutes. La réponse principale part sans
attendre, et le contrepoids suit dans un **message séparé**, dès qu'il est prêt, rattaché
à la remarque qu'il concerne. Décision de l'utilisateur.

**Sens d'un fait.** Mimir annote, à la main et avec source, le sens d'un fait au regard
de l'énoncé d'un thème (soutient / critique / neutre). L'annotation commence par les
thèmes principaux. Un fait non annoté n'est pas utilisé comme contrepoids sourcé. Le
modèle ne classe pas les faits. Décision de l'utilisateur ; c'est un travail préalable
côté Mimir.

**Forme.** Un paragraphe court, distinct de la réponse. Pas de leçon de
morale, et le même ton dans les deux sens.

**Arrêt.**

- « pas de contrepoids » le coupe pour la conversation en cours ;
- un interrupteur dans les réglages de l'app le coupe durablement : réglage global, tenu
  par la tête, piloté par un outil `app_` ;
- l'état est rappelé en une ligne quand il est coupé, pour qu'il ne soit jamais oublié.

**Détection.** Décider si une remarque touche un thème, et dans quel sens, relève du
modèle local. En cas de doute, il s'abstient plutôt que de forcer un contrepoids.

## 6. Confidentialité

- Un usage LLM dédié, `preferences`, utilisé pour la détection et pour l'extraction de
  nuit. Il est **stratégique par construction** : refusé dans `common_usages` comme
  `conversation`. Aucun repli externe. Sans moteur local disponible, il n'y a pas de
  contrepoids.
- Les recherches dans Mimir sont faites par la tête, en code : les outils de la
  tentacule `politique` sont réservés à l'app et ne passent pas par la boucle d'outils.
- Quand l'utilisateur converse avec un moteur externe, le profil ne lui est jamais
  transmis. Le contrepoids s'appuie alors seulement sur les faits Mimir publics, sans
  connaître la position, et dans les deux sens. Ce choix est celui du concepteur, à
  confirmer.
- Le profil n'apparaît dans aucun briefing, aucun partage, aucun journal applicatif.
- L'écran « Mes positions » permet de tout voir, corriger, effacer : position par
  position ou en bloc, historique compris.
- La future sauvegarde de la base mémoire, pas encore en place, devra être chiffrée.
- Dépôt public : aucune position réelle dans le code, les tests, la documentation. Les
  fixtures sont fictives.

## 7. Réalisation par lots

1. **Profil.** Nature `position` dans le module mémoire, outils de lecture et d'écriture
   réservés à l'app, écran « Mes positions ». Effacement complet.
2. **Propositions.** Étape de la passe de nuit, cartes de validation dans l'app.
3. **Contrepoids.** Dans la tête : détection, recherche dans Mimir, paragraphe,
   interrupteurs.

## 8. Usages activables, plus tard

Chaque usage a sa propre spec, est désactivé par défaut et s'explique quand il agit :

- rendez-vous et actions locales en lien avec les positions (agenda) ;
- contre-points de vue proposés hors conversation ;
- habitudes cohérentes avec les positions : alimentation, consommation ;
- lectures (futur module lecture) ;
- paris à surveiller (module Marchés) ;
- prochaines élections et pièces nécessaires pour voter.

## 9. Ce qui reste au concepteur

- Formulation exacte de l'énoncé de chaque échelle par thème.
- Format des cartes de proposition et du paragraphe de contrepoids, dans la DA de l'app.
- Seuil d'abstention de la détection.
- Délai d'expiration des propositions.
