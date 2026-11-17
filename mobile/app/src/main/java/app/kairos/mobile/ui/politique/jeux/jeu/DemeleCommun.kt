package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import app.kairos.mobile.ui.politique.jeux.Coque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* Pièces communes aux manettes du thème Démêle (portraits, tampons, fiches). */

/** [dp] en pixels entiers : bandes et marches nettes. */
internal fun DrawScope.ent(dp: Dp): Float = dp.toPx().roundToInt().coerceAtLeast(1).toFloat()

/** Rectangle [x], [y], [w]×[h] en pixels. */
internal fun DrawScope.aplat(c: Color, x: Float, y: Float, w: Float, h: Float) = drawRect(c, Offset(x, y), Size(w, h))

/** Biseau : bandes de [e] px, claires en haut et à gauche, sombres en bas et à droite (le clair passe dessus). */
internal fun DrawScope.biseau(clair: Color, sombre: Color, e: Float) {
    aplat(sombre, 0f, size.height - e, size.width, e)
    aplat(sombre, size.width - e, 0f, e, size.height)
    aplat(clair, 0f, 0f, size.width, e)
    aplat(clair, 0f, 0f, e, size.height)
}

/** Cadre intérieur de [e] px. */
internal fun DrawScope.cadre(c: Color, e: Float) {
    aplat(c, 0f, 0f, size.width, e)
    aplat(c, 0f, size.height - e, size.width, e)
    aplat(c, 0f, 0f, e, size.height)
    aplat(c, size.width - e, 0f, e, size.height)
}

/** Petit saut de joie (dp, vers le haut négatif) quand [actif] devient vrai. */
@Composable
internal fun rememberSaut(actif: Boolean, reduit: Boolean): Animatable<Float, AnimationVector1D> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(actif) {
        if (actif && !reduit) a.animateTo(0f, keyframes { durationMillis = 550; -8f at 165; 0f at 330; -3f at 440 })
    }
    return a
}

/**
 * Appui sur une pièce : elle bouge tout de suite ([pièce] = son index), la coque tranche après [ms]
 * (aussitôt en animations réduites). Un seul appui à la fois.
 */
internal class Appui(private val scope: CoroutineScope, private val c: Coque, private val ms: Long, private val juger: (Int) -> Unit) {
    var piece by mutableStateOf<Int?>(null)
        private set

    fun jouer(i: Int) {
        if (piece != null) return
        piece = i
        c.cran()
        scope.launch {
            delay(if (c.reduit) 0 else ms)
            juger(i)
            piece = null
        }
    }
}

@Composable
internal fun rememberAppui(key: Any, c: Coque, ms: Long, juger: (Int) -> Unit): Appui {
    val scope = rememberCoroutineScope()
    return remember(key, c) { Appui(scope, c, ms, juger) }
}
