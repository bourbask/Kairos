package app.kairos.mobile.ui.politique.jeux.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.ui.composed
import androidx.compose.ui.zIndex
import app.kairos.mobile.ui.theme.Motion
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
 * Glisser-déposer d'une manette, avec l'alternative appui-appui (toucher l'élément, puis la cible) : [Tuile] et
 * [Emplacement], ou toute forme via [glissable] et [cible]. [deposer] renvoie false pour refuser : l'élément revient,
 * la cible tremble. Animations réduites (`coque.reduit`) : ni retour animé ni tremblement.
 */
@Stable
class GlisserDeposer internal constructor(private val coque: Coque, deposer: (tuile: String, cible: String) -> Boolean) {
    internal var deposer = deposer
    /** Élément saisi par appui (alternative au glisser). */
    var selection by mutableStateOf<String?>(null)
        private set
    /** Élément tenu au doigt. */
    var tenue by mutableStateOf<String?>(null)
        private set
    /** Cible survolée par l'élément tenu. */
    var survol by mutableStateOf<String?>(null)
        private set
    /** Refus par cible : compteur, clé de [rememberTremblement]. */
    val refus = mutableStateMapOf<String, Int>()
    val reduit: Boolean get() = coque.reduit

    private val cibles = HashMap<String, Rect>()

    /** Élément [id] levé (tenu ou saisi). */
    fun leve(id: String): Boolean = tenue == id || selection == id

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

    internal fun appuiCible(id: String) {
        val t = selection
        if (t != null) poser(t, id) else coque.annoncer("Touchez d'abord une tuile.")
    }

    internal fun inscrire(id: String, r: Rect?) { if (r == null) cibles.remove(id) else cibles[id] = r }

    internal fun tenir(id: String?) { tenue = id; if (id == null) survol = null }

    internal fun survoler(p: Offset, marge: Float) {
        survol = cibles.entries.firstOrNull { (_, r) -> p.x > r.left && p.x < r.right && p.y > r.top - marge && p.y < r.bottom + marge }?.key
    }
}

@Composable
fun rememberGlisserDeposer(coque: Coque, deposer: (tuile: String, cible: String) -> Boolean): GlisserDeposer =
    remember(coque) { GlisserDeposer(coque, deposer) }.also { it.deposer = deposer }

/**
 * Rend l'élément [id] attrapable : il suit le doigt (soulevé de 6 dp), se dépose sur la cible survolée, revient s'il est
 * refusé ; appui = saisie. Le dessin reste au jeu : `gd.leve(id)` dit s'il est levé, `gd.survol` quelle cible s'éclaire.
 */
fun Modifier.glissable(id: String, gd: GlisserDeposer, label: String, enabled: Boolean = true): Modifier = composed {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val en by rememberUpdatedState(enabled)
    val sel = gd.selection == id
    val retour: suspend () -> Unit = { if (gd.reduit) off.snapTo(Offset.Zero) else off.animateTo(Offset.Zero, tween(250, easing = Motion.EmphasizedDecelerate)) }
    this.zIndex(if (gd.leve(id)) 1f else 0f)
        .onGloballyPositioned { bounds = it.boundsInRoot() }
        .graphicsLayer {
            translationX = off.value.x
            translationY = off.value.y - if (gd.leve(id)) 6.dp.toPx() else 0f
        }
        .pointerInput(id) {
            val marge = with(density) { 8.dp.toPx() }
            detectDragGestures(
                // Un seul élément tenu à la fois : un second doigt n'en vole pas la main.
                onDragStart = { if (en && gd.tenue == null) gd.tenir(id) },
                onDrag = { ch, d ->
                    if (!en || gd.tenue != id) return@detectDragGestures
                    ch.consume()
                    scope.launch { off.snapTo(off.value + d) }
                    gd.survoler(bounds.center + off.value + d, marge)
                },
                onDragEnd = {
                    if (gd.tenue != id) { scope.launch { retour() }; return@detectDragGestures }
                    val hot = gd.survol
                    gd.tenir(null)
                    val ok = hot != null && gd.poser(id, hot)
                    scope.launch { if (ok) off.snapTo(Offset.Zero) else retour() }
                },
                onDragCancel = { if (gd.tenue == id) gd.tenir(null); scope.launch { retour() } },
            )
        }
        .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Button, onClickLabel = if (sel) "Reposer" else "Saisir") { gd.saisir(id) }
        .semantics { contentDescription = label + if (sel) ", saisi : choisissez un emplacement" else ""; selected = sel }
}

