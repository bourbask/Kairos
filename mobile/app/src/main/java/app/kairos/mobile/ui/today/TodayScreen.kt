package app.kairos.mobile.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Card
import app.kairos.mobile.data.Chip
import app.kairos.mobile.data.Mark
import app.kairos.mobile.data.Repository
import app.kairos.mobile.data.Snapshot
import app.kairos.mobile.data.Tone
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Fr = Locale.FRENCH

/**
 * Accueil : salutation, lendemain, pile de décisions, fil du jour.
 * Pincer bascule sur la semaine (bouton « Semaine » équivalent), tirer actualise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    repository: Repository,
    padding: PaddingValues,
    onOpenDecisions: () -> Unit,
    onOpenSettings: () -> Unit,
    onUnauthorized: () -> Unit,
    decisionsAnchor: Modifier = Modifier,
) {
    val snapshot by repository.snapshot.collectAsState()
    val marks by repository.marks.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    var week by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refresh()
            } catch (_: ApiError.Unauthorized) {
                onUnauthorized()
            } finally {
                now = LocalDateTime.now()
                refreshing = false
            }
        }
    }

    // Actualise à chaque retour au premier plan : l'écran du matin n'affiche pas la veille.
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = LocalDateTime.now()
        }
    }
    BackHandler(enabled = week) { week = false }

    fun markWithUndo(day: String, card: Card, mark: Mark) {
        scope.launch {
            repository.mark(day, card.key, mark)
            snackbar.currentSnackbarData?.dismiss()
            val label = if (mark == Mark.Fait) "Fait" else "Plus tard"
            val result = snackbar.showSnackbar("$label · ${card.title}", actionLabel = "Annuler", withDismissAction = false)
            if (result == SnackbarResult.ActionPerformed) repository.mark(day, card.key, null)
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = ::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .pinch(onPinchIn = { week = true }, onSpread = { week = false }),
        ) {
            AnimatedContent(
                targetState = week,
                transitionSpec = { Motion.zoom(outward = targetState) },
                label = "zoom",
            ) { isWeek ->
                if (isWeek) {
                    WeekScreen(
                        snapshot = snapshot,
                        marks = marks,
                        bottom = padding.calculateBottomPadding(),
                        onRefresh = ::refresh,
                        onBack = { week = false },
                    )
                } else {
                    DayContent(
                        snapshot = snapshot,
                        marks = marks,
                        now = now,
                        bottom = padding.calculateBottomPadding(),
                        onRefresh = ::refresh,
                        onWeek = { week = true },
                        onSettings = onOpenSettings,
                        onOpenDecisions = onOpenDecisions,
                        decisionsAnchor = decisionsAnchor,
                        onMark = ::markWithUndo,
                        onUnmark = { day, card -> scope.launch { repository.mark(day, card.key, null) } },
                    )
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp),
        ) { data ->
            Snackbar(
                data,
                shape = MaterialTheme.shapes.medium,
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                actionColor = MaterialTheme.colorScheme.primaryContainer,
            )
        }
    }
}

@Composable
private fun DayContent(
    snapshot: Snapshot,
    marks: Map<String, Map<String, Mark>>,
    now: LocalDateTime,
    bottom: Dp,
    onRefresh: () -> Unit,
    onWeek: () -> Unit,
    onSettings: () -> Unit,
    onOpenDecisions: () -> Unit,
    decisionsAnchor: Modifier,
    onMark: (String, Card, Mark) -> Unit,
    onUnmark: (String, Card) -> Unit,
) {
    val todayKey = now.toLocalDate().toString()
    val today = snapshot.days.firstOrNull { it.day == todayKey }
    val tomorrow = snapshot.days.firstOrNull { it.day == now.toLocalDate().plusDays(1).toString() }
    val items = remember(today, marks, now) {
        timeline(today?.cards.orEmpty(), marks[todayKey].orEmpty(), now.toLocalTime())
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom + 16.dp),
    ) {
        if (snapshot.offline) item(key = "offline") { OfflineBanner(snapshot.fetchedAt, onRefresh) }
        item(key = "header") { Header(now, tomorrow?.work, onWeek, onRefresh, onSettings) }
        if (snapshot.decisions.isNotEmpty()) {
            item(key = "decisions") { DecisionsPeek(snapshot.decisions.size, onOpenDecisions, decisionsAnchor) }
        }
        item(key = "label") { SectionLabel("Fil du jour") }
        if (items.isEmpty()) {
            item(key = "empty") { EmptyDay(loaded = snapshot.fetchedAt != null) }
        }
        items(items, key = { it.card.key }) { item ->
            val marked = item.phase == Phase.Fait || item.phase == Phase.PlusTard
            TimelineRow(
                item = item,
                expanded = expanded == item.card.key,
                onToggle = { expanded = if (expanded == item.card.key) null else item.card.key },
                onDone = { onMark(todayKey, item.card, Mark.Fait) },
                onLater = { onMark(todayKey, item.card, Mark.PlusTard) },
                onRestore = { onUnmark(todayKey, item.card) },
                modifier = Modifier.animateItem(),
                swipeEnabled = !marked,
            )
        }
    }
}

@Composable
private fun Header(now: LocalDateTime, tomorrowWork: String?, onWeek: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Fr)).replaceFirstChar { it.titlecase(Fr) }
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(greeting(now.toLocalTime()), style = MaterialTheme.typography.headlineMedium)
            Text(
                listOfNotNull(date, tomorrowLine(tomorrowWork)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRefresh, modifier = Modifier.semantics { contentDescription = "Actualiser" }) {
                Text("↻", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill("Semaine", onWeek)
            TextButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "Réglages" }) {
                Text("⚙", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun Pill(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun OfflineBanner(fetchedAt: String?, onRetry: () -> Unit) {
    val since = fetchedAt?.let {
        runCatching {
            val t = LocalDateTime.parse(it)
            if (t.toLocalDate() == LocalDate.now()) "à " + t.format(DateTimeFormatter.ofPattern("H:mm"))
            else "du " + t.format(DateTimeFormatter.ofPattern("d MMMM", Fr))
        }.getOrNull()
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Hors ligne" + (since?.let { " · dernier état $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Réessayer") }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(Fr),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Coins de la carte de tête de l'aperçu (la pile de décisions en naît). */
internal val DecisionsPeekCorner = 18.dp

