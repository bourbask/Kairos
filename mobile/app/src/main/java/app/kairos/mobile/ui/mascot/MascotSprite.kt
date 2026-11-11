package app.kairos.mobile.ui.mascot

import kotlin.math.PI
import kotlin.math.cos

enum class MascotState { Repos, Ecoute, Reflechit, Agit, Attend }

/** Durées des images d'un état : une seule valeur ou une par image, en boucle. */
class Timing(val n: Int, private val ms: IntArray) {
    fun ms(f: Int): Int = ms[f % ms.size]

    private val total = (0 until n).sumOf { ms(it) }

    /** Nombre d'images écoulées depuis le début du cycle, [scale] étirant chaque durée. */
    fun countAt(elapsed: Long, scale: Double = 1.0): Long {
        val period = total * scale
        var x = elapsed % period
        var count = (elapsed / period).toLong() * n
        for (f in 0 until n) {
            x -= ms(f) * scale
            if (x < 0) return count + f
        }
        return count
    }
}

private fun t(n: Int, vararg ms: Int) = Timing(n, ms)

/** Flottement continu, en cases : aller-retour sur [ms], forme [ease]. */
class Breath(val ms: Int, val amp: Float, private val ease: Ease) {
    enum class Ease { Linear, Sine, Step }

    fun at(elapsed: Long): Float {
        val ph = (elapsed % (2L * ms)).toFloat() / ms
        val tri = if (ph < 1f) ph else 2f - ph
        val e = when (ease) {
            Ease.Sine -> (1f - cos(PI.toFloat() * tri)) / 2f
            Ease.Step -> if (tri > 0.5f) 1f else 0f
            Ease.Linear -> tri
        }
        return e * amp
    }
}

/**
 * Repères d'un personnage dans sa grille 16×16 (pieds en y=13) : [cx] centre,
 * [top] haut du corps, [crown] pixel le plus haut, [hold] hauteur de prise,
 * [halfW] demi-largeur, [eye] hauteur des yeux.
 */
class Anchor(val cx: Double, val top: Int, val crown: Int, val hold: Int, val halfW: Int, val eye: Int)

/** Main posée par une scène : forme décrite pour la droite, miroir à gauche. */
class Hand(val shape: IntArray, val role: Char)

/** Bras articulés, tracés de l'épaule à la main. */
class Arm(val l: IntArray, val r: IntArray, val role: Char)

/**
 * Les personnages au choix. Chacun se dessine sur une grille 16×16 par rôles de
 * pixel ; [MascotState] se lit dans l'expression et le mouvement, `f` avance dans
 * le cycle de l'état. [tempo] étire les scènes, [breath] porte le flottement.
 */
