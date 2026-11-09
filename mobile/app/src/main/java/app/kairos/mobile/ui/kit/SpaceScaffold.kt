package app.kairos.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * En-tête d'un espace : chevron retour (48 dp), titre, sous-titre, actions à droite.
 * Seul, pour les écrans qui gèrent eux-mêmes leur corps (onglets).
 */
@Composable
fun SpaceHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backLabel: String = "Retour aux espaces",
    backIcon: ImageVector = KairosIcons.ChevronBack,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(backIcon, contentDescription = backLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

/**
 * Charpente d'un espace plein écran : [SpaceHeader], séparateur quand le contenu a défilé
 * ([scrolled]), bandeau d'état, action principale bas-droite au-dessus du dock.
 * [padding] : marges de la coque (barre d'état en haut, dock en bas). Le contenu reçoit
 * la marge basse à laisser libre (dock et action principale compris).
 */
@Composable
fun SpaceScaffold(
    title: String,
    onBack: () -> Unit,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backLabel: String = "Retour aux espaces",
    scrolled: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    banner: (@Composable () -> Unit)? = null,
    primaryAction: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val bottom = padding.calculateBottomPadding()
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            SpaceHeader(title, onBack, subtitle = subtitle, backLabel = backLabel, actions = actions)
            if (scrolled) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (banner != null) {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { banner() }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                content(PaddingValues(bottom = bottom + if (primaryAction != null) primaryActionClearance() else 24.dp))
            }
        }
        if (primaryAction != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottom + 16.dp)) { primaryAction() }
        }
    }
}
