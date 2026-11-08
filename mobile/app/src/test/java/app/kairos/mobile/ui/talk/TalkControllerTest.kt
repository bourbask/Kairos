package app.kairos.mobile.ui.talk

import app.kairos.mobile.ui.mascot.MascotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkControllerTest {

    private fun controller() = TalkController(density = 1f)

    @Test
    fun long_press_starts_recording() {
        val c = controller()
        val effects = c.pressStart()
        assertEquals(TalkMode.Recording, c.mode)
        assertTrue(TalkEffect.StartListening in effects)
    }

    @Test
    fun slide_up_locks() {
        val c = controller()
        c.pressStart()
        assertTrue(c.drag(0f, -60f).isEmpty())
        assertEquals(TalkMode.Recording, c.mode)
        val effects = c.drag(-10f, -90f)
        assertEquals(TalkMode.Locked, c.mode)
        assertTrue(TalkEffect.Threshold in effects)
        // Relâcher après verrouillage n'envoie rien : on reste en mains libres.
        c.onPartial("ajoute du lait")
        assertTrue(c.pressEnd().none { it is TalkEffect.Send })
        assertEquals(TalkMode.Locked, c.mode)
        val sent = c.send()
        assertTrue(TalkEffect.CancelListening in sent)
        assertEquals(TalkEffect.Send("ajoute du lait", handsFree = true), sent.last())
        assertEquals(TalkMode.Idle, c.mode)
    }

    @Test
    fun slide_left_cancels() {
        val c = controller()
        c.pressStart()
        c.onPartial("oublie ça")
        val effects = c.drag(-110f, 5f)
        assertEquals(TalkMode.Cancelled, c.mode)
        assertTrue(TalkEffect.CancelListening in effects)
        assertTrue(TalkEffect.Threshold in effects)
        assertTrue(c.drag(0f, -200f).isEmpty())
        assertTrue(c.pressEnd().none { it is TalkEffect.Send })
        c.onFinal("oublie ça")
        assertEquals(TalkMode.Idle, c.mode)
    }

    @Test
    fun release_sends_transcript() {
        val c = controller()
        c.pressStart()
        c.onPartial("j'ai fini")
        assertTrue(TalkEffect.StopListening in c.pressEnd())
        assertEquals(listOf(TalkEffect.Send("j'ai fini la soupe", handsFree = false)), c.onFinal("j'ai fini la soupe"))
        assertEquals(TalkMode.Idle, c.mode)
    }

    @Test
    fun long_dictation_keeps_listening_while_held() {
        val c = controller()
        c.pressStart()
        assertEquals(listOf(TalkEffect.StartListening), c.onFinal("j'ai fini la soupe"))
        c.onPartial("ajoute du lait")
        c.pressEnd()
        assertEquals(
            listOf(TalkEffect.Send("j'ai fini la soupe ajoute du lait", handsFree = false)),
            c.onFinal("ajoute du lait"),
        )
    }

    @Test
    fun empty_transcript_not_sent() {
        val c = controller()
        c.pressStart()
        c.pressEnd()
        assertTrue(c.onFinal("  ").isEmpty())
        assertEquals(TalkMode.Idle, c.mode)
        c.pressStart()
        c.pressEnd()
        assertTrue(c.onError(retry = true).isEmpty())
        assertEquals(TalkMode.Idle, c.mode)
    }

    @Test
    fun recognizer_failure_falls_back_to_editable_text() {
        val c = controller()
        c.pressStart()
        c.onPartial("ajoute")
        c.onError(retry = false)
        assertEquals(TalkMode.Locked, c.mode)
        c.edit("ajoute du lait")
        assertEquals(listOf(TalkEffect.Send("ajoute du lait", handsFree = true)), c.send())
    }

    @Test
    fun mascot_reflects_state() {
        assertEquals(MascotState.Ecoute, mascotFor(TalkMode.Recording, busy = true, decisions = 3, unread = true))
        assertEquals(MascotState.Reflechit, mascotFor(TalkMode.Idle, busy = true, decisions = 3, unread = false))
        assertEquals(MascotState.Agit, mascotFor(TalkMode.Idle, busy = false, decisions = 3, unread = true))
        assertEquals(MascotState.Attend, mascotFor(TalkMode.Idle, busy = false, decisions = 3, unread = false))
        assertEquals(MascotState.Repos, mascotFor(TalkMode.Idle, busy = false, decisions = 0, unread = false))
    }

    @Test
    fun empty_final_after_release_keeps_partial() {
        val c = controller()
        c.pressStart()
        c.onPartial("ajoute du lait")
        c.pressEnd()
        assertEquals(listOf(TalkEffect.Send("ajoute du lait", handsFree = false)), c.onFinal(""))
    }
}
