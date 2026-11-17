package app.kairos.mobile.ui.politique.jeux.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.mascot.MascotGravity
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------- Cadran à aiguille, 4 crans ----------

/** Angle de l'aiguille par cran (0 : repos à gauche). */
val CADRAN_ANGLES = floatArrayOf(-90f, -67.5f, -22.5f, 22.5f, 67.5f)
private val DIGITS = mapOf(
    1 to listOf(".#.", "##.", ".#.", ".#.", "###"), 2 to listOf("##.", "..#", ".#.", "#..", "###"),
    3 to listOf("##.", "..#", ".#.", "..#", "##."), 4 to listOf("#.#", "#.#", "###", "..#", "..#"),
)
private val BackOut = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Cran sous le point ([x], [y] en cellules de la grille 72×44, pivot 36,36) ; sous le pivot : ramené à l'horizontale. */
fun cadranNiveau(x: Float, y: Float): Int {
    val a = atan2(x - 36f, max(36f - y, 4f)) * 180f / PI.toFloat()
    return (floor((a + 90f) / 45f).toInt() + 1).coerceIn(1, 4)
}

/**
 * Cadran pixel à 4 crans : le doigt (ou les touches d'accessibilité) fait tourner l'aiguille, qui se cale sur le cran.
 * [niveau] : 0 (aucun) à 4 ; [ecartes] : crans hachurés (déjà refusés) ; [libelle] : texte du cran pour le lecteur d'écran.
 */
@Composable
fun Cadran(
    niveau: Int,
    onNiveau: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ecartes: Set<Int> = emptySet(),
    description: String = "Cadran",
    libelle: (Int) -> String = { "Cran $it" },
) {
    val sc = MaterialTheme.colorScheme
    val reduit = reducedMotion()
    val angle = remember { Animatable(CADRAN_ANGLES[niveau.coerceIn(0, 4)]) }
    var fast by remember { mutableStateOf(false) }
    LaunchedEffect(niveau) {
        val to = CADRAN_ANGLES[niveau.coerceIn(0, 4)]
        if (reduit) angle.snapTo(to) else angle.animateTo(to, tween(if (fast) 120 else 320, easing = if (fast) CubicBezierEasing(0f, 0f, 0.58f, 1f) else BackOut))
    }
    val set by rememberUpdatedState(onNiveau)
    val att = sc.tertiary
    val g = listOf(sc.outlineVariant, sc.primary, att, mix(att, sc.error, 0.5f), sc.error)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val avail = with(density) { maxWidth.coerceIn(144.dp, 240.dp).toPx() }
        val s = max(1, floor(avail / 72f).toInt())
        val wDp = with(density) { (72 * s).toDp() }
        val hDp = with(density) { (44 * s).toDp() }
        Canvas(
            Modifier.size(wDp, hDp)
                .semantics {
                    contentDescription = description
                    stateDescription = if (niveau in 1..4) libelle(niveau) + if (niveau in ecartes) ", écarté" else "" else "Aucun cran choisi"
                    progressBarRangeInfo = ProgressBarRangeInfo(niveau.toFloat(), 0f..4f, 3)
                    if (!enabled) disabled()
                    else setProgress { v -> set(v.roundToInt().coerceIn(1, 4)); true }
                }
                .pointerInput(enabled, s) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fast = false
                        set(cadranNiveau(down.position.x / s, down.position.y / s))
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (ch.positionChange() != Offset.Zero) { ch.consume(); fast = true; set(cadranNiveau(ch.position.x / s, ch.position.y / s)) }
                        }
                        fast = false
                    }
                },
        ) {
            val ink = sc.onSurface
            val surf = sc.surface
            val line = sc.outlineVariant
            val col = if (niveau in 1..4) g[niveau] else line
            for (j in 0 until 44) for (i in 0 until 72) {
                val dx = i + 0.5f - 36
                val dy = j + 0.5f - 36
                val r = hypot(dx, dy)
                if (r <= 26.6f || r > 34.4f || dy > 0) continue
                val a = atan2(dx, -dy) * 180f / PI.toFloat()
                val u = (a + 90f) % 45f
                if (u < 1.6f || u > 43.4f) continue
                val n = (floor((a + 90f) / 45f).toInt() + 1).coerceAtMost(4)
                val on = n <= niveau
                var c = if (on) col else line
                if (n in ecartes && !on && (i + j) % 2 == 1) c = mix(line, sc.onSurfaceVariant, 0.35f)
                cell(i, j, if (r > 33.3f) mix(c, Color.Black, 0.18f) else if (r < 27.7f) mix(c, Color.White, 0.3f) else c, s)
            }
            for (n in 1..4) {
                val t = CADRAN_ANGLES[n] * PI.toFloat() / 180f
                val cx = (36 + 21.5f * sin(t)).roundToInt() - 1
                val cy = (36 - 21.5f * cos(t)).roundToInt() - 2
                DIGITS.getValue(n).forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') cell(cx + x, cy + y, if (n == niveau) ink else sc.onSurfaceVariant, s) } }
            }
            // Aiguille : ombre décalée d'une cellule, corps encre, arête claire, plus fine vers la pointe.
            val t = angle.value * PI.toFloat() / 180f
            val ux = sin(t)
            val uy = -cos(t)
            val cells = HashMap<Long, Float>()
            var k = 4f
            while (k <= 15.5f) {
                val px = 36 + ux * k
                val py = 36 + uy * k
                val rw = 2.9f - k * 0.085f
                for (j in floor(py - 3).toInt()..(py + 3).toInt()) for (i in floor(px - 3).toInt()..(px + 3).toInt()) {
                    if (hypot(i + 0.5f - px, j + 0.5f - py) <= rw) {
                        val side = (i + 0.5f - 36) * uy - (j + 0.5f - 36) * ux
                        val key = i.toLong() shl 32 or (j.toLong() and 0xffffffffL)
                        if (side < (cells[key] ?: Float.MAX_VALUE)) cells[key] = side
                    }
                }
                k += 0.25f
            }
            fun ij(key: Long) = (key shr 32).toInt() to key.toInt()
            cells.keys.forEach { key -> val (i, j) = ij(key); if ((((i + 1).toLong() shl 32) or ((j + 1).toLong() and 0xffffffffL)) !in cells) cell(i + 1, j + 1, Color.Black.copy(alpha = 0.16f), s) }
            cells.forEach { (key, sd) -> val (i, j) = ij(key); cell(i, j, if (sd < -0.9f) mix(ink, surf, 0.38f) else ink, s) }
            for (j in 28 until 44) for (i in 28 until 44) {
                val dx = i + 0.5f - 36
                val dy = j + 0.5f - 36
                val r = hypot(dx, dy)
                if (r > 6.6f) continue
                var c = if (r > 5.5f) ink else mix(ink, surf, 0.28f)
                if (r <= 5.5f && dx < -0.5f && dy < -0.5f && r > 2.2f && r < 4.4f) c = mix(ink, surf, 0.62f)
                if (r <= 5.5f && dx > 1 && dy > 1 && r > 3.4f) c = mix(ink, Color.Black, 0.1f)
                if (kotlin.math.abs(dx) < 1 && kotlin.math.abs(dy) < 1) c = surf
                cell(i, j, c, s)
            }
        }
    }
}

