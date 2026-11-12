# Module Politique — conception

## 1. Intention

Un site d'information politique existe déjà à côté de Kairos : des données publiques
rédigées et sourcées à la main (programmes chiffrés, votes, déclarations vérifiées,
revirements, procédés rhétoriques, probité, financements, médias, niveau de vie,
résultats locaux), affichées par un site web statique et partagées par liens d'accès
(module `partage`). Dans l'application, il est aujourd'hui affiché dans une vue web :
deux styles qui ne vont pas ensemble, une navigation qui marche mal.

Le module **Politique** fait de ces données un module Kairos :

- une **source unique** de données, lue à la fois par le site web et par le module ;
- un **écran natif** dans l'application, dans le style Kairos, conçu pour s'informer,
  comparer, vérifier et partager ;
- plus tard (lot 2, spec séparée) : les centres d'intérêt politiques de l'utilisateur et
  des croisements avec d'autres modules, activés un par un.

Usage visé : comprendre où se situe un candidat ou un parti, comparer des chiffres
sourcés, confronter les propos aux votes, vérifier une phrase entendue, reconnaître les
procédés rhétoriques, sortir de sa bulle, et partager un point précis avec ses proches
pour en discuter.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs (lot 1)**
- Le module `politique` sert, en lecture seule, les données publiques existantes à
  l'application, par des outils `app_*` (passe-plat de la tête, cachés au moteur).
- L'espace Politique natif remplace la vue web dans l'application, avec les écrans de la
  maquette validée (§4).
- Le site web continue de fonctionner à l'identique, sur les mêmes fichiers.
- Une scène de personnage pour l'espace Politique : **l'urne** (le personnage plie son
  bulletin, le glisse dans une urne transparente, un voyant s'allume).

**Non-objectifs (lot 1)**
- Aucune donnée personnelle : ni préférences, ni historique de consultation, ni
  croisement avec d'autres modules (lot 2).
- Pas d'édition des données depuis l'application ; pas d'ingestion automatique de
  sources externes ; pas de moteur de langue dans ce module.
- Le moteur de conversation n'a pas accès aux données politiques (lot 2 décidera).

**Anti-objectifs**
- Un écran qui trie, classe ou recommande un camp. L'écran expose des données sourcées et
  les met côte à côte ; il ne conclut pas à la place du lecteur.
- Une donnée sans source affichée, ou une source perdue en route.
- Un nom de candidat, de parti, de site ou une opinion de l'utilisateur dans le dépôt
  public (code, tests, docs, exemples) : valeurs fictives seulement.

## 3. Données

### 3.1 Source

Les fichiers JSON du site (une douzaine de rubriques, ~100 Ko, date de vérification
dans `meta.json`) restent la source de vérité, dans le dépôt privé du site. Sur l'hôte,
le module `politique` lit ce dossier **monté en lecture seule** ; le site continue de les
servir tels quels. Une mise à jour des fichiers vaut pour les deux, sans redémarrage :
le module relit un fichier quand sa date de modification change.

Les rubriques sont hétérogènes (listes de chiffres, matrices, fiches). Le module ne
réinvente pas leur sens : il les expose rubrique par rubrique, et ajoute une seule
couche transversale, l'**identité** des candidats et partis (§3.2).

### 3.2 Identités

Les rubriques désignent les personnes et partis par des libellés libres. Un fichier
`identites.json`, ajouté à côté des données (dépôt privé), fait la jointure :
identifiant stable, nom affiché, parti, couleur, et les **alias** sous lesquels il
apparaît dans chaque rubrique. Le module rattache chaque entrée de rubrique à une
identité par ses alias ; une entrée non rattachée reste visible dans sa rubrique,
jamais perdue. Le module signale au démarrage, sans les citer, le nombre d'alias
inconnus.

### 3.3 Contrat des outils (module `politique`, tous `app_*`)

| Outil | Arguments | Résultat |
|---|---|---|
| `app_politique_accueil` | — | date de vérification, identités (id, nom, parti, couleur), rubriques (id, titre, résumé d'une ligne, nombre d'entrées) |
| `app_politique_rubrique` | `id` | la rubrique telle quelle, plus pour chaque entrée l'identité rattachée si elle existe |
| `app_politique_fiche` | `id` (identité) | toutes les entrées rattachées, regroupées par rubrique, avec leurs sources |
| `app_politique_comparer` | `ids` (2 à 3), `theme` | une ligne par indicateur comparable du thème, une colonne par identité, chaque valeur avec sa source ; « non renseigné » quand une donnée manque |
| `app_politique_chercher` | `q` | entrées de toutes les rubriques qui contiennent les termes (recherche simple, sans accents ni casse), groupées par rubrique |

Thèmes comparables (lot 1) : ceux que les données permettent réellement de mettre côte
à côte, au moins le **budget des programmes** et le **niveau de vie** ; la liste est
renvoyée par l'accueil, pas codée dans l'application.

Résultats bornés (taille), erreurs claires (« identité inconnue », « thème inconnu »),
aucun contenu dans les journaux.

## 4. Écran natif (maquette validée)

> **Partie application remplacée par le lot 2** (`2026-11-12-politique-lot2-design.md`) :
> l'application lit désormais les fichiers de données paginés (`app_politique_fichier`)
> et ne passe plus par les outils d'accueil, de rubrique, de fiche, de comparaison et de
> recherche décrits au §3.3, qui restent servis par le module. La section ci-dessous
> décrit l'écran du lot 1, conservé pour l'historique.

Espace « Politique » ouvert depuis sa carte, comme les autres espaces (flèche vers le
bas pour fermer, mêmes motifs de chargement, d'erreur et de hors ligne).

1. **Accueil** : recherche (un thème ou une phrase entendue) ; « Données vérifiées le … » ;
   identités en vignettes défilantes ; deux actions principales **Comparer** et
   **Vérifier une phrase** ; rubriques en cartes (Dit / voté, Budgets, Votes,
   Revirements, Rhétorique, Probité, Argent, Médias, et une carte large **Hors de ma
   bulle**).
2. **Fiche** d'une identité : synthèse et sources, puis onglets par rubrique où elle
   apparaît ; bouton « Comparer avec… ».
3. **Comparer** : choix de 2 à 3 identités et d'un thème ; tableau ; chaque case ouvre sa
   source.
4. **Vérifier une phrase** : la recherche appliquée aux déclarations vérifiées
   (verdict nuancé, faits, sources), plus les autres rubriques qui en parlent.
5. **Dit / voté** : par identité, ses déclarations face aux votes et revirements quand
   les données les relient ; sinon les deux listes côte à côte, sans prétendre à un lien
   que la donnée n'établit pas.
6. **Rhétorique** : fiches des procédés (définition, comment le repérer, exemple, question
   à poser).
7. **Hors de ma bulle** : par thème, les affirmations de chaque camp côte à côte avec leur
   verdict et leurs faits, et le chiffre qui contredit le plus souvent l'intuition quand
   la donnée le permet. Aucun choix de camp mémorisé (lot 1).
8. **Partage** : depuis l'espace, le bouton de partage existant du site (liens d'accès
   du module `partage`) reste disponible. Le partage d'une fiche ou d'une comparaison
   précise est **hors lot 1** (le site devra savoir ouvrir une vue précise).

Le site reste accessible depuis l'espace pour qui préfère la vue complète (« Ouvrir le
site »), mais n'est plus l'écran principal.

