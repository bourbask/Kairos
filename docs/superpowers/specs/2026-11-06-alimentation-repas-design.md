# Alimentation — qualification des produits et propositions de repas

## Intention

À « on mange quoi ce soir ? », la tête doit proposer un plat cohérent tiré du
stock, pas réciter la liste des produits à finir. Aujourd'hui le module ne sait
pas ce qu'est un produit (un pain au chocolat, une sauce soja et un poulet sont
indistincts) et n'a aucun outil orienté repas : le moteur prend `use_first`, qui
mélange viennoiseries, condiments vieux de deux ans et produits frais, et rate
le poulet hors de l'horizon de 4 jours.

Principe : **le module trie, le moteur compose.** Le filtrage par repas et
contexte est déterministe, dans le code et testé ; le moteur (petit modèle
local) ne reçoit que des candidats pertinents et assemble un plat.

## Objectifs

1. Chaque produit porte une **nature** et des **attributs** dérivés.
2. Un outil `cook_options` renvoie les ingrédients utilisables pour un repas
   donné, dans un contexte donné, triés par urgence.
3. `use_first` distingue l'urgent (DLC) du « vieux, à vérifier » (DDM).
4. Les descriptions d'outils décrivent le process attendu et le format de
   réponse, pour que le moteur réponde court et utile.
5. La tête indique au moteur le rythme de travail du jour et du lendemain.

## Non-objectifs

- Pas de base de recettes : le moteur compose. À reconsidérer si ses
  propositions déçoivent à l'usage.
- Pas de lecture d'agenda (sortie, déplacement) : le contexte vient du message
  ou du rythme de travail. Agenda dans un second temps.
- Pas de planification de semaine / préparation à l'avance automatique.
- Pas de calcul nutritionnel.

## Anti-objectifs

- Un dîner proposé à base de viennoiseries, de condiments ou de boissons.
- Un produit DDM dépassé présenté comme urgent.
- Une réponse qui liste l'inventaire au lieu de proposer.
- Une catégorisation qui exige une saisie manuelle pour chaque produit.

## 1. Nature et attributs

Natures (valeurs stockées, `snake_case`) :

| Nature | Exemples |
|---|---|
| `proteine` | viande, poisson, œufs, jambon, tofu, légumineuses |
| `legume` | carottes, courgettes, potimarron, épinards |
| `fruit` | oranges, pommes, compote nature exclue (→ `sucre`) |
| `feculent` | pâtes, riz, pomme de terre, patate douce, pain, semoule, lentilles sèches → `proteine` |
| `laitier` | lait, yaourt, fromage, beurre |
| `sucre` | viennoiseries, biscuits, compote, confiture, miel, chocolat, céréales sucrées |
| `condiment` | sauces, vinaigres, huiles, moutarde, bouillons, pesto, olivade |
| `epice` | épices, herbes, sel, poivre |
| `boisson` | jus, sodas, café, thé, sirops, alcools |
| `base` | farine, sucre en poudre, levure, fécule, chapelure, lait de coco |
| `plat` | plat préparé, reste |

Attributs dérivés de la nature et de mots-clés, jamais saisis :

- `a_cuire` : demande une cuisson (viande crue, poisson cru, pâtes, riz,
  légumes racines…). Faux pour `plat` (réchauffer suffit), fromage, fruit,
  jambon, pain.
- `froid_ok` : se mange froid (fruit, fromage, jambon, pain, salade, `plat`
  marqué froid, œufs durs non : déduits par mots-clés).
- `transportable` : supporte quelques heures hors frigo en sac (pain, fruit
  entier, fromage à pâte dure, saucisson, biscuits, `plat` en boîte).

Sources, de la plus forte à la plus faible :

1. Correction explicite via `product_set` (stockée, jamais écrasée).
2. Catégorie Open Food Facts quand le code-barres est connu (table de
   correspondance des catégories OFF de premier niveau vers une nature).
3. Règles par mots-clés sur le nom replié, même mécanique que `shelf.rs`
   (première règle qui correspond).
4. À défaut : nature inconnue. Le produit n'est proposé dans aucun repas et
   apparaît dans `product_set` sans argument (liste « à qualifier »).

Stockage : seule la correction manuelle est stockée (colonne `nature` sur
`products`, migration additive comme `expiry_kind`). La nature est **calculée à
la lecture** : manuel, sinon catégorie OFF *reconnue* (déjà stockée dans
`category`), sinon règles. Un tag OFF non reconnu ne masque pas une règle. Une
règle corrigée s'applique donc à tout le stock sans migration ni
requalification au démarrage ; les attributs sont calculés de même.

Correctif inclus : l'estimation de date prend « farine pain… » pour du pain et
les conserves de tomates pour du frais. Les règles de nature et de date
partagent le repli de nom : une farine est `base`, une conserve (« pelées »,
« conserve », « boîte », « concentré ») n'est pas du frais.

## 2. Règles par repas et contexte

Créneaux : `petit_dejeuner`, `midi`, `gouter`, `soir` (valeurs déjà utilisées
par `meal_log`).

Contextes : `maison`, `boulot`, `rando`.

| Créneau | Contexte | Natures candidates | Contrainte |
|---|---|---|---|
| petit_dejeuner | tous | laitier, sucre, fruit, feculent (pain) | pas `a_cuire` |
| gouter | tous (rando : `transportable`) | fruit, sucre, laitier | pas `a_cuire` |
| midi | maison | proteine, legume, feculent, plat, laitier (fromage) | aucune |
| midi | boulot | plat ; sinon proteine, legume, feculent à cuisiner **la veille** | réponse = « à préparer ce soir » |
| midi | rando | `froid_ok` ET `transportable` | aucune cuisson le jour même |
| soir | maison | proteine, legume, plat, feculent (petite part), laitier | léger : soupe et légumes favorisés |
| soir | boulot / rando | comme maison | le soir on est rentré |

