package app.kairos.mobile.ui.maison

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Order
import app.kairos.mobile.data.OrderLine
import app.kairos.mobile.data.Repository
import app.kairos.mobile.scan.ExpiryParser
import app.kairos.mobile.scan.LabelCamera
import app.kairos.mobile.ui.decide.Stamp
import app.kairos.mobile.ui.decide.Swipe
import app.kairos.mobile.ui.decide.swipeOf
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.theme.Motion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val UNDO_MS = 4_000L
private const val THRESHOLD = 0.35f

/** Vitesse au-delà de laquelle un lancer vaut validation, même sous le seuil. */
private const val FLING_DP_S = 1_200

/** Libellé affiché d'une zone stockée ; null si ce n'est pas une zone de [zones]. */
internal fun zoneLabel(id: String?, zones: List<Pair<String, String>>): String? = zones.firstOrNull { it.first == id }?.second

/**
 * Ranger une commande, un produit à la fois : → (ou la zone proposée) valide,
 * une autre tuile range ailleurs, ← (ou « Rupture ») retire la ligne. Produit
 * frais : la date est lue sur l'étiquette puis confirmée d'un tap. Chaque ligne
 * part après [UNDO_MS] (annulable jusque-là) depuis [appScope], qui survit à
 * l'écran ; fermer envoie aussitôt ce qui attendait.
 */
