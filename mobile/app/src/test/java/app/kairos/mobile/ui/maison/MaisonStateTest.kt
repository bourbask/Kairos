package app.kairos.mobile.ui.maison

import app.kairos.mobile.data.Inventory
import app.kairos.mobile.data.Lot
import app.kairos.mobile.data.MaisonSnapshot
import app.kairos.mobile.data.Order
import app.kairos.mobile.data.OrderLine
import app.kairos.mobile.data.Tone
import app.kairos.mobile.data.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MaisonStateTest {

    private val champignons = Lot(1, "Champignons", 250.0, "g", "250 g", 50.0, "frigo", "2026-11-09", tone = Tone.Urgent)
    private val soupe = Lot(2, "Soupe carcasse", 2.0, "pièce", "2 pièces", 1.0, "frigo", "2026-11-10", tone = Tone.Attention)
    private val riz = Lot(3, "Riz", 1000.0, "g", "1 kg", 100.0, "placard")

    private fun snapshot(offline: Boolean = false) = MaisonSnapshot(
        inventory = Inventory(
            zones = SERVER_ZONES,
            lots = listOf(champignons, soupe, riz),
        ),
        orders = listOf(
            Order(7, "drive", lines = listOf(OrderLine(0, "Lait", done = true), OrderLine(1, "Œufs"))),
            Order(8, "drive", lines = listOf(OrderLine(0, "Pain", done = true))),
        ),
        fetchedAt = "2026-11-08T08:00",
        offline = offline,
    )

    @Test
    fun zones_from_inventory() {
        val s = MaisonState(snapshot())
        // Les zones du serveur, dans son ordre ; compteurs recalculés sur les lots affichés.
        assertEquals(SERVER_ZONES.map { it.id }, s.zones.map { it.id })
        assertEquals(SERVER_ZONES.map { it.label }, s.zones.map { it.label })
        val frigo = s.zones.first()
        assertEquals(2 to 1, frigo.count to frigo.urgent)
        assertEquals(1, s.zones.first { it.id == "placard" }.count)
        assertEquals(3, s.total)
        // Zone ouverte d'office : celle qui a des urgences ; stock trié comme le serveur l'a rendu.
        assertEquals("frigo", s.zone)
        assertEquals(listOf("Champignons", "Soupe carcasse"), s.lotsIn(s.zone).map { it.name })
        // Seules les commandes qui ont encore des lignes à ranger.
        assertEquals(listOf(7L), s.orders.map { it.id })
    }

    @Test
    fun zones_from_server_layout() {
        val lessive = Lot(4, "Lessive", 1.0, "pièce", "1 pièce", 1.0, "evier")
        val s = MaisonState(snapshot().let { it.copy(inventory = it.inventory.copy(lots = it.inventory.lots + lessive)) })
        assertEquals(7, s.zones.size)
        assertEquals("Sous l'évier" to 1, s.zones.first { it.id == "evier" }.let { it.label to it.count })
        // Les nouvelles zones sont des destinations ; une zone que le serveur ne donne pas, non.
        assertEquals("sdb", (s.move(riz, "sdb") as LotAction.Update).location)
        assertNull(s.move(riz, "garage"))
        // Serveur muet sur les zones (ancien, ou rien en mémoire) : plan par défaut.
        assertEquals(ZONES.map { it.first }, MaisonState(MaisonSnapshot()).zones.map { it.id })
        assertEquals(ZONES, zonesOf(Inventory()))
        assertEquals("sdb" to "Salle de bain", zonesOf(snapshot().inventory).last())
        // Grille : trois colonnes au plus, rangées équilibrées, les plus pleines en haut.
        assertEquals(listOf(3, 2, 2), planRows(7))
        assertEquals(listOf(3, 3, 2), planRows(8))
        assertEquals(listOf(3, 2), planRows(5))
        assertEquals(listOf(3, 3), planRows(6))
        assertEquals(listOf(2, 2), planRows(4))
        assertEquals(listOf(1), planRows(1))
        assertEquals(emptyList<Int>(), planRows(0))
    }

    @Test
    fun banner_shows_latest_order() {
        fun order(id: Long, pickup: String?) = Order(id, "drive", pickup, listOf(OrderLine(0, "Pain")))
        val s = MaisonState(snapshot().copy(orders = listOf(order(4, "2026-11-10"), order(9, "2026-11-08"), order(6, "2026-11-10"))))
        // Retrait le plus tardif, puis la dernière enregistrée ; les autres en nombre.
        assertEquals(6L, s.banner?.id)
        assertEquals(2, s.orders.size - 1)
        assertEquals("retrait 10/11", pickupLabel("2026-11-10"))
        assertNull(pickupLabel(null))
        assertNull(pickupLabel("bientôt"))
        assertNull(MaisonState(snapshot().copy(orders = emptyList())).banner)
    }

    @Test
    fun swipe_left_consumes_after_undo_window() {
        val s = MaisonState(snapshot())
        val use = s.consume(champignons)!!
        assertEquals(listOf("Soupe carcasse"), s.lotsIn("frigo").map { it.name })
        assertEquals(0, s.zones.first().urgent)
        assertTrue(s.canUndo)

        assertTrue(s.undo())
        assertEquals(2, s.lotsIn("frigo").size)
        assertFalse(s.dispatch(use))

        val again = s.consume(champignons)!!
        assertTrue(s.dispatch(again))
        assertFalse(s.canUndo)
        assertFalse(s.undo())
        assertEquals("app_lot_use" to """{"lot":1}""", again.tool to again.args.toString())

        // Confirmé : le lot reste parti, même avant l'actualisation.
        s.confirmed(again)
        assertEquals(1, s.lotsIn("frigo").size)
        // Une actualisation qui le rend encore (serveur en retard) ne le fait pas revenir tant qu'une action est en vol.
        val pending = s.consume(soupe)!!
        s.load(snapshot())
        assertTrue(s.lotsIn("frigo").none { it.id == soupe.id })
        assertTrue(s.dispatch(pending))
    }

    @Test
    fun move_updates_zone_locally_then_server() {
        val s = MaisonState(snapshot())
        val move = s.move(champignons, "congelateur")!!
        assertEquals("congelateur", s.lots.first { it.id == 1L }.location)
        assertEquals(1 to 1, s.zones.first { it.id == "congelateur" }.let { it.count to it.urgent })
        assertEquals(1, s.zones.first().count)
        // Pas de fenêtre d'annulation : part tout de suite.
        assertFalse(s.canUndo)
        assertTrue(s.dispatch(move))
        assertEquals("app_lot_update" to """{"lot":1,"location":"congelateur"}""", move.tool to move.args.toString())
        // Même zone, ou zone inconnue : rien à faire.
        assertNull(s.move(riz, "placard"))
        assertNull(s.move(riz, "garage"))

        s.confirmed(move)
        assertEquals("congelateur", s.lots.first { it.id == 1L }.location)
    }

    @Test
    fun quantity_steps_and_zero_consumes() {
        val s = MaisonState(snapshot())
        val more = s.setQty(riz, 1100.0)!!
        assertEquals("1,1 kg", s.lots.first { it.id == 3L }.label)
        assertEquals("""{"lot":3,"qty":1100.0}""", more.args.toString())
        assertNull(s.setQty(soupe, 2.0))
        assertTrue(s.setQty(soupe, 0.0) is LotAction.Use)
        assertTrue(s.canUndo)
    }

    @Test
    fun server_refusal_restores_lot_with_message() {
        val s = MaisonState(snapshot())
        val move = s.move(riz, "etagere")!!
        s.dispatch(move)
        s.refused(move, "Lot 3 introuvable.")
        assertEquals("placard", s.lots.first { it.id == 3L }.location)
        assertEquals("Lot 3 introuvable.", s.message)

        val use = s.consume(champignons)!!
        s.dispatch(use)
        s.refused(use, "Serveur injoignable : rien n'a été enregistré.")
        assertEquals(listOf("Champignons", "Soupe carcasse"), s.lotsIn("frigo").map { it.name })
        assertEquals("Serveur injoignable : rien n'a été enregistré.", s.message)
        s.consumeMessage()
        assertNull(s.message)
    }

    @Test
    fun maison_offline_disables_actions() {
        val s = MaisonState(snapshot(offline = true))
        assertFalse(s.canAct)
        // Lecture seule avec le dernier état.
        assertEquals(3, s.total)
        assertNull(s.consume(champignons))
        assertNull(s.move(champignons, "placard"))
        assertNull(s.setQty(riz, 900.0))
        assertEquals(3, s.lots.size)

        s.load(snapshot(offline = false))
        assertTrue(s.canAct)
        // Fermer l'écran envoie ce qui attendait la fin de la fenêtre d'annulation.
        val use = s.consume(champignons)!!
        assertEquals(listOf(use), s.flush())
        assertFalse(s.canUndo)
    }

    @Test
    fun confirmed_move_survives_stale_snapshot_until_settled() {
        val s = MaisonState(snapshot())
        val move = s.move(riz, "frigo")!!
        s.dispatch(move)
        s.confirmed(move)
        // Une actualisation partie avant le déplacement (ou un échec qui rend l'ancien état)
        // ne ramène pas le lot : plan et liste restent d'accord.
        s.load(snapshot())
        assertEquals(3 to 0, s.zones.first { it.id == "frigo" }.count to s.zones.first { it.id == "placard" }.count)
        assertEquals(listOf(3L), s.lotsIn("frigo").filter { it.id == 3L }.map { it.id })
        // L'état lu après la confirmation fait foi : l'effet local n'est plus rejoué.
        val after = snapshot().let { it.copy(inventory = it.inventory.copy(lots = listOf(champignons, soupe))) }
        s.settle(after)
        assertEquals(2, s.total)
        assertEquals(0, s.zones.first { it.id == "placard" }.count)
        s.load(snapshot())
        assertEquals("placard", s.lots.first { it.id == 3L }.location)
    }

    @Test
    fun quantity_editor_steps_then_validates() {
        // Pas du serveur, sans dérive flottante, jamais sous zéro.
        assertEquals(1100.0, stepQty(1000.0, 100.0, up = true), 0.0)
        assertEquals(0.3, stepQty(stepQty(stepQty(0.0, 0.1, true), 0.1, true), 0.1, true), 0.0)
        assertEquals(0.0, stepQty(30.0, 50.0, up = false), 0.0)
        assertEquals(2.0, stepQty(1.0, 0.0, up = true), 0.0)
        // « Valider » envoie la quantité (unité de base) par app_lot_update, plan et liste à jour.
        val s = MaisonState(snapshot())
        val v = s.setQty(riz, stepQty(riz.qty, riz.step, up = false))!!
        assertEquals("app_lot_update" to """{"lot":3,"qty":900.0}""", v.tool to v.args.toString())
        assertEquals("900 g", s.lotsIn("placard").single().label)
        assertTrue(s.dispatch(v))
    }

    @Test
    fun readable_quantities_and_dates() {
        assertEquals("950 g", formatQty(950.0, "g"))
        assertEquals("1,25 kg", formatQty(1250.0, "g"))
        assertEquals("1,5 l", formatQty(1500.0, "ml"))
        assertEquals("1 pièce", formatQty(1.0, "pièce"))
        assertEquals("3 pièces", formatQty(3.0, "pièce"))
        val today = LocalDate.of(2026, 11, 8)
        assertEquals("demain", expiryText(champignons, today))
        assertEquals("2 j", expiryText(soupe, today))
        assertEquals("~19/11", expiryText(riz.copy(expiry = "2026-11-19", estimated = true), today))
        assertEquals("passée", expiryText(riz.copy(expiry = "2026-11-01"), today))
        assertNull(expiryText(riz, today))
    }
}

private val SERVER_ZONES = listOf(
    Zone("frigo", "Frigo", 2, 1),
    Zone("congelateur", "Congélo"),
    Zone("placard", "Placard", 1, 0),
    Zone("etagere", "Étagère"),
    Zone("corbeille", "Table"),
    Zone("evier", "Sous l'évier"),
    Zone("sdb", "Salle de bain"),
)
