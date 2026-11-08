package app.kairos.mobile.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion

/**
 * Valeur consommée face à sa limite, avec seuils (> 80 % attention, > 100 % urgent).
 * [format] met en forme valeur et limite ; [masked] les remplace par un masque, sémantique comprise.
 */
@Composable
fun Bar(
    label: String,
    value: Double,
    limit: Double,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { formatEuro(it, cents = false) },
    masked: Boolean = LocalAmountsMasked.current,
) {
    val tone = barTone(value, limit)
    val f by animateFloatAsState(fill(value, limit), Motion.enter(), label = "bar")
    val color by animateColorAsState(tone.color(), Motion.enter(), label = "barTone")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val shown: (Double) -> String = { if (masked) MASK else format(it) }
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = describeBar(label, value, limit, format, masked)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                // Le ton se lit aussi sans la couleur.
                "${shown(value)} / ${shown(limit)}" + when (tone) {
                    Tone.Neutral -> ""
                    Tone.Attention -> " !"
                    Tone.Urgent -> " ⚠"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else color,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val h = 6.dp.toPx()
            val top = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(track, Offset(0f, top), Size(size.width, h), r)
            if (f > 0f) drawRoundRect(color, Offset(0f, top), Size(size.width * f, h), r)
        }
    }
}

/**
 * Colonnes d'une série (ex. un volume par jour), hauteur relative au maximum, sans axe
 * chiffré. [startLabel] / [endLabel] bornent l'axe horizontal ; [format] sert à la
 * description pour les lecteurs d'écran (total et maximum).
 */
@Composable
fun Columns(
    label: String,
    values: List<Double>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 64.dp,
    startLabel: String? = null,
    endLabel: String? = null,
    format: (Double) -> String = { formatEuro(it, cents = false) },
) {
    val color = MaterialTheme.colorScheme.primary
    val heights = columnHeights(values)
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = describeColumns(label, values, format) }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (heights.isEmpty()) return@Canvas
            val slot = size.width / heights.size
            val gap = (slot * 0.25f).coerceAtMost(4.dp.toPx())
            val r = CornerRadius(2.dp.toPx())
            heights.forEachIndexed { i, f ->
                val h = maxOf(f * size.height, if (f > 0f) 2.dp.toPx() else 0f)
                drawRoundRect(color, Offset(i * slot + gap / 2, size.height - h), Size(slot - gap, h), r)
            }
        }
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(startLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(endLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
