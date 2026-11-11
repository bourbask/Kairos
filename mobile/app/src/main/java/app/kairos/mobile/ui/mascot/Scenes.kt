package app.kairos.mobile.ui.mascot

import kotlin.math.hypot

/** Image d'une scène en trois calques 32×32 : décor, personnage (qui respire), premier plan. */
class SceneFrame(val back: Grid, val mascot: Grid, val front: Grid) {
    /** Les trois calques fondus, le premier plan au-dessus. */
    fun flatten(): Grid = Grid(SCENE_SIZE).also { out ->
        for (l in listOf(back, mascot, front)) for (k in out.cells.indices) if (l.cells[k] != Grid.EMPTY) out.cells[k] = l.cells[k]
    }
}

const val SCENE_SIZE = 32

/** Où se tient un personnage dans la scène : décalage de sa grille 16×16. */
private class Stage(val ox: Int, var oy: Int)

private fun stage(m: MascotKind, x: Int, floor: Int) = Stage(jsRound(x - m.anchor.cx), floor - 13)

/** Repères utiles d'un personnage posé, en coordonnées scène. */
private class Geo(m: MascotKind, at: Stage) {
    private val a = m.anchor
    val cx = jsRound(at.ox + a.cx)
    val left = jsRound(at.ox + a.cx - a.halfW)
    val right = jsRound(at.ox + a.cx + a.halfW)
    val top = at.oy + a.top
    val crown = at.oy + a.crown
    val hold = at.oy + a.hold
}

/**
 * Pose le personnage : bras derrière le corps, puis le corps, puis l'objet tenu
 * ([held], dessiné en coordonnées scène), puis les mains devant. [pose] porte
 * les mains en coordonnées scène.
 */
private fun putMascot(l: Grid, m: MascotKind, at: Stage, pose: Pose, t: Int, held: ((Pen) -> Unit)? = null) {
    val ox = at.ox + pose.dx
    val oy = at.oy + pose.dy
    val p = Pen(l, ox, oy)
    val hl = pose.handL?.let { xy(it[0] - ox, it[1] - oy) }
    val hr = pose.handR?.let { xy(it[0] - ox, it[1] - oy) }
    m.arm?.let { arm ->
        hl?.let { p.line(arm.l[0], arm.l[1], it[0], it[1], arm.role) }
        hr?.let { p.line(arm.r[0], arm.r[1], it[0], it[1], arm.role) }
    }
    m.draw(p, Pose(pose.state, pose.eyes, pose.look, hl, hr, pose.dx, pose.dy, pose.sit), t)
    held?.invoke(Pen(l))
    val shape = m.hand.shape
    for (k in shape.indices step 2) {
        hl?.let { p.set(it[0] - shape[k], it[1] + shape[k + 1], m.hand.role) }
        hr?.let { p.set(it[0] + shape[k], it[1] + shape[k + 1], m.hand.role) }
    }
}

private fun floorLine(p: Pen, x0: Int, x1: Int, y: Int) = p.rect(x0, y, x1 - x0 + 1, 1, 's')

private fun jar(p: Pen, x: Int, y: Int) {
    p.rect(x, y, 3, 1, 'a'); p.rect(x, y + 1, 3, 3, 'A'); p.set(x, y + 1, 'F')
}

private val COIN_FACE = listOf(".AA.", "AFAA", "AAaA", ".aa.")
private val COIN_HALF = listOf("AA", "FA", "Aa", "aa")
private val COIN_EDGE = listOf("A", "A", "A", "A")

private fun coinStack(p: Pen, x: Int, yBottom: Int, n: Int) {
    for (i in 0 until n) {
        val y = yBottom - i * 2
        p.rect(x, y, 4, 1, 'a'); p.rect(x, y - 1, 4, 1, 'A')
    }
}

private fun look(x: Int, y: Int) = xy(x, y)

/**
 * Scène jouée sur le bouton Kairos pendant qu'un espace est ouvert. [ms] = durée
 * de chaque image (étirée par le tempo du personnage), [key] = image fixe quand les
 * animations sont coupées, [lift] = lignes retirées en haut pour remonter la scène.
 */
