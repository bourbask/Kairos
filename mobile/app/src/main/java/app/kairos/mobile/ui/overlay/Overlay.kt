package app.kairos.mobile.ui.overlay

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** D'où naît un panneau : d'un élément de l'écran (transformation de conteneur) ou du bord de fin. */
enum class Origin { Anchor, End }

/** Coins d'un panneau qui suit le geste retour. */
private val BackCorner = 28.dp
private val BaseShape = RoundedCornerShape(BackCorner)

/**
 * État d'un panneau plein écran, partagé entre le panneau et l'écran qu'il recouvre.
 * Les valeurs animées ne sont lues qu'au dessin : l'animation ne recompose rien.
 */
@Stable
class OverlayState internal constructor(open: Boolean, val origin: Origin) {
    internal val progress = Animatable(if (open) 1f else 0f)
    internal val back = Animatable(0f)
    internal var fromLeft = false
    internal var anchor: LayoutCoordinates? = null
    internal var anchorCorner = 0.dp
    internal var source: Frame? = null

    /** Encore à l'écran : ouvert, ou en train de se refermer. */
    internal val shown by derivedStateOf { progress.value > 0f }

    internal fun frame(size: Size, rtl: Boolean, density: Density): Frame {
        val w = size.width
        val h = size.height
        val p = progress.value
        val base = when (origin) {
            Origin.End -> slide(p, w, h, towardsLeft = !rtl)
            Origin.Anchor -> morph(p, source ?: centered(w, h, with(density) { BackCorner.toPx() }), w, h)
        }
        return backGesture(base, back.value, fromLeft, backShift(w, density.density), with(density) { BackCorner.toPx() })
    }
}

@Composable
fun rememberOverlayState(open: Boolean, origin: Origin): OverlayState = remember { OverlayState(open, origin) }

/** Marque l'élément d'où naît le panneau, et où il retourne à la fermeture. */
fun Modifier.overlayAnchor(state: OverlayState, corner: Dp): Modifier = onGloballyPositioned {
    state.anchor = it
    state.anchorCorner = corner
}

/** Plusieurs éléments ouvrent le même panneau : celui qu'on vient de toucher devient son origine. */
fun OverlayState.anchorTo(coordinates: LayoutCoordinates, corner: Dp) {
    anchor = coordinates
    anchorCorner = corner
}

/**
 * Écran recouvert par des panneaux : il recule (transformation de conteneur)
 * ou glisse en parallaxe (panneau venu du bord), arrondi pendant le mouvement.
 * Tant qu'un panneau est à l'écran, l'écran recouvert sort de l'arbre d'accessibilité :
 * lecteurs d'écran et outils d'inspection ne voient que le panneau.
 * Appelé pendant la composition : le masquage suit l'ouverture et la fermeture.
 */
fun Modifier.underOverlays(rtl: Boolean, vararg states: OverlayState): Modifier =
    (if (states.any { it.shown }) clearAndSetSemantics {} else this).graphicsLayer {
        var depth = 0f
        var shift = 0f
        for (s in states) {
            val p = s.progress.value
            if (s.origin == Origin.End) shift = maxOf(shift, p) else depth = maxOf(depth, p)
        }
        val scale = recede(depth)
        scaleX = scale
        scaleY = scale
        translationX = PARALLAX * size.width * shift * (if (rtl) 1f else -1f)
        shape = BaseShape
        clip = depth > 0f || shift > 0f
    }

/**
 * Panneau plein écran : voile sur l'écran du dessous, entrée et sortie
 * symétriques, retour prédictif. Le contenu reste composé pendant la
 * fermeture ; [onClose] est appelé quand le geste retour est validé.
 * [anchorColor] : couleur de l'élément d'origine, dont le panneau prend la place.
 */
@Composable
fun Overlay(
    state: OverlayState,
    open: Boolean,
    onClose: () -> Unit,
    anchorColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val background = MaterialTheme.colorScheme.background
    val density = LocalDensity.current

    LaunchedEffect(open) {
        if (open) {
            // Mesurée avant que l'écran du dessous ne recule.
            state.source = state.anchor?.takeIf { it.isAttached }?.boundsInRoot()?.let {
                Frame(it.left, it.top, it.right, it.bottom, with(density) { state.anchorCorner.toPx() })
            }
            launch { state.back.snapTo(0f) }
            state.progress.animateTo(1f, Motion.enter(Motion.OverlayEnterMs))
        } else {
            // L'élément d'origine a disparu (pile vidée…) : le panneau se résorbe au centre.
            if (state.anchor?.isAttached != true) state.source = null
            val spec = if (state.origin == Origin.Anchor) {
                tween<Float>(Motion.OverlayExitMs, easing = Motion.Emphasized)
            } else {
                Motion.exit(Motion.OverlayExitMs)
            }
            launch { state.back.animateTo(0f, spec) }
            state.progress.animateTo(0f, spec)
        }
    }

    // Enregistré à l'ouverture : le dernier panneau ouvert reçoit le retour en premier.
    if (open) PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                state.fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                state.back.snapTo(e.progress)
            }
            close()
        } catch (e: CancellationException) {
            scope.launch { state.back.animateTo(0f, Motion.settle()) }
            throw e
        }
    }

    if (!open && !state.shown) return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = SCRIM * state.progress.value) })
        val path = remember { Path() }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val f = state.frame(size, rtl, this)
                    val p = state.progress.value
                    path.rewind()
                    // ponytail: un RoundRect par image ; négligeable devant le dessin du contenu.
                    path.addRoundRect(RoundRect(f.left, f.top, f.right, f.bottom, CornerRadius(f.corner)))
                    clipPath(path) {
                        if (state.origin == Origin.Anchor) {
                            drawRect(
                                lerp(anchorColor, background, colorFraction(p)),
                                topLeft = Offset(f.left, f.top),
                                size = Size(f.width, f.bottom - f.top),
                                alpha = containerAlpha(p),
                            )
                        }
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val f = state.frame(size, rtl, this)
                        val s = f.width / size.width
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = s
                        scaleY = s
                        translationX = f.left
                        translationY = f.top
                        alpha = if (state.origin == Origin.Anchor) contentAlpha(state.progress.value) else 1f
                    },
            ) { content() }
        }
        // Pendant la fermeture, plus rien ne réagit au toucher sous le panneau qui s'efface.
        if (!open) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                },
            )
        }
    }
}
