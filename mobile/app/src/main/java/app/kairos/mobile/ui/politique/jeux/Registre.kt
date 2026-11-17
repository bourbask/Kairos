package app.kairos.mobile.ui.politique.jeux

import app.kairos.mobile.ui.politique.jeux.jeu.Exemple
import app.kairos.mobile.ui.politique.jeux.jeu.LeFinanceur
import app.kairos.mobile.ui.politique.jeux.jeu.LesLiens

/**
 * Jeux implémentés, par slug (clé de `JEUX` dans `jeux.json`) : une ligne par jeu, chacune remplacée par le jeu
 * dédié quand il arrive. Un slug publié absent d'ici affiche « Bientôt ».
 */
val JEUX_REGISTRE: Map<String, PolJeu<*>> = listOf<PolJeu<*>>(
    Exemple("qui-l-a-dit"),
    Exemple("le-procede", citation = true),
    Exemple("trente-secondes"),
    Exemple("la-ville"),
    LesLiens(),
    LeFinanceur(),
).associateBy { it.slug }