enum class MascotKind(
    val label: String,
    /** D'où vient le nom, en une phrase. */
    val origin: String,
    val anchor: Anchor,
    val breath: Breath,
    val tempo: Double,
    val hand: Hand,
    private val timings: List<Timing>,
    val arm: Arm? = null,
) {
    Kairos(
        "Kairos", "Kairos, l'instant opportun en grec, l'occasion à saisir.",
        Anchor(7.5, 5, 1, 10, 5, 8), Breath(1800, 0.5f, Breath.Ease.Linear), 1.0,
        Hand(xy(0, 0, 1, 0), 'h'),
        listOf(t(20, 200), t(4, 320), t(4, 420), t(4, 170), t(4, 480)),
    ) {
        override fun draw(p: Pen, pose: Pose, f: Int) {
            val st = pose.state
            val odd = f % 2 == 1
            val q = p.shift(0, if (st == MascotState.Agit && odd) -1 else 0)
            q.rect(3, 5, 10, 1, 'h'); q.rect(3, 6, 10, 7, 'F')
            // Mèche en avant ; en attente, sa pointe oscille.
            val sway = st == MascotState.Attend && odd
            q.rect(6, 3, 3, 2, 'F'); q.rect(if (sway) 9 else 8, 2, 3, 1, 'F'); q.rect(if (sway) 11 else 10, 1, 2, 1, 'F')
            val auto = when (st) {
                MascotState.Ecoute -> Eye.Wide
                MascotState.Reflechit -> Eye.Focus
                MascotState.Attend -> Eye.Low
                else -> Eye.Open
            }
            drawEyes(q, EyeRig(5, 8, 9, 8, 2, 2), eyeMode(pose, auto.both(), st == MascotState.Repos && f % 20 == 19), pose.look)
            if (!pose.sit) { q.rect(4, 13, 2, 1, 'F'); q.rect(10, 13, 2, 1, 'F') }
            // Petites ailes ; en action, elles battent.
            val wy = if (st == MascotState.Agit && odd) 11 else 12
            if (pose.handL == null) q.rect(2, wy, 2, 1, 'h')
            if (pose.handR == null) q.rect(12, wy, 2, 1, 'h')
            if (st == MascotState.Ecoute) {
                if (odd) { q.rect(0, 6, 1, 6, 'm'); q.rect(15, 6, 1, 6, 'm') } else { q.rect(1, 7, 1, 4, 'm'); q.rect(14, 7, 1, 4, 'm') }
            }
            if (st == MascotState.Reflechit) bubbles(q, xy(13, 4, 14, 2, 15, 0), f % 4)
        }
    },

    /** Caillou de rivière, dessus plat, légèrement de biais ; lignes [x début, x fin], du bas vers le haut. */
    Lithos(
        "Lithos", "Lithos, « la pierre » en grec.",
        Anchor(7.5, 6, 3, 11, 6, 9), Breath(2600, 0.35f, Breath.Ease.Sine), 1.3,
        Hand(xy(0, 0, 1, 0), 'h'),
        listOf(t(12, 520), t(4, 420), t(6, 600), t(6, 140, 120, 160, 160, 120, 260), t(8, 640)),
    ) {
        override fun draw(p: Pen, pose: Pose, f: Int) {
            val st = pose.state
            val odd = f % 2 == 1
            var shape = GALET_N
            var hop = 0
            var sprout = SPROUT_REST
            var look = pose.look
            var auto = Eye.Open
            var blink = false
            when (st) {
                MascotState.Repos -> {
                    shape = if (f == 9) GALET_S else GALET_N
                    sprout = if (f % 12 < 6) SPROUT_REST else if (f % 12 < 9) SPROUT_SWAY_L else SPROUT_REST
                    blink = f % 12 == 5 || f % 12 == 11 && f % 24 == 23
                }
                MascotState.Ecoute -> { auto = Eye.Wide; sprout = SPROUT_UP }
                MascotState.Reflechit -> { auto = Eye.Focus; look = look ?: xy(0, -1); sprout = if (odd) SPROUT_SWAY_L else SPROUT_SWAY_R }
                MascotState.Agit -> {
                    shape = listOf(GALET_S, GALET_T, GALET_N, GALET_N, GALET_T, GALET_S)[f % 6]
                    hop = intArrayOf(0, -2, -3, -3, -1, 0)[f % 6]
                    auto = if (hop < 0) Eye.Happy else Eye.Open
                    sprout = if (hop < -1) SPROUT_SWAY_R else SPROUT_UP
                }
                MascotState.Attend -> { auto = Eye.Low; sprout = SPROUT_DROOP; look = look ?: LOOK_ATTEND_8[f % 8] }
            }
            val q = p.shift(0, hop)
            val bottom = 13
            val top = bottom - shape.size + 1
            shape.forEachIndexed { i, r -> q.rect(r[0], bottom - i, r[1] - r[0] + 1, 1, if (i == 0) 'h' else 'F') }
            // Face ombrée en bas à droite et grain de la pierre.
            q.rect(shape[1][1] - 4, bottom - 1, 5, 1, 'h'); q.pts(xy(shape[2][1], bottom - 2, shape[3][1], bottom - 3), 'h')
            q.pts(xy(9, top + 1, 6, bottom - 1), 'm')
            q.pts(sprout, 'A', 0, top)
            drawEyes(q, EyeRig(4, top + 3, 10, top + 3, 2, 2), eyeMode(pose, auto.both(), blink), look)
            if (st == MascotState.Ecoute) {
                if (odd) { q.rect(-1, top + 1, 1, 6, 'm'); q.rect(16, top + 1, 1, 6, 'm') } else { q.rect(0, top + 2, 1, 4, 'm'); q.rect(15, top + 2, 1, 4, 'm') }
            }
            if (st == MascotState.Reflechit) bubbles(q, xy(13, 4, 14, 2, 15, 0), f % 4)
        }
    },

    /** Chouette à aigrettes, regard qui pivote. */
    Glaux(
        "Glaux", "Glaux, la chouette d'Athéna en grec ancien.",
        Anchor(7.5, 4, 2, 10, 6, 7), Breath(2200, 0.4f, Breath.Ease.Sine), 1.15,
        Hand(xy(0, 0, 0, 1), 'h'),
        listOf(t(16, 360, 360, 360, 360, 360, 360, 360, 360, 360, 360, 360, 360, 360, 360, 240, 360), t(4, 320), t(8, 420), t(4, 170), t(8, 520)),
    ) {
        override fun draw(p: Pen, pose: Pose, f: Int) {
            val st = pose.state
            val odd = f % 2 == 1
            var look = pose.look
            var auto = Eye.Open.both()
            var blink = false
            var hop = 0
            var flap = false
            var perk = false
            when (st) {
                MascotState.Repos -> { look = look ?: LOOK_REPOS_16[f % 16]; blink = f % 16 == 14 || f % 16 == 8 && f % 32 == 8 }
                MascotState.Ecoute -> { auto = Eye.Wide.both(); perk = true }
                // Clin d'œil d'un seul côté.
                MascotState.Reflechit -> { auto = if (f % 8 < 4) Eye.Focus to Eye.Open else Eye.Focus to Eye.Wide; look = look ?: xy(1, -1) }
                MascotState.Agit -> { hop = if (odd) -1 else 0; flap = odd; auto = Eye.Happy.both(); perk = true }
                MascotState.Attend -> { look = look ?: if (f % 8 < 4) xy(-1, 0) else xy(1, 0); blink = f % 4 == 3 }
            }
            val q = p.shift(0, hop)
            q.rows(HULOTTE_BODY, 0, 0)
            if (perk) { q.set(1, 1, 'F'); q.set(14, 1, 'F'); q.set(0, 1, 'F'); q.set(15, 1, 'F') }
            val lx = look?.get(0) ?: 0
            // Sourcils en V : ils suivent le regard.
            q.pts(xy(3, 5, 4, 5, 5, 6), 'h', lx, 0); q.pts(xy(12, 5, 11, 5, 10, 6), 'h', lx, 0)
            drawEyes(q, EyeRig(4, 7, 10, 7, 2, 2), eyeMode(pose, auto, blink), look)
            q.pts(xy(7, 8, 8, 8, 7, 9, 8, 9), 'A', lx, look?.let { maxOf(0, it[1]) } ?: 0)
            if (!pose.sit) q.pts(xy(5, 13, 6, 13, 9, 13, 10, 13), 'A')
            if (flap) {
                if (pose.handL == null) q.pts(xy(1, 8, 0, 7, 1, 9), 'h')
                if (pose.handR == null) q.pts(xy(14, 8, 15, 7, 14, 9), 'h')
            }
            if (st == MascotState.Ecoute) {
                if (odd) { q.rect(0, 5, 1, 6, 'm'); q.rect(15, 5, 1, 6, 'm') } else { q.rect(1, 6, 1, 4, 'm'); q.rect(14, 6, 1, 4, 'm') }
            }
            if (st == MascotState.Reflechit) bubbles(q, xy(13, 4, 14, 2, 15, 0), f % 4)
        }
    },

    /** Petit robot à visière, antenne décentrée, bras articulés. */
    Talos(
        "Talos", "Talos, l'automate de bronze qui veillait sur la Crète.",
        Anchor(7.5, 3, 0, 11, 5, 5), Breath(1600, 0.25f, Breath.Ease.Step), 1.0,
        Hand(xy(0, 0), 'F'),
        listOf(t(8, 260), t(4, 300), t(6, 240), t(4, 160), t(8, 420)),
        Arm(xy(3, 10), xy(12, 10), 'h'),
    ) {
        override fun draw(p: Pen, pose: Pose, f: Int) {
            val st = pose.state
            val odd = f % 2 == 1
            var auto = Eye.Open
            var look = pose.look
            var blink = false
            var bulb = true
            var armUp: Char? = null
            when (st) {
                MascotState.Repos -> { bulb = f % 8 < 4; blink = f % 8 == 7 }
                MascotState.Ecoute -> auto = Eye.Wide
                MascotState.Agit -> { auto = Eye.Happy; armUp = if (odd) 'R' else 'L'; bulb = odd }
                MascotState.Attend -> { look = look ?: LOOK_TALOS_8[f % 8]; bulb = f % 8 < 2 }
                MascotState.Reflechit -> Unit
            }
            p.set(10, 1, 'h'); p.set(10, 2, 'h'); p.set(10, 0, if (bulb) 'A' else 'm')
            p.rows(BOULON_BODY, 0, 0)
            // En réflexion, la visière affiche un chargement à la place des yeux.
            if (st == MascotState.Reflechit) intArrayOf(4, 7, 10).forEachIndexed { i, x -> p.rect(x, 6, 2, 1, if (i == f % 3) 'A' else 'a') }
            else drawEyes(p, EyeRig(5, 5, 9, 5, 2, 2, 'A'), eyeMode(pose, auto.both(), blink), look)
            if (!pose.sit) { p.rect(5, 13, 2, 1, 'h'); p.rect(9, 13, 2, 1, 'h') }
            if (pose.handL == null) {
                if (armUp == 'L') { p.pts(xy(3, 10, 2, 9, 2, 8), 'h'); p.set(2, 7, 'F') } else { p.pts(xy(3, 10, 2, 11), 'h'); p.set(2, 12, 'F') }
            }
            if (pose.handR == null) {
                if (armUp == 'R') { p.pts(xy(12, 10, 13, 9, 13, 8), 'h'); p.set(13, 7, 'F') } else { p.pts(xy(12, 10, 13, 11), 'h'); p.set(13, 12, 'F') }
            }
            if (st == MascotState.Ecoute) {
                if (odd) { p.pts(xy(8, 0, 12, 0), 'm'); p.pts(xy(8, 1, 12, 1), 'm') } else p.pts(xy(9, 0, 11, 0), 'm')
            }
        }
    },

    /** Souris minuscule, oreilles rondes, queue vive. */
    Tosk(
        "Tosk", "Tosk, de Ratatosk, le messager qui court sans cesse le long de l'arbre du monde nordique.",
        Anchor(6.5, 6, 4, 10, 5, 8), Breath(900, 0.3f, Breath.Ease.Sine), 0.75,
        Hand(xy(0, 0), 'F'),
        listOf(
            t(12, 140, 140, 90, 90, 140, 420, 120, 120, 300, 140, 110, 500), t(4, 220), t(6, 160, 160, 160, 160, 420, 420),
            t(4, 110), t(8, 200, 200, 140, 140, 200, 200, 600, 140),
        ),
    ) {
        override fun draw(p: Pen, pose: Pose, f: Int) {
            val st = pose.state
            val odd = f % 2 == 1
            var auto = Eye.Open
            var look = pose.look
            var blink = false
            var tail = TAIL_A
            var twitch = false
            var earUp = false
            var flick = false
            var hop = 0
            var scratch = false
            var tap = false
            when (st) {
                MascotState.Repos -> {
                    val k = f % 12
                    twitch = k == 2; tail = if (k == 6) TAIL_B else if (k == 7) TAIL_C else TAIL_A; flick = k == 8; blink = k == 10
                }
                MascotState.Ecoute -> { auto = Eye.Wide; earUp = true; tail = if (odd) TAIL_B else TAIL_A }
                MascotState.Reflechit -> { look = look ?: xy(-1, -1); scratch = f % 6 < 4; tail = TAIL_B }
                MascotState.Agit -> { hop = intArrayOf(0, -2, -1, 0)[f % 4]; auto = Eye.Happy; tail = TAIL_C; twitch = odd }
                MascotState.Attend -> { look = look ?: LOOK_TOSK_8[f % 8]; tap = odd }
            }
            val q = p.shift(0, hop)
            q.rows(PEPIN_BODY, 0, 0)
            if (earUp) q.pts(xy(2, 3, 3, 3, 10, 3, 11, 3), 'F')
            if (flick) q.pts(xy(2, 5, 3, 5), 'F')
            q.pts(tail, 'F')
            drawEyes(q, EyeRig(4, 8, 9, 8, 1, 2), eyeMode(pose, auto.both(), blink), look)
            // Le nez frétille.
            q.pts(if (twitch) xy(6, 9, 7, 9) else xy(6, 10, 7, 10), 'A')
            if (!pose.sit) { q.set(4, 13, 'F'); q.set(if (tap) 8 else 9, if (tap) 12 else 13, 'F') }
            if (pose.handL == null) q.set(5, 11, 'h')
            if (pose.handR == null) {
                if (scratch) { q.set(if (odd) 13 else 12, if (odd) 6 else 7, 'F'); q.set(12, 8, 'h') } else q.set(8, 11, 'h')
            }
            if (st == MascotState.Reflechit) bubbles(q, xy(13, 4, 14, 2, 15, 0), f % 4)
        }
    },

    /** Petite flamme qui ne touche jamais le sol. */
    Logi(
        "Logi", "Logi, le feu en personne dans les sagas nordiques.",
        Anchor(7.5, 5, 2, 10, 5, 8), Breath(1300, 1f, Breath.Ease.Sine), 0.85,
        Hand(xy(0, 0, 0, 1), 'h'),
        listOf(t(8, 150), t(4, 220), t(6, 360), t(4, 120), t(8, 420)),
    ) {
        override fun draw(p: Pen, pose: Pose, f: Int) {
            val st = pose.state
            val odd = f % 2 == 1
            var tip = TIP_FLICKER[f % 8]
            var auto = Eye.Open
            var look = pose.look
            var blink = false
            var hop = 0
            var handY = if (odd) 10 else 9
            var spark: IntArray? = null
            when (st) {
                MascotState.Repos -> { blink = f % 16 == 13; spark = if (f % 8 == 5) xy(13, 3) else null }
                MascotState.Ecoute -> { tip = if (odd) TIP_T else TIP_C; auto = Eye.Wide; handY = 8 }
                MascotState.Reflechit -> { tip = TIP_S; auto = Eye.Focus; look = look ?: xy(0, -1) }
                MascotState.Agit -> {
                    tip = listOf(TIP_T, TIP_R, TIP_T, TIP_L)[f % 4]; hop = if (odd) -1 else 0; auto = Eye.Happy
                    handY = if (odd) 6 else 7; spark = if (odd) xy(1, 3, 14, 4) else xy(2, 5, 13, 2)
                }
                MascotState.Attend -> { tip = if (f % 8 < 4) TIP_S else TIP_C; auto = Eye.Low; handY = 11 }
            }
            val q = p.shift(0, hop)
            // Langues du haut, puis corps à base couleur braise.
            q.rows(tip, 0, 0); q.rows(FLAME_LOW, 0, 7)
            q.set(if (odd) 6 else 9, 14, 'A')
            drawEyes(q, EyeRig(5, 8, 9, 8, 2, 2), eyeMode(pose, auto.both(), blink), look)
            if (pose.handL == null) q.rect(1, handY, 1, 2, 'h')
            if (pose.handR == null) q.rect(14, if (st == MascotState.Repos) (if (odd) 9 else 10) else handY, 1, 2, 'h')
            spark?.let { q.pts(it, 'A') }
            if (st == MascotState.Reflechit) bubbles(q, xy(9, 3 - f % 2, 10, 1 - f % 2, 11, -1 - f % 2), 1 + f % 3)
        }
    },
    ;

    /** Dessine le personnage avec [p] ; [pose] porte l'état et ce qu'impose une scène. */
    abstract fun draw(p: Pen, pose: Pose, f: Int)

    fun timing(state: MascotState): Timing = timings[state.ordinal]

    /** Image seule, entourée de [pad] cases pour les ondes et les aigrettes qui débordent. */
    fun frame(state: MascotState, f: Int, pad: Int = 0): Grid =
        Grid(16 + 2 * pad).also { draw(Pen(it, pad, pad), Pose(state = state), f) }
}

