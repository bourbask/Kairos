package app.kairos.mobile.ui.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Couleur des yeux : sombre, lisible sur le corps clair du personnage. */
private val Eye = Color(0xFF1F2620)

/** Cadence de l'animation par état : lente au repos, vive en action. */
private fun frameMillis(state: MascotState): Long = when (state) {
    MascotState.Repos -> 200
    MascotState.Ecoute -> 320
    MascotState.Reflechit -> 420
    MascotState.Agit -> 170
    MascotState.Attend -> 480
}

/**
 * Le personnage de Kairos, dessiné pixel par pixel. [onBackground] est la
 * couleur de son corps, choisie pour contraster avec le fond qui le porte.
 */
@Composable
fun Mascot(state: MascotState, size: Dp, onBackground: Color, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        frame = 0
        while (true) {
            delay(frameMillis(state))
            // Animations retirées dans les réglages du système : le personnage reste immobile.
            if (coroutineContext[MotionDurationScale]?.scaleFactor != 0f) frame++
        }
    }
    // Respiration : léger flottement continu, sous le pixel.
    val breath by rememberInfiniteTransition(label = "souffle").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "souffle",
    )
    val pixels = remember(state, frame) { MascotSprite.pixels(state, frame) }

    Canvas(modifier.size(size)) {
        val cell = this.size.minDimension / MascotSprite.SIZE
        val lift = -(breath * cell * 0.5f).roundToInt()
        // Bords arrondis au pixel de l'écran : cases jointives, sans liseré.
        fun edge(i: Int) = (i * cell).roundToInt().toFloat()
        for (p in pixels) {
            val color = when (p.role) {
                PixelRole.Corps, PixelRole.Meche, PixelRole.Pied -> onBackground
                PixelRole.Reflet, PixelRole.Aile -> onBackground.copy(alpha = 0.78f)
                PixelRole.Oeil -> Eye
                PixelRole.Onde, PixelRole.Bulle -> onBackground.copy(alpha = 0.55f)
            }
            val x = edge(p.x)
            val y = edge(p.y)
            drawRect(color, Offset(x, y + lift), Size(edge(p.x + 1) - x, edge(p.y + 1) - y))
        }
    }
}
