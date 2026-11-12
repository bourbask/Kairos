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
    }

    @Test fun evaluator_tag_texts() {
        val ev = Evaluator(id = "x", nom = "Institut de recherche économique et sociale appliquée", alias = listOf("IRESA 2024", "IRESA"), independance = 2)
        assertEquals("IRESA", evShortName(ev))
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
