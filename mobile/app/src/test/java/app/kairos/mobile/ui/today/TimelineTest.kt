package app.kairos.mobile.ui.today

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.Mark
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class TimelineTest {
    private fun card(id: String, time: String?) = Card(id = id, title = id, time = time)

    private fun ids(items: List<TimelineItem>) = items.map { it.card.id }

    @Test
    fun chronological_order_with_untimed_last() {
        val cards = listOf(card("soir", "19:00"), card("libre", null), card("matin", "08:00"), card("midi", "12:15"))
        val items = timeline(cards, emptyMap(), LocalTime.of(14, 0))
        assertEquals(listOf("matin", "midi", "soir", "libre"), ids(items))
        assertEquals(listOf(Phase.Passe, Phase.Maintenant, Phase.Ensuite, Phase.Ensuite), items.map { it.phase })
    }

    @Test
    fun before_first_item_the_next_one_is_current() {
        val items = timeline(listOf(card("midi", "12:15"), card("soir", "19:00")), emptyMap(), LocalTime.of(8, 12))
        assertEquals(listOf(Phase.Maintenant, Phase.Ensuite), items.map { it.phase })
    }

    @Test
    fun marking_one_task_never_moves_any_card() {
        val cards = listOf(card("matin", "08:00"), card("midi", "12:15"), card("soir", "19:00"), card("libre", null))
        val before = ids(timeline(cards, emptyMap(), LocalTime.of(13, 0)))
        val after = timeline(cards, mapOf("/midi" to Mark.Fait), LocalTime.of(13, 0))
        assertEquals(before, ids(after))
        assertEquals(Phase.Fait, after[1].phase)
    }

    @Test
    fun marking_two_tasks_keeps_order_and_moves_now_forward() {
        val cards = listOf(card("matin", "08:00"), card("midi", "12:15"), card("soir", "19:00"), card("box", "20:00"), card("libre", null))
        val items = timeline(cards, mapOf("/midi" to Mark.Fait, "/soir" to Mark.PlusTard), LocalTime.of(13, 0))
        assertEquals(listOf("matin", "midi", "soir", "box", "libre"), ids(items))
        // L'élément en cours est fait : « maintenant » passe au prochain ouvert, jamais à un élément passé.
        assertEquals(listOf(Phase.Passe, Phase.Fait, Phase.PlusTard, Phase.Maintenant, Phase.Ensuite), items.map { it.phase })
    }

    @Test
    fun done_current_does_not_promote_a_past_item() {
        val cards = listOf(card("matin", "08:00"), card("midi", "12:15"))
        val items = timeline(cards, mapOf("/midi" to Mark.Fait), LocalTime.of(14, 0))
        assertEquals(listOf(Phase.Passe, Phase.Fait), items.map { it.phase })
    }

    @Test
    fun marks_are_scoped_by_module() {
        val a = Card(id = "1", title = "a", module = "agenda")
        val b = Card(id = "1", title = "b", module = "alimentation")
        assertEquals("agenda/1", a.key)
        val items = timeline(listOf(a, b), mapOf(a.key to Mark.Fait), LocalTime.of(9, 0))
        assertEquals(listOf(Phase.Fait, Phase.Ensuite), items.map { it.phase })
        assertEquals("a", items.first().card.title)
    }

    @Test
    fun card_in_flight_and_after_confirmation_keep_their_place() {
        val cards = listOf(card("midi", "12:15"), card("soir", "19:00"), card("libre", null))
        val inFlight = timeline(cards, mapOf("/midi" to Mark.Fait), LocalTime.of(13, 0), pending = setOf("/midi"))
        val confirmed = timeline(cards, mapOf("/midi" to Mark.Fait), LocalTime.of(13, 0))
        val refused = timeline(cards, emptyMap(), LocalTime.of(13, 0))
        assertEquals(listOf("midi", "soir", "libre"), ids(inFlight))
        assertEquals(ids(inFlight), ids(confirmed))
        assertEquals(ids(inFlight), ids(refused))
        assertEquals(Phase.Fait, confirmed.first().phase)
    }

    @Test
    fun current_index_points_at_now() {
        val cards = listOf(card("matin", "08:00"), card("midi", "12:15"), card("soir", "19:00"))
        assertEquals(1, currentIndex(timeline(cards, emptyMap(), LocalTime.of(13, 0))))
        assertEquals(-1, currentIndex(timeline(cards, mapOf("/matin" to Mark.Fait, "/midi" to Mark.Fait, "/soir" to Mark.Fait), LocalTime.of(20, 0))))
    }

    @Test
    fun greeting_follows_hour() {
        assertEquals("Bonjour", greeting(LocalTime.of(8, 0)))
        assertEquals("Bon après-midi", greeting(LocalTime.of(14, 0)))
        assertEquals("Bonsoir", greeting(LocalTime.of(22, 41)))
    }

    @Test
    fun module_done_state_shows_done_without_local_mark() {
        val midi = Card(id = "midi", title = "midi", time = "12:15", state = "fait")
        val soir = card("soir", "19:00")
        val items = timeline(listOf(midi, soir), emptyMap(), LocalTime.of(13, 0))
        assertEquals(listOf(Phase.Fait, Phase.Maintenant), items.map { it.phase })
        assertEquals(Mark.Fait, midi.markIn(emptyMap()))
    }
}