/** Scène de la mascotte jaugeant la gravité (cran 0 à 4), décorative : le libellé du cran est porté ailleurs. */
@Composable
fun MascotteGravite(niveau: Int, modifier: Modifier = Modifier.size(96.dp)) = MascotGravity(niveau, modifier.clearAndSetSemantics { })

// ---------- Levier à crans ----------

/** Cran le plus proche d'une position [x] sur une glissière de [largeur] à [crans] crans (0 à crans − 1). */
fun levierCran(x: Float, largeur: Float, crans: Int): Int =
    if (crans < 2 || largeur <= 0) 0 else (x / largeur * (crans - 1)).roundToInt().coerceIn(0, crans - 1)

/**
 * Levier (curseur) pixel à [crans] crans : glissière en creux graduée, manette en relief qui suit le doigt et se cale
 * sur le cran le plus proche, retour haptique à chaque cran. [valeur] : 0 à crans − 1, ou -1 (aucun).
 */
@Composable
fun Levier(
    crans: Int,
    valeur: Int,
    onValeur: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = "Levier",
    libelle: (Int) -> String = { "Cran ${it + 1}" },
    onCran: () -> Unit = {},
    couleur: Color = Teintes.valid,
) {
    val sc = MaterialTheme.colorScheme
    val set by rememberUpdatedState(onValeur)
    val tick by rememberUpdatedState(onCran)
    val courante by rememberUpdatedState(valeur)
    val knob = remember { pxButton(8, 12, 2, emptyList(), GlyphMode.Grave) }
    val s = cellPx(3.dp)
    val kc = if (enabled) couleur else Teintes.eteint
    val colors = mapOf(
        Px.Pl to Color.Transparent, Px.Pld to Color.Transparent,
        Px.Fl to mix(kc, Color.Black, 0.42f), Px.Fld to mix(kc, Color.Black, 0.58f),
        Px.T to kc, Px.Tl to mix(kc, Color.White, 0.26f), Px.Td to mix(kc, Color.Black, 0.2f),
        Px.Ic to kc, Px.Icl to kc, Px.Iw to kc, Px.Rf to Color.White.copy(alpha = 0.85f),
    )
    var dragX by remember { mutableStateOf<Float?>(null) }
    val hPx = (knob.h + 2) * s
    val hDp = with(LocalDensity.current) { hPx.toDp() }
    Canvas(
        modifier.fillMaxWidth().height(hDp)
            .semantics {
                contentDescription = description
                stateDescription = if (valeur >= 0) libelle(valeur) else "Aucun cran choisi"
                progressBarRangeInfo = ProgressBarRangeInfo(valeur.coerceAtLeast(0).toFloat(), 0f..(crans - 1).toFloat(), (crans - 2).coerceAtLeast(0))
                if (!enabled) disabled() else setProgress { v -> set(v.roundToInt().coerceIn(0, crans - 1)); true }
            }
            .pointerInput(enabled, crans, s) {
                if (!enabled) return@pointerInput
                val margin = knob.w * s / 2f
                val track = size.width - 2 * margin
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // Valeur relue à chaque geste : elle a pu changer entre deux (accessibilité, nouvelle manche).
                    var last = courante
                    fun at(x: Float) {
                        dragX = (x - margin).coerceIn(0f, track)
                        val c = levierCran(x - margin, track, crans)
                        if (c != last) { last = c; tick(); set(c) }
                    }
                    try {
                        at(down.position.x)
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            at(ch.position.x)
                        }
                    } finally {
                        dragX = null
                    }
                }
            },
    ) {
        val margin = knob.w * s / 2f
        val track = size.width - 2 * margin
        val cy = (knob.h / 2 + 1) * s
        // Glissière en creux : 2 cellules de haut, bord sombre en haut, clair en bas.
        val gl = margin.toInt()
        val gw = track.toInt() / s
        for (x in 0 until gw) {
            val px = gl + x * s
            drawRect(mix(sc.surface, sc.onSurface, 0.28f), Offset(px.toFloat(), (cy - s).toFloat()), androidx.compose.ui.geometry.Size(s.toFloat(), s.toFloat()))
            drawRect(mix(sc.surface, sc.onSurface, 0.12f), Offset(px.toFloat(), cy.toFloat()), androidx.compose.ui.geometry.Size(s.toFloat(), s.toFloat()))
        }
        // Graduations : une cellule sous la glissière par cran.
        for (i in 0 until crans) {
            val x = (margin + if (crans > 1) track * i / (crans - 1) else 0f).roundToInt() / s * s
            drawRect(if (i == valeur) sc.onSurface else sc.onSurfaceVariant, Offset(x.toFloat(), (cy + 3 * s).toFloat()), androidx.compose.ui.geometry.Size(s.toFloat(), (2 * s).toFloat()))
        }
        val pos = dragX ?: if (valeur >= 0 && crans > 1) track * valeur / (crans - 1) else 0f
        val left = ((margin + pos) / s).roundToInt() - knob.w / 2
        knob.base.forEach { cell(it.x + left, it.y - 2, colors.getValue(it.k), s) }
        knob.top.forEach { cell(it.x + left, it.y - 2, colors.getValue(it.k), s, dy = if (dragX != null) 1 else 0) }
    }
}

