package app.kairos.mobile.ui.marches

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.FollowedMarket
import app.kairos.mobile.data.MarchesSnapshot
import app.kairos.mobile.data.MarketDetail
import app.kairos.mobile.data.Repository
import app.kairos.mobile.data.Scenario
import app.kairos.mobile.ui.decide.DeckScreen
import app.kairos.mobile.ui.finances.dayLabel
import app.kairos.mobile.ui.finances.syncLabel
import app.kairos.mobile.ui.kit.AlertRow
import app.kairos.mobile.ui.kit.Columns
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.LocalNotifier
import app.kairos.mobile.ui.kit.PrimaryAction
import app.kairos.mobile.ui.kit.primaryActionClearance
import app.kairos.mobile.ui.kit.SpaceHeader
import app.kairos.mobile.ui.finances.issueBanner
import app.kairos.mobile.ui.kit.LineChart
import app.kairos.mobile.ui.kit.Metric
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.Sparkline
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.maison.ChevronBackIcon
import app.kairos.mobile.ui.overlay.Origin
import app.kairos.mobile.ui.overlay.Overlay
import app.kairos.mobile.ui.overlay.anchorTo
import app.kairos.mobile.ui.overlay.overlayAnchor
import app.kairos.mobile.ui.overlay.rememberOverlayState
import app.kairos.mobile.ui.overlay.underOverlays
import app.kairos.mobile.ui.talk.IconButton
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.InFlight
import app.kairos.mobile.ui.kit.SwipeRow
import app.kairos.mobile.ui.today.completeCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val RowCorner = 16.dp

/** Couleur d'un sens de variation : fougère à la hausse, brique à la baisse (toujours avec flèche et texte). */
@Composable
fun Trend.color(): Color = when (this) {
    Trend.Up -> MaterialTheme.colorScheme.primary
    Trend.Down -> MaterialTheme.colorScheme.error
    Trend.Flat -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Espace Marchés : questions suivies (plus forte variation d'abord ; glisser ← = ne plus
 * suivre, annulable), scénarios, alertes récentes. Une question ouvre son détail en panneau ;
 * les propositions se trient dans la pile Décider (→ suivre, ← ignorer). Lecture seule du
 * monde, dernier état gardé hors ligne.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarchesScreen(
    repository: Repository,
    padding: PaddingValues,
    appScope: CoroutineScope,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val snapshot by repository.marches.collectAsState()
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val notifier = LocalNotifier.current
    var refreshing by remember { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var deckOpen by rememberSaveable { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val busy = remember { InFlight() }
    val detail = rememberOverlayState(detailId != null, Origin.Anchor)
    val deck = rememberOverlayState(deckOpen, Origin.Anchor)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    fun refresh(force: Boolean = true) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refreshMarches(force)
            } catch (_: ApiError.Unauthorized) {
                unauthorized()
            } finally {
                refreshing = false
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        refresh(force = false)
        onPauseOrDispose {}
    }

    suspend fun undoWindow(text: String): Boolean = notifier.undo(text)

    // Depuis appScope : quitter l'espace pendant la fenêtre n'abandonne pas l'action.
    fun unfollow(m: FollowedMarket) {
        appScope.launch {
            val out = try {
                completeCard(
                    unfollowCard(m),
                    null,
                    mark = { mark -> hidden = if (mark == null) hidden - m.id else hidden + m.id },
                    undoWindow = { undoWindow("Plus suivie · ${m.question}") },
                    call = repository::tool,
                    busy = busy,
                )
            } catch (_: ApiError.Unauthorized) {
                return@launch unauthorized()
            }
            when (out) {
                is DoneOutcome.Done -> {
                    try {
                        repository.refreshMarches()
                    } catch (_: ApiError.Unauthorized) {
                        return@launch unauthorized()
                    }
                    // Relue sans elle : plus besoin de la cacher, et suivie à nouveau plus tard, elle doit réapparaître.
                    if (repository.marches.value.issue == null) hidden = hidden - m.id
                }
                is DoneOutcome.Refused -> notifier.show(out.message)
                else -> Unit
            }
        }
    }

    val pending = pendingProposals(snapshot)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant)) {
        // L'action principale vit sous les panneaux : masquée au lecteur d'écran avec le reste quand l'un est ouvert.
        Box(Modifier.fillMaxSize().underOverlays(rtl, detail, deck).background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                SpaceHeader("Marchés", onClose)
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(top = 8.dp, bottom = padding.calculateBottomPadding() + if (pending.isNotEmpty()) primaryActionClearance() else 24.dp),
                    ) {
                        snapshot.issue?.let { issue ->
                            val (title, detail) = issueBanner("Marchés", issue, snapshot.fetchedAt?.let { "Dernier état : ${syncLabel(it)}" })
                            StatusBanner(title, detail = detail, actionLabel = "Réessayer", onAction = { refresh() })
                            Spacer(Modifier.height(8.dp))
                        }
                        val markets = snapshot.markets
                        if (markets == null) {
                            Hint(snapshot.issue)
                        } else {
                            val followed = sortedByVariation(markets.followed).filter { it.id !in hidden }
                            SectionHeader("Questions suivies")
                            if (followed.isEmpty()) Caption("Aucune question suivie pour l'instant.")
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                followed.forEach { m ->
                                    MarketRow(
                                        m,
                                        onOpen = { at ->
                                            detail.anchorTo(at, RowCorner)
                                            detailId = m.id
                                        },
                                        onUnfollow = { unfollow(m) },
                                    )
                                }
                            }
                            if (markets.scenarios.isNotEmpty()) {
                                SectionHeader("Scénarios")
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    markets.scenarios.forEach { ScenarioBlock(it, markets.followed) }
                                }
                            }
                            if (markets.alerts.isNotEmpty()) {
                                SectionHeader("Alertes récentes")
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    markets.alerts.forEach { a ->
                                        AlertRow(a.text, at = a.at?.let(::syncLabel))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Action principale : trier les propositions, absente quand il n'y en a pas.
            if (pending.isNotEmpty()) {
                val n = pending.size
                PrimaryAction(
                    "Trier $n proposition${if (n > 1) "s" else ""}",
                    { deckOpen = true },
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp)
                        .overlayAnchor(deck, RowCorner),
                    icon = KairosIcons.ChevronForward,
                )
            }
        }
        Overlay(detail, detailId != null, onClose = { detailId = null }, anchorColor = MaterialTheme.colorScheme.surface) {
            // Gardé pendant la fermeture : le panneau ne se vide pas en se refermant.
            val last = remember { arrayOfNulls<String>(1) }
            detailId?.let { last[0] = it }
            last[0]?.let { id ->
                DetailPanel(
                    id,
                    snapshot,
                    repository,
                    padding,
                    onUnauthorized = unauthorized,
                    onClose = { detailId = null },
                    onUnfollow = { m ->
                        detailId = null
                        unfollow(m)
                    },
                )
            }
        }
        Overlay(deck, deckOpen, onClose = { deckOpen = false }, anchorColor = MaterialTheme.colorScheme.surface) {
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            Box(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
                DeckScreen(
                    decisions = proposalCards(pending),
                    offline = snapshot.issue != null,
                    appScope = appScope,
                    decide = { c -> decideProposal(c) { tool, args -> repository.tool(MARCHES, tool, args) } },
                    onDispatch = { c -> appScope.launch { repository.dropProposal(c.card.id, ignore = c.option == IGNORE) } },
                    onDecided = {},
                    onFinished = {
                        appScope.launch {
                            try {
                                repository.refreshMarches()
                            } catch (_: ApiError.Unauthorized) {
                                unauthorized()
                            }
                        }
                    },
                    onUnauthorized = unauthorized,
                    onNotice = { scope.launch { notifier.show(it) } },
                    onClose = { deckOpen = false },
                    directLeft = true,
                )
            }
        }
    }
}

