package app.kairos.mobile.ui.spaces

import app.kairos.mobile.ui.finances.PARTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

class SpacesStateTest {
    @Test
    fun load_state_never_hides_an_error() {
        assertEquals(SpaceLoad.Loading, spaceLoad(hasData = false, issue = null))
        assertEquals(SpaceLoad.Ready, spaceLoad(hasData = true, issue = null))
        // Échec avec un état gardé : l'espace reste indisponible, pas « à jour ».
        assertEquals(SpaceLoad.Down, spaceLoad(hasData = true, issue = "Hors ligne"))
        assertEquals(SpaceLoad.Down, spaceLoad(hasData = false, issue = "Module indisponible"))
        // Réponse partielle : des données, montrées.
        assertEquals(SpaceLoad.Ready, spaceLoad(hasData = true, issue = PARTIAL))
    }

    @Test
    fun worst_issue_wins() {
        assertEquals(null, worstIssue(null, null))
        assertEquals(PARTIAL, worstIssue(PARTIAL, null))
        assertEquals("Hors ligne", worstIssue(PARTIAL, "Hors ligne"))
    }

    @Test
    fun plural_counts() {
        assertEquals("1 dépassée", countLabel(1, "dépassée"))
        assertEquals("3 dépassées", countLabel(3, "dépassée"))
    }

    @Test
    fun agenda_status_never_says_empty_without_the_week() {
        val today = java.time.LocalDate.of(2026, 11, 9)
        // Semaine pas encore lue (jours seuls) : on ne sait pas, on ne dit pas « rien ».
        assertEquals("À venir : pas encore lu", agendaStatus(null, today))
        assertEquals("Rien de prévu", agendaStatus(app.kairos.mobile.data.AgendaWeek(), today))
        val week = app.kairos.mobile.data.AgendaWeek(upcoming = listOf(app.kairos.mobile.data.Upcoming("u", "Dentiste", "2026-11-09", "10:00")))
        assertEquals(true, agendaStatus(week, today).startsWith("Dentiste · "))
    }
}
