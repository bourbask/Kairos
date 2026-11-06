# Alimentation — plan de la semaine et carnet de recettes

## Intention

Chaque semaine, à la demande (« planifie la semaine »), la tête compose les
repas à partir du stock ; le module en fait un plan cohérent (restes, boîtes
des jours de bureau) et l'écrit dans un calendrier « Repas », une recette pas à
pas par plat cuisiné. Le moteur choisit les plats ; il ne réécrit pas les
recettes : elles vivent dans un carnet, écrites une fois, corrigeables,
réutilisées.

## Objectifs

1. Carnet de recettes : titre, portions, durées de préparation et de
   cuisson, ingrédients pesés (nom, quantité, unité), étapes ordonnées.
   Création, correction, lecture. Description d'événement en trois
   sections : INFOS (durées, portions et restes prévus), INGRÉDIENTS (liste
   pesée, pour vérifier le stock d'un coup d'œil), ÉTAPES (numérotées).
2. `meal_plan_set` : reçoit les plats **cuisinés** (jour, créneau, plat,
   portions) sur une période (7 jours par défaut, 14 max) ; le module :
   - remplit les créneaux suivants avec les restes (portions − 1) ;
   - transforme en **boîte** un reste tombant un midi de bureau, et ajoute à
     l'événement de la veille au soir « mettre une portion en boîte » ;
   - signale : plat sans recette au carnet, ingrédient de recette absent du
     stock, créneaux sans repas, restes qui débordent de la période, plat
     cuisiné un midi de bureau ;
   - enregistre le plan et l'écrit dans le calendrier (la période est
     remplacée : un créneau vide supprime l'événement existant).
3. `meal_plan` : lecture du plan à partir d'un jour.
4. Jours de bureau : préférence du module `bureau` (« lundi, jeudi »), lue à
   chaque plan ; aucun lien avec la tête.

## Non-objectifs

- Pas de génération automatique nocturne : le plan se fait à la demande.
- Pas de déduction du stock à la planification (le stock baisse quand le repas
  est noté, comme aujourd'hui).
- Pas de liste de courses.

## Contraintes

- Calendrier CalDAV configuré par l'environnement :
  `ALIMENTATION_CALDAV_URL` (collection), `ALIMENTATION_CALDAV_USER`,
  `ALIMENTATION_CALDAV_PASS`. Absents : plan enregistré, calendrier ignoré, dit
  dans la réponse. Identifiants jamais journalisés.
- Événements : UID `kairos-repas-AAAAMMJJ-midi|soir` (stables : republier
  remplace). Midi 12:15 (45 min), soir 19:00 (60 min), heure locale du module.
- Titres : « Midi : … » / « Soir : … » ; reste « … (reste) », boîte « … (boîte) ».
- Repo public : aucune donnée personnelle dans le code ni les tests ; le carnet
  initial est chargé par les outils, pas versionné.

## Laissé au constructeur

Format exact des réponses texte, dans le style des outils existants.
