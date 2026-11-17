package app.kairos.mobile.ui.politique.screens

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolRoute
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.RUBRICS
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.jeux.kit.PixelGrid
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.DockSlack
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.LocalPolBottom
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.openUrl
import app.kairos.mobile.ui.politique.kit.safeHttps
import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.reducedMotion
import app.kairos.mobile.ui.theme.KairosColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* Duel en pixel art : sélection façon jeu de combat, écran VS, une manche par écran, fin sans vainqueur. */

private val Mono = FontFamily.Monospace
private val Night = Color(0xFF0D1217)
private val PxInk = Color(0xFF10161C)
private val PxShade = Color(0x6B000000)
private val PipInk = Color(0xFF1A1A12)
private val Neutral = Color(0xFF8A938A)
private val Glide = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Jetons de la maquette, tirés du thème courant. */
@Immutable
internal class DuelInk(
    val dark: Boolean, val pri: Color, val onPri: Color, val pc: Color, val onPc: Color, val surf: Color, val ink: Color,
    val att: Color, val attTx: Color, val onsv: Color, val line: Color, val btn: Color, val pour: Color, val contre: Color,
) {
    val halo = if (dark) Color.White else Color.Black
    /** Teinte de la couleur principale, sous la trame cathodique. */
    val night = if (dark) pri.copy(alpha = 0.27f) else mix(surf, pri, 0.23f)
    val scan = pri.copy(alpha = 0.11f)
    val shadow = if (dark) Color(0x80000000) else Color(0x261F2620)
    val pourBg = mix(surf, pour, 0.16f)
    val contreBg = mix(surf, contre, 0.16f)
    val partBg = mix(surf, att, 0.2f)

    /** Liseré d'une couleur d'identité : plus sombre en clair, plus clair en sombre. */
    fun cd(c: Color) = mix(c, halo, 0.45f)

    /** Encre mêlée à la carte, comme `color-mix(surf, ink t)`. */
    fun inked(t: Float) = mix(surf, ink, t)
}

@Composable
internal fun duelInk(): DuelInk {
    val s = MaterialTheme.colorScheme
    val k = KairosColors.current
    val p = PolColors.current
    return DuelInk(
        s.background.luminance() < 0.5f, s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.surface, s.onSurface,
        s.tertiary, k.attentionText, s.onSurfaceVariant, s.outlineVariant, k.btn, p.pour, p.contre,
    )
}

/** Rectangle aux coins en marches ([steps] marches de [step]) ; [top] / [bottom] : coins coupés de ce côté. */
internal class CutShape(private val step: Dp, private val top: Boolean = true, private val bottom: Boolean = true, private val steps: Int = 2) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { step.toPx().roundToInt().coerceAtLeast(1).toFloat() }
        val w = size.width
        val h = size.height
        val tl = listOf(0f to steps * s) + (1..steps).flatMap { i -> listOf(i * s to (steps - i + 1) * s, i * s to (steps - i) * s) }
        val pts = buildList {
            if (top) { addAll(tl); addAll(tl.reversed().map { (x, y) -> w - x to y }) } else { add(0f to 0f); add(w to 0f) }
            if (bottom) { addAll(tl.map { (x, y) -> w - x to h - y }); addAll(tl.reversed().map { (x, y) -> x to h - y }) } else { add(w to h); add(0f to h) }
        }
        val p = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        p.close()
        return Outline.Generic(p)
    }
}

private val Cut = CutShape(3.dp)
private val Cut2 = CutShape(2.dp)

/** Fonction en escalier de CSS `steps(n, end)`. */
internal fun steps(n: Int) = Easing { f -> if (f >= 1f) 1f else floor(f * n) / n }

/** Trame cathodique : une ligne de 1 dp toutes les 4 dp. */
internal fun Modifier.scan(c: Color) = drawBehind {
    val u = 1.dp.toPx()
    var y = 3 * u
    while (y < size.height) { drawRect(c, Offset(0f, y.roundToInt().toFloat()), Size(size.width, u.roundToInt().coerceAtLeast(1).toFloat())); y += 4 * u }
}

/** Contour intérieur de [width] le long de [shape]. */
private fun DrawScope.innerStroke(shape: Shape, color: Color, width: Float, size: Size = this.size) {
    val o = shape.createOutline(size, layoutDirection, this) as? Outline.Generic ?: return
    clipPath(o.path) { drawPath(o.path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width * 2)) }
}

/** Silhouette de [shape] agrandie de [grow] et décalée de [dy]. */
private fun DrawScope.silhouette(shape: Shape, color: Color, grow: Float = 0f, dy: Float = 0f) {
    val o = shape.createOutline(Size(size.width + 2 * grow, size.height + 2 * grow), layoutDirection, this)
    translate(-grow, -grow + dy) { drawOutline(o, color) }
}

/** Bandes intérieures d'un rectangle : [t] en haut, [b] en bas, [l] à gauche, [r] à droite. */
private fun DrawScope.insetBands(color: Color, t: Float = 0f, b: Float = 0f, l: Float = 0f, r: Float = 0f, area: Size = size) {
    if (area.width <= 0f || area.height <= 0f) return
    if (t > 0) drawRect(color, Offset.Zero, Size(area.width, t))
    if (b > 0) drawRect(color, Offset(0f, area.height - b), Size(area.width, b))
    if (l > 0) drawRect(color, Offset.Zero, Size(l, area.height))
    if (r > 0) drawRect(color, Offset(area.width - r, 0f), Size(r, area.height))
}

private fun DrawScope.dashedLine(color: Color, y: Float, width: Float, on: Float, off: Float) =
    drawLine(color, Offset(0f, y), Offset(size.width, y), width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off)))

/** Trait pointillé de 1 dp sous l'élément. */
private fun Modifier.dashedBottom(c: Color) = drawBehind { val u = 1.dp.toPx(); dashedLine(c, size.height - u / 2, u, 3 * u, 3 * u) }

// ---------- Texte et pictogrammes pixel ----------

@Composable
internal fun PxImg(img: PxImage, color: Color, cell: Dp, modifier: Modifier = Modifier) {
    val s = cellPx(cell)
    val d = LocalDensity.current
    val light = mix(color, Color.White, 0.45f)
    Canvas(modifier.size(with(d) { (img.w * s).toDp() }, with(d) { (img.h * s).toDp() }).clearAndSetSemantics { }) {
        img.cells.forEach { (x, y, r) ->
            val c = when (r) { PxRole.Shadow -> PxShade; PxRole.Outline -> PxInk; PxRole.Light -> light; PxRole.Fill -> color }
            drawRect(c, Offset((x * s).toFloat(), (y * s).toFloat()), Size(s.toFloat(), s.toFloat()))
        }
    }
}

@Composable
internal fun PxText(text: String, color: Color, cell: Dp, modifier: Modifier = Modifier, bold: Boolean = false, outline: Boolean = false, shadow: Boolean = false, two: Boolean = false) {
    val img = remember(text, bold, outline, shadow, two) { pxText(text, bold, outline, shadow, two) }
    PxImg(img, color, cell, modifier)
}

@Composable
private fun PxIcon(name: String, color: Color, size: Dp, modifier: Modifier = Modifier) =
    PixelGrid(DUEL_ICONS.getValue(name), color, modifier.size(size))

// ---------- État de la sélection ----------

/** Choix en cours, gardé au retour du comparatif : famille et deux places. */
@Stable
class DuelPickState {
    var kind by mutableStateOf(IdKind.Personne)
    var pick by mutableStateOf(Pick())

    fun reset() { pick = Pick() }
}

private val KINDS = listOf(IdKind.Personne to "Candidats", IdKind.Parti to "Partis", IdKind.Camp to "Camps")

private fun PolData.colorOf(id: String): Color = identityColor(byId(id)?.couleur) ?: Neutral

/** Annonce pour le lecteur d'écran : texte invisible en région vivante. */
@Composable
private fun Say(text: String) {
    if (text.isNotEmpty()) Text(text, Modifier.size(1.dp).alpha(0f).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 1.sp)
}

/** Duel : choix des deux adversaires, puis rounds face à face. */
@Composable
fun DuelScreen(data: PolData, nav: PolNavigator, a: String?, b: String?, state: DuelPickState) {
    val ready = remember(data, a, b) { duelReady(data, a, b) }
    if (ready) DuelFight(data, nav, a!!, b!!, state) else DuelPick(data, nav, state)
}

@Composable
private fun Loading(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}

// ---------- Sélection ----------

