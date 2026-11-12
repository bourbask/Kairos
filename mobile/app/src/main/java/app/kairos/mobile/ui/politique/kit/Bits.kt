package app.kairos.mobile.ui.politique.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef

/** Petites briques de mise en page partagées par les écrans de l'espace. */

/**
 * Marge basse que le contenu défilant d'une page laisse libre (dock et actions du bas) : le contenu
 * passe sous le dégradé de la coque au lieu de s'arrêter au-dessus.
 */
val LocalPolBottom = compositionLocalOf { 24.dp }

/** Filet entre deux lignes d'une carte. */
@Composable
fun Divider(top: Dp = 0.dp) = Box(Modifier.padding(top = top).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))

/** Texte secondaire, petit et atténué. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(frTypo(text), modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

/** Paragraphe reçu des données (gras seul interprété) ; vide : rien. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    if (text.isNotBlank()) RichText(text, modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
}

/** Étiquettes neutres (titres, catégories), jamais aux couleurs d'un parti. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Tags(items: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEach { t ->
            Text(t, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Ligne « libellé … valeur » ; [color] : teinte d'état de la valeur. */
@Composable
fun KeyRow(k: String, v: String, color: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) { }) {
        Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(v, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = color ?: Color.Unspecified)
    }
}

/** Barre horizontale à la part [fraction] de la piste, avec son chiffre. */
@Composable
fun Bar(fraction: Float, color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(14.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
        Text(label, Modifier.width(92.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, softWrap = false)
    }
}

/** Entrée de légende : pastille de couleur et libellé. */
@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Légende qui passe à la ligne. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }

/** Légende de pastilles. */
@Composable
fun Legend(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) = Legend(modifier) { items.forEach { (c, t) -> LegendDot(c, t) } }

/** Pied de section : « [label] » puis les sources ; aucune source : rien. */
@Composable
fun SourceFoot(label: String, srcs: List<SourceRef>, data: PolData, onOpenEv: (String) -> Unit, modifier: Modifier = Modifier) {
    if (srcs.isEmpty()) return
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(top = 12.dp, end = 8.dp), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SourceList(srcs, data, onOpenEv)
    }
}
