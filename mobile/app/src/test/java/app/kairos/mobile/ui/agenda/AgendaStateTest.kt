package app.kairos.mobile.ui.agenda

import app.kairos.mobile.data.AgendaDay
import app.kairos.mobile.data.AgendaEvent
import app.kairos.mobile.data.AgendaSnapshot
import app.kairos.mobile.data.AgendaSync
import app.kairos.mobile.data.AgendaWeek
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Upcoming
import app.kairos.mobile.ui.kit.BlockKind
import app.kairos.mobile.ui.today.InFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AgendaStateTest {
    private val today = LocalDate.of(2026, 11, 8)

    @Test
    fun day_strip_seven_days() {
        val strip = dayStrip(today)
        assertEquals(7, strip.size)
        assertEquals(today, strip.first())
        assertEquals(LocalDate.of(2026, 11, 14), strip.last())
        assertEquals((0L..6L).map { today.plusDays(it) }, strip)
    }

    @Test
    fun swipe_changes_day_not_from_edge() {
        val strip = dayStrip(today)
        val day = today.plusDays(2)
        fun swipe(startX: Float, dx: Float, on: LocalDate = day) =
            swipeDay(on, strip, startX = startX, dx = dx, width = 1000f, edge = 48f, threshold = 120f)
        // Du milieu : ← jour suivant, → jour précédent.
        assertEquals(today.plusDays(3), swipe(500f, -200f))
        assertEquals(today.plusDays(1), swipe(500f, 200f))
        // Trop court : rien.
        assertEquals(day, swipe(500f, -60f))
        // Depuis les bords (zone des gestes système) : jamais.
        assertEquals(day, swipe(10f, 300f))
        assertEquals(day, swipe(990f, -300f))
        // Bornes du bandeau : on reste dessus.
        assertEquals(today, swipe(500f, 200f, on = today))
        assertEquals(strip.last(), swipe(500f, -200f, on = strip.last()))
    }

    private val valid = AddForm(title = "Dentiste", day = today.plusDays(1), start = LocalTime.of(9, 30), span = Span.H1, calendar = Cal.Travail)

    @Test
    fun add_form_validation() {
        assertNull(formError(valid))
        assertNotNull("titre vide", formError(valid.copy(title = "   ")))
        assertNotNull("titre trop long", formError(valid.copy(title = "x".repeat(201))))
        assertNotNull("durée absente", formError(valid.copy(span = null)))
        assertNotNull("heure absente", formError(valid.copy(start = null)))
        // Journée entière : pas d'heure demandée.
        assertNull(formError(valid.copy(span = Span.Day, start = null)))

        val args = addArgs(valid)
        assertEquals("Dentiste", args["title"]!!.jsonPrimitive.content)
        assertEquals("2026-11-09", args["day"]!!.jsonPrimitive.content)
        assertEquals("09:30", args["start"]!!.jsonPrimitive.content)
        assertEquals("60", args["minutes"]!!.jsonPrimitive.content)
        assertEquals("travail", args["calendar"]!!.jsonPrimitive.content)
        val allDay = addArgs(valid.copy(span = Span.Day))
        assertEquals("true", allDay["all_day"]!!.jsonPrimitive.content)
        assertNull(allDay["start"])
        assertNull(allDay["minutes"])
    }

    @Test
    fun add_once() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val busy = InFlight()
        val call: suspend (JsonObject) -> String = { calls++; gate.await(); "Événement ajouté." }
        val first = async(start = CoroutineStart.UNDISPATCHED) { submitEvent(valid, busy, call) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { submitEvent(valid, busy, call) }
        assertEquals(AddOutcome.Ignored, second.await())
        gate.complete(Unit)
        assertEquals(AddOutcome.Added("Événement ajouté."), first.await())
        assertEquals(1, calls)
        // Formulaire invalide : rien ne part.
        assertTrue(submitEvent(valid.copy(title = ""), busy, call) is AddOutcome.Invalid)
        assertEquals(1, calls)
        // Refus du module : raison affichée, le panneau reste ouvert.
        val refused = submitEvent(valid, busy) { throw ApiError.Server(422, "agenda travail non configuré") }
        assertEquals(AddOutcome.Refused("agenda travail non configuré"), refused)
    }

    @Test
    fun offline_keeps_last_day() {
        val d = AgendaDay(day = "2026-11-09", events = listOf(AgendaEvent(uid = "a", title = "Dentiste", start = "09:30", end = "10:30")))
        val week = AgendaWeek(upcoming = listOf(Upcoming(uid = "a", title = "Dentiste", day = "2026-11-09", start = "09:30")))
        val fresh = agendaAfter(AgendaSnapshot(), Result.success(d), Result.success(week), AgendaSync(true, "2026-11-08T08:00:00+01:00"), "2026-11-08T08:00", today)
        assertEquals(d, fresh.days["2026-11-09"])
        assertNull(fresh.issue)
        assertNull(syncBanner(fresh))

        // Hors ligne : le jour et la semaine restent, avec la raison et l'heure du dernier état.
        val down = agendaAfter(fresh, Result.failure(ApiError.Offline), Result.failure(ApiError.Offline), null, "2026-11-08T09:00", today)
        assertEquals(d, down.days["2026-11-09"])
        assertEquals(week, down.week)
        assertEquals("Hors ligne", down.issue)
        assertEquals("2026-11-08T08:00", down.fetchedAt)

        // Synchronisation impossible : base locale lue, bandeau avec la dernière synchro réussie.
        val stale = agendaAfter(fresh, Result.success(d), Result.success(week), AgendaSync(false, "2026-11-08T08:00:00+01:00"), "2026-11-08T10:00", today)
        assertEquals("Agenda pas à jour — dernière synchro 08/11 à 08:00", syncBanner(stale))

        // Les jours passés sortent du cache.
        val later = agendaAfter(fresh, Result.failure(ApiError.Offline), null, null, "2026-11-10T08:00", LocalDate.of(2026, 11, 10))
        assertTrue(later.days.isEmpty())
    }

    @Test
    fun events_become_blocks() {
        val blocks = blocksOf(
            listOf(
                AgendaEvent(uid = "1", title = "Réunion", start = "09:00", end = "10:30", calendar = "travail", location = "Salle B"),
                AgendaEvent(uid = "2", title = "Voyage", end = "11:00"),
                AgendaEvent(uid = "3", title = "Concert", start = "21:00"),
                AgendaEvent(uid = "4", title = "Congé", allDay = true),
                AgendaEvent(uid = "5", title = "Séminaire"),
            ),
        )
        assertEquals(listOf(540, 0, 1260), blocks.filter { !it.allDay }.map { it.start })
        assertEquals(listOf(630, 660, 1440), blocks.filter { !it.allDay }.map { it.end })
        assertEquals(BlockKind.Secondary, blocks[0].kind)
        assertEquals(BlockKind.Primary, blocks[1].kind)
        assertEquals("Salle B", blocks[0].subtitle)
        assertEquals("jusqu'à 11 h", blocks[1].subtitle)
        assertEquals("dès 21 h", blocks[2].subtitle)
        assertEquals(listOf("Congé", "Séminaire"), blocks.filter { it.allDay }.map { it.title })
    }
}