@Composable
fun UnpackScreen(
    order: Order,
    offline: Boolean,
    repository: Repository,
    appScope: CoroutineScope,
    onUnauthorized: () -> Unit,
    onNotice: (String) -> Unit,
    onClose: () -> Unit,
    others: Int = 0,
    onOther: () -> Unit = {},
) {
    val maison by repository.maison.collectAsState()
    val zones = zonesOf(maison.inventory)
    val state = remember(order.id) { UnpackState(order, zones) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var busy by remember { mutableStateOf(false) }
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val notice by rememberUpdatedState(onNotice)
    val today = remember { LocalDate.now() }

    // Envois en route ; Maison n'est rechargée qu'une fois le dernier revenu.
    val inFlight = remember { AtomicInteger() }

    suspend fun send(s: Stow) {
        fun fail(reason: String) {
            when {
                UnpackState.alreadyHandled(reason) -> Unit
                state.closed -> notice("${s.line.name} : $reason")
                else -> state.refused(s, reason)
            }
        }
        inFlight.incrementAndGet()
        try {
            repository.maisonTool("app_order_line", s.args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            when (e) {
                ApiError.Unauthorized -> return unauthorized()
                ApiError.Offline -> fail("serveur injoignable, rien n'a été enregistré.")
                // Ligne réservée côté serveur : la renvoyer ne la range jamais deux fois.
                ApiError.NoReply -> fail("pas de réponse du serveur, réessaie.")
                is ApiError.Server -> fail(if (e.code == 422) e.message ?: "refusé." else "${e.unavailable()}.")
            }
        } finally {
            // Le serveur fait foi : Maison se met à jour derrière le panneau, une fois.
            if (inFlight.decrementAndGet() == 0) {
                appScope.launch {
                    try {
                        repository.refreshMaison()
                    } catch (_: ApiError.Unauthorized) {
                        unauthorized()
                    }
                }
            }
        }
    }

    fun schedule(s: Stow?) {
        s ?: return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            delay(UNDO_MS)
            if (state.dispatch(s)) appScope.launch { send(s) }
        }
    }

    // Quitter l'écran, quelle qu'en soit la raison, envoie ce qui a été rangé.
    DisposableEffect(state) {
        onDispose { state.flush().forEach { appScope.launch { send(it) } } }
    }

    var area by remember { mutableStateOf(IntSize.Zero) }
    val width = area.width.toFloat().coerceAtLeast(1f)
    val threshold = width * THRESHOLD
    val flingSpeed = with(LocalDensity.current) { FLING_DP_S.dp.toPx() }

    // Gestes, tuiles et boutons : une animation à la fois.
    fun exclusive(block: suspend () -> Unit) {
        if (busy || offline) return
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }

    /** La carte part vers [target] (droite = proposée, gauche = rupture, bas = une tuile), puis l'action s'applique. */
    suspend fun throwCard(target: Offset, act: () -> Stow?) {
        offset.animateTo(target, Motion.fling())
        val s = act()
        offset.snapTo(Offset.Zero)
        if (s != null) schedule(s) else haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    suspend fun onSwipe(swipe: Swipe?) {
        when {
            swipe == Swipe.Right && state.proposed != null -> throwCard(Offset(width * 1.4f, offset.value.y)) { state.place() }
            swipe == Swipe.Left -> throwCard(Offset(-width * 1.4f, offset.value.y)) { state.missing() }
            else -> offset.animateTo(Offset.Zero, Motion.settle())
        }
    }

    fun place(zone: String) = exclusive { throwCard(Offset(0f, area.height * 1.1f)) { state.place(zone) } }
    fun missing() = exclusive { throwCard(Offset(-width * 1.4f, 0f)) { state.missing() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        TopBar("Ranger la commande", listOfNotNull(order.store.trim().takeIf { it.isNotEmpty() }, pickupLabel(order.pickup)).joinToString(" · "), state.done, state.total, onClose)
        // Les autres commandes à ranger restent à portée, une à la fois.
        if (others > 0) {
            TextButton(onClick = onOther, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Commande suivante (${others} autre${if (others > 1) "s" else ""}) ›")
            }
        }
        if (offline) {
            Text(
                "Hors ligne : rien ne part pour l'instant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        val screen = if (state.finished) null else state.step
        AnimatedContent(screen, Modifier.weight(1f), transitionSpec = { Motion.fadeThrough() }, label = "étape") { step ->
            val line = state.current
            when {
                step == null || line == null -> Done("Tout est rangé")
                step == UnpackState.Step.Date -> DateStep(
                    name = line.name,
                    zone = zoneLabel(state.zone, zones),
                    today = today,
                    enabled = !offline,
                    onDate = { d -> schedule(state.date(d)) },
                    onBack = state::back,
                    onMissing = { schedule(state.missing()) },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // Hors de la zone du geste retour système : un glissement depuis le bord reste à la carte.
                            .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .onSizeChanged { area = it },
                        contentAlignment = Alignment.Center,
                    ) {
                        val o = offset.value
                        val lift = (maxOf(abs(o.x), abs(o.y)) / threshold).coerceIn(0f, 1f)
                        state.next?.let { next ->
                            LineCard(
                                next,
                                proposed = zoneLabel(next.location, zones),
                                message = null,
                                Modifier.graphicsLayer {
                                    val s = 0.92f + 0.08f * lift
                                    scaleX = s
                                    scaleY = s
                                    translationY = (1f - lift) * 18.dp.toPx()
                                    alpha = 0.6f + 0.4f * lift
                                },
                            )
                        }
                        // Déplacement cumulé ici : ne dépend pas de l'avance de l'animation.
                        var acc by remember { mutableStateOf(0f) }
                        var armed by remember { mutableStateOf<Swipe?>(null) }
                        LineCard(
                            line,
                            proposed = zoneLabel(state.proposed, zones),
                            message = state.message,
                            Modifier
                                .graphicsLayer {
                                    translationX = o.x
                                    translationY = o.y
                                    rotationZ = (o.x / width) * 14f
                                    val sink = (o.y / area.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    scaleX = 1f - 0.3f * sink
                                    scaleY = 1f - 0.3f * sink
                                }
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    enabled = !offline && !busy,
                                    onDragStarted = {
                                        acc = offset.value.x
                                        armed = null
                                    },
                                    onDragStopped = { v -> exclusive { onSwipe(swipeOf(Offset(acc, 0f), Offset(v, 0f), threshold, flingSpeed)) } },
                                    state = rememberDraggableState { d ->
                                        acc += d
                                        val x = acc
                                        scope.launch { offset.snapTo(Offset(x, 0f)) }
                                        val now = swipeOf(Offset(x, 0f), Offset.Zero, threshold, flingSpeed)
                                            ?.takeIf { it != Swipe.Right || state.proposed != null }
                                        if (now != null && now != armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        armed = now
                                    },
                                ),
                            stamps = {
                                zoneLabel(state.proposed, zones)?.let {
                                    Stamp(it.uppercase(Locale.FRENCH), MaterialTheme.colorScheme.primary, (o.x / threshold).coerceIn(0f, 1f), 12f, Alignment.TopEnd)
                                }
                                Stamp("RUPTURE", MaterialTheme.colorScheme.error, (-o.x / threshold).coerceIn(0f, 1f), -12f, Alignment.TopStart)
                            },
                        )
                    }
                    ZoneGrid(zones, proposed = state.proposed, enabled = !offline, onZone = ::place) { m ->
                        BigTile("Rupture", m, enabled = !offline, color = MaterialTheme.colorScheme.error, onClick = ::missing)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { if (!busy) state.undo() }, enabled = state.canUndo) { Text("⟲ annuler") }
        }
    }

    // Tout est traité : écran de fin, puis retour une fois la dernière ligne
    // partie (son annulation reste possible jusque-là et interrompt la fermeture).
    LaunchedEffect(state.finished) {
        if (state.finished) {
            delay(UNDO_MS)
            onClose()
        }
    }
}

/** En-tête des panneaux Maison : titre, sous-titre, progression, fermeture. */
@Composable
internal fun TopBar(title: String, subtitle: String, done: Int, total: Int, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            subtitle.trim().takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (total > 0) {
            Text("$done / $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClose) { Text("Fermer") }
    }
    if (total > 0) {
        val progress by animateFloatAsState(done.toFloat() / total, Motion.enter(), label = "progression")
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun LineCard(
    line: OrderLine,
    /** Libellé de la zone proposée ; null = aucune zone connue. */
    proposed: String?,
    message: String?,
    modifier: Modifier = Modifier,
    stamps: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(24.dp)
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.12f)),
    ) {
        Box(Modifier.fillMaxSize().padding(20.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (line.fresh) "À RANGER · FRAIS" else "À RANGER",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (line.fresh) colors.tertiary else colors.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )
                Text(line.name, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                line.label.ifBlank { formatQty(line.qty, line.unit) }.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.primaryContainer, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    val known = proposed != null
                    Text(
                        if (known) "Kairos propose · glisse →" else "Touche une zone ci-dessous",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onPrimaryContainer,
                    )
                    if (proposed != null) Text(proposed, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                }
            }
            stamps()
        }
    }
}

/**
 * Les zones en grosses tuiles sous le pouce, rangées de trois au plus ;
 * [proposed] en surbrillance ; [extra] occupe la dernière case.
 */
@Composable
internal fun ZoneGrid(
    zones: List<Pair<String, String>>,
    proposed: String?,
    enabled: Boolean,
    onZone: (String) -> Unit,
    extra: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var start = 0
        for (n in planRows(zones.size + 1)) {
            val row = (start until start + n).toList()
            start += n
            // Tuiles d'une rangée à la même hauteur : celle du libellé le plus long.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in row) {
                    val m = Modifier.weight(1f).fillMaxHeight()
                    zones.getOrNull(i)?.let { (id, label) ->
                        BigTile(label, m, enabled, highlighted = id == proposed) { onZone(id) }
                    } ?: extra(m)
                }
            }
        }
    }
}

@Composable
internal fun BigTile(
    text: String,
    modifier: Modifier,
    enabled: Boolean,
    highlighted: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = if (highlighted) color else MaterialTheme.colorScheme.surface,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else color,
        border = BorderStroke(if (highlighted) 0.dp else 1.5.dp, if (highlighted) Color.Transparent else color.copy(alpha = 0.6f)),
        modifier = modifier.heightIn(min = 76.dp).alpha(if (enabled) 1f else 0.45f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        }
    }
}

private val CHIP_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)

/**
 * Date d'un produit frais : l'étiquette visée est lue en continu, les dates
 * trouvées s'affichent en grosses puces (un tap confirme). « Autre date » et
 * « Pas de date » restent toujours là, caméra ou non ; [onMissing] (rupture) aussi s'il est fourni.
 */
@Composable
internal fun DateStep(
    name: String,
    /** Libellé de la zone choisie. */
    zone: String?,
    today: LocalDate,
    enabled: Boolean,
    onDate: (LocalDate?) -> Unit,
    onBack: () -> Unit,
    onMissing: (() -> Unit)?,
) {
    // Dernière image où une date a été lue : les puces ne clignotent pas quand l'étiquette sort du champ.
    var found by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← ${zone ?: "?"}") }
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LabelCamera(
            why = "Vise la date de l'étiquette : Kairos la lit sur le téléphone, rien n'est envoyé.",
            onText = { text ->
                val dates = ExpiryParser.parse(text, today).take(3)
                if (dates.isNotEmpty() && dates != found) {
                    if (found.isEmpty()) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    found = dates
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            if (found.isEmpty()) "Vise la date (DLC, DDM)…" else "Touche la bonne date",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (d in found) {
                val n = ChronoUnit.DAYS.between(today, d)
                val rel = when (n) {
                    0L -> "aujourd'hui"
                    1L -> "demain"
                    else -> "dans $n j"
                }
                Surface(
                    onClick = { onDate(d) },
                    enabled = enabled,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.heightIn(min = 64.dp),
                ) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.format(CHIP_DATE), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(rel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigTile("Autre date", Modifier.weight(1f), enabled) { picking = true }
            BigTile("Pas de date", Modifier.weight(1f), enabled, highlighted = found.isEmpty()) { onDate(null) }
            onMissing?.let { BigTile("Rupture", Modifier.weight(1f), enabled, color = MaterialTheme.colorScheme.error, onClick = it) }
        }
    }

    if (picking) {
        val start = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = start,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= start
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    picker.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Annuler") } },
        ) { DatePicker(picker) }
    }
}

/** Fin d'un panneau Maison, avec le personnage content. */
@Composable
internal fun Done(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Mascot(MascotState.Agit, size = 120.dp, onBackground = MaterialTheme.colorScheme.background)
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.headlineMedium)
    }
}