private fun spans(vararg r: Int) = List(r.size / 2) { xy(r[2 * it], r[2 * it + 1]) }
private val GALET_N = spans(4, 12, 2, 13, 2, 13, 2, 13, 2, 13, 2, 13, 2, 12, 4, 10)
private val GALET_S = spans(3, 12, 1, 14, 1, 14, 1, 14, 1, 14, 2, 13, 4, 11)
private val GALET_T = spans(4, 11, 3, 12, 3, 12, 3, 12, 3, 12, 3, 12, 3, 12, 3, 11, 5, 10)
private val SPROUT_REST = xy(8, -1, 7, -2, 9, -2)
private val SPROUT_SWAY_L = xy(8, -1, 7, -2, 6, -3)
private val SPROUT_SWAY_R = xy(8, -1, 9, -2, 10, -3)
private val SPROUT_UP = xy(8, -1, 8, -2, 7, -3, 9, -3)
private val SPROUT_DROOP = xy(8, -1, 7, -1, 9, -1)
private val LOOK_ATTEND_8 = listOf(xy(-1, 0), xy(-1, 0), xy(0, 0), xy(0, 0), xy(1, 0), xy(1, 0), xy(0, 0), xy(0, 0))
private val LOOK_REPOS_16 = listOf(
    xy(0, 0), xy(0, 0), xy(0, 0), xy(0, 0), xy(-1, 0), xy(-1, 0), xy(-1, 0), xy(0, 0),
    xy(0, 0), xy(0, 0), xy(1, 0), xy(1, 0), xy(1, 0), xy(0, 0), xy(0, 0), xy(0, 0),
)
private val LOOK_TALOS_8 = listOf(xy(-1, 0), xy(-1, 0), xy(0, 0), xy(1, 0), xy(1, 0), xy(0, 0), xy(0, 0), xy(0, 0))
private val LOOK_TOSK_8 = listOf(xy(-1, 0), xy(-1, 0), xy(1, 0), xy(1, 0), xy(0, 0), xy(0, 0), xy(0, 0), xy(0, 0))

