package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.normalizeVerdict
import app.kairos.mobile.ui.politique.kit.verdictLabel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Logique pure du jeu « Vrai ou faux ? » : réponses, gestes, retours, paquets. Les cartes sont désignées par leur rang dans [claimsOf]. */

enum class Guess(val label: String) { Vrai("Vrai"), PlutotVrai("Plutôt vrai"), PlutotFaux("Plutôt faux"), Faux("Faux") }

/** Réponse attendue ; null = hors jeu (non documenté, verdict inconnu). */
fun expectedAnswer(verdict: String): Guess? = when (normalizeVerdict(verdict)) {
    "vrai" -> Guess.Vrai
    "plutot-vrai" -> Guess.PlutotVrai
    "trompeur", "plutot-faux", "non-prouve" -> Guess.PlutotFaux
    "faux" -> Guess.Faux
    else -> null
}

/** Flèche d'une réponse, de gauche à droite : ←, ↖, ↗, →. */
enum class Arrow { L, UL, UR, R }

val Guess.arrow: Arrow get() = when (this) { Guess.Faux -> Arrow.L; Guess.PlutotFaux -> Arrow.UL; Guess.PlutotVrai -> Arrow.UR; Guess.Vrai -> Arrow.R }

/** Secteur d'angle du geste (dp, y vers le bas) : droite −50° à 25°, haut-droite 25° à 90°, haut-gauche 90° à 155°, gauche 155° à 230° ; le bas est neutre. */
fun quizSector(dx: Float, dy: Float): Guess? {
    val a = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
    return when {
        a >= -50 && a < 25 -> Guess.Vrai
        a >= 25 && a < 90 -> Guess.PlutotVrai
        a >= 90 && a < 155 -> Guess.PlutotFaux
        a >= 155 || a < -130 -> Guess.Faux
        else -> null
    }
}

/** Cran d'allumage du voyant visé pendant le glissé : 0 (rien) à 3 (le lâcher répondra). */
fun quizLevel(dx: Float, dy: Float): Int {
    val d = hypot(dx, dy)
    return when { d < 18f -> 0; d >= 100f -> 3; d >= 55f -> 2; else -> 1 }
}

/** Geste lâché : déplacement en dp, vitesse en dp/ms. La distance décide, ou une vitesse franche après un début de course. */
fun quizRelease(dx: Float, dy: Float, vx: Float, vy: Float): Guess? {
    val k = quizSector(dx, dy) ?: return null
    val d = hypot(dx, dy)
    return if (d >= 100f || (hypot(vx, vy) > 0.5f && d > 30f)) k else null
}

/** Carte déjà retournée : un glissement surtout horizontal et suffisant passe à la suivante. */
fun swipeToNext(dx: Float, dy: Float, vx: Float, vy: Float): Boolean = backDragTakes(dx, dy) && (hypot(dx, dy) > 90f || hypot(vx, vy) > 0.5f)

enum class Tone { Ok, Near, Ko }
class Feedback(val tone: Tone, val text: String)

/** Même côté de l'échelle (vrai / plutôt vrai, faux / plutôt faux) : « presque ». */
private fun Guess.trueSide() = this == Guess.Vrai || this == Guess.PlutotVrai

fun quizFeedback(verdict: String, guess: Guess): Feedback {
    val exp = expectedAnswer(verdict)
    return when {
        guess == exp -> Feedback(Tone.Ok, "Bien vu : c'était votre réponse.")
        exp != null && guess.trueSide() == exp.trueSide() -> Feedback(Tone.Near, "Presque : c'était « ${exp.label} ».")
        else -> Feedback(Tone.Ko, "Vous aviez dit « ${guess.label} ».")
    }
}

/** Thèmes ayant au moins une phrase jouable, et nombre de phrases hors jeu. */
fun quizThemes(claims: List<Claim>): List<String> =
    claims.filter { expectedAnswer(it.v) != null }.map { it.theme }.filter { it.isNotBlank() }.distinct()

fun outOfPlay(claims: List<Claim>): Int = claims.count { expectedAnswer(it.v) == null }

/** Paquet d'un thème (vide = tous) : les camps alternent, aucun n'enchaîne plusieurs cartes. */
fun quizDeck(claims: List<Claim>, camps: List<String>, theme: String): List<Int> {
    val pool = claims.indices.filter { expectedAnswer(claims[it].v) != null && (theme.isEmpty() || claims[it].theme == theme) }
    val by = (camps + "").distinct().map { k -> pool.filter { claims[it].camp == k }.toMutableList() }
    val out = ArrayList<Int>()
    while (by.any { it.isNotEmpty() }) for (l in by) if (l.isNotEmpty()) out += l.removeAt(0)
    return out
}

fun quizEndMessage(ok: Int, n: Int): String = when {
    ok == n -> "Sans faute."
    ok * 10 >= n * 7 -> "Belle lecture des faits."
    ok * 10 >= n * 4 -> "Des réflexes justes, et quelques surprises."
    else -> "Les données surprennent souvent : c'est le but."
}

