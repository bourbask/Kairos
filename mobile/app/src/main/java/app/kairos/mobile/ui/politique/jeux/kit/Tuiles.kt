package app.kairos.mobile.ui.politique.jeux.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.politique.jeux.Coque
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Rectangle aux coins en marches : [steps] marches de [step] (pixel art). */
class PixelShape(private val step: Dp = 3.dp, private val steps: Int = 2) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { step.toPx().roundToInt().coerceAtLeast(1).toFloat() }
        val w = size.width
        val h = size.height
        // Coin haut gauche en marches, puis ses miroirs, sens horaire.
        val tl = listOf(0f to steps * s) + (1..steps).flatMap { i -> listOf(i * s to (steps - i + 1) * s, i * s to (steps - i) * s) }
        val pts = tl + tl.reversed().map { (x, y) -> w - x to y } + tl.map { (x, y) -> w - x to h - y } + tl.reversed().map { (x, y) -> x to h - y }
        val p = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        p.close()
        return Outline.Generic(p)
    }
}

private fun DrawScope.outlinePath(shape: Shape, size: Size = this.size): Path =
    (shape.createOutline(size, layoutDirection, this) as Outline.Generic).path

/**
 * Glisser-déposer des tuiles dans les emplacements d'une manette, avec l'alternative appui-appui (toucher la tuile,
 * puis l'emplacement). [deposer] renvoie false pour refuser : la tuile revient, l'emplacement tremble.
 */
@Stable
class GlisserDeposer internal constructor(private val coque: Coque, deposer: (tuile: String, cible: String) -> Boolean) {
    internal var deposer = deposer
    /** Tuile saisie par appui (alternative au glisser). */
    var selection by mutableStateOf<String?>(null)
        internal set
    /** Emplacement survolé par la tuile tenue. */
    var survol by mutableStateOf<String?>(null)
        internal set
    internal val cibles = HashMap<String, Rect>()
    internal val refus = mutableStateMapOf<String, Int>()

    internal fun poser(tuile: String, cible: String): Boolean {
        selection = null
        val ok = deposer(tuile, cible)
        if (!ok) refus[cible] = (refus[cible] ?: 0) + 1 else coque.cran()
        return ok
    }

    internal fun saisir(tuile: String) {
        selection = if (selection == tuile) null else tuile
        coque.annoncer(if (selection != null) "Saisi. Choisissez un emplacement." else "Reposé.")
    }

    internal fun annoncerVide() = coque.annoncer("Touchez d'abord une tuile.")

    internal fun cibleSous(p: Offset, marge: Float): String? =
        cibles.entries.firstOrNull { (_, r) -> p.x > r.left && p.x < r.right && p.y > r.top - marge && p.y < r.bottom + marge }?.key
}

@Composable
fun rememberGlisserDeposer(coque: Coque, deposer: (tuile: String, cible: String) -> Boolean): GlisserDeposer =
    remember(coque) { GlisserDeposer(coque, deposer) }.also { it.deposer = deposer }

/** Hauteur d'une tuile : dessus 48 dp, flanc 9 dp. */
val TUILE_HAUTEUR = 57.dp

/** Dessin d'une tuile en relief ([assise] : logée, flanc réduit, sans ombre). */
private fun DrawScope.tuile(kc: Color, ink: Color, ombre: Color, lift: Float, assise: Boolean) {
    val c = 3.dp.toPx().roundToInt().toFloat()
    val face = 48.dp.toPx().roundToInt().toFloat()
    val shape = PixelShape()
    val path = outlinePath(shape)
    if (!assise) translate(top = (if (lift > 0) 9.dp.toPx() else c)) { drawPath(path, ombre) }
    clipPath(path) {
        drawRect(mix(kc, ink, 0.32f))
        drawRect(kc, size = Size(size.width, face))
        drawRect(mix(kc, Color.White, 0.65f), size = Size(size.width, c))
        drawRect(mix(kc, Color.White, 0.65f), size = Size(c, face))
        drawRect(mix(kc, ink, 0.16f), Offset(0f, face - c), Size(size.width, c))
        drawRect(mix(kc, ink, 0.16f), Offset(size.width - c, 0f), Size(c, face))
        drawRect(Color.White.copy(alpha = 0.7f), Offset(3 * c, 2 * c), Size(3 * c, c))
    }
}

/**
 * Tuile en relief à attraper : se soulève au glisser, se loge dans un [Emplacement] ; appui = saisie (alternative).
 * [label] : lu par le lecteur d'écran ; [content] : texte de la tuile (dans les 48 dp du dessus).
 */
