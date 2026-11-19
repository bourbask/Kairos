package app.kairos.mobile.ui.politique.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import app.kairos.mobile.ui.politique.jeux.kit.cell
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Images clés du retournement : avancement (0 à 1) et durée de l'image en ms. Aucun rebond : la dernière image pose la feuille. */
val TURN_KEYS: List<Pair<Double, Int>> = listOf(.05 to 110, .17 to 70, .32 to 55, .45 to 45, .55 to 45, .68 to 55, .83 to 70, 1.0 to 90)

/** Face de la feuille : réponse, ou faits et sources. */
enum class TurnFace { Res, Det }

/** Encres du pictogramme de la feuille en mouvement. */
enum class TurnInk { Paper, Edge, Line, Acc, Box, Ear, Shadow }

/** Rangée de [w] cellules en ([x], [y]) ; [light] assombrit (pli, profil). */
class TurnRun(val x: Int, val y: Int, val w: Int, val ink: TurnInk, val light: Float)

/** Image du retournement : canevas [cols] × [rows] cellules de 3 dp, la feuille débordant de [TURN_PAD] dp ; ombre au sol d'opacité [shadowAlpha]. */
class TurnFrame(val cols: Int, val rows: Int, val shadow: TurnRun, val shadowAlpha: Float, val runs: List<TurnRun>)

const val TURN_PAD = 48

/** Pictogramme d'une face au point ([u], [v]) en fractions, pour une feuille de hauteur [h] dp. */
typealias TurnMotif = (face: TurnFace, u: Double, v: Double, h: Double) -> TurnInk

/** Pictogramme du tract. */
val TRACT_MOTIF: TurnMotif = { k, u, v, h ->
    val row = floor(v * h / 12).toInt()
    val inRow = (v * h) % 12 < 3
    if (k == TurnFace.Res) {
        if (v > .05 && v < .12 && u > .07 && u < .42) TurnInk.Acc
        else if (v > .87 && v < .95 && u > .6 && u < .93) TurnInk.Box
        else if (v > .17 && v < .66 && inRow && u > .07 && u < .93 - ((row * 37) % 5) * .08) TurnInk.Line
        else TurnInk.Paper
    } else if (v > .04 && v < .07 && u > .07 && u < .35) TurnInk.Line
    else if ((v > .73 && v < .8 || v > .83 && v < .9) && u > .07 && u < .93) TurnInk.Box
    else if (v > .11 && v < .68 && inRow && u > .07 && u < .93 - ((row * 53) % 5) * .08) TurnInk.Line
    else TurnInk.Paper
}

/** Pictogramme de la carte d'affirmation : recto (verdict, phrase, jauge, faits, sources) et verso (justification, confiance). */
val CARD_MOTIF: TurnMotif = { k, u, v, h ->
    val row = floor(v * h / 12).toInt()
    val inRow = (v * h) % 12 < 3
    val wide = u > .07 && u < .93
    val rowLine = { v0: Double, v1: Double, salt: Int -> v > v0 && v < v1 && inRow && u > .07 && u < .93 - ((row * salt) % 5) * .08 }
    if (k == TurnFace.Res) {
        if (v > .04 && v < .07 && u > .07 && u < .45) TurnInk.Line
        else if (v > .04 && v < .08 && u > .62 && u < .93) TurnInk.Acc
        else if (v > .12 && v < .20 && inRow && row - floor(.12 * h / 12).toInt() < 2 && wide) TurnInk.Line
        else if (v > .24 && v < .27 && wide) TurnInk.Box
        else if (rowLine(.32, .82, 37)) TurnInk.Line
        else if (v > .88 && v < .93 && (u > .07 && u < .40 || u > .43 && u < .70)) TurnInk.Box
        else TurnInk.Paper
    } else {
        if (v > .04 && v < .07 && u > .07 && u < .35) TurnInk.Line
        else if (v > .12 && v < .18 && inRow && wide) TurnInk.Line
        else if (rowLine(.22, .55, 53)) TurnInk.Line
        else if (v > .60 && v < .80 && wide) TurnInk.Box
        else if (v > .88 && v < .93 && (u > .07 && u < .35 || u > .60 && u < .93)) TurnInk.Box
        else TurnInk.Paper
    }
}

/**
 * Retournement à l'avancement [t] d'une feuille de [w] × [h] dp, vue de face, en bande souple : une colonne par cellule de
 * 3 dp, chacune avec son angle ; le bord droit mène, le gauche suit avec un retard de 0,6, ce qui courbe le papier. La
 * colonne la plus proche grandit (perspective), le pli s'assombrit, la feuille se soulève et son ombre au sol pâlit.
 * Les faces sont dessinées en pictogramme ; le coin corné ([ear] cellules, 0 pour le supprimer) reste visible tout du long.
 */
