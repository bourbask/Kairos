package app.kairos.mobile.ui.today

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class TodayStateTest {
    private fun card(id: String, time: String?) = Card(id = id, title = id, time = time)

    @Test
    fun earlier_items_fold_before_the_current_one() {
        val cards = listOf(card("a", "08:00"), card("b", "09:00"), card("c", "14:00"), card("libre", null))
        // 9 h est commencé à 10 h : il est « maintenant », seul 8 h est replié.
        assertEquals(1, earlierCount(timeline(cards, emptyMap(), LocalTime.of(10, 0))))
        assertEquals(2, earlierCount(timeline(cards, emptyMap(), LocalTime.of(14, 30))))
        // Rien en cours (tout est marqué) : rien n'est replié, rien ne disparaît.
        val all = mapOf("/a" to Mark.Fait, "/b" to Mark.Fait, "/c" to Mark.PlusTard)
        assertEquals(0, earlierCount(timeline(cards, all, LocalTime.of(22, 0))))
        // L'élément en cours est le premier : rien à replier.
        assertEquals(0, earlierCount(timeline(cards, emptyMap(), LocalTime.of(7, 0))))
    }

    @Test
    fun current_card_says_now_or_how_long_until() {
        assertEquals("maintenant", startsIn("09:00", LocalTime.of(9, 30)))
        assertEquals("maintenant", startsIn("09:30", LocalTime.of(9, 30)))
        assertEquals("dans 25 min", startsIn("09:55", LocalTime.of(9, 30)))
        assertEquals("dans 1 h 05", startsIn("10:35", LocalTime.of(9, 30)))
        assertEquals("dans 2 h", startsIn("11:30", LocalTime.of(9, 30)))
        assertNull(startsIn(null, LocalTime.of(9, 30)))
        assertNull(startsIn("bientôt", LocalTime.of(9, 30)))
    }

    @Test
    fun time_is_spoken_in_words() {
        assertEquals("15 h 30, Rendez-vous", spokenItem("15:30", "Rendez-vous"))
        assertEquals("9 h, Courses", spokenItem("09:00", "Courses"))
        assertEquals("sans heure, Lire", spokenItem(null, "Lire"))
        assertEquals("matin, Lire", spokenItem("matin", "Lire"))
    }

    @Test
    fun decisions_peek_is_spoken_with_its_duration() {
        assertEquals("1 décision en attente, environ 1 minute. Ouvrir.", decisionsSpoken(1))
        assertEquals("13 décisions en attente, environ 3 minutes. Ouvrir.", decisionsSpoken(13))
    }

    @Test
    fun update_banner_changes_text_only_at_start_half_and_end() {
        assertEquals("Kairos 1.2.0 disponible", updateBannerText("1.2.0", null))
        assertEquals("Téléchargement commencé", updateBannerText("1.2.0", 0f))
        assertEquals("Téléchargement commencé", updateBannerText("1.2.0", 0.49f))
        assertEquals("Téléchargement à moitié", updateBannerText("1.2.0", 0.5f))
        assertEquals("Téléchargement à moitié", updateBannerText("1.2.0", 0.99f))
        assertEquals("Installation…", updateBannerText("1.2.0", 1f))
    }
}
