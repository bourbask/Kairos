package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ClaimsTest {
    private fun file(s: String) = PolJson.parseToJsonElement(s).jsonObject

    // 0 Énergie/A solide, 1 Énergie/B faux, 2 Santé/A fragile, 3 Santé/A faux, 4 École/B solide (seule sur son thème), 5 Énergie/C inconnu
    private val data = PolData(mapOf("affirmations" to file("""{"CAMPS":["Camp A","Camp B"],"CLAIMS":[
        {"theme":"Énergie","camp":"Camp A","q":"Q0","v":"solide","cache":"Cache 0","date":"2026-03","src":[["Source fictive","https://example.org/a"]]},
        {"theme":"Énergie","camp":"Camp B","q":"Q1","v":"faux"},
        {"theme":"Santé","camp":"Camp A","q":"Q2","v":"fragile"},
        {"theme":"Santé","camp":"Camp A","q":"Q3","v":"faux"},
        {"theme":"École","camp":"Camp B","q":"Q4","v":"solide"},
        {"theme":"Énergie","camp":"Camp C","q":"Q5","v":"bizarre"},
        {"theme":"Énergie","camp":"","q":"Q6","v":"faux"},
        {"theme":"","camp":"Camp A","q":"Q7","v":"faux"},
        {"theme":"","camp":"Camp B","q":"Q8","v":"faux"},
        42]}""")))

    @Test fun bulle_only_offers_claims_with_facing() {
        val c = claimsOf(data)
        assertEquals(9, c.size)
        // Q4 est seule sur son thème, Q2 et Q3 ne se répondent qu'entre elles : jamais proposées.
        assertEquals(listOf(0, 1, 5), bubbleOffers(c, ""))
        // Camp ou thème vide : ni proposée, ni comptée en face ; l'étape 2 contient toujours une phrase.
        val camps = campsOf(data, c)
        bubbleOffers(c, "").forEach { i -> assertTrue(facingColumns(c, i, camps).any { it.second.isNotEmpty() }) }
        assertTrue(facing(c, 6).isEmpty() && facing(c, 7).isEmpty() && facing(c, 8).isEmpty())
        assertFalse(6 in facing(c, 0))
        assertEquals(listOf(0), bubbleOffers(c, "Camp A"))
        assertTrue(bubbleOffers(c, "Camp C").isNotEmpty())
        assertEquals(emptyList<Int>(), bubbleOffers(c, "Inconnu"))
        // En face de Q0 : Camp B (Q1), Camp C (Q5), pas son propre camp.
        val cols = facingColumns(c, 0, campsOf(data, c))
        assertEquals(listOf("Camp B" to listOf(1), "Camp C" to listOf(5)), cols)
        assertEquals(listOf("Énergie", "Santé", "École"), bubbleThemes(c))
        assertEquals(listOf("Camp A" to listOf(2, 3), "Camp B" to emptyList<Int>(), "Camp C" to emptyList<Int>()), themeColumns(c, campsOf(data, c), "Santé"))
    }

    @Test fun filters_combine_and_paginate() {
        val st = phrasesState(data)
        assertEquals(listOf("solide", "fragile", "faux"), st.verdicts)
        assertEquals(listOf("École", "Énergie", "Santé"), st.themes)
        assertEquals(listOf("Camp A", "Camp B", "Camp C"), st.camps)
        assertEquals(9, phrasesView(st, PhraseFilter()).total)
        assertEquals(listOf(3, 7), phrasesView(st, PhraseFilter(v = "faux", camp = "Camp A")).items)
        assertEquals(listOf(1, 6), phrasesView(st, PhraseFilter(v = "faux", theme = "Énergie")).items)
        assertEquals(0, phrasesView(st, PhraseFilter(v = "solide", camp = "Camp B", theme = "Santé")).total)
        // Pagination : 25 phrases, 20 affichées, puis 5 de plus.
        val many = PolData(mapOf("affirmations" to file("""{"CLAIMS":[${(0 until 25).joinToString(",") { """{"theme":"T","camp":"A","q":"q$it","v":"solide"}""" }}]}""")))
        val big = phrasesState(many)
        val v = phrasesView(big, PhraseFilter())
        assertEquals(20, v.items.size); assertEquals(25, v.total); assertEquals(5, v.more)
        val v2 = phrasesView(big, PhraseFilter(n = 40))
        assertEquals(25, v2.items.size); assertEquals(0, v2.more)
    }

    @Test fun verdict_tally_by_camp() {
        val c = claimsOf(data)
        assertEquals(linkedMapOf("solide" to 1, "fragile" to 1, "faux" to 2), verdictTally(c, "Camp A").filterValues { it > 0 })
        assertEquals(mapOf("solide" to 2, "fragile" to 1, "faux" to 5), verdictTally(c).filterValues { it > 0 })
        assertEquals(emptyMap<String, Int>(), verdictTally(c, "Inconnu").filterValues { it > 0 })
    }

    @Test fun claim_table_card_and_dates() {
        val c = claimsOf(data)[0]
        val t = claimTableCard(c)
        assertEquals("Énergie · entendu côté Camp A", t.kicker)
        assertEquals("Q0", t.quote); assertEquals("solide", t.verdict)
        assertTrue(t.lines.first().startsWith("Ce que la phrase cache : Cache 0"))
        assertEquals("Source fictive", t.source)
        assertEquals("mars 2026", fmtDate("2026-03")); assertEquals("1er mars 2026", fmtDate("2026-03-01")); assertEquals("12 mars 2026", fmtDate("2026-03-12")); assertEquals("n'importe quoi", fmtDate("n'importe quoi"))
        assertNull(claimTableCard(claimsOf(data)[1]).source)
    }

    @Test fun claims_state_empty_and_malformed() {
        for (d in listOf(PolData(emptyMap()), PolData(mapOf("affirmations" to file("""{"CLAIMS":"x","CAMPS":3}"""))), PolData(mapOf("affirmations" to file("""{"CLAIMS":[1,"a",{"q":5}]}"""))))) {
            val c = claimsOf(d)
            val st = phrasesState(d)
            assertTrue(c.size <= 1)
            assertTrue(phrasesView(st, PhraseFilter(v = "faux")).items.isEmpty())
            assertTrue(bubbleOffers(c, "").isEmpty())
            assertTrue(bubbleThemes(c).size <= 1)
        }
        assertTrue(phrasesState(PolData(emptyMap())).claims.isEmpty())
        assertEquals(emptyList<Int>(), facingColumns(emptyList(), 3, emptyList()).flatMap { it.second })
    }
}
