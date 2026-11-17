package app.kairos.mobile.ui.politique.jeux

import app.kairos.mobile.ui.politique.jeux.jeu.AvantApres
import app.kairos.mobile.ui.politique.jeux.jeu.LeFinanceur
import app.kairos.mobile.ui.politique.jeux.jeu.LesLiens
import app.kairos.mobile.ui.politique.jeux.jeu.LeProcede
import app.kairos.mobile.ui.politique.jeux.jeu.QuiLaDit
import app.kairos.mobile.ui.politique.jeux.jeu.TrenteSecondes
import app.kairos.mobile.ui.politique.jeux.jeu.Independant
import app.kairos.mobile.ui.politique.jeux.jeu.LeJusteCout
import app.kairos.mobile.ui.politique.jeux.jeu.QuelleGravite
import app.kairos.mobile.ui.politique.jeux.jeu.LaVille
import app.kairos.mobile.ui.politique.jeux.jeu.LeVote

/**
 * Jeux implémentés, par slug (clé de `JEUX` dans `jeux.json`) : une ligne par jeu, chacune remplacée par le jeu
 * dédié quand il arrive. Un slug publié absent d'ici affiche « Bientôt ».
 */
val JEUX_REGISTRE: Map<String, PolJeu<*>> = listOf<PolJeu<*>>(
    QuiLaDit(),
    LeProcede(),
    TrenteSecondes(),
    LesLiens(),
    LeFinanceur(),
    QuelleGravite(),
    LeJusteCout(),
    Independant(),
    LeVote(),
    AvantApres(),
    LaVille(),
).associateBy { it.slug }
