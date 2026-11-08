package app.kairos.mobile.ui.overlay

import kotlin.math.max

/** Rectangle d'un panneau à un instant donné (coordonnées de l'écran) et rayon de ses coins. */
internal data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float, val corner: Float) {
    val width: Float get() = right - left
}

/** Recul de l'écran du dessous quand un panneau s'ouvre dessus. */
internal const val RECEDE = 0.05f

/** Opacité du voile entre l'écran du dessous et un panneau ouvert. */
internal const val SCRIM = 0.32f

/** Décalage de l'écran du dessous quand un panneau glisse depuis le bord (en fraction de largeur). */
internal const val PARALLAX = 0.2f

/** Réduction maximale d'un panneau suivant le geste retour. */
internal const val BACK_SCALE = 0.1f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun fraction(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

/** Échelle de l'écran du dessous pour une ouverture [progress] (0 fermé, 1 ouvert). */
internal fun recede(progress: Float) = 1f - RECEDE * progress

/**
 * Transformation de conteneur : le panneau naît du rectangle [source] et
 * occupe l'écran à l'ouverture complète. La source est vue à travers le recul
 * de l'écran du dessous, pour que le retour se pose exactement sur elle.
 */
internal fun morph(progress: Float, source: Frame, width: Float, height: Float): Frame {
    val s = recede(progress)
    val cx = width / 2
    val cy = height / 2
    return Frame(
        left = lerp(cx + (source.left - cx) * s, 0f, progress),
        top = lerp(cy + (source.top - cy) * s, 0f, progress),
        right = lerp(cx + (source.right - cx) * s, width, progress),
        bottom = lerp(cy + (source.bottom - cy) * s, height, progress),
        corner = lerp(source.corner * s, 0f, progress),
    )
}

/** Source de repli quand l'élément d'origine n'est plus à l'écran : le panneau grandit depuis le centre. */
internal fun centered(width: Float, height: Float, corner: Float): Frame {
    val dx = width * 0.04f
    val dy = height * 0.04f
    return Frame(dx, dy, width - dx, height - dy, corner)
}

/** Panneau qui entre par le bord de fin ([towardsLeft] : il glisse de droite à gauche). */
internal fun slide(progress: Float, width: Float, height: Float, towardsLeft: Boolean): Frame {
    val x = (1f - progress) * width * (if (towardsLeft) 1f else -1f)
    return Frame(x, 0f, x + width, height, 0f)
}

/**
 * Retour prédictif (recommandations Material) : le panneau rétrécit jusqu'à
 * 90 %, s'écarte du bord d'où part le geste de [maxShift] au plus et arrondit
 * ses coins jusqu'à [maxCorner]. [back] : avancée du geste, de 0 à 1.
 */
internal fun backGesture(frame: Frame, back: Float, fromLeft: Boolean, maxShift: Float, maxCorner: Float): Frame {
    if (back <= 0f) return frame
    val s = 1f - BACK_SCALE * back
    val shift = maxShift * back * (if (fromLeft) 1f else -1f)
    val cx = (frame.left + frame.right) / 2 + shift
    val cy = (frame.top + frame.bottom) / 2
    val hw = (frame.right - frame.left) * s / 2
    val hh = (frame.bottom - frame.top) * s / 2
    return Frame(cx - hw, cy - hh, cx + hw, cy + hh, max(frame.corner, maxCorner * back))
}

/** Décalage maximal du geste retour : 1/20 de la largeur moins 8 dp. */
internal fun backShift(width: Float, density: Float) = max(0f, width / 20f - 8f * density)

/** Opacité du conteneur : il recouvre l'élément d'origine en tout début d'ouverture. */
internal fun containerAlpha(progress: Float) = fraction(progress, 0f, 0.12f)

/** Opacité du contenu : il apparaît une fois le conteneur établi (fondu enchaîné). */
internal fun contentAlpha(progress: Float) = fraction(progress, 0.15f, 0.6f)

/** Passage de la couleur de l'élément d'origine à celle du panneau. */
internal fun colorFraction(progress: Float) = fraction(progress, 0f, 0.6f)
