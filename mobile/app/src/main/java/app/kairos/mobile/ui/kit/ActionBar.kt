package app.kairos.mobile.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion

/**
 * Barre sous le pouce des écrans « une carte à la fois » : [primary] rempli pleine largeur
 * (56 dp), puis les [secondary] côte à côte, qui passent à la ligne quand le texte grandit.
 */
@Composable
fun ActionBar(
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondary: @Composable FlowRowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = secondary,
        )
    }
}

/** Action secondaire de l'[ActionBar] : contour, 48 dp, partage la rangée à parts égales. */
@Composable
fun FlowRowScope.SecondaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
        Text(text, textAlign = TextAlign.Center)
    }
}

/**
 * En-tête des panneaux nés d'une carte (Décider, Ranger, Ajouter) : « Fermer » à gauche,
 * compteur [position] / [total] et « Annuler » ([onUndo]) à droite, progression [done] / [total] dessous.
 */
@Composable
fun PanelHeader(
    title: String,
    onClose: () -> Unit,
    subtitle: String? = null,
    done: Int = 0,
    total: Int = 0,
    position: Int = done,
    onUndo: (() -> Unit)? = null,
    canUndo: Boolean = false,
) {
    SpaceHeader(title, onClose, subtitle = subtitle, backLabel = "Fermer", backIcon = KairosIcons.ChevronDown) {
        if (total > 0) {
            Text(
                "$position / $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Dit par la barre de progression.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (onUndo != null) TextButton(onClick = onUndo, enabled = canUndo) { Text("Annuler") }
    }
    if (total > 0) {
        val progress by animateFloatAsState(done.toFloat() / total, Motion.enter(), label = "progression")
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "$position sur $total" },
            drawStopIndicator = {},
        )
    }
}
