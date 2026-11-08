package app.kairos.mobile.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Frise horaire d'une journée : blocs placés à la minute (chevauchements côte à côte),
 * plages mises en valeur [highlights] avec leur libellé, trait de l'heure si [day] est
 * aujourd'hui, blocs « journée » en ligne du haut. Défile verticalement dans une fenêtre
 * de [viewport] (hauteur bornée : s'insère dans un écran déjà défilant) et se place
 * à l'ouverture près de l'heure actuelle, sinon au premier bloc.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Timeline(
    day: LocalDate,
    blocks: List<TimeBlock>,
    modifier: Modifier = Modifier,
    highlights: List<TimeSpan> = emptyList(),
    startHour: Int = 7,
    endHour: Int = 23,
    hourHeight: Dp = 56.dp,
    viewport: Dp = 360.dp,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val layout = remember(blocks, startHour, endHour) { timelineLayout(blocks, startHour, endHour) }
    val nowAt = nowFraction(day, now, startHour, endHour)
    val total = hourHeight * (endHour - startHour)
    val gutter = 40.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, Motion.enter()) }
    LaunchedEffect(day, startHour, endHour) {
        // Une heure de contexte au-dessus de la cible.
        val target = nowAt ?: layout.placed.minOfOrNull { it.top } ?: return@LaunchedEffect
        val px = with(density) { (total * target - hourHeight).toPx() }.toInt().coerceAtLeast(0)
        scroll.animateScrollTo(px, Motion.enter())
    }

    val line = MaterialTheme.colorScheme.outlineVariant
    val shade = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val nowColor = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (layout.allDay.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                layout.allDay.forEach { BlockCard(it, Modifier.graphicsLayer { alpha = reveal.value }) }
            }
        }
        Box(Modifier.fillMaxWidth().height(viewport).verticalScroll(scroll)) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(total).drawWithCache {
                    val g = gutter.toPx()
                    val hour = hourHeight.toPx()
                    val spans = highlights.map {
                        minuteFraction(it.start, startHour, endHour) * size.height to
                            minuteFraction(it.end, startHour, endHour) * size.height
                    }
                    onDrawBehind {
                        spans.forEach { (top, bottom) -> drawRect(shade, Offset(g, top), Size(size.width - g, bottom - top)) }
                        for (h in 0..(endHour - startHour)) {
                            val y = h * hour
                            drawLine(line, Offset(g, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        nowAt?.let {
                            val y = it * size.height
                            drawLine(nowColor, Offset(g, y), Offset(size.width, y), 2.dp.toPx())
                            drawCircle(nowColor, 4.dp.toPx(), Offset(g, y))
                        }
                    }
                },
            ) {
                // Heures décoratives : l'information passe par les blocs et les plages.
                for (h in startHour until endHour) {
                    Text(
                        clockText(h * 60),
                        Modifier.offset(y = hourHeight * (h - startHour)).clearAndSetSemantics {},
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                highlights.forEach { s ->
                    val top = minuteFraction(s.start, startHour, endHour)
                    val bottom = minuteFraction(s.end, startHour, endHour)
                    if (bottom <= top) return@forEach
                    Text(
                        s.label,
                        Modifier.offset(x = gutter + 8.dp, y = total * top + 4.dp).semantics(s),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                    )
                }
                val columnsWidth = maxWidth - gutter
                layout.placed.forEach { p ->
                    val w = columnsWidth / p.columns
                    BlockCard(
                        blocks[p.index],
                        Modifier
                            .offset(x = gutter + w * p.column, y = total * p.top)
                            .width(w - 2.dp)
                            .height((total * (p.bottom - p.top) - 2.dp).coerceAtLeast(18.dp))
                            .graphicsLayer { alpha = reveal.value },
                    )
                }
            }
        }
    }
}

private fun Modifier.semantics(s: TimeSpan) =
    clearAndSetSemantics { contentDescription = "${s.label}, de ${clockText(s.start)} à ${clockText(s.end)}" }

/** Bloc : fond selon la famille, liseré selon le ton (lisible aussi sans couleur grâce au titre). */
@Composable
private fun BlockCard(b: TimeBlock, modifier: Modifier) {
    val (bg, fg) = when (b.kind) {
        BlockKind.Primary -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        BlockKind.Secondary -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    val accent: Color = if (b.tone == Tone.Neutral) fg.copy(alpha = 0.5f) else b.tone.color()
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clearAndSetSemantics { contentDescription = describeBlock(b) },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(b.title, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            b.subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
