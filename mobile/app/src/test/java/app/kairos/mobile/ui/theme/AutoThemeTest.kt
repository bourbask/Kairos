package app.kairos.mobile.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoThemeTest {
    @Test
    fun stays_light_on_brief_shadow() {
        val h = LuxHysteresis()
        assertFalse(h.update(100f, 0))
        // Une ombre de 3 s ne suffit pas.
        assertFalse(h.update(2f, 1_000))
        assertFalse(h.update(2f, 4_000))
        assertFalse(h.update(100f, 4_500))
        assertFalse(h.update(2f, 5_000))
        assertFalse(h.update(2f, 12_000)) // le compteur a été remis à zéro
    }

    @Test
    fun goes_dark_after_hold() {
        val h = LuxHysteresis()
        assertFalse(h.update(5f, 0))
        assertFalse(h.update(5f, 7_999))
        assertTrue(h.update(5f, 8_000))
    }

    @Test
    fun returns_light_with_hysteresis() {
        val h = LuxHysteresis()
        h.update(5f, 0)
        assertTrue(h.update(5f, 8_000))
        // Entre 15 et 40 lux : l'état ne change pas.
        assertTrue(h.update(25f, 9_000))
        assertTrue(h.update(25f, 60_000))
        // Au-dessus de 40 lux, après le délai.
        assertTrue(h.update(80f, 61_000))
        assertTrue(h.update(80f, 68_999))
        assertFalse(h.update(80f, 69_000))
    }
}