private val HULOTTE_BODY = listOf(
    "................",
    "................",
    ".F............F.",
    "..FF........FF..",
    "..FFFFFFFFFFFF..",
    "..hFFFFFFFFFFh..",
    "..hFFFFFFFFFFh..",
    "..hFFFFFFFFFFh..",
    "..hFFFFFFFFFFh..",
    "..hFFFFFFFFFFh..",
    "..hhFFhFFhFFhh..",
    "...hhFFFFFFhh...",
    "....hhFFFFhh....",
)

private val BOULON_BODY = listOf(
    "................",
    "................",
    "................",
    "...hFFFFFFFFFh..",
    "..hFDDDDDDDDFh..",
    "..hFDDDDDDDDFh..",
    "...FDDDDDDDDF...",
    "...FDDDDDDDDF...",
    "...FFFFFFFFFF...",
    "......hhhh......",
    "....FFFFFFFF....",
    "....FFFmmFFF....",
    "....hFFFFFFh....",
)

private val PEPIN_BODY = listOf(
    "................",
    "................",
    "................",
    "................",
    "..FF......FF....",
    ".FhhF....FhhF...",
    ".FhhFFFFFFhhF...",
    "..FFFFFFFFFF....",
    "..FFFFFFFFFF....",
    "..FFFFFFFFFF....",
    "...FFFFFFFF.....",
    "...FFFFFFFF.....",
    "....FFFFFF......",
)

