package app.kairos.mobile.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion
import kotlin.math.roundToInt

/**
 * Progression horizontale [progress] (0 à 1), avec un repère facultatif [marker]
 * (ex. aujourd'hui dans une période). Proportions seules, jamais de montant :
 * pas de masquage, par conception (annoncée en pourcentage).
 */
@Composable
fun Gauge(progress: Float, label: String, modifier: Modifier = Modifier, marker: Float? = null, tone: Tone = Tone.Neutral) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.enter(), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = tone.color()
    val mark = MaterialTheme.colorScheme.onBackground
    Canvas(
        modifier.fillMaxWidth().height(14.dp).semantics {
            contentDescription = "$label : ${(progress.coerceIn(0f, 1f) * 100).roundToInt()} %"
            progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        },
    ) {
        val h = 8.dp.toPx()
        val top = (size.height - h) / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(track, Offset(0f, top), Size(size.width, h), r)
        if (p > 0f) drawRoundRect(bar, Offset(0f, top), Size(size.width * p, h), r)
        marker?.let {
            val x = size.width * it.coerceIn(0f, 1f)
            drawLine(mark, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}
