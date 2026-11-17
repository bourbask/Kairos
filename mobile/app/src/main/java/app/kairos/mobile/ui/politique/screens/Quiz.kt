package app.kairos.mobile.ui.politique.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.jeux.kit.CouleursJeux
import app.kairos.mobile.ui.politique.jeux.kit.PixelGrid
import app.kairos.mobile.ui.politique.jeux.kit.PixelShape
import app.kairos.mobile.ui.politique.jeux.kit.cell
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.LocalPolBottom
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.TitleInfo
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.verdictLabel
import app.kairos.mobile.ui.politique.kit.verdictLook
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

private val Pad: PaddingValues @Composable get() = ScreenPad

private class GuessLook(val arrow: String, val fg: Color, val bg: Color)

@Composable
private fun look(g: Guess): GuessLook {
    val (arrow, v) = when (g) {
        Guess.Faux -> "←" to "faux"
        Guess.PlutotFaux -> "↖" to "plutot-faux"
        Guess.PlutotVrai -> "↗" to "plutot-vrai"
        Guess.Vrai -> "→" to "vrai"
    }
    val l = verdictLook(v)
    return GuessLook(arrow, l.fg, l.bg)
}

/** Les quatre réponses, de gauche à droite, comme sur l'écran de départ. */
private val ORDER = listOf(Guess.Faux, Guess.PlutotFaux, Guess.PlutotVrai, Guess.Vrai)

@Composable
fun QuizScreen(data: PolData, nav: PolNavigator) {
    val claims = remember(data) { claimsOf(data) }
    val camps = remember(data, claims) { campsOf(data, claims) }
    var theme by rememberSaveable { mutableStateOf<String?>(null) }
    // Partie en cours : le retour ramène au choix des paquets avant de quitter le jeu.
    BackHandler(enabled = theme != null) { theme = null }
    val t = theme
    if (t == null) QuizStart(claims, camps) { theme = it }
    else QuizGame(data, nav, claims, camps, t) { theme = null }
}

// ---------- Départ ----------