private val TAIL_A = xy(10, 12, 11, 12, 12, 12, 13, 11, 14, 10, 14, 9)
private val TAIL_B = xy(10, 12, 11, 12, 12, 11, 13, 10, 13, 9, 12, 8)
private val TAIL_C = xy(10, 12, 11, 12, 12, 12, 13, 12, 14, 11, 15, 10)

/** Corps de la flamme (y 7 à 13, base braise) et langues du haut (y 0 à 6), qui changent à chaque image. */
private val FLAME_LOW = listOf(
    "....FFFFFFFF....",
    "...FFFFFFFFFF...",
    "...FFFFFFFFFF...",
    "...AFFFFFFFFA...",
    "...AAFFFFFFAA...",
    "....AAAAAAAA....",
    ".....aaaaaa.....",
)
private val TIP_C = listOf(".........F......", "........FF......", ".......FFF......", "....F..FFF......", "....F.FFFFF.....", "....FFFFFFF.F...", "....FFFFFFFFF...")
private val TIP_R = listOf("..........F.....", ".........FF.....", "........FFF.....", "...F....FF......", "...F...FFFF.....", "....F.FFFFF.....", "....FFFFFFFF....")
private val TIP_L = listOf("......F.........", "......FF........", "......FFF.......", "......FFF..F....", ".....FFFFF.F....", "...F.FFFFFFF....", "...FFFFFFFFF....")
private val TIP_T = listOf(".......F........", ".......FF.......", "......FFF.......", "....F.FFFF.F....", "....F.FFFF.F....", "....FFFFFFFF....", "....FFFFFFFF....")
private val TIP_S = listOf("................", "................", "................", ".......F........", ".......FF.......", "....F.FFF.F.....", "....FFFFFFFF....")
private val TIP_FLICKER = listOf(TIP_C, TIP_R, TIP_C, TIP_L, TIP_C, TIP_R, TIP_C, TIP_L)

/** État du personnage en mots, pour le lecteur d'écran (le dessin seul ne se lit pas). */
fun mascotDescription(state: MascotState, decisions: Int): String = when (state) {
    MascotState.Repos -> "au repos"
    MascotState.Ecoute -> "à l'écoute"
    MascotState.Reflechit -> "réfléchit"
    MascotState.Agit -> "réponse arrivée"
    MascotState.Attend -> if (decisions == 1) "1 décision en attente" else "$decisions décisions en attente"
}
