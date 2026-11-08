package app.kairos.mobile.ui.maison

import app.kairos.mobile.data.Order
import app.kairos.mobile.data.OrderLine
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class UnpackStateTest {
    private val lait = OrderLine(0, "Lait", location = "placard", done = true)
    private val riz = OrderLine(1, "Riz", location = "placard")
    private val yaourts = OrderLine(2, "Yaourts", location = "frigo", fresh = true)
    private val pain = OrderLine(3, "Pain", location = "corbeille")
    private val order = Order(7, "drive", lines = listOf(lait, riz, yaourts, pain))

    @Test
    fun next_order_cycles_through_pending() {
        fun o(id: Long, done: Boolean = false) = Order(id, lines = listOf(OrderLine(0, "Pain", done = done)))
        val all = listOf(o(9), o(4), o(6), o(5, done = true))
        assertEquals(listOf(6L, 9L, 4L), listOf(4L, 6L, 9L).map { UnpackState.nextOrder(all, it)?.id })
        assertNull(UnpackState.nextOrder(listOf(o(4)), 4))
    }

    @Test
    fun proposed_zone_first() {
        val s = UnpackState(order)
        // Les lignes déjà traitées ne reviennent pas ; la zone habituelle est proposée.
        assertEquals(riz, s.current)
        assertEquals("placard", s.proposed)
        assertEquals(1 to 4, s.done to s.total)
        // Valider sans choisir = la zone proposée.
        val stow = s.place()
        assertNotNull(stow)
        assertEquals("placard", stow!!.location)
        assertEquals(yaourts, s.current)
        assertEquals(2, s.done)
        // Une autre tuile : rangé là.
        assertEquals("congelateur", UnpackState(order).place("congelateur")!!.location)
        // Zone inconnue : refusée, rien ne bouge.
        val t = UnpackState(order)
        assertNull(t.place("cave"))
        assertEquals(riz, t.current)
    }

    @Test
    fun fresh_line_requires_date_step() {
        val s = UnpackState(order)
        s.place()
        // Frais : la zone choisie mène à l'étape date, rien ne part encore.
        assertNull(s.place("frigo"))
        assertEquals(UnpackState.Step.Date, s.step)
        assertEquals(yaourts, s.current)
        // Retour à la zone, puis date confirmée.
        s.back()
        assertEquals(UnpackState.Step.Zone, s.step)
        s.place("frigo")
        val d = LocalDate.of(2026, 11, 20)
        val stow = s.date(d)!!
        assertEquals("frigo" to d, stow.location to stow.expiry)
        assertEquals("2026-11-20", stow.args["expiry"]!!.jsonPrimitive.content)
        assertEquals(UnpackState.Step.Zone, s.step)
        assertEquals(pain, s.current)
        // « Pas de date » est une réponse valable.
        val t = UnpackState(order)
        t.place()
        t.place()
        assertNull(t.date(null)!!.expiry)
    }

    @Test
    fun zones_loaded_after_opening_are_accepted() {
        val lessive = OrderLine(4, "Lessive", location = "evier")
        val s = UnpackState(Order(8, "drive", lines = listOf(lessive)))
        // Ouvert avant que le serveur ait donné ses zones : « sous l'évier » inconnu.
        assertNull(s.proposed)
        assertNull(s.place("evier"))
        s.zones = ZONES + ("evier" to "Sous l'évier")
        assertEquals("evier", s.proposed)
        assertEquals("evier", s.place()!!.location)
    }

    @Test
    fun missing_line_skips() {
        val s = UnpackState(order)
        val stow = s.missing()!!
        assertTrue(stow.missing)
        assertEquals("true", stow.args["missing"]!!.jsonPrimitive.content)
        assertNull(stow.args["location"])
        assertEquals(yaourts, s.current)
        // Rupture possible aussi depuis l'étape date.
        s.place()
        assertTrue(s.missing()!!.missing)
        assertEquals(pain, s.current)
        assertEquals(UnpackState.Step.Zone, s.step)
    }

    @Test
    fun done_when_all_lines_handled() {
        val s = UnpackState(order)
        assertFalse(s.finished)
        s.place()
        s.place()
        s.date(null)
        s.place()
        assertTrue(s.finished)
        assertNull(s.current)
        assertEquals(4 to 4, s.done to s.total)
        assertNull(s.place())
        assertNull(s.missing())
        // Commande déjà entièrement traitée : fini d'emblée.
        assertTrue(UnpackState(Order(8, lines = listOf(lait))).finished)
    }

    @Test
    fun refusal_puts_line_back() {
        val s = UnpackState(order)
        val first = s.place()!!
        s.missing()
        assertTrue(s.dispatch(first))
        s.refused(first, "Riz : zone inconnue.")
        // La ligne refusée repasse en tête, avec la raison.
        assertEquals(riz, s.current)
        assertEquals("Riz : zone inconnue.", s.message)
        assertEquals(2, s.done)
        // Ranger à nouveau reprend le fil.
        assertTrue(s.dispatch(s.place()!!))
        assertEquals(pain, s.current)
        assertFalse(s.canUndo)
    }

    @Test
    fun undo_only_before_dispatch() {
        val s = UnpackState(order)
        val stow = s.place()!!
        assertTrue(s.canUndo)
        assertTrue(s.undo())
        assertEquals(riz, s.current)
        assertFalse(s.dispatch(stow))
        val next = s.place()!!
        assertTrue(s.dispatch(next))
        assertFalse(s.undo())
        // Fermer rend ce qui n'est pas parti.
        val last = s.missing()!!
        assertEquals(listOf(last), s.flush())
        assertFalse(s.undo())
    }

    @Test
    fun already_handled_counts_as_done() {
        // Double envoi, autre appareil : la ligne est faite côté serveur, rien ne revient.
        for (reason in listOf("Riz : déjà traité.", "Commande 7 : pas à ranger.")) {
            val s = UnpackState(order)
            val first = s.place()!!
            s.dispatch(first)
            assertTrue(UnpackState.alreadyHandled(reason))
            s.refused(first, reason)
            assertEquals(yaourts, s.current)
            assertEquals(2, s.done)
            assertNull(s.message)
        }
        assertFalse(UnpackState.alreadyHandled("Serveur injoignable."))
    }

    @Test
    fun refusal_keeps_date_step_in_progress() {
        val s = UnpackState(order)
        val first = s.place()!!
        s.dispatch(first)
        s.place("frigo")
        assertEquals(UnpackState.Step.Date, s.step)
        s.refused(first, "Riz : zone inconnue.")
        // L'étape date en cours reste ; la ligne refusée passe juste après.
        assertEquals(UnpackState.Step.Date, s.step)
        assertEquals(yaourts, s.current)
        assertEquals("frigo", s.zone)
        assertEquals(riz, s.next)
        assertEquals("Riz : zone inconnue.", s.message)
        s.date(null)
        assertEquals(riz, s.current)
    }
}
