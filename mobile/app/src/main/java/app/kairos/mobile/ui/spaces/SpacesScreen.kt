package app.kairos.mobile.ui.spaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.AgendaSnapshot
import app.kairos.mobile.data.DocumentsSnapshot
import app.kairos.mobile.data.FinancesSnapshot
import app.kairos.mobile.data.MaisonSnapshot
import app.kairos.mobile.data.MarchesSnapshot
import app.kairos.mobile.data.MediasSnapshot
import app.kairos.mobile.data.MemoireSnapshot
import app.kairos.mobile.ui.documents.nextDeadline
import app.kairos.mobile.ui.documents.overdueCount
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.LocalAmountsMasked
import app.kairos.mobile.ui.kit.SpaceRow
import app.kairos.mobile.ui.kit.StatePill
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.kit.amountText
import app.kairos.mobile.ui.kit.barTone
import app.kairos.mobile.ui.marches.deltaLabel
import app.kairos.mobile.ui.marches.headline
import app.kairos.mobile.ui.marches.pendingProposals
import app.kairos.mobile.ui.marches.spokenDelta
import app.kairos.mobile.ui.marches.strongestToday
import app.kairos.mobile.ui.medias.nextRelease
import app.kairos.mobile.ui.medias.readyCount
import app.kairos.mobile.ui.medias.stuck
import app.kairos.mobile.ui.memoire.openIntentions
import app.kairos.mobile.ui.memoire.timeLeft
import app.kairos.mobile.ui.overlay.Origin
import app.kairos.mobile.ui.overlay.Overlay
import app.kairos.mobile.ui.overlay.overlayAnchor
import app.kairos.mobile.ui.overlay.rememberOverlayState
import app.kairos.mobile.ui.overlay.underOverlays
import app.kairos.mobile.ui.theme.KairosColors
import java.time.LocalDate
import java.time.LocalDateTime

/** Coins des rangées (thème : `shapes.large`), repris par l'espace qui en naît. */
private val CardCorner = 22.dp

/**
 * Onglet Espaces : une rangée par espace, toujours avec un état (valeur clé, chargement, ou
 * « Indisponible » et « Réessayer »). L'espace ouvert naît de sa rangée et y retourne (retour
 * prédictif compris) ; la barre du bas et Kairos restent au-dessus. Réglages en pied.
 */
@Composable
fun SpacesScreen(
    padding: PaddingValues,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    maison: MaisonSnapshot,
    maisonOpen: Boolean,
    onOpenMaison: () -> Unit,
    onCloseMaison: () -> Unit,
    finances: FinancesSnapshot,
    financesOpen: Boolean,
    onOpenFinances: () -> Unit,
    onAfford: () -> Unit,
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
    medias: MediasSnapshot,
    mediasOpen: Boolean,
    onOpenMedias: () -> Unit,
    onCloseMedias: () -> Unit,
    mediasContent: @Composable () -> Unit,
    documents: DocumentsSnapshot,
    documentsOpen: Boolean,
    onOpenDocuments: () -> Unit,
    onCloseDocuments: () -> Unit,
    documentsContent: @Composable () -> Unit,
    mimirConfigured: Boolean,
    mimirOpen: Boolean,
    onOpenMimir: () -> Unit,
    onCloseMimir: () -> Unit,
    mimirContent: @Composable () -> Unit,
    maisonContent: @Composable () -> Unit,
) {
    val overlay = rememberOverlayState(maisonOpen, Origin.Anchor)
    val financesOverlay = rememberOverlayState(financesOpen, Origin.Anchor)
    val agendaOverlay = rememberOverlayState(agendaOpen, Origin.Anchor)
    val marchesOverlay = rememberOverlayState(marchesOpen, Origin.Anchor)
    val memoireOverlay = rememberOverlayState(memoireOpen, Origin.Anchor)
    val mediasOverlay = rememberOverlayState(mediasOpen, Origin.Anchor)
    val documentsOverlay = rememberOverlayState(documentsOpen, Origin.Anchor)
    val mimirOverlay = rememberOverlayState(mimirOpen, Origin.Anchor)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant)) {
        Column(
            Modifier
                .fillMaxSize()
                .underOverlays(rtl, overlay, financesOverlay, agendaOverlay, marchesOverlay, memoireOverlay, mediasOverlay, documentsOverlay, mimirOverlay)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Espaces",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() }.padding(bottom = 10.dp),
            )
            // Ordre fixe : la main retrouve chaque espace au même endroit.
            MaisonRow(maison, onOpenMaison, onRetry, Modifier.overlayAnchor(overlay, CardCorner))
            FinancesRow(finances, onOpenFinances, onAfford, onRetry, Modifier.overlayAnchor(financesOverlay, CardCorner))
            AgendaRow(agenda, onOpenAgenda, onRetry, Modifier.overlayAnchor(agendaOverlay, CardCorner))
            MarchesRow(marches, onOpenMarches, onRetry, Modifier.overlayAnchor(marchesOverlay, CardCorner))
            MemoireRow(memoire, memoireLater, onOpenMemoire, onRetry, Modifier.overlayAnchor(memoireOverlay, CardCorner))
            MediasRow(medias, onOpenMedias, onRetry, Modifier.overlayAnchor(mediasOverlay, CardCorner))
            DocumentsRow(documents, onOpenDocuments, onRetry, Modifier.overlayAnchor(documentsOverlay, CardCorner))
            MimirRow(mimirConfigured, onOpenMimir, Modifier.overlayAnchor(mimirOverlay, CardCorner))
            Spacer(Modifier.height(6.dp))
            SpaceRow("Réglages", onOpenSettings, icon = { GearIcon() }) { Status("Thème, lecture à voix haute, mise à jour") }
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
        Overlay(mediasOverlay, mediasOpen, onClose = onCloseMedias, anchorColor = MaterialTheme.colorScheme.surface) {
            mediasContent()
        }
        Overlay(documentsOverlay, documentsOpen, onClose = onCloseDocuments, anchorColor = MaterialTheme.colorScheme.surface) {
            documentsContent()
        }
        Overlay(mimirOverlay, mimirOpen, onClose = onCloseMimir, anchorColor = MaterialTheme.colorScheme.surface) {
            mimirContent()
        }
    }
}

