package app.kairos.mobile.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntSize

/**
 * Grammaire de mouvement unique de l'application (courbes et durées Material 3).
 * Une entrée décélère et dure plus longtemps qu'une sortie, qui accélère.
 * Ce qui suit un doigt ou un changement de taille se pose sur un ressort sans rebond.
 */
object Motion {
    /** Entrée : part vite, se pose en douceur. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Sortie : démarre doucement, quitte l'écran vite. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Aller-retour d'un même élément visible aux deux bouts (retour d'un panneau dans sa source). */
    val Emphasized = PathEasing(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        },
    )

    /** Panneaux plein écran. */
    const val OverlayEnterMs = 450
    const val OverlayExitMs = 300

    /** Éléments à l'intérieur d'un écran. */
    const val EnterMs = 300
    const val ExitMs = 150

    /** Fondu enchaîné : l'ancien contenu s'efface, puis le nouveau apparaît. */
    const val FadeOutMs = 90
    const val FadeInMs = 210

    fun <T> enter(durationMs: Int = EnterMs): FiniteAnimationSpec<T> = tween(durationMs, easing = EmphasizedDecelerate)

    fun <T> exit(durationMs: Int = ExitMs): FiniteAnimationSpec<T> = tween(durationMs, easing = EmphasizedAccelerate)

    /** Lancer : l'élément part vite, comme jeté, et ralentit hors de l'écran. */
    fun <T> fling(): FiniteAnimationSpec<T> = tween(220, easing = EmphasizedDecelerate)

    /** Relâchement d'un geste : rejoint sa place sans rebondir. */
    fun <T> settle(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(Spring.DampingRatioNoBouncy, 700f, visibilityThreshold)

    /** Changement de taille d'un contenu. */
    private val resize = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntSize(1, 1))

    /** Déplier un détail. */
    val expand: EnterTransition = expandVertically(resize) + fadeIn(tween(EnterMs, delayMillis = FadeOutMs, easing = EmphasizedDecelerate))

    /** Replier un détail. */
    val collapse: ExitTransition = shrinkVertically(resize) + fadeOut(exit(FadeOutMs))

    /** Panneau qui monte du bas (et y redescend). */
    val riseIn: EnterTransition = slideInVertically(enter()) { it / 4 } + fadeIn(enter())
    val sinkOut: ExitTransition = slideOutVertically(exit()) { it / 4 } + fadeOut(exit())

    /** Changement de contenu sans lien spatial (onglets, bouton qui change de rôle). */
    fun fadeThrough(): ContentTransform =
        (fadeIn(tween(FadeInMs, delayMillis = FadeOutMs, easing = EmphasizedDecelerate)) +
            scaleIn(tween(FadeInMs, delayMillis = FadeOutMs, easing = EmphasizedDecelerate), initialScale = 0.92f)) togetherWith
            fadeOut(tween(FadeOutMs, easing = EmphasizedAccelerate))

    /**
     * Zoom sémantique (axe Z) : [outward] = on prend du recul (jour → semaine),
     * le contenu rapetisse ; sinon il grossit.
     */
    fun zoom(outward: Boolean): ContentTransform {
        val from = if (outward) 1.1f else 0.9f
        val to = if (outward) 0.9f else 1.1f
        return (fadeIn(tween(FadeInMs, delayMillis = FadeOutMs, easing = EmphasizedDecelerate)) +
            scaleIn(enter(EnterMs), initialScale = from)) togetherWith
            (fadeOut(tween(FadeOutMs, easing = EmphasizedAccelerate)) + scaleOut(exit(EnterMs), targetScale = to))
    }
}
