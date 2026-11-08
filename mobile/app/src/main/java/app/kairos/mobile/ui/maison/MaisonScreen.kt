package app.kairos.mobile.ui.maison

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Lot
import app.kairos.mobile.data.Order
import app.kairos.mobile.data.Repository
import app.kairos.mobile.data.Tone
import app.kairos.mobile.ui.theme.Motion
import app.kairos.mobile.ui.today.SwipeRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val UNDO_MS = 4_000L

/** Coins du bandeau « Commande à ranger » et du bouton « Ajouter » (les panneaux suivants en naissent). */
val MaisonActionCorner = 16.dp

/** Vitesse verticale au-delà de laquelle le panneau suit le lancer plutôt que sa position. */
private const val SHEET_FLING = 1_500f

/** Ce qui est déplié sous un produit. */
private enum class Panel { Actions, Move, Qty }

/**
 * Espace Maison : plan de la cuisine, stock de la zone choisie dans le panneau
 * du bas (↑ l'agrandit). Sur un produit : ← consommé (annulable [UNDO_MS]),
 * → déplacer, tap sur la quantité = +/−, tap sur la ligne = les mêmes actions
 * en boutons. Les écritures partent depuis [appScope], qui survit à l'écran.
 * [onUnpack] : ranger une commande, depuis son bandeau ; [onAdd] : ajouter hors commande.
 */
