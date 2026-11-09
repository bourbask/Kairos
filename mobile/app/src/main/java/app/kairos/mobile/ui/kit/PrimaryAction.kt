package app.kairos.mobile.ui.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion

/**
 * Action principale d'un espace, bas-droite au-dessus de la barre. [expanded] faux au défilement :
 * l'icône seule reste, le libellé passe au lecteur d'écran. Désactivée, elle reste lisible (AA).
 * Remonte le temps d'un message de la file commune, qui s'affiche juste au-dessus du dock.
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
    val lift by animateDpAsState(messageLift(), Motion.settle(), label = "message")
    ExtendedFloatingActionButton(
        text = { Text(text, fontWeight = FontWeight.Bold) },
        icon = { Icon(icon, contentDescription = null) },
        onClick = { if (enabled) onClick() },
        expanded = expanded,
        containerColor = if (enabled) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
        modifier = modifier.offset(y = -lift).semantics {
            if (!expanded) contentDescription = text
            if (!enabled) disabled()
        },
    )
}

/** Hauteur dont l'action remonte pendant un message de la file commune. */
@Composable
private fun messageLift(): Dp =
    // ponytail: hauteur fixe d'un message sur deux lignes ; mesurer l'hôte si un message plus haut apparaît.
    if (LocalNotifier.current.host.currentSnackbarData != null) 80.dp else 0.dp

/**
 * Marge basse (en plus de celle de la coque) qu'un contenu défilant doit laisser sous une
 * [PrimaryAction] : sa hauteur, ses marges et sa remontée pendant un message. Ainsi la fin
 * du contenu n'est jamais masquée.
 */
@Composable
fun primaryActionClearance(): Dp = 96.dp + messageLift()
