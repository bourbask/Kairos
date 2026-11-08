package app.kairos.mobile.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion

/** Couleurs par défaut des parts, tirées du thème (lisibles en clair et en sombre). */
@Composable
fun donutColors(): List<Color> = with(MaterialTheme.colorScheme) {
    listOf(primary, tertiary, onPrimaryContainer, outline, error)
}

/**
 * Anneau de parts [parts] (nom, valeur) ; les couleurs suivent [colors] dans l'ordre.
 * Proportions seules à l'écran ; la sémantique nomme les parts, sans chiffre si masqué.
 */
@Composable
fun Donut(
    label: String,
    parts: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    diameter: Dp = 140.dp,
    colors: List<Color> = donutColors(),
    format: (Double) -> String = { formatEuro(it, cents = false) },
    masked: Boolean = LocalAmountsMasked.current,
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(parts) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, Motion.enter(Motion.OverlayEnterMs))
    }
    val weights = donutWeights(parts.map { it.second })
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.size(diameter).clearAndSetSemantics { contentDescription = describeDonut(label, parts, format, masked) }) {
        val w = 18.dp.toPx()
        val tl = Offset(w / 2, w / 2)
        val sz = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(w))
        var start = -90f
        weights.forEachIndexed { i, weight ->
            val angle = 360f * weight * sweep.value
            if (angle > 0f) drawArc(colors[i % colors.size], start, angle, false, tl, sz, style = Stroke(w))
            start += angle
        }
    }
}
