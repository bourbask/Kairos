package app.kairos.mobile.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.AppRelease
import app.kairos.mobile.data.Card
import app.kairos.mobile.data.Mark
import app.kairos.mobile.data.Repository
import app.kairos.mobile.data.Snapshot
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.LocalNotifier
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.StatePill
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.SwipeRow
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.kairos.mobile.data.Tone as ChipTone

private val Fr = Locale.FRENCH

/**
 * Accueil : salutation, lendemain, pile de décisions, fil du jour.
 * Pincer bascule sur la semaine (bascule Jour / Semaine équivalente), tirer actualise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    repository: Repository,
    padding: PaddingValues,
    onOpenDecisions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgenda: (LocalDate) -> Unit,
    onUnauthorized: () -> Unit,
    appScope: CoroutineScope,
    update: AppRelease?,
    updateProgress: Float?,
    onUpdate: () -> Unit,
    decisionsAnchor: Modifier = Modifier,
    settingsAnchor: Modifier = Modifier,
) {
    val snapshot by repository.snapshot.collectAsState()
    val marks by repository.marks.collectAsState()
    val scope = rememberCoroutineScope()
    val notifier = LocalNotifier.current
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

    val haptic = LocalHapticFeedback.current
    val unauthorized by rememberUpdatedState(onUnauthorized)

    val busy = remember { InFlight() }
    val pending by busy.keys.collectAsState()

    fun notice(text: String) {
        appScope.launch { notifier.show(text) }
    }

    fun later(day: String, card: Card) {
        if (!busy.claim(card.key)) return
        scope.launch {
            try {
                repository.mark(day, card.key, Mark.PlusTard)
                if (notifier.undo("Plus tard · ${card.title}")) repository.mark(day, card.key, null)
            } finally {
                busy.release(card.key)
            }
        }
    }

    // Depuis appScope : quitter l'écran pendant la fenêtre n'abandonne pas l'action du module.
    fun complete(day: String, card: Card, choice: JsonElement?) {
        appScope.launch {
            val out = try {
                completeCard(
                    card,
                    choice,
                    mark = { repository.mark(day, card.key, it) },
                    undoWindow = { notifier.undo("Fait · ${card.title}") },
                    call = repository::reply,
                    busy = busy,
                )
            } catch (_: ApiError.Unauthorized) {
                return@launch unauthorized()
            }
            when (out) {
                is DoneOutcome.Done -> {
                    notice(out.message.ifBlank { "Fait." })
                    try {
                        repository.refresh()
                        repository.refreshMaison()
                    } catch (_: ApiError.Unauthorized) {
                        unauthorized()
                    }
                }
                is DoneOutcome.Refused -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    notice(out.message)
                }
                DoneOutcome.Local, DoneOutcome.Undone, DoneOutcome.Ignored -> Unit
            }
        }
    }

    // Choix demandé par le module (portions…) : posé sous le pouce, avant la fenêtre d'annulation.
    var choosing by remember { mutableStateOf<Pair<String, Card>?>(null) }
    var chooserOpen by remember { mutableStateOf(false) }

    fun done(day: String, card: Card) {
        if (card.done?.asks == true) {
            choosing = day to card
            chooserOpen = true
        } else {
            complete(day, card, null)
        }
    }

    BackHandler(enabled = chooserOpen) { chooserOpen = false }

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
                        onOpenDay = onOpenAgenda,
                    )
                } else {
                    DayContent(
                        snapshot = snapshot,
                        marks = marks,
                        pending = pending,
                        now = now,
                        bottom = padding.calculateBottomPadding(),
                        onRefresh = ::refresh,
                        onWeek = { week = true },
                        onSettings = onOpenSettings,
                        onOpenDecisions = onOpenDecisions,
                        decisionsAnchor = decisionsAnchor,
                        settingsAnchor = settingsAnchor,
                        onDone = ::done,
                        onLater = ::later,
                        onUnmark = { day, card -> scope.launch { repository.mark(day, card.key, null) } },
                        update = update,
                        updateProgress = updateProgress,
                        onUpdate = onUpdate,
                    )
                }
            }
        }
        AnimatedVisibility(
            chooserOpen,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = padding.calculateBottomPadding())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            enter = Motion.riseIn,
            exit = Motion.sinkOut,
        ) {
            choosing?.let { (day, card) ->
                Chooser(card, onPick = { v ->
                    chooserOpen = false
                    complete(day, card, v)
                }, onCancel = { chooserOpen = false })
            }
        }
    }
}

@Composable
private fun DayContent(
    snapshot: Snapshot,
    marks: Map<String, Map<String, Mark>>,
    pending: Set<String>,
    now: LocalDateTime,
    bottom: Dp,
    onRefresh: () -> Unit,
    onWeek: () -> Unit,
    onSettings: () -> Unit,
    onOpenDecisions: () -> Unit,
    decisionsAnchor: Modifier,
    settingsAnchor: Modifier,
    onDone: (String, Card) -> Unit,
    onLater: (String, Card) -> Unit,
    onUnmark: (String, Card) -> Unit,
    update: AppRelease?,
    updateProgress: Float?,
    onUpdate: () -> Unit,
) {
    val todayKey = now.toLocalDate().toString()
    val today = snapshot.days.firstOrNull { it.day == todayKey }
    val tomorrow = snapshot.days.firstOrNull { it.day == now.toLocalDate().plusDays(1).toString() }
    val items = remember(today, marks, pending, now) {
        timeline(today?.cards.orEmpty(), marks[todayKey].orEmpty(), now.toLocalTime(), pending)
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    // Le fil s'ouvre sur l'élément en cours : ce qui le précède est replié sous « N plus tôt ».
    var showEarlier by rememberSaveable(todayKey) { mutableStateOf(false) }
    val hidden = if (showEarlier) 0 else earlierCount(items)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom + 16.dp),
    ) {
        if (snapshot.offline) item(key = "offline") { OfflineStatus(snapshot.fetchedAt, onRefresh) }
        if (update != null) {
            item(key = "update") { UpdateStatus(update.version, updateProgress, onUpdate, Modifier.animateItem()) }
        }
        item(key = "header") { Header(now, tomorrow?.work, onSettings, settingsAnchor) }
        if (snapshot.decisions.isNotEmpty()) {
            item(key = "decisions") { DecisionsPeek(snapshot.decisions.size, onOpenDecisions, decisionsAnchor) }
        }
        item(key = "label") {
            SectionHeader("Fil du jour", Modifier.padding(start = 22.dp, end = 14.dp)) {
                DayWeekToggle(week = false) { if (it) onWeek() }
            }
        }
        if (items.isEmpty()) {
            item(key = "empty") { EmptyDay(loaded = snapshot.fetchedAt != null, offline = snapshot.offline) }
        }
        if (hidden > 0) {
            item(key = "earlier") {
                TextButton(
                    onClick = { showEarlier = true },
                    modifier = Modifier
                        .padding(start = 30.dp)
                        .semantics { contentDescription = "Afficher $hidden élément${if (hidden > 1) "s" else ""} plus tôt" },
                ) { Text("$hidden plus tôt · afficher") }
            }
        }
        items(items.drop(hidden), key = { it.card.key }) { item ->
            val marked = item.phase == Phase.Fait || item.phase == Phase.PlusTard
            TimelineRow(
                item = item,
                now = now.toLocalTime(),
                expanded = expanded == item.card.key,
                onToggle = { expanded = if (expanded == item.card.key) null else item.card.key },
                onDone = { onDone(todayKey, item.card) },
                onLater = { onLater(todayKey, item.card) },
                onRestore = { onUnmark(todayKey, item.card) },
                modifier = Modifier.animateItem(),
                swipeEnabled = !marked,
            )
        }
    }
}

@Composable
private fun Header(now: LocalDateTime, tomorrowWork: String?, onSettings: () -> Unit, settingsAnchor: Modifier) {
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Fr)).replaceFirstChar { it.titlecase(Fr) }
    Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                greeting(now.toLocalTime()),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onSettings, modifier = settingsAnchor) {
                Icon(KairosIcons.Gear, contentDescription = "Réglages", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        tomorrowLine(tomorrowWork)?.let { StatePill(it, Modifier.padding(top = 8.dp)) }
    }
}

/** Bascule Jour / Semaine, en tête du fil comme de la semaine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayWeekToggle(week: Boolean, onWeek: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        listOf("Jour" to false, "Semaine" to true).forEachIndexed { i, (label, value) ->
            SegmentedButton(
                selected = week == value,
                onClick = { if (week != value) onWeek(value) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                icon = {},
            ) { Text(label) }
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

/** « à 14:05 » aujourd'hui, « du 8 novembre » avant. */
private fun offlineSince(fetchedAt: String?): String? = fetchedAt?.let {
    runCatching {
        val t = LocalDateTime.parse(it)
        if (t.toLocalDate() == LocalDate.now()) "à " + t.format(DateTimeFormatter.ofPattern("H:mm"))
        else "du " + t.format(DateTimeFormatter.ofPattern("d MMMM", Fr))
    }.getOrNull()
}