Toujours exclus d'un repas principal : `condiment`, `epice`, `boisson`, `base`
— ils figurent seulement en ligne « dans le placard aussi » (noms seuls, sans
dates), pour que le moteur sache qu'il peut assaisonner.

Contexte absent de l'appel : `maison`. C'est la tête qui renseigne le moteur
(§5) ; le module ne connaît pas le rythme de travail.

## 3. Outils

### `cook_options(slot, context?, day?)`

`day` (AAAA-MM-JJ, aujourd'hui par défaut ; le moteur convertit « demain »
grâce à la date de la consigne système) sert de date de référence : un produit
dont la DLC tombe avant ce jour est exclu, l'urgence se calcule depuis ce jour.

Renvoie, pour le créneau et le contexte :

1. Restes et plats préparés (`plat`) d'abord.
2. Puis les candidats groupés par nature, triés par urgence : DLC la plus
   proche d'abord ; DDM non urgente ; DDM dépassée signalée « à vérifier »
   sans priorité.
3. Quantité restante, emplacement, marque ⚠ pour une DLC ≤ 2 jours.
4. Ligne « dans le placard aussi » : condiments et épices disponibles, noms seuls.
5. Préférences (`prefs`) jointes en une ligne (« à éviter : … ») si présentes.

Texte compact (une ligne par produit). Plafond : 8 produits par nature.

Description de l'outil (le moteur la lit) : quand l'appeler (toute demande de
repas, menu, « on mange quoi »), quel contexte passer (message, sinon rythme
du jour indiqué en consigne système), et le format de réponse attendu (§4).

### `use_first(days?)` — modifié

Deux sections : « À consommer » (DLC dans l'horizon, ou dépassée de ≤ 2 j) et
« Vieux, à vérifier » (DDM dépassées, plafonné à 5, les plus anciennes
d'abord). Une DDM dans l'horizon n'est pas urgente : elle n'apparaît pas.

### `product_set(name, nature)` — nouveau

Fixe la nature d'un produit (source `manuel`). Sans argument : liste les
produits de nature inconnue, et ceux en stock dont la nature vient d'une règle
(pour vérification ponctuelle, plafonné à 20).

### `brief_section` — inchangé dans son principe

Utilise la section « À consommer » de `use_first` (2 jours), plus de DDM.

## 4. Process et format de réponse attendus

Décrits dans la description de `cook_options` :

- « on mange quoi ce soir ? » → `cook_options(soir, maison)` → **2 plats au
  plus**.
- « demain midi au boulot ? » → `cook_options(midi, boulot, demain)` → un plat
  **à préparer ce soir**, en une portion à emporter.
- « sortie samedi » → `cook_options(midi, rando, samedi)` → **liste à
  emporter**.
- Petit-déjeuner / goûter → 2 suggestions simples.

Format : pour chaque proposition, nom du plat ; ingrédients du stock utilisés
(⚠ sur les urgents) ; temps approximatif ; ce qui manque, s'il manque quelque
chose. **6 lignes maximum, jamais la liste de l'inventaire.**

## 5. Tête : rythme du jour dans la consigne système

La consigne système de la conversation gagne une ligne : « Rythme : aujourd'hui
<sur site|télétravail|repos>, demain <…> » d'après `WorkRhythm` (et les
modifications `set_work_day` déjà gérées). Aucun lien entre la tête et le
module : c'est le moteur qui traduit « sur site » en `context = boulot` pour
`midi`, comme l'indique la description de `cook_options`.

## 6. Tests de référence

Sur un inventaire de test représentatif (une viande à DLC J+7, des légumes
frais, une viennoiserie, un condiment à DDM J+4, des condiments à DDM dépassées,
une boisson ouverte) :

- `cook_options(soir, maison)` contient la viande et des légumes ; ne contient
  ni viennoiserie, ni condiment, ni boisson en tête.
- `cook_options(petit_dejeuner)` contient la viennoiserie, pas la viande.
- `cook_options(midi, rando)` ne contient rien qui soit `a_cuire`.
- `use_first(4)` : condiment à DDM proche absent des urgents ; condiments
  dépassés depuis longtemps sous « Vieux, à vérifier », plafonnés à 5.
- Règles de nature : un produit de base (farine), un légume en conserve (pas
  d'estimation frais), une viennoiserie (`sucre`), une viande (`proteine`)
  reçoivent la nature attendue.
- Tête : consigne système contient « aujourd'hui sur site » un jour `OnSite`.

## Livraison

Une PR, dans l'ordre des commits : nature + règles + migration + `product_set`
+ correctif dates ; `cook_options` + `use_first` ; consigne de la tête.
Déploiement sur le serveur, puis requalification automatique du stock existant
au premier démarrage.

## Choix tranchés après relecture

- `plat` reconnu par mots-clés du nom (« reste », « plat », « lasagne »,
  « gratin »…). `froid_ok` et `transportable` faux par défaut, vrais sur liste
  explicite de natures et de mots-clés.
- Lait et autres `laitier` : pas d'exclusion, tri après `proteine` et `legume`
  pour midi et soir.
- Rythme : `OnSite` → « sur site », `Remote` → « télétravail », `Off` ou jour
  non configuré → « repos ». Les modifications de `set_work_day` s'appliquent.
- `brief_section` : même règle que la section « À consommer » de `use_first`.

## Laissé au constructeur

- Liste exacte des mots-clés par nature (partir des produits de l'inventaire
  de test et des règles de `shelf.rs`).
- Correspondance des catégories OFF.
- Libellés exacts des sections de sortie, dans le style des outils existants.