## 5. Personnage

Nouvelle scène **Urne** pour l'espace Politique, dans le système de scènes existant
(grille 32 × 32, décor + rig de pose, tous les personnages) : il plie un bulletin, le
glisse dans une urne transparente, un voyant s'allume, petite réaction. Lisible à la
taille réelle du bouton. La maquette des personnages (hors dépôt) reçoit la scène
d'abord, puis l'application, avec ses images de référence.

## 6. Contraintes

- Module en lecture seule, sans moteur de langue, outils `app_*` uniquement,
  `private = true` côté tête.
- Conteneur durci comme les autres (lecture seule, sans capacités, mémoire et processus
  limités), réseau interne uniquement ; le dossier de données monté en lecture seule.
- Dépôt public : aucun nom réel (candidat, parti, site), aucune opinion ; données de test
  fictives ; l'identité réelle des données et le fichier `identites.json` vivent dans le
  dépôt privé du site.
- Application : rien de ce module n'est écrit sur l'appareil au-delà du cache commun
  déjà utilisé par les espaces publics.

## 7. Lot 2 (à spécifier plus tard)

Centres d'intérêt et préférences politiques de l'utilisateur (donnée de catégorie
particulière) : stockés chez lui seulement, jamais vers un moteur tiers, jamais dans le
dépôt public ; croisements activables un par un (orientation du module Marchés,
suggestions d'événements…), toujours expliqués et accompagnés de ce qui contredit ;
accès du moteur de conversation aux données publiques ; partage d'une vue précise.