// ---------- Banderole LED ----------

/**
 * Banderole LED : lettres ambrées sur fond sombre tramé, défilement pas à pas (4 dp toutes les 110 ms) en boucle.
 * Animations réduites : texte fixe sur deux lignes. Le texte est lu tel quel par le lecteur d'écran.
 */
@Composable
fun Banderole(texte: String, modifier: Modifier = Modifier) {
    val sc = MaterialTheme.colorScheme
    val lb = mix(sc.surface, sc.onSurface, 0.62f)
    val amber = mix(Color(0xFFF2B04A), lb, 0.18f)
    val reduit = reducedMotion()
    val density = LocalDensity.current
    var x by remember { mutableIntStateOf(8) }
    var w by remember { mutableIntStateOf(0) }
    var tw by remember { mutableIntStateOf(0) }
    val step = with(density) { 4.dp.roundToPx() }
    LaunchedEffect(reduit, texte) {
        if (reduit) return@LaunchedEffect
        while (true) {
            delay(110)
            x -= step
            if (x < -tw) x = w
        }
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().let { if (reduit) it else it.height(30.dp) }.clip(PixelShape()).background(lb).clipToBounds()
            .semantics { contentDescription = texte },
        contentAlignment = Alignment.CenterStart,
    ) {
        w = constraints.maxWidth
        Text(
            texte.uppercase(),
            Modifier.clearAndSetSemantics { }.let {
                if (reduit) it.padding(horizontal = 10.dp, vertical = 6.dp)
                else it.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(x, 0) }.onSizeChanged { s -> tw = s.width }
            },
            color = amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.08.em,
            maxLines = if (reduit) 2 else 1, softWrap = reduit, overflow = if (reduit) TextOverflow.Ellipsis else TextOverflow.Visible,
        )
        // Trame de points : lignes de 1 px toutes les 3 dp.
        Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
            val p = 3.dp.toPx()
            var i = 0f
            while (i < size.width) { drawRect(lb.copy(alpha = 0.55f), Offset(i, 0f), androidx.compose.ui.geometry.Size(1f, size.height)); i += p }
            var j = 0f
            while (j < size.height) { drawRect(lb.copy(alpha = 0.55f), Offset(0f, j), androidx.compose.ui.geometry.Size(size.width, 1f)); j += p }
        }
    }
}