@Composable
private fun QuizStart(claims: List<Claim>, camps: List<String>, onPlay: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val themes = remember(claims) { quizThemes(claims) }
    val out = remember(claims) { outOfPlay(claims) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column {
                TitleInfo(
                    "Devinez le verdict des données",
                    "Des phrases entendues à table, de tous bords. Pour chacune, devinez ce que disent les données publiques, puis découvrez les faits et la source. " +
                        "« Plutôt faux » regroupe les phrases trompeuses, plutôt fausses ou non prouvées. " +
                        "Le score compte vos bonnes réponses, jamais celles d'un camp. Chaque paquet alterne les camps.",
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ORDER.forEach { g ->
                    val l = look(g)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(l.bg).padding(vertical = 10.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(l.arrow, color = l.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(g.label, color = l.fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        item { Text("« Trompeur » et « Non prouvé » se jouent en « Plutôt faux ».", fontSize = 13.sp, color = scheme.onSurfaceVariant) }
        if (out > 0) item { Text("${frCount(out, "phrase non documentée", "phrases non documentées")} hors jeu.", fontSize = 13.sp, color = scheme.onSurfaceVariant) }
        if (themes.isEmpty()) {
            item { EmptyState("Aucune phrase jouable", "Il faut des phrases vérifiées avec un verdict pour jouer. Elles apparaîtront ici dès leur publication.") }
            return@LazyColumn
        }
        item { Text("Choisir un paquet", Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(listOf("") + themes, key = { it }) { t ->
            val deck = quizDeck(claims, camps, t)
            val nCamps = deck.map { claims[it].camp }.filter { it.isNotBlank() }.distinct().size
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = scheme.surface, tonalElevation = 1.dp) {
                Row(Modifier.clickable(role = Role.Button) { onPlay(t) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.ifEmpty { "Toutes les phrases" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${frCount(deck.size, "carte", "cartes")} · ${frCount(nCamps, "camp", "camps")}", fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
                    }
                    Text("›", fontSize = 22.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.items(list: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) =
    items(list.size, key = { key(list[it]) }) { content(list[it]) }

// ---------- Jeu ----------

/** Hauteur de la barre de score, puis de la rangée de voyants (les « plutôt » montent de [LED_RAISE]). */
private val BAR_H = 32.dp
private val LED_H = 60.dp
private val LED_RAISE = 14.dp

/** Direction d'envoi d'une carte répondue. */
private val VEC = mapOf(Guess.Faux to (-1f to .08f), Guess.PlutotFaux to (-.72f to -.72f), Guess.PlutotVrai to (.72f to -.72f), Guess.Vrai to (1f to .08f))

/** Carte qui s'envole hors de la pile : rang dans le paquet, face, direction, pose au départ (dp, degrés). */
private class Fly(val k: Int, val back: Boolean, val turned: Boolean, val vx: Float, val vy: Float, val x: Float, val y: Float, val rot: Float, val id: Int)

@Composable
private fun QuizGame(data: PolData, nav: PolNavigator, claims: List<Claim>, camps: List<String>, theme: String, onPacks: () -> Unit) {
    val deck = remember(claims, camps, theme) { quizDeck(claims, camps, theme) }
    if (deck.isEmpty()) {
        EmptyState("Paquet vide", "Aucune phrase jouable dans ce paquet.", Modifier.padding(16.dp), action = "Autre paquet" to onPacks)
        return
    }
    var run by rememberSaveable { mutableIntStateOf(0) }
    var i by rememberSaveable(run) { mutableIntStateOf(0) }
    var ans by rememberSaveable(run) { mutableStateOf<List<Guess>>(emptyList()) }
    val n = deck.size
    val score = ans.withIndex().count { (k, g) -> g == expectedAnswer(claims[deck[k]].v) }
    if (i >= n) {
        QuizEnd(data, nav, claims, deck, ans, theme, score, onAgain = { run++ }, onPacks = onPacks)
        return
    }
    val reduced = reducedMotion()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val claim = claims[deck[i]]
    val revealed = ans.size > i
    val expected = expectedAnswer(claim.v)

    // État de la carte en main, remis à zéro à chaque carte.
    var dx by remember(i) { mutableFloatStateOf(0f) }
    var dy by remember(i) { mutableFloatStateOf(0f) }
    var dragging by remember(i) { mutableStateOf(false) }
    var aim by remember(i) { mutableStateOf<Guess?>(null) }
    var level by remember(i) { mutableIntStateOf(0) }
    var shownBack by remember(i) { mutableStateOf(revealed) }
    var flipX by remember(i) { mutableFloatStateOf(1f) }
    var rise by remember(i) { mutableFloatStateOf(if (reduced || i == 0) 1f else 0f) }
    var turned by remember(i) { mutableStateOf(false) }
    var turnT by remember(i) { mutableStateOf<Double?>(null) }
    var peekHeld by remember(i) { mutableStateOf(false) }
    var peekOpen by remember(i) { mutableStateOf(false) }
    var say by remember { mutableStateOf("") }
    var fly by remember { mutableStateOf<Fly?>(null) }
    var flyId by remember { mutableIntStateOf(0) }

    LaunchedEffect(i) {
        if (rise < 1f) { delay(80); rise = .5f; delay(80); rise = 1f }
    }

    fun springBack() {
        val x0 = dx
        val y0 = dy
        if (reduced) { dx = 0f; dy = 0f; return }
        scope.launch { animate(1f, 0f, animationSpec = tween(250, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))) { p, _ -> dx = x0 * p; dy = y0 * p } }
    }

    fun answer(g: Guess) {
        if (revealed || ans.size != i) return
        ans = ans + g
        aim = g; level = 3
        val (vx, vy) = VEC.getValue(g)
        if (!reduced) fly = Fly(i, back = false, turned = false, vx, vy, dx, dy, Math.round(dx / 18f).toFloat(), ++flyId)
        dx = 0f; dy = 0f
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val fb = quizFeedback(claim.v, g)
        scope.launch {
            if (!reduced) delay(260)
            shownBack = true
            say = "${g.label}. Verdict : ${verdictLabel(claim.v)}. ${fb.text}"
            if (!reduced) { for (k in 0..3) { flipX = k / 4f; delay(60) }; flipX = 1f }
        }
    }

    fun next(dir: Float, ddy: Float) {
        if (!shownBack || turnT != null) return
        if (!reduced) fly = Fly(i, back = true, turned = turned, dir, if (ddy > 60) .4f else if (ddy < -60) -.4f else 0f, dx, dy * .5f, Math.round(dx / 18f).toFloat(), ++flyId)
        say = if (i + 1 < n) "Carte ${i + 2}." else "Partie terminée."
        i++
    }

    fun turn() {
        if (turnT != null) return
        if (reduced) { turned = !turned; say = if (turned) "Faits et sources" else "Réponse"; return }
        scope.launch {
            for ((t, ms) in TURN_KEYS) { turnT = t; delay(ms.toLong()) }
            turned = !turned; turnT = null
            say = if (turned) "Faits et sources" else "Réponse"
        }
    }

    // Voyants : le visé pendant le glissé ; au verso, la bonne réponse, qui clignote trois fois.
    val ledGuess = if (shownBack) expected else aim
    val ledLevel = if (shownBack) 3 else level
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(ledGuess, ledLevel == 3, shownBack) {
        blinkOn = true
        if (reduced || ledLevel != 3 || ledGuess == null || (revealed && !shownBack)) return@LaunchedEffect
        var k = 0
        while (!shownBack || k < 3) { delay(250); blinkOn = false; delay(250); blinkOn = true; k++ }
    }

    val ink = tractInks()
    BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = LocalPolBottom.current)) {
        val density = LocalDensity.current
        val s = cellPx(3.dp)
        val cd = with(density) { s.toDp() }
        val wC = constraints.maxWidth / s
        val hC = constraints.maxHeight / s
        // Carte au plus 318 × 441 dp ; réduite pour garder la place du score et des voyants au-dessus d'elle.
        val reserve = ((BAR_H + LED_H + LED_RAISE + 12.dp) / cd).toInt() + 1
        val cw = minOf(TRACT_W, wC - 8, ((hC - 2 * reserve) * TRACT_W / TRACT_H.toFloat()).toInt()).coerceAtLeast(40)
        val ch = (cw * TRACT_H / TRACT_W.toFloat()).roundToInt()
        val (x0, y0) = cardOrigin(wC, hC, cw, ch)
        val left = n - i - 1
        val cells = remember(wC, hC, i, left, cw, ch) { rearCells(wC, hC, i, left, cw, ch) }
        val rearInk = listOf(ink.shade, ink.flank, ink.edge, ink.paper, ink.alt, ink.pri, ink.txt, ink.dot, ink.ear)

        // Pile du dessous.
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            for (y in 0 until hC) {
                var x = 0
                while (x < wC) {
                    val k = cells[y * wC + x]
                    if (k < 0) { x++; continue }
                    var e = x + 1
                    while (e < wC && cells[y * wC + e] == k) e++
                    cell(x, y, rearInk[k], s, w = e - x)
                    x = e
                }
            }
        }

        // Voyants, centrés dans l'écart entre le score et la pile.
        val gap = cd * y0 - BAR_H
        val ledTop = BAR_H + (gap - (LED_H + LED_RAISE)) / 2 + LED_RAISE
        Row(
            Modifier.fillMaxWidth().offset(y = ledTop).padding(horizontal = 12.dp).semantics { contentDescription = "Répondre" },
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom,
        ) {
            ORDER.forEach { g ->
                val raised = g == Guess.PlutotFaux || g == Guess.PlutotVrai
                LedButton(
                    g, if (g == ledGuess) ledLevel else 0, if (g == ledGuess) blinkOn else true, enabled = !revealed,
                    onClick = { answer(g) }, modifier = Modifier.weight(1f).offset(y = if (raised) -LED_RAISE else 0.dp),
                )
            }
        }

        // Score.
        Row(Modifier.fillMaxWidth().height(BAR_H).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuizBar(quizProgress(ans.size, n, atResult = false), Modifier.weight(1f))
            Text("${i + 1} / $n · ${frCount(score, "juste", "justes")}", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = say })

        val wDp = cd * cw
        val hDp = cd * ch
        val cardMod = Modifier.offset { IntOffset(x0 * s, y0 * s) }.size(wDp, hDp)

        // Carte qui s'envole, par-dessus tout.
        fly?.let { f ->
            key(f.id) {
                val time = remember { Animatable(0f) }
                LaunchedEffect(Unit) { time.animateTo(1f, tween(280, easing = LinearEasing)); if (fly?.id == f.id) fly = null }
                val idx = deck[f.k]
                val c = claims[idx]
                Box(
                    cardMod.clearAndSetSemantics { }.graphicsLayer {
                        val p = CubicBezierEasing(.3f, 0f, .8f, .15f).transform(time.value)
                        val far = 640.dp.toPx()
                        translationX = f.x.dp.toPx() + (f.vx * far - f.x.dp.toPx()) * p
                        translationY = f.y.dp.toPx() + (f.vy * far - f.y.dp.toPx()) * p
                        rotationZ = f.rot + (f.vx * 22f - f.rot) * p
                        alpha = 1f - floor(time.value * 3f) / 3f
                    },
                ) {
                    if (f.back) TractBack(data, nav, c, ans.getOrNull(f.k) ?: Guess.Vrai, cw, ch, s, ink, f.turned, isLast = f.k + 1 >= n, onNext = {})
                    else TractFront(c, cw, ch, s, ink, peek = false, onInfo = {}, dragShadow = true)
                }
            }
        }

        // Carte en main : le cadre fixe reçoit les gestes, la carte bouge dedans.
        if (!revealed || shownBack) {
            val front = !shownBack
            Box(
                cardMod
                    .pointerInput(i, front) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (turnT != null) return@awaitEachGesture
                            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
                            val slop = 8.dp.toPx()
                            var res: PointerInputChange? = null
                            val decided = withTimeoutOrNull(if (front) 420L else Long.MAX_VALUE) {
                                while (true) {
                                    val ch0 = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    tracker.addPointerInputChange(ch0)
                                    if (!ch0.pressed || ch0.isConsumed || (ch0.position - down.position).getDistance() >= slop) { res = ch0; break }
                                }
                                true
                            }
                            if (decided == null) {
                                // Appui long : le contexte tant qu'on maintient.
                                peekHeld = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Jusqu'au lâcher, consommé avant les enfants : le bouton « Contexte » ne reçoit pas de clic en plus.
                                longHeld()
                                peekHeld = false
                                return@awaitEachGesture
                            }
                            val first = res ?: return@awaitEachGesture
                            if (!first.pressed) {
                                if (!front && !first.isConsumed) turn()
                                return@awaitEachGesture
                            }
                            if (first.isConsumed) return@awaitEachGesture
                            first.consume()
                            dragging = true
                            var up = false
                            while (true) {
                                val ch1 = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                tracker.addPointerInputChange(ch1)
                                if (!ch1.pressed) { up = true; break }
                                ch1.consume()
                                dx = (ch1.position.x - down.position.x) / density.density
                                dy = (ch1.position.y - down.position.y) / density.density
                                if (front) { aim = quizSector(dx, dy); level = if (aim != null) quizLevel(dx, dy) else 0 }
                            }
                            dragging = false
                            val v = tracker.calculateVelocity()
                            val vx = v.x / 1000f / density.density
                            val vy = v.y / 1000f / density.density
                            if (front) {
                                val k = if (up) quizRelease(dx, dy, vx, vy) else null
                                if (k != null) { answer(k); return@awaitEachGesture }
                                aim = null; level = 0
                            } else if (up && swipeToNext(dx, dy, vx, vy)) {
                                next(if (dx != 0f) sign(dx) else 1f, dy); return@awaitEachGesture
                            }
                            springBack()
                        }
                    }
                    .semantics {
                        if (front) contentDescription = "Carte ${i + 1} : « ${claim.q} » Vrai ou faux ?"
                        else onClick(if (turned) "Revenir à la réponse" else "Voir les faits et sources") { turn(); true }
                    },
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val r = (quizRnd(i.toDouble()) * 2 - 1).toFloat() * 5.5f
                        translationX = dx.dp.toPx()
                        translationY = (dy * (if (front) 1f else .5f) + 3f * (1f - rise)).dp.toPx()
                        rotationZ = Math.round(dx / 18f) + r * (1f - rise)
                        scaleX = flipX
                        alpha = if (turnT != null) 0f else 1f
                    },
                ) {
                    if (front) TractFront(claim, cw, ch, s, ink, peek = peekHeld || peekOpen, onInfo = { peekOpen = !peekOpen }, dragShadow = dragging)
                    else TractBack(data, nav, claim, ans.getOrNull(i) ?: Guess.Vrai, cw, ch, s, ink, turned, isLast = i + 1 >= n, onNext = { next(1f, 0f) }, dragShadow = dragging)
                }
                turnT?.let { t -> TurnCanvas(t, cw, ch, s, ink, if (turned) TurnFace.Det else TurnFace.Res) }
            }
        }
    }
}

/** Barre de progression, drapeau de résultat accolé à droite : contour pendant le jeu, plein sur la page de résultat. */
@Composable
private fun QuizBar(p: QuizProgress, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f).height(6.dp).clip(CircleShape).background(scheme.outlineVariant)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(p.fraction, 0f..1f) },
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction).background(scheme.onSurface))
        }
        Icon(
            if (p.resultReached) KairosIcons.FlagFilled else KairosIcons.Flag,
            // Pendant le jeu, seule la barre parle ; la page de résultat l'annonce.
            if (p.resultReached) "Résultat atteint" else null,
            Modifier.size(20.dp), tint = if (p.resultReached) scheme.onSurface else scheme.outline,
        )
    }
}


