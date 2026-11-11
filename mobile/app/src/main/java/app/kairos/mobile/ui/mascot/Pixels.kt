package app.kairos.mobile.ui.mascot

import kotlin.math.abs
import kotlin.math.floor

/**
 * Grille carrée de rôles de pixel (voir [app.kairos.mobile.ui.theme.SceneColors.ROLES]) ;
 * case vide = caractère nul. Les couleurs ne sont résolues qu'au dessin, selon le thème.
 */
class Grid(val n: Int) {
    val cells = CharArray(n * n)

    operator fun get(x: Int, y: Int): Char = cells[y * n + x]

    /** Lignes lisibles, « . » pour une case vide (comparaison avec la maquette). */
    fun rows(): List<String> = (0 until n).map { y -> String(CharArray(n) { x -> this[x, y].takeIf { it != EMPTY } ?: '.' }) }

    companion object {
        const val EMPTY = '\u0000'
    }
}

/** Arrondi à l'entier comme la maquette : moitié vers le haut. */
internal fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

/** Liste de points à plat : x0, y0, x1, y1… */
internal fun xy(vararg v: Int): IntArray = v

/** Crayon décalé de ([ox], [oy]) ; tout ce qui sort de la grille est ignoré. */
class Pen(private val g: Grid, private val ox: Int = 0, private val oy: Int = 0) {
    fun set(x: Int, y: Int, c: Char) {
        val px = x + ox
        val py = y + oy
        if (px >= 0 && py >= 0 && px < g.n && py < g.n) g.cells[py * g.n + px] = c
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, c: Char) {
        for (j in 0 until h) for (i in 0 until w) set(x + i, y + j, c)
    }

    fun pts(list: IntArray, c: Char, dx: Int = 0, dy: Int = 0) {
        for (k in list.indices step 2) set(list[k] + dx, list[k + 1] + dy, c)
    }

    /** Motif en texte : « . » et espace laissent la case telle quelle. */
    fun rows(rs: List<String>, x0: Int, y0: Int) {
        rs.forEachIndexed { j, r -> r.forEachIndexed { i, ch -> if (ch != '.' && ch != ' ') set(x0 + i, y0 + j, ch) } }
    }

    /** Segment de Bresenham entre deux points arrondis à la case. */
    fun line(fx0: Double, fy0: Double, fx1: Double, fy1: Double, c: Char) {
        var x0 = jsRound(fx0)
        var y0 = jsRound(fy0)
        val x1 = jsRound(fx1)
        val y1 = jsRound(fy1)
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var e = dx + dy
        while (true) {
            set(x0, y0, c)
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * e
            if (e2 >= dy) { e += dy; x0 += sx }
            if (e2 <= dx) { e += dx; y0 += sy }
        }
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Char) = line(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(), c)

    fun shift(dx: Int, dy: Int) = Pen(g, ox + dx, oy + dy)
}

/** Expression d'un œil. */
enum class Eye { Open, Blink, Wide, Focus, Low, Sad, Happy }

/** Yeux d'un personnage : coin haut gauche de chacun, taille, rôle. */
internal class EyeRig(val lx: Int, val ly: Int, val rx: Int, val ry: Int, val w: Int, val h: Int, val role: Char = 'D')

/**
 * Pose imposée par une scène ; sans scène, tout est nul et le personnage suit son état.
 * [look] = regard (décalage d'une case) ; [handL] / [handR] = mains posées, en
 * coordonnées locales au personnage pour [MascotKind.draw].
 */
class Pose(
    val state: MascotState = MascotState.Repos,
    val eyes: Eye? = null,
    val look: IntArray? = null,
    val handL: IntArray? = null,
    val handR: IntArray? = null,
    val dx: Int = 0,
    val dy: Int = 0,
    val sit: Boolean = false,
)

internal fun drawEyes(p: Pen, rig: EyeRig, modes: Pair<Eye, Eye>, look: IntArray?) {
    val lx = look?.get(0) ?: 0
    val ly = look?.get(1) ?: 0
    val gap = rig.rx - (rig.lx + rig.w)
    val w = rig.w
    val h = rig.h
    val role = rig.role
    for (side in intArrayOf(-1, 1)) {
        val x = (if (side < 0) rig.lx else rig.rx) + lx
        val y = (if (side < 0) rig.ly else rig.ry) + ly
        when (if (side < 0) modes.first else modes.second) {
            Eye.Blink -> p.rect(x, y + h - 1, w, 1, role)
            Eye.Wide -> p.rect(x, y - 1, w, h + 1, role)
            Eye.Focus -> p.rect(x, y, w, 1, role)
            Eye.Low -> p.rect(x, y + 1, w, h, role)
            Eye.Sad -> {
                p.rect(x, y + 1, w, maxOf(1, h - 1), role)
                p.set(if (side < 0) x + w - 1 else x, y, role)
            }
            Eye.Happy -> {
                p.rect(x, y, w, 1, role)
                p.set(if (side < 0) x - 1 else x + w, y + 1, role)
                if (gap >= 3) p.set(if (side < 0) x + w else x - 1, y + 1, role)
            }
            Eye.Open -> p.rect(x, y, w, h, role)
        }
    }
}

/**
 * Expression retenue : celle de la scène, sinon celle de l'état ; un œil ouvert
 * des deux côtés cligne quand [blink].
 */
internal fun eyeMode(p: Pose, auto: Pair<Eye, Eye>, blink: Boolean): Pair<Eye, Eye> {
    val m = p.eyes?.let { it to it } ?: auto
    return if (m.first == Eye.Open && m.second == Eye.Open && blink) Eye.Blink to Eye.Blink else m
}

internal fun Eye.both() = this to this

internal fun bubbles(p: Pen, pts: IntArray, n: Int) {
    for (k in 0 until minOf(n, pts.size / 2)) p.set(pts[2 * k], pts[2 * k + 1], 'm')
}
