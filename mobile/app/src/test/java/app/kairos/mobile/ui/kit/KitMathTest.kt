package app.kairos.mobile.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitMathTest {
    private val nnbsp = ' '
    private val nbsp = ' '

    @Test
    fun euro_format_french() {
        assertEquals("1${nnbsp}234,56${nbsp}€", formatEuro(1234.56))
        assertEquals("0,00${nbsp}€", formatEuro(0.0))
        assertEquals("-12,50${nbsp}€", formatEuro(-12.5))
        assertEquals("1${nnbsp}234${nnbsp}567,00${nbsp}€", formatEuro(1_234_567.0))
        // Arrondi commercial au centime, pas l'arrondi bancaire.
        assertEquals("2,35${nbsp}€", formatEuro(2.345))
        assertEquals("1${nnbsp}235${nbsp}€", formatEuro(1234.56, cents = false))
        assertEquals("0${nbsp}€", formatEuro(-0.2, cents = false))
    }

    @Test
    fun masked_amount_never_shows_digits() {
        val values = listOf(0.0, 7.0, -42.5, 1234.56, 9_999_999.99, Double.NaN)
        val fmt: (Double) -> String = { formatEuro(it) }
        val texts = values.map { amountText(it, masked = true) } +
            values.map { amountText(it, masked = true, cents = false) } +
            describeBar("Courses", 180.0, 200.0, fmt, masked = true) +
            describeBar("Loisirs", 250.0, 200.0, fmt, masked = true) +
            describeLine("Évolution", listOf(1000.0, 1200.0, 900.0), fmt, masked = true) +
            describeDonut("Répartition", listOf("A" to 30.0, "B" to 70.0), fmt, masked = true)
        for (t in texts) {
            assertFalse("chiffre visible dans « $t »", t.any { it.isDigit() })
            assertTrue(t.isNotBlank())
        }
        // Démasqué, les montants apparaissent bien.
        assertTrue(describeBar("Courses", 180.0, 200.0, fmt, masked = false).contains("180,00"))
    }

    @Test
    fun bar_tone_thresholds() {
        assertEquals(Tone.Neutral, barTone(0.0, 100.0))
        assertEquals(Tone.Neutral, barTone(80.0, 100.0))
        // Exactement 80 % reste neutre, quelle que soit l'échelle (centimes compris).
        assertEquals(Tone.Neutral, barTone(160.0, 200.0))
        assertEquals(Tone.Neutral, barTone(0.24, 0.3))
        assertEquals(Tone.Neutral, barTone(56.0, 70.0))
        assertEquals(Tone.Attention, barTone(80.01, 100.0))
        assertEquals(Tone.Attention, barTone(100.0, 100.0))
        assertEquals(Tone.Urgent, barTone(100.01, 100.0))
        // Sans plafond : tout usage est un dépassement.
        assertEquals(Tone.Neutral, barTone(0.0, 0.0))
        assertEquals(Tone.Urgent, barTone(5.0, 0.0))
        assertEquals(0.5f, fill(50.0, 100.0))
        assertEquals(1f, fill(150.0, 100.0))
        assertEquals(0f, fill(-5.0, 100.0))
    }

    @Test
    fun line_scale_handles_flat_and_single_point() {
        assertTrue(linePoints(emptyList()).isEmpty())
        assertEquals(listOf(0.5f to 0.5f), linePoints(listOf(42.0)))
        assertEquals(listOf(0f to 0.5f, 0.5f to 0.5f, 1f to 0.5f), linePoints(listOf(3.0, 3.0, 3.0)))
        // y = 0 en bas (minimum), 1 en haut (maximum).
        assertEquals(listOf(0f to 0f, 0.5f to 1f, 1f to 0.5f), linePoints(listOf(10.0, 30.0, 20.0)))
        assertTrue(linePoints(listOf(1.0, Double.NaN)).all { (x, y) -> x.isFinite() && y.isFinite() })
    }

    @Test
    fun donut_weights_normalized() {
        val w = donutWeights(listOf(1.0, 3.0))
        assertEquals(listOf(0.25f, 0.75f), w)
        assertEquals(1f, donutWeights(listOf(0.2, 0.3, 0.5)).sum(), 1e-6f)
        // Négatifs ignorés, tout à zéro : anneau vide plutôt qu'une division par zéro.
        assertEquals(listOf(0f, 1f), donutWeights(listOf(-5.0, 2.0)))
        assertEquals(listOf(0f, 0f), donutWeights(listOf(0.0, 0.0)))
        assertTrue(donutWeights(emptyList()).isEmpty())
    }
}