/** Ligne d'état : un seul niveau d'emphase ; [attention] pour ce qui empêche de voir l'espace. */
@Composable
private fun Status(text: String, modifier: Modifier = Modifier, attention: Boolean = false) = Text(
    text,
    modifier = modifier,
    style = MaterialTheme.typography.bodyMedium,
    color = if (attention) KairosColors.current.attentionText else MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
)

/**
 * Rangée d'un espace selon [load] : chargement, indisponible (avec « Réessayer »), ou
 * [ready] (état) et [pill] (ce qui attend une action, avec son ton).
 */
@Composable
private fun StateRow(
    name: String,
    load: SpaceLoad,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    pill: Pair<String, Tone>? = null,
    extra: @Composable RowScope.() -> Unit = {},
    ready: @Composable () -> Unit,
) {
    SpaceRow(
        name,
        onClick,
        icon,
        modifier,
        trailing = {
            when (load) {
                SpaceLoad.Down -> TextButton(onClick = onRetry, modifier = Modifier.semantics { contentDescription = "Réessayer : $name" }) {
                    Text("Réessayer")
                }
                SpaceLoad.Ready -> {
                    pill?.let { (text, tone) -> StatePill(text, tone = tone) }
                    extra()
                }
                SpaceLoad.Loading -> Unit
            }
        },
    ) {
        when (load) {
            SpaceLoad.Loading -> Status("Chargement…")
            SpaceLoad.Down -> Status("Indisponible", attention = true)
            SpaceLoad.Ready -> ready()
        }
    }
}

@Composable
private fun MaisonRow(maison: MaisonSnapshot, onClick: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val lots = maison.inventory.lots.size
    val urgent = maison.inventory.zones.sumOf { it.urgent }
    val toUnpack = maison.orders.any { o -> o.lines.any { !it.done } }
    val load = spaceLoad(maison.fetchedAt != null, if (maison.offline) maison.reason ?: "Hors ligne" else null)
    val pill = when {
        urgent > 0 -> "$urgent à finir" to Tone.Urgent
        toUnpack -> "à ranger" to Tone.Attention
        else -> null
    }
    StateRow("Inventaire", load, onClick, onRetry, modifier, { PlanIcon() }, pill) {
        // La commande à ranger reste dite quand la pastille parle déjà des produits à finir.
        Status(listOfNotNull(countLabel(lots, "produit"), "commande à ranger".takeIf { toUnpack && urgent > 0 }).joinToString(" · "))
    }
}

