package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.screens.*
import org.junit.Assert.*
import org.junit.Test

class VotesTest {
    private fun s(id: String, ch: String, date: String, vararg v: Verdict) = Scrutin(id = id, chambre = ch, date = date, titre = "T$id", verdicts = v.toList())
    private fun v(qui: String, k: String, base: String? = null) = Verdict(qui = qui, v = k, base = base)

    private val all = listOf(
        s("a", "AN", "2023-01-10", v("p1", "ecart"), v("p2", "coherent")),
        s("b", "AN", "2024-03-01", v("p1", "coherent", "seance"), v("p1", "sans-parole"), v("p2", "ambigu")),
        s("c", "PE", "2022-05-05", v("p1", "ecart", "seance")),
    )

    @Test fun dv_count_separates_bases() {
        val items = all.flatMap { sc -> sc.verdicts.filter { it.qui == "p1" }.map { DvItem(sc, it) } }
        val pub = dvCount(items)
        assertEquals(DvCount(c = 0, a = 0, e = 1, sp = 1, t = 1), pub)
        val seance = dvCount(items, "seance")
        assertEquals(DvCount(c = 1, a = 0, e = 1, sp = 1, t = 2), seance)
    }

    @Test fun dv_rate_zero_when_only_seance() {
        val items = listOf(DvItem(all[1], v("p1", "coherent", "seance")), DvItem(all[2], v("p1", "ecart", "seance")), DvItem(all[1], v("p1", "sans-parole")))
        val pub = dvCount(items)
        assertEquals(0, pub.t); assertEquals(0, pub.c)
        assertEquals(1, pub.sp)
        assertEquals(2, dvCount(items, "seance").t)
    }

    @Test fun dv_gaps_sorted_ecart_first_then_recent() {
        val items = listOf(
            DvItem(all[1], v("p2", "ambigu")), DvItem(all[0], v("p2", "coherent")),
            DvItem(all[0], v("p1", "ecart")), DvItem(all[1], v("p1", "ecart")),
        )
        assertEquals(listOf("b" to "ecart", "a" to "ecart", "b" to "ambigu"), dvGaps(items).map { it.scrutin.id to it.verdict.v })
    }

    @Test fun scrutin_filters_and_parties() {
        assertEquals(listOf("b", "a"), scrutinsFor(all, "AN", "").map { it.id })
        assertEquals(listOf("a"), scrutinsFor(all, "AN", "ecart").map { it.id })
        assertEquals(listOf("b"), scrutinsFor(all, "AN", "ambigu").map { it.id })
        assertEquals(listOf("p1", "p2"), dvParties(scrutinsFor(all, "AN", "")))
    }

    @Test fun position_of_party_by_group_alias() {
        val sc = all[0].copy(positions = mapOf("G1" to listOf("P", "5 pour"), "G2" to listOf("C", "2 contre")))
        val ids = { g: String -> if (g == "G2") listOf("p2") else listOf("p1") }
        assertEquals(Triple("G2", "C", "2 contre"), positionOf(sc, "p2", ids))
        assertNull(positionOf(sc, "zz") { emptyList() })
    }

    @Test fun revirement_counts_by_kind() {
        val rv = listOf(IdTurn(camp = "A", k = "R"), IdTurn(camp = "A", k = "R"), IdTurn(camp = "A", k = "I"), IdTurn(camp = "B", k = "C"))
        assertEquals(listOf("A", "B"), revirementCamps(rv))
        assertEquals(mapOf("R" to 2, "I" to 1, "C" to 0), revirementCounts(rv, "A"))
        assertEquals(mapOf("R" to 0, "I" to 0, "C" to 1), revirementCounts(rv, "B"))
        assertEquals("2 revirements · 1 inflexion · 0 constance", revirementLine(revirementCounts(rv, "A")))
    }

    @Test fun split_when_and_vote_counts() {
        assertEquals("2017" to "retour à la monnaie", splitWhen("2017 : retour à la monnaie"))
        assertEquals("" to "sans date", splitWhen("sans date"))
        val rows = listOf(VoteRow(t = "x", c = listOf(listOf("P", "a"), listOf("C", "b"))), VoteRow(t = "y", c = listOf(listOf("P", "a"), listOf("P", "b"))))
        assertEquals(listOf("P" to 2), voteCounts(rows, 0))
        assertEquals(listOf("P" to 1, "C" to 1), voteCounts(rows, 1))
    }

    @Test fun no_public_statement_is_not_coherence() {
        val none = DvCount(0, 0, 0, 3, 0)
        val m = noGapsMessage(none, DvCount(1, 0, 0, 0, 1))
        assertTrue(m.startsWith("Aucune déclaration publique trouvée hors séance"))
        assertFalse(m.contains("suit la parole")); assertTrue(m.contains("en séance"))
        assertFalse(noGapsMessage(none, DvCount(0, 0, 0, 0, 0)).contains("en séance"))
        assertTrue(noGapsMessage(DvCount(2, 0, 0, 1, 2), none).contains("suit la parole"))
    }

    @Test fun public_rate_label_null_when_nothing_countable() {
        assertNull(publicRateLabel(DvCount(0, 0, 0, 4, 0)))
        assertEquals("1 / 3", publicRateLabel(DvCount(1, 1, 1, 0, 3))!!.figure)
    }
}
