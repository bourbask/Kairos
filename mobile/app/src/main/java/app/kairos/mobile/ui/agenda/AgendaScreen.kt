package app.kairos.mobile.ui.agenda

import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.AgendaDay
import app.kairos.mobile.data.AgendaTask
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.Timeline
import app.kairos.mobile.ui.kit.clockText
import app.kairos.mobile.ui.maison.ChevronBackIcon
import app.kairos.mobile.ui.talk.IconButton
import app.kairos.mobile.ui.theme.Motion
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.InFlight
import app.kairos.mobile.ui.today.Pill
import app.kairos.mobile.ui.today.SwipeRow
import app.kairos.mobile.ui.today.completeCard
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val UNDO_MS = 4_000L
private val Fr = Locale.FRENCH
private val DAY_TITLE = DateTimeFormatter.ofPattern("EEEE d MMMM", Fr)

/**
 * Espace Agenda : bandeau de sept jours, frise du jour choisi (glisser horizontalement
 * sur la frise change de jour, jamais depuis le bord), tâches dues (glisser → = faite,
 * annulable), à venir, ajout d'un événement en panneau du bas. À l'ouverture, les
 * calendriers sont resynchronisés puis l'agenda local est lu ; dernier état gardé hors ligne.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    repository: Repository,
    padding: PaddingValues,
    appScope: CoroutineScope,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val agenda by repository.agenda.collectAsState()
    val days by repository.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val snackbar = remember { SnackbarHostState() }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val strip = remember(today) { dayStrip(today) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val selected = runCatching { LocalDate.parse(selectedText) }.getOrNull()?.takeIf { it in strip } ?: today
    var loading by remember { mutableIntStateOf(0) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    val adding = remember { InFlight() }
    val tasksBusy = remember { InFlight() }
    var hidden by remember { mutableStateOf(emptySet<Long>()) }

    fun load(day: LocalDate, sync: Boolean, force: Boolean = true) {
        loading++
        scope.launch {
            try {
                repository.refreshAgenda(day, sync, force)
            } catch (_: ApiError.Unauthorized) {
                unauthorized()
            } finally {
                loading--
            }
        }
    }

    // Ouverture et retour au premier plan : synchroniser puis lire le jour affiché (retour rapide : rien).
    val shown by rememberUpdatedState(selected)
    LifecycleResumeEffect(Unit) {
        val now = LocalDate.now()
        today = now
        load(shown.takeIf { it >= now } ?: now, sync = true, force = false)
        onPauseOrDispose {}
    }
    // Passé minuit, écran ouvert : le bandeau repart du nouveau jour.
    LaunchedEffect(today) {
        delay(Duration.between(LocalDateTime.now(), today.plusDays(1).atStartOfDay()).toMillis().coerceAtLeast(0) + 1_000)
        today = LocalDate.now()
    }
    var shownDay by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(selected) {
        if (shownDay != null) load(selected, sync = false)
        shownDay = selected
    }

    suspend fun undoWindow(text: String): Boolean = withTimeoutOrNull(UNDO_MS) {
        snackbar.showSnackbar(text, actionLabel = "Annuler", duration = SnackbarDuration.Indefinite)
    } == SnackbarResult.ActionPerformed

    // Depuis appScope : quitter l'espace pendant la fenêtre n'abandonne pas l'action.
    fun taskDone(task: AgendaTask) {
        val card = Card(
            id = "tache-${task.id}",
            title = task.title,
            module = "agenda",
            done = CardDone("app_task_done", buildJsonObject { put("id", task.id) }),
        )
        appScope.launch {
            val out = try {
                completeCard(
                    card,
                    null,
                    mark = { m -> hidden = if (m == null) hidden - task.id else hidden + task.id },
                    undoWindow = { undoWindow("Faite · ${task.title}") },
                    call = repository::tool,
                    busy = tasksBusy,
                )
            } catch (_: ApiError.Unauthorized) {
                return@launch unauthorized()
            }
            when (out) {
                is DoneOutcome.Done -> load(selected, sync = false)
                is DoneOutcome.Refused -> snackbar.showSnackbar(out.message)
                else -> Unit
            }
        }
    }

    val day = agenda.days[selected.toString()]
    val work = days.days.firstOrNull { it.day == selected.toString() }?.work
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Header(onClose, showToday = selected != today) { selectedText = today.toString() }
            DayStrip(strip, selected, today, agenda.week?.days?.filter { it.events.isNotEmpty() }?.map { it.day }.orEmpty().toSet()) {
                selectedText = it.toString()
            }
            PullToRefreshBox(
                isRefreshing = loading > 0,
                onRefresh = { load(selected, sync = true) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = padding.calculateBottomPadding() + 88.dp),
                ) {
                    agenda.issue?.let { issue ->
                        StatusBanner(issue, detail = agenda.fetchedAt?.let { "Dernier état : ${dayTime(it)}" })
                        Spacer(Modifier.height(8.dp))
                    }
                    syncBanner(agenda)?.let {
                        StatusBanner(it)
                        Spacer(Modifier.height(8.dp))
                    }
                    DayTitle(selected, work)
                    Spacer(Modifier.height(8.dp))
                    AnimatedContent(
                        selected to day,
                        modifier = Modifier.swipeDays(strip, selected) { selectedText = it.toString() },
                        transitionSpec = { Motion.fadeThrough() },
                        label = "day",
                        contentKey = { it.first },
                    ) { (d, content) ->
                        Column {
                            if (content == null) {
                                Hint(agenda.issue)
                            } else {
                                DayView(d, content, hidden, onDone = ::taskDone)
                            }
                        }
                    }
                    Upcoming(agenda.week?.upcoming.orEmpty(), today)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { addOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 8.dp),
        ) { Text("Ajouter un événement") }
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
        if (addOpen) {
            ModalBottomSheet(onDismissRequest = { addOpen = false }) {
                AddPanel(strip, selected, adding) { form, done ->
                    // Depuis appScope : fermer le panneau ou l'espace n'abandonne pas l'envoi.
                    appScope.launch {
                        val out = try {
                            submitEvent(form, adding) { args -> repository.tool("agenda", "app_event_add", args) }
                        } catch (_: ApiError.Unauthorized) {
                            return@launch unauthorized()
                        }
                        done(out)
                        when (out) {
                            is AddOutcome.Added -> {
                                addOpen = false
                                selectedText = form.day.toString()
                                try {
                                    repository.refreshAgenda(form.day)
                                } catch (_: ApiError.Unauthorized) {
                                    return@launch unauthorized()
                                }
                                snackbar.showSnackbar(out.message.ifBlank { "Événement ajouté." })
                            }
                            // Panneau fermé entre-temps : la raison ne doit pas se perdre.
                            is AddOutcome.Refused -> if (!addOpen) snackbar.showSnackbar(out.message)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

/**
 * Glisser horizontalement sur la vue du jour change de jour ; un geste parti du bord
 * de l'écran (zone des gestes système, retour compris) est laissé au système.
 */
