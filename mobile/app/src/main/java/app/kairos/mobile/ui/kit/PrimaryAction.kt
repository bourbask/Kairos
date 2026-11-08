package app.kairos.mobile.ui.kit

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

/**
 * Action principale d'un espace, bas-droite au-dessus de la barre. [expanded] faux au défilement :
 * l'icône seule reste, le libellé passe au lecteur d'écran. Désactivée, elle reste lisible (AA).
 */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean = true,
    icon: ImageVector = KairosIcons.Plus,
) {
    val colors = MaterialTheme.colorScheme
    ExtendedFloatingActionButton(
        text = { Text(text, fontWeight = FontWeight.Bold) },
        icon = { Icon(icon, contentDescription = null) },
        onClick = { if (enabled) onClick() },
        expanded = expanded,
        containerColor = if (enabled) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
        modifier = modifier.semantics {
            if (!expanded) contentDescription = text
            if (!enabled) disabled()
        },
    )
}
