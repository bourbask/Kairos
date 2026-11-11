package app.kairos.mobile.ui.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import app.kairos.mobile.ui.theme.KairosColors
import app.kairos.mobile.ui.theme.SceneColors
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Personnage choisi dans les réglages. */
val LocalMascot = staticCompositionLocalOf { MascotKind.Kairos }

/** Marge autour du personnage seul : ondes et aigrettes peuvent déborder de sa grille. */
private const val PAD = 2

/**
 * Images déjà calculées, par personnage, état ou scène, et image : une
 * recomposition ne reconstruit pas de grille. Chaque case est empaquetée en
 * x | y << 8 | rôle << 16.
 */
internal object SpriteCache {
    private val mascots = ConcurrentHashMap<Int, IntArray>()
    private val scenes = ConcurrentHashMap<Long, Array<IntArray>>()

    fun mascot(kind: MascotKind, state: MascotState, f: Int): IntArray =
        mascots.getOrPut((kind.ordinal * 8 + state.ordinal) * 64 + f) { pack(kind.frame(state, f, PAD)) }

    fun scene(scene: SceneKind, down: Boolean, kind: MascotKind, i: Int, t: Int): Array<IntArray> {
        val key = (((scene.ordinal * 2L + (if (down) 1 else 0)) * 8 + kind.ordinal) * 16 + i) * 64 + t
        return scenes.getOrPut(key) { scene.frame(kind, i, t, down).let { arrayOf(pack(it.back), pack(it.mascot), pack(it.front)) } }
    }

    private fun pack(g: Grid): IntArray {
        val out = ArrayList<Int>()
        for (y in 0 until g.n) for (x in 0 until g.n) {
            val c = g[x, y]
            if (c != Grid.EMPTY) out += x or (y shl 8) or (SceneColors.ROLES.indexOf(c) shl 16)
        }
        return out.toIntArray()
    }
}

/**
 * Période du compteur de repos dans une scène : deux cycles couvrent tous les
 * motifs du personnage (clignements une fois sur deux cycles compris).
 */
internal fun MascotKind.restPeriod(): Int = 2 * timing(MascotState.Repos).n

/** Dessine des cases empaquetées ; [origin] = case placée au coin du canevas, [dy] en pixels. */
private fun DrawScope.cells(packed: IntArray, cell: Float, origin: Int, dy: Float, colors: SceneColors) {
    // Bords arrondis au pixel de l'écran : cases jointives, sans liseré.
    fun edge(i: Int) = ((i - origin) * cell).roundToInt().toFloat()
    for (v in packed) {
        val x = v and 0xFF
        val y = (v shr 8) and 0xFF
        val left = edge(x)
        val top = edge(y)
        drawRect(colors.byRole[v shr 16], Offset(left, top + dy), Size(edge(x + 1) - left, edge(y + 1) - top))
    }
}

/**
 * Le personnage, dessiné case par case. [size] couvre sa grille 16×16 ; ondes et
 * aigrettes peuvent déborder de deux cases. [colors] : teintes du bouton Kairos par défaut.
 */
@Composable
fun Mascot(
    state: MascotState,
    size: Dp,
    modifier: Modifier = Modifier,
    kind: MascotKind = LocalMascot.current,
    colors: SceneColors = KairosColors.current.scene,
) {
    val timing = kind.timing(state)
    var frame by remember(kind, state) { mutableIntStateOf(0) }
    var lift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(kind, state) {
        val motion = coroutineContext[MotionDurationScale]
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now ->
            // Animations retirées dans les réglages du système : le personnage reste immobile.
            val still = motion?.scaleFactor == 0f
            frame = if (still) 0 else (timing.countAt(now - start) % timing.n).toInt()
            lift = if (still) 0f else kind.breath.at(now)
        }
    }
    val pixels = SpriteCache.mascot(kind, state, frame)
    Canvas(modifier.size(size)) {
        val cell = this.size.minDimension / 16
        cells(pixels, cell, PAD, -(lift * cell).roundToInt().toFloat(), colors)
    }
}

/**
 * Le personnage joue la scène d'un espace ouvert, sur toute la surface : décor,
 * personnage qui respire, premier plan. [down] : variante en baisse (Marchés).
 * Sans animation, l'image clé reste affichée.
 */
@Composable
fun MascotScene(
    scene: SceneKind,
    down: Boolean,
    modifier: Modifier = Modifier,
    kind: MascotKind = LocalMascot.current,
    colors: SceneColors = KairosColors.current.scene,
) {
    var frame by remember(scene, kind) { mutableIntStateOf(scene.key) }
    var rest by remember(scene, kind) { mutableLongStateOf(0) }
    var lift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(scene, kind) {
        val motion = coroutineContext[MotionDurationScale]
        val steps = Timing(scene.ms.size, scene.ms)
        val idle = kind.timing(MascotState.Repos)
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now ->
            val still = motion?.scaleFactor == 0f
            val elapsed = now - start
            frame = if (still) scene.key else (steps.countAt(elapsed, kind.tempo) % steps.n).toInt()
            rest = if (still) 0 else idle.countAt(elapsed) % kind.restPeriod()
            lift = if (still) 0f else kind.breath.at(now)
        }
    }
    val layers = SpriteCache.scene(scene, down, kind, frame, rest.toInt())
    Canvas(modifier) {
        val cell = this.size.minDimension / SCENE_SIZE
        cells(layers[0], cell, 0, 0f, colors)
        cells(layers[1], cell, 0, -(lift * cell).roundToInt().toFloat(), colors)
        cells(layers[2], cell, 0, 0f, colors)
    }
}

/** Teintes d'un personnage posé sur le fond de l'écran plutôt que sur le bouton : corps couleur du fond. */
@Composable
fun onPageColors(): SceneColors {
    val bg = MaterialTheme.colorScheme.background
    val s = KairosColors.current.scene
    return remember(bg, s) { SceneColors(bg, bg, s['D'], s['A'], s['R']) }
}
