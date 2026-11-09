# Mémoire de Kairos — un modèle inspiré de la mémoire humaine

Proposition de modèle, à valider avant tout plan. Complète le module mémoire
existant (journal, faits, intentions, conversations, leçons, personnalité).

## 1. Intention

Kairos doit se souvenir comme un humain, pas comme une base de données :

- **ne jamais oublier l'essentiel** : qui est l'utilisateur, ses proches
  déclarés, ses préférences fondatrices, la personnalité de l'assistant ;
- **comprendre les habitudes** à force de les observer, sans qu'on les lui dicte ;
- **voir qu'une habitude change** et laisser l'ancienne s'estomper peu à peu,
  au lieu de la défendre ou de l'effacer d'un coup ;
- **laisser s'effacer le détail du quotidien** tout en gardant le sens
  (« la semaine dernière a été chargée » plutôt que chaque rendez-vous) ;
- **retrouver un souvenir ancien quand on le lui rappelle**, même s'il ne
  l'évoquait plus de lui-même.

Anti-objectifs : une mémoire qui grossit sans fin ; une mémoire qui « oublie »
sans jamais supprimer réellement (faux droit à l'oubli) ; une mémoire qui invente
(un souvenir déduit présenté comme un fait déclaré) ; un traitement qui envoie des
données personnelles à un modèle hébergé ailleurs.

## 2. Ce que dit la recherche sur la mémoire humaine

| Mécanisme | Ce qu'on en retient pour Kairos | Références |
|---|---|---|
| **Courbe de l'oubli** : la rétention décroît vite puis lentement | chaque souvenir perd de sa facilité d'accès avec le temps | Ebbinghaus, 1885 |
| **Répétition espacée** : un rappel réussi ralentit l'oubli suivant, d'autant plus qu'il est espacé | chaque réutilisation renforce le souvenir ; des rappels rapprochés comptent moins | Cepeda et al., 2006 ; modèles SM-2 / FSRS |
| **Deux forces distinctes** : force de stockage (ne fait que croître) et force d'accès (décroît sans usage) | « estompé » n'est pas « effacé » : un souvenir peu accessible revient vite si on le rappelle | Bjork & Bjork, *New theory of disuse*, 1992 |
| **Activation = fréquence + récence** : `A = ln(Σ tⱼ^−d)`, d ≈ 0,5 | score d'accès calculé à partir des dates d'usage, sans modèle de langue | Anderson, ACT-R, 1998 |
| **Importance et émotion** renforcent la consolidation | un souvenir important part avec une stabilité plus grande | McGaugh, 2004 |
| **Plusieurs systèmes** : épisodique (événements datés), sémantique (connaissances), procédural (habitudes), prospectif (intentions), mémoire de travail | des catégories de souvenirs aux règles d'oubli différentes | Tulving, 1972 ; Squire, 2004 |
| **Consolidation pendant le sommeil** : les épisodes se transforment en connaissances générales, le détail se perd | traitement nocturne qui résume, généralise et allège | McClelland et al., 1995 (systèmes complémentaires) |
| **Interférence et reconsolidation** : une information nouvelle qui contredit l'ancienne la remplace progressivement | une valeur déduite qui change coexiste un temps avec l'ancienne avant de la remplacer | Nader et al., 2000 |
| **Habitudes** : se forment par répétition dans un même contexte ; une nouvelle routine fait s'éteindre l'ancienne, qui se réapprend vite si elle revient | habitudes apprises par comptage, ancienne habitude affaiblie mais réactivable | Wood & Rünger, 2016 ; « savings » d'Ebbinghaus |
| **Agents à mémoire** : rappel = récence × importance × pertinence, avec des « réflexions » périodiques | formule de rappel et synthèse nocturne éprouvées sur des agents de langage | Park et al., *Generative Agents*, 2023 |

## 3. Le modèle proposé

### 3.1 Six familles de souvenirs

| Famille | Exemples (fictifs) | Oubli | Existant |
|---|---|---|---|
| **Noyau** | identité de l'utilisateur, proches déclarés, préférences fondatrices, personnalité | **jamais** (ancre) | faits + personnalité |
| **Épisodes** | « dîner pris dehors mardi », une conversation, un rendez-vous passé | oui, puis condensés | journal, conversations, repas notés |
| **Connaissances** | « allergique au pollen », « préfère les trajets en train » | lent, révisables | faits |
| **Habitudes** | « dîne vers 19 h en semaine », « jour sur site le mercredi » | s'estompent quand elles ne se vérifient plus | à créer |
| **Intentions** | « rappeler le garage avant vendredi » | à l'échéance | intentions |
| **Leçons** | une erreur et la règle qui en découle | jamais (devient un garde-fou) | leçons |

La mémoire de travail (la conversation en cours) n'est pas stockée en tant que
souvenir ; seule sa transcription l'est, comme aujourd'hui.

### 3.2 Ce que porte chaque souvenir

| Champ | Rôle |
|---|---|
| `stabilite` (jours) | vitesse d'oubli : plus elle est grande, plus le souvenir dure |
| `dernier_rappel` | date de la dernière réactivation (création, usage, observation) |
| `rappels` | nombre de réactivations |
| `importance` (0 à 1) | fixée à la création, révisable |
| `ancre` | vrai = ne s'oublie jamais (noyau, leçons, « retiens ça ») |
| `confiance` (0 à 1) | déclaré par l'utilisateur = 1 ; déduit = moins |
| `provenance`, `sensibilite` | déjà présents ; conservés |
| `etat` | actif → estompé → condensé → oublié |

### 3.3 Facilité d'accès et oubli

Facilité d'accès d'un souvenir au jour `t` :

```
R(t) = exp(−(t − dernier_rappel) / stabilite)        (entre 0 et 1)
```

- **Actif** : `R ≥ 0,5` — Kairos peut l'évoquer spontanément.
- **Estompé** : `R < 0,5` — n'est plus évoqué de lui-même, mais une recherche le
  retrouve, et le retrouver le réactive.
- **Condensé** : estompé depuis plus de `D_cond` jours, épisode seulement — fondu
  dans un souvenir de synthèse (§3.6), le détail est supprimé.
- **Oublié** : `R < 0,05`, sans protection — supprimé réellement de la base.

Ce qui peut être oublié, et ce qui ne l'est jamais automatiquement :

| Souvenir | Oubli automatique |
|---|---|
| Ancre (noyau, leçons, « retiens toujours ») | jamais (`R = 1`) |
| Connaissance **déclarée** par l'utilisateur (`confiance = 1`) | jamais : elle peut s'estomper (ne plus être évoquée), pas disparaître ; seul « oublie » la supprime |
| Connaissance ou habitude **déduite** | oui |
| Épisode | jamais seul : il passe toujours par la condensation |
| Synthèse de semaine ou de mois | oui, une fois fondue dans le niveau supérieur |
| Synthèse d'année | jamais (quelques lignes par an) |
| Souvenir qui renvoie à un document | jamais tant que la table des durées légales n'existe pas |

L'instant où un souvenir est devenu estompé se calcule (`dernier_rappel + stabilite × ln 2`) :
il n'est pas stocké.

### 3.4 Renforcement

Une **réactivation** est un signal venu du monde réel, jamais une simple lecture
par le système (sinon tout ce qui est relu se renforcerait sans fin) :
- l'utilisateur en reparle ou le confirme (détecté par l'extraction nocturne, §3.6) ;
- le comportement est observé à nouveau (habitudes, §3.7) ;
- l'utilisateur le ravive ou le corrige dans l'application.

