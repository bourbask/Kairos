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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.kairos.mobile.ui.mascot.MascotScene
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.mascot.SceneKind
import app.kairos.mobile.ui.mascot.mascotDescription
import app.kairos.mobile.ui.theme.Motion
import app.kairos.mobile.ui.theme.KairosColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Tab { Aujourdhui, Espaces }

/** Onglet d'un dock propre à un espace : [icon] reçoit la teinte (sélectionné ou non). */
class DockItem(val id: String, val label: String, val icon: @Composable (Color) -> Unit)

/**
 * Dock propre à l'espace ouvert : deux onglets de chaque côté du bouton Kairos, à la place
 * d'« Aujourd'hui » et « Espaces ». [selected] : id de l'onglet courant, ou aucun.
 */
class ContextDock(val items: List<DockItem>, val selected: String?, val onSelect: (String) -> Unit)

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
    /** Espace ouvert dont le bouton Kairos joue la scène ; [sceneDown] : variante en baisse. */
    scene: SceneKind? = null,
    sceneDown: Boolean = false,
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
    /** Dock de l'espace ouvert ; null : onglets de l'application. */
    contextDock: ContextDock? = null,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(top = top, bottom = DockHeight + nav)
    // Scène ponctuelle demandée par un écran via LocalPlayMoment, jouée une fois par le bouton Kairos.
    var moment by remember { mutableStateOf<SceneKind?>(null) }
    val play = remember { { s: SceneKind -> moment = s } }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalPlayMoment provides play) {
            AnimatedContent(tab, transitionSpec = { Motion.fadeThrough() }, label = "onglet") {
                when (it) {
                    Tab.Aujourdhui -> today(padding)
                    Tab.Espaces -> spaces(padding)
                }
            }
        }
        Dock(
            tab = tab,
            onTab = onTab,
            mascotState = mascotState,
            scene = scene,
            sceneDown = sceneDown,
            moment = moment,
            onMomentDone = { moment = null },
            kairosState = kairosState,
            onTap = onTap,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onDrag = onDrag,
            onSpeak = onSpeak,
            kairosModifier = kairosModifier,
            contextDock = contextDock,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun Dock(
    tab: Tab,
    onTab: (Tab) -> Unit,
    mascotState: MascotState,
    scene: SceneKind?,
    sceneDown: Boolean,
    moment: SceneKind?,
    onMomentDone: () -> Unit,
    kairosState: String,
    onTap: () -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onSpeak: () -> Unit,
    kairosModifier: Modifier,
    contextDock: ContextDock?,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    val kairos = @Composable {
        KairosButton(mascotState, onTap, onPressStart, onPressEnd, onDrag, onSpeak, kairosModifier, kairosState, scene, sceneDown, moment, onMomentDone)
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.35f to bg, 1f to bg))
            .navigationBarsPadding()
            .padding(start = if (contextDock != null) 8.dp else 28.dp, end = if (contextDock != null) 8.dp else 28.dp, top = 24.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (contextDock != null) {
            // Onglets de l'espace : deux à gauche, deux à droite du bouton Kairos.
            val half = (contextDock.items.size + 1) / 2
            listOf(contextDock.items.take(half), contextDock.items.drop(half)).forEachIndexed { side, group ->
                if (side == 1) kairos()
                Row(Modifier.weight(1f)) {
                    group.forEach { item ->
                        TabItem(item.label, item.id == contextDock.selected, { contextDock.onSelect(item.id) }, Modifier.weight(1f).widthIn(min = 48.dp), item.icon)
                    }
                }
            }
        } else {
            TabItem("Aujourd'hui", tab == Tab.Aujourdhui, { onTab(Tab.Aujourdhui) }) { TodayIcon(it) }
            kairos()
            TabItem("Espaces", tab == Tab.Espaces, { onTab(Tab.Espaces) }) { SpacesIcon(it) }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    // Largeur minimale seulement : un libellé agrandi (police 1,3×) ne se coupe pas.
    modifier: Modifier = Modifier.widthIn(min = 80.dp),
    icon: @Composable (Color) -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
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
            maxLines = 1,
            softWrap = false,
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

/**
 * Joue une fois une scène ponctuelle dans le bouton Kairos du dock (paliers :
 * [SceneKind.PremierCoup], [SceneKind.Moitie], [SceneKind.Complet]), puis le
 * bouton revient de lui-même à son état normal. Fourni par [Shell] aux contenus
 * de ses onglets ; ailleurs (aperçus, panneaux hors coque), ne fait rien. Une
 * scène lancée pendant qu'une autre se joue la remplace ; relancer la même scène
 * en cours n'a pas d'effet.
 *
 * ```
 * val play = LocalPlayMoment.current
 * LaunchedEffect(theme.complete) { if (theme.complete) play(SceneKind.Complet) }
 * ```
 */
val LocalPlayMoment = staticCompositionLocalOf<(SceneKind) -> Unit> { {} }

/**
 * Bouton central : tap pour écrire, appui long pour parler.
 *
 * [scene] : scène d'espace jouée en boucle. [moment] : scène ponctuelle jouée une
 * fois par-dessus (prioritaire sur [scene]), puis [onMomentDone] est appelé et
 * l'appelant remet [moment] à null. Une conversation (écoute, réflexion, réponse)
 * passe avant les deux et met fin au moment en cours.
 */
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
    scene: SceneKind? = null,
    sceneDown: Boolean = false,
    moment: SceneKind? = null,
    onMomentDone: () -> Unit = {},
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
    val btn = KairosColors.current.btn

    Box(
        modifier
            .scale(scale)
            .shadow(14.dp, shape, ambientColor = btn, spotColor = btn)
            .size(68.dp)
            .background(btn, shape)
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
        // La scène d'un espace cède la place quand on parle à Kairos : écoute, réflexion, réponse.
        val conversing = state == MascotState.Ecoute || state == MascotState.Reflechit || state == MascotState.Agit
        val momentDone by rememberUpdatedState(onMomentDone)
        // Un moment interrompu par la conversation ne se rejoue pas après coup.
        LaunchedEffect(conversing, moment) { if (conversing && moment != null) momentDone() }
        when {
            conversing -> Mascot(state, size = 46.dp)
            moment != null -> MascotScene(moment, false, Modifier.fillMaxSize().clip(shape), onDone = { momentDone() })
            scene != null -> MascotScene(scene, sceneDown, Modifier.fillMaxSize().clip(shape))
            else -> Mascot(state, size = 46.dp)
        }
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