/** Aperçu de la pile : trois cartes décalées, celle du dessus en couleur principale. */
@Composable
private fun DecisionsPeek(count: Int, onOpen: () -> Unit, anchor: Modifier) {
    val shape = RoundedCornerShape(DecisionsPeekCorner)
    val surface = MaterialTheme.colorScheme.surface
    val line = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .height(78.dp),
    ) {
        for ((inset, top, alpha) in listOf(Triple(16.dp, 14.dp, 0.6f), Triple(8.dp, 7.dp, 0.85f))) {
            Surface(
                shape = shape,
                color = surface,
                border = BorderStroke(1.dp, line),
                modifier = Modifier
                    .padding(start = inset, end = inset, top = top)
                    .fillMaxWidth()
                    .height(62.dp)
                    .alpha(alpha),
            ) {}
        }
        Surface(
            onClick = onOpen,
            shape = shape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = anchor
                .fillMaxWidth()
                .height(66.dp)
                .shadow(8.dp, shape, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$count", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (count > 1) "décisions en attente" else "décision en attente",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${decisionsEta(count)} · glisse pour trier",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.85f),
                    )
                }
                Text("›", fontSize = 26.sp)
            }
        }
    }
}

@Composable
private fun EmptyDay(loaded: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mascot(MascotState.Repos, size = 56.dp, onBackground = MaterialTheme.colorScheme.background)
        Spacer(Modifier.height(12.dp))
        Text(
            if (loaded) "Rien de prévu aujourd'hui." else "Le fil du jour arrive…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Un élément du fil : rail chronologique à gauche, carte glissable à droite. */
@Composable
private fun TimelineRow(
    item: TimelineItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onRestore: () -> Unit,
    swipeEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    val bg = MaterialTheme.colorScheme.background
    val dimmed = item.phase != Phase.Maintenant && item.phase != Phase.Ensuite
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 14.dp, end = 14.dp)) {
        Box(
            Modifier
                .width(22.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = 5.dp.toPx()
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    val c = Offset(x, 22.dp.toPx())
                    val r = 5.5.dp.toPx()
                    val ring = if (dimmed) line else primary
                    drawCircle(if (item.phase == Phase.Maintenant) primary else bg, r, c)
                    drawCircle(ring, r, c, style = Stroke(2.dp.toPx()))
                },
        )
        SwipeRow(
            enabled = swipeEnabled,
            onRight = onDone,
            onLeft = onLater,
            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
        ) {
            ItemCard(item, expanded, onToggle, onDone, onLater, onRestore, swipeEnabled)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(
    item: TimelineItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onRestore: () -> Unit,
    actionable: Boolean,
) {
    val card = item.card
    val marked = item.phase == Phase.Fait || item.phase == Phase.PlusTard
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (marked) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .alpha(if (marked) 0.55f else if (item.phase == Phase.Passe) 0.75f else 1f)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (item.phase == Phase.Fait) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f),
                )
                val trailing = when (item.phase) {
                    Phase.Fait -> "fait"
                    Phase.PlusTard -> "plus tard"
                    else -> card.time
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(trailing, style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }
            card.body?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 2.dp))
            }
            if (card.chips.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.chips.forEach { ChipView(it) }
                }
            }
            AnimatedVisibility(expanded, enter = Motion.expand, exit = Motion.collapse) {
                Column(Modifier.padding(top = 10.dp)) {
                    card.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (actionable) {
                            Pill("Fait ✓", onDone)
                            Pill("Plus tard", onLater)
                        } else {
                            Pill("Remettre dans le fil", onRestore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChipView(chip: Chip) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (chip.tone) {
        Tone.Neutre -> scheme.primaryContainer to scheme.onPrimaryContainer
        Tone.Attention -> scheme.tertiary.copy(alpha = 0.18f) to scheme.tertiary.darken()
        Tone.Urgent -> scheme.error.copy(alpha = 0.14f) to scheme.error
    }
    Text(
        chip.text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

/** Ambre lisible sur fond clair ; inchangé quand la couleur est déjà claire (thème sombre). */
private fun Color.darken(): Color =
    if (red + green + blue > 2.2f) this else Color(red * 0.64f, green * 0.62f, blue * 0.6f, alpha)

/**
 * Glissement horizontal : → positif ([rightLabel]), ← négatif ([leftLabel]). Le libellé
 * de l'action apparaît sous l'élément pendant le geste ; vibration au seuil.
 * [rightStays] : → ouvre un choix sur place, l'élément revient au lieu de partir.
 */
@Composable
internal fun SwipeRow(
    enabled: Boolean,
    onRight: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    rightLabel: String = "Fait ✓",
    leftLabel: String = "Plus tard",
    leftColor: Color = MaterialTheme.colorScheme.tertiary,
    rightStays: Boolean = false,
    content: @Composable () -> Unit,
) {
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dx by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var armed by remember { mutableStateOf(false) }
    val threshold = width * 0.32f

    Box(modifier.onSizeChanged { width = it.width }) {
        if (dx != 0f) {
            val positive = dx > 0
            Row(
                Modifier
                    .matchParentSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (positive) MaterialTheme.colorScheme.primary else leftColor)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = if (positive) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (positive) rightLabel else leftLabel,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alpha((abs(dx) / threshold).coerceIn(0.35f, 1f)),
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(dx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    state = rememberDraggableState { delta ->
                        dx += delta
                        val over = abs(dx) > threshold
                        if (over != armed) {
                            armed = over
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    onDragStopped = { velocity ->
                        val fling = abs(velocity) > 2000f && abs(dx) > width * 0.12f && velocity * dx > 0
                        val commit = armed || fling
                        val positive = dx > 0
                        val target = if (!commit || (rightStays && positive)) 0f else if (positive) width.toFloat() else -width.toFloat()
                        scope.launch {
                            animate(dx, target, initialVelocity = velocity, animationSpec = Motion.settle()) { v, _ -> dx = v }
                            if (commit) {
                                if (positive) right() else left()
                            }
                            dx = 0f
                            armed = false
                        }
                    },
                ),
        ) { content() }
    }
}

/** Pincer (deux doigts qui se rapprochent) ou écarter ; un seul doigt laisse défiler la liste. */
internal fun Modifier.pinch(onPinchIn: () -> Unit, onSpread: () -> Unit): Modifier = composed {
    val pinchIn by rememberUpdatedState(onPinchIn)
    val spread by rememberUpdatedState(onSpread)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoom = 1f
            var fired = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    if (!fired && zoom < 0.75f) { fired = true; pinchIn() }
                    if (!fired && zoom > 1.33f) { fired = true; spread() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
