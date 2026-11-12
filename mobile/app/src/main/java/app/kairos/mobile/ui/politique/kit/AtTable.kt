package app.kairos.mobile.ui.politique.kit

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kairos.mobile.ui.kit.KairosIcons

/** Une carte du mode « À table » : ce qu'on montre à quelqu'un en face, en très gros caractères. */
data class TableCard(
    val kicker: String,
    val title: String,
    val quote: String? = null,
    val verdict: String? = null,
    val lines: List<String> = emptyList(),
    val source: String? = null,
)

/** Texte partagé d'une carte : l'essentiel, source comprise. */
fun shareText(c: TableCard): String = buildList {
    add(c.kicker)
    add(c.title)
    c.quote?.let { add("« $it »") }
    c.verdict?.let { add(verdictLabel(it)) }
    addAll(c.lines)
    c.source?.let { add("Source : $it") }
}.filter { it.isNotBlank() }.joinToString("\n")

/**
 * Plein écran, contraste maximal. Balayage entre cartes, « Retourner » (le texte se lit de l'autre
 * côté de la table), « Partager » (texte).
 */
@Composable
fun AtTable(cards: List<TableCard>, start: Int, onClose: () -> Unit) {
    if (cards.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pc = PolColors.current
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, cards.size - 1)) { cards.size }
    var flipped by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (flipped) 180f else 0f, label = "retourner")
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(pc.tableBg).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("Fermer", color = pc.tableInk, fontSize = 16.sp) }
                Text("${pager.currentPage + 1} / ${cards.size}", color = pc.tableInk.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            HorizontalPager(pager, Modifier.weight(1f).graphicsLayer { rotationZ = angle }) { page ->
                val c = cards[page]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Kicker(c.kicker, color = pc.tableInk.copy(alpha = 0.7f))
                    Text(c.title, Modifier.padding(top = 10.dp), color = pc.tableInk, fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em)
                    if (c.quote != null) Text("« ${c.quote} »", Modifier.padding(top = 10.dp), color = pc.tableInk, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em)
                    if (c.verdict != null) VerdictChip(c.verdict, Modifier.padding(top = 22.dp), large = true)
                    c.lines.forEach { Text(frTypo(it), Modifier.padding(top = 12.dp), color = pc.tableInk, fontSize = 21.sp, lineHeight = 29.sp) }
                    if (c.source != null) Text(c.source, Modifier.padding(top = 22.dp).alpha(0.75f), color = pc.tableInk, fontSize = 15.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ flipped = !flipped }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(KairosIcons.Refresh, null, Modifier.padding(end = 8.dp), tint = pc.tableInk)
                    Text("Retourner", color = pc.tableInk)
                }
                Button(
                    {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(cards[pager.currentPage]))
                        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = pc.tableInk, contentColor = pc.tableBg),
                ) {
                    Icon(KairosIcons.Share, null, Modifier.padding(end = 8.dp))
                    Text("Partager")
                }
            }
        }
    }
}
