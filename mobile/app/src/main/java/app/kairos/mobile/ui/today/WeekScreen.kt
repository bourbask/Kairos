package app.kairos.mobile.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.Day
import app.kairos.mobile.data.Mark
import app.kairos.mobile.data.Snapshot
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.StatePill
import app.kairos.mobile.ui.theme.Motion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Vue semaine : les jours suivants en lecture rapide ; un jour ouvre l'Agenda ; écarter ou « Jour » pour revenir. */
@Composable
fun WeekScreen(
    snapshot: Snapshot,
    marks: Map<String, Map<String, Mark>>,
    bottom: Dp,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        if (snapshot.offline) item(key = "offline") { OfflineStatus(snapshot.fetchedAt, onRefresh) }
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Semaine", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                DayWeekToggle(week = true) { if (!it) onBack() }
            }
        }
        if (snapshot.days.isEmpty()) {
            item(key = "empty") {
                Text(
                    // Jamais reçue et serveur injoignable : inconnue, pas vide.
                    if (snapshot.offline) "Semaine indisponible pour l'instant." else "Rien de connu pour la semaine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(22.dp),
                )
            }
        }
        items(snapshot.days, key = { it.day }) { day ->
            DayBlock(day, marks[day.day].orEmpty(), expanded, onOpenDay) { id -> expanded = if (expanded == id) null else id }
        }
    }
}

@Composable
private fun DayBlock(day: Day, marks: Map<String, Mark>, expanded: String?, onOpenDay: (LocalDate) -> Unit, onToggle: (String) -> Unit) {
    val date = runCatching { LocalDate.parse(day.day) }.getOrNull()
    val title = when (date) {
        null -> day.day
        LocalDate.now() -> "Aujourd'hui"
        LocalDate.now().plusDays(1) -> "Demain"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE d", Locale.FRENCH)).replaceFirstChar { it.titlecase(Locale.FRENCH) }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            // En-tête du jour : ouvre l'Agenda sur ce jour.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (date != null) Modifier.clickable(onClickLabel = "Ouvrir l'agenda") { onOpenDay(date) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                day.work?.let { StatePill(it, Modifier.padding(horizontal = 6.dp)) }
                if (date != null) {
                    Icon(KairosIcons.ChevronForward, contentDescription = null, tint = muted)
                }
            }
            if (day.cards.isEmpty()) {
                Text("Rien de prévu", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            // Ordre de la journée tel que servi (heures d'abord) ; les marques locales grisent.
            day.cards.forEachIndexed { i, card ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val mark = card.markIn(marks)
                val key = "${day.day}/${card.key}"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onToggle(key) }
                        .alpha(if (mark != null) 0.7f else 1f)
                        .padding(vertical = 7.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(Modifier.clearAndSetSemantics { contentDescription = spokenItem(card.time, card.title) + markSpoken(mark) }) {
                        Text(card.time ?: "—", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.width(56.dp))
                        Text(
                            card.title,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (mark == Mark.Fait) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AnimatedVisibility(expanded == key, enter = Motion.expand, exit = Motion.collapse) {
                        Column(Modifier.padding(start = 56.dp, top = 4.dp)) {
                            card.body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                            card.detail?.let {
                                Spacer(Modifier.padding(top = 4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun markSpoken(mark: Mark?): String = when (mark) {
    Mark.Fait -> ", fait"
    Mark.PlusTard -> ", plus tard"
    null -> ""
}
