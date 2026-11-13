package app.kairos.mobile.ui.politique.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import app.kairos.mobile.ui.kit.KairosIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.LocalPolBottom
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.TitleInfo
import app.kairos.mobile.ui.politique.kit.FullscreenButton
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.VerdictMeter
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.verdictLabel
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.coroutines.launch

private val Pad: PaddingValues @Composable get() = ScreenPad

private class GuessLook(val arrow: String, val fg: Color, val bg: Color)

@Composable
private fun look(g: Guess): GuessLook {
    val pc = PolColors.current
    return when (g) {
        Guess.Faux -> GuessLook("←", pc.fragile, pc.fragileBg)
        Guess.Partiel -> GuessLook("↑", pc.partiel, pc.partielBg)
        Guess.Mais -> GuessLook("↓", pc.trompeur, pc.trompeurBg)
        Guess.Vrai -> GuessLook("→", pc.solide, pc.solideBg)
    }
}

/** Les quatre réponses, de gauche à droite, comme sur l'écran de départ. */
private val ORDER = listOf(Guess.Faux, Guess.Partiel, Guess.Mais, Guess.Vrai)

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
                        "Vrai mais… : la phrase est exacte, mais elle trompe (chiffre sorti de son contexte, comparaison bancale). " +
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
        item { Text("Vrai mais… : exacte, mais trompeuse. « Fragile » et « Faux » se jouent à gauche.", fontSize = 13.sp, color = scheme.onSurfaceVariant) }
        if (out > 0) item { Text("${frCount(out, "phrase non documentée", "phrases non documentées")} hors jeu.", fontSize = 13.sp, color = scheme.onSurfaceVariant) }
        if (themes.isEmpty()) {
            item { EmptyState("Aucune phrase jouable", "Il faut des phrases vérifiées avec un verdict pour jouer. Elles apparaîtront ici dès leur publication.") }
            return@LazyColumn
        }
        item { Text("Choisir un paquet", Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(listOf("") + themes, key = { it }) { t ->
            val deck = quizDeck(claims, camps, t)
            val nCamps = deck.map { claims[it].camp }.distinct().size
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
    val scheme = MaterialTheme.colorScheme
    val revealed = ans.size > i
    var exit by remember(i) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = LocalPolBottom.current)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuizBar(quizProgress(ans.size, n, atResult = false), Modifier.weight(1f))
            Text("${i + 1} / $n · ${frCount(score, "juste", "justes")}", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Box(Modifier.weight(1f).padding(top = 14.dp, bottom = 12.dp)) {
            for (j in 2 downTo 1) if (i + j < n) {
                Surface(
                    Modifier.fillMaxSize().graphicsLayer { translationY = (28 * j).dp.toPx(); scaleX = 1f - .05f * j; scaleY = 1f - .05f * j; alpha = if (j == 1) .7f else .4f },
                    shape = RoundedCornerShape(24.dp), color = scheme.surface, tonalElevation = 1.dp, border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {}
            }
            androidx.compose.runtime.key(i) {
                QuizCard(data, nav, claims[deck[i]], ans.getOrNull(i), exit, onAnswer = { if (ans.size == i) ans = ans + it }, onExit = { if (exit == 0) exit = it }, onNext = { i++ })
            }
        }
        if (revealed) {
            Button({ if (exit == 0) exit = 1 }, Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (i + 1 < n) "Carte suivante ›" else "Voir mon score ›", fontWeight = FontWeight.Bold)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ORDER.forEach { g ->
                    val l = look(g)
                    Surface({ if (ans.size == i) ans = ans + g }, Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp), color = l.bg) {
                        Column(Modifier.padding(vertical = 8.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(l.arrow, color = l.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(g.label, color = l.fg, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 14.sp)
                        }
                    }
                }
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

@Composable
private fun QuizCard(data: PolData, nav: PolNavigator, claim: Claim, guess: Guess?, exit: Int, onAnswer: (Guess) -> Unit, onExit: (Int) -> Unit, onNext: () -> Unit) {
    val reduced = reducedMotion()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    val flip = remember { Animatable(0f) }
    val scale = remember { Animatable(if (reduced) 1f else .95f) }
    val fade = remember { Animatable(1f) }
    val revealed = guess != null
    val spring = spring<Float>(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow)

    suspend fun settle(a: Animatable<Float, *>, to: Float) = if (reduced) a.snapTo(to) else a.animateTo(to, spring)
    fun springBack() { scope.launch { settle(dx, 0f) }; scope.launch { settle(dy, 0f) } }

    LaunchedEffect(Unit) { if (!reduced) scale.animateTo(1f, tween(300)) }
    LaunchedEffect(revealed) {
        if (revealed) {
            launch { settle(dx, 0f) }; launch { settle(dy, 0f) }
            if (reduced) flip.snapTo(180f) else flip.animateTo(180f, tween(450))
        }
    }
    LaunchedEffect(exit) {
        if (exit == 0) return@LaunchedEffect
        if (!reduced) {
            launch { fade.animateTo(0f, tween(300)) }
            dx.animateTo(exit * 520f * density, tween(300))
        }
        onNext()
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationX = dx.value; translationY = dy.value * (if (revealed) .3f else .6f)
                rotationZ = dx.value / 18f; scaleX = scale.value; scaleY = scale.value; alpha = fade.value
            }
            .pointerInput(revealed, exit) {
                if (exit != 0) return@pointerInput
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = { tracker.resetTracking(); scope.launch { dx.stop(); dy.stop() } },
                    onDragCancel = { springBack() },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        val x = dx.value / density; val y = dy.value / density
                        val vx = v.x / 1000f / density; val vy = v.y / 1000f / density
                        if (revealed) {
                            if (swipeToNext(x, vx)) onExit(if (x != 0f) Math.signum(x).toInt() else if (vx < 0) -1 else 1) else springBack()
                        } else swipeDirection(x, y, vx, vy)?.let(onAnswer) ?: springBack()
                    },
                    onDrag = { change, amount ->
                        change.consume(); tracker.addPointerInputChange(change)
                        scope.launch { dx.snapTo(dx.value + amount.x); dy.snapTo(dy.value + amount.y) }
                    },
                )
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = flip.value; cameraDistance = 16f * density }) {
            if (flip.value <= 90f) QuizFront(data, claim, dx.value / density, dy.value / density, interactive = !revealed)
            else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { QuizBack(data, nav, claim, guess ?: Guess.Vrai) }
        }
    }
}

private fun ramp(x: Float) = x.coerceIn(0f, 1f)

@Composable
private fun QuizFront(data: PolData, claim: Claim, x: Float, y: Float, interactive: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val edge = data.campColor(claim.camp) ?: scheme.outlineVariant
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp), color = scheme.surface, tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(edge))
                Column(Modifier.weight(1f).padding(22.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Kicker(claim.theme, Modifier.weight(1f))
                        val id = data.idOf(claim.camp)
                        if (id != null) IdentityChip(data, id, text = claim.camp) else Text(claim.camp, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                        Text("« ${frTypo(claim.q)} »", fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                        Text("Phrase entendue côté ${claim.camp.lowercase()}", Modifier.padding(top = 10.dp), fontSize = 13.5.sp, color = scheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("← Faux", "↑ En partie", "↓ Vrai mais", "Vrai →").forEach { Text(it.uppercase(), fontSize = 9.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = scheme.onSurfaceVariant) }
                    }
                }
            }
            if (interactive) {
                val mid = kotlin.math.abs(x) < 60f
                Stamp("Vrai", look(Guess.Vrai).fg, ramp(x / 100f), -12f, Modifier.align(Alignment.TopStart).offset(20.dp, 76.dp))
                Stamp("Faux", look(Guess.Faux).fg, ramp(-x / 100f), 12f, Modifier.align(Alignment.TopEnd).offset((-20).dp, 76.dp))
                Stamp("En partie", look(Guess.Partiel).fg, if (mid) ramp(-y / 100f) else 0f, 0f, Modifier.align(Alignment.BottomCenter).offset(y = (-70).dp))
                Stamp("Vrai mais…", look(Guess.Mais).fg, if (mid) ramp(y / 100f) else 0f, 0f, Modifier.align(Alignment.TopCenter).offset(y = 76.dp))
            }
        }
    }
}

