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

    private val BAREME = """{"VERDICTS":[
      {"id":"vrai","libelle":"Vrai (barème)","famille":"solide","definition":"Déf vrai"},
      {"id":"faux","libelle":"Faux","famille":"partiel","definition":"Déf faux"}],
      "CONFIANCE":[{"niveau":1,"libelle":"Basse","definition":"D1"},{"niveau":9,"libelle":"X","definition":"X"}],
      "PRINCIPE":"Principe fictif."}"""

    @Test fun justification_fields_decode_with_fallback() {
        val d = PolData(mapOf("affirmations" to file("""{"CLAIMS":[
          {"id":"phrase-exemple","q":"Q0","v":"vrai","facts":["F1","F2"],"pourquoi":"P","confiance":2,
           "confiance_pourquoi":"C","appui":[2,0,9,2],"camp":null,"camp_note":"Note"},
          {"q":"Q1","v":"faux","confiance":7}]}""")))
        val (a, b) = claimsOf(d)
        assertEquals("phrase-exemple", a.id); assertTrue(hasWhy(a)); assertEquals(2, confidenceLevel(a))
        assertEquals(listOf(2), supportFacts(a)); assertEquals("", a.camp); assertEquals("Note", a.camp_note)
        assertEquals("", b.id); assertFalse(hasWhy(b)); assertNull(confidenceLevel(b)); assertTrue(supportFacts(b).isEmpty())
    }

    @Test fun bareme_comes_from_file_or_is_absent() {
        val b = baremeOf(PolData(mapOf("bareme" to file(BAREME))))!!
        assertEquals("Vrai (barème)", verdictName(b, "solide"))
        assertEquals("Déf faux", verdictDefinition(b, "faux"))
        assertEquals(2, verdictThirds(b, "faux"))
        assertEquals(2, verdictThirds(b, "plutot-vrai"))
        assertEquals(listOf(1), b.confiance.map { it.niveau })
        assertEquals("Principe fictif.", b.principe)
        assertNull(baremeOf(PolData(emptyMap())))
        assertEquals("Plutôt vrai", verdictName(null, "plutot-vrai"))
        assertNull(verdictDefinition(null, "vrai"))
        assertEquals(listOf(3, 2, 1, 1), listOf("vrai", "plutot-vrai", "trompeur", "non-documente").map { verdictThirds(null, it) })
    }

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
        assertEquals(listOf("vrai", "non-prouve", "faux"), st.verdicts)
        assertEquals(listOf("École", "Énergie", "Santé"), st.themes)
        assertEquals(listOf("Camp A", "Camp B", "Camp C"), st.camps)
        assertEquals(9, phrasesView(st, PhraseFilter()).total)
        assertEquals(listOf(3, 7), phrasesView(st, PhraseFilter(v = "faux", camp = "Camp A")).items)
        assertEquals(listOf(1, 6), phrasesView(st, PhraseFilter(v = "faux", theme = "Énergie")).items)
        assertEquals(0, phrasesView(st, PhraseFilter(v = "vrai", camp = "Camp B", theme = "Santé")).total)
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
        assertEquals(linkedMapOf("vrai" to 1, "non-prouve" to 1, "faux" to 2), verdictTally(c, "Camp A").filterValues { it > 0 })
        assertEquals(mapOf("vrai" to 2, "non-prouve" to 1, "faux" to 5), verdictTally(c).filterValues { it > 0 })
        assertEquals(emptyMap<String, Int>(), verdictTally(c, "Inconnu").filterValues { it > 0 })
    }

    @Test fun claim_table_card_and_dates() {
        val c = claimsOf(data)[0]
        val t = claimTableCard(data, c)
        assertEquals("Énergie · entendu côté Camp A", t.kicker)
        assertEquals("Q0", t.quote); assertEquals("vrai", t.verdict)
        assertTrue(t.lines.first().startsWith("Ce que la phrase cache : Cache 0"))
        assertEquals("libellé du composant : nom · article", "Source fictive · example.org", t.source)
        assertEquals("mars 2026", fmtDate("2026-03")); assertEquals("1er mars 2026", fmtDate("2026-03-01")); assertEquals("12 mars 2026", fmtDate("2026-03-12")); assertEquals("n'importe quoi", fmtDate("n'importe quoi"))
        assertNull(claimTableCard(data, claimsOf(data)[1]).source)
    }

    @Test fun claim_without_camp_says_so() {
        val c = claimsOf(data)[0].copy(camp = "")
        assertEquals("camp non établi", campLabel(c))
        assertEquals("Énergie · camp non établi", claimTableCard(data, c).kicker)
        assertEquals("camp non établi", claimTableCard(data, c).title)
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

    @Test fun share_link_keeps_only_origin_and_anchor() {
        assertEquals("https://site.example/#a-phrase-exemple", claimLink("https://site.example/entrer#jeton-fictif", "phrase-exemple"))
        assertEquals("https://site.example/#a-phrase-exemple", claimLink("https://site.example/x?t=1#y", "phrase-exemple"))
        assertNull(claimLink(null, "phrase-exemple"))
        assertNull(claimLink("pas une adresse", "phrase-exemple"))
        listOf("", "ab", "Phrase", "a b c", "../x", "x".repeat(61)).forEach { assertNull(it, claimLink("https://site.example/", it)) }
    }

    @Test fun verdict_key_labels() {
        assertEquals("Verdict : Vrai. Pourquoi ?", verdictKeyLabel("Vrai", hasWhy = true, back = false))
        assertEquals("Vrai : revenir aux faits", verdictKeyLabel("Vrai", hasWhy = true, back = true))
        assertEquals("Verdict : Vrai. Justification en cours", verdictKeyLabel("Vrai", hasWhy = false, back = false))
    }
}
