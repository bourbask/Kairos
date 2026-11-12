package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.screens.*
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class FiguresTest {
    private val eps = 1e-9

    @Test fun axis_bounds_include_reference() {
        // L'axe publié est respecté tant qu'il contient tout.
        assertEquals(128.0 to 148.0, axisBounds(Scale(128.0, 148.0), listOf(130.0, 139.8, 145.0)))
        // Une valeur ou la référence hors de l'axe l'élargit, au multiple de 5 englobant.
        assertEquals(125.0 to 150.0, axisBounds(Scale(128.0, 148.0), listOf(126.2, 139.8, 149.1)))
        // Sans axe publié : calculé sur les valeurs, jamais dégénéré.
        val (lo, hi) = axisBounds(null, listOf(140.0))
        assertTrue(lo <= 140.0 && hi > 140.0)
        assertEquals(listOf(130.0, 135.0, 140.0, 145.0), axisTicks(128.0, 148.0))
    }

    @Test fun household_bar_sign_and_scale() {
        val gain = householdBar(1000.0, 2000.0)
        assertEquals(0.5, gain.start, eps); assertEquals(0.25, gain.width, eps)
        val loss = householdBar(-2000.0, 2000.0)
        assertEquals(0.0, loss.start, eps); assertEquals(0.5, loss.width, eps)
        val none = householdBar(0.0, 0.0)
        assertEquals(0.0, none.width, eps)
    }

    @Test fun gap_ratio_handles_zero_credit() {
        assertEquals(20.0, gapRatio(Gap(claimed = 140.0, credited = 7.0))!!, eps)
        assertEquals(2.0, gapRatio(Gap(claimed = 10.0, credited = 20.0, inverse = true))!!, eps)
        assertNull(gapRatio(Gap(claimed = 10.0, credited = 0.0)))
        assertNull(gapRatio(Gap(claimed = 0.0, credited = 5.0, inverse = true)))
        assertEquals("÷20", gapRatioLabel(Gap(claimed = 140.0, credited = 7.0)))
        assertEquals("×2,5", gapRatioLabel(Gap(claimed = 10.0, credited = 25.0, inverse = true)))
        assertEquals("—", gapRatioLabel(Gap(claimed = 10.0, credited = 0.0)))
    }

    @Test fun signed_bar_places_zero() {
        val b = signedBar(8.3, -3.0, 14.0)
        assertEquals(3.0 / 17.0, b.start, eps); assertEquals(8.3 / 17.0, b.width, eps)
        val n = signedBar(-1.0, -3.0, 3.0)
        assertEquals(1.0 / 3.0, n.start, eps); assertEquals(1.0 / 6.0, n.width, eps)
        assertEquals(0.0, signedBar(1.0, 2.0, 2.0).width, eps)
    }

    @Test fun source_parsing_splits_label_url_rest() {
        val s = parseSource("Institut X, mise à jour (https://exemple.org/a) ; note")
        assertEquals("Institut X, mise à jour", s.ref.label); assertEquals("https://exemple.org/a", s.ref.url); assertEquals("note", s.rest)
        assertNull(parseSource("Sans lien").ref.url)
    }

    @Test fun numbers_use_french_format() {
        assertEquals("139,8", fmtNum(139.8, 1)); assertEquals("−2\u00A0078", fmtSigned(-2078.0)); assertEquals("+1\u00A0146", fmtSigned(1146.0))
    }

    @Test fun fixtures_decode_with_balance() {
        val json = PolJson.parseToJsonElement("""{"PROGRAMS":[
            {"who":"Candidat A","party":"Parti Bleu","debt":131.5,"hh":-800,"lecture":{"contrepartie_courte":"Coupes","postes":[{"quoi":"Poste","montant":"3 Md€"}]}},
            {"who":"Sans mesure","ref":true,"debt":140.0},12],
          "MATRIX":[{"o":"Orientation","s":"2023","c":{"Candidat A":["keep","texte"]}}]}""").jsonObject
        val data = PolData(mapOf("budgets" to json, "bilan" to json))
        val p = data.decode<Program>("budgets", "PROGRAMS")
        assertEquals(2, p.size)
        assertEquals("Coupes", p[0].lecture!!.contrepartie_courte); assertEquals(-800.0, p[0].hh!!, eps); assertTrue(p[1].ref)
        assertEquals("keep", data.decode<MatrixRow>("bilan", "MATRIX")[0].c["Candidat A"]!![0])
    }
}