Les lectures par les outils, les rappels proposés au modèle (§3.9) et les recherches
du traitement de nuit ne réactivent rien. À chaque réactivation :

```
stabilite ← min(S_max, stabilite × (1 + k × (1 − R)))
dernier_rappel ← maintenant ; rappels ← rappels + 1
```

Le facteur `(1 − R)` traduit l'effet d'espacement : réutiliser un souvenir encore
très frais le renforce peu, le retrouver alors qu'il s'estompait le renforce
beaucoup. Stabilité initiale : `S₀(famille) × (1 + 4 × importance)`, avec une
stabilité de départ propre à chaque famille (§5) : un épisode s'estompe en jours, une
connaissance en mois.

### 3.5 Importance

- **Déclarée** : « retiens ça », « c'est important » → importance haute, et ancre si
  l'utilisateur le demande explicitement.
- Échelle utilisée : basse = 0, moyenne = 0,5, haute = 1.
- **Par nature** : santé, argent, proches, engagements datés → importance moyenne
  par défaut ; routine (repas, petites courses) → basse.
- **Notée à l'écriture** par le modèle local (une note de 1 à 10, comme dans
  *Generative Agents*), seulement quand ni la déclaration ni la nature ne suffisent.

### 3.6 Consolidation nocturne (le « sommeil »)