@Composable
private fun DuelPick(data: PolData, nav: PolNavigator, st: DuelPickState) {
    val rosters by produceState<Map<IdKind, List<String>>?>(null, data) {
        value = withContext(Dispatchers.Default) { IdKind.entries.associateWith { duelRoster(data, it) } }
    }
    val r = rosters ?: return Loading("Chargement des adversaires")
    if (r.values.all { it.isEmpty() }) {
        EmptyState("Aucun duel possible", "Il faut deux candidats, deux partis ou deux camps qui ont des données comparables. Les rubriques restent ouvertes depuis l'accueil.", Modifier.padding(16.dp))
        return
    }
    val k = duelInk()
    val still = reducedMotion()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var say by remember { mutableStateOf("") }
    var lock by remember { mutableIntStateOf(0) }
    var vs by remember { mutableStateOf<List<String>?>(null) }
    // Le curseur glisse et claque d'abord, la grille change ensuite.
    var shown by remember { mutableStateOf(st.kind) }
    LaunchedEffect(st.kind) { if (!still && shown != st.kind) delay(260); shown = st.kind }
    val roster = r[shown].orEmpty()
    val name = { id: String -> if (id == RANDOM) "Au hasard" else data.byId(id)?.nom.orEmpty() }

    fun tap(id: String) {
        val before = st.pick
        st.pick = pickTap(before, id)
        val removed = id != RANDOM && id in before.sel
        say = if (removed) "${name(id)} retiré" else "${name(id)} en P${st.pick.sel.indexOf(id) + 1}"
    }

    fun launch() {
        if (!st.pick.ready) { Toast.makeText(context, "Choisissez deux adversaires, ou la case ?.", Toast.LENGTH_SHORT).show(); return }
        val res = resolvePick(st.pick.sel, roster.shuffled())
            ?: return Toast.makeText(context, "Pas assez d'adversaires pour un tirage.", Toast.LENGTH_SHORT).show()
        val random = RANDOM in st.pick.sel
        st.pick = Pick(res, st.pick.active)
        if (random) {
            lock++
            say = "Tirage : ${duelName(data, res[0])} contre ${duelName(data, res[1])}"
            scope.launch { delay(if (still) 0 else 700); vs = res }
        } else vs = res
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Arcade(data, st, k, roster, lock, still) { i ->
                if (st.pick.sel[i] != null) say = "P${i + 1} vidé"
                st.pick = slotTap(st.pick, i)
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Lever(st.kind, k, still, Modifier.weight(1f, fill = false).widthIn(max = 300.dp).fillMaxWidth()) { kd ->
                    if (kd == st.kind) return@Lever
                    st.kind = kd
                    st.reset()
                    say = "Mode ${KINDS.first { it.first == kd }.second.lowercase()}"
                }
                Text(roster.size.toString(), Modifier.padding(start = 12.dp), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.09.em, color = k.onsv)
            }
            val label = KINDS.first { it.first == shown }.second
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (roster + RANDOM).chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { id ->
                            val tags = st.pick.sel.indices.filter { st.pick.sel[it] == id }
                            Tile(data, id, shown, tags, k, Modifier.weight(1f)) { tap(id) }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        val bottom = (LocalPolBottom.current - DockSlack).coerceAtLeast(18.dp)
        PxButton(
            "Lancer le duel" + if (st.pick.ready) "" else " (choisir deux adversaires d'abord)", ::launch,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottom), enabled = st.pick.ready,
        ) { c ->
            PxText("LANCER LE DUEL", c, 2.dp, bold = true)
            PxIcon("fwd", c, 18.dp)
        }
        Say(say)
    }
    vs?.let { (a, b) -> VsScreen(data, a, b, still) { vs = null; nav.go(PolRoute.Duel(a, b)) } }
}

@Composable
private fun Arcade(data: PolData, st: DuelPickState, k: DuelInk, roster: List<String>, lock: Int, still: Boolean, onSlot: (Int) -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(k.night).scan(k.scan).drawBehind {
            val u = 1.dp.toPx()
            drawRect(k.pri.copy(alpha = 0.14f), Offset(0f, size.height - 8 * u), Size(size.width, 8 * u))
            drawRect(k.pri.copy(alpha = 0.3f), Offset(0f, size.height - 4 * u), Size(size.width, 4 * u))
        },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)) {
            Slot(data, 0, st, k, roster, lock, still, Modifier.weight(1f).fillMaxHeight()) { onSlot(0) }
            Box(Modifier.width(76.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val img = remember { vsImage() }
                PxImg(img, k.att, 3.dp)
            }
            Slot(data, 1, st, k, roster, lock, still, Modifier.weight(1f).fillMaxHeight()) { onSlot(1) }
        }
    }
}

/** Clignotement d'une seconde (moitié allumée), figé quand les animations sont coupées. */
@Composable
private fun blink(on: Boolean, still: Boolean): Boolean {
    var lit by remember { mutableStateOf(true) }
    LaunchedEffect(on, still) {
        lit = true
        if (!on || still) return@LaunchedEffect
        while (true) { delay(500); lit = !lit }
    }
    return lit
}

@Composable
private fun Slot(data: PolData, i: Int, st: DuelPickState, k: DuelInk, roster: List<String>, lock: Int, still: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val v = st.pick.sel[i]
    val full = v != null && v != RANDOM
    val act = st.pick.active == i
    val c = if (full) data.colorOf(v) else null
    val lit = blink(act && !full, still)
    val fc = when {
        c != null -> c
        act -> if (lit) k.att else Color.Transparent
        else -> k.ink.copy(alpha = 0.24f)
    }
    val inner = if (c != null) mix(k.surf, c, 0.24f) else k.surf.copy(alpha = 0.55f)
    val cd = c?.let(k::cd)
    val label = "P${i + 1} : " + when (v) { null -> "vide. Toucher pour choisir ce joueur"; RANDOM -> "au hasard. Toucher pour retirer"; else -> "${data.byId(v)?.nom}. Toucher pour retirer" }
    Box(
        modifier.heightIn(min = 198.dp).clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .drawBehind {
                val u = 1.dp.toPx()
                drawOutline(Cut.createOutline(size, layoutDirection, this), fc)
                if (cd != null) innerStroke(Cut, cd, u)
                val g = 3 * u
                translate(g, g) {
                    val s = Size(size.width - 2 * g, size.height - 2 * g)
                    drawOutline(Cut.createOutline(s, layoutDirection, this), inner)
                    if (cd != null) innerStroke(Cut, cd, u, s)
                }
            },
    ) {
        PxText("P${i + 1}", k.ink, 2.dp, Modifier.align(if (i == 0) Alignment.TopStart else Alignment.TopEnd).padding(10.dp), bold = true)
        Column(
            Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 26.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            val q = @Composable { Box(Modifier.height(84.dp), contentAlignment = Alignment.Center) { PxText("?", k.att, 6.dp, outline = true, shadow = true) } }
            when {
                v == null -> { q(); Text(if (act) "À choisir" else "En attente", fontSize = 12.sp, lineHeight = 15.sp, color = k.onsv) }
                v == RANDOM -> {
                    if (still || roster.isEmpty()) q() else Shuffler(data, roster, 84.dp)
                    SlotName("Au hasard", k)
                    SlotSub("Tirage au lancement", k)
                }
                else -> {
                    val scale = remember { Animatable(1f) }
                    LaunchedEffect(lock) { if (lock > 0 && !still) { scale.snapTo(1.25f); scale.animateTo(1f, tween(400, easing = steps(3))) } }
                    data.byId(v)?.let { x ->
                        IdentityBadge(x, 84.dp, Modifier.graphicsLayer { scaleX = scale.value * if (i == 1) -1f else 1f; scaleY = scale.value }, shape = Cut2)
                    }
                    SlotName(duelName(data, v), k)
                    SlotSub(duelSub(data, v), k)
                }
            }
        }
    }
}

@Composable
private fun SlotName(t: String, k: DuelInk) =
    Text(frTypo(t), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = k.ink)

@Composable
private fun SlotSub(t: String, k: DuelInk) =
    Text(frTypo(t), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = k.onsv)

/** Case « ? » posée : les personnages défilent jusqu'au tirage. */
@Composable
private fun Shuffler(data: PolData, roster: List<String>, size: Dp, modifier: Modifier = Modifier) {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(roster) { while (true) { delay(140); t++ } }
    data.byId(roster[t % roster.size])?.let { IdentityBadge(it, size, modifier, shape = Cut2) }
}

/** Sélecteur à trois crans : platine en creux, curseur en relief qui glisse cran par cran et s'enfonce à l'appui. */
@Composable
private fun Lever(kind: IdKind, k: DuelInk, still: Boolean, modifier: Modifier, onKind: (IdKind) -> Unit) {
    val idx = KINDS.indexOfFirst { it.first == kind }
    val pos by animateFloatAsState(idx.toFloat(), tween(if (still) 0 else 210, easing = steps(3)), label = "cran")
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    var clk by remember { mutableIntStateOf(0) }
    var snap by remember { mutableStateOf(false) }
    LaunchedEffect(clk) { if (clk > 0 && !still) { delay(120); snap = true; delay(120); snap = false } }
    Box(
        modifier.height(56.dp).clip(Cut2).background(k.inked(0.12f)).drawBehind {
            val u = 1.dp.toPx()
            insetBands(k.inked(0.2f), t = 4 * u)
            insetBands(k.inked(0.28f), 2 * u, 2 * u, 2 * u, 2 * u)
        }.padding(4.dp),
    ) {
        Row(Modifier.fillMaxSize().selectableGroup()) {
            KINDS.forEachIndexed { i, (kd, label) ->
                val sel = kd == kind
                Box(
                    Modifier.weight(1f).fillMaxHeight().selectable(sel, src, null, role = Role.RadioButton) { clk++; onKind(kd) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i > 0) Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(20.dp).background(k.inked(0.24f)))
                    Text(label.uppercase(), Modifier.padding(top = 2.dp), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.05.em, color = if (sel) Color.Transparent else k.onsv)
                }
            }
        }
        val down = if (pressed || snap) 4.dp else 0.dp
        Box(
            Modifier.fillMaxWidth(1f / 3f).height(44.dp).layout { m, c ->
                val p = m.measure(c)
                layout(p.width, p.height) { p.place((pos * p.width).roundToInt(), 0) }
            },
        ) {
            Box(Modifier.padding(top = 6.dp).fillMaxSize().clip(Cut2).background(mix(k.pri, Color.Black, 0.4f)))
            Box(
                Modifier.offset(y = down).fillMaxWidth().height(40.dp).clip(Cut2).background(k.pri).drawBehind {
                    val u = 3.dp.toPx()
                    insetBands(mix(k.pri, Color.Black, 0.18f), b = u, r = u)
                    insetBands(mix(k.pri, Color.White, 0.28f), t = u, l = u)
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(KINDS[idx].second.uppercase(), Modifier.clearAndSetSemantics { }, fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp, letterSpacing = 0.05.em, color = k.onPri)
            }
        }
    }
}