/** Progression de la barre : `answered` réponses données sur `n` ; l'icône de résultat se remplit sur la page de résultat. */
class QuizProgress(val fraction: Float, val resultReached: Boolean)

fun quizProgress(answered: Int, n: Int, atResult: Boolean): QuizProgress =
    QuizProgress(if (n <= 0) 0f else (answered.toFloat() / n).coerceIn(0f, 1f), atResult)

/** Verdict exact des vérificateurs, quand il diffère du libellé joué (« Trompeur » joué en « Plutôt faux ») ; sinon null. */
fun verdictNote(verdict: String): String? {
    val label = verdictLabel(verdict)
    return label.takeIf { it != expectedAnswer(verdict)?.label }
}

// ---------- Pixel : flèches, voyants ----------

/** Flèche 9×9 : cellules allumées (x, y). */
fun arrowCells(dir: Arrow): List<Pair<Int, Int>> = buildList {
    for (y in 0 until 9) for (x in 0 until 9) {
        val xx = if (dir == Arrow.L || dir == Arrow.UL) 8 - x else x
        val on = if (dir == Arrow.R || dir == Arrow.L) (y in 3..5 && xx <= 4) || (xx >= 5 && abs(y - 4) <= 8 - xx)
        else (xx >= 3 && y <= 5 && xx - y >= 3) || ((xx + y == 8 || xx + y == 9) && xx <= 5 && y >= 3)
        if (on) add(x to y)
    }
}

/** Rôles d'une cellule de voyant, dans l'ordre de dessin : halo extérieur, halo, bague, lentille, reflet, ombre. */
enum class LedRole { Ha2, Ha1, Bz, Ln, Lh, Ls }

const val LED_SIZE = 15

/** Voyant-flèche 15×15 : bague autour de la flèche, lentille à reflet en haut à gauche et ombre en bas à droite, halo en deux anneaux. */
fun ledCells(dir: Arrow): List<Triple<Int, Int, LedRole>> {
    val pad = 3
    val on = arrowCells(dir).map { (x, y) -> (x + pad) to (y + pad) }.toSet()
    fun has(x: Int, y: Int) = (x to y) in on
    fun dist(x: Int, y: Int) = on.minOf { (a, b) -> jsRound(hypot((a - x).toDouble(), (b - y).toDouble())) }.coerceAtMost(9)
    val out = ArrayList<Triple<Int, Int, LedRole>>()
    for (y in 0 until LED_SIZE) for (x in 0 until LED_SIZE) {
        val r = if (has(x, y)) {
            if (!has(x, y + 1) || !has(x + 1, y)) LedRole.Ls else if (!has(x, y - 1) || !has(x - 1, y)) LedRole.Lh else LedRole.Ln
        } else when (dist(x, y)) { 1 -> LedRole.Bz; 2 -> LedRole.Ha1; 3 -> LedRole.Ha2; else -> null }
        if (r != null) out += Triple(x, y, r)
    }
    return out.sortedBy { it.third }
}

private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

// ---------- Pile de tracts ----------

/** Encres de la pile, du dessous au tract juste sous la carte en main. */
enum class RearInk { Shade, Flank, Edge, Face, Alt, Band, Txt, Dot, Ear }

/** Tract en cellules de 3 dp : 106 × 147 (318 × 441 dp). */
const val TRACT_W = 106
const val TRACT_H = 147

/** Pseudo-aléatoire stable par carte, celui de la maquette. */
fun quizRnd(k: Double): Double { val x = sin(k * 12.9898 + 78.233) * 43758.5453; return x - floor(x) }

/** Coin supérieur gauche de la carte en main, en cellules : centrée dans la zone [w] × [h]. */
fun cardOrigin(w: Int, h: Int, cw: Int = TRACT_W, ch: Int = TRACT_H): Pair<Int, Int> = jsRound((w - cw) / 2.0) to jsRound((h - ch) / 2.0)

/**
 * Pile sous la carte en main, tramée en cellules de 3 dp sur une zone [w] × [h] : jusqu'à 7 tracts légèrement tournés
 * (rotation cellule par cellule, sans anti-crénelage), tranche et ombre dures ; celui juste dessous reprend la mise en
 * page en pictogramme (coin corné, bandeau, quatre lignes de fausse écriture alignées à gauche, pointillés).
 * [index] = rang de la carte en main, [left] = cartes restantes dessous. Case vide = -1, sinon [RearInk.ordinal].
 */
