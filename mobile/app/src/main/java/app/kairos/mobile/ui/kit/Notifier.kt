package app.kairos.mobile.ui.kit

import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * avec son délai complet, compté seulement une fois « Annuler » affiché ; tout message
 * d'information qui occupe l'hôte avant elle est écarté puis reproposé après elle (un refus
 * n'est jamais effacé par le geste suivant). [undoMs] est relu à chaque fenêtre (service
 * d'accessibilité activé entre-temps).
 */
class Notifier(val host: SnackbarHostState = SnackbarHostState(), private val undoMs: () -> Long = { UNDO_WINDOW_MS }) {
    private val lock = Mutex()

    /** Messages écartés par une fenêtre, à reproposer après elle. Accès depuis le fil principal. */
    private val deferred = mutableSetOf<SnackbarVisuals>()

    /** Vrai si « Annuler » a été touché pendant la fenêtre. */
    suspend fun undo(text: String): Boolean = lock.withLock {
        coroutineScope {
            val visuals = Visuals(text, "Annuler", SnackbarDuration.Indefinite)
            val result = async { host.showSnackbar(visuals) }
            // ponytail: scrutation toutes les 16 ms, l'hôte n'expose pas de file observable hors composition.
            while (host.currentSnackbarData?.visuals !== visuals) {
                host.currentSnackbarData?.let {
                    deferred += it.visuals
                    it.dismiss()
                }
                delay(16)
            }
            val r = withTimeoutOrNull(undoMs()) { result.await() }
            result.cancel()
            r == SnackbarResult.ActionPerformed
        }
    }

    /** Message d'information ; attend la fin du message en cours, revient après une fenêtre qui l'a écarté. */
    suspend fun show(text: String) {
        val visuals = Visuals(text, null, SnackbarDuration.Short)
        while (host.showSnackbar(visuals) == SnackbarResult.Dismissed && deferred.remove(visuals)) {
            lock.withLock {} // la fenêtre en cours passe d'abord
        }
    }

    /** Identité propre à chaque message : deux textes égaux restent deux messages. */
    private class Visuals(
        override val message: String,
        override val actionLabel: String?,
        override val duration: SnackbarDuration,
    ) : SnackbarVisuals {
        override val withDismissAction = false
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
