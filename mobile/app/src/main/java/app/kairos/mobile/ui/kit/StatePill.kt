package app.kairos.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.KairosColors

/** Pastille d'état (« déjà demandé », « 82 % », « fait ») : le mot porte l'état, la couleur l'appuie. */
@Composable
fun StatePill(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.Neutral -> scheme.primaryContainer to scheme.onPrimaryContainer
        Tone.Attention -> scheme.tertiary.copy(alpha = 0.18f) to KairosColors.current.attentionText
        Tone.Urgent -> scheme.error.copy(alpha = 0.14f) to scheme.error
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 2.dp),
    )
}
