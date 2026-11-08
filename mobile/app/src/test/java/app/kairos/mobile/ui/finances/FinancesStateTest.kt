package app.kairos.mobile.ui.finances

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Envelope
import app.kairos.mobile.data.FinanceCycle
import app.kairos.mobile.data.FinanceWealth
import app.kairos.mobile.data.FinancesSnapshot
import app.kairos.mobile.data.KairosJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FinancesStateTest {
    @Test
    fun afford_input_parsing() {
        assertEquals(12.5, parseAmount("12,50")!!, 1e-9)
        assertEquals(12.5, parseAmount("12.5")!!, 1e-9)
        assertEquals(1200.0, parseAmount(" 1 200 ")!!, 1e-9)
        assertEquals(40.0, parseAmount("40 €")!!, 1e-9)
        for (bad in listOf("", "   ", "-5", "0", "0,00", "abc", "12,5,0", "1,234", "NaN", "Infinity", "1e3", "2000000000")) {
            assertNull("« $bad » accepté", parseAmount(bad))
        }
    }

    @Test
    fun offline_keeps_last_state() {
        val cycle = FinanceCycle(available = 420.0, envelopes = listOf(Envelope("courses", 300.0, 120.0)))
        val wealth = FinanceWealth(netWorth = 10_000.0)
        val fresh = financesAfter(FinancesSnapshot(), Result.success(cycle), Result.success(wealth), "2026-11-08T08:00")
        assertFalse(fresh.offline)
        assertEquals(cycle, fresh.cycle)

        // Serveur injoignable : tout est gardé, marqué hors ligne.
        val down = financesAfter(fresh, Result.failure(ApiError.Offline), Result.failure(ApiError.Offline), "2026-11-08T09:00")
        assertTrue(down.offline)
        assertEquals(cycle, down.cycle)
        assertEquals(wealth, down.wealth)
        assertEquals("2026-11-08T08:00", down.fetchedAt)
        assertNull(down.reason)

        // Un seul outil en échec : l'autre est rafraîchi, le reste gardé, raison donnée.
        val newer = wealth.copy(netWorth = 11_000.0)
        val partial = financesAfter(fresh, Result.failure(ApiError.Server(502, "x")), Result.success(newer), "2026-11-08T10:00")
        assertTrue(partial.offline)
        assertEquals(cycle, partial.cycle)
        assertEquals(newer, partial.wealth)
        assertEquals("Module indisponible", partial.reason)
    }

    @Test
    fun envelopes_sorted_by_pressure() {
        val sorted = envelopesByPressure(
            listOf(
                Envelope("loisirs", 100.0, 20.0),
                Envelope("courses", 300.0, 290.0),
                Envelope("imprévu", 0.0, 15.0),
                Envelope("transport", 100.0, 120.0),
                Envelope("vide", 0.0, 0.0),
            ),
        )
        assertEquals(listOf("imprévu", "transport", "courses", "loisirs", "vide"), sorted.map { it.poste })
    }

    @Test
    fun missing_sections_hidden() {
        // Alertes omises (source indisponible), listes vides : sections cachées.
        val cycle = KairosJson.decodeFromString(
            FinanceCycle.serializer(),
            """{"start":"2026-11-01","end":"2026-11-30","today":"2026-11-08","days_left":22,"available":420.5,"balance":900,
               "envelopes":[{"poste":"courses","planned":300,"spent":120}],"charges":[],"cushion":{"balance":1000,"floor":500},
               "postes":["courses"],"freshness":{"stale":false,"consent_expired":false}}""",
        )
        assertEquals(listOf(CycleSection.Summary, CycleSection.Envelopes, CycleSection.Cushion), cycleSections(cycle))

        // Patrimoine : seul le portefeuille a répondu, poids de catégorie inconnu.
        val wealth = KairosJson.decodeFromString(
            FinanceWealth.serializer(),
            """{"portfolio":{"buckets":[{"name":"high","amount":800},{"name":"low","weight":0.2,"amount":200}],"lines":[]}}""",
        )
        assertNull(wealth.portfolio!!.buckets[0].weight)
        assertEquals(listOf(WealthSection.Portfolio), wealthSections(wealth))
        assertEquals(emptyList<WealthSection>(), wealthSections(FinanceWealth()))

        val full = KairosJson.decodeFromString(
            FinanceWealth.serializer(),
            """{"net_worth":5000,"debt":2000,"history":[{"month":"2026-10","net_worth":4800}],
               "loans":[{"name":"Prêt","balance":2000}],"freshness":{"stale":true}}""",
        )
        assertEquals(listOf(WealthSection.NetWorth, WealthSection.History, WealthSection.Loans), wealthSections(full))
    }

    @Test
    fun labels_progress_and_masking() {
        assertEquals("Dynamique", bucketLabel("high"))
        assertEquals("Équilibré", bucketLabel("mid"))
        assertEquals("Prudent", bucketLabel("low"))
        assertEquals("autre", bucketLabel("autre"))

        assertEquals(0.25f, cycleProgress("2026-11-01", "2026-11-09", LocalDate.of(2026, 11, 3))!!, 1e-6f)
        assertEquals(1f, cycleProgress("2026-11-01", "2026-11-09", LocalDate.of(2026, 12, 1))!!, 1e-6f)
        assertNull(cycleProgress(null, "2026-11-09", LocalDate.of(2026, 11, 3)))

        // Texte libre du module : masqué, plus aucun chiffre.
        val t = maskDigits("Enveloppe dépassée de 45,20 € le 12/11", masked = true)
        assertFalse(t, t.any { it.isDigit() })
        assertEquals("Rien à signaler", maskDigits("Rien à signaler", masked = true))
        assertEquals("45 €", maskDigits("45 €", masked = false))
    }
}
