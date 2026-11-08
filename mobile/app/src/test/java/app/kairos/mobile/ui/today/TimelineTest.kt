package app.kairos.mobile.ui.today

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.Mark
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class TimelineTest {
    private fun card(id: String, time: String?) = Card(id = id, title = id, time = time)

    @Test
    fun current_item_first_then_marked_at_bottom() {
        val cards = listOf(card("midi", "12:15"), card("soir", "19:00"), card("box", "20:00"), card("libre", null))
        val items = timeline(cards, mapOf("/box" to Mark.Fait), LocalTime.of(13, 0))
        assertEquals(listOf("midi", "soir", "libre", "box"), items.map { it.card.id })
        assertEquals(listOf(Phase.Maintenant, Phase.Ensuite, Phase.Ensuite, Phase.Fait), items.map { it.phase })
    }

    @Test
    fun current_item_leads_and_past_items_follow_upcoming() {
        val cards = listOf(card("matin", "08:00"), card("midi", "12:15"), card("soir", "19:00"), card("libre", null))
        val items = timeline(cards, mapOf("x" to Mark.PlusTard), LocalTime.of(14, 0))
        assertEquals(listOf("midi", "soir", "libre", "matin"), items.map { it.card.id })
        assertEquals(listOf(Phase.Maintenant, Phase.Ensuite, Phase.Ensuite, Phase.Passe), items.map { it.phase })
    }

    @Test
    fun before_first_item_the_next_one_is_current() {
        val items = timeline(listOf(card("midi", "12:15"), card("soir", "19:00")), emptyMap(), LocalTime.of(8, 12))
        assertEquals(listOf(Phase.Maintenant, Phase.Ensuite), items.map { it.phase })
    }

    @Test
    fun marks_are_scoped_by_module() {
        val a = Card(id = "1", title = "a", module = "agenda")
        val b = Card(id = "1", title = "b", module = "alimentation")
        assertEquals("agenda/1", a.key)
        val items = timeline(listOf(a, b), mapOf(a.key to Mark.Fait), LocalTime.of(9, 0))
        assertEquals(listOf(Phase.Ensuite, Phase.Fait), items.map { it.phase })
        assertEquals("b", items.first().card.title)
    }

    @Test
    fun greeting_follows_hour() {
        assertEquals("Bonjour", greeting(LocalTime.of(8, 0)))
        assertEquals("Bon après-midi", greeting(LocalTime.of(14, 0)))
        assertEquals("Bonsoir", greeting(LocalTime.of(22, 41)))
    }
}
