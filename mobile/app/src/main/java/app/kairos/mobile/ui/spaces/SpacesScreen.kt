package app.kairos.mobile.ui.spaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.MaisonSnapshot
import app.kairos.mobile.ui.overlay.Origin
import app.kairos.mobile.ui.overlay.Overlay
import app.kairos.mobile.ui.overlay.overlayAnchor
import app.kairos.mobile.ui.overlay.rememberOverlayState
import app.kairos.mobile.ui.overlay.underOverlays

private val CardCorner = 20.dp

/**
 * Onglet Espaces : un espace par module. L'espace ouvert naît de sa carte et y
 * retourne (retour prédictif compris) ; la barre du bas et Kairos restent au-dessus.
 */
@Composable
fun SpacesScreen(
    padding: PaddingValues,
    maison: MaisonSnapshot,
    maisonOpen: Boolean,
    onOpenMaison: () -> Unit,
    onCloseMaison: () -> Unit,
    maisonContent: @Composable () -> Unit,
) {
    val overlay = rememberOverlayState(maisonOpen, Origin.Anchor)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant)) {
        Column(
            Modifier
                .fillMaxSize()
                .underOverlays(rtl, overlay)
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text("Espaces", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(20.dp))
            Text("MODULES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            MaisonCard(maison, onOpenMaison, Modifier.overlayAnchor(overlay, CardCorner))
            Spacer(Modifier.height(16.dp))
            Text(
                "D'autres espaces viendront ici.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Overlay(overlay, maisonOpen, onClose = onCloseMaison, anchorColor = MaterialTheme.colorScheme.surface) {
            maisonContent()
        }
    }
}

@Composable
private fun MaisonCard(maison: MaisonSnapshot, onClick: () -> Unit, modifier: Modifier) {
    val lots = maison.inventory.lots.size
    val urgent = maison.inventory.zones.sumOf { it.urgent }
    val toUnpack = maison.orders.count { o -> o.lines.any { !it.done } }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(CardCorner),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PlanIcon(colors.primary, colors.primaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Inventaire", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (maison.fetchedAt == null) "Le stock de la cuisine, zone par zone" else "$lots produit${if (lots > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                val notes = listOfNotNull(
                    urgent.takeIf { it > 0 }?.let { "$it à finir vite" },
                    toUnpack.takeIf { it > 0 }?.let { "commande à ranger" },
                )
                if (notes.isNotEmpty()) {
                    Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.error, fontWeight = FontWeight.SemiBold)
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Miniature du plan de la cuisine. */
@Composable
private fun PlanIcon(line: Color, fill: Color) = Canvas(Modifier.size(44.dp).background(fill, RoundedCornerShape(12.dp)).padding(9.dp)) {
    val g = 2.dp.toPx()
    val r = CornerRadius(2.dp.toPx())
    val w = size.width
    val h = size.height
    val left = w * 0.36f
    drawRoundRect(line, Offset.Zero, Size(left, h * 0.62f), r)
    drawRoundRect(line, Offset(0f, h * 0.62f + g), Size(left, h * 0.38f - g), r)
    drawRoundRect(line.copy(alpha = 0.6f), Offset(left + g, 0f), Size(w - left - g, h * 0.28f), r)
    drawRoundRect(line.copy(alpha = 0.4f), Offset(left + g, h * 0.28f + g), Size(w - left - g, h * 0.2f), r)
    drawCircle(line.copy(alpha = 0.75f), radius = h * 0.17f, center = Offset(left + g + (w - left - g) / 2, h * 0.78f))
}
