package app.kairos.mobile.ui.decide

import app.kairos.mobile.data.Decision
import app.kairos.mobile.data.DecisionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckStateTest {

    private fun card(id: String, proposal: String? = "Alimentation", expires: String = "2026-12-06") = Decision(
        id = id,
        title = "Dépense à classer",
        module = "finances",
        proposal = proposal?.let { DecisionOption(it, it) },
        alternatives = listOf(DecisionOption("Loisirs", "Loisirs"), DecisionOption("Maison", "Maison")),
        expires = expires,
    )

    @Test
    fun swipe_right_accepts_proposal() {
        val deck = DeckState(listOf(card("a"), card("b")))
        deck.swipeRight()
        val sent = deck.outbox.single()
        assertEquals(card("a") to "Alimentation", sent.card to sent.option)
        assertEquals("b", deck.current?.id)
        assertEquals(1, deck.done)
        assertFalse(deck.alternativesOpen)
    }

    @Test
    fun swipe_right_without_proposal_opens_alternatives() {
        val deck = DeckState(listOf(card("a", proposal = null)))
        deck.swipeRight()
        assertTrue(deck.alternativesOpen)
        assertEquals("a", deck.current?.id)
        assertTrue(deck.outbox.isEmpty())
        deck.choose("Loisirs")
        assertEquals("Loisirs", deck.outbox.single().option)
        assertTrue(deck.finished)
    }

    @Test
    fun swipe_left_opens_alternatives() {
        val deck = DeckState(listOf(card("a")))
        deck.swipeLeft()
        assertTrue(deck.alternativesOpen)
        assertEquals("a", deck.current?.id)
    }

    @Test
    fun later_moves_card_to_end_keeps_expiry() {
        val deck = DeckState(listOf(card("a", expires = "2026-11-20"), card("b")))
        deck.later()
        assertEquals(listOf("b", "a"), deck.cards.map { it.id })
        assertEquals("2026-11-20", deck.cards.last().expires)
        assertEquals(0, deck.done)
        assertTrue(deck.outbox.isEmpty())
    }

    @Test
    fun undo_restores_card() {
        val deck = DeckState(listOf(card("a"), card("b")))
        deck.swipeRight()
        assertTrue(deck.undo())
        assertEquals(listOf("a", "b"), deck.cards.map { it.id })
        assertTrue(deck.outbox.isEmpty())
        assertEquals(0, deck.done)

        deck.later()
        assertTrue(deck.undo())
        assertEquals(listOf("a", "b"), deck.cards.map { it.id })
        assertFalse(deck.undo())
    }

    @Test
    fun undo_impossible_once_the_call_has_left() {
        val deck = DeckState(listOf(card("a"), card("b")))
        deck.swipeRight()
        val choice = deck.outbox.single()
        assertTrue(deck.dispatch(choice))
        assertFalse(deck.dispatch(choice))
        assertFalse(deck.undo())
        assertEquals("b", deck.current?.id)
    }

    @Test
    fun server_refusal_puts_card_back_with_message() {
        val deck = DeckState(listOf(card("a"), card("b")))
        deck.swipeRight()
        val choice = deck.outbox.single()
        deck.dispatch(choice)
        deck.refused(choice, "Libellé trop générique")
        assertEquals(listOf("a", "b"), deck.cards.map { it.id })
        assertEquals("Libellé trop générique", deck.message)
        assertEquals(0, deck.done)
        deck.swipeLeft()
        deck.choose("Maison")
        assertNull(deck.message)
    }

    @Test
    fun close_flushes_pending_choices() {
        val deck = DeckState(listOf(card("a"), card("b")))
        deck.swipeRight()
        val flushed = deck.close()
        assertEquals(1, flushed.size)
        assertTrue(deck.outbox.isEmpty())
        assertTrue(deck.closed)
    }

    @Test
    fun refusal_then_undo_keeps_refused_card() {
        val deck = DeckState(listOf(card("a"), card("b"), card("c")))
        deck.swipeRight()
        val first = deck.outbox.single()
        deck.dispatch(first)
        deck.swipeRight()
        deck.refused(first, "Refusé")
        assertFalse(deck.undo())
        assertEquals("a", deck.current?.id)
    }

    @Test
    fun rechoose_after_undo_keeps_full_window() {
        val deck = DeckState(listOf(card("a")))
        deck.swipeRight()
        val first = deck.outbox.single()
        deck.undo()
        deck.swipeRight()
        val second = deck.outbox.single()
        // Le minuteur du premier choix, annulé, ne doit pas envoyer le second.
        assertFalse(deck.dispatch(first))
        assertTrue(deck.dispatch(second))
    }

    @Test
    fun swipe_by_distance_or_fast_flick() {
        val t = 100f
        val fast = 1000f
        assertEquals(Swipe.Right, swipeOf(Offset(120f, 10f), Offset.Zero, t, fast))
        assertEquals(Swipe.Up, swipeOf(Offset(5f, -150f), Offset.Zero, t, fast))
        assertNull(swipeOf(Offset(40f, 0f), Offset(300f, 0f), t, fast))
        assertEquals(Swipe.Left, swipeOf(Offset(-30f, 5f), Offset(-2000f, 100f), t, fast))
        assertEquals(Swipe.Down, swipeOf(Offset(0f, 20f), Offset(50f, 1500f), t, fast))
        // Un geste lent qui revient vers le centre n'est pas un lancer.
        assertNull(swipeOf(Offset(30f, 0f), Offset(-2000f, 0f), t, fast))
    }

    @Test
    fun blank_texts_are_not_shown() {
        assertNull(shown(null))
        assertNull(shown("   "))
        assertEquals("⚠ La règle ne les a pas reconnues", shown(" ⚠ La règle ne les a pas reconnues "))
    }
}
