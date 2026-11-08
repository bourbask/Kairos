package app.kairos.mobile.ui.decide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Decision
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.theme.Motion
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val UNDO_MS = 4_000L
private const val THRESHOLD = 0.35f
private const val END_MS = 1_800L

/** Vitesse au-delà de laquelle un lancer vaut validation, même sous le seuil. */
private const val FLING_DP_S = 1_200

/**
 * Pile « Décider » : → accepte la proposition, ← montre les alternatives,
 * ↑ plus tard, ↓ ferme. Chaque geste a son bouton. Un choix part au serveur
 * après [UNDO_MS] (annulable jusque-là) depuis [appScope], qui survit à l'écran ;
 * fermer la pile envoie aussitôt les choix en attente. [onDecided] suit chaque
 * réponse du serveur, [onFinished] la fermeture. [onDispatch] reçoit chaque
 * choix au moment où il part : il quitte l'état connu, rouvrir la pile
 * (ou tourner l'écran) ne le repropose pas.
 */
@Composable
fun DeckScreen(
    decisions: List<Decision>,
    offline: Boolean,
    appScope: CoroutineScope,
    decide: suspend (Choice) -> String,
    onDispatch: (Choice) -> Unit,
    onDecided: () -> Unit,
    onFinished: () -> Unit,
    onUnauthorized: () -> Unit,
    onNotice: (String) -> Unit,
    onClose: () -> Unit,
) {
    val deck = remember { DeckState(decisions) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var busy by remember { mutableStateOf(false) }
    val decided by rememberUpdatedState(onDecided)
    val finished by rememberUpdatedState(onFinished)
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val notice by rememberUpdatedState(onNotice)
    val dispatched by rememberUpdatedState(onDispatch)

    suspend fun submit(c: Choice) {
        fun fail(reason: String) = if (deck.closed) notice(reason) else deck.refused(c, reason)
        try {
            // Message du module (avertissement compris) remonté tel quel.
            shown(decide(c))?.let(notice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            when (e) {
                ApiError.Unauthorized -> return unauthorized()
                ApiError.Offline -> fail("Serveur injoignable : rien n'a été enregistré.")
                ApiError.NoReply -> fail("Pas de réponse du serveur : réessaie.")
                is ApiError.Server -> when (e.code) {
                    404 -> notice("Cette question n'existe plus.")
                    503 -> fail("Module indisponible, réessaie plus tard.")
                    else -> fail(e.message ?: "Refusé.")
                }
            }
        }
        decided()
    }

    fun schedule(c: Choice) {
        scope.launch {
            delay(UNDO_MS)
            if (deck.dispatch(c)) {
                dispatched(c)
                appScope.launch { submit(c) }
            }
        }
    }

    // Quitter l'écran, quelle qu'en soit la raison, envoie ce qui a été décidé.
    DisposableEffect(deck) {
        onDispose {
            val flushed = deck.close()
            flushed.forEach(dispatched)
            appScope.launch {
                flushed.forEach { submit(it) }
                finished()
            }
        }
    }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val width = area.width.toFloat().coerceAtLeast(1f)
    val height = area.height.toFloat()
    val threshold = width * THRESHOLD

    val flingSpeed = with(LocalDensity.current) { FLING_DP_S.dp.toPx() }
    fun direction(o: Offset, v: Offset = Offset.Zero) = swipeOf(o, v, threshold, flingSpeed)

    suspend fun flingTo(target: Offset) = offset.animateTo(target, Motion.fling())
    suspend fun back() = offset.animateTo(Offset.Zero, Motion.settle())

    suspend fun commit(option: String) {
        flingTo(Offset(width * 1.4f, offset.value.y))
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        deck.choose(option)
        offset.snapTo(Offset.Zero)
        deck.outbox.lastOrNull()?.let(::schedule)
    }

    suspend fun act(swipe: Swipe?) {
        val card = deck.current
        when {
            card == null -> back()
            swipe == Swipe.Right && !offline && card.proposal != null -> commit(card.proposal.id)
            swipe == Swipe.Right || swipe == Swipe.Left -> {
                back()
                if (!offline) deck.swipeLeft()
            }
            swipe == Swipe.Up -> {
                flingTo(Offset(offset.value.x, -height * 1.2f))
                deck.later()
                offset.snapTo(Offset.Zero)
            }
            swipe == Swipe.Down -> {
                flingTo(Offset(offset.value.x, height * 1.2f))
                onClose()
            }
            else -> back()
        }
    }

    // Gestes, boutons et puces : une animation à la fois.
    fun exclusive(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }

    fun undo() {
        if (busy || !deck.undo()) return
        scope.launch {
            offset.snapTo(Offset(0f, -height * 0.3f))
            back()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        val progress by animateFloatAsState(
            if (deck.total == 0) 1f else deck.done.toFloat() / deck.total,
            Motion.enter(),
            label = "progression",
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            drawStopIndicator = {},
        )
        val current = deck.current
        if (current != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    current.module.replaceFirstChar { it.titlecase(Locale.FRENCH) },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${deck.done + 1} / ${deck.total}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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
            val next = deck.cards.getOrNull(1)
            val o = offset.value
            val lift = (maxOf(abs(o.x), abs(o.y)) / threshold).coerceIn(0f, 1f)
            if (next != null) {
                DecisionCard(
                    next,
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
            if (current != null) {
                DecisionCard(
                    current,
                    message = deck.message,
                    Modifier
                        .graphicsLayer {
                            translationX = o.x
                            translationY = o.y
                            rotationZ = (o.x / width) * 14f
                        }
                        .pointerInput(current.module, current.id, area, offline) {
                            // Déplacement cumulé ici : ne dépend pas de l'avance de l'animation.
                            var acc = Offset.Zero
                            var armed: Swipe? = null
                            val tracker = VelocityTracker()
                            detectDragGestures(
                                onDragStart = {
                                    acc = offset.value
                                    armed = null
                                    tracker.resetTracking()
                                },
                                onDragEnd = {
                                    val swipe = direction(acc, tracker.calculateVelocity().let { Offset(it.x, it.y) })
                                    exclusive { act(swipe) }
                                },
                                onDragCancel = { exclusive { back() } },
                            ) { change, drag ->
                                if (busy) return@detectDragGestures
                                change.consume()
                                acc += drag
                                tracker.addPosition(change.uptimeMillis, acc)
                                val target = acc
                                scope.launch { offset.snapTo(target) }
                                val now = direction(acc)
                                if (now != null && now != armed) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                armed = now
                            }
                        },
                    stamps = {
                        val yes = if (current.proposal != null && !offline) "OUI" else "CHOISIR"
                        Stamp(yes, MaterialTheme.colorScheme.primary, (o.x / threshold).coerceIn(0f, 1f), 12f, Alignment.TopEnd)
                        Stamp("NON", MaterialTheme.colorScheme.error, (-o.x / threshold).coerceIn(0f, 1f), -12f, Alignment.TopStart)
                        Stamp("PLUS TARD", MaterialTheme.colorScheme.tertiary, (-o.y / threshold).coerceIn(0f, 1f), -4f, Alignment.BottomCenter)
                    },
                )
            } else {
                Done(deck.done > 0)
            }
        }

        if (offline) {
            Text(
                "Hors ligne : décisions en pause, la pile reste lisible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }

        if (current != null && deck.alternativesOpen) {
            Alternatives(current, enabled = !offline, onChoose = { exclusive { commit(it) } }, onBack = deck::dismissAlternatives)
        } else if (current != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { exclusive { act(Swipe.Left) } }, enabled = !offline) { Text("← autre") }
                TextButton(onClick = { exclusive { act(Swipe.Up) } }) { Text("↑ plus tard") }
                TextButton(onClick = { exclusive { act(Swipe.Right) } }, enabled = !offline) {
                    Text(if (current.proposal != null) "oui →" else "choisir →", fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onClose) { Text("↓ fermer") }
            TextButton(onClick = ::undo, enabled = deck.canUndo) { Text("⟲ annuler") }
        }
    }

    // Pile vide : écran de fin, puis retour (une annulation l'interrompt).
    LaunchedEffect(deck.finished) {
        if (deck.finished) {
            delay(END_MS)
            onClose()
        }
    }
}

@Composable
private fun DecisionCard(
    card: Decision,
    message: String?,
    modifier: Modifier = Modifier,
    stamps: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.12f)),
    ) {
        Box(Modifier.fillMaxSize().padding(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    card.title.uppercase(Locale.FRENCH),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )
                shown(card.headline)?.let { Text(it, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 44.sp) }
                shown(card.subtitle)?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                card.body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6) }
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    val p = card.proposal
                    Text(
                        if (p != null) "Kairos propose" else "À toi de choisir",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (p != null) {
                        Text(p.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        p.reason?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    } else {
                        Text(
                            "Glisse ou touche pour voir les choix.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            stamps()
        }
    }
}

@Composable
internal fun BoxScope.Stamp(
    text: String,
    color: Color,
    alpha: Float,
    angle: Float,
    alignment: Alignment,
) {
    if (alpha <= 0f) return
    Text(
        text,
        color = color,
        fontSize = 24.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .align(alignment)
            .alpha(alpha)
            .rotate(angle)
            .border(3.dp, color, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Alternatives en grosses puces, sous le pouce. */
@Composable
private fun Alternatives(card: Decision, enabled: Boolean, onChoose: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            card.alternatives.forEach { option ->
                Surface(
                    onClick = { onChoose(option.id) },
                    enabled = enabled,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    Box(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(option.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (card.alternatives.isEmpty()) {
            Text("Aucun autre choix proposé.", style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onBack) { Text("Retour à la carte") }
    }
}

@Composable
private fun Done(any: Boolean) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mascot(if (any) MascotState.Agit else MascotState.Repos, size = 120.dp, onBackground = MaterialTheme.colorScheme.background)
        Spacer(Modifier.height(16.dp))
        Text(if (any) "C'est fait" else "Rien à décider", style = MaterialTheme.typography.headlineMedium)
    }
}