fun rearCells(w: Int, h: Int, index: Int, left: Int, cw: Int = TRACT_W, ch: Int = TRACT_H): IntArray {
    val out = IntArray(w * h) { -1 }
    val n = min(left, 7)
    if (n <= 0) return out
    val (x0, y0) = cardOrigin(w, h, cw, ch)
    val cx0 = x0 + cw / 2.0
    val cy0 = y0 + ch / 2.0
    class Sh(val k: Int, val a: Double, val cx: Double, val cy: Double)
    val shapes = (n downTo 1).map { k ->
        val id = (index + k).toDouble()
        Sh(k, (quizRnd(id) * 2 - 1) * 5.5 * PI / 180, cx0 + jsRound((quizRnd(id + .5) * 2 - 1) * 2), cy0 + k)
    }
    val wdp = cw * 3.0
    val hdp = ch * 3.0
    val foot = hdp - 3 - 3 - 14 - 24
    val mid = (63 + foot) / 2
    val lens = doubleArrayOf(.84, .94, .72, .46)
    fun paint(sh: Sh, dx: Int, dy: Int, fn: (Double, Double, Double, Double) -> RearInk?) {
        val ca = cos(sh.a); val sa = sin(sh.a); val r = hypot(cw.toDouble(), ch.toDouble()) / 2 + 2
        val hu = cw / 2.0; val hw = ch / 2.0
        var cy = floor(sh.cy - r).toInt()
        while (cy < sh.cy + r) {
            var cx = floor(sh.cx - r).toInt()
            while (cx < sh.cx + r) {
                val px = cx + .5 - sh.cx - dx; val py = cy + .5 - sh.cy - dy
                val u = px * ca + py * sa; val v = -px * sa + py * ca
                if (abs(u) <= hu && abs(v) <= hw && cx in 0 until w && cy in 0 until h) {
                    fn(u + hu, v + hw, hu - abs(u), hw - abs(v))?.let { out[cy * w + cx] = it.ordinal }
                }
                cx++
            }
            cy++
        }
    }
    paint(shapes[0], 1, 2) { _, _, _, _ -> RearInk.Shade }
    for (sh in shapes) {
        paint(sh, 0, 1) { _, _, _, _ -> RearInk.Flank }
        paint(sh, 0, 0) { lx, ly, eu, ev ->
            if (sh.k != 1) return@paint if (eu < 1 || ev < 1) RearInk.Edge else if (sh.k % 2 == 1) RearInk.Face else RearInk.Alt
            val px = lx * 3; val py = ly * 3; val rx = wdp - px
            when {
                rx + py < 27 -> null
                eu < 1 || ev < 1 -> RearInk.Edge
                rx < 27 && py < 27 -> RearInk.Ear
                py >= 21 && py < 63 -> RearInk.Band
                py >= foot + 9 && py < foot + 15 && px >= 25 && px < wdp - 25 -> if (floor((px - 25) / 3).toInt() % 5 < 3) RearInk.Dot else RearInk.Face
                else -> {
                    val col = floor(lx).toInt()
                    (0 until 4).firstOrNull { j ->
                        val top = mid + (j - 1.5) * 30 - 3
                        py >= top && py < top + 6 && px >= 25 && px < 25 + (wdp - 50) * lens[j] && (col * 7 + j * 5) % 13 != 0
                    }?.let { RearInk.Txt } ?: RearInk.Face
                }
            }
        }
    }
    return out
}

// ---------- Retournement de la feuille ----------

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

private fun pattern(k: TurnFace, u: Double, v: Double, h: Double): TurnInk {
    val row = floor(v * h / 12).toInt()
    val inRow = (v * h) % 12 < 3
    if (k == TurnFace.Res) {
        if (v > .05 && v < .12 && u > .07 && u < .42) return TurnInk.Acc
        if (v > .87 && v < .95 && u > .6 && u < .93) return TurnInk.Box
        if (v > .17 && v < .66 && inRow && u > .07 && u < .93 - ((row * 37) % 5) * .08) return TurnInk.Line
        return TurnInk.Paper
    }
    if (v > .04 && v < .07 && u > .07 && u < .35) return TurnInk.Line
    if ((v > .73 && v < .8 || v > .83 && v < .9) && u > .07 && u < .93) return TurnInk.Box
    if (v > .11 && v < .68 && inRow && u > .07 && u < .93 - ((row * 53) % 5) * .08) return TurnInk.Line
    return TurnInk.Paper
}

/**
 * Retournement à l'avancement [t] d'une feuille de [w] × [h] dp, vue de face, en bande souple : une colonne par cellule de
 * 3 dp, chacune avec son angle ; le bord droit mène, le gauche suit avec un retard de 0,6, ce qui courbe le papier. La
 * colonne la plus proche grandit (perspective), le pli s'assombrit, la feuille se soulève et son ombre au sol pâlit.
 * Les faces sont dessinées en pictogramme ; le coin corné et son triangle restent visibles tout du long.
 */
fun turnFrame(t: Double, w: Int, h: Int, from: TurnFace): TurnFrame {
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
    val lift = jsRound(sin(PI * t) * 10 / c) * c
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
            if (ex + ey < 27) continue
            val ink = when {
                cy == c0 || cy == c1 - 1 || i == 0 || i == nc - 1 -> TurnInk.Edge
                ex < 27 && ey < 27 -> TurnInk.Ear
                else -> pattern(k, u, v, h.toDouble())
            }
            runs += TurnRun(a, cy, b - a + 1, ink, lt)
        }
    }
    return TurnFrame(cols, rows, shadow, (.16 - lift / 200).toFloat(), runs)
}

/** Face « faits et sources » : seul un glissé surtout horizontal est pris par la carte ; le vertical reste au défilement. */
fun backDragTakes(dx: Float, dy: Float): Boolean = abs(dx) > abs(dy)
