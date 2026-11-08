package app.kairos.mobile.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion

/**
 * Courbe d'une série (ex. une valeur par mois), sans axe chiffré : la forme seule,
 * le dernier point marqué. [startLabel] / [endLabel] bornent l'axe horizontal.
 * La courbe se dessine de gauche à droite à chaque nouvelle série.
 */
@Composable
fun LineChart(
    label: String,
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    startLabel: String? = null,
    endLabel: String? = null,
    format: (Double) -> String = { formatEuro(it, cents = false) },
    masked: Boolean = LocalAmountsMasked.current,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, Motion.enter(Motion.OverlayEnterMs))
    }
    val line = MaterialTheme.colorScheme.primary
    val area = line.copy(alpha = 0.16f)
    val points = linePoints(values)
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = describeLine(label, values, format, masked) }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (points.isEmpty()) return@Canvas
            val pad = 4.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            val pts = points.map { (x, y) -> Offset(pad + x * w, pad + (1 - y) * h) }
            val stroke = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val fillPath = Path().apply {
                addPath(stroke)
                lineTo(pts.last().x, size.height)
                lineTo(pts[0].x, size.height)
                close()
            }
            clipRect(right = size.width * reveal.value) {
                drawPath(fillPath, Brush.verticalGradient(listOf(area, area.copy(alpha = 0f))))
                drawPath(stroke, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (reveal.value == 1f) drawCircle(line, 4.dp.toPx(), pts.last())
        }
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(startLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(endLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
