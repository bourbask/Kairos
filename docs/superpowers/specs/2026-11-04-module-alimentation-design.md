# Module alimentation

## Intention

Numériser ce qu'il y a à manger dans le logement et ce qui est réellement
mangé, pour :

- consommer dans le bon ordre et limiter le gaspillage ;
- proposer des recettes avec ce qui reste ;
- préparer la préparation des repas à l'avance (cuisiner une fois, manger varié) ;
- faciliter les courses en ligne ;
- à terme, orienter achats, recettes et portions selon la santé (poids,
  sommeil, sport, charge de travail) et des objectifs.

## Principes

- **Un module, hébergé avec les modules toujours joignables** (pas sur le poste
  de travail).
- **La tête converse, le module tient les comptes.** Recettes, menus et
  arbitrages se discutent ; le module garde un inventaire exact, qui sert de
  base à chaque proposition.
- **Inventaire juste à moindre effort** : les entrées viennent des commandes ;
  les sorties, des repas notés (ingrédients déduits), d'un mot (« j'ai fini les
  œufs »), et d'une correction quand le réel diverge. Aucune saisie n'est
  obligatoire pour que le reste fonctionne.
- **Unités cohérentes** : chaque produit est compté en masse, en volume ou en
  pièces ; les saisies (kg, cl, douzaine…) sont converties.
- **Consommation ordonnée** : on puise d'abord dans ce qui est entamé, puis dans
  ce qui périme le plus tôt.
- **Écritures groupées en deux temps** (commande, réception) : aperçu, puis
  confirmation.
- **Données de santé** (étape 4) : sensibles, traitées par le modèle
  auto-hébergé uniquement.

## Étape 1 — socle (cette PR)

- Stock par emplacement (frigo, congélateur, placard, corbeille à fruits et
  légumes), lots avec date limite, mouvements tracés (achat, repas,
  consommation, correction).
- Commande de courses enregistrée à la passation, réceptionnée au retrait
  (ruptures exclues) : c'est seulement là qu'elle entre en stock.
- Repas notés (moment, lieu, plat, ingrédients déduits) et bilan d'habitudes.
- Préférences durables (à éviter, équipement, budget, créneau de batch
  cooking, objectifs).
- Fiche produit par code-barres (base ouverte Open Food Facts) : nom, marque,
  contenance, Nutri-Score, degré de transformation (NOVA).
- Outils : `order_record`, `orders`, `order_receive`, `stock_add`, `stock_use`,
  `stock_set`, `stock_move`, `stock`, `use_first`, `meal_log`, `meals`,
  `pref_set`, `prefs`, `product_lookup`, `brief_section`.

## Étapes suivantes

2. **Recettes et préparation à l'avance** : recettes favorites et notées, plan de préparation
   du week-end à partir du stock et des dates, liste de courses de ce qui
   manque, suivi du budget (lien avec le module finances).
3. **Courses** : de la liste au panier du magasin en ligne (navigateur piloté sur le
   poste ; la validation et le paiement restent à l'utilisateur), scan de
   codes-barres depuis le téléphone.
4. **Santé** : poids, sommeil, activité (export de l'application santé du
   téléphone), points réguliers sur la forme et la charge de travail (agenda) ;
   portions, fréquence et choix d'aliments orientés par les objectifs, y compris
   la limitation de certains contaminants alimentaires.

## Import des bons de commande

Saisir une commande ligne à ligne est le point de friction. Le bon de commande
en PDF que le magasin met à disposition contient tout : référence, date de
retrait, total, et une ligne par article rangée sous son rayon.

- **`order_import`** (module, VPS) lit ce texte (`pdftotext -layout`) : quantité
  déduite de la désignation (500g, 1.5kg, 6x1L, x12, 1p, poids de viande en fin
  de libellé), emplacement déduit du rayon (surgelés → congélateur, frais →
  frigo, épicerie → placard, avec quelques exceptions par mot-clé), articles non
  alimentaires écartés. Une référence déjà enregistrée est refusée : l'import
  peut être rejoué sans doublon.
- **`kairos-alimentation-depot`** (poste de travail) surveille un dossier dédié,
  réservé aux bons de commande : il ne voit que ce qu'on y dépose, jamais les
  autres fichiers personnels. Chaque nouveau PDF est lu sur place ; seul un
  texte reconnu comme bon de commande (numéro, date de retrait, total, rayons)
  est envoyé au module, confirmé d'office. Un autre PDF déposé par erreur ne
  quitte pas la machine. Sondage périodique plutôt que
  notifications du système de fichiers, peu fiables sur un dossier monté
  dans un conteneur.

Écarté : un favori (bookmarklet) qui enverrait la page du magasin au module. Une
page HTTPS ne peut pas appeler un service en HTTP simple (contenu mixte bloqué
par les navigateurs), et le bon de commande est un PDF, pas une page. Le dossier
surveillé couvre le même besoin avec un seul geste : enregistrer le PDF.

Plus tard : récupération entièrement automatique (mail de confirmation, puis
navigateur piloté sur le poste), avec l'étape panier.

## Dates de conservation

La date imprimée dépend du lot fabriqué : ni le bon de commande ni les bases
produits ne la donnent. Plutôt que de la demander produit par produit :

- **Tout lot a une date.** Celle de l'emballage quand on la donne, sinon une
  durée type selon le produit et l'emplacement (`shelf.rs` : viande fraîche
  3 j, yaourts 21 j, courges 60 j, surgelés 180 j, placard 365 j…), marquée
  « estimée ». La nature de la date est gardée : DLC (au-delà, on jette) ou
  DDM (« de préférence avant » : au-delà, on vérifie).
- **Une seule règle de priorité pour tout le stock** : un produit remonte
  quand sa date entre dans l'horizon demandé, ou quand il ne lui reste qu'un
  sixième de sa durée de conservation. Une conserve d'un an remonte deux mois
  avant sa date : les oublis au fond du placard ne finissent plus à la
  poubelle.
- **Événements de vie** : la première ouverture raccourcit la date des produits
  concernés (lait, crème, lait de coco, jus, sauces, charcuterie) ; congeler
  sans date donnée l'estime à 3 mois, décongeler la ramène à 2 jours au plus.
- Les vraies DLC comptent surtout pour le frais animal : une photo des
  étiquettes au retrait suffit à remplacer les estimations.
