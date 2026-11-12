package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.LIST_KEY
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileVizTest {
    private fun obj(s: String) = PolJson.parseToJsonElement(s).jsonObject
    private val eps = 1e-4f

    private val data = PolData(
        mapOf(
            "identites" to obj("""{"$LIST_KEY":[{"id":"a","nom":"Candidat A","parti":"Bleu","alias":["Cand. A"],"couleur":"#2255AA"},{"id":"b","nom":"B"}]}"""),
            "budgets" to obj("""{"REF":120,"AXIS":{"min":100,"max":140},"PROGRAMS":[{"who":"Cand. A","debt":110},{"who":"B","debt":130},{"who":"Réf.","ref":true,"debt":120}]}"""),
            "niveau-de-vie" to obj("""{"MEASURES":[{"who":"B","hh":-50},{"who":"Cand. A","hh":100}]}"""),
            "votes" to obj("""{"AN_V":[{"t":"Loi","c":[["P"],["C"],["A"]]}]}"""),
            "dit-vote" to obj("""{"SCRUTINS":[{"id":"s","verdicts":[{"qui":"a","v":"coherent"},{"qui":"b","v":"sans-parole"}]}]}"""),
            "affirmations" to obj("""{"CAMPS":["Cand. A","B"]}"""),
            "revirements" to obj("""{"RV":[{"k":"R"},{"k":"R"},{"k":"C"}]}"""),
        ),
    )

    @Test fun nothing_drawn_without_data() {
        val empty = PolData(emptyMap())
        (RUBRICS.map { it.id } + "inconnu").forEach { assertNull(it, tileViz(empty, it)) }
    }

    @Test fun budgets_dots_on_debt_axis_with_reference() {
        val v = tileViz(data, "budgets")!!
        val dots = v.marks.filter { it.shape == MarkShape.Dot }
        assertEquals("programmes hors référence", 2, dots.size)
        assertEquals("#2255AA", dots[0].hex)
        assertEquals(Ink.Identity, dots[0].ink)
        assertTrue("dette plus faible plus à gauche", dots[0].x < dots[1].x)
        val ref = v.marks.single { it.shape == MarkShape.Dash }
        assertEquals(6f + 20f / 40f * (v.w - 12f), ref.x, eps)
    }

    @Test fun household_bars_from_the_middle() {
        val v = tileViz(data, "niveau-de-vie")!!
        val (gain, loss) = v.marks
        // Triées du plus grand gain à la plus grande perte, au-dessus puis en dessous de la ligne médiane.
        assertEquals(v.h / 2, gain.y + gain.h, eps)
        assertEquals(v.h / 2, loss.y, eps)
        assertEquals(gain.h / 2, loss.h, eps)
        assertEquals("#2255AA", gain.hex)
    }

    @Test fun votes_and_dit_vote_use_state_colours() {
        assertEquals(listOf(Ink.Pour, Ink.Contre, Ink.Partiel), tileViz(data, "votes")!!.marks.map { it.ink })
        val dv = tileViz(data, "dit-vote")!!.marks
        assertEquals(listOf(MarkShape.Box, MarkShape.Hollow), dv.map { it.shape })
        assertEquals(Ink.Solide, dv[0].ink)
    }

    @Test fun turns_split_by_kind_and_bubble_by_camp() {
        val rv = tileViz(data, "revirements")!!.marks
        assertEquals(listOf(Ink.Contre, Ink.Pour), rv.map { it.ink })
        assertEquals(2f, (rv[0].w + 3f) / (rv[1].w + 3f), eps)
        val b = tileViz(data, "bulle")!!.marks
        assertEquals(2, b.size)
        assertEquals(Ink.Identity, b[0].ink)
        assertEquals("#2255AA", b[0].hex)
        assertNull("couleur absente : gris neutre au rendu", b[1].hex)
    }
}
