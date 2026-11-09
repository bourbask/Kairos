package app.kairos.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.mascot.mascotDescription
import app.kairos.mobile.ui.theme.Motion
import app.kairos.mobile.ui.theme.Palette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Tab { Aujourdhui, Espaces }

/** Coins du bouton Kairos (la conversation en naît). */
internal val KairosButtonCorner = 22.dp

/** Hauteur réservée en bas du contenu pour ne pas passer sous la barre. */
internal val DockHeight = 104.dp

/**
 * Coque de l'application : deux onglets et Kairos au centre, sous le pouce.
 * Chaque onglet reçoit les marges à respecter (barre d'état, barre du bas).
 * Le bouton Kairos : [onTap] = ouvrir la conversation ; appui long =
 * [onPressStart], puis [onDrag] (déplacement cumulé depuis l'appui), puis
 * [onPressEnd] au relâchement. [onSpeak] : équivalent accessible de l'appui long.
 */
@Composable
fun Shell(
    mascotState: MascotState = MascotState.Repos,
    kairosState: String = mascotDescription(mascotState, 0),
    onTap: () -> Unit = {},
    onPressStart: () -> Unit = {},
    onPressEnd: () -> Unit = {},
    onDrag: (Offset) -> Unit = {},
    onSpeak: () -> Unit = {},
    today: @Composable (PaddingValues) -> Unit = { TodayPlaceholder(it) },
    spaces: @Composable (PaddingValues) -> Unit = {},
    kairosModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    tab: Tab = Tab.Aujourdhui,
    onTab: (Tab) -> Unit = {},
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(top = top, bottom = DockHeight + nav)

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(tab, transitionSpec = { Motion.fadeThrough() }, label = "onglet") {
            when (it) {
                Tab.Aujourdhui -> today(padding)
                Tab.Espaces -> spaces(padding)
            }
        }
        Dock(
            tab = tab,
            onTab = onTab,
            mascotState = mascotState,
            kairosState = kairosState,
            onTap = onTap,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onDrag = onDrag,
            onSpeak = onSpeak,
            kairosModifier = kairosModifier,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun Dock(
    tab: Tab,
    onTab: (Tab) -> Unit,
    mascotState: MascotState,
    kairosState: String,
    onTap: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onSpeak: () -> Unit,
    kairosModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.35f to bg, 1f to bg))
            .navigationBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        TabItem("Aujourd'hui", tab == Tab.Aujourdhui, { onTab(Tab.Aujourdhui) }) { TodayIcon(it) }
        KairosButton(mascotState, onTap, onPressStart, onPressEnd, onDrag, onSpeak, kairosModifier, kairosState)
        TabItem("Espaces", tab == Tab.Espaces, { onTab(Tab.Espaces) }) { SpacesIcon(it) }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .width(80.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(color)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TodayIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val r = size.minDimension / 2
    drawCircle(color, radius = r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
    drawCircle(color, radius = r * 0.42f)
}

@Composable
private fun SpacesIcon(color: Color) = Canvas(Modifier.size(22.dp)) {
    val gap = 3.dp.toPx()
    val side = (size.minDimension - gap) / 2
    val corner = CornerRadius(2.5.dp.toPx())
    for (i in 0..1) for (j in 0..1) {
        drawRoundRect(color, Offset(i * (side + gap), j * (side + gap)), Size(side, side), corner)
    }
}

/** Bouton central : tap pour écrire, appui long pour parler. */
@Composable
fun KairosButton(
    state: MascotState,
    onTap: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier,
    /** État du personnage en mots, annoncé avec le bouton. */
    stateText: String = mascotDescription(state, 0),
) {
    val speak by rememberUpdatedState(onSpeak)
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onPressStart)
    val end by rememberUpdatedState(onPressEnd)
    val drag by rememberUpdatedState(onDrag)
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, Motion.settle(), label = "appui")
    val shape = RoundedCornerShape(KairosButtonCorner)
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .scale(scale)
            .shadow(14.dp, shape, ambientColor = primary, spotColor = primary)
            .size(68.dp)
            .background(primary, shape)
            .semantics {
                role = Role.Button
                contentDescription = "Kairos"
                stateDescription = stateText
                onClick("Ouvrir la conversation") { tap(); true }
                customActions = listOf(CustomAccessibilityAction("Parler") { speak(); true })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    var released = false
                    val beforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        released = waitForUpOrCancellation()?.also { it.consume() } != null
                    } != null
                    if (beforeLongPress) {
                        pressed = false
                        if (released) tap()
                        return@awaitEachGesture
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    start()
                    try {
                        var total = Offset.Zero
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                change.consume()
                                total += delta
                                drag(total)
                            }
                        }
                    } finally {
                        pressed = false
                        end()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Mascot(state, size = 46.dp, onBackground = Palette.CorpsKairos)
    }
}

@Composable
private fun TodayPlaceholder(padding: PaddingValues) {
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)) }
    Column(Modifier.padding(padding).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("Bonjour", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            date.replaceFirstChar { it.titlecase(Locale.FRENCH) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