@Composable
private fun Hint(issue: String?) =
    Caption(if (issue != null) "Rien en mémoire pour l'instant." else "Chargement…", Modifier.padding(vertical = 24.dp))

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/** Variations d'une question, chacune avec flèche, texte et période. */
@Composable
private fun Deltas(m: FollowedMarket) {
    val deltas = deltasOf(m)
    if (deltas.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        deltas.forEach { (period, d) ->
            Text(
                "${deltaLabel(d)} · $period",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = trendOf(d).color(),
                modifier = Modifier.clearAndSetSemantics { contentDescription = spokenDelta(d, period) },
            )
        }
    }
}

/**
 * Question suivie : touche = détail ; glisser ← = ne plus suivre (bouton dans le détail).
 * Le tap porte sur le même nœud que le texte et l'action « Ne plus suivre » : un seul arrêt
 * du lecteur d'écran.
 */
@Composable
private fun MarketRow(m: FollowedMarket, onOpen: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit, onUnfollow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var at by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    SwipeRow(
        enabled = true,
        onRight = {},
        onLeft = onUnfollow,
        rightLabel = "",
        leftLabel = "Ne plus suivre",
        leftColor = colors.error,
        rightStays = true,
        modifier = Modifier
            .clip(RoundedCornerShape(RowCorner))
            .clickable(onClickLabel = "Voir le détail") { at?.let(onOpen) },
    ) {
        Surface(
            shape = RoundedCornerShape(RowCorner),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { at = it },
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(m.question, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            headline(m),
                            style = if (m.closed) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        m.outcome.takeIf { it.isNotBlank() && !m.closed }?.let { Caption("« $it »", Modifier.padding(bottom = 3.dp)) }
                    }
                    readingNote(m)?.let { Caption(it) }
                    Deltas(m)
                    endText(m.end)?.let { Caption(it) }
                }
                if (m.spark.size >= 2) {
                    Spacer(Modifier.width(12.dp))
                    val trend = trendOf(m.spark.last() - m.spark.first())
                    Sparkline(m.spark, Modifier.size(width = 72.dp, height = 32.dp), color = if (m.closed) colors.onSurfaceVariant else trend.color())
                }
            }
        }
    }
}