@Composable
private fun Modifier.swipeDays(strip: List<LocalDate>, selected: LocalDate, onDay: (LocalDate) -> Unit): Modifier {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val edge = maxOf(gestures.getLeft(density, direction), gestures.getRight(density, direction), with(density) { 24.dp.roundToPx() }).toFloat()
    val screen = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val threshold = with(density) { 72.dp.toPx() }
    val current by rememberUpdatedState(selected)
    val pick by rememberUpdatedState(onDay)
    var left by remember { mutableFloatStateOf(0f) }
    return this
        .onGloballyPositioned { left = it.positionInRoot().x }
        .pointerInput(strip, screen, edge) {
            var startX = 0f
            var dx = 0f
            detectHorizontalDragGestures(
                onDragStart = { startX = left + it.x; dx = 0f },
                onDragEnd = { pick(swipeDay(current, strip, startX, dx, screen, edge, threshold)) },
            ) { change, amount ->
                dx += amount
                change.consume()
            }
        }
}

@Composable
private fun Header(onClose: () -> Unit, showToday: Boolean, onToday: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(label = "Retour aux espaces", onClick = onClose) { ChevronBackIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(4.dp))
        Text(
            "Agenda",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (showToday) Pill("Aujourd'hui", onToday)
    }
}

/** Sept puces ; un point sous les jours qui ont des événements. */
@Composable
private fun DayStrip(strip: List<LocalDate>, chosen: LocalDate, today: LocalDate, busy: Set<String>, onPick: (LocalDate) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        strip.forEach { d ->
            val on = d == chosen
            val label = relativeDay(d, today).replaceFirstChar { it.titlecase(Fr) }
            Surface(
                onClick = { onPick(d) },
                shape = RoundedCornerShape(14.dp),
                color = if (on) colors.primary else colors.surface,
                border = if (on) null else BorderStroke(1.dp, colors.outlineVariant),
                modifier = Modifier.semantics {
                    selected = on
                    contentDescription = d.format(DAY_TITLE) + if (d.toString() in busy) ", des événements" else ""
                },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        d.format(DateTimeFormatter.ofPattern("EEE", Fr)).removeSuffix("."),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                    )
                    Text(
                        d.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) colors.onPrimary else colors.onSurface,
                    )
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(5.dp)
                            .background(if (d.toString() in busy) (if (on) colors.onPrimary else colors.primary) else colors.surface.copy(alpha = 0f), CircleShape),
                    )
                }
            }
        }
    }
}

