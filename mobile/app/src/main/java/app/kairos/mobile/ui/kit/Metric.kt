package app.kairos.mobile.ui.kit

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import app.kairos.mobile.ui.theme.Motion

/**
 * Chiffre clé et son libellé. [value] arrive déjà formaté (voir [amountText]
 * pour un montant masquable) ; un changement de valeur passe en fondu enchaîné.
 * Pas de masquage propre, par conception : c'est à l'appelant de fournir un texte déjà masqué.
 */
@Composable
fun Metric(value: String, label: String, modifier: Modifier = Modifier, tone: Tone? = null, caption: String? = null) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedContent(value, transitionSpec = { Motion.fadeThrough() }, label = "metric") { v ->
            Text(
                v,
                style = MaterialTheme.typography.headlineLarge,
                color = tone?.color() ?: Color.Unspecified,
                maxLines = 1,
            )
        }
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