@Composable
internal fun OfflineStatus(fetchedAt: String?, onRetry: () -> Unit) {
    StatusBanner(
        "Hors ligne",
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        detail = offlineSince(fetchedAt)?.let { "Dernier état $it" },
        actionLabel = "Réessayer",
        onAction = onRetry,
    )
}

/** Nouvelle version sur le serveur, puis avancement du téléchargement (détail dans la barre). */
@Composable
private fun UpdateStatus(version: String, progress: Float?, onUpdate: () -> Unit, modifier: Modifier) {
    // À 100 % : confirmation du système quittée sans réponse, à remontrer ou abandonner.
    val action = when {
        progress == null -> "Mettre à jour"
        progress >= 1f -> "Reprendre"
        else -> null
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        StatusBanner(
            updateBannerText(version, progress),
            tone = Tone.Neutral,
            actionLabel = action,
            onAction = action?.let { onUpdate },
        )
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Coins de la carte de tête de l'aperçu (la pile de décisions en naît). */
internal val DecisionsPeekCorner = 18.dp

/** Bouton rond des réglages : le panneau en naît et y retourne. */
internal val SettingsButtonCorner = 24.dp

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
                .shadow(8.dp, shape, ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                .semantics { contentDescription = decisionsSpoken(count) },
        ) {
            Row(Modifier.padding(start = 16.dp, end = 12.dp).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                Text("$count", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (count > 1) "décisions en attente" else "décision en attente",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(decisionsEta(count), style = MaterialTheme.typography.bodySmall)
                }
                Icon(KairosIcons.ChevronForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun EmptyDay(loaded: Boolean, offline: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mascot(MascotState.Repos, size = 56.dp, onBackground = MaterialTheme.colorScheme.background)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                loaded -> "Rien de prévu aujourd'hui."
                // Jamais reçu et serveur injoignable : on ne sait pas, on ne dit pas « rien ».
                offline -> "Fil du jour indisponible pour l'instant."
                else -> "Le fil du jour arrive…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Un élément du fil : rail chronologique à gauche, carte glissable à droite. */
@Composable
private fun TimelineRow(
    item: TimelineItem,
    now: LocalTime,
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
    val toggle by rememberUpdatedState(onToggle)
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
        // La carte reste dans le fil pendant le geste (marquée sur place) : elle revient se poser, sans partir.
        // Le dépli est porté par le nœud de la rangée (texte + actions de glissement), pas par un clic imbriqué.
        SwipeRow(
            enabled = swipeEnabled,
            onRight = onDone,
            onLeft = onLater,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp)
                .semantics { onClick(label = if (expanded) "Replier" else "Afficher le détail") { toggle(); true } },
            rightStays = true,
            leftStays = true,
        ) {
            ItemCard(item, now, expanded, { toggle() }, onDone, onLater, onRestore, swipeEnabled)
        }
    }
}

private fun ChipTone.kit(): Tone = when (this) {
    ChipTone.Neutre -> Tone.Neutral
    ChipTone.Attention -> Tone.Attention
    ChipTone.Urgent -> Tone.Urgent
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(
    item: TimelineItem,
    now: LocalTime,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onRestore: () -> Unit,
    actionable: Boolean,
) {
    val card = item.card
    val marked = item.phase == Phase.Fait || item.phase == Phase.PlusTard
    val current = item.phase == Phase.Maintenant
    val scheme = MaterialTheme.colorScheme
    val muted = if (current) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    // Fondu sur le texte seulement : la pastille d'état et les boutons gardent leur contraste.
    val fade = Modifier.alpha(if (marked) 0.7f else if (item.phase == Phase.Passe) 0.75f else 1f)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (current) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = if (marked) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onTap = { onToggle() }) },
    ) {
        Column(Modifier.padding(start = 14.dp, end = if (actionable) 4.dp else 14.dp, top = 11.dp, bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(fade.weight(1f)) {
                    Text(
                        card.title,
                        style = MaterialTheme.typography.titleSmall,
                        textDecoration = if (item.phase == Phase.Fait) TextDecoration.LineThrough else null,
                    )
                    val time = card.time
                    if (current) {
                        val label = listOfNotNull(time, startsIn(time, now)).joinToString(" · ")
                        if (label.isNotEmpty()) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.primary, fontWeight = FontWeight.Bold)
                        }
                    } else if (time != null) {
                        Text(time, style = MaterialTheme.typography.labelMedium, color = muted)
                    }
                }
                if (actionable) {
                    OutlinedIconButton(
                        onClick = onDone,
                        modifier = Modifier.size(48.dp),
                        border = BorderStroke(1.5.dp, scheme.primary),
                    ) {
                        Icon(KairosIcons.Check, contentDescription = "Marquer fait : ${card.title}", tint = scheme.primary)
                    }
                } else if (marked) {
                    Spacer(Modifier.width(8.dp))
                    StatePill(if (item.phase == Phase.Fait) "fait" else "plus tard")
                }
            }
            card.body?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted, modifier = fade.padding(top = 2.dp))
            }
            if (card.chips.isNotEmpty()) {
                FlowRow(fade.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.chips.forEach { StatePill(it.text, tone = it.tone.kit()) }
                }
            }
            AnimatedVisibility(expanded, enter = Motion.expand, exit = Motion.collapse) {
                Column(Modifier.padding(top = 10.dp)) {
                    card.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (actionable) {
                            Pill("Plus tard", onLater)
                        } else if (restorable(item)) {
                            Pill("Remettre dans le fil", onRestore)
                        }
                    }
                }
            }
        }
    }
}

/** Choix compact (portions…) : une puce par option, la valeur par défaut en avant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chooser(card: Card, onPick: (JsonElement) -> Unit, onCancel: () -> Unit) {
    val c = card.done?.choices ?: return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
            Text(c.label.ifBlank { "Combien ?" }, style = MaterialTheme.typography.titleSmall)
            Text(card.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                c.options.forEach { o ->
                    FilterChip(
                        selected = o == c.default,
                        onClick = { onPick(o) },
                        label = { Text(optionLabel(o), style = MaterialTheme.typography.titleSmall) },
                    )
                }
                TextButton(onClick = onCancel) { Text("Annuler") }
            }
        }
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