@Composable
fun MaisonScreen(
    repository: Repository,
    padding: PaddingValues,
    appScope: CoroutineScope,
    onClose: () -> Unit,
    onUnpack: (Order, LayoutCoordinates?) -> Unit,
    onAdd: () -> Unit,
    onUnauthorized: () -> Unit,
    addAnchor: Modifier = Modifier,
) {
    val snapshot by repository.maison.collectAsState()
    val state = remember { MaisonState(snapshot) }
    LaunchedEffect(snapshot) { state.load(snapshot) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val unauthorized by rememberUpdatedState(onUnauthorized)
    var refreshing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Pair<Long, Panel>?>(null) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refreshMaison()
            } catch (_: ApiError.Unauthorized) {
                unauthorized()
            } finally {
                refreshing = false
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose {}
    }

    suspend fun send(a: LotAction) {
        try {
            repository.maisonTool(a.tool, a.args)
            state.confirmed(a)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            when (e) {
                ApiError.Unauthorized -> {
                    // Le lot réapparaît : rien n'a été enregistré.
                    state.refused(a, "Appareil non autorisé : rien n'a été enregistré.")
                    return unauthorized()
                }
                ApiError.Offline -> state.refused(a, "Serveur injoignable : rien n'a été enregistré.")
                is ApiError.Server -> state.refused(
                    a,
                    if (e.code == 422) e.message ?: "Refusé." else "Module indisponible, réessaie plus tard.",
                )
            }
        }
        // Le serveur fait foi (urgence, quantités lisibles, lot disparu ailleurs).
        try {
            repository.refreshMaison()
        } catch (_: ApiError.Unauthorized) {
            unauthorized()
        }
    }

    /** Une consommation attend la fin de la fenêtre d'annulation ; le reste part aussitôt. */
    fun schedule(a: LotAction?) {
        a ?: return
        open = null
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        if (a !is LotAction.Use) {
            if (state.dispatch(a)) appScope.launch { send(a) }
            return
        }
        // Une seule horloge : « Annuler » n'est visible que tant que l'annulation est possible.
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = withTimeoutOrNull(UNDO_MS) {
                snackbar.showSnackbar("Consommé · ${a.lot.name}", actionLabel = "Annuler", duration = SnackbarDuration.Indefinite)
            }
            if (r == SnackbarResult.ActionPerformed) {
                if (state.undo()) return@launch
                snackbar.showSnackbar("Déjà enregistré")
                return@launch
            }
            // Délai écoulé, ou remplacé par une consommation plus récente : l'envoi part.
            if (state.dispatch(a)) appScope.launch { send(a) }
        }
    }

    // Quitter l'écran, quelle qu'en soit la raison, envoie ce qui attendait.
    // ponytail: un refus arrivé après la fermeture n'est montré nulle part ; le lot
    // revient simplement à la prochaine ouverture. Le remonter au snackbar global si cela gêne.
    DisposableEffect(state) {
        onDispose { state.flush().forEach { appScope.launch { send(it) } } }
    }
    // Un refus attend la fin du snackbar en cours (annulation comprise) au lieu de le chasser.
    LaunchedEffect(state) {
        snapshotFlow { state.message }.filterNotNull().collect { m ->
            state.consumeMessage()
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            launch { snackbar.showSnackbar(m) }
        }
    }

    val sheet = remember { SheetState() }
    var height by remember { mutableIntStateOf(0) }
    var headerHeight by remember { mutableIntStateOf(0) }
    var topHeight by remember { mutableIntStateOf(0) }
    sheet.range = (topHeight - headerHeight).toFloat().coerceAtLeast(1f)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { height = it.height },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = padding.calculateTopPadding())
                .onSizeChanged { topHeight = it.height },
        ) {
            Header(
                total = state.total,
                urgent = state.zones.sumOf { it.urgent },
                onClose = onClose,
                modifier = Modifier.onSizeChanged { headerHeight = it.height },
            )
            if (state.offline) {
                OfflineBar(snapshot.reason ?: "Hors ligne", snapshot.fetchedAt, refreshing, ::refresh)
            }
            // Le panneau naît du bandeau touché, et y retourne.
            val banners = remember { mutableMapOf<Long, LayoutCoordinates>() }
            state.orders.forEach { order ->
                OrderBanner(
                    order,
                    enabled = state.canAct,
                    onClick = { onUnpack(order, banners[order.id]) },
                    modifier = Modifier.onGloballyPositioned { banners[order.id] = it },
                )
            }
            KitchenPlan(
                zones = state.zones,
                selected = state.zone,
                onSelect = {
                    state.select(it)
                    open = null
                },
            )
            Spacer(Modifier.height(14.dp))
        }

        val padTop = with(LocalDensity.current) { padding.calculateTopPadding().roundToPx() }
        Sheet(
            sheet = sheet,
            expandedTop = { headerHeight + padTop },
            collapsedTop = { topHeight },
            height = { height },
        ) {
            AnimatedContent(state.zone, transitionSpec = { Motion.fadeThrough() }, label = "zone") { zone ->
                val label = state.zones.first { it.id == zone }.label
                val lots = state.lotsIn(zone)
                Column(Modifier.fillMaxSize()) {
                    SheetHeader(label, sheet, onToggle = { scope.launch { sheet.settle(if (sheet.fraction > 0.5f) 0f else 1f) } })
                    when {
                        !state.known -> Hint(if (state.offline) "${snapshot.reason ?: "Hors ligne"}, et rien en mémoire pour l'instant." else "Chargement…")
                        lots.isEmpty() -> Hint("Rien ici.")
                        else -> LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .nestedScroll(sheet.connection)
                                // Hors de la zone du geste retour : un glissement depuis le bord reste à la ligne.
                                .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)),
                            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 72.dp),
                        ) {
                            items(lots, key = { it.id }) { lot ->
                                LotItem(
                                    lot = lot,
                                    panel = open?.takeIf { it.first == lot.id }?.second,
                                    enabled = state.canAct,
                                    zones = state.zones,
                                    onPanel = { p -> open = if (p == null) null else lot.id to p },
                                    onConsume = { schedule(state.consume(lot)) },
                                    onMove = { to -> schedule(state.move(lot, to)) },
                                    onQty = { q -> schedule(state.setQty(lot, q)) },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = Motion.enter(),
                                        placementSpec = Motion.settle(IntOffset(1, 1)),
                                        // Une ligne consommée est déjà partie hors de l'écran : pas de fondu qui la ferait revenir.
                                        fadeOutSpec = null,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        AddButton(
            enabled = state.canAct,
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 8.dp)
                .then(addAnchor),
        )
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

/**
 * Panneau du bas : [fraction] 0 = sous le plan, 1 = agrandi jusqu'à l'en-tête.
 * Suit le doigt sur sa poignée, et la liste quand elle est en butée.
 */
@Stable
private class SheetState {
    var fraction by mutableFloatStateOf(0f)
    var range = 1f

    /** Déplace de [dy] pixels (négatif = vers le haut) ; rend la part de [dy] absorbée. */
    fun drag(dy: Float): Float {
        val before = fraction
        fraction = (fraction - dy / range).coerceIn(0f, 1f)
        return (before - fraction) * range
    }

    suspend fun settle(target: Float, velocity: Float = 0f) {
        animate(fraction, target, initialVelocity = velocity / range, animationSpec = Motion.settle()) { v, _ -> fraction = v }
    }

    fun target(velocity: Float): Float = when {
        velocity < -SHEET_FLING -> 1f
        velocity > SHEET_FLING -> 0f
        else -> if (fraction > 0.5f) 1f else 0f
    }

    val connection = object : NestedScrollConnection {
        // Doigt vers le haut : le panneau s'agrandit avant que la liste ne défile.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y < 0 && fraction < 1f) Offset(0f, drag(available.y)) else Offset.Zero

        // Liste en haut, doigt vers le bas : le panneau se réduit.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y > 0 && fraction > 0f) Offset(0f, drag(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (fraction <= 0f || fraction >= 1f) return Velocity.Zero
            settle(target(available.y), -available.y)
            return available
        }
    }
}

@Composable
private fun Sheet(
    sheet: SheetState,
    expandedTop: () -> Int,
    collapsedTop: () -> Int,
    height: () -> Int,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier
            // Position lue à la mise en page seulement : suivre le doigt ne recompose rien.
            .layout { measurable, constraints ->
                val top = (collapsedTop() + (expandedTop() - collapsedTop()) * sheet.fraction).roundToInt()
                val h = max(0, height() - top)
                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, top) }
            },
    ) { content() }
}