/** Cible [id] : reçoit les éléments [glissable] (survol, dépôt, appui avec un élément saisi), tremble au refus. Pleine : [enabled] faux. */
fun Modifier.cible(id: String, gd: GlisserDeposer, label: String, enabled: Boolean = true): Modifier = composed {
    val shake = rememberTremblement(gd.refus[id] ?: 0, gd.reduit)
    this.onGloballyPositioned { gd.inscrire(id, if (enabled) it.boundsInRoot() else null) }
        .tremble(shake)
        .let { m -> if (enabled) m.clickable(role = Role.Button, onClickLabel = "Déposer ici") { gd.appuiCible(id) } else m }
        .semantics { contentDescription = label }
}

/** Tremblement horizontal (dp) rejoué à chaque hausse de [compteur] quand [ici] ; immobile en animations réduites. */
@Composable
fun rememberTremblement(compteur: Int, reduit: Boolean, ici: Boolean = true): Animatable<Float, AnimationVector1D> {
    val a = remember { Animatable(0f) }
    val depart = remember { compteur }
    LaunchedEffect(compteur) {
        if (compteur > depart && ici && !reduit) a.animateTo(0f, keyframes { durationMillis = 350; -5f at 87; 5f at 175; -3f at 262 })
    }
    return a
}

/** Décalage horizontal d'un [rememberTremblement]. */
fun Modifier.tremble(a: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer { translationX = a.value.dp.toPx() }

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
    val haut = gd.leve(id)
    Box(
        modifier.fillMaxWidth().height(TUILE_HAUTEUR)
            .glissable(id, gd, label, enabled)
            .graphicsLayer { val k = if (gd.tenue == id) 0.92f else 1f; scaleX = k; scaleY = k }
            .drawBehind { tuile(couleur, ink, ombre, if (haut) 1f else 0f, false) },
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
    // La tuile se loge : elle descend d'un cran en arrivant (pas au premier affichage d'un emplacement déjà plein).
    val loge = remember { Animatable(0f) }
    var etaitPlein by remember { mutableStateOf(plein != null) }
    LaunchedEffect(plein != null) {
        if (plein != null && !etaitPlein && !gd.reduit) { loge.snapTo(-6f); loge.animateTo(0f, tween(160, easing = { t -> if (t < 0.5f) 0f else 1f })) }
        etaitPlein = plein != null
    }
    val bg = if (hot) mix(sc.surface, sc.primary, 0.34f) else mix(sc.surface, sc.onSurface, 0.1f)
    val dark = if (hot) mix(sc.primary, Color.Black, 0.2f) else mix(sc.surface, sc.onSurface, 0.28f)
    val light = if (hot) mix(sc.primary, Color.White, 0.45f) else mix(sc.surface, Color.White, 0.55f)
    val ink = sc.onSurface
    Box(
        modifier.fillMaxWidth().height(TUILE_HAUTEUR)
            .cible(id, gd, label, enabled = plein == null)
            .drawBehind {
                val c = 3.dp.toPx().roundToInt().toFloat()
                clipPath(outlinePath(PixelShape(steps = 1))) {
                    drawRect(bg)
                    drawRect(dark, size = Size(size.width, c)); drawRect(dark, size = Size(c, size.height))
                    drawRect(light, Offset(0f, size.height - c), Size(size.width, c)); drawRect(light, Offset(size.width - c, 0f), Size(c, size.height))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (plein != null) Box(Modifier.padding(3.dp).fillMaxWidth().height(51.dp).graphicsLayer { translationY = loge.value.dp.toPx() }.drawBehind { tuile(couleurPleine, ink, Color.Transparent, 0f, true) }, contentAlignment = Alignment.TopStart) {
            Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart, content = plein)
        }
        else content()
    }
}

