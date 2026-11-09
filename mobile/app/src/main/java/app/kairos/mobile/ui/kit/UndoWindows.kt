package app.kairos.mobile.ui.kit

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Durée pendant laquelle « Annuler » reste proposé. */
const val UNDO_WINDOW_MS = 4_000L

/**
 * Fenêtres d'annulation d'un écran : une à la fois, chacune avec son délai complet, compté
 * seulement une fois « Annuler » affiché. Un message d'information en cours est écarté ;
 * un geste suivant attend son tour au lieu de partir sans avoir été annulable.
 */
class UndoWindows(private val snackbar: SnackbarHostState, private val windowMs: Long = UNDO_WINDOW_MS) {
    private val lock = Mutex()

    /** Vrai si « Annuler » a été touché pendant la fenêtre. */
    suspend fun open(text: String): Boolean = lock.withLock {
        snackbar.currentSnackbarData?.dismiss()
        withTimeoutOrNull(windowMs) {
            snackbar.showSnackbar(text, actionLabel = "Annuler", duration = SnackbarDuration.Indefinite)
        } == SnackbarResult.ActionPerformed
    }
}