@Composable
private fun SheetHeader(label: String, sheet: SheetState, onToggle: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var high by remember { mutableStateOf(sheet.fraction > 0.5f) }
    val expanded = sheet.fraction > 0.5f
    Column(
        Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { dy ->
                    sheet.drag(dy)
                    val now = sheet.fraction > 0.5f
                    if (now != high) {
                        high = now
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                },
                onDragStopped = { v -> scope.launch { sheet.settle(sheet.target(v), -v) } },
            )
            .clickable(onClickLabel = if (expanded) "Réduire le panneau" else "Agrandir le panneau", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "agrandi" else "réduit" }
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "trié par urgence",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(total: Int, urgent: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onClose,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(40.dp).semantics { contentDescription = "Retour aux espaces" },
        ) {
            Box(contentAlignment = Alignment.Center) { Text("←", fontSize = 18.sp) }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Maison", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row {
                Text(
                    "$total produit${if (total > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (urgent > 0) {
                    Text(
                        " · $urgent à finir vite",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun OfflineBar(reason: String, fetchedAt: String?, refreshing: Boolean, onRetry: () -> Unit) {
    val since = fetchedAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }?.let {
        if (it.toLocalDate() == LocalDate.now()) " (état de ${it.format(TIME)})" else " (état du ${it.format(DateTimeFormatter.ofPattern("dd/MM"))})"
    }.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$reason · lecture seule$since",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, enabled = !refreshing) { Text(if (refreshing) "…" else "Réessayer") }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun OrderBanner(order: Order, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val left = order.lines.count { !it.done }
    val shape = RoundedCornerShape(MaisonActionCorner)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(modifier)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Commande à ranger", fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(order.store.trim().takeIf { it.isNotEmpty() }, "$left produit${if (left > 1) "s" else ""}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
            }
            Text("Ranger ›", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Plan de la cuisine : proportions de la maquette, adaptées à la largeur de l'écran. */
@Composable
private fun KitchenPlan(zones: List<ZoneView>, selected: String, onSelect: (String) -> Unit) {
    val byId = zones.associateBy { it.id }
    @Composable
    fun tile(id: String, modifier: Modifier, round: Boolean = false) {
        byId[id]?.let { ZoneTile(it, it.id == selected, round, { onSelect(id) }, modifier) }
    }
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(216.dp),
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(0.33f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tile("frigo", Modifier.weight(0.64f))
                tile("congelateur", Modifier.weight(0.36f))
            }
            Column(Modifier.weight(0.67f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tile("placard", Modifier.weight(0.27f))
                tile("etagere", Modifier.weight(0.2f))
                Box(Modifier.weight(0.53f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    tile("corbeille", Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.94f), round = true)
                }
            }
        }
    }
}

@Composable
private fun ZoneTile(zone: ZoneView, selected: Boolean, round: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) colors.primaryContainer else colors.background, Motion.enter(), label = "fond")
    val line by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, Motion.enter(), label = "bord")
    val count = if (zone.count == 0) "vide" else "${zone.count} produit${if (zone.count > 1) "s" else ""}"
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.5.dp, line, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .semantics(mergeDescendants = true) {
                contentDescription = zone.label + ", " + count + if (zone.urgent > 0) ", ${zone.urgent} urgent" + (if (zone.urgent > 1) "s" else "") else ""
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Column(
            Modifier.align(if (round) Alignment.Center else Alignment.BottomStart),
            horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(zone.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
            Text(count, fontSize = 11.5.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
        if (zone.urgent > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(colors.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${zone.urgent}", color = colors.onError, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
    )
}

@Composable
private fun LotItem(
    lot: Lot,
    panel: Panel?,
    enabled: Boolean,
    zones: List<ZoneView>,
    onPanel: (Panel?) -> Unit,
    onConsume: () -> Unit,
    onMove: (String) -> Unit,
    onQty: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        SwipeRow(
            enabled = enabled,
            onRight = { onPanel(Panel.Move) },
            onLeft = onConsume,
            rightLabel = "Déplacer →",
            leftLabel = "Consommé",
            leftColor = colors.error,
            rightStays = true,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surface)
                    .clickable(onClickLabel = "Actions") { onPanel(if (panel == null) Panel.Actions else null) }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToneDot(lot.tone)
                Text(
                    lot.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (lot.tone == Tone.Urgent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (lot.tone == Tone.Urgent) colors.error else colors.onSurface,
                    maxLines = 2,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                val today = remember { LocalDate.now() }
                expiryText(lot, today)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (lot.tone == Tone.Urgent) colors.error else colors.onSurfaceVariant,
                        fontWeight = if (lot.tone == Tone.Neutre) FontWeight.Normal else FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    onClick = { onPanel(if (panel == Panel.Qty) null else Panel.Qty) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    color = colors.background,
                    border = BorderStroke(1.dp, if (panel == Panel.Qty) colors.primary else colors.outlineVariant),
                    modifier = Modifier.semantics { contentDescription = "Quantité ${lot.label}, modifier" },
                ) {
                    Text(
                        lot.label.ifBlank { formatQty(lot.qty, lot.unit) },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        AnimatedVisibility(panel != null, enter = Motion.expand, exit = Motion.collapse) {
            // Garde le dernier panneau pendant la fermeture.
            var shown by remember { mutableStateOf(panel ?: Panel.Actions) }
            if (panel != null) shown = panel
            Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                when (shown) {
                    Panel.Actions -> Actions(enabled, onConsume, { onPanel(Panel.Move) }, { onPanel(Panel.Qty) })
                    Panel.Move -> MoveChoices(lot, zones, enabled, onMove) { onPanel(null) }
                    Panel.Qty -> QtyEditor(lot, enabled, onQty) { onPanel(null) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
    }
}

@Composable
private fun ToneDot(tone: Tone) {
    val color = when (tone) {
        Tone.Urgent -> MaterialTheme.colorScheme.error
        Tone.Attention -> MaterialTheme.colorScheme.tertiary
        Tone.Neutre -> Color.Transparent
    }
    Box(Modifier.padding(end = 10.dp).size(8.dp).background(color, CircleShape))
}

@Composable
private fun Pill(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    filled: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = if (filled) color else MaterialTheme.colorScheme.surface,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else color,
        border = if (filled) null else BorderStroke(1.5.dp, color),
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Actions(enabled: Boolean, onConsume: () -> Unit, onMove: () -> Unit, onQty: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("← Consommé", onConsume, enabled, MaterialTheme.colorScheme.error)
        Pill("Déplacer →", onMove, enabled)
        Pill("Quantité", onQty, enabled)
    }
}

@Composable
private fun MoveChoices(lot: Lot, zones: List<ZoneView>, enabled: Boolean, onMove: (String) -> Unit, onCancel: () -> Unit) {
    Column {
        Text("Déplacer vers", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            zones.filter { it.id != lot.location }.forEach { z -> Pill(z.label, { onMove(z.id) }, enabled) }
            TextButton(onClick = onCancel) { Text("Annuler") }
        }
    }
}

@Composable
private fun QtyEditor(lot: Lot, enabled: Boolean, onQty: (Double) -> Unit, onCancel: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var value by remember(lot.id, lot.qty) { mutableDoubleStateOf(lot.qty) }
    val step = lot.step.takeIf { it > 0 } ?: 1.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled && value > 0) {
            value = max(0.0, value - step)
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        Text(
            if (value <= 0) "tout consommé" else formatQty(value, lot.unit),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 104.dp).padding(horizontal = 6.dp),
        )
        StepButton("+", enabled) {
            value += step
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Annuler") }
        Pill("OK", { onQty(value) }, enabled && abs(value - lot.qty) > 1e-9, filled = true)
    }
}

@Composable
private fun RowScope.StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(44.dp).alpha(if (enabled) 1f else 0.45f),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun AddButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(MaisonActionCorner)
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = primary, spotColor = primary)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Text(
            "＋  Ajouter",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}