/** Scénario et ses questions liées : signe (soutient + / contredit −) et tendance de chacune. */
@Composable
private fun ScenarioBlock(s: Scenario, followed: List<FollowedMarket>) {
    val byId = followed.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        s.links.forEach { l ->
            val m = byId[l.id]
            val d = m?.let { deltasOf(it).firstOrNull() }
            val spoken = listOfNotNull(
                signWord(l.sign),
                m?.question ?: "question plus suivie",
                m?.let(::headline),
                d?.let { (period, v) -> spokenDelta(v, period) },
            ).joinToString(", ")
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (l.sign >= 0) "+" else "−",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    m?.question ?: "Question plus suivie",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (m != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (d != null) "${headline(m)} ${deltaLabel(d.second)}" else headline(m),
                        style = MaterialTheme.typography.labelMedium,
                        color = d?.let { trendOf(it.second).color() } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Détail d'une question : relu à l'ouverture, dernier détail connu affiché entre-temps
 * ou hors ligne. Courbe 30 j, volume par jour, issue, projection, scénarios liés.
 */
@Composable
private fun DetailPanel(
    id: String,
    snapshot: MarchesSnapshot,
    repository: Repository,
    padding: PaddingValues,
    onUnauthorized: () -> Unit,
    onClose: () -> Unit,
    onUnfollow: (FollowedMarket) -> Unit,
) {
    var stale by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        stale = try {
            !repository.refreshMarket(id)
        } catch (_: ApiError.Unauthorized) {
            onUnauthorized()
            false
        }
    }
    val market = snapshot.markets?.followed?.firstOrNull { it.id == id }
    val d: MarketDetail? = snapshot.details[id]
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = padding.calculateTopPadding()),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(label = "Retour aux marchés", onClick = onClose) { ChevronBackIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(4.dp))
            Text(
                d?.question?.ifBlank { null } ?: market?.question.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            if (stale) {
                StatusBanner(if (d == null) "Détail indisponible pour l'instant." else "Détail pas à jour : dernier état connu.")
                Spacer(Modifier.height(8.dp))
            }
            market?.let { m ->
                Metric(
                    headline(m),
                    m.outcome.takeIf { it.isNotBlank() }?.let { "Issue « $it »" } ?: "Probabilité",
                    caption = listOfNotNull(readingNote(m), endText(m.end)).joinToString(" · ").ifBlank { null },
                )
                Deltas(m)
            }
            if (d == null) {
                if (!stale) Caption("Chargement…", Modifier.padding(vertical = 24.dp))
            } else {
                if (d.history.size >= 2) {
                    SectionHeader("30 derniers jours")
                    LineChart(
                        "Probabilité sur 30 jours",
                        d.history.map { it.prob },
                        startLabel = dayLabel(d.history.first().at),
                        endLabel = dayLabel(d.history.last().at),
                        format = ::percentText,
                        masked = false,
                    )
                }
                if (d.volume.isNotEmpty()) {
                    SectionHeader("Volume par jour")
                    Columns(
                        "Volume échangé par jour",
                        d.volume.map { it.value },
                        startLabel = dayLabel(d.volume.first().day),
                        endLabel = dayLabel(d.volume.last().day),
                        format = ::volumeText,
                    )
                }
                if (d.outcomes.isNotEmpty()) {
                    SectionHeader("Issues")
                    d.outcomes.forEach { o ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
                            Text(o.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(percentText(o.prob), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                val p = d.projection
                if (p?.up != null || p?.down != null) {
                    SectionHeader("Projection")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.up?.let { ProjectionLine("▲ Si ça monte", "Si la probabilité monte", it, MaterialTheme.colorScheme.primary) }
                        p.down?.let { ProjectionLine("▼ Si ça baisse", "Si la probabilité baisse", it, MaterialTheme.colorScheme.error) }
                    }
                }
                if (d.scenarios.isNotEmpty()) {
                    SectionHeader("Scénarios liés")
                    d.scenarios.forEach { s ->
                        val sign = s.links.firstOrNull { it.id == id }?.sign ?: 1
                        Text(
                            "${if (sign >= 0) "+" else "−"} ${s.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .clearAndSetSemantics { contentDescription = "${signWord(sign)} le scénario ${s.name}" },
                        )
                    }
                }
            }
            if (market != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { onUnfollow(market) }, modifier = Modifier.fillMaxWidth()) { Text("Ne plus suivre") }
            }
        }
    }
}

@Composable
private fun ProjectionLine(title: String, spoken: String, text: String, color: Color) {
    Column(Modifier.clearAndSetSemantics { contentDescription = "$spoken : $text" }) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
