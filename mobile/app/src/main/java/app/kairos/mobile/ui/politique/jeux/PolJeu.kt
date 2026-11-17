package app.kairos.mobile.ui.politique.jeux

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * État d'une manche, observable, remis à zéro à la manche suivante. La coque l'écrit ; le jeu le lit.
 * [out] : clés des mauvaises réponses dans l'ordre ; [react] : incrémenté à chaque erreur (clé de `LaunchedEffect`
 * pour animer `out.last()`).
 */
@Stable
class MancheEtat(val manche: Manche) {
    var page by mutableStateOf(Page.Question)
    var solved by mutableStateOf(false)
        internal set
    var err by mutableIntStateOf(0)
        internal set
    var react by mutableIntStateOf(0)
        internal set
    val out = mutableStateListOf<String>()

    /**
     * État propre au jeu pour cette manche, nul au départ, perdu à la manche suivante. Champ simple (non observé) :
     * y ranger un état observable (`mutableIntStateOf`…), créé une fois, par exemple dans `remember(st)`.
     */
    var jeu: Any? = null
}

/** Ce que la coque offre à la manette d'un jeu. */
interface Coque {
    /** Bonne réponse, une seule fois : la carte est comptée, la fenêtre glisse vers la réponse. */
    fun juste()

    /** Mauvaise réponse : [cle] rejoint `out`, [msg] est annoncé suivi de « Réessayez. ». */
    fun faux(cle: String, msg: String = "Ce n'est pas ça.")

    /** Annonce au lecteur d'écran. */
    fun annoncer(msg: String)

    /** Retour haptique court (appui, cran). */
    fun cran()

    /** Animations système coupées : rien ne bouge, durées à zéro. */
    val reduit: Boolean

    /** `params` du jeu (constantes communes à ses manches), à décoder par le jeu. */
    val params: JsonObject

    /** Données de l'espace (évaluateurs, identités…) pour les jeux qui s'y réfèrent. */
    val data: PolData
}

/**
 * Contrat d'un jeu : un fichier par jeu, enregistré d'une ligne dans [JEUX_REGISTRE].
 * [D] : forme du champ `d` de ses manches, décodée de façon tolérante (clés inconnues ignorées) ; une manche dont `d`
 * ne se décode pas, ou que [valide] refuse, est écartée du vivier.
 * La coque fait le reste : carte, pager, piste question / réponse / suivant, réponse (titre, verdict, texte, sources),
 * progression, parties, annonces.
 */
abstract class PolJeu<D : Any>(val slug: String, private val serializer: KSerializer<D>) {

    /** `d` décodé et validé avec les [params] du jeu ; null : manche écartée. */
    fun decoder(d: JsonObject, params: JsonObject = JsonObject(emptyMap())): D? =
        runCatching { PolJson.decodeFromJsonElement(serializer, d) }.getOrNull()?.takeIf { valide(it, params) }

    /** Manche jouable avec ces données et ces `params`. */
    open fun valide(d: D, params: JsonObject): Boolean = true

    /** Grand pictogramme « citation » translucide dans la fenêtre question. */
    open val glyphe: Boolean = false

    /** Question, dans la fenêtre (fond blanc). Par défaut : sur-titre et question de la manche. [c] : données, `params`, `reduit`. */
    @Composable
    open fun Fenetre(m: Manche, d: D, st: MancheEtat, c: Coque) = FenetreQuestion(m, citation = glyphe)

    /** Manette : l'objet qu'on manipule, sous la fenêtre (≤ 260 dp de haut). Reste affichée après la bonne réponse, sans interaction. */
    @Composable
    abstract fun Manette(m: Manche, d: D, st: MancheEtat, c: Coque)

    /**
     * Étiquette neutre de la réponse quand la manche n'a pas de verdict (`reponse.verdict` null) : famille du procédé,
     * cran, saisie du joueur… ; null : aucune.
     */
    open fun etiquette(m: Manche, d: D, st: MancheEtat, params: JsonObject): String? = null

    /** Corps de réponse en plus du texte publié (rarement utile). */
    @Composable
    open fun ColumnScope.ReponseEnPlus(m: Manche, d: D, st: MancheEtat) {}

    // Ponts sans type, pour la coque.
    internal fun ok(m: Manche, params: JsonObject): Boolean = decoder(m.d, params) != null

    @Composable
    internal fun FenetreBrute(m: Manche, d: Any, st: MancheEtat, c: Coque) = @Suppress("UNCHECKED_CAST") Fenetre(m, d as D, st, c)

    @Composable
    internal fun ManetteBrute(m: Manche, d: Any, st: MancheEtat, c: Coque) = @Suppress("UNCHECKED_CAST") Manette(m, d as D, st, c)

    internal fun etiquetteBrute(m: Manche, d: Any, st: MancheEtat, params: JsonObject): String? =
        @Suppress("UNCHECKED_CAST") etiquette(m, d as D, st, params)

    @Composable
    internal fun ReponseBrute(scope: ColumnScope, m: Manche, d: Any, st: MancheEtat) = with(scope) { @Suppress("UNCHECKED_CAST") ReponseEnPlus(m, d as D, st) }
}
