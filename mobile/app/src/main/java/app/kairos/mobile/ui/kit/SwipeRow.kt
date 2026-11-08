package app.kairos.mobile.ui.kit

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Libellé d'action dit par le lecteur d'écran : les symboles décoratifs (✓, →) en moins. */
fun spokenLabel(label: String): String = label.filterNot { it == '✓' || it == '→' || it == '←' }.trim()

/**
 * Glissement horizontal : → positif ([rightLabel]), ← négatif ([leftLabel]). Le libellé
 * de l'action apparaît sous l'élément pendant le geste ; vibration au seuil.
 * [rightStays] : → ouvre un choix sur place, l'élément revient au lieu de partir ([leftStays] : ←).
 * Chaque sens doté d'un libellé est aussi proposé en action d'accessibilité ; un libellé vide
 * signifie « pas d'action dans ce sens ». Le contenu forme un seul élément pour le lecteur d'écran.
 */
@Composable
fun SwipeRow(
    enabled: Boolean,
    onRight: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    rightLabel: String = "Fait ✓",
    leftLabel: String = "Plus tard",
    leftColor: Color = MaterialTheme.colorScheme.tertiary,
    rightStays: Boolean = false,
    leftStays: Boolean = false,
    content: @Composable () -> Unit,
) {
    val right by rememberUpdatedState(onRight)
    val left by rememberUpdatedState(onLeft)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dx by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var armed by remember { mutableStateOf(false) }
    val threshold = width * 0.32f
    val actions = buildList {
        spokenLabel(rightLabel).takeIf { it.isNotEmpty() }?.let { add(CustomAccessibilityAction(it) { right(); true }) }
        spokenLabel(leftLabel).takeIf { it.isNotEmpty() }?.let { add(CustomAccessibilityAction(it) { left(); true }) }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .semantics(mergeDescendants = true) { if (enabled) customActions = actions },
    ) {
        if (dx != 0f) {
            val positive = dx > 0
            Row(
                Modifier
                    .matchParentSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (positive) MaterialTheme.colorScheme.primary else leftColor)
                    .padding(horizontal = 18.dp),
                horizontalArrangement = if (positive) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (positive) rightLabel else leftLabel,
                    // Rôles « on… » du thème : contraste AA sur le fond du geste.
                    color = if (positive) MaterialTheme.colorScheme.onPrimary else contentColorFor(leftColor),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alpha((abs(dx) / threshold).coerceIn(0.35f, 1f)),
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(dx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    state = rememberDraggableState { delta ->
                        dx += delta
                        val over = abs(dx) > threshold
                        if (over != armed) {
                            armed = over
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    },
                    onDragStopped = { velocity ->
                        val fling = abs(velocity) > 2000f && abs(dx) > width * 0.12f && velocity * dx > 0
                        val commit = armed || fling
                        val positive = dx > 0
                        val target = if (!commit || (if (positive) rightStays else leftStays)) 0f else if (positive) width.toFloat() else -width.toFloat()
                        scope.launch {
                            animate(dx, target, initialVelocity = velocity, animationSpec = Motion.settle()) { v, _ -> dx = v }
                            if (commit) {
                                if (positive) right() else left()
                            }
                            dx = 0f
                            armed = false
                        }
                    },
                ),
        ) { content() }
    }
}