// ---------- Tract ----------

/** Encres du tract, tirées de la palette : papier du conteneur principal, bandeau de la principale, coin et tranche assombris à l'encre. */
private class TractInks(
    val paper: Color, val onPaper: Color, val pri: Color, val onPri: Color, val att: Color, val ink: Color,
    val edge: Color, val flank: Color, val ear: Color, val alt: Color, val txt: Color, val dot: Color, val shade: Color,
    val line: Color, val box: Color, val muted: Color, val dragShadow: Color, val groundShadow: Color,
)

@Composable
private fun tractInks(): TractInks {
    val sc = MaterialTheme.colorScheme
    val dark = sc.background.luminance() < 0.5f
    val pc = sc.primaryContainer
    val onpc = sc.onPrimaryContainer
    val ink = sc.onSurface
    return TractInks(
        paper = pc, onPaper = onpc, pri = sc.primary, onPri = sc.onPrimary, att = sc.tertiary, ink = ink,
        edge = mix(pc, ink, .42f), flank = mix(pc, ink, .22f), ear = mix(pc, ink, .26f), alt = mix(pc, sc.tertiary, if (dark) .18f else .3f),
        txt = mix(pc, onpc, .5f), dot = mix(pc, onpc, .3f), shade = mix(sc.background, ink, if (dark) .35f else .13f),
        line = mix(onpc, pc, .55f), box = mix(pc, onpc, .12f), muted = mix(pc, onpc, .74f),
        dragShadow = if (dark) Color(0x80000000) else ink.copy(alpha = .16f), groundShadow = if (dark) Color.Black else ink,
    )
}