/** Tuile du roster : visage sur le fond du bouton Kairos, nom dessous ; pastilles P1 (haut gauche) et P2 (haut droit). */
@Composable
private fun Tile(data: PolData, id: String, kind: IdKind, tags: List<Int>, k: DuelInk, modifier: Modifier, onClick: () -> Unit) {
    val rnd = id == RANDOM
    val x = if (rnd) null else data.byId(id)
    if (!rnd && x == null) return Spacer(modifier)
    val c = if (rnd) k.att else data.colorOf(id)
    val cd = k.cd(c)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sel = tags.isNotEmpty()
    val name = if (rnd) "Au hasard" else if (kind == IdKind.Parti) x!!.parti?.takeIf { it.isNotBlank() } ?: x.nom else duelName(data, id)
    val label = (if (rnd) "Au hasard" else x!!.nom + if (kind == IdKind.Personne && !x.parti.isNullOrBlank()) ", ${x.parti}" else "") +
        if (sel) ", choisi en " + tags.joinToString(" et ") { "P${it + 1}" } else ""
    val down = if (pressed) 2.dp else 0.dp
    Box(
        modifier.clickable(src, null, role = Role.Button, onClick = onClick).semantics { contentDescription = label; selected = sel }.drawBehind {
            val u = 1.dp.toPx()
            if (sel) {
                silhouette(Cut2, k.shadow, 4 * u, 3 * u)
                silhouette(Cut2, cd, 4 * u)
                silhouette(Cut2, c, 3 * u)
            } else silhouette(Cut2, k.shadow, dy = 3 * u)
        },
    ) {
        Column(Modifier.offset(y = down)) {
            Box(
                Modifier.fillMaxWidth().height(60.dp).clip(CutShape(2.dp, bottom = false)).background(if (rnd) mix(k.surf, k.pri, 0.18f) else k.btn),
                contentAlignment = Alignment.Center,
            ) {
                if (rnd) PxText("?", k.att, 4.dp, outline = true, shadow = true) else IdentityBadge(x!!, 58.dp)
            }
            Text(
                frTypo(name), Modifier.fillMaxWidth().height(24.dp).clip(CutShape(2.dp, top = false)).background(k.surf).padding(horizontal = 2.dp),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = k.ink,
            )
        }
        tags.forEach { i ->
            Box(Modifier.align(if (i == 0) Alignment.TopStart else Alignment.TopEnd).clip(Cut2).background(k.ink).padding(3.dp)) {
                PxText("P${i + 1}", k.surf, 2.dp)
            }
        }
    }
}

/** Bouton physique pixel : platine, flanc en marches, dessus en aplat à reflet ; l'appui descend le dessus. */
@Composable
private fun PxButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false, alt: Boolean = false,
    content: @Composable RowScope.(Color) -> Unit,
) {
    val k = duelInk()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val kc = if (!enabled) mix(k.line, k.onsv, 0.2f) else if (alt) k.surf else k.pri
    val kt = if (!enabled) k.onsv else if (alt) k.ink else k.onPri
    val flank = if (alt && enabled) k.inked(0.3f) else mix(kc, Color.Black, 0.4f)
    val down = if (!enabled || pressed) 6.dp else 0.dp
    Box(
        modifier.fillMaxWidth().height(if (small) 52.dp else 64.dp).clickable(src, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; if (!enabled) disabled() },
    ) {
        Box(Modifier.padding(top = if (small) 6.dp else 8.dp).fillMaxSize().clip(Cut).background(flank))
        Box(
            Modifier.offset(y = down).fillMaxWidth().height(if (small) 44.dp else 56.dp).clip(Cut).background(kc).drawBehind {
                val u = 1.dp.toPx()
                if (alt) {
                    insetBands(Color.White.copy(alpha = 0.4f), t = 5 * u, l = 5 * u)
                    insetBands(k.inked(0.3f), 2 * u, 2 * u, 2 * u, 2 * u)
                } else {
                    insetBands(mix(kc, Color.Black, 0.18f), b = 3 * u, r = 3 * u)
                    insetBands(mix(kc, Color.White, 0.3f), t = 3 * u, l = 3 * u)
                }
                if (enabled) drawRect(Color.White.copy(alpha = 0.7f), Offset(12 * u, 7 * u), Size(12 * u, 3 * u))
            },
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content(kt) }
        }
    }
}

// ---------- VS plein écran ----------

/** Diagonale en marches : la moitié gauche ([left]) ou droite de l'écran. */
private fun stairShape(left: Boolean) = GenericShape { s, _ ->
    val xs = vsStairs()
    val n = xs.size - 1
    if (left) moveTo(0f, 0f) else moveTo(s.width, 0f)
    xs.forEachIndexed { i, f ->
        lineTo(f * s.width, i.toFloat() / n * s.height)
        lineTo(f * s.width, min(1f, (i + 1).toFloat() / n) * s.height)
    }
    lineTo(if (left) 0f else s.width, s.height)
    close()
}

@Composable
private fun VsScreen(data: PolData, a: String, b: String, still: Boolean, onDone: () -> Unit) {
    var done by remember { mutableStateOf(false) }
    val end = { if (!done) { done = true; onDone() } }
    val slide = remember { Animatable(if (still) 0f else 1f) }
    val stamp = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!still) {
            launch { slide.animateTo(0f, tween(450, easing = steps(5))) }
            launch { stamp.animateTo(1f, tween(500, delayMillis = 350, easing = steps(4))) }
        }
        delay(if (still) 1400 else 1900)
        end()
    }
    Dialog(onDismissRequest = end, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Night).clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Passer") { end() }
                .semantics { contentDescription = "${data.byId(a)?.nom} contre ${data.byId(b)?.nom}. Aucun vainqueur ne sera désigné." },
        ) {
            val w = maxWidth
            listOf(a, b).forEachIndexed { i, id ->
                val x = data.byId(id) ?: return@forEachIndexed
                Box(
                    Modifier.fillMaxSize().graphicsLayer { translationX = slide.value * size.width * if (i == 0) -1f else 1f }
                        .clip(stairShape(i == 0)).background(mix(Night, data.colorOf(id), 0.58f)),
                ) {
                    Column(
                        Modifier.align(if (i == 0) Alignment.TopStart else Alignment.BottomEnd).padding(horizontal = 22.dp)
                            .padding(top = if (i == 0) w * 0.09f else 0.dp, bottom = if (i == 1) w * 0.12f else 0.dp),
                        horizontalAlignment = if (i == 0) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PxText("P${i + 1}", Color.White, 3.dp, outline = true)
                        IdentityBadge(x, 132.dp, if (i == 1) Modifier.graphicsLayer { scaleX = -1f } else Modifier, shape = Cut2)
                        Text(frTypo(duelName(data, id)), style = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, shadow = Shadow(Color(0x88000000), Offset(4f, 4f), 0f)), textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
                        Text(frTypo(duelSub(data, id)), fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f), textAlign = if (i == 0) TextAlign.Start else TextAlign.End)
                    }
                }
            }
            val img = remember { vsImage() }
            PxImg(img, duelInk().att, 8.dp, Modifier.align(Alignment.Center).graphicsLayer {
                val t = stamp.value
                val s = 2.4f - 1.4f * t
                scaleX = s; scaleY = s
                alpha = min(1f, t / 0.6f)
            })
            Text("Toucher pour passer", Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

// ---------- Comparatif ----------

/** Les deux côtés : noms, sous-titres, couleurs ; [hatch] : second côté hachuré si les couleurs se confondent. */
private class Sides(val ids: List<String>, val names: List<String>, val subs: List<String>, val colors: List<Color>, val hatch: Boolean)

/** État du comparatif partagé par les cartes. */
private class Fight(
    val data: PolData, val nav: PolNavigator, val k: DuelInk, val s: Sides, val still: Boolean,
    val help: MutableMap<Int, Boolean>, val wins: MutableMap<String, Int>, val grown: List<Int>, val say: (String) -> Unit,
)

@Composable
private fun DuelFight(data: PolData, nav: PolNavigator, a: String, b: String, st: DuelPickState) {
    val rounds by produceState<List<DuelRound>?>(null, data, a, b) { value = withContext(Dispatchers.Default) { duelRounds(data, a, b) } }
    val r = rounds ?: return Loading("Préparation du duel")
    val k = duelInk()
    val still = reducedMotion()
    val s = remember(data, a, b) {
        Sides(listOf(a, b), listOf(duelName(data, a), duelName(data, b)), listOf(duelSub(data, a), duelSub(data, b)), listOf(data.colorOf(a), data.colorOf(b)), hatchSecond(data.byId(a)?.couleur, data.byId(b)?.couleur))
    }
    val n = r.size
    val pager = rememberPagerState { n + 1 }
    val scope = rememberCoroutineScope()
    val seen = remember(a, b) { mutableStateListOf<Int>() }
    val grown = remember(a, b) { mutableStateListOf<Int>() }
    val help = remember(a, b) { mutableStateMapOf<Int, Boolean>() }
    val wins = remember(a, b) { mutableStateMapOf<String, Int>() }
    var say by remember { mutableStateOf("") }
    var ann by remember { mutableStateOf<Int?>(null) }
    var annKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(pager, n) {
        snapshotFlow { pager.settledPage }.collect { i ->
            val first = i !in seen
            if (i < n && first) seen += i
            say = if (i < n) "Round ${i + 1} sur $n : ${r[i].title}" else "Fin du duel. Aucun vainqueur."
            if (first && !still) { ann = i; annKey++ }
            if (i < n && i !in grown) launch { if (first && !still) delay(500); grown += i }
        }
    }
    val go = { i: Int -> scope.launch { if (still) pager.scrollToPage(i) else pager.animateScrollToPage(i) }; Unit }
    val f = Fight(data, nav, k, s, still, help, wins, grown) { say = it }
    Column(Modifier.fillMaxSize()) {
        Hud(f, r, pager.currentPage, seen, go)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            HorizontalPager(
                pager, Modifier.fillMaxSize().semantics { contentDescription = "Rounds, glisser pour changer" },
                pageSpacing = 12.dp, verticalAlignment = Alignment.Top, beyondViewportPageCount = 1,
            ) { i ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp + LocalPolBottom.current)) {
                    if (i < n) RoundCard(f, i, r[i])
                    else {
                        if (n == 0) Text("Rien à comparer pour l'instant entre ces deux adversaires.", Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), fontSize = 12.5.sp, fontStyle = FontStyle.Italic, color = k.onsv)
                        FinCard(f, data.kindOf(a) == IdKind.Camp, onRematch = { go(0) }) { st.reset(); nav.replace(PolRoute.Duel()) }
                    }
                }
            }
            if (!still) ann?.let { i -> Annonce(i, annKey, r, k, Modifier.padding(top = maxHeight * 0.3f)) { if (ann == i) ann = null } }
        }
        Say(say)
    }
}