@Composable
private fun FinancesRow(finances: FinancesSnapshot, onClick: () -> Unit, onAfford: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val cycle = finances.cycle
    val masked = LocalAmountsMasked.current
    val load = spaceLoad(cycle != null || finances.wealth != null, worstIssue(finances.cycleIssue, finances.wealthIssue))
    val over = cycle?.envelopes.orEmpty().count { barTone(it.spent, it.planned) == Tone.Urgent }
    StateRow(
        "Finances", load, onClick, onRetry, modifier, { CoinsIcon() },
        pill = (countLabel(over, "dépassée") to Tone.Urgent).takeIf { over > 0 },
        extra = {
            if (cycle != null) {
                TextButton(onClick = onAfford, modifier = Modifier.semantics { contentDescription = "Puis-je me permettre ?" }) { Text("Puis-je… ?") }
            }
        },
    ) {
        val available = cycle?.available
        if (available == null) {
            Status("Pas de cycle en cours")
        } else {
            val days = cycle.daysLeft?.let { " · $it j" }.orEmpty()
            Status(
                "Reste à vivre ${amountText(available, masked, cents = false)}$days",
                Modifier.clearAndSetSemantics {
                    contentDescription = "Reste à vivre ${if (masked) "masqué" else amountText(available, false, cents = false)}" +
                        (cycle.daysLeft?.let { ", $it jours" } ?: "")
                },
            )
        }
    }
}

@Composable
private fun AgendaRow(agenda: AgendaSnapshot, onClick: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val load = spaceLoad(agenda.week != null || agenda.days.isNotEmpty(), agenda.issue)
    StateRow("Agenda", load, onClick, onRetry, modifier, { CalendarIcon() }) {
        Status(agendaStatus(agenda.week, LocalDate.now()))
    }
}

/** Question qui a le plus bougé sur 24 h, sens dit par la flèche et le mot ; propositions à trier. */
@Composable
private fun MarchesRow(marches: MarchesSnapshot, onClick: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val top = strongestToday(marches.markets?.followed.orEmpty())
    val proposals = pendingProposals(marches).size
    val load = spaceLoad(marches.markets != null, marches.issue)
    StateRow(
        "Marchés", load, onClick, onRetry, modifier, { TrendIcon() },
        pill = ("$proposals à trier" to Tone.Attention).takeIf { proposals > 0 },
    ) {
        val d1 = top?.d1
        if (top == null || d1 == null) {
            Status("Pas de mouvement sur 24 h")
        } else {
            Status(
                "${top.question} · ${headline(top)} ${deltaLabel(d1)}",
                Modifier.clearAndSetSemantics { contentDescription = "${top.question}, ${headline(top)}, ${spokenDelta(d1, "24 h")}" },
            )
        }
    }
}

/** Intentions à traiter et la plus proche de l'échéance. */
@Composable
private fun MemoireRow(memoire: MemoireSnapshot, later: Set<Long>, onClick: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val now = LocalDateTime.now()
    val open = memoire.memory?.intentions?.let { openIntentions(it, now, later) }.orEmpty()
    val load = spaceLoad(memoire.memory != null, memoire.issue)
    StateRow(
        "Mémoire", load, onClick, onRetry, modifier, { NoteIcon() },
        pill = ("${open.size} à traiter" to Tone.Attention).takeIf { open.isNotEmpty() },
    ) {
        val next = open.firstOrNull()
        Status(if (next == null) "Aucune intention à traiter" else listOfNotNull(next.what, timeLeft(next.expires, now)).joinToString(" · "))
    }
}

/** Nombre d'éléments prêts à regarder et la prochaine sortie ; téléchargements bloqués. */
@Composable
private fun MediasRow(medias: MediasSnapshot, onClick: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val m = medias.medias
    val blocked = m?.downloads?.let(::stuck) ?: 0
    val load = spaceLoad(m != null, medias.issue)
    StateRow(
        "Médias", load, onClick, onRetry, modifier, { PlayIcon() },
        pill = (countLabel(blocked, "bloqué") to Tone.Urgent).takeIf { blocked > 0 },
    ) {
        if (m != null) {
            val n = readyCount(m)
            val ready = if (n == 0) "Rien de prêt à regarder" else "${countLabel(n, "prêt")} à regarder"
            Status(listOfNotNull(ready, nextRelease(m, LocalDate.now())).joinToString(" · "))
        }
    }
}