/** Teintes d'une réponse : tampon (encre, fond) et voyant. Fixes : vert juste, rouge faux, ambre entre les deux. */
private class GuessInk(val fg: Color, val bg: Color, val led: Color)

@Composable
private fun guessInk(g: Guess): GuessInk {
    val p = PolColors.current
    return when (g) {
        Guess.Faux -> GuessInk(p.faux, p.fragileBg, Color(0xFFFF3B30))
        Guess.PlutotFaux -> GuessInk(mix(p.partiel, p.fragile, .4f), mix(p.partielBg, p.fragileBg, .4f), Color(0xFFF7742E))
        Guess.PlutotVrai -> GuessInk(mix(p.solide, p.partiel, .5f), mix(p.solideBg, p.partielBg, .5f), Color(0xFFA9C93A))
        Guess.Vrai -> GuessInk(p.solide, p.solideBg, Color(0xFF3DDC5A))
    }
}

private val SPR_INFO = listOf("..#####..", ".#.....#.", "#...#...#", "#.......#", "#...#...#", "#...#...#", "#...#...#", ".#.....#.", "..#####..")
private val SPR_MEGA = listOf("......##", "....####", "##.#####", "########", "########", "##.#####", "..#.####", ".##...##")
private val SPR_PIN = listOf("..####..", ".#######", ".#######", "..####..", "...##...", "...##...", "....#...", "....#...")