@Composable
private fun Stamp(text: String, color: Color, a: Float, rot: Float, modifier: Modifier) {
    if (a <= 0f) return
    Text(
        text.uppercase(), modifier.graphicsLayer { rotationZ = rot }.alpha(a).clearAndSetSemantics { }.border(3.dp, color, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        color = color, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
    )
}

@Composable
private fun QuizBack(data: PolData, nav: PolNavigator, claim: Claim, guess: Guess) {
    val scheme = MaterialTheme.colorScheme
    val pc = PolColors.current
    val fb = remember(claim, guess) { quizFeedback(claim.v, guess) }
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp), color = scheme.surface, tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
            Kicker("${claim.theme} · côté ${claim.camp}")
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                VerdictMeter(claim.v)
                VerdictChip(claim.v, large = true)
                Spacer(Modifier.weight(1f))
                FullscreenButton({ nav.table(listOf(claimTableCard(claim)), 0) })
            }
            Text(
                fb.text, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = when (fb.tone) { Tone.Ok -> pc.solide; Tone.Near -> pc.trompeur; Tone.Ko -> scheme.onSurfaceVariant },
            )
            Text("« ${frTypo(claim.q)} »", Modifier.padding(top = 12.dp), fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            CacheBox(claim.cache)
            claim.facts.forEach { Text("• ${frTypo(it)}", Modifier.padding(top = 10.dp), fontSize = 14.5.sp, lineHeight = 20.sp) }
            SourceList(sourcesOf(claim.src), data, { nav.sheet(PolSheetState.Evaluator(it)) })
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
