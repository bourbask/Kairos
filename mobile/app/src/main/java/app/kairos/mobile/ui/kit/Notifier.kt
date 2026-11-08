package app.kairos.mobile.ui.kit

import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Durée pendant laquelle « Annuler » reste proposé. */
const val UNDO_WINDOW_MS = 4_000L

/** Durée allongée quand un lecteur d'écran explore au toucher : le temps d'atteindre « Annuler ». */
const val UNDO_WINDOW_ACCESSIBLE_MS = 10_000L

/** Délai d'annulation : [recommendedMs] (réglage système), au moins 10 s en exploration tactile. */
fun undoWindowMs(touchExploration: Boolean, recommendedMs: Long): Long =
    if (touchExploration) maxOf(UNDO_WINDOW_ACCESSIBLE_MS, recommendedMs) else recommendedMs

/**
 * File unique des messages de l'application. Fenêtres d'annulation : une à la fois, chacune
 * avec son délai complet, compté seulement une fois « Annuler » affiché ; un message
 * d'information en cours est écarté, un geste suivant attend son tour au lieu de partir sans
 * avoir été annulable. [undoMs] est relu à chaque fenêtre (service d'accessibilité activé entre-temps).
 */
class Notifier(val host: SnackbarHostState = SnackbarHostState(), private val undoMs: () -> Long = { UNDO_WINDOW_MS }) {
    private val lock = Mutex()

    /** Vrai si « Annuler » a été touché pendant la fenêtre. */
    suspend fun undo(text: String): Boolean = lock.withLock {
        host.currentSnackbarData?.dismiss()
        withTimeoutOrNull(undoMs()) {
            host.showSnackbar(text, actionLabel = "Annuler", duration = SnackbarDuration.Indefinite)
        } == SnackbarResult.ActionPerformed
    }

    /** Message d'information ; attend la fin du message en cours. */
    suspend fun show(text: String) {
        host.showSnackbar(text)
    }
}

/** File de l'application, fournie par l'écran racine. */
val LocalNotifier = staticCompositionLocalOf<Notifier> { error("Aucun Notifier fourni") }

/** File dont le délai d'annulation suit l'état des services d'accessibilité. */
@Composable
fun rememberNotifier(host: SnackbarHostState = remember { SnackbarHostState() }): Notifier {
    val context = LocalContext.current
    return remember(host) {
        val a11y = context.getSystemService(AccessibilityManager::class.java)
        Notifier(host) {
            undoWindowMs(
                touchExploration = a11y?.isTouchExplorationEnabled == true,
                recommendedMs = a11y?.getRecommendedTimeoutMillis(
                    UNDO_WINDOW_MS.toInt(),
                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
                )?.toLong() ?: UNDO_WINDOW_MS,
            )
        }
    }
}

/** Hôte unique des messages, à placer au-dessus du dock. */
@Composable
fun NotifierHost(notifier: Notifier, modifier: Modifier = Modifier) {
    SnackbarHost(notifier.host, modifier) { data ->
        Snackbar(
            data,
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.onBackground,
            contentColor = MaterialTheme.colorScheme.background,
            actionColor = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}
