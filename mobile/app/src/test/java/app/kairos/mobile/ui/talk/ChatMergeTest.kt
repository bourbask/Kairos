package app.kairos.mobile.ui.talk

import app.kairos.mobile.data.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMergeTest {

    private fun e(id: Long, role: String = "user") = ChatEntry(id, "2026-11-08T08:00:00", role, "app", "m$id")

    @Test
    fun chat_merge_dedups_by_id() {
        val first = listOf(e(1), e(2, "kairos"), e(3))
        val next = listOf(e(3), e(4, "kairos"), e(5))
        val merged = mergeChat(first, next)
        assertEquals(listOf(1L, 2, 3, 4, 5), merged.map { it.id })
        // Une page plus ancienne fusionnée après coup reprend sa place.
        assertEquals(listOf(0L, 1, 2, 3, 4, 5), mergeChat(merged, listOf(e(0), e(1))).map { it.id })
        assertEquals(merged, mergeChat(merged, emptyList()))
    }

    @Test
    fun failed_send_says_why_on_the_bubble() {
        assertEquals("Non envoyé : serveur injoignable", failedNote(app.kairos.mobile.data.ApiError.Offline))
        // Sans réponse, le message a pu partir : renvoyer est un choix éclairé.
        assertEquals("Envoi incertain : vérifie avant de renvoyer", failedNote(app.kairos.mobile.data.ApiError.NoReply))
        assertEquals("Non envoyé : Kairos a trop à faire", failedNote(app.kairos.mobile.data.ApiError.Server(503, "x")))
        assertEquals("Non envoyé : refusé", failedNote(app.kairos.mobile.data.ApiError.Server(400, "refusé")))
    }
}