@Composable
private fun Hud(f: Fight, r: List<DuelRound>, cur: Int, seen: List<Int>, go: (Int) -> Unit) {
    val k = f.k
    Column(Modifier.fillMaxWidth().background(k.night).scan(k.scan).padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Fighter(f, 0, r, Modifier.weight(1f))
            val img = remember { vsImage(shadow = false) }
            PxImg(img, k.att, 2.dp)
            Fighter(f, 1, r, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
            (0..r.size).forEach { i ->
                val now = cur == i
                val cup = i == r.size
                val bg = if (now) k.att else if (i in seen) k.surf else k.surf.copy(alpha = 0.45f)
                val ring = if (now) mix(k.att, Color.Black, 0.38f) else k.ink.copy(alpha = 0.22f)
                val foot = if (now) mix(k.att, Color.Black, 0.2f) else k.ink.copy(alpha = 0.1f)
                Box(
                    Modifier.size(48.dp).clickable(role = Role.Button) { go(i) }
                        .semantics { contentDescription = if (cup) "Fin du duel" else "Round ${i + 1} : ${r[i].title}"; selected = now },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(30.dp).background(bg).drawBehind { val u = 1.dp.toPx(); insetBands(foot, b = 5 * u); insetBands(ring, 2 * u, 2 * u, 2 * u, 2 * u) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cup) PxIcon("cup", if (now) PipInk else k.attTx, 18.dp) else PxText("${i + 1}", if (now) PipInk else k.ink, 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Fighter(f: Fight, i: Int, r: List<DuelRound>, modifier: Modifier) {
    val k = f.k
    val x = f.data.byId(f.s.ids[i]) ?: return Spacer(modifier)
    val c = f.s.colors[i]
    val right = i == 1
    val count = r.count { if (right) it.avail.second else it.avail.first }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, if (right) Alignment.End else Alignment.Start)) {
        val face = @Composable {
            Box(Modifier.clip(Cut2).background(c).drawBehind { innerStroke(Cut2, k.cd(c), 1.dp.toPx()) }.padding(3.dp)) {
                IdentityBadge(x, 48.dp, if (right) Modifier.graphicsLayer { scaleX = -1f } else Modifier, shape = Cut2)
            }
        }
        if (!right) face()
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (right) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(frTypo(f.s.names[i]), fontWeight = FontWeight.Bold, fontSize = 14.5.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = k.ink)
            Text(frTypo(f.s.subs[i]), fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = k.onsv)
            if (r.isNotEmpty()) Row(
                Modifier.padding(top = 3.dp).clearAndSetSemantics { contentDescription = "Données : $count rounds sur ${r.size}. Couverture, pas un score." },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val num = @Composable { Text("$count/${r.size}", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.04.em, color = k.onsv) }
                if (right) num()
                Row(Modifier.background(k.ink.copy(alpha = 0.16f)).padding(1.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    r.forEach { round ->
                        val on = if (right) round.avail.second else round.avail.first
                        Box(Modifier.size(6.dp, 8.dp).background(if (on) c else k.surf.copy(alpha = 0.75f)).drawBehind { if (on) insetBands(k.cd(c), 1.dp.toPx(), 1.dp.toPx(), 1.dp.toPx(), 1.dp.toPx()) })
                    }
                }
                if (!right) num()
            }
        }
        if (right) face()
    }
}

/** Annonce « ROUND n » : bande teintée qui traverse l'arène, en crans. */
@Composable
private fun Annonce(i: Int, key: Int, r: List<DuelRound>, k: DuelInk, modifier: Modifier, onEnd: () -> Unit) {
    val x = remember(key) { Animatable(-1.05f) }
    LaunchedEffect(key) {
        x.animateTo(0f, tween(275, easing = steps(5)))
        delay(700)
        x.animateTo(1.05f, tween(275, easing = steps(5)))
        onEnd()
    }
    Column(
        modifier.fillMaxWidth().graphicsLayer { translationX = x.value * size.width }.clearAndSetSemantics { }
            .drawBehind { val u = 4.dp.toPx(); val c = k.pri.copy(alpha = 0.55f); drawRect(c, Offset(0f, -u), Size(size.width, u)); drawRect(c, Offset(0f, size.height), Size(size.width, u)) }
            .background(mix(k.surf.copy(alpha = 0.86f), k.pri, 0.22f)).scan(k.scan).padding(top = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (i < r.size) {
            PxText("ROUND ${i + 1}", k.att, 5.dp, bold = true, outline = true, shadow = true, two = true)
            Text(frTypo(r[i].title), Modifier.fillMaxWidth(0.9f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = k.ink)
        } else {
            PxText("FIN", k.att, 6.dp, bold = true, outline = true, shadow = true, two = true)
            Text("Aucun vainqueur", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = k.ink)
        }
    }
}

// ---------- Carte de round ----------

/**
 * Appui long n'importe où dans l'élément, boutons compris : une fois reconnu, la suite du geste est
 * consommée avant les enfants, qui ne voient jamais le lâcher (aucun clic déclenché en plus).
 */
internal suspend fun androidx.compose.ui.input.pointer.PointerInputScope.longPress(onLong: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val slop = viewConfiguration.touchSlop
    val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed || (c.position - down.position).getDistance() > slop) break
        }
        true
    }
    if (ended != null) return@awaitEachGesture
    onLong()
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        e.changes.forEach { it.consume() }
        if (e.changes.none { it.pressed }) break
    }
}

/** Carte : coins en marches, contour pixel, ombre dure. */
private fun Modifier.cardFrame(k: DuelInk) = drawBehind {
    val u = 1.dp.toPx()
    silhouette(Cut, k.shadow, 2 * u, 4 * u)
    silhouette(Cut, k.line, 2 * u)
}

/** Bouton poussoir pixel : dessus en aplat, flanc de 4 dp ; l'appui (ou l'état ouvert) descend le dessus. */
@Composable
private fun PushBtn(label: String, onClick: () -> Unit, k: DuelInk, expanded: Boolean? = null, enabled: Boolean = true, icon: Boolean = false, content: @Composable RowScope.(Color) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val open = expanded == true
    val down = pressed || open
    val bg = if (open) k.pc else k.surf
    val fg = if (open) k.onPc else k.ink
    val ring = if (open) mix(k.pc, k.ink, 0.45f) else k.inked(0.38f)
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(src, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; expanded?.let { stateDescription = if (it) "ouvert" else "fermé" } }.alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.offset(y = if (down) 2.dp else (-2).dp).height(30.dp).let { if (icon) it.width(30.dp) else it }
                .drawBehind {
                    val u = 1.dp.toPx()
                    if (!down) drawRect(k.inked(0.48f), Offset(0f, size.height), Size(size.width, 4 * u))
                    drawRect(bg)
                    if (!down) insetBands(k.inked(0.12f), b = 3 * u)
                    insetBands(ring, 2 * u, 2 * u, 2 * u, 2 * u)
                }.padding(horizontal = if (icon) 0.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        ) { content(fg) }
    }
}

/** Volet qui se déroule en crans ([n] images en [ms]) ; [override] : hauteur imposée pendant la prise de la poignée. */
@Composable
private fun Unroll(open: Boolean, still: Boolean, ms: Int, n: Int, modifier: Modifier = Modifier, closeDelay: Int = 0, override: Float? = null, onHeight: (Int) -> Unit = {}, content: @Composable () -> Unit) {
    val frac = remember { Animatable(if (open) 1f else 0f) }
    // Lâcher de la poignée : le volet est déjà à sa place, il s'y cale sans rejouer le déroulé.
    val held = remember { booleanArrayOf(false) }
    LaunchedEffect(open, override == null) {
        if (override != null) { held[0] = true; return@LaunchedEffect }
        val target = if (open) 1f else 0f
        if (still || held[0]) { held[0] = false; frac.snapTo(target); return@LaunchedEffect }
        if (!open) delay(closeDelay.toLong())
        frac.animateTo(target, tween(ms, easing = steps(n)))
    }
    Layout(content, modifier.clipToBounds()) { ms2, c ->
        val p = ms2.firstOrNull()?.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val full = p?.height ?: 0
        onHeight(full)
        val h = (override?.roundToInt() ?: (full * frac.value).roundToInt()).coerceIn(0, full)
        layout(c.maxWidth, h) { p?.place(0, 0) }
    }
}

@Composable
private fun RoundCard(f: Fight, i: Int, round: DuelRound) {
    val k = f.k
    val open = f.help[i] == true
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var drag by remember { mutableStateOf<Float?>(null) }
    var full by remember { mutableIntStateOf(0) }
    var start by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    fun setHelp(o: Boolean) {
        f.help[i] = o
        if (o) f.say("Aide : " + round.help.joinToString(" "))
    }
    Box(
        Modifier.fillMaxWidth().cardFrame(k).pointerInput(i) {
            longPress { haptic.performHapticFeedback(HapticFeedbackType.LongPress); setHelp(true) }
        }.semantics { contentDescription = "Round ${i + 1} : ${round.title}" },
    ) {
        Column(Modifier.clip(Cut).background(k.surf)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).background(k.night).scan(k.scan).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f)) { PxText("ROUND ${i + 1}", k.ink, 2.dp, bold = true) }
                // Onglet de rubrique seulement quand la rubrique a un écran dans l'app.
                if (RUBRICS.any { it.id == round.rubric.id }) PushBtn("Rubrique ${round.rubric.label}", { f.nav.go(PolRoute.Rubric(round.rubric.id)) }, k) { c ->
                    Text(round.rubric.label, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.03.em, color = c)
                    PxIcon("chev", c, 10.dp)
                }
                PushBtn("Aide : ${round.title}", { setHelp(!open) }, k, expanded = open, enabled = drag == null, icon = true) { c -> PxIcon("info", c, 12.dp) }
            }
            Unroll(open, f.still, 300, 6, closeDelay = 140, override = drag, onHeight = { full = it }) { Drawer(f, round, open || drag != null) }
            Box {
                Column {
                    Spacer(Modifier.height(13.dp))
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 4.dp)) {
                        Text(frTypo(round.title), Modifier.semantics { heading() }, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.005).em, color = k.ink)
                        RoundBody(f, i, round)
                    }
                    SourceFoot(f, round.sources)
                }
                val step = with(density) { 12.dp.toPx() }
                Grip(k, drag != null, Modifier.align(Alignment.TopCenter).offset(y = (-1).dp).zIndex(1f), onTap = { setHelp(!open) }) { phase, dy ->
                    when (phase) {
                        0 -> { start = if (open) full.toFloat() else 0f; drag = start }
                        1 -> drag = gripHeight(start, dy, full.toFloat(), step)
                        else -> {
                            val cur = drag ?: return@Grip
                            val o = gripOpens(cur, full.toFloat(), start > 0f)
                            scope.launch {
                                val a = Animatable(cur)
                                if (!f.still) a.animateTo(if (o) full.toFloat() else 0f, tween(240, easing = steps(4))) { drag = value }
                                f.help[i] = o
                                drag = null
                                if (o) f.say("Aide : " + round.help.joinToString(" "))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Poignée collée au bord du tiroir : se tire au doigt ([onDrag] : 0 début, 1 suivi avec le déplacement total, 2 lâcher). */
@Composable
private fun Grip(k: DuelInk, held: Boolean, modifier: Modifier, onTap: () -> Unit, onDrag: (Int, Float) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    var acc by remember { mutableFloatStateOf(0f) }
    val cb by rememberUpdatedState(onDrag)
    val c = if (held || pressed) k.pri else mix(k.pc, k.ink, 0.35f)
    Box(
        modifier.size(72.dp, 48.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f; cb(0, 0f) },
                    onDragEnd = { cb(2, acc) }, onDragCancel = { cb(2, acc) },
                ) { ch, dy -> ch.consume(); acc += dy; cb(1, acc) }
            }
            .clickable(src, null, role = Role.Button, onClick = onTap)
            .semantics { contentDescription = "Poignée de l'aide : tirer vers le bas pour ouvrir, remonter pour fermer" },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.size(48.dp, 14.dp)) {
            val u = 1.dp.toPx()
            val w = size.width
            val h = size.height
            val p = Path().apply {
                moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h - 4 * u); lineTo(w - 3 * u, h - 4 * u); lineTo(w - 3 * u, h)
                lineTo(3 * u, h); lineTo(3 * u, h - 4 * u); lineTo(0f, h - 4 * u); close()
            }
            drawPath(p, c)
            drawRect(k.surf, Offset(14 * u, 3 * u), Size(w - 28 * u, 2 * u))
            drawRect(k.surf, Offset(14 * u, 7 * u), Size(w - 28 * u, 2 * u))
        }
    }
}