/** « Lundi 9 novembre » et le mode de travail du jour en badge. */
@Composable
private fun DayTitle(day: LocalDate, work: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            day.format(DAY_TITLE).replaceFirstChar { it.titlecase(Fr) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        workLabel(work)?.let {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DayView(day: LocalDate, content: AgendaDay, hidden: Set<Long>, onDone: (AgendaTask) -> Unit) {
    Legend()
    Spacer(Modifier.height(6.dp))
    Timeline(day, blocksOf(content.events), highlights = freeSpans(content))
    if (content.events.isEmpty()) Caption("Rien de prévu ce jour-là.")
    val tasks = content.tasks.filter { it.id !in hidden }
    if (tasks.isNotEmpty()) {
        SectionHeader("Tâches")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tasks.forEach { t -> TaskRow(t, day, onDone) }
        }
    }
}

/** Perso et travail : les deux familles de blocs de la frise. */
@Composable
private fun Legend() {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Cal.Perso to colors.primaryContainer, Cal.Travail to colors.secondaryContainer).forEach { (c, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(6.dp))
                Caption(c.label)
            }
        }
    }
}

/** Glisser → = faite ; le bouton « Faite » fait de même. */
@Composable
private fun TaskRow(task: AgendaTask, day: LocalDate, onDone: (AgendaTask) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val late = task.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it < day }
    // Seul le geste → est proposé : ← revient en place sans rien faire.
    SwipeRow(enabled = true, onRight = { onDone(task) }, onLeft = {}, rightLabel = "Faite ✓", leftLabel = "", leftStays = true) {
        Surface(shape = MaterialTheme.shapes.medium, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    late?.let { Text("en retard depuis le ${it.format(DateTimeFormatter.ofPattern("d MMMM", Fr))}", style = MaterialTheme.typography.bodySmall, color = colors.tertiary) }
                }
                TextButton(onClick = { onDone(task) }) { Text("Faite") }
            }
        }
    }
}

@Composable
private fun Upcoming(items: List<app.kairos.mobile.data.Upcoming>, today: LocalDate) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    SectionHeader("À venir")
    items.take(UPCOMING_MAX).forEach { u ->
        val description = "${u.title}, ${calendarLabel(u.calendar)}, ${upcomingWhen(u, today)}"
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (u.calendar == "travail") colors.secondaryContainer else colors.primaryContainer, RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(u.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Caption(upcomingWhen(u, today))
        }
    }
}

private const val UPCOMING_MAX = 8

@Composable
private fun Hint(issue: String?) {
    Caption(if (issue != null) "$issue, et rien en mémoire pour ce jour." else "Chargement…", Modifier.padding(vertical = 24.dp))
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/** « 08/11 à 09:12 » pour un horodatage ISO, sinon le texte tel quel. */
private fun dayTime(s: String): String = app.kairos.mobile.ui.finances.syncLabel(s)

/**
 * Titre, jour (bandeau), heure de début, durée et calendrier en puces. Refusé sur place
 * tant qu'il est incomplet ; « Ajouter » ne part qu'une fois ([busy] partagé avec l'écran).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPanel(
    strip: List<LocalDate>,
    initialDay: LocalDate,
    busy: InFlight,
    onSubmit: (AddForm, (AddOutcome) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val pending by busy.keys.collectAsState()
    var title by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(initialDay) }
    var start by rememberSaveable { mutableStateOf<LocalTime?>(defaultStart(LocalTime.now())) }
    var span by rememberSaveable { mutableStateOf<Span?>(null) }
    var calendar by rememberSaveable { mutableStateOf(Cal.Perso) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    val form = AddForm(title, day, start, span, calendar)
    val error = formError(form)
    val sending = pending.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajouter un événement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; refused = null },
            label = { Text("Titre") },
            singleLine = true,
            isError = tried && title.isBlank(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Caption("Jour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            strip.forEach { d ->
                FilterChip(selected = d == day, onClick = { day = d }, label = { Text(relativeDay(d, strip.first())) })
            }
        }
        Caption("Durée")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Span.entries.forEach { s ->
                FilterChip(selected = s == span, onClick = { span = s }, label = { Text(s.label) })
            }
        }
        if (span != Span.Day) {
            OutlinedButton(onClick = {
                val t = start ?: LocalTime.of(9, 0)
                TimePickerDialog(context, { _, h, m -> start = LocalTime.of(h, m) }, t.hour, t.minute, true).show()
            }) {
                Text(start?.let { "Début : ${clockText(it.hour * 60 + it.minute)}" } ?: "Choisir l'heure de début")
            }
        }
        Caption("Calendrier")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cal.entries.forEach { c ->
                FilterChip(selected = c == calendar, onClick = { calendar = c }, label = { Text(c.label) })
            }
        }
        val message = refused ?: error?.takeIf { tried }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                tried = true
                if (error == null) {
                    refused = null
                    onSubmit(form) { out ->
                        if (out is AddOutcome.Refused) refused = out.message
                        if (out is AddOutcome.Invalid) refused = out.message
                    }
                }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (sending) "Ajout…" else "Ajouter") }
    }
}

/** Heure ronde suivante, entre 7 h et 22 h. */
private fun defaultStart(now: LocalTime): LocalTime = LocalTime.of((now.hour + 1).coerceIn(7, 22), 0)
