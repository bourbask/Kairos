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
import app.kairos.mobile.data.AgendaSnapshot
import app.kairos.mobile.data.FinancesSnapshot
import app.kairos.mobile.data.MaisonSnapshot
import app.kairos.mobile.data.MarchesSnapshot
import app.kairos.mobile.data.MemoireSnapshot
import app.kairos.mobile.ui.memoire.openIntentions
import app.kairos.mobile.ui.memoire.timeLeft
import java.time.LocalDateTime
import app.kairos.mobile.ui.marches.color
import app.kairos.mobile.ui.marches.deltaLabel
import app.kairos.mobile.ui.marches.headline
import app.kairos.mobile.ui.marches.pendingProposals
import app.kairos.mobile.ui.marches.strongestToday
import app.kairos.mobile.ui.marches.spokenDelta
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import app.kairos.mobile.ui.marches.trendOf
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import app.kairos.mobile.ui.kit.Amount
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.kit.barTone
import app.kairos.mobile.ui.agenda.upcomingWhen
import java.time.LocalDate
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
    finances: FinancesSnapshot,
    financesOpen: Boolean,
    onOpenFinances: () -> Unit,
    onCloseFinances: () -> Unit,
    financesContent: @Composable () -> Unit,
    agenda: AgendaSnapshot,
    agendaOpen: Boolean,
    onOpenAgenda: () -> Unit,
    onCloseAgenda: () -> Unit,
    agendaContent: @Composable () -> Unit,
    marches: MarchesSnapshot,
    marchesOpen: Boolean,
    onOpenMarches: () -> Unit,
    onCloseMarches: () -> Unit,
    marchesContent: @Composable () -> Unit,
    memoire: MemoireSnapshot,
    memoireOpen: Boolean,
    onOpenMemoire: () -> Unit,
    onCloseMemoire: () -> Unit,
    memoireContent: @Composable () -> Unit,
    memoireLater: Set<Long> = emptySet(),
    maisonContent: @Composable () -> Unit,
) {
    val overlay = rememberOverlayState(maisonOpen, Origin.Anchor)
    val financesOverlay = rememberOverlayState(financesOpen, Origin.Anchor)
    val agendaOverlay = rememberOverlayState(agendaOpen, Origin.Anchor)
    val marchesOverlay = rememberOverlayState(marchesOpen, Origin.Anchor)
    val memoireOverlay = rememberOverlayState(memoireOpen, Origin.Anchor)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant)) {
        Column(
            Modifier
                .fillMaxSize()
                .underOverlays(rtl, overlay, financesOverlay, agendaOverlay, marchesOverlay, memoireOverlay)
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text("Espaces", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(20.dp))
            Text("MODULES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            MaisonCard(maison, onOpenMaison, Modifier.overlayAnchor(overlay, CardCorner))
            Spacer(Modifier.height(12.dp))
            FinancesCard(finances, onOpenFinances, Modifier.overlayAnchor(financesOverlay, CardCorner))
            Spacer(Modifier.height(12.dp))
            AgendaCard(agenda, onOpenAgenda, Modifier.overlayAnchor(agendaOverlay, CardCorner))
            Spacer(Modifier.height(12.dp))
            MarchesCard(marches, onOpenMarches, Modifier.overlayAnchor(marchesOverlay, CardCorner))
            Spacer(Modifier.height(12.dp))
            MemoireCard(memoire, memoireLater, onOpenMemoire, Modifier.overlayAnchor(memoireOverlay, CardCorner))
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
        Overlay(financesOverlay, financesOpen, onClose = onCloseFinances, anchorColor = MaterialTheme.colorScheme.surface) {
            financesContent()
        }
        Overlay(agendaOverlay, agendaOpen, onClose = onCloseAgenda, anchorColor = MaterialTheme.colorScheme.surface) {
            agendaContent()
        }
        Overlay(marchesOverlay, marchesOpen, onClose = onCloseMarches, anchorColor = MaterialTheme.colorScheme.surface) {
            marchesContent()
        }
        Overlay(memoireOverlay, memoireOpen, onClose = onCloseMemoire, anchorColor = MaterialTheme.colorScheme.surface) {
            memoireContent()
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

@Composable
private fun FinancesCard(finances: FinancesSnapshot, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val cycle = finances.cycle
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(CardCorner),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoinsIcon(colors.primary, colors.primaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Finances", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val available = cycle?.available
                if (available == null) {
                    Text("Budget du cycle et patrimoine", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    Row {
                        Text("Reste à vivre ", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Amount(available, cents = false, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    }
                }
                val over = cycle?.envelopes.orEmpty().count { barTone(it.spent, it.planned) == Tone.Urgent }
                if (over > 0) {
                    Text(
                        "$over enveloppe${if (over > 1) "s" else ""} dépassée${if (over > 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgendaCard(agenda: AgendaSnapshot, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val next = agenda.week?.upcoming?.firstOrNull()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(CardCorner),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CalendarIcon(colors.primary, colors.primaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Agenda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (next == null) {
                    Text("Journée, plages libres et tâches", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    Text(next.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1)
                    Text(upcomingWhen(next, LocalDate.now()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Question qui a le plus bougé sur 24 h, sens lisible sans la couleur ; propositions à trier. */
@Composable
private fun MarchesCard(marches: MarchesSnapshot, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val top = strongestToday(marches.markets?.followed.orEmpty())
    val proposals = pendingProposals(marches).size
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(CardCorner),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TrendIcon(colors.primary, colors.primaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Marchés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val d1 = top?.d1
                if (top == null || d1 == null) {
                    Text("Questions suivies et propositions", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    Text(top.question, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${headline(top)} · ${deltaLabel(d1)} sur 24 h",
                        modifier = Modifier.clearAndSetSemantics { contentDescription = "${headline(top)}, ${spokenDelta(d1, "24 h")}" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = trendOf(d1).color(),
                    )
                }
                if (proposals > 0) {
                    Text(
                        "$proposals proposition${if (proposals > 1) "s" else ""} à trier",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Intentions à traiter et la plus proche de l'échéance. */
@Composable
private fun MemoireCard(memoire: MemoireSnapshot, later: Set<Long>, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val now = LocalDateTime.now()
    val open = memoire.memory?.intentions?.let { openIntentions(it, now, later) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(CardCorner),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteIcon(colors.primary, colors.primaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Mémoire", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val next = open?.firstOrNull()
                if (open == null || next == null) {
                    Text(
                        if (open == null) "Intentions, journal et ce que Kairos sait de toi" else "Aucune intention à traiter",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "${open.size} intention${if (open.size > 1) "s" else ""} à traiter",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(next.what, timeLeft(next.expires, now)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Carnet : page et lignes. */
@Composable
private fun NoteIcon(line: Color, fill: Color) = Canvas(Modifier.size(44.dp).background(fill, RoundedCornerShape(12.dp)).padding(9.dp)) {
    val r = CornerRadius(3.dp.toPx())
    val stroke = 2.5.dp.toPx()
    drawRoundRect(line.copy(alpha = 0.35f), Offset(size.width * 0.1f, 0f), Size(size.width * 0.8f, size.height), r)
    for (i in 0 until 3) {
        val y = size.height * (0.28f + i * 0.22f)
        drawLine(line, Offset(size.width * 0.25f, y), Offset(size.width * (if (i == 2) 0.55f else 0.75f), y), stroke, StrokeCap.Round)
    }
}

/** Courbe montante. */
@Composable
private fun TrendIcon(line: Color, fill: Color) = Canvas(Modifier.size(44.dp).background(fill, RoundedCornerShape(12.dp)).padding(9.dp)) {
    val w = size.width
    val h = size.height
    val pts = listOf(Offset(0f, h * 0.8f), Offset(w * 0.35f, h * 0.5f), Offset(w * 0.6f, h * 0.65f), Offset(w, h * 0.15f))
    for (i in 0 until pts.size - 1) drawLine(line, pts[i], pts[i + 1], 2.5.dp.toPx(), StrokeCap.Round)
    drawCircle(line, 3.dp.toPx(), pts.last())
}

/** Page de calendrier : bandeau et grille. */
@Composable
private fun CalendarIcon(line: Color, fill: Color) = Canvas(Modifier.size(44.dp).background(fill, RoundedCornerShape(12.dp)).padding(9.dp)) {
    val g = 2.dp.toPx()
    val r = CornerRadius(2.dp.toPx())
    val head = size.height * 0.26f
    drawRoundRect(line, Offset.Zero, Size(size.width, head), r)
    val cell = (size.width - g * 2) / 3
    val rowH = (size.height - head - g * 3) / 2
    for (row in 0 until 2) for (col in 0 until 3) {
        drawRoundRect(line.copy(alpha = if (row == 0 && col == 1) 1f else 0.4f), Offset(col * (cell + g), head + g + row * (rowH + g)), Size(cell, rowH), r)
    }
}

/** Pile de pièces. */
@Composable
private fun CoinsIcon(line: Color, fill: Color) = Canvas(Modifier.size(44.dp).background(fill, RoundedCornerShape(12.dp)).padding(9.dp)) {
    val h = size.height / 5
    val r = CornerRadius(h / 2)
    for (i in 0 until 4) {
        val inset = if (i % 2 == 0) 0f else size.width * 0.08f
        drawRoundRect(line.copy(alpha = 1f - i * 0.18f), Offset(inset, size.height - (i + 1) * (h + 1.dp.toPx())), Size(size.width - inset * 2, h), r)
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