/** Tiroir pixel de l'aide : conteneur principal tramé, bords à l'encre, bas en marches ; texte révélé une fois déroulé. */
@Composable
private fun Drawer(f: Fight, round: DuelRound, shown: Boolean) {
    val k = f.k
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 180 else 100, delayMillis = if (shown && !f.still) 320 else 0, easing = steps(if (shown) 3 else 2)), label = "aide")
    val dk = mix(k.pc, k.ink, 0.35f)
    Box(Modifier.padding(horizontal = 10.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(CutShape(3.dp, top = false)).background(mix(k.surf, k.pc, 0.7f)).scan(k.scan)
                .drawBehind { val u = 2.dp.toPx(); insetBands(dk, b = u, l = u, r = u) }.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)
                .alpha(if (f.still) 1f else a),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            round.help.forEach { Text(frTypo(it), fontSize = 13.sp, lineHeight = 18.sp, color = k.ink) }
            if (round is VotesRound) VCase.entries.forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PxIcon(v.icon, k.ink, 14.dp)
                    Text(v.label, fontSize = 13.sp, lineHeight = 18.sp, color = k.ink)
                }
            }
        }
    }
}

/** Pied : toujours les sources ; évaluateur reconnu → sa fiche, adresse https → l'article. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceFoot(f: Fight, list: List<SourceRef>) {
    if (list.isEmpty()) return
    val k = f.k
    val context = LocalContext.current
    FlowRow(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp).fillMaxWidth()
            .drawBehind { val u = 2.dp.toPx(); drawLine(k.line, Offset(0f, u / 2), Offset(size.width, u / 2), u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(u, u))) }
            .padding(top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.forEach { s ->
            val ev = f.data.evFor(s.label, s.ev)
            val url = safeHttps(s.url)
            val name = ev?.nom?.replace(Regex("""\s*\(.*\)$"""), "") ?: s.label.split(Regex(", (?=[«A-Z0-9])"))[0]
            val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.removePrefix("www.")
            val txt = ev == null && url == null
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                Row(Modifier.let { if (txt) it.heightIn(min = 28.dp) else it.height(28.dp) }.clip(Cut2).background(k.inked(0.07f)), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        frTypo(name),
                        Modifier.weight(1f, fill = false).let { m -> if (ev != null) m.clickable(role = Role.Button, onClickLabel = "Fiche : ${ev.nom}") { f.nav.sheet(PolSheetState.Evaluator(ev.id)) } else m }
                            .padding(horizontal = 8.dp, vertical = if (txt) 4.dp else 0.dp),
                        fontSize = 12.sp, lineHeight = if (txt) 15.6.sp else 28.sp, fontWeight = if (txt) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (txt) k.onsv else k.ink, maxLines = if (txt) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (url != null && host != null) Row(
                        Modifier.fillMaxHeight().clickable(role = Role.Button, onClickLabel = "Article : $host") { openUrl(context, url) }
                            .drawBehind { drawRect(k.surf, size = Size(2.dp.toPx(), size.height)) }.padding(horizontal = 8.dp).widthIn(max = 150.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(host, Modifier.weight(1f, fill = false), fontSize = 12.sp, color = k.onsv, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PxIcon("ext", k.onsv, 9.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundBody(f: Fight, i: Int, round: DuelRound) {
    val grown = f.still || i in f.grown
    when (round) {
        is FinRound -> round.gauges.forEachIndexed { j, g -> GaugeView(f, g, grown, last = j == round.gauges.lastIndex) }
        is WalletRound -> WalletBody(f, i, round, grown)
        is GapRound -> GapBody(f, round)
        is BilanRound -> BilanBody(f, round)
        is VotesRound -> VotesBody(f, round)
        is TurnRound -> TurnBody(f, i, round)
        is CardRound -> round.blocks.forEachIndexed { j, b ->
            when (b) {
                is GaugeBlock -> GaugeView(f, b.gauge, grown, last = j == round.blocks.lastIndex || round.blocks.getOrNull(j + 1) !is GaugeBlock)
                is Cols -> SectionView(f, b.title, b.a, b.b)
            }
        }
    }
}

// ---------- Deux colonnes ----------

/** Pointillé vertical à la couleur d'un côté, liseré en [cd] ; [x] : bord gauche, 4 dp de large. */
private fun DrawScope.sepLine(x: Float, c: Color, cd: Color) {
    val u = 1.dp.toPx()
    val h = size.height
    val x0 = x.roundToInt().toFloat()
    var y = 0f
    while (y < h) { drawRect(cd, Offset(x0, y), Size(4 * u, min(8 * u, h - y))); y += 10 * u }
    y = u
    while (y < h) { drawRect(c, Offset(x0 + u, y), Size(2 * u, min(6 * u, h - y))); y += 10 * u }
}