Lors du traitement de nuit déjà existant :

1. **Résumer** les conversations du jour (existe).
2. **Extraire** les connaissances candidates des épisodes du jour : une connaissance
   déjà connue est renforcée ; une contradiction ouvre une révision (§3.8).
3. **Mettre à jour les habitudes** (§3.7).
4. **Condenser** les épisodes estompés par période : la semaine devient un souvenir
   de synthèse, les semaines d'un mois un souvenir du mois, et ainsi de suite. Chaque
   synthèse est un nouveau souvenir, avec une stabilité plus grande que ses épisodes ;
   les épisodes condensés sont supprimés.
5. **Oublier** ce qui doit l'être (suppression réelle), et purger les transcriptions
   brutes déjà résumées selon la durée de conservation réglée.

Seules les étapes 1, 2 et 4 utilisent un modèle de langue, **local uniquement**. Le
calcul de l'oubli, du renforcement et des habitudes est déterministe : il ne coûte
rien et ne dépend pas de la qualité du modèle.

Organisation : comme les résumés aujourd'hui, le modèle est appelé par le
planificateur de la tête, qui lit et écrit dans le module mémoire par ses outils. Le
module mémoire ne contient aucun appel à un modèle.

Condensation : un épisode appartient à la semaine de sa date. Une semaine n'est
condensée que lorsque **tous** ses épisodes sont estompés ou protégés ; les épisodes
protégés (renvoi à un document) restent tels quels à côté de la synthèse. Un mois
est condensé quand toutes ses semaines le sont, une année quand tous ses mois le sont.
Stabilité de départ : semaine 60 jours, mois 180 jours, année sans oubli. Avant de
supprimer un épisode, la condensation vérifie qu'aucun autre module ne s'y réfère
(les briefings lisent les entrées récentes, jamais celles qui sont estompées).

### 3.7 Habitudes et changement d'habitude

Une habitude est une régularité observée : `(quoi, valeur, contexte)`.

- **Sources** : uniquement des événements **structurés** déjà enregistrés par les
  autres modules, qui disent aussi quand rien ne s'est passé : repas notés par créneau
  (alimentation), événements et tâches (agenda), jours sur site ou en télétravail
  (rythme de travail), gestes faits dans l'application. Pas d'habitude tirée de texte
  libre : pas de modèle de langue dans ce calcul.
- **Quoi et valeur** : vocabulaire fermé, propre à chaque source (« heure du dîner »
  → tranche de 30 minutes ; « mode de travail » → sur site, télétravail, repos).
- **Contexte** : créneau (matin, midi, soir) et type de jour (jour ouvré, week-end, ou
  jour de la semaine quand la source est hebdomadaire).
- **Comptage** : à chaque occurrence du contexte (chaque soir de semaine, par
  exemple), chaque valeur candidate reçoit `x = 1` si elle est observée, `0` sinon :
  `m ← m + a × (x − m)`, avec `m` qui part de 0 ; `a` dépend de la fréquence du
  contexte, pour que l'apprentissage et l'oubli se comptent en semaines : 0,1 pour un
  contexte quotidien, 0,25 pour un contexte hebdomadaire.
- **Naissance** : `m ≥ 0,5` avec au moins 3 observations positives → l'habitude devient
  un souvenir de la famille Habitudes.
- **Force d'une habitude** : c'est `m` lui-même, pas la courbe d'oubli. Active si
  `m ≥ 0,5`, estompée si `m < 0,3`, oubliée sous 0,05 (si elle est déduite).
