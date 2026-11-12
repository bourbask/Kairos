package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReadingTest {
    private fun file(s: String) = PolJson.parseToJsonElement(s).jsonObject
    private fun data(vararg f: Pair<String, String>) = PolData(f.associate { (k, v) -> k to file(v) })
    private val empty = PolData(emptyMap())

    private val rhet = data(
        "rhetorique" to """{"RHET":[
          {"t":"A","famille":"Esquiver","d":"da","spot":"<b>À repérer :</b> signe","ex":[["Camp","texte","https://exemple.test/x"],["Autre","sans lien"],["Cassé"],"mal",["X","ok","javascript:alert(1)"]],"ask":"Quoi ?","aussi":"autre nom"},
          {"t":"B","famille":"Cadrer","d":"db","aussi":["un","deux"]},
          {"t":"C","famille":"Esquiver","d":"dc"},
          {"t":"D","d":"dd"},
          {"t":"","famille":"Vide"}]}""",
    )

    @Test fun rhet_grouped_by_family_in_order() {
        val l = rhetOf(rhet)
        assertEquals(listOf("A", "B", "C", "D"), l.map { it.t })
        val fams = rhetFamilies(l)
        assertEquals(listOf("Esquiver", "Cadrer", OTHER_FAMILY), fams.map { it.label })
        assertEquals(listOf(0, 2), fams[0].cards)
        assertEquals(listOf(3), fams[2].cards)
        assertTrue(rhetFamilies(emptyList()).isEmpty())
        assertTrue(rhetOf(empty).isEmpty())
    }

    @Test fun rhet_examples_aussi_spot_and_lookup() {
        val l = rhetOf(rhet)
        val ex = rhetExamples(l[0])
        assertEquals(listOf("Camp", "Autre", "X"), ex.map { it.who })
        assertEquals("https://exemple.test/x", ex[0].url)
        assertNull(ex[1].url)
        assertNull("adresse non https refusée", ex[2].url)
        assertEquals("autre nom", aussiOf(l[0]))
        assertEquals("un, deux", aussiOf(l[1]))
        assertEquals("", aussiOf(l[2]))
        assertEquals("signe", spotBody(l[0].spot))
        assertEquals("sans balise", spotBody("sans balise"))
        assertEquals("B", rhetFind(l, "b")?.t)
        assertNull(rhetFind(l, "Z"))
        assertTrue(rhetExamples(l[2]).isEmpty())
        val cards = rhetTableCards(l)
        assertEquals(4, cards.size)
        assertTrue(cards[0].lines.contains("La question à poser : Quoi ?"))
    }

    @Test fun rhet_malformed_data_is_skipped_not_fatal() {
        val d = data("rhetorique" to """{"RHET":[{"t":"A","ex":"pas une liste"},{"t":"B","famille":3,"d":null},42]}""")
        assertEquals(listOf("B"), rhetOf(d).map { it.t })
        assertTrue(rhetOf(data("rhetorique" to """{"RHET":"x"}""")).isEmpty())
    }

    private val dz = data(
        "dossiers" to """{"DOSSIERS":[
          {"id":"d1","titre":"T1","source":{"nom":"N","ev":null,"type":"militant","date":"2026-11","relaye_par":"x"},"contexte":"c","items":[
            {"dit":"a","verdict":"faux","faits":["f"],"procede":"P","src":[]},
            {"dit":"b","verdict":"solide","faits":[]},
            {"dit":"c","verdict":"faux","faits":[]},
            {"dit":"d","verdict":"inconnu","faits":[]}]},
          {"titre":"T2"},
          {"id":"d3"}]}""",
    )

    @Test fun dossier_tally() {
        val l = contentDossiersOf(dz)
        assertEquals(listOf("T1", "T2"), l.map { it.titre })
        assertEquals(listOf("solide" to 1, "faux" to 2), dossierTally(l[0].items))
        assertEquals(listOf("solide" to 1, "faux" to 2, "" to 1), dossierBar(l[0].items))
        assertTrue(dossierTally(l[1].items).isEmpty())
        assertEquals("militant", l[0].source.type)
        assertNull(l[0].source.ev)
        assertTrue(contentDossiersOf(empty).isEmpty())
        assertTrue(contentDossiersOf(data("dossiers" to """{"DOSSIERS":{"a":1}}""")).isEmpty())
    }

    @Test fun source_type_labels_with_neutral_fallback() {
        assertEquals("Organisation militante", sourceTypeLabel("militant"))
        assertEquals("Parole de candidat", sourceTypeLabel("candidat"))
        assertEquals("Contenu", sourceTypeLabel("zzz"))
        assertEquals("Contenu", sourceTypeLabel(""))
    }

    private fun ev(id: String, nom: String, ind: Int?, type: String = "public", tendance: String = "") = Evaluator(id = id, nom = nom, type = type, independance = ind, tendance = tendance)

    @Test fun evaluators_grouped_by_level_unknown_last() {
        val g = evaluatorsByLevel(listOf(ev("a", "Zèbre", 1), ev("b", "école", 1), ev("c", "Mu", null), ev("d", "Nu", 9), ev("e", "Pi", 4), ev("f", "Ab", 0)))
        assertEquals(listOf(1, 4, 0), g.map { it.first })
        assertEquals(listOf("école", "Zèbre"), g[0].second.map { it.nom })
        assertEquals(listOf("Ab", "Mu", "Nu"), g[2].second.map { it.nom })
        assertTrue(evaluatorsByLevel(emptyList()).isEmpty())
    }

    @Test fun evaluator_sub_line() {
        assertEquals("Média · tendance documentée", evaluatorSub(ev("a", "A", 1, "media", "plus sévère")))
        assertEquals("Organisme public", evaluatorSub(ev("a", "A", 1, "public", "non mesurée")))
        assertEquals("exotique", evaluatorSub(ev("a", "A", 1, "exotique")))
        assertEquals("", evaluatorSub(ev("a", "A", 1, "")))
    }
}
