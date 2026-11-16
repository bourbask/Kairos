package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.ui.politique.model.LIST_KEY
import app.kairos.mobile.ui.politique.model.POL_FILES
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.model.PolPage
import app.kairos.mobile.ui.politique.model.mergePages
import app.kairos.mobile.ui.politique.screens.GRAVITES
import app.kairos.mobile.ui.politique.screens.PromFilter
import app.kairos.mobile.ui.politique.screens.fondementOf
import app.kairos.mobile.ui.politique.screens.graviteLabel
import app.kairos.mobile.ui.politique.screens.originDeck
import app.kairos.mobile.ui.politique.screens.promState
import app.kairos.mobile.ui.politique.screens.promView
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DoorsTest {
    private fun obj(s: String) = PolJson.parseToJsonElement(s).jsonObject

    private val identities = """{"$LIST_KEY": [
        {"id":"a","type":"personne","nom":"Candidat A","parti":"Parti Bleu"},
        {"id":"b","type":"personne","nom":"Candidate B","parti":"Parti Vert"},
        {"id":"parti-bleu","type":"parti","nom":"Parti Bleu","parti":"Parti Bleu"}
    ]}"""

    // 0 a gravité 2, 1 b gravité 4, 2 a gravité 4 (autre thème), 3 gravité inconnue, 4 sans promesse (écartée), 5 invalide.
    private val proms = """{"PROMESSES": [
        {"id":"p0","qui":"a","theme":"Fiscalité","promesse":"Promesse 0","gravite":2,"pourquoi":"Raison 0"},
        {"id":"p1","qui":"b","theme":"Justice","promesse":"Promesse 1","gravite":4,"fondement":["Article fictif","https://example.org/art"]},
        {"id":"p2","qui":"a","theme":"Europe","promesse":"Promesse 2","gravite":4},
        {"id":"p3","qui":"Personne Exemple","theme":"Fiscalité","promesse":"Promesse 3","gravite":9},
        {"id":"p4","qui":"a","theme":"Fiscalité","promesse":" ","gravite":1},
        "x"
    ]}"""

    private val claims = """{"CLAIMS": [
        {"q":"Phrase 0","v":"faux","origine":"Une étude fictive mal lue.","cache":"Le vrai chiffre fictif."},
        {"q":"Phrase 1","v":"vrai"},
        {"q":"Phrase 2","v":"trompeur","origine":"Un slogan fictif."},
        {"q":"Phrase 3","v":"faux","origine":"  "},
        {"q":"Phrase 4","v":"plutot-faux","origine":"Une rumeur fictive."}
    ]}"""

    private val data = PolData(mapOf("identites" to obj(identities), "promesses" to obj(proms), "affirmations" to obj(claims)))

    @Test fun doors_cover_every_rubric() {
        val placed = DOORS.flatMap { it.rubrics }
        // Chaque rubrique dans exactement une porte, et chaque pastille mène à une rubrique connue.
        assertEquals(RUBRICS.map { it.id }.sorted(), placed.sorted())
        assertEquals(placed.size, placed.toSet().size)
        assertEquals(listOf("verifier", "chiffrer", "bilan", "pouvoirs"), DOORS.map { it.id })
        assertTrue("promesses" in DOORS.single { it.id == "chiffrer" }.rubrics)
        assertTrue("origines" in DOORS.single { it.id == "verifier" }.rubrics)
        assertEquals("chiffrer", doorOf("promesses")?.id)
        assertNull(doorOf("inconnue"))
        assertEquals("Gravité des promesses", rubricTitle("promesses"))
        assertEquals("Ça vient d'où ?", rubricTitle("origines"))
    }

    @Test fun pill_counts_only_for_plain_numbers() {
        assertEquals(12, pillCount("12 scrutins"))
        assertNull(pillCount("aucune donnée"))
        assertNull(pillCount("2 thèmes · 3 camps"))
    }

    @Test fun daily_teaser_stable_for_a_day() {
        val door = DOORS.single { it.id == "verifier" }
        val day = LocalDate.of(2026, 11, 16)
        val t = dailyTeaser(data, door, day)!!
        assertEquals(t, dailyTeaser(data, door, day))
        // Sur une semaine, l'accroche change ; toujours une phrase vérifiée publiée.
        val week = (0L..6L).map { dailyTeaser(data, door, day.plusDays(it))!! }
        assertTrue(week.toSet().size > 1)
        assertTrue(week.all { w -> listOf("Phrase 0", "Phrase 1", "Phrase 2", "Phrase 3", "Phrase 4").any { w.contains(it) } })
        // Gravité des promesses : accroche tirée des promesses.
        assertTrue(dailyTeaser(data, DOORS.single { it.id == "chiffrer" }, day)!!.contains("Promesse"))
        // Rien publié : pas d'accroche.
        DOORS.forEach { assertNull(it.id, dailyTeaser(PolData(emptyMap()), it, day)) }
    }

    @Test fun promises_sorted_and_filtered() {
        assertTrue("chargé avec les autres fichiers", "promesses" in POL_FILES)
        val st = promState(data)
        // Gravité décroissante, puis thème ; gravité inconnue à la fin ; sans promesse écartée.
        assertEquals(listOf("p2", "p1", "p0", "p3"), st.list.map { it.id })
        assertEquals(listOf("a", "b", "a", null), st.who)
        assertEquals(listOf("a", "b"), st.ids)
        assertEquals(listOf("Europe", "Fiscalité", "Justice"), st.themes)
        assertEquals(listOf(4, 2), st.graves)
        assertEquals(listOf(0, 1, 2, 3), promView(st, PromFilter()))
        assertEquals(listOf(0, 2), promView(st, PromFilter(qui = "a")))
        assertEquals(listOf(0, 1), promView(st, PromFilter(gravite = 4)))
        assertEquals(listOf(2, 3), promView(st, PromFilter(theme = "Fiscalité")))
        assertTrue(promView(st, PromFilter(qui = "b", theme = "Europe")).isEmpty())
        // Échelle : quatre marches, libellé neutre pour l'inconnu.
        assertEquals(listOf(1, 2, 3, 4), GRAVITES.map { it.n })
        assertEquals("Atteinte aux droits fondamentaux", graviteLabel(4))
        assertEquals("Non précisé", graviteLabel(9))
        assertEquals("Article fictif", fondementOf(st.list[1])?.label)
        assertNull(fondementOf(st.list[0]))
        assertTrue(promState(PolData(emptyMap())).list.isEmpty())
        // Fiche : les promesses de la personne, et pour un parti celles de ses membres.
        assertEquals(listOf("p2", "p0"), identityLinks(data, "a").proms.map { it.id })
        assertEquals(listOf("p2", "p0"), identityLinks(data, "parti-bleu").proms.map { it.id })
    }

    @Test fun origin_deck_only_claims_with_origine() {
        val deck = originDeck(data, 7)
        assertEquals(setOf("Phrase 0", "Phrase 2", "Phrase 4"), deck.map { it.q }.toSet())
        assertEquals(3, deck.size)
        // Même graine (même session) : même ordre.
        assertEquals(deck, originDeck(data, 7))
        assertTrue((1L..20L).map { originDeck(data, it).map { c -> c.q } }.toSet().size > 1)
        assertEquals("3 cartes", RUBRICS.single { it.id == "origines" }.count(data))
        assertTrue(originDeck(PolData(emptyMap()), 1).isEmpty())
    }

    @Test fun contract_sample_decodes() {
        val page = KairosJson.decodeFromString(PolPage.serializer(), javaClass.getResource("/politique/fichier-promesses.json")!!.readText())
        val st = promState(PolData(mapOf("promesses" to mergePages(listOf(page)))))
        assertEquals(listOf(3, 2, 1), st.list.map { it.gravite })
        assertNotEquals(null, fondementOf(st.list[0]))
    }
}
