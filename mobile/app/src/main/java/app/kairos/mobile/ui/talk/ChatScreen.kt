package app.kairos.mobile.ui.talk

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.ChatEntry
import app.kairos.mobile.ui.DockHeight
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.theme.Motion
import app.kairos.mobile.ui.theme.Palette
import kotlin.math.max

/**
 * Conversation écrite : même fil que le salon. Glisser l'en-tête vers le bas
 * ou le bouton « Fermer » referme ; le micro de la saisie lance la dictée
 * mains libres (équivalent visible de l'appui long sur Kairos).
 */
@Composable
fun ChatScreen(
    conversation: Conversation,
    mascot: MascotState,
    focusInput: Boolean,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
) {
    DisposableEffect(conversation) {
        conversation.visible = true
        onDispose { conversation.visible = false }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Header(conversation, mascot, onClose)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Thread(conversation, Modifier.weight(1f))
        InputBar(conversation, focusInput, onMic, onSend)
    }
}

@Composable
private fun Header(
    conversation: Conversation,
    mascot: MascotState,
    onClose: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val closePx = with(LocalDensity.current) { 96.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(pull, Motion.settle(), label = "tirer")
    Row(
        Modifier
            .fillMaxWidth()
            .offset { androidx.compose.ui.unit.IntOffset(0, (offset / 3).toInt()) }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d ->
                    val before = pull
                    pull = max(0f, pull + d)
                    if (before < closePx && pull >= closePx) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                },
                onDragStopped = {
                    if (pull >= closePx) onClose()
                    pull = 0f
                },
            )
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(mascot, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Kairos", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            val status = when {
                conversation.offline -> "hors ligne"
                conversation.busy || conversation.awaiting -> "réfléchit…"
                else -> "à l'écoute"
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(label = "Fermer", onClick = onClose) { ChevronDownIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Personnage sur sa pastille, comme sur le bouton central. */
@Composable
internal fun Badge(state: MascotState, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) { Mascot(state, size = size * 0.68f, onBackground = Palette.CorpsKairos) }
}

private sealed interface ThreadRow {
    val key: Any

    data class Entry(val entry: ChatEntry) : ThreadRow {
        override val key: Any get() = entry.id
    }

    data class Pending(val text: String, val index: Int) : ThreadRow {
        override val key: Any get() = "attente-$index-$text"
    }

    data object Typing : ThreadRow {
        override val key: Any get() = "kairos-ecrit"
    }
}

@Composable
private fun Thread(conversation: Conversation, modifier: Modifier) {
    // Liste inversée : la conversation colle au bas, comme une messagerie.
    val rows = buildList {
        if (conversation.awaiting) add(ThreadRow.Typing)
        conversation.pending.withIndex().reversed().forEach { add(ThreadRow.Pending(it.value, it.index)) }
        conversation.entries.asReversed().forEach { add(ThreadRow.Entry(it)) }
    }
    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Écris-moi, ou garde le doigt sur le micro pour parler.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        items(rows, key = { it.key }) { row ->
            Box(Modifier.fillMaxWidth().animateItem()) {
                when (row) {
                    is ThreadRow.Entry -> Bubble(
                        text = row.entry.text,
                        mine = row.entry.role == "user",
                        caption = caption(row.entry),
                    )
                    is ThreadRow.Pending -> Bubble(row.text, mine = true, caption = "envoi…", Modifier.alpha(0.6f))
                    ThreadRow.Typing -> TypingBubble()
                }
            }
        }
    }
}

private fun caption(entry: ChatEntry): String {
    val time = entry.at.takeIf { it.length >= 16 }?.substring(11, 16).orEmpty()
    return if (entry.role == "user" && entry.source == "salon") "$time · depuis le salon" else time
}

@Composable
private fun Bubble(text: String, mine: Boolean, caption: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        val shape = RoundedCornerShape(
            topStart = 18.dp, topEnd = 18.dp,
            bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
        )
        Surface(
            shape = shape,
            color = if (mine) colors.primary else colors.surface,
            contentColor = if (mine) colors.onPrimary else colors.onSurface,
            border = if (mine) null else BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
        }
        if (caption.isNotEmpty()) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    val t = rememberInfiniteTransition(label = "ecrit")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "points")
    val dot = MaterialTheme.colorScheme.primary
    Row(
        Modifier.semantics { contentDescription = "Kairos réfléchit" },
        verticalAlignment = Alignment.Bottom,
    ) {
        Badge(MascotState.Reflechit, 28.dp)
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Canvas(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).size(width = 34.dp, height = 8.dp)) {
                for (i in 0..2) {
                    val lift = (1f - kotlin.math.abs(phase - i - 0.5f).coerceAtMost(1f))
                    drawCircle(
                        dot.copy(alpha = 0.35f + 0.65f * lift),
                        radius = 3.5.dp.toPx(),
                        center = Offset(size.width / 6 * (2 * i + 1), size.height / 2 - lift * 2.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(conversation: Conversation, focusInput: Boolean, onMic: () -> Unit, onSend: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusInput) { if (focusInput) focus.requestFocus() }
    val canSend = conversation.draft.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = conversation.draft,
            onValueChange = { conversation.draft = capInput(it) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            maxLines = 5,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "Message à Kairos" },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .background(colors.surface, RoundedCornerShape(24.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                ) {
                    if (conversation.draft.isEmpty()) {
                        Text("Écrire à Kairos…", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        // Micro quand la saisie est vide, envoi dès qu'il y a du texte.
        AnimatedContent(
            targetState = canSend,
            transitionSpec = { Motion.fadeThrough() },
            label = "action",
        ) { send ->
            RoundAction(
                label = if (send) "Envoyer" else "Parler",
                filled = send,
                onClick = {
                    if (send) {
                        val text = conversation.draft
                        conversation.draft = ""
                        onSend(text)
                    } else {
                        onMic()
                    }
                },
            ) { tint -> if (send) SendIcon(tint) else MicIcon(tint) }
        }
    }
}

@Composable
private fun RoundAction(label: String, filled: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (filled) colors.primary else colors.primaryContainer)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { icon(if (filled) colors.onPrimary else colors.onPrimaryContainer) }
}

@Composable
private fun IconButton(label: String, onClick: () -> Unit, state: String? = null, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/**
 * Panneau de parole, au-dessus du bouton Kairos (ou de la saisie quand la
 * conversation est ouverte) : ondes, transcription en direct, verrou ↑ et
 * annulation ←. En mains libres, le texte devient modifiable avant envoi.
 */
@Composable
fun TalkPanel(
    talk: TalkController,
    level: Float,
    aboveDock: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = talk.mode != TalkMode.Idle
    BackHandler(enabled = visible, onBack = onCancel)
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    AnimatedVisibility(
        visible = visible,
        enter = Motion.riseIn,
        exit = Motion.sinkOut,
        modifier = modifier,
    ) {
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxWidth().imePadding().padding(bottom = if (aboveDock) DockHeight + nav - 18.dp else 0.dp)) {
            Surface(
                shape = if (aboveDock) RoundedCornerShape(24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = colors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (aboveDock) 10.dp else 0.dp)
                    .shadow(16.dp, RoundedCornerShape(24.dp), clip = false),
            ) {
                Column(
                    Modifier.padding(top = 18.dp, bottom = if (aboveDock) 18.dp else 18.dp + nav).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (talk.mode == TalkMode.Locked) {
                        LockedContent(talk, level, onSend, onCancel, onMic)
                    } else {
                        HoldContent(talk, level)
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldContent(talk: TalkController, level: Float) {
    val colors = MaterialTheme.colorScheme
    val cancelled = talk.mode == TalkMode.Cancelled
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        // Annuler : glisser vers la gauche ; le libellé rougit à l'approche du seuil.
        Text(
            if (cancelled) "Annulé" else "‹ glisser pour annuler",
            style = MaterialTheme.typography.labelMedium,
            color = if (cancelled) colors.error else lerp(colors.onSurfaceVariant, colors.error, talk.cancelProgress),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-16).dp * talk.cancelProgress),
        )
        Wave(level, active = talk.listening, Modifier.align(Alignment.Center))
        LockPill(talk.lockProgress, Modifier.align(Alignment.CenterEnd))
    }
    Spacer(Modifier.height(8.dp))
    val text = talk.transcript
    Text(
        when {
            cancelled -> "Rien n'est envoyé."
            text.isNotEmpty() -> "« $text »"
            talk.mode == TalkMode.Finishing -> "…"
            else -> "Je t'écoute…"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = if (text.isEmpty() || cancelled) colors.onSurfaceVariant else colors.onSurface,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Transcription : $text" },
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "relâche pour envoyer · ↑ mains libres",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
    )
}

@Composable
private fun LockedContent(talk: TalkController, level: Float, onSend: () -> Unit, onCancel: () -> Unit, onMic: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Badge(MascotState.Ecoute, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Mains libres", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(
                if (talk.listening) "je t'écoute…" else "corrige si besoin, puis envoie",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (talk.listening) colors.primary else colors.primaryContainer)
                .clickable(
                    onClickLabel = if (talk.listening) "Arrêter la dictée" else "Reprendre la dictée",
                    role = Role.Button,
                    onClick = onMic,
                )
                .semantics { contentDescription = if (talk.listening) "Arrêter la dictée" else "Reprendre la dictée" },
            contentAlignment = Alignment.Center,
        ) {
            if (talk.listening) Wave(level, active = true, bars = 3) else MicIcon(colors.onPrimaryContainer)
        }
    }
    Spacer(Modifier.height(12.dp))
    BasicTextField(
        value = talk.transcript,
        onValueChange = talk::edit,
        readOnly = talk.listening,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Transcription modifiable" },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(colors.background, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                if (talk.transcript.isEmpty()) {
                    Text("Parle, ou écris ici…", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                inner()
            }
        },
    )
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel) { Text("Annuler") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSend, enabled = talk.transcript.isNotBlank()) { Text("Envoyer") }
    }
}

/** Ondes de la voix : hauteur selon le niveau du micro, ondulation douce au repos. */
@Composable
private fun Wave(level: Float, active: Boolean, modifier: Modifier = Modifier, bars: Int = 7) {
    val t = rememberInfiniteTransition(label = "ondes")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "phase")
    val amp by animateFloatAsState(if (active) 0.25f + 0.75f * level else 0.1f, tween(120), label = "niveau")
    val color = MaterialTheme.colorScheme.let { if (bars == 7) it.primary else it.onPrimary }
    Canvas(modifier.size(width = (bars * 7).dp, height = if (bars == 7) 34.dp else 20.dp)) {
        val w = 4.dp.toPx()
        val step = size.width / bars
        for (i in 0 until bars) {
            val wobble = 0.55f + 0.45f * kotlin.math.sin((phase + i * 0.37f) * Math.PI.toFloat() * 2)
            val h = (size.height * amp * wobble).coerceAtLeast(w)
            drawRoundRect(
                color,
                topLeft = Offset(step * i + (step - w) / 2, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/** Verrou : se remplit à mesure que le doigt monte. */
@Composable
private fun LockPill(progress: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = lerp(colors.onSurfaceVariant, colors.primary, progress)
    Column(
        modifier
            .offset(y = (-14).dp * progress)
            .width(36.dp)
            .height(60.dp)
            .background(lerp(colors.surface, colors.primaryContainer, progress), RoundedCornerShape(18.dp))
            .border(1.dp, lerp(colors.outlineVariant, colors.primary, progress), RoundedCornerShape(18.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        LockIcon(tint)
        ChevronUpIcon(tint)
    }
}

// Icônes dessinées : pas de bibliothèque d'icônes pour une poignée de pictogrammes.

@Composable
private fun MicIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val s = 2.dp.toPx()
    val w = size.width
    drawRoundRect(color, Offset(w * 0.34f, 0f), Size(w * 0.32f, size.height * 0.6f), CornerRadius(w * 0.16f))
    drawArc(color, 0f, 180f, false, Offset(w * 0.18f, size.height * 0.2f), Size(w * 0.64f, size.height * 0.56f), style = Stroke(s, cap = StrokeCap.Round))
    drawLine(color, Offset(w / 2, size.height * 0.76f), Offset(w / 2, size.height), s, StrokeCap.Round)
}

@Composable
private fun SendIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val p = Path().apply {
        moveTo(size.width * 0.1f, size.height * 0.12f)
        lineTo(size.width * 0.95f, size.height * 0.5f)
        lineTo(size.width * 0.1f, size.height * 0.88f)
        lineTo(size.width * 0.24f, size.height * 0.5f)
        close()
    }
    drawPath(p, color)
}

@Composable
private fun ChevronDownIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val s = 2.2.dp.toPx()
    drawLine(color, Offset(size.width * 0.2f, size.height * 0.38f), Offset(size.width * 0.5f, size.height * 0.66f), s, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.5f, size.height * 0.66f), Offset(size.width * 0.8f, size.height * 0.38f), s, StrokeCap.Round)
}

@Composable
private fun ChevronUpIcon(color: Color) = Canvas(Modifier.size(14.dp)) {
    val s = 2.dp.toPx()
    drawLine(color, Offset(size.width * 0.15f, size.height * 0.68f), Offset(size.width * 0.5f, size.height * 0.32f), s, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.5f, size.height * 0.32f), Offset(size.width * 0.85f, size.height * 0.68f), s, StrokeCap.Round)
}

@Composable
private fun LockIcon(color: Color) = Canvas(Modifier.size(16.dp)) {
    val s = 1.8.dp.toPx()
    drawArc(color, 180f, 180f, false, Offset(size.width * 0.26f, size.height * 0.08f), Size(size.width * 0.48f, size.height * 0.5f), style = Stroke(s))
    drawRoundRect(color, Offset(size.width * 0.14f, size.height * 0.42f), Size(size.width * 0.72f, size.height * 0.52f), CornerRadius(2.dp.toPx()))
}