- **Changement** : une autre valeur dépasse l'actuelle dans le même contexte (dîner
  qui glisse de 19 h à 20 h 30) → elle devient l'habitude active, l'ancienne est
  marquée « ancienne habitude depuis telle date » ; faute d'être observée, son `m`
  décroît et elle s'estompe en quelques semaines.
- **Retour** : si l'ancien comportement réapparaît, l'ancienne habitude, encore
  présente car seulement estompée, est réactivée vite. C'est le « réapprentissage
  rapide » observé chez l'humain.
- Kairos **dit** ce qu'il a remarqué, une fois (« tu sembles maintenant dîner plus
  tard en semaine »), et l'utilisateur confirme ou corrige : la confirmation porte la
  confiance à 1.

### 3.8 Connaissances qui changent

La force (stabilité, importance, ancre, confiance) se porte **par clé** de fait,
sur sa valeur courante ; l'historique des valeurs reste un simple historique, oublié
avec la clé.

- **Valeur déclarée** par l'utilisateur : remplace immédiatement l'ancienne.
- **Valeur déduite** : la clé est choisie dans les clés existantes (la liste est fournie
  au modèle pendant l'extraction ; une clé nouvelle reste sous un préfixe connu). Une
  valeur déduite différente de la valeur courante est mise en attente avec une confiance
  qui suit la même règle que les habitudes (+ à chaque nouvel indice, − sinon) ; elle
  remplace la valeur courante quand sa confiance dépasse celle de l'actuelle.
- Une valeur **déclarée** n'est jamais remplacée par une déduction : une déduction qui la
  contredit produit une question à l'utilisateur, une fois.
- Un souvenir déduit n'est **jamais** une ancre et n'est jamais présenté comme
  certain.

### 3.9 Se souvenir au bon moment (le rappel)

À chaque conversation et pour chaque briefing, les souvenirs proposés au modèle sont
choisis par :

```
score = α × R + β × importance + γ × pertinence
```

Avant chaque réponse, la tête interroge le module mémoire avec le message de
l'utilisateur et ajoute les souvenirs retenus à son contexte : c'est un appel nouveau,
fait par la tête, en plus des outils actuels.

- `pertinence` = rang dans la recherche plein texte existante, ramené entre 0 et 1
  (`1 / (1 + rang)`) ; 0 hors des résultats. Pour un briefing (pas de question),
  `γ = 0`.
- Le noyau et les leçons sont toujours fournis, dans un budget d'environ 800 jetons ;
  au-delà, les plus importants d'abord.
- Les souvenirs estompés ne sont fournis que s'ils sont dans les 3 premiers résultats de
  la recherche : « s'en souvenir parce qu'on en parle ».
- Une recherche par sens, avec des vecteurs calculés localement, pourra compléter la
  recherche plein texte plus tard, si la mémoire du serveur le permet.

### 3.10 Contrôle par l'utilisateur

L'espace Mémoire de l'application existe déjà : voir les faits, corriger, oublier, supprimer.
S'y ajoutent :
- marquer « retiens toujours » (ancre) et le retirer ;
- voir les habitudes apprises, les confirmer ou les rejeter ;
- voir ce qui s'estompe, et le raviver d'un geste.

### 3.11 Données existantes

À la mise en place : chaque fait existant devient une connaissance **déclarée**
(`confiance = 1`, importance moyenne, `dernier_rappel` = sa date de mise à jour), donc
jamais oubliée automatiquement. Les entrées de journal et les conversations deviennent
des épisodes avec leur date. La personnalité reste lue à part par la tête, toujours
chargée : elle fait partie du noyau sans être une ligne de la table.

## 4. Conservation et stockage

- **Base** : SQLite du module mémoire, sur le serveur, comme aujourd'hui. Les champs du
  §3.2 s'ajoutent aux tables existantes ; une table `habitudes` et une table `syntheses`
  sont créées.
- **Oubli réel** : un souvenir oublié est supprimé, avec ses entrées de recherche
  plein texte. Aucune corbeille cachée.
- **Contraintes légales** : les documents administratifs suivent leur durée légale de
  conservation, définie par une table de référence dans le module documents. La mémoire
  ne fait que pointer vers eux.
- **Sensibilité** : les données de santé ou d'argent ne sont conservées que sous forme
  d'agrégat utile (déjà acté) ; l'archive chiffrée hors du serveur pour les données
  durables et sensibles est un lot ultérieur.
- **Modèle de langue** : toujours local pour la mémoire, sans repli sur un modèle hébergé
  ailleurs. Le modèle local du serveur est modeste : il sert à résumer et à extraire,
  jamais à décider de l'oubli.

## 5. Valeurs de départ (à régler à l'usage)

| Paramètre | Valeur | Sens |
|---|---|---|
| `S₀` épisode / habitude / connaissance / synthèse | 3 / 14 / 30 / 60 jours | stabilité de départ par famille |
| `S_max` | 3 650 jours | plafond |
| `k` | 2 | force du renforcement |
| seuils estompé / oublié | 0,5 / 0,05 | sur `R` |
| `D_cond` | 14 jours | délai avant condensation d'un épisode estompé |
| `a` quotidien / hebdomadaire | 0,1 / 0,25 | vitesse d'apprentissage des habitudes |
| naissance d'une habitude | `m ≥ 0,5` et 3 observations | |
| habitude estompée / oubliée | `m < 0,3` / `m < 0,05` | |
| `S₀` synthèse semaine / mois / année | 60 / 180 / sans oubli | |
| budget du noyau | ≈ 800 jetons | |
| `α, β, γ` | 1, 1, 1 | poids du rappel |

Exemples, avec ces valeurs :
- un rendez-vous ordinaire jamais reparlé (épisode, importance basse) : estompé en 2 jours
  environ, condensé dans la synthèse de la semaine un peu plus de deux semaines après ;
- une préférence alimentaire d'importance moyenne (connaissance, stabilité de départ
  90 jours) évoquée une fois par mois : encore active au rappel suivant (`R ≈ 0,7`), et
  chaque rappel la fait grandir d'environ moitié ;