/** Deux colonnes, P1 à gauche, P2 à droite, séparées par deux pointillés à leurs couleurs (6 dp d'écart). */
@Composable
private fun TwoSep(f: Fight, modifier: Modifier = Modifier, content: @Composable ColumnScope.(Int) -> Unit) {
    val k = f.k
    val (c0, c1) = f.s.colors
    Row(
        modifier.fillMaxWidth().drawBehind {
            val u = 1.dp.toPx()
            sepLine(size.width / 2 - 7 * u, c0, k.cd(c0))
            sepLine(size.width / 2 + 3 * u, c1, k.cd(c1))
        },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        (0..1).forEach { s -> Column(Modifier.weight(1f)) { content(s) } }
    }
}

@Composable
private fun H4(t: String, k: DuelInk, modifier: Modifier = Modifier) =
    Text(frTypo(t), modifier, fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = k.ink)

@Composable
private fun Na(t: String, k: DuelInk, modifier: Modifier = Modifier) =
    Text(frTypo(t), modifier.padding(vertical = 6.dp), fontSize = 12.5.sp, fontStyle = FontStyle.Italic, color = k.onsv)

// ---------- Jauges ----------

@Composable
private fun GaugeView(f: Fight, g: Gauge, grown: Boolean, last: Boolean) {
    val k = f.k
    Column(Modifier.fillMaxWidth().let { if (last) it else it.dashedBottom(k.line) }.padding(top = 12.dp, bottom = if (last) 4.dp else 12.dp)) {
        H4(g.title, k, Modifier.padding(bottom = 6.dp))
        Row(Modifier.padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(g.a, g.b).forEachIndexed { s, v ->
                Column(Modifier.weight(1f), horizontalAlignment = if (s == 0) Alignment.Start else Alignment.End) {
                    if (v == null) Text(NO_DATA, fontSize = 13.sp, lineHeight = 17.sp, color = k.onsv)
                    else Text(g.label(v), fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, lineHeight = 19.5.sp, letterSpacing = (-0.02).em, color = k.ink, textAlign = if (s == 0) TextAlign.Start else TextAlign.End)
                    v?.let(g::gap)?.let { Text(frTypo(it), Modifier.padding(top = 2.dp), fontSize = 11.5.sp, lineHeight = 15.sp, color = k.onsv, textAlign = if (s == 0) TextAlign.Start else TextAlign.End) }
                }
            }
        }
        listOf(g.a, g.b).forEachIndexed { s, v -> Track(f, g, s, v, grown) }
        val (lo, hi) = g.scale
        Row(Modifier.fillMaxWidth().padding(top = 9.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val st = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 12.6.sp, color = k.onsv)
            Text(lo, style = st)
            Text(frTypo(g.baseLabel), Modifier.weight(1f), style = st, textAlign = TextAlign.Center)
            Text(hi, style = st)
        }
    }
}

/** Piste en creux, repères gris des autres, barre tracée depuis la référence, en crans ; positions calées sur 2 dp. */
@Composable
private fun Track(f: Fight, g: Gauge, s: Int, v: Double?, grown: Boolean) {
    val k = f.k
    val p by animateFloatAsState(if (grown) 1f else 0f, tween(if (f.still) 0 else 640, easing = steps(8)), label = "jauge")
    val c = f.s.colors[s]
    val cd = k.cd(c)
    val hatch = s == 1 && f.s.hatch
    Canvas(Modifier.padding(top = 6.dp).fillMaxWidth().height(22.dp).semantics { contentDescription = "${f.s.names[s]} : ${if (v == null) "pas de donnée" else g.label(v)}" }) {
        val u = 1.dp.toPx()
        val w = size.width
        val h = size.height
        fun snap(x: Double) = (w * x / (2 * u)).roundToInt() * 2 * u
        drawRect(k.inked(0.07f))
        insetBands(k.inked(0.13f), t = 4 * u)
        insetBands(k.inked(0.2f), 2 * u, 2 * u, 2 * u, 2 * u)
        g.others.forEach { o -> drawRect(k.onsv.copy(alpha = 0.65f), Offset(snap(g.pos(o)) - 2 * u, 8 * u), Size(4 * u, 6 * u)) }
        val z = snap(g.pos(g.base))
        if (v != null && p > 0f) {
            val x = snap(g.pos(v))
            val l = min(z, x)
            val full = max(2 * u, max(z, x) - l)
            val bw = full * p
            val left = if (x >= z) l else l + full - bw
            translate(left, 4 * u) {
                val bs = Size(bw, h - 8 * u)
                drawRect(c, size = bs)
                if (hatch) clipRect(right = bs.width, bottom = bs.height) {
                    var d = -bs.height
                    while (d < bs.width) { drawLine(Color.White.copy(alpha = 0.6f), Offset(d, bs.height), Offset(d + bs.height, 0f), 2 * u); d += 6 * u }
                }
                drawRect(Color.Black.copy(alpha = 0.18f), Offset(0f, bs.height - 4 * u), Size(bs.width, 4 * u))
                insetBands(cd, 2 * u, 2 * u, 2 * u, 2 * u, bs)
                var sx = 2 * u + 4 * u
                while (sx < bs.width - 2 * u) { drawRect(Color.Black.copy(alpha = 0.14f), Offset(sx, 2 * u), Size(min(2 * u, bs.width - 2 * u - sx), bs.height - 4 * u)); sx += 6 * u }
            }
        }
        drawRect(k.ink, Offset(z - 2 * u, -5 * u), Size(4 * u, h + 10 * u))
        drawRect(k.ink, Offset(z - 4 * u, -5 * u), Size(8 * u, 3 * u))
    }
}

// ---------- Fenêtre coulissante ----------

/**
 * Deux panneaux sur une piste qui coulisse derrière la fenêtre : suivi du doigt, calage à 30 %, hauteur du panneau visible.
 * [hint] : à chaque nouvelle valeur non nulle, la piste esquisse un glissement vers le second panneau puis revient.
 */
@Composable
private fun SlideWindow(
    page: Int, onPage: (Int) -> Unit, f: Fight, gap: Pair<Color, Color>, modifier: Modifier = Modifier, frame: Boolean = false,
    hint: Int = 0, onTouch: () -> Unit = {}, panel: @Composable (Int) -> Unit,
) {
    val k = f.k
    val pos = remember { Animatable(page.toFloat()) }
    val back = remember { Animatable(0f) }
    var live by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val nudge = remember { Animatable(0f) }
    val gapPx = with(LocalDensity.current) { 25.dp.toPx() }
    val nudgePx = with(LocalDensity.current) { 36.dp.toPx() }
    var width by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val turn by rememberUpdatedState(onPage)
    LaunchedEffect(page) { if (f.still) pos.snapTo(page.toFloat()) else pos.animateTo(page.toFloat(), tween(300, easing = Glide)) }
    LaunchedEffect(hint) {
        nudge.snapTo(0f)
        if (hint == 0 || f.still) return@LaunchedEffect
        nudge.animateTo(1f, tween(900, easing = steps(4)))
        nudge.snapTo(0f)
    }
    Layout(
        content = {
            (0..1).forEach { i ->
                // Panneau caché : hors de portée du lecteur d'écran.
                Box(Modifier.fillMaxWidth().let { if (i != page) it.clearAndSetSemantics { } else it }) { panel(i) }
                if (i == 0) Box(Modifier.fillMaxHeight().background(Brush.horizontalGradient(listOf(gap.first, gap.second))))
            }
        },
        modifier = modifier.fillMaxWidth().clipToBounds()
            .let { if (frame) it.drawBehind { insetBands(k.line, 2.dp.toPx(), 2.dp.toPx(), 2.dp.toPx(), 2.dp.toPx()) } else it }
            .pointerInput(page) {
                detectHorizontalDragGestures(
                    onDragStart = { held = true; live = 0f; onTouch() },
                    onDragEnd = {
                        val np = windowRelease(page, live, width.toFloat())
                        val from = live
                        scope.launch {
                            back.snapTo(from)
                            held = false
                            live = 0f
                            if (np != page) { turn(np); f.say(if (np == 0) "Premier panneau" else "Second panneau") }
                            back.animateTo(0f, tween(if (f.still) 0 else 300, easing = Glide))
                        }
                    },
                    onDragCancel = { held = false; live = 0f },
                ) { ch, dx ->
                    ch.consume()
                    val raw = live + dx
                    live = if ((page == 0 && raw > 0) || (page == 1 && raw < 0)) live + dx / 3 else raw
                }
            },
    ) { ms, c ->
        val w = c.maxWidth
        width = w
        val fixed = Constraints(minWidth = w, maxWidth = w)
        val p0 = ms[0].measure(fixed)
        val p1 = ms[2].measure(fixed)
        val g = gapPx.roundToInt()
        val gp = ms[1].measure(Constraints.fixed(g, max(p0.height, p1.height)))
        val step = w + g
        val t = nudge.value
        val hintOff = -nudgePx * when { t < 0.4f -> t / 0.4f; t < 0.6f -> 1f; else -> (1f - t) / 0.4f }
        val off = -pos.value * step + (if (held) live else back.value) + hintOff
        val fr = (-off / step).coerceIn(0f, 1f)
        val h = (p0.height + (p1.height - p0.height) * fr).roundToInt()
        layout(w, h) {
            p0.place(off.roundToInt(), 0)
            gp.place((off + w).roundToInt(), 0)
            p1.place((off + w + g).roundToInt(), 0)
        }
    }
}

