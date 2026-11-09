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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
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
 * aujourd'hui, blocs « journée » en ligne du haut. Avec [viewport], défile verticalement dans
 * cette fenêtre et se place à l'ouverture près de l'heure actuelle, sinon au premier bloc.
 * Sans [viewport], pas de défilement propre : la frise prend la plage utile du jour
 * ([usefulHours]) et laisse défiler la page. Le trait suit l'horloge
 * à la minute tant que l'écran est visible ; [now] fige l'heure (aperçus, tests).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Timeline(
    day: LocalDate,
    blocks: List<TimeBlock>,
    modifier: Modifier = Modifier,
    highlights: List<TimeSpan> = emptyList(),
    fromHour: Int = 7,
    toHour: Int = 23,
    hourHeight: Dp = 56.dp,
    viewport: Dp? = 320.dp,
    minMinutes: Int = 20,
    now: LocalDateTime? = null,
) {
    // Horloge interne rafraîchie à chaque minute, seulement écran visible ; [now] la remplace.
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, now) {
        if (now != null) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                clock = LocalDateTime.now()
                delay(millisToNextMinute(clock))
            }
        }
    }
    val current = now ?: clock
    val (startHour, endHour) = if (viewport != null) {
        fromHour to toHour
    } else {
        usefulHours(blocks, (current.hour * 60 + current.minute).takeIf { current.toLocalDate() == day })
    }
    val layout = remember(blocks, startHour, endHour, minMinutes) { timelineLayout(blocks, startHour, endHour, minMinutes) }
    val nowAt = nowFraction(day, current, startHour, endHour)
    val total = hourHeight * (endHour - startHour)
    val gutter = 40.dp * LocalDensity.current.fontScale
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, Motion.enter()) }
    LaunchedEffect(day, startHour, endHour, layout.placed.isEmpty()) {
        if (viewport == null) return@LaunchedEffect
        // Une heure de contexte au-dessus de la cible.
        val target = nowAt ?: layout.placed.minOfOrNull { it.top } ?: return@LaunchedEffect
        val px = with(density) { (total * target - hourHeight).toPx() }.toInt().coerceAtLeast(0)
        scroll.animateScrollTo(px, Motion.enter())
    }

    val line = MaterialTheme.colorScheme.outlineVariant
    val shade = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val nowColor = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hatch = MaterialTheme.colorScheme.outlineVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (layout.allDay.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                layout.allDay.forEach { BlockCard(it, Modifier.graphicsLayer { alpha = reveal.value }) }
            }
        }
        Box(if (viewport != null) Modifier.fillMaxWidth().height(viewport).verticalScroll(scroll) else Modifier.fillMaxWidth()) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(total).drawWithCache {
                    val g = gutter.toPx()
                    val hour = hourHeight.toPx()
                    val spans = highlights.map {
                        minuteFraction(it.start, startHour, endHour) * size.height to
                            minuteFraction(it.end, startHour, endHour) * size.height
                    }
                    val step = 10.dp.toPx()
                    onDrawBehind {
                        // Plages libres : fond et hachures, lisibles aussi sans la couleur.
                        spans.forEach { (top, bottom) ->
                            drawRect(shade, Offset(g, top), Size(size.width - g, bottom - top))
                            clipRect(g, top, size.width, bottom) {
                                var x = g - (bottom - top)
                                while (x < size.width) {
                                    drawLine(hatch, Offset(x, bottom), Offset(x + (bottom - top), top), 1.dp.toPx())
                                    x += step
                                }
                            }
                        }
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
                val columnsWidth = maxWidth - gutter
                layout.placed.forEach { p ->
                    val w = columnsWidth / p.columns
                    BlockCard(
                        blocks[p.index],
                        Modifier
                            .offset(x = gutter + w * p.column, y = total * p.top)
                            .width(w - 2.dp)
                            .height((total * (p.bottom - p.top) - 2.dp).coerceAtLeast(hourHeight * minMinutes / 60 - 2.dp))
                            .graphicsLayer { alpha = reveal.value },
                    )
                }
                // Libellés des plages par-dessus les blocs, à droite.
                highlights.forEach { s ->
                    val top = minuteFraction(s.start, startHour, endHour)
                    val bottom = minuteFraction(s.end, startHour, endHour)
                    if (bottom <= top) return@forEach
                    Text(
                        s.label,
                        Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = total * top + 4.dp).spanSemantics(s),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun Modifier.spanSemantics(s: TimeSpan) =
    clearAndSetSemantics { contentDescription = "${s.label}, de ${clockText(s.start)} à ${clockText(s.end)}" }

/** Fond, texte et liseré d'une famille de blocs : fougère pour la première, ardoise pour la seconde. */
@Composable
fun BlockKind.colors(): Triple<Color, Color, Color> = when (this) {
    BlockKind.Primary -> MaterialTheme.colorScheme.let { Triple(it.primaryContainer, it.onPrimaryContainer, it.primary) }
    BlockKind.Secondary -> MaterialTheme.colorScheme.let { Triple(it.secondaryContainer, it.onSecondaryContainer, it.onSecondaryContainer) }
}

/**
 * Bloc : fond et liseré selon la famille (le ton, s'il y en a un, prend le liseré) ; la seconde
 * famille dit aussi sa source en toutes lettres quand la hauteur le permet.
 */
@Composable
private fun BlockCard(b: TimeBlock, modifier: Modifier) {
    val (bg, fg, side) = b.kind.colors()
    val accent: Color = if (b.tone == Tone.Neutral) side else b.tone.color()
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clearAndSetSemantics { contentDescription = describeBlock(b) },
    ) {
        Box(Modifier.width(if (b.kind == BlockKind.Primary) 3.dp else 5.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(b.title, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            b.subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (b.kind == BlockKind.Secondary) b.source?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
            }
        }
    }
}