private fun arrowGrid(dir: Arrow): List<String> {
    val on = arrowCells(dir).toSet()
    return (0 until 9).map { y -> String(CharArray(9) { x -> if ((x to y) in on) '#' else '.' }) }
}

/** Voyant-flèche : allumé en 3 crans ([level]), halo au dernier ; c'est aussi un bouton qui répond sans glisser. */
@Composable
private fun LedButton(g: Guess, level: Int, lit: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sc = MaterialTheme.colorScheme
    val dark = sc.background.luminance() < 0.5f
    val gi = guessInk(g)
    val cells = remember(g) { ledCells(g.arrow) }
    val s = cellPx(3.dp)
    val off = mix(sc.surface, sc.onSurface, if (dark) .42f else .22f)
    val bezel = mix(sc.surface, sc.onSurface, if (dark) .2f else .5f)
    val lens = when (level) { 0 -> off; 1 -> mix(off, gi.led, .38f); 2 -> mix(off, gi.led, .72f); else -> gi.led }
    val side = with(LocalDensity.current) { (LED_SIZE * s).toDp() }
    Column(
        modifier.heightIn(min = 58.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = g.label; if (!enabled) disabled() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
    ) {
        Canvas(Modifier.size(side).alpha(if (lit) 1f else .35f)) {
            cells.forEach { (x, y, r) ->
                val c = when (r) {
                    LedRole.Ha2 -> if (level == 3) gi.led.copy(alpha = .16f) else null
                    LedRole.Ha1 -> if (level == 3) gi.led.copy(alpha = .4f) else null
                    LedRole.Bz -> bezel
                    LedRole.Ln -> lens
                    LedRole.Lh -> mix(lens, Color.White, .48f)
                    LedRole.Ls -> mix(lens, Color.Black, .3f)
                }
                if (c != null) cell(x, y, c, s)
            }
        }
        Text(
            g.label.uppercase(), Modifier.clearAndSetSemantics { }, color = if (level > 0) gi.fg else sc.onSurfaceVariant,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, lineHeight = 12.sp, letterSpacing = 0.03.em, maxLines = 1, softWrap = false,
        )
    }
}

/**
 * Tract en cellules de [s] px : tranche, papier au coin haut-droit corné en marches (rabat sombre), épaisseur en bas ;
 * au recto, bandeau et pied en pointillés. [dragShadow] : ombre dure portée pendant le glissé.
 */
private fun DrawScope.tractChrome(w: Int, h: Int, s: Int, k: TractInks, front: Boolean, dragShadow: Boolean) {
    fun cardRow(y: Int) = if (y >= 9) w else w - 9 + y
    if (dragShadow) for (y in 0 until h) cell(0, y, k.dragShadow, s, w = cardRow(y), dy = 3)
    for (y in 0 until h) cell(0, y, k.edge, s, w = cardRow(y))
    val wf = w - 2
    val hf = h - 3
    for (fy in 0 until hf) cell(1, fy + 1, k.paper, s, w = if (fy >= 8) wf else wf - 8 + fy)
    cell(1, h - 2, k.flank, s, w = wf)
    if (front) {
        for (fy in 6 until 20) cell(1, fy + 1, k.pri, s, w = if (fy >= 8) wf else wf - 8 + fy)
        for (fy in hf - 10 until hf - 8) for (fx in 7 until wf - 7) if ((fx - 7) % 5 < 3) cell(fx + 1, fy + 1, k.dot, s)
    }
    for (fy in 0 until 9) cell(1 + wf - 9, fy + 1, k.ear, s, w = fy + 1)
}

/** Dos de carte à bord intérieur de 3 dp, en marches : tampon, bouton, fenêtre de contexte. */
private fun DrawScope.innerRim(c: Color, width: Float, bottom: Float = width) {
    drawRect(c, Offset.Zero, Size(size.width, width))
    drawRect(c, Offset(0f, size.height - bottom), Size(size.width, bottom))
    drawRect(c, Offset.Zero, Size(width, size.height))
    drawRect(c, Offset(size.width - width, 0f), Size(width, size.height))
}

@Composable
private fun TractFront(claim: Claim, w: Int, h: Int, s: Int, k: TractInks, peek: Boolean, onInfo: () -> Unit, dragShadow: Boolean) {
    val cd = with(LocalDensity.current) { s.toDp() }
    val scale = (w / TRACT_W.toFloat()).coerceAtMost(1f)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { tractChrome(w, h, s, k, front = true, dragShadow = dragShadow) }
        PixelGrid(SPR_PIN, k.att, Modifier.align(Alignment.TopCenter).offset(y = cd * 2).size(16.dp))
        Row(Modifier.padding(top = cd * 7, start = cd + 14.dp).height(cd * 14).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            PixelGrid(SPR_MEGA, k.onPri, Modifier.size(16.dp))
            Text(
                "Ils l'affirment".uppercase(), Modifier.padding(start = 8.dp), color = k.onPri,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 0.12.em, maxLines = 1,
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = cd + 12.dp, end = cd + 22.dp).size(52.dp)
                .clickable(role = Role.Button, onClick = onInfo)
                .semantics { contentDescription = "Contexte de la phrase"; stateDescription = if (peek) "Affiché" else "Masqué" },
        ) { PixelGrid(SPR_INFO, k.onPri, Modifier.align(Alignment.Center).size(18.dp)) }
        Box(
            Modifier.fillMaxSize().padding(top = cd * 21, bottom = cd * 2 + 38.dp, start = cd + 22.dp, end = cd + 22.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                "« ${frTypo(claim.q)} »", Modifier.clearAndSetSemantics { }, color = k.onPaper, fontWeight = FontWeight.ExtraBold,
                fontSize = (24 * scale).sp, lineHeight = (24 * scale * 1.24f).sp, overflow = TextOverflow.Ellipsis,
            )
        }
        if (peek) {
            Column(
                Modifier.padding(start = cd + 9.dp, end = cd + 9.dp, top = cd + 9.dp).fillMaxWidth()
                    .drawBehind { drawRect(k.dragShadow, Offset(0f, 6.dp.toPx()), size) }
                    .clip(PixelShape()).background(k.paper).drawBehind { innerRim(k.edge, 3.dp.toPx()) }
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)
                    .semantics(mergeDescendants = true) { },
            ) {
                Kicker("Contexte")
                listOf("Côté" to campLabel(claim), "Thème" to claim.theme, "Période" to (claim.date?.takeIf { it.isNotBlank() } ?: "—")).forEach { (t, d) ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Text(t.uppercase(), Modifier.width(72.dp), color = k.muted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.06.em, lineHeight = 18.sp)
                        Text(d, color = k.onPaper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TractBack(
    data: PolData, nav: PolNavigator, claim: Claim, guess: Guess, w: Int, h: Int, s: Int, k: TractInks, turned: Boolean,
    isLast: Boolean, onNext: () -> Unit, dragShadow: Boolean = false,
) {
    val cd = with(LocalDensity.current) { s.toDp() }
    val exp = expectedAnswer(claim.v) ?: Guess.Vrai
    val gi = guessInk(exp)
    val pc = PolColors.current
    val fb = remember(claim, guess) { quizFeedback(claim.v, guess) }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { tractChrome(w, h, s, k, front = false, dragShadow = dragShadow) }
        if (!turned) {
            Column(
                Modifier.fillMaxSize().padding(start = cd + 22.dp, end = cd + 22.dp, top = cd + 24.dp, bottom = cd * 2 + 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Tampon : le libellé du jeu, jamais le verdict brut.
                Row(
                    Modifier.clip(PixelShape()).background(gi.bg).drawBehind { innerRim(gi.fg, 3.dp.toPx()) }
                        .padding(start = 10.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelGrid(arrowGrid(exp.arrow), gi.fg, Modifier.size(18.dp))
                    Text(exp.label.uppercase(), color = gi.fg, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = 0.06.em, maxLines = 1)
                }
                Text(
                    fb.text, Modifier.semantics { contentDescription = "${guess.label}. Verdict : ${exp.label}. ${fb.text}" },
                    color = when (fb.tone) { Tone.Ok -> guessInk(Guess.Vrai).fg; Tone.Near -> pc.partiel; Tone.Ko -> guessInk(Guess.Faux).fg },
                    fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
                Text("« ${frTypo(claim.q)} »", color = k.muted, fontStyle = FontStyle.Italic, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    claim.facts.firstOrNull()?.let(::frTypo).orEmpty(), Modifier.weight(1f), color = k.onPaper,
                    fontSize = 15.sp, lineHeight = 21.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val nSrc = sourcesOf(claim.src).size
                    Text(
                        "Toucher : ${frCount(claim.facts.size - 1, "fait", "faits")} · ${frCount(nSrc, "source", "sources")}".uppercase(), Modifier.weight(1f).clearAndSetSemantics { },
                        color = k.muted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.04.em, lineHeight = 14.sp,
                    )
                    NextButton(if (isLast) "Score ›" else "Suivante ›", onNext)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(start = cd + 22.dp, end = cd + 22.dp, top = cd + 22.dp, bottom = cd * 2 + 14.dp)
                    .verticalScroll(rememberScrollState()).semantics { contentDescription = "Faits et sources" },
            ) {
                val note = verdictNote(claim.v)
                Kicker((if (note != null) "Verdict des vérificateurs : $note · " else "") + "Les faits")
                claim.facts.forEach { Text(frTypo(it), Modifier.padding(top = 8.dp), color = k.onPaper, fontSize = 14.sp, lineHeight = 19.sp) }
                claim.cache?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Nuance. ") }; append(frTypo(it)) },
                        Modifier.padding(top = 8.dp), color = k.onPaper, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                }
                SourceList(sourcesOf(claim.src), data, { nav.sheet(PolSheetState.Evaluator(it)) })
            }
        }
    }
}

/** « Suivante » : bouton pixel au panneau de navigation de la palette, rebord épais en bas qui s'enfonce à l'appui. */
@Composable
private fun NextButton(text: String, onClick: () -> Unit) {
    val cj = CouleursJeux.current
    val rim = mix(cj.suivant, MaterialTheme.colorScheme.onSurface, .35f)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier.heightIn(min = 48.dp).offset(y = if (pressed) 3.dp else 0.dp).clip(PixelShape()).background(cj.suivant)
            .drawBehind { innerRim(rim, 3.dp.toPx(), if (pressed) 3.dp.toPx() else 6.dp.toPx()) }
            .clickable(src, indication = null, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = cj.suivantEncre, fontWeight = FontWeight.ExtraBold) }
}

/** Retournement image par image : la feuille en bande souple, dessinée en pictogramme, déborde du cadre de la carte. */
@Composable
private fun TurnCanvas(t: Double, w: Int, h: Int, s: Int, k: TractInks, from: TurnFace) {
    val frame = remember(t, w, h, from) { turnFrame(t, w * 3, h * 3, from) }
    val pad = TURN_PAD / 3
    Canvas(
        Modifier.layout { m, _ ->
            val p = m.measure(Constraints.fixed(frame.cols * s, frame.rows * s))
            layout(0, 0) { p.place(-pad * s, -pad * s) }
        }.clearAndSetSemantics { },
    ) {
        val sh = frame.shadow
        cell(sh.x, sh.y, k.groundShadow.copy(alpha = frame.shadowAlpha.coerceIn(0f, 1f)), s, w = sh.w)
        frame.runs.forEach { r ->
            val base = when (r.ink) {
                TurnInk.Paper -> k.paper; TurnInk.Edge -> k.edge; TurnInk.Line -> k.line; TurnInk.Acc -> k.pri
                TurnInk.Box -> k.box; TurnInk.Ear -> k.ear; TurnInk.Shadow -> k.groundShadow
            }
            cell(r.x, r.y, Color(base.red * r.light, base.green * r.light, base.blue * r.light), s, w = r.w)
        }
    }
}

// ---------- Fin ----------

@Composable
private fun QuizEnd(data: PolData, nav: PolNavigator, claims: List<Claim>, deck: List<Int>, ans: List<Guess>, theme: String, score: Int, onAgain: () -> Unit, onPacks: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val pc = PolColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = Pad, horizontalAlignment = Alignment.CenterHorizontally) {
        item { QuizBar(quizProgress(ans.size, deck.size, atResult = true), Modifier.fillMaxWidth().padding(top = 4.dp)) }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Kicker(theme.ifEmpty { "Toutes les phrases" })
                Text("$score / ${deck.size}", Modifier.padding(top = 8.dp).semantics { heading() }, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                Text(quizEndMessage(score, deck.size), fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onAgain, Modifier.heightIn(min = 48.dp)) { Text("Rejouer") }
                    OutlinedButton(onPacks, Modifier.heightIn(min = 48.dp)) { Text("Autre paquet") }
                }
            }
        }
        itemsIndexed(deck) { k, idx ->
            val c = claims[idx]
            val ok = ans.getOrNull(k) == expectedAnswer(c.v)
            Row(
                Modifier.fillMaxWidth().padding(top = if (k == 0) 20.dp else 0.dp).clickable(role = Role.Button) { nav.sheet(PolSheetState.Claim(idx)) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            ) {
                Text(if (ok) "✓" else "✗", Modifier.semantics { contentDescription = if (ok) "Bonne réponse" else "Réponse manquée" }, color = if (ok) pc.solide else scheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(frTypo(c.q), Modifier.weight(1f), fontSize = 14.sp)
                VerdictChip(c.v)
            }
        }
    }
}

/** Fin d'un appui long : tout est consommé en passe initiale jusqu'au lâcher. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.longHeld() {
    while (true) {
        val e = awaitPointerEvent(PointerEventPass.Initial)
        e.changes.forEach { it.consume() }
        if (e.changes.none { it.pressed }) break
    }
}