/** Points du panneau : deux cibles de 48 dp, le panneau courant élargi. */
@Composable
private fun WDots(cur: Int, labels: List<String>, k: DuelInk, onGo: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, l ->
            Box(Modifier.size(32.dp, 24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(48.dp).clickable(role = Role.Button) { onGo(i) }.semantics { contentDescription = l; selected = i == cur }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (i == cur) 16.dp else 8.dp, 8.dp).background(if (i == cur) k.ink else k.line))
                }
            }
        }
    }
}

@Composable
private fun Kick(t: String, k: DuelInk, modifier: Modifier = Modifier) =
    Text(t.uppercase(), modifier, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.09.em, color = k.onsv)

private val VERDICTS = mapOf("financable" to "Finançable", "conteste" to "Financement contesté", "irrealiste" to "Irréaliste", "deja-tente" to "Déjà tenté")

@Composable
private fun WalletBody(f: Fight, i: Int, r: WalletRound, grown: Boolean) {
    val k = f.k
    val key = "w$i"
    val page = f.wins[key] ?: 0
    val go = { p: Int -> f.wins[key] = p }
    GaugeView(f, r.gauge, grown, last = false)
    H4("Ce que chaque programme sacrifie", k, Modifier.padding(top = 14.dp, bottom = 6.dp))
    SlideWindow(page, go, f, k.surf to k.inked(0.1f), frame = true) { p ->
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Kick(if (p == 0) "Contexte" else "Qui perd, combien, pourquoi", k, Modifier.weight(1f))
                WDots(p, listOf("Contexte", "Détail"), k, go)
            }
            TwoSep(f) { s ->
                val l = if (s == 0) r.la else r.lb
                if (p == 0) {
                    if (l == null) Na("Balance non documentée.", k) else Context(f, l, s, if (s == 0) r.whoA else r.whoB)
                } else {
                    val ps = l?.postes.orEmpty()
                    if (ps.isEmpty()) Na(if (l != null) "Aucun poste détaillé." else "Balance non documentée.", k)
                    ps.forEach { x ->
                        Column(Modifier.fillMaxWidth().dashedBottom(k.line).padding(vertical = 7.dp)) {
                            val who = x.qui?.takeIf { it.isNotBlank() }
                            Text(who?.replaceFirstChar { it.uppercase() }?.let(::frTypo) ?: "Perdants non nommés", fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (who != null) FontWeight.Bold else FontWeight.Medium, fontStyle = if (who != null) FontStyle.Normal else FontStyle.Italic, color = if (who != null) k.ink else k.onsv)
                            val amt = x.montant?.takeIf { it.isNotBlank() }
                            if (amt != null) Text(amt, fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, lineHeight = 18.sp, color = k.ink)
                            else Text("non chiffré", fontSize = 12.sp, lineHeight = 18.sp, fontStyle = FontStyle.Italic, color = k.onsv)
                            Text("↳ " + frTypo(x.quoi), fontSize = 12.sp, lineHeight = 16.sp, color = k.onsv)
                        }
                    }
                }
            }
            if (p == 0) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp).clickable(role = Role.Button, onClickLabel = "Voir le détail : qui perd, combien, pourquoi") { go(1) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("QUI PERD, COMBIEN", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.05.em, color = k.onsv)
                PxIcon("fwd", k.onsv, 16.dp)
            }
        }
    }
}

/** Contexte d'une balance : la contrepartie, le verdict, et la balance complète en feuille. */
@Composable
private fun Context(f: Fight, l: Lecture, s: Int, who: String?) {
    val k = f.k
    val vd = VERDICTS[l.verdict]
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Balance complète : ${f.s.names[s]}") {
            f.nav.sheet(PolSheetState.Balance(l, f.s.names[s], who))
        }.padding(vertical = 2.dp),
    ) {
        Text(frTypo(l.contrepartie_courte.ifBlank { l.contrepartie }), fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = k.ink)
        Text(
            (vd ?: "Non documenté").uppercase(),
            Modifier.padding(top = 6.dp).let { if (vd != null) it.background(mix(k.surf, k.att, 0.18f)) else it.drawBehind { insetBands(k.line, 1.dp.toPx(), 1.dp.toPx(), 1.dp.toPx(), 1.dp.toPx()) } }
                .padding(horizontal = 5.dp, vertical = 4.dp),
            fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.05.em, color = if (vd != null) k.attTx else k.onsv,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Balance", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = k.onsv)
            PxIcon("chev", k.onsv, 10.dp)
        }
    }
}

// ---------- Étiquettes ----------

/** Étiquette pixel de position ou de vote : texte, encre, fond ; [bg] null : contour seul. */
@Composable
private fun Tag(t: String, fg: Color, bg: Color?, k: DuelInk, modifier: Modifier = Modifier) = Text(
    t.uppercase(),
    modifier.let { if (bg != null) it.clip(Cut2).background(bg) else it.drawBehind { insetBands(k.line, 1.dp.toPx(), 1.dp.toPx(), 1.dp.toPx(), 1.dp.toPx()) } }
        .padding(horizontal = 6.dp, vertical = 5.dp),
    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, lineHeight = 10.5.sp, letterSpacing = 0.05.em, color = fg, maxLines = 1,
)

private val MARKS = mapOf("keep" to "Poursuit", "flip" to "Inverse", "mix" to "Modifie")

@Composable
private fun Mark(code: String?, k: DuelInk) {
    val label = MARKS[code]
    when (code) {
        "keep" -> Tag(label!!, k.pour, k.pourBg, k)
        "flip" -> Tag(label!!, k.contre, k.contreBg, k)
        "mix" -> Tag(label!!, k.attTx, k.partBg, k)
        else -> Tag("Non trouvé", k.onsv, null, k)
    }
}

@Composable
private fun Vote(code: String, k: DuelInk, modifier: Modifier = Modifier) {
    val w = VOTE_WORDS[code] ?: VOTE_WORDS.getValue("N")
    when (code) {
        "P" -> Tag(w, k.pour, k.pourBg, k, modifier)
        "C" -> Tag(w, k.contre, k.contreBg, k, modifier)
        "A" -> Tag(w, k.attTx, k.partBg, k, modifier)
        "S" -> Tag(w, k.ink, k.pourBg, k, modifier)
        else -> Tag(w, k.onsv, null, k, modifier)
    }
}

// ---------- Corps des rounds ----------

@Composable
private fun BilanBody(f: Fight, r: BilanRound) {
    val k = f.k
    r.lines.forEach { l ->
        Column(Modifier.padding(top = 12.dp)) {
            H4(l.o, k)
            if (l.s.isNotBlank()) Text(frTypo(l.s), Modifier.padding(top = 1.dp, bottom = 6.dp), fontSize = 12.sp, color = k.onsv)
            TwoSep(f) { s ->
                val x = if (s == 0) l.a else l.b
                Mark(x?.firstOrNull(), k)
                if (x != null) Text(frTypo(x.getOrNull(1).orEmpty()), Modifier.padding(top = 5.dp), fontSize = 12.5.sp, lineHeight = 17.sp, color = k.ink)
                else Na("Aucune position dans une source fiable.", k)
            }
        }
    }
}

@Composable
private fun GapBody(f: Fight, r: GapRound) {
    val k = f.k
    TwoSep(f, Modifier.padding(top = 6.dp)) { s ->
        val gs = if (s == 0) r.a else r.b
        if (gs.isEmpty()) Na("Aucun chiffrage extérieur relevé.", k)
        val c = f.s.colors[s]
        val cd = k.cd(c)
        val hatch = s == 1 && f.s.hatch
        gs.forEach { g ->
            val (la, lb) = gapLabels(g)
            val mx = max(g.claimed ?: 0.0, g.credited ?: 0.0).takeIf { it > 0 } ?: 1.0
            fun fmt(v: Double?) = v?.let { fmtNum(it, if (it % 1.0 != 0.0) 1 else 0) + " " + g.unit } ?: NO_DATA
            Text(frTypo(g.what), Modifier.padding(top = 10.dp, bottom = 4.dp), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = k.ink)
            listOf(Triple(la, g.claimed, true), Triple(lb, g.credited, false)).forEach { (label, v, hollow) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(label, Modifier.weight(1f), fontSize = 11.5.sp, color = k.onsv)
                    Text(fmt(v), fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = k.ink, softWrap = false)
                }
                Canvas(Modifier.padding(top = 3.dp).fillMaxWidth().height(14.dp).clearAndSetSemantics { contentDescription = "$label : ${fmt(v)}" }) {
                    val u = 1.dp.toPx()
                    val h = size.height
                    drawRect(k.inked(0.07f))
                    val w = size.width * ((v ?: 0.0) / mx).toFloat()
                    if (w <= 0f) return@Canvas
                    if (hollow) {
                        insetBands(cd, u, u, u, u, Size(w, h))
                        translate(u, u) { insetBands(c, 3 * u, 3 * u, 3 * u, 3 * u, Size(w - 2 * u, h - 2 * u)) }
                        translate(4 * u, 4 * u) { insetBands(cd, u, u, u, u, Size(w - 8 * u, h - 8 * u)) }
                    } else {
                        drawRect(c, size = Size(w, h))
                        if (hatch) clipRect(right = w) {
                            var d = -h
                            while (d < w) { drawLine(Color.White.copy(alpha = 0.6f), Offset(d, h), Offset(d + h, 0f), 2 * u); d += 6 * u }
                        }
                        insetBands(cd, 2 * u, 2 * u, 2 * u, 2 * u, Size(w, h))
                    }
                }
            }
        }
    }
}