- un dîner qui glisse de 19 h à 20 h 30 en semaine (contexte quotidien, `a = 0,1`) : la
  nouvelle heure devient l'habitude au bout de 7 soirs environ (`m` ≈ 0,52), et
  l'ancienne, qui n'est plus observée, passe sous 0,3 en une douzaine de soirs, soit
  deux à trois semaines ;
- un jour de télétravail qui change (contexte hebdomadaire, `a = 0,25`) : le nouveau jour
  est appris en 3 semaines, l'ancien s'estompe en 4 à 5 semaines ;
- une connaissance déclarée jamais reparlée (un anniversaire, par exemple) : s'estompe,
  n'est plus évoquée sans raison, mais n'est jamais supprimée et revient dès qu'on en parle.

## 6. Réalisation par lots

1. **Force des souvenirs** : champs du §3.2 sur faits et journal, ancres, calcul de `R`,
   renforcement à l'usage, rappel pondéré dans la tête. Rien n'est encore supprimé.
2. **Sommeil** : extraction nocturne, condensation en synthèses, oubli réel.
3. **Habitudes** : comptage, naissance, changement, retour ; annonce et confirmation.
4. **Contrôle** : ancres, habitudes et souvenirs estompés dans l'espace Mémoire.
5. **Plus tard** : recherche par sens (vecteurs locaux), archive chiffrée externe, table
   des durées légales.

Chaque lot est utile seul et mesurable : nombre de souvenirs actifs, taille de la base,
habitudes détectées et confirmées.

## 7. Risques et parades

| Risque | Parade |
|---|---|
| Faux souvenirs (le modèle invente pendant l'extraction) | provenance et confiance sur tout souvenir déduit ; jamais d'ancre ; affichage « je crois que » |
| Oublier quelque chose d'utile | estompé n'est pas supprimé ; la suppression n'arrive qu'à `R < 0,05` ; ancres et contraintes légales protègent le reste |
| Une habitude détectée à tort | annonce une seule fois, puis confirmation ou rejet ; seuils prudents |
| Lenteur du modèle local de nuit | seules trois étapes l'utilisent ; ordre de priorité si la nuit ne suffit pas |
| Croissance de la base | condensation et oubli réels ; mesure de la taille à chaque lot |
| Données de tiers | proches limités à ce qui est déclaré ; « oublie » efface immédiatement |
