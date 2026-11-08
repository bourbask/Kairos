package app.kairos.mobile.work

import app.kairos.mobile.data.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyWatcherTest {

    private fun e(id: Long, role: String, text: String) = ChatEntry(id, "2026-11-08T08:00:00", role, "app", text)

    @Test
    fun watcher_ignores_holding_message_while_busy() {
        val holding = listOf(e(5, "user", "question"), e(6, "kairos", "Je regarde…"))
        assertNull(replyToNotify(holding, busy = true))
        val done = holding + e(7, "kairos", "Voici la réponse")
        assertEquals("Voici la réponse", replyToNotify(done, busy = false)?.text)
        assertNull(replyToNotify(listOf(e(5, "user", "question")), busy = false))
    }
}