@Composable
private fun VotesBody(f: Fight, r: VotesRound) {
    val k = f.k
    val context = LocalContext.current
    Row(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${r.clash} / ${r.both}", fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 30.sp, letterSpacing = (-0.04).em, softWrap = false, color = k.ink)
        Text("oppositions nettes (pour contre contre) sur les scrutins où les deux groupes ont voté", fontSize = 13.5.sp, lineHeight = 18.sp, color = k.ink)
    }
    r.lines.forEach { l ->
        Column(Modifier.fillMaxWidth().dashedBottom(k.line).padding(vertical = 8.dp)) {
            val url = safeHttps(l.u)
            Text(
                frTypo(l.t), Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 0.dp)
                    .let { if (url != null) it.clickable(role = Role.Button, onClickLabel = "Ouvrir le scrutin") { openUrl(context, url) } else it }
                    .wrapCenter(),
                fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = k.ink,
            )
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { Vote(l.a, k) }
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    PxIcon(l.case.icon, if (l.case == VCase.Clash) k.ink else k.onsv, 16.dp, Modifier.semantics { contentDescription = l.case.label; role = Role.Image })
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Vote(l.b, k) }
            }
        }
    }
}

/** Texte centré verticalement dans sa cible de 48 dp. */
private fun Modifier.wrapCenter() = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0))
    val h = max(p.height, c.minHeight)
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnBody(f: Fight, i: Int, r: TurnRound) {
    val k = f.k
    var open by remember { mutableStateOf<String?>(null) }
    // Indice : sans geste au bout de 5 s, la piste du sujet ouvert esquisse un glissement vers APRÈS.
    var hint by remember { mutableIntStateOf(0) }
    var hinted by remember { mutableStateOf<String?>(null) }
    val touched = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(open) {
        hinted = null
        val id = open ?: return@LaunchedEffect
        touched.remove(id)
        delay(5000)
        if (open == id && touched[id] != true && (f.wins[id] ?: 0) == 0) { hinted = id; hint++ }
    }
    TwoSep(f, Modifier.padding(top = 6.dp)) { s ->
        val ts = if (s == 0) r.a else r.b
        if (ts.isEmpty()) { Na("Aucune position suivie.", k); return@TwoSep }
        FlowRow(Modifier.padding(top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TURN_KINDS.forEach { (code, label) ->
                val n = ts.count { it.k == code }
                if (n > 0) Text(
                    "$n ${label.lowercase()}${if (n > 1) "s" else ""}", Modifier.clip(Cut2).background(k.inked(0.07f)).padding(horizontal = 6.dp, vertical = 5.dp),
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, lineHeight = 10.5.sp, letterSpacing = 0.04.em, color = k.ink,
                )
            }
        }
        ts.forEachIndexed { j, t ->
            val id = "tw${i}_${s}_$j"
            val on = open == id
            val rot by animateFloatAsState(if (on) 90f else 0f, tween(if (f.still) 0 else 200, easing = steps(2)), label = "chevron")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).dashedBottom(k.line)
                    .clickable(role = Role.Button) { open = if (on) null else id; if (!on) f.wins[id] = 0 }
                    .semantics { stateDescription = if (on) "ouvert" else "fermé" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(frTypo(t.s), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = if (on) k.pri else k.ink)
                PxIcon("chev", if (on) k.pri else k.onsv, 12.dp, Modifier.graphicsLayer { rotationZ = rot })
            }
            Unroll(on, f.still, 260, 5) {
                Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                    val page = f.wins[id] ?: 0
                    SlideWindow(page, { f.wins[id] = it }, f, k.inked(0.05f) to mix(k.surf, k.pc, 0.6f), hint = if (hinted == id) hint else 0, onTouch = { touched[id] = true }) { p ->
                        Column(Modifier.fillMaxWidth().background(if (p == 0) k.inked(0.05f) else mix(k.surf, k.pc, 0.6f)).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
                            FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center) {
                                Before(p == 1, k)
                                WDots(p, listOf("Avant", "Après"), k) { f.wins[id] = it; touched[id] = true }
                            }
                            Text(frTypo(if (p == 0) t.a else t.b), Modifier.padding(top = 2.dp), fontSize = 12.5.sp, lineHeight = 17.sp, color = k.ink)
                        }
                    }
                }
            }
        }
    }
}

/** Badge AVANT (en creux) ou APRÈS (en relief, couleur principale). */
@Composable
private fun Before(after: Boolean, k: DuelInk) {
    val fg = if (after) k.onPri else k.onsv
    Row(
        Modifier.clip(Cut2).background(if (after) k.pri else k.inked(0.12f)).drawBehind {
            val u = 1.dp.toPx()
            if (after) insetBands(mix(k.pri, Color.Black, 0.25f), b = 3 * u) else insetBands(k.inked(0.25f), 2 * u, 2 * u, 2 * u, 2 * u)
        }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!after) PxIcon("back", fg, 12.dp)
        Text(if (after) "APRÈS" else "AVANT", fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, lineHeight = 11.sp, letterSpacing = 0.08.em, color = fg)
        if (after) PxIcon("fwd", fg, 12.dp)
    }
}

@Composable
private fun SectionView(f: Fight, title: String, a: List<Line>, b: List<Line>) {
    val k = f.k
    Column(Modifier.padding(top = 12.dp)) {
        H4(title, k, Modifier.padding(bottom = 4.dp))
        TwoSep(f) { s -> (if (s == 0) a else b).forEachIndexed { j, l -> LineView(f, l, j == 0) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineView(f: Fight, l: Line, first: Boolean) {
    val k = f.k
    val dt = @Composable { t: String ->
        Text(t.uppercase(), Modifier.padding(top = if (first) 4.dp else 7.dp), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.06.em, color = k.onsv)
    }
    val small = @Composable { t: String -> Text(frTypo(t), fontSize = 11.5.sp, lineHeight = 15.sp, color = k.onsv) }
    when (l) {
        is Kv -> {
            dt(l.k)
            Column(Modifier.padding(top = 1.dp)) {
                l.vals.forEach { v ->
                    when {
                        v.v == null -> Text(v.nr, fontSize = 12.5.sp, lineHeight = 17.sp, fontStyle = FontStyle.Italic, color = k.onsv)
                        v.v.isNotEmpty() -> Text(frTypo(v.v), fontSize = 12.5.sp, lineHeight = 17.sp, color = k.ink)
                    }
                    v.small?.let { small(it) }
                }
            }
        }
        is CampLine -> {
            dt(l.k)
            val x = l.id?.let { f.data.byId(it) }
            if (x == null) Text("aucun camp", Modifier.padding(top = 1.dp), fontSize = 12.5.sp, color = k.ink)
            else Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val c = f.data.colorOf(x.id)
                Box(Modifier.size(12.dp).background(c).drawBehind { insetBands(k.cd(c), 2.dp.toPx(), 2.dp.toPx(), 2.dp.toPx(), 2.dp.toPx()) })
                Text(frTypo(x.nom), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = k.ink)
            }
        }
        is Tiles -> FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            l.ids.forEach { id ->
                val x = f.data.byId(id) ?: return@forEach
                val c = f.data.colorOf(id)
                Row(
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Fiche : ${x.nom}") { f.nav.sheet(PolSheetState.Identity(id)) }
                        .semantics { contentDescription = x.nom }
                        .background(k.inked(0.06f)).drawBehind {
                            val u = 1.dp.toPx()
                            insetBands(k.inked(0.14f), b = 4 * u)
                            insetBands(k.inked(0.22f), 2 * u, 2 * u, 2 * u, 2 * u)
                        }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(10.dp).background(c).drawBehind { insetBands(k.cd(c), 2.dp.toPx(), 2.dp.toPx(), 2.dp.toPx(), 2.dp.toPx()) })
                    Text(x.parti?.takeIf { it.isNotBlank() } ?: x.nom, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = k.ink)
                }
            }
        }
        is Big -> Text(l.text, Modifier.padding(top = 4.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = k.ink)
        is Note -> Na(l.text, k)
    }
}

// ---------- Fin ----------

@Composable
private fun FinCard(f: Fight, camps: Boolean, onRematch: () -> Unit, onNew: () -> Unit) {
    val k = f.k
    Box(Modifier.fillMaxWidth().cardFrame(k).semantics { contentDescription = "Fin du duel" }) {
        Column(Modifier.clip(Cut).background(k.surf)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).background(k.night).scan(k.scan).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PxText("FIN", k.ink, 2.dp, bold = true)
                PxIcon("cup", k.attTx, 18.dp)
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                val p = @Composable { t: String -> Text(frTypo(t), Modifier.padding(bottom = 10.dp), fontSize = 14.sp, lineHeight = 20.sp, color = k.ink) }
                p("Aucun vainqueur ici. Ce duel sert à connaître les candidats et à comparer leurs promesses, chiffres sourcés à l'appui, sur un pied d'égalité. Votre vote n'appartient qu'à vous.")
                p("Restez critique envers vos propres idées et allez vers ceux qui ne les partagent pas : écouter, puis discuter, c'est ainsi qu'on grandit ensemble.")
                if (camps) p("Un camp n'a pas de programme commun : seuls ses partis et ses candidats en ont.")
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PxButton("Revanche : retour au round 1", onRematch, small = true, alt = true) { c -> Text("Revanche", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c) }
                    PxButton("Nouveau duel", onNew, small = true) { c -> Text("Nouveau duel", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c) }
                }
            }
        }
    }
}
