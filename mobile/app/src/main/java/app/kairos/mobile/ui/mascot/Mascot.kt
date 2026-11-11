package app.kairos.mobile.ui.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.kairos.mobile.ui.theme.KairosColors
import app.kairos.mobile.ui.theme.SceneColors
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Personnage choisi dans les réglages. */
val LocalMascot = staticCompositionLocalOf { MascotKind.Kairos }

/** Marge autour du personnage seul : ondes et aigrettes peuvent déborder de sa grille. */
private const val PAD = 2

/**
 * Images déjà calculées : une recomposition ne reconstruit pas de grille. Chaque
 * case est empaquetée en x | y << 8 | rôle << 16. Personnages seuls : tous gardés
 * (quelques centaines de petites images au plus). Scène : seule la scène en cours
 * est gardée ; décor et premier plan par image, personnage par image et image de repos.
 */
internal object SpriteCache {
    private val mascots = ConcurrentHashMap<Int, IntArray>()

    private var sceneKey = -1
    private val sceneStatics = HashMap<Int, Array<IntArray>>()
    private val sceneMascots = HashMap<Int, IntArray>()

    fun mascot(kind: MascotKind, state: MascotState, f: Int): IntArray =
        mascots.getOrPut((kind.ordinal * 8 + state.ordinal) * 64 + f) { pack(kind.frame(state, f, PAD)) }

    /** Calques décor, personnage, premier plan de l'image [i] ; [t] = image de repos du personnage. */
    @Synchronized
    fun scene(scene: SceneKind, down: Boolean, kind: MascotKind, i: Int, t: Int): Array<IntArray> {
        val key = (scene.ordinal * 2 + (if (down) 1 else 0)) * 8 + kind.ordinal
        if (key != sceneKey) {
            sceneKey = key
            sceneStatics.clear()
            sceneMascots.clear()
        }
        var fr: SceneFrame? = null
        val statics = sceneStatics.getOrPut(i) { scene.frame(kind, i, t, down).also { fr = it }.let { arrayOf(pack(it.back), pack(it.front)) } }
        val mascot = sceneMascots.getOrPut(i * 128 + t) { pack((fr ?: scene.frame(kind, i, t, down)).mascot) }
        return arrayOf(statics[0], mascot, statics[1])
    }

    /** Nombre de calques gardés pour la scène en cours (décor et premier plan, personnage). */
    @Synchronized
    internal fun sceneSizes(): Pair<Int, Int> = sceneStatics.size to sceneMascots.size

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

/**
 * Image de repos passée à la scène : le premier tour tel quel, puis un tour sur
 * [0, période) décalé d'une période. Les motifs cycliques restent identiques ; ce
 * qui n'arrive qu'une fois dans la maquette (l'écrasement de Lithos) n'arrive
 * qu'une fois par ouverture de la scène, sans compteur qui grandit sans fin.
 */
internal fun restFrame(count: Long, period: Int): Long = if (count < period) count else period + count % period

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
    // Décalage du souffle en pixels entiers : redessin seulement quand il change.
    var lift by remember { mutableIntStateOf(0) }
    val cellPx = with(LocalDensity.current) { size.toPx() } / 16
    val cell by rememberUpdatedState(cellPx)
    LaunchedEffect(kind, state) {
        animate(still = { frame = 0; lift = 0 }) { elapsed, now ->
            frame = (timing.countAt(elapsed) % timing.n).toInt()
            lift = (kind.breath.at(now) * cell).roundToInt()
        }
    }
    val pixels = SpriteCache.mascot(kind, state, frame)
    Canvas(modifier.size(size)) {
        cells(pixels, this.size.minDimension / 16, PAD, -lift.toFloat(), colors)
    }
}

/**
 * Boucle d'animation : [tick] à chaque image (temps écoulé, horloge) tant que les
 * animations sont actives. Animations retirées dans les réglages du système :
 * [still] fige l'image, puis la boucle ne vérifie plus qu'une fois par seconde.
 */
internal suspend fun animate(still: () -> Unit, tick: (elapsed: Long, now: Long) -> Unit) {
    val motion = coroutineContext[MotionDurationScale]
    while (true) {
        if (motion?.scaleFactor == 0f) {
            still()
            delay(1_000)
            continue
        }
        val start = withFrameMillis { it }
        while (motion?.scaleFactor != 0f) withFrameMillis { tick(it - start, it) }
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
    var lift by remember { mutableIntStateOf(0) }
    // Taille d'une case, connue au dessin : le souffle s'arrondit au pixel avant d'être écrit.
    val cell = remember { FloatArray(1) }
    LaunchedEffect(scene, kind) {
        val steps = Timing(scene.ms.size, scene.ms)
        val idle = kind.timing(MascotState.Repos)
        animate(still = { frame = scene.key; rest = 0; lift = 0 }) { elapsed, now ->
            frame = (steps.countAt(elapsed, kind.tempo) % steps.n).toInt()
            rest = restFrame(idle.countAt(elapsed), kind.restPeriod())
            lift = (kind.breath.at(now) * cell[0]).roundToInt()
        }
    }
    val layers = SpriteCache.scene(scene, down, kind, frame, rest.toInt())
    Canvas(modifier) {
        val side = this.size.minDimension / SCENE_SIZE
        cell[0] = side
        cells(layers[0], side, 0, 0f, colors)
        cells(layers[1], side, 0, -lift.toFloat(), colors)
        cells(layers[2], side, 0, 0f, colors)
    }
}

/** Teintes d'un personnage posé sur le fond de l'écran plutôt que sur le bouton. */
@Composable
fun onPageColors(): SceneColors = KairosColors.current.page