@Composable
fun Tuile(
    id: String,
    gd: GlisserDeposer,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    couleur: Color = MaterialTheme.colorScheme.tertiaryContainer,
    content: @Composable BoxScope.() -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val ombre = Teintes.ombre
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var lifted by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val sel = gd.selection == id
    val en by rememberUpdatedState(enabled)
    Box(
        modifier.fillMaxWidth().height(TUILE_HAUTEUR)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                translationX = off.value.x
                translationY = off.value.y - if (lifted || sel) 6.dp.toPx() else 0f
                val k = if (lifted) 0.92f else 1f
                scaleX = k; scaleY = k
            }
            .drawBehind { tuile(couleur, ink, ombre, if (lifted || sel) 1f else 0f, false) }
            .pointerInput(id) {
                val marge = with(density) { 8.dp.toPx() }
                detectDragGestures(
                    onDragStart = { if (en) lifted = true },
                    onDrag = { ch, d ->
                        if (!en) return@detectDragGestures
                        ch.consume()
                        scope.launch { off.snapTo(off.value + d) }
                        gd.survol = gd.cibleSous(bounds.center + off.value + d, marge)
                    },
                    onDragEnd = {
                        val hot = gd.survol
                        gd.survol = null
                        lifted = false
                        val ok = hot != null && gd.poser(id, hot)
                        scope.launch { if (ok) off.snapTo(Offset.Zero) else off.animateTo(Offset.Zero, tween(250, easing = app.kairos.mobile.ui.theme.Motion.EmphasizedDecelerate)) }
                    },
                    onDragCancel = { gd.survol = null; lifted = false; scope.launch { off.animateTo(Offset.Zero) } },
                )
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Button, onClickLabel = if (sel) "Reposer" else "Saisir") { gd.saisir(id) }
            .semantics { contentDescription = label + if (sel) ", saisi : choisissez un emplacement" else ""; selected = sel },
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart, content = content)
    }
}

/**
 * Emplacement en creux : s'éclaire au survol d'une tuile, tremble au refus ; appui avec une tuile saisie = dépôt.
 * [plein] : contenu de la tuile logée (tuile assise, plus d'interaction) ; sinon [content] (étiquette en creux).
 */
@Composable
fun Emplacement(
    id: String,
    gd: GlisserDeposer,
    label: String,
    modifier: Modifier = Modifier,
    plein: (@Composable BoxScope.() -> Unit)? = null,
    couleurPleine: Color = MaterialTheme.colorScheme.tertiaryContainer,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val sc = MaterialTheme.colorScheme
    val hot = gd.survol == id
    val shake = remember { Animatable(0f) }
    val refus = gd.refus[id] ?: 0
    LaunchedEffect(refus) {
        if (refus > 0) shake.animateTo(0f, keyframes { durationMillis = 350; -5f at 87; 5f at 175; -3f at 262 })
    }
    val bg = if (hot) mix(sc.surface, sc.primary, 0.34f) else mix(sc.surface, sc.onSurface, 0.1f)
    val dark = if (hot) mix(sc.primary, Color.Black, 0.2f) else mix(sc.surface, sc.onSurface, 0.28f)
    val light = if (hot) mix(sc.primary, Color.White, 0.45f) else mix(sc.surface, Color.White, 0.55f)
    val ink = sc.onSurface
    Box(
        modifier.fillMaxWidth().height(TUILE_HAUTEUR)
            .onGloballyPositioned { if (plein == null) gd.cibles[id] = it.boundsInRoot() else gd.cibles.remove(id) }
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .drawBehind {
                val c = 3.dp.toPx().roundToInt().toFloat()
                clipPath(outlinePath(PixelShape(steps = 1))) {
                    drawRect(bg)
                    drawRect(dark, size = Size(size.width, c)); drawRect(dark, size = Size(c, size.height))
                    drawRect(light, Offset(0f, size.height - c), Size(size.width, c)); drawRect(light, Offset(size.width - c, 0f), Size(c, size.height))
                }
            }
            .let { m ->
                if (plein != null) m.semantics { contentDescription = label }
                else m.clickable(role = Role.Button, onClickLabel = "Déposer ici") {
                    val t = gd.selection
                    if (t != null) gd.poser(t, id) else gd.annoncerVide()
                }.semantics { contentDescription = label }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (plein != null) Box(Modifier.padding(3.dp).fillMaxWidth().height(51.dp).drawBehind { tuile(couleurPleine, ink, Color.Transparent, 0f, true) }, contentAlignment = Alignment.TopStart) {
            Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart, content = plein)
        }
        else content()
    }
}

