package app.kairos.mobile.ui.kit

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        // Écarté par la fenêtre, le message revient après elle au lieu d'être perdu.
        delay(50)
        assertEquals("info", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData?.dismiss()
        notice.join()
    }

    /** Instant où [text] devient le message affiché. */
    private suspend fun shownAt(host: SnackbarHostState, text: String): Long = withTimeout(2_000) {
        while (host.currentSnackbarData?.visuals?.message != text) delay(5)
        System.currentTimeMillis()
    }

    @Test
    fun queued_infos_do_not_eat_the_window() = runBlocking {
        val host = SnackbarHostState()
        val notifier = Notifier(host) { 300 }
        val i1 = launch { notifier.show("i1") }
        val i2 = launch { notifier.show("i2") }
        delay(20) // i1 affiché, i2 en file devant l'hôte
        val w = async { notifier.undo("W") }
        val start = shownAt(host, "W")
        delay(200)
        // La fenêtre court depuis l'affichage d'« Annuler », pas depuis le geste.
        assertEquals("W", host.currentSnackbarData?.visuals?.message)
        assertTrue(System.currentTimeMillis() - start < 300)
        host.currentSnackbarData?.performAction()
        assertTrue(w.await())
        // Les deux messages écartés repassent ensuite, dans l'ordre.
        shownAt(host, "i1"); host.currentSnackbarData?.dismiss()
        shownAt(host, "i2"); host.currentSnackbarData?.dismiss()
        i1.join(); i2.join()
    }

    @Test
    fun refusal_is_shown_again_after_the_next_window() = runBlocking {
        val host = SnackbarHostState()
        val notifier = Notifier(host) { 100 }
        val refusal = launch { notifier.show("Refusé") }
        delay(20)
        assertFalse(notifier.undo("W")) // geste suivant : la fenêtre passe devant, sans annulation
        shownAt(host, "Refusé") // échoue par délai dépassé si le refus a été effacé
        host.currentSnackbarData?.dismiss()
        refusal.join()
    }

    @Test
    fun info_dismissed_by_the_user_does_not_come_back() = runBlocking {
        val host = SnackbarHostState()
        val notifier = Notifier(host) { 100 }
        val info = launch { notifier.show("info") }
        delay(20)
        host.currentSnackbarData?.dismiss()
        info.join()
        assertEquals(null, host.currentSnackbarData)
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
