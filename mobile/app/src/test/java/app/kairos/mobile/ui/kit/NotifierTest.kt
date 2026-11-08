package app.kairos.mobile.ui.kit

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifierTest {
    @Test
    fun queued_gesture_gets_its_own_full_window() = runBlocking {
        val host = SnackbarHostState()
        val notifier = Notifier(host) { 300 }
        val first = async { notifier.undo("A") }
        delay(20)
        val second = async { notifier.undo("B") }
        delay(400) // la première fenêtre est écoulée ; la seconde vient de s'ouvrir
        assertFalse(first.await())
        assertEquals("B", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData?.performAction()
        assertTrue(second.await())
    }

    @Test
    fun notice_does_not_eat_the_window() = runBlocking {
        val host = SnackbarHostState()
        val notifier = Notifier(host) { 300 }
        val notice = launch { notifier.show("info") }
        delay(20)
        val w = async { notifier.undo("C") }
        delay(50)
        assertEquals("C", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData?.performAction()
        assertTrue(w.await())
        notice.join()
    }

    @Test
    fun window_duration_is_read_when_the_window_opens() = runBlocking {
        val host = SnackbarHostState()
        var ms = 50L
        val notifier = Notifier(host) { ms }
        ms = 400 // un service d'accessibilité vient d'être activé
        val w = async { notifier.undo("D") }
        delay(150)
        assertEquals("D", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData?.performAction()
        assertTrue(w.await())
    }

    @Test
    fun undo_window_is_longer_with_touch_exploration() {
        assertEquals(4_000L, undoWindowMs(touchExploration = false, recommendedMs = 4_000))
        assertEquals(10_000L, undoWindowMs(touchExploration = true, recommendedMs = 4_000))
        // Un délai plus long choisi dans les réglages d'accessibilité du système l'emporte.
        assertEquals(20_000L, undoWindowMs(touchExploration = true, recommendedMs = 20_000))
        assertEquals(20_000L, undoWindowMs(touchExploration = false, recommendedMs = 20_000))
    }

    @Test
    fun swipe_action_labels_are_spoken_words() {
        assertEquals("Fait", spokenLabel("Fait ✓"))
        assertEquals("Déplacer", spokenLabel("Déplacer →"))
        assertEquals("Ne plus suivre", spokenLabel("Ne plus suivre"))
        assertEquals("", spokenLabel(""))
    }
}