enum class SceneKind(val ms: IntArray, val key: Int, private val lift: Int = 0) {
    /** Fouille l'étagère, sort un bocal, l'admire, le range. */
    Inventaire(intArrayOf(420, 160, 130, 130, 130, 220, 340, 240, 300, 200, 180, 240), 7) {
        override fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean) {
            val b = Pen(lb)
            floorLine(b, 3, 28, 26)
            // Étagère : fond, montants, planches.
            b.rect(19, 9, 9, 16, 's')
            b.rect(18, 8, 1, 18, 'D'); b.rect(28, 8, 1, 18, 'D')
            for (y in intArrayOf(8, 14, 20, 25)) b.rect(18, y, 11, 1, 'D')
            // Haut : livres et petit pot.
            b.rect(19, 10, 1, 4, 'A'); b.rect(20, 11, 1, 3, 'R'); b.rect(21, 10, 1, 4, 'F'); b.rect(22, 12, 2, 2, 'h')
            b.rect(25, 11, 2, 3, 'm'); b.rect(25, 10, 2, 1, 'a')
            // Bas : panier tressé et balle.
            b.rect(19, 22, 6, 3, 'a'); for (x in intArrayOf(20, 22, 24)) b.set(x, 23, 'A'); b.rect(19, 21, 6, 1, 'A')
            b.rect(26, 23, 2, 2, 'R'); b.set(26, 23, 'F')
            val at = stage(m, 10, 26)
            val g = Geo(m, at)
            // Milieu : boîte qui bouge quand on fouille, et le bocal (case vide quand il est sorti).
            val jiggle = if (i == 2 || i == 4) 1 else 0
            b.rect(24 + jiggle, 17, 4 - jiggle, 3, 'F'); b.rect(25 + jiggle, 17, 1, 3, 'h'); b.rect(24 + jiggle, 17, 4 - jiggle, 1, 'h')
            if (i !in 5..9) jar(b, if (i == 3) 21 else 20, 16)
            if (i == 2) b.set(23, 15, 'm')
            if (i == 3) b.set(22, 15, 'm')
            if (i == 4) b.pts(xy(21, 15, 24, 15), 'm')
            val hx = g.right + 1
            val up = xy(g.cx - 3, g.crown - 3) to xy(g.cx + 3, g.crown - 3)
            val p = when (i) {
                0 -> Pose(look = look(1, 0))
                1 -> Pose(dx = 1, eyes = Eye.Focus, look = look(1, 0), handR = xy(19, 17))
                2 -> Pose(dx = 1, eyes = Eye.Focus, look = look(1, 0), handR = xy(21, 16))
                3 -> Pose(dx = 1, eyes = Eye.Focus, look = look(1, 1), handR = xy(20, 18))
                4 -> Pose(dx = 1, eyes = Eye.Focus, look = look(1, 0), handR = xy(22, 17))
                5 -> Pose(eyes = Eye.Wide, look = look(1, 0), handR = xy(hx + 2, 17))
                6 -> Pose(eyes = Eye.Wide, look = look(0, -1), handL = up.first, handR = up.second)
                7 -> Pose(dy = -1, eyes = Eye.Wide, look = look(0, -1), handL = xy(g.cx - 3, g.crown - 4), handR = xy(g.cx + 3, g.crown - 4))
                8 -> Pose(eyes = Eye.Happy, handL = up.first, handR = up.second)
                9 -> Pose(look = look(1, 0), handR = xy(hx + 2, 17))
                10 -> Pose(dx = 1, look = look(1, 0), handR = xy(19, 18))
                else -> Pose(look = look(1, 0), eyes = Eye.Happy)
            }
            putMascot(lm, m, at, p, t) { h ->
                if (i == 5 || i == 9) jar(h, hx - 1, 16)
                if (i in 6..8) {
                    val y = g.crown - 5 + p.dy
                    jar(h, g.cx - 1, y)
                    // Petits éclats autour du bocal levé.
                    if (i != 8) h.pts(if (i == 6) xy(g.cx - 3, y - 1, g.cx + 3, y + 1) else xy(g.cx + 3, y - 1, g.cx - 3, y + 1), 'A')
                }
            }
        }
    },

    /** Compte les piles, fait sauter une pièce, la rattrape, la repose. */
    Finances(intArrayOf(300, 220, 160, 300, 220, 140, 110, 110, 120, 120, 150, 260, 220, 280), 7) {
        override fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean) {
            val b = Pen(lb)
            val f = Pen(lf)
            floorLine(b, 3, 28, 26)
            val at = stage(m, 16, 26)
            val g = Geo(m, at)
            coinStack(f, 4, 25, 3)
            coinStack(f, 24, 25, if (i in 4..11) 3 else 4)
            if (i == 1) f.set(5, 20, 'F')
            if (i == 3) f.set(25, 18, 'F')
            if (i == 12) f.pts(xy(24, 17, 27, 17), 'F')
            val topL = 25 - 3 * 2
            val topR = 25 - 4 * 2 + 1
            // Pièce en l'air : vue, hauteur, décalage depuis le centre (un arc de la main droite vers les deux mains).
            val air = when (i) {
                5 -> Triple(COIN_HALF, 9, 4)
                6 -> Triple(COIN_EDGE, 5, 3)
                7 -> Triple(COIN_FACE, 3, 1)
                8 -> Triple(COIN_HALF, 4, -1)
                9 -> Triple(COIN_EDGE, 7, -1)
                10 -> Triple(COIN_FACE, 10, 0)
                else -> null
            }
            val p = when (i) {
                0 -> Pose(look = look(-1, 0))
                1 -> Pose(look = look(-1, 1), handL = xy(8, topL))
                2 -> Pose(look = look(-1, 0), handL = xy(9, topL - 2))
                3 -> Pose(look = look(1, 1), handR = xy(23, topR))
                4 -> Pose(look = look(1, 0), eyes = Eye.Wide, handR = xy(g.right + 4, topR - 1))
                5 -> Pose(look = look(1, -1), eyes = Eye.Wide, handR = xy(g.right + 2, g.top - 1))
                6 -> Pose(look = look(1, -1), eyes = Eye.Wide, dy = -1)
                7 -> Pose(look = look(0, -1), eyes = Eye.Wide, dy = -1)
                8 -> Pose(look = look(-1, -1), eyes = Eye.Wide)
                9 -> Pose(look = look(0, -1), eyes = Eye.Open, handL = xy(g.cx - 3, g.top - 1), handR = xy(g.cx + 3, g.top - 1))
                10 -> Pose(look = look(0, -1), eyes = Eye.Open, handL = xy(g.cx - 3, 12), handR = xy(g.cx + 3, 12))
                11 -> Pose(eyes = Eye.Happy, handL = xy(g.cx - 3, 12), handR = xy(g.cx + 3, 12))
                12 -> Pose(look = look(1, 0), handR = xy(23, topR - 1))
                else -> Pose(eyes = Eye.Happy)
            }
            putMascot(lm, m, at, p, t) { h ->
                if (i == 4) h.rows(COIN_FACE, g.right + 1, topR - 3)
                air?.let { (s, y, dx) -> h.rows(s, g.cx - s[0].length / 2 + dx, y) }
                if (i == 11) h.rows(COIN_FACE, g.cx - 2, 10)
            }
        }
    },

    /** Tient un calendrier, tourne la page, coche un jour. */
    Agenda(intArrayOf(380, 200, 140, 120, 140, 160, 300, 220, 160, 260, 320, 260, 300), 10) {
        override fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean) {
            floorLine(Pen(lb), 3, 28, 26)
            val at = stage(m, 10, 26)
            // Coin haut gauche du calendrier.
            val cx0 = 17
            val cy0 = 11
            val marked = i == 0 || i >= 9
            // Étapes de la page qui tourne : coin levé, mi-course, au-dessus de l'en-tête, retombée.
            val flip = when (i) { 2 -> 1; 3 -> 2; 4 -> 3; 5 -> 4; else -> 0 }
            val tap = i == 8 || i == 9
            val hold = xy(cx0 - 1, cy0 + 4)
            val p = when (i) {
                0 -> Pose(look = look(1, 0), handL = hold)
                1 -> Pose(look = look(1, 0), handL = xy(cx0 - 1, cy0 + 8), handR = xy(cx0 + 10, cy0 + 8))
                2 -> Pose(look = look(1, 1), eyes = Eye.Wide, handL = hold, handR = xy(cx0 + 10, cy0 + 7))
                3 -> Pose(look = look(1, 0), eyes = Eye.Wide, handL = hold, handR = xy(cx0 + 10, cy0 + 4))
                4 -> Pose(look = look(1, -1), eyes = Eye.Wide, handL = hold, handR = xy(cx0 + 10, cy0 + 1))
                5 -> Pose(look = look(1, -1), handL = hold, handR = xy(cx0 + 10, cy0 - 3))
                6 -> Pose(look = look(1, 0), eyes = Eye.Focus, handL = hold)
                7 -> Pose(look = look(1, 1), eyes = Eye.Focus, handL = hold)
                8 -> Pose(look = look(1, 1), handL = hold, handR = xy(cx0 + 6, cy0 + 8))
                9 -> Pose(look = look(1, 1), eyes = Eye.Happy, handL = hold, handR = xy(cx0 + 6, cy0 + 9))
                10 -> Pose(eyes = Eye.Happy, dy = 1, handL = hold)
                else -> Pose(look = look(1, 0), handL = hold)
            }
            putMascot(lm, m, at, p, t) { h ->
                val x = cx0
                val y = cy0 + if (i == 10) 1 else 0
                // Corps 10×11 : anneaux, en-tête, page, tranche.
                h.rect(x, y + 2, 10, 2, 'R')
                h.rect(x, y + 4, 10, 6, 'F')
                h.rect(x, y + 10, 10, 1, 'h')
                h.pts(xy(x + 2, y + 1, x + 7, y + 1), 'D'); h.pts(xy(x + 2, y + 2, x + 7, y + 2), 'D')
                // Jours : trois rangées de trois cases de deux pixels, assez marquées pour se lire en petit.
                val days = List(9) { k -> xy(x + 1 + (k % 3) * 3, y + 5 + (k / 3) * 2) }
                fun day(d: IntArray) = h.rect(d[0], d[1], 2, 1, 's')
                val mx = days[4][0]
                val my = days[4][1]
                if (flip == 0 || flip == 4) days.forEach(::day)
                if (marked) { h.rect(mx - 1, my - 1, 4, 3, 'A'); h.rect(mx, my, 2, 1, 'D') }
                if (tap && !marked) h.rect(mx, my, 2, 1, 'A')
                if (i == 8) h.pts(xy(mx - 1, my - 1, mx + 2, my - 1), 'A')
                if (flip == 1) {
                    // Le coin se soulève.
                    h.pts(xy(x + 8, y + 10, x + 9, y + 10, x + 9, y + 9), 'B')
                    h.pts(xy(x + 7, y + 9, x + 8, y + 8, x + 8, y + 9, x + 9, y + 8, x + 7, y + 8), 'h')
                    days.take(7).forEach(::day)
                }
                if (flip == 2) {
                    // Mi-course : le bas de la page remonte, vu par la tranche.
                    h.rect(x, y + 4, 10, 1, 'F'); h.rect(x + 1, y + 4, 9, 3, 'h'); h.rect(x + 1, y + 4, 9, 1, 'F')
                    h.rect(x, y + 7, 10, 3, 'F'); days.drop(6).forEach(::day)
                }
                if (flip == 3) {
                    // La page monte au-dessus de l'en-tête.
                    h.rect(x + 1, y - 3, 9, 5, 'h'); h.rect(x + 1, y - 3, 9, 1, 'F'); h.rect(x + 4, y + 4, 6, 3, 'F')
                    days.filter { it[1] > y + 6 }.forEach(::day)
                }
                if (flip == 4) h.rect(x + 2, y - 2, 7, 2, 'h')
            }
        }
    },

    /** Tableau papier : la courbe se trace, réaction, on tourne la feuille. [down] = courbe en baisse. */
    Marches(intArrayOf(360, 150, 150, 150, 150, 150, 150, 220, 260, 260, 300, 200, 160, 240), 9) {
        override fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean) {
            val b = Pen(lb)
            floorLine(b, 3, 28, 26)
            // Chevalet.
            b.line(16, 20, 14, 25, 'D'); b.line(27, 20, 29, 25, 'D'); b.rect(21, 20, 1, 6, 's')
            b.rect(14, 6, 15, 14, 'F'); b.rect(14, 19, 15, 1, 'h')
            b.rect(13, 5, 17, 1, 'D'); b.pts(xy(15, 4, 27, 4), 'D')
            // Axes.
            b.rect(16, 8, 1, 10, 'm'); b.rect(16, 17, 11, 1, 'm')
            val pts = CURVE_UP.copyOf().also { if (down) for (k in 1 until it.size step 2) it[k] = 24 - it[k] }
            val n = pts.size / 2
            val col = if (down) 'R' else 'B'
            val shown = if (i == 0 || i >= 12) 0 else minOf(n, 1 + maxOf(0, i - 1) * 2)
            val ex = pts[2 * n - 2]
            val ey = pts[2 * n - 1]
            if (i != 11 && i != 12) {
                for (k in 1 until shown) {
                    b.line(pts[2 * k - 2], pts[2 * k - 1], pts[2 * k], pts[2 * k + 1], col)
                    b.line(pts[2 * k - 2], pts[2 * k - 1] + 1, pts[2 * k], pts[2 * k + 1] + 1, col)
                }
                if (shown == 1) b.set(pts[0], pts[1], col)
                if (i in 7..10) b.pts(if (down) xy(ex - 1, ey, ex, ey - 1, ex - 1, ey - 1) else xy(ex - 1, ey, ex, ey + 1, ex - 1, ey + 1), col)
            }
            // La feuille tourne.
            if (i == 11) { b.rect(14, 6, 15, 7, 'h'); b.rect(14, 1, 15, 4, 'h'); b.rect(14, 1, 15, 1, 'F') }
            if (i == 12) b.rect(15, 2, 13, 3, 'h')
            val at = stage(m, 7, 26)
            val g = Geo(m, at)
            val tipK = if (shown > 0) minOf(shown, n) - 1 else 0
            val pointing = i in 1..7
            val p = when {
                i == 0 || i == 13 -> Pose(look = look(1, -1))
                pointing -> Pose(look = look(1, -1), eyes = Eye.Focus, handR = xy(g.right + 2, g.hold - 1))
                i in 8..10 && down -> Pose(look = look(1, 0), eyes = if (i == 9) Eye.Sad else Eye.Low, dy = if (i == 9) 1 else 0)
                i in 8..10 -> Pose(
                    state = if (i == 9) MascotState.Agit else MascotState.Repos, eyes = Eye.Happy,
                    dy = if (i == 9) -2 else if (i == 8) -1 else 0,
                    handL = if (i == 9) xy(g.left - 2, g.top) else null, handR = if (i == 9) xy(g.right + 2, g.top) else null,
                )
                else -> Pose(look = look(1, -1), eyes = Eye.Wide)
            }
            putMascot(lm, m, at, p, t) { h ->
                if (pointing) {
                    // Baguette pointée vers le bout de la courbe.
                    val sx = g.right + 3
                    val sy = g.hold - 2
                    val vx = (pts[2 * tipK] - sx).toDouble()
                    val vy = (pts[2 * tipK + 1] - sy).toDouble()
                    val l = hypot(vx, vy)
                    h.line(sx.toDouble(), sy.toDouble(), sx + vx / l * 5, sy + vy / l * 5, 'D')
                }
                if (i in 8..10 && !down) h.pts(if (i % 2 == 1) xy(ex + 2, ey - 2, ex - 2, ey - 3) else xy(ex + 2, ey, ex + 1, ey - 3), 'A')
                if (i in 8..10 && down) {
                    // Goutte de sueur.
                    val y = g.top - 1 + (i - 8) * 2
                    h.pts(xy(g.right + 1, y, g.right + 1, y + 1, g.right, y + 1), 'm')
                }
            }
        }
    },

    /** Carnet à spirale, crayon, lignes qui s'écrivent, page arrachée. */
    Memoire(intArrayOf(300, 150, 150, 150, 150, 380, 380, 150, 150, 300, 200, 140, 160, 220, 240), 4) {
        override fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean) {
            floorLine(Pen(lb), 3, 28, 26)
            val at = stage(m, 9, 26)
            val g = Geo(m, at)
            // Carnet 9×12.
            val px = 17
            val py = 12
            val lines = listOf(xy(18, 16, 24), xy(18, 18, 22), xy(18, 20, 23))
            // Longueur écrite de chaque ligne selon l'image.
            val prog = MEMOIRE_PROG[i]
            // Arrachage : page saisie, décollée à mi-hauteur, levée, puis au loin.
            val tearing = i in 10..13
            var tip: IntArray? = null
            for (k in 0 until 3) if (prog[k] != 0 && (k == 2 || prog[k + 1] == 0)) tip = xy(lines[k][0] + prog[k] - 1, lines[k][1])
            if (i == 0 || i >= 13) tip = xy(lines[0][0], lines[0][1] - 1)
            val writing = i in intArrayOf(1, 2, 3, 4, 7, 8)
            val thinking = i == 5 || i == 6
            // En réflexion, le crayon touche la tempe, hors du visage.
            val ey = at.oy + m.anchor.eye
            val holdL = xy(px - 1, py + 7)
            val p = when {
                thinking -> Pose(look = look(-1, -1), eyes = Eye.Focus, handL = holdL, handR = xy(g.right + 2, ey))
                i == 9 -> Pose(eyes = Eye.Happy, handL = holdL, handR = xy(27, 12))
                tearing -> Pose(
                    look = look(1, -1), eyes = Eye.Wide, handL = holdL,
                    handR = when (i) { 10 -> xy(px + 8, py + 1); 11 -> xy(px + 8, py - 2); else -> xy(px + 5, py - 4) },
                )
                else -> Pose(look = look(1, 1), eyes = if (writing) Eye.Focus else Eye.Open, handL = holdL, handR = xy(tip!![0] + 2, tip[1] - 2))
            }
            putMascot(lm, m, at, p, t) { h ->
                h.rect(px, py + 1, 9, 11, 'F'); h.rect(px, py + 11, 9, 1, 'h'); h.rect(px, py + 1, 1, 11, 'h')
                for (x in px + 1 until px + 9 step 2) { h.set(x, py, 'D'); h.set(x, py + 1, 'D') }
                // Lignes écrites à l'encre foncée, lisibles en petit.
                if (!tearing) lines.forEachIndexed { k, ln -> if (prog[k] != 0) h.rect(ln[0], ln[1], prog[k], 1, 's') }
                if (i == 10) {
                    h.rect(px + 1, py + 2, 8, 9, 'h'); h.rect(px + 1, py + 2, 8, 1, 'F')
                    for (ln in lines) h.rect(ln[0], ln[1], ln[2] - ln[0], 1, 's')
                }
                if (i == 11) {
                    h.rect(px + 1, py - 1, 8, 7, 'h'); h.rect(px + 1, py - 1, 8, 1, 'F')
                    h.rect(px + 2, py + 1, 6, 1, 's'); h.rect(px + 2, py + 3, 4, 1, 's')
                }
                if (i == 12) { h.rect(px + 2, py - 5, 6, 5, 'h'); h.rect(px + 3, py - 4, 4, 1, 's'); h.rect(px + 3, py - 2, 3, 1, 's') }
                if (i == 13) { h.rect(px + 5, py - 8, 4, 3, 'h'); h.rect(px + 6, py - 7, 2, 1, 's') }
                // Crayon : mine, corps, gomme.
                val c = when {
                    thinking -> xy(g.right, ey)
                    writing || i == 0 || i == 14 -> xy(tip!![0], tip[1] - 1)
                    else -> null
                }
                if (c != null) { h.set(c[0], c[1], 'D'); h.set(c[0] + 1, c[1] - 1, 'A'); h.set(c[0] + 2, c[1] - 2, 'A'); h.set(c[0] + 3, c[1] - 3, 'R') }
                if (thinking) bubbles(h, xy(g.cx + 3, g.crown - 2, g.cx + 4, g.crown - 4, g.cx + 5, g.crown - 7), if (i == 5) 2 else 3)
            }
        }
    },

    /** Canapé, télé qui scintille, pop-corn. */
    Medias(intArrayOf(260, 260, 260, 220, 200, 240, 260, 260, 180, 300, 260, 260), 5) {
        override fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean) {
            val b = Pen(lb)
            val f = Pen(lf)
            floorLine(b, 2, 29, 26)
            // Télé sur meuble bas.
            b.rect(20, 21, 10, 4, 's'); b.rect(20, 20, 10, 1, 'D'); b.pts(xy(21, 25, 28, 25), 'D')
            b.rect(20, 11, 10, 8, 'D'); b.rect(24, 19, 2, 1, 'D'); b.line(23, 10, 22, 7, 'D'); b.line(26, 10, 27, 7, 'D')
            if (i == 8) {
                b.rect(21, 12, 8, 6, 'F')
            } else {
                // Image simple en aplats de deux pixels : ciel, soleil, colline qui glisse d'une case.
                val c = i in intArrayOf(0, 2, 4, 6, 9, 11)
                val o = if (c) 0 else 1
                b.rect(21, 12, 8, 6, 'm')
                b.rect(if (c) 26 else 22, 13, 2, 2, 'A')
                b.rect(21, 16, 8, 2, 'B'); b.rect(22 + o, 15, 4, 1, 'B'); b.rect(23 + o, 14, 2, 1, 'B')
                b.rect(21, if (c) 12 else 17, 8, 1, 'F')
            }
            // Canapé vu de profil : dossier à gauche, assise, accoudoir.
            b.rect(2, 14, 4, 11, 'a'); b.rect(3, 13, 2, 1, 'a'); b.rect(5, 14, 1, 10, 'A')
            val seat = 22
            // Le bas du corps (y=12) repose sur l'assise.
            val at = stage(m, 9, seat).also { it.oy = seat - 12 }
            val g = Geo(m, at)
            f.rect(3, seat, 15, 1, 'A'); f.rect(3, seat + 1, 15, 2, 'a'); f.pts(xy(4, 25, 16, 25), 'D')
            f.rect(16, 18, 3, 5, 'A'); f.rect(16, 18, 3, 1, 'F'); f.rect(18, 19, 1, 4, 'a')
            // Pot de pop-corn posé sur l'accoudoir.
            val bx = 16
            val by = 14
            f.rect(bx, by, 3, 4, 'R'); f.rect(bx + 1, by, 1, 4, 'F'); f.rect(bx, by - 1, 3, 1, 'F'); f.set(bx + 1, by - 2, 'F')
            if (i == 8) f.pts(xy(bx, by - 3, bx + 2, by - 4), 'F')
            val p = when (i) {
                2, 7 -> Pose(sit = true, look = look(1, -1))
                3 -> Pose(sit = true, look = look(1, 0), handR = xy(bx - 1, by - 1))
                4 -> Pose(sit = true, look = look(0, 0), eyes = Eye.Open, handR = xy(g.cx + 2, g.top + 4))
                5 -> Pose(sit = true, eyes = Eye.Happy, dy = 1, handR = xy(g.cx + 2, g.top + 5))
                8 -> Pose(sit = true, look = look(1, -1), eyes = Eye.Wide, dy = -1)
                9 -> Pose(sit = true, look = look(1, 0), eyes = Eye.Wide)
                else -> Pose(sit = true, look = look(1, 0))
            }
            putMascot(lm, m, at, p, t) { h -> if (i == 4) h.set(g.cx + 1, g.top + 4, 'F') }
        }
    },

    /** Derrière un bureau, prend chaque feuille en main pour la lire, la lance sur la bonne pile (oui / non). */
    Documents(intArrayOf(300, 180, 220, 320, 240, 160, 140, 240, 260, 180, 220, 320, 120, 120, 140, 240), 3, lift = 3) {
        override fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean) {
            val f = Pen(lf)
            val at = stage(m, 16, 23).also { it.oy = minOf(it.oy, 18 - m.anchor.eye) }
            val g = Geo(m, at)
            // Bureau.
            f.rect(1, 23, 30, 1, 'D'); f.rect(2, 24, 28, 6, 's'); f.rect(2, 24, 28, 1, 'a')
            // Piles : à trier (gauche), classées oui / non (droite, onglets accent et urgent).
            fun stack(x: Int, w: Int, n: Int) { for (k in 0 until n) f.rect(x, 22 - k, w, 1, if (k % 2 == 1) 's' else 'F') }
            stack(2, 6, 6); stack(22, 4, 3); stack(27, 4, 3)
            f.rect(25, 19, 1, 2, 'A'); f.rect(30, 19, 1, 2, 'R')
            if (i == 7) f.pts(xy(22, 19, 23, 18), 'A')
            if (i == 15) f.pts(xy(27, 19, 28, 18), 'R')
            // Feuille tenue à deux mains, à hauteur des yeux, côté gauche.
            val sx = g.left - 3 + when (i) { 12 -> -1; 13 -> 1; else -> 0 }
            val sy = 12 + if (i == 4) 1 else 0
            val read = i in intArrayOf(2, 3, 4, 10, 11, 12, 13)
            val l = xy(sx - 1, sy + 4)
            val r = xy(sx + 5, sy + 3)
            val p = when (i) {
                0, 8 -> Pose(look = look(-1, 0))
                1, 9 -> Pose(look = look(-1, 1), handL = xy(8, 16))
                2, 10 -> Pose(look = look(-1, 0), eyes = Eye.Wide, handL = l, handR = r)
                3, 11 -> Pose(look = look(-1, 0), eyes = Eye.Focus, handL = l, handR = r)
                4 -> Pose(eyes = Eye.Happy, dy = 1, handL = l, handR = r)
                5 -> Pose(look = look(0, -1), handL = xy(g.cx - 4, g.crown - 3), handR = xy(g.cx + 4, g.crown - 3))
                6 -> Pose(look = look(1, -1), eyes = Eye.Happy, handR = xy(g.right + 2, g.crown - 1))
                7 -> Pose(look = look(1, 1), eyes = Eye.Happy, handR = xy(27, 17))
                12 -> Pose(look = look(-1, 0), eyes = Eye.Low, dx = -1, handL = l, handR = r)
                13 -> Pose(look = look(-1, 0), eyes = Eye.Low, dx = 1, handL = l, handR = r)
                14 -> Pose(look = look(1, 0), eyes = Eye.Low, handR = xy(g.right + 3, g.top))
                else -> Pose(look = look(1, 1), eyes = Eye.Low, handR = xy(31, 17))
            }
            putMascot(lm, m, at, p, t) { h ->
                if (i == 1 || i == 9) paper(h, 2, 14, 6, 3)
                if (read) { paper(h, sx, sy, 5, 7); h.rect(sx + 1, sy + 1, 2, 1, 's'); h.rect(sx + 1, sy + 3, 2, 1, 's') }
                if (i == 5) paper(h, g.cx - 3, g.crown - 5, 7, 3)
                // En vol : la feuille décrit un arc avant d'atterrir.
                if (i == 6) paper(h, g.right + 1, g.crown - 4, 5, 3)
                if (i == 14) paper(h, g.right + 3, g.top - 4, 4, 3)
                if (i == 7) paper(h, 22, 17, 4, 2)
                if (i == 15) paper(h, 27, 17, 4, 2)
            }
        }
    },
    ;

    protected abstract fun draw(lb: Grid, lm: Grid, lf: Grid, m: MascotKind, i: Int, t: Int, down: Boolean)

    /** Image [i] de la scène avec [m], dont le cycle de repos en est à l'image [t]. */
    fun frame(m: MascotKind, i: Int, t: Int, down: Boolean = false): SceneFrame {
        val layers = List(3) { Grid(SCENE_SIZE) }
        draw(layers[0], layers[1], layers[2], m, i, t, down)
        if (lift > 0) for (l in layers) {
            val c = l.cells
            System.arraycopy(c, lift * SCENE_SIZE, c, 0, c.size - lift * SCENE_SIZE)
            c.fill(Grid.EMPTY, c.size - lift * SCENE_SIZE, c.size)
        }
        return SceneFrame(layers[0], layers[1], layers[2])
    }
}

/** Feuille : papier ombré d'encre à droite et en bas, pour se détacher des corps clairs. */
private fun paper(p: Pen, x: Int, y: Int, w: Int, h: Int) {
    p.rect(x, y, w, h, 'D'); p.rect(x, y, w - 1, h - 1, 'F')
}

private val CURVE_UP = xy(17, 15, 18, 14, 19, 15, 20, 13, 21, 12, 22, 13, 23, 10, 24, 9, 25, 10, 26, 7)

private val MEMOIRE_PROG = listOf(
    xy(0, 0, 0), xy(3, 0, 0), xy(7, 0, 0), xy(7, 2, 0), xy(7, 5, 0), xy(7, 5, 0), xy(7, 5, 0),
    xy(7, 5, 3), xy(7, 5, 6), xy(7, 5, 6), xy(7, 5, 6), xy(0, 0, 0), xy(0, 0, 0), xy(0, 0, 0), xy(0, 0, 0),
)
