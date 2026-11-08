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

class UndoWindowsTest {
    @Test
    fun queued_gesture_gets_its_own_full_window() = runBlocking {
        val host = SnackbarHostState()
        val undo = UndoWindows(host, windowMs = 300)
        val first = async { undo.open("A") }
        delay(20)
        val second = async { undo.open("B") }
        delay(400) // la première fenêtre est écoulée ; la seconde vient de s'ouvrir
        assertFalse(first.await())
        assertEquals("B", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData?.performAction()
        assertTrue(second.await())
    }

    @Test
    fun notice_does_not_eat_the_window() = runBlocking {
        val host = SnackbarHostState()
        val undo = UndoWindows(host, windowMs = 300)
        val notice = launch { host.showSnackbar("info") }
        delay(20)
        val w = async { undo.open("C") }
        delay(50)
        assertEquals("C", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData?.performAction()
        assertTrue(w.await())
        notice.join()
    }
}
