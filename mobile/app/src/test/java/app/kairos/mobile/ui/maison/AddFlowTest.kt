package app.kairos.mobile.ui.maison

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Product
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AddFlowTest {
    private val riz = Product(true, "Riz", location = "placard", unit = "g", qty = 1000.0)
    private val pomme = Product(true, "Pommes", location = "corbeille", unit = "pièce", qty = 6.0)

    @Test
    fun quantity_asked_only_outside_pieces() {
        assertTrue(needsQuantity(riz))
        assertFalse(needsQuantity(pomme))
        assertFalse(needsQuantity(null))
    }

    @Test
    fun add_sends_quantity_and_unit() {
        val a = addArgs("Riz", "3017620422003", riz, 750.0, "placard", LocalDate.of(2027, 1, 2))
        assertEquals(listOf("Riz", "3017620422003", "750.0", "g", "placard", "2027-01-02"),
            listOf("name", "barcode", "qty", "unit", "location", "expiry").map { a[it]!!.jsonPrimitive.content })
        // Pièces : une à la fois, sans question.
        val p = addArgs("Pommes", null, pomme, null, "corbeille", null)
        assertEquals("1.0" to "pièce", p["qty"]!!.jsonPrimitive.content to p["unit"]!!.jsonPrimitive.content)
        assertNull(p["barcode"])
        // Produit inconnu : une pièce.
        assertEquals("pièce", addArgs("Pain", null, null, null, "corbeille", null)["unit"]!!.jsonPrimitive.content)
    }

    @Test
    fun quantity_input() {
        assertEquals(1.5, parseQty(" 1,5 ")!!, 0.0)
        assertEquals(500.0, parseQty("500")!!, 0.0)
        assertNull(parseQty("0"))
        assertNull(parseQty("-2"))
        assertNull(parseQty("abc"))
        assertEquals("1000", qtyText(1000.0))
        assertEquals("1.5", qtyText(1.5))
    }

    @Test
    fun failures_say_what_happened() {
        assertEquals(LOOKUP_FAILED, addFailure(ApiError.Server(422, "base produits injoignable"), LOOKUP_FAILED))
        assertEquals(LOOKUP_FAILED, addFailure(ApiError.NoReply, LOOKUP_FAILED))
        assertEquals("Peut-être pas enregistré — vérifie avant de réessayer.", addFailure(ApiError.NoReply))
        assertEquals("Riz est compté en masse.", addFailure(ApiError.Server(422, "Riz est compté en masse.")))
        assertEquals("Serveur à mettre à jour.", addFailure(ApiError.Server(404, "Erreur du serveur (404)", bare = true)))
        assertEquals("Module indisponible, réessaie plus tard.", addFailure(ApiError.Server(503, "x"), LOOKUP_FAILED))
        assertNull(addFailure(ApiError.Unauthorized))
    }
}
