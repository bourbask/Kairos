package app.kairos.mobile.ui.politique

import androidx.compose.ui.graphics.Color
import app.kairos.mobile.ui.politique.kit.*
import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.Poste
import app.kairos.mobile.ui.politique.model.SourceRef
import org.junit.Assert.*
import org.junit.Test

class PolKitTest {
    private val ink = Color(0xFF1F2620)

    @Test fun text_on_identity_color_is_readable() {
        assertEquals(ink, textOn(Color.White))
        assertEquals(ink, textOn(Color(0xFFFFD400)))
        assertEquals(Color.White, textOn(Color(0xFF14213D)))
    }

    @Test fun only_https_sources_open() {
        assertEquals("https://exemple.org/a?b=1", safeHttps("  https://exemple.org/a?b=1 "))
        assertNull(safeHttps("http://exemple.org"))
        assertNull(safeHttps("javascript:alert(1)"))
        assertNull(safeHttps("intent://x#Intent;end"))
        assertNull(safeHttps("https://exemple.org/a b"))
        assertNull(safeHttps("https://"))
        assertNull(safeHttps(null))
    }

    @Test fun verdict_labels_cover_all_values() {
        val expected = mapOf(
            "solide" to "Solide", "partiel" to "En partie vrai", "trompeur" to "Vrai mais trompeur",
            "fragile" to "Fragile", "faux" to "Faux", "non-documente" to "Non documenté",
        )
        expected.forEach { (k, v) -> assertEquals(v, verdictLabel(k)) }
        assertEquals(NEUTRAL_LABEL, verdictLabel("inconnu"))
        assertEquals(NEUTRAL_LABEL, verdictLabel(""))
        assertEquals(7, PROMISE_VERDICTS.size)
        PROMISE_VERDICTS.forEach { assertNotEquals(NEUTRAL_LABEL, promiseLabel(it)) }
        assertEquals("Finançable", promiseLabel("financable"))
        assertEquals(NEUTRAL_LABEL, promiseLabel("???"))
    }

    @Test fun balance_prefers_short_form() {
        val lec = Lecture(
            contrepartie = "Texte long.", contrepartie_courte = "Courte.",
            postes = listOf(Poste("Poste A", "1 Md", "Les X"), Poste(" ", "2 Md")),
        )
        val s = balanceSummary(lec)
        assertEquals("Courte.", s.short)
        assertEquals(listOf("Poste A"), s.postes.map { it.quoi })
        val fallback = balanceSummary(Lecture(contrepartie = "Texte long.", angle_mort = "Angle."))
        assertNull(fallback.short)
        assertEquals("Texte long.", fallback.long)
        assertEquals("Angle.", balanceSummary(Lecture(angle_mort = "Angle.")).long)
        // Drapeau qui fait afficher « Ce que le chiffre laisse dehors » : seulement sans contrepartie.
        assertTrue(balanceSummary(Lecture(angle_mort = "Angle.")).angleMort)
        assertFalse(balanceSummary(Lecture(contrepartie = "Coût.", angle_mort = "Angle.")).angleMort)
    }

    @Test fun balance_row_signs_losses() {
        assertEquals("−1,4 Md€/an", lossAmount("1,4 Md€/an"))
        assertEquals("−14,1 Md€", lossAmount("−14,1 Md€"))
        assertEquals("−3 Md€", lossAmount("- 3 Md€"))
        assertEquals("plus de 4 Md€", lossAmount(" plus de 4 Md€ "))
        assertEquals("0", lossAmount("0"))
        assertEquals("0 €", lossAmount("0 €"))
        assertEquals("2 à 4 Md€", lossAmount("2 à 4 Md€"))
        assertEquals("−1 200 €", lossAmount("1 200 €"))
        assertEquals("0,0 Md€", lossAmount("-0,0 Md€"))
        assertEquals("−0,5 Md€", lossAmount("0,5 Md€"))
        val rows = balanceRows(listOf(Poste("Poste B", "2 Md€", " Les Y "), Poste("Poste A", " ", ""), Poste("Poste C", null, null)))
        assertEquals(listOf("Poste B", "Poste A", "Poste C"), rows.map { it.quoi })
        assertEquals(BalanceRow("Poste B", "Les Y", "−2 Md€"), rows[0])
        assertEquals(BalanceRow("Poste A", null, null), rows[1])
        assertEquals(BalanceRow("Poste C", null, null), rows[2])
    }

    @Test fun evaluator_tag_texts() {
        val ev = Evaluator(id = "x", nom = "Institut Exemple pour la recherche économique et sociale", alias = listOf("IEX 2024", "IEX"), independance = 2)
        assertEquals("IEX", evShortName(ev))
        assertEquals("Court", evShortName(Evaluator(nom = "Court")))
        assertEquals(2, indLevel(ev))
        assertEquals(0, indLevel(Evaluator(independance = 9)))
        assertTrue(evA11yLabel(ev).contains("niveau 2 sur 4"))
        assertTrue(evA11yLabel(Evaluator(nom = "N")).contains("non qualifiée"))
    }

    @Test fun rich_text_and_share() {
        assertEquals(listOf("a " to false, "gras" to true, " b" to false), splitBold("a <b>gras</b> b"))
        assertEquals(listOf("<i>x</i>" to false), splitBold("<i>x</i>"))
        assertEquals("«\u00a0A\u00a0»\u00a0:", frTypo("« A » :"))
        val c = TableCard("Rubrique", "Titre", quote = "Dit", verdict = "faux", lines = listOf("L1"), source = "Org")
        assertEquals("Rubrique\nTitre\n« Dit »\nFaux\nL1\nSource : Org", shareText(c))
    }

    @Test fun source_name_falls_back_to_domain() {
        assertEquals("exemple.org", sourceName(SourceRef("", "https://www.exemple.org/x")))
        assertEquals("Source", sourceName(SourceRef(" ", "http://exemple.org")))
        assertEquals("Rapport", sourceName(SourceRef("Rapport")))
    }
}