fun turnFrame(t: Double, w: Int, h: Int, from: TurnFace, ear: Int = 27, motif: TurnMotif = TRACT_MOTIF): TurnFrame {
    val c = 3.0
    val cols = ceil((w + 2.0 * TURN_PAD) / c).toInt()
    val rows = ceil((h + 2.0 * TURN_PAD) / c).toInt()
    val to = if (from == TurnFace.Res) TurnFace.Det else TurnFace.Res
    val nc = ceil(w / c).toInt()
    val lag = .6
    fun ease(v: Double) = if (v < .5) 2 * v * v else 1 - (-2 * v + 2).let { it * it } / 2
    val th = DoubleArray(nc) { i -> PI * ease(((t * (1 + lag)) - lag * (1 - (i + .5) / nc)).coerceIn(0.0, 1.0)) }
    val xs = DoubleArray(nc + 1)
    val zs = DoubleArray(nc + 1)
    for (i in 0 until nc) { xs[i + 1] = xs[i] + cos(th[i]) * c; zs[i + 1] = zs[i] + sin(th[i]) * c }
    val mid = (xs.min() + xs.max()) / 2
    val off = TURN_PAD + w / 2.0 - mid
    val lift = floor(sin(PI * t) * 10 / c + 0.5).toInt() * c
    val sx0 = xs.min() + off
    val sx1 = xs.max() + off
    val shadow = TurnRun(floor(sx0 / c).toInt() + 1, floor((TURN_PAD + h) / c).toInt(), max(1, floor((sx1 - sx0) / c).toInt() - 1), TurnInk.Shadow, 1f)
    val runs = ArrayList<TurnRun>()
    for (i in (0 until nc).sortedBy { zs[it] + zs[it + 1] }) {
        val co = cos(th[i])
        val z = (zs[i] + zs[i + 1]) / 2
        val hh = h * (1 + z / (w * 2.0))
        val top = TURN_PAD + h / 2.0 - hh / 2 - lift
        val bend = abs((th[min(nc - 1, i + 1)] - th[max(0, i - 1)]) / 2)
        val lt = max(.45, .6 + .4 * abs(co) - bend * 5).toFloat()
        val front = co >= 0
        val k = if (front) from else to
        val u = if (front) (i + .5) / nc else 1 - (i + .5) / nc
        val a = floor((min(xs[i], xs[i + 1]) + off) / c).toInt()
        val b = max(a, floor((max(xs[i], xs[i + 1]) + off) / c).toInt())
        val c0 = floor(top / c).toInt()
        val c1 = ceil((top + hh) / c).toInt()
        for (cy in c0 until c1) {
            val v = (cy * c - top) / hh
            val ex = (1 - u) * w
            val ey = v * hh
            if (ex + ey < ear) continue
            val ink = when {
                cy == c0 || cy == c1 - 1 || i == 0 || i == nc - 1 -> TurnInk.Edge
                ex < ear && ey < ear -> TurnInk.Ear
                else -> motif(k, u, v, h.toDouble())
            }
            runs += TurnRun(a, cy, b - a + 1, ink, lt)
        }
    }
    return TurnFrame(cols, rows, shadow, (.16 - lift / 200).toFloat(), runs)
}

/** Retournement image par image : la feuille en bande souple, dessinée en pictogramme, déborde du cadre ; [w] et [h] en cellules de 3 dp, [s] la taille d'une cellule en px. */
@Composable
fun TurnCanvas(t: Double, w: Int, h: Int, s: Int, from: TurnFace, ink: (TurnInk) -> Color, ear: Int = 27, motif: TurnMotif = TRACT_MOTIF) {
    val frame = remember(t, w, h, from, ear, motif) { turnFrame(t, w * 3, h * 3, from, ear, motif) }
    val pad = TURN_PAD / 3
    Canvas(
        Modifier.layout { m, _ ->
            val p = m.measure(Constraints.fixed(frame.cols * s, frame.rows * s))
            layout(0, 0) { p.place(-pad * s, -pad * s) }
        }.clearAndSetSemantics { },
    ) {
        val sh = frame.shadow
        cell(sh.x, sh.y, ink(TurnInk.Shadow).copy(alpha = frame.shadowAlpha.coerceIn(0f, 1f)), s, w = sh.w)
        frame.runs.forEach { r ->
            val base = ink(r.ink)
            cell(r.x, r.y, Color(base.red * r.light, base.green * r.light, base.blue * r.light), s, w = r.w)
        }
    }
}

/**
 * Recto et verso dans la même case : la hauteur est celle de la plus haute face, seule la face visible est placée.
 * La face cachée ne dessine rien, ne reçoit ni toucher ni focus, et sort de la sémantique.
 */
@Composable
fun TwoFaces(back: Boolean, front: @Composable () -> Unit, rear: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val hidden = Modifier.clearAndSetSemantics { }
    Layout(
        contents = listOf(
            { Box(if (back) hidden else Modifier) { front() } },
            { Box(if (back) Modifier else hidden) { rear() } },
        ),
        modifier = modifier,
    ) { (f, r), c ->
        val pf = f.map { it.measure(c.copy(minHeight = 0)) }
        val pr = r.map { it.measure(c.copy(minHeight = 0)) }
        val w = (pf + pr).maxOfOrNull { it.width } ?: c.minWidth
        val h = maxOf((pf + pr).maxOfOrNull { it.height } ?: 0, c.minHeight)
        layout(w.coerceIn(c.minWidth, c.maxWidth), h.coerceIn(c.minHeight, c.maxHeight)) {
            (if (back) pr else pf).forEach { it.place(0, 0) }
        }
    }
}