/** Prochaine échéance et nombre de retards. */
@Composable
private fun DocumentsRow(documents: DocumentsSnapshot, onClick: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val d = documents.documents
    val today = LocalDate.now()
    val late = d?.let { overdueCount(it, today) } ?: 0
    val load = spaceLoad(d != null, documents.issue)
    StateRow(
        "Documents", load, onClick, onRetry, modifier, { FolderIcon() },
        pill = ("$late en retard" to Tone.Urgent).takeIf { late > 0 },
    ) {
        if (d != null) Status(nextDeadline(d, today) ?: "Aucune échéance à venir")
    }
}

/**
 * Site web affiché tel quel : pas d'état serveur à charger, seulement le lien à coller
 * tant qu'il n'est pas enregistré sur l'appareil.
 */
@Composable
private fun MimirRow(configured: Boolean, onClick: () -> Unit, modifier: Modifier) {
    SpaceRow(
        "Mimir",
        onClick,
        { GlobeIcon() },
        modifier,
        trailing = { if (!configured) StatePill("lien à coller", tone = Tone.Attention) },
    ) {
        Status("Le Crible 2027")
    }
}

/** Fond commun des icônes d'espace : 40 dp, couleur de l'espace. */
private val IconSize = 40.dp

@Composable
private fun iconModifier(): Modifier = Modifier.size(IconSize).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(8.dp)

@Composable
private fun GearIcon() = Box(iconModifier()) {
    Icon(KairosIcons.Gear, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

/** Globe : un site web. */
@Composable
private fun GlobeIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
    val stroke = 2.dp.toPx()
    val r = size.minDimension / 2 - stroke / 2
    val c = center
    drawCircle(line.copy(alpha = 0.35f), radius = r, center = c)
    drawCircle(line, radius = r, center = c, style = Stroke(stroke))
    drawOval(line, Offset(c.x - r * 0.45f, c.y - r), Size(r * 0.9f, r * 2), style = Stroke(stroke))
    drawLine(line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), stroke)
}

/** Dossier. */
@Composable
private fun FolderIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
    val w = size.width
    val h = size.height
    drawRoundRect(line.copy(alpha = 0.35f), Offset(0f, h * 0.18f), Size(w * 0.5f, h * 0.2f), CornerRadius(2.dp.toPx()))
    drawRoundRect(line, Offset(0f, h * 0.28f), Size(w, h * 0.56f), CornerRadius(3.dp.toPx()))
}

/** Écran et bouton lecture. */
@Composable
private fun PlayIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
    val w = size.width
    val h = size.height
    drawRoundRect(line.copy(alpha = 0.35f), Offset(0f, h * 0.12f), Size(w, h * 0.76f), CornerRadius(3.dp.toPx()))
    val play = Path().apply {
        moveTo(w * 0.4f, h * 0.32f)
        lineTo(w * 0.68f, h * 0.5f)
        lineTo(w * 0.4f, h * 0.68f)
        close()
    }
    drawPath(play, line)
}

/** Carnet : page et lignes. */
@Composable
private fun NoteIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
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
private fun TrendIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
    val w = size.width
    val h = size.height
    val pts = listOf(Offset(0f, h * 0.8f), Offset(w * 0.35f, h * 0.5f), Offset(w * 0.6f, h * 0.65f), Offset(w, h * 0.15f))
    for (i in 0 until pts.size - 1) drawLine(line, pts[i], pts[i + 1], 2.5.dp.toPx(), StrokeCap.Round)
    drawCircle(line, 3.dp.toPx(), pts.last())
}

/** Page de calendrier : bandeau et grille. */
@Composable
private fun CalendarIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
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
private fun CoinsIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
    val h = size.height / 5
    val r = CornerRadius(h / 2)
    for (i in 0 until 4) {
        val inset = if (i % 2 == 0) 0f else size.width * 0.08f
        drawRoundRect(line.copy(alpha = 1f - i * 0.18f), Offset(inset, size.height - (i + 1) * (h + 1.dp.toPx())), Size(size.width - inset * 2, h), r)
    }
}

/** Miniature du plan de la cuisine. */
@Composable
private fun PlanIcon(line: Color = MaterialTheme.colorScheme.primary) = Canvas(iconModifier()) {
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
