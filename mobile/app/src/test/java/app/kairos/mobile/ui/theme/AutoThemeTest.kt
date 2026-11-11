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
        assertFalse(h.update(100f, 0))
        assertFalse(h.update(5f, 1_000))
        assertFalse(h.update(5f, 8_999))
        assertTrue(h.update(5f, 9_000))
    }

    @Test
    fun first_reading_decides_immediately() {
        // Démarrage dans le noir : sombre d'emblée, sans passage par le clair.
        assertTrue(LuxHysteresis().update(3f, 0))
        assertFalse(LuxHysteresis().update(300f, 0))
        // Entre les deux seuils, le milieu tranche.
        assertTrue(LuxHysteresis().update(20f, 0))
        assertFalse(LuxHysteresis().update(35f, 0))
    }

    @Test
    fun restored_state_is_kept() {
        // Après recréation de l'écran, l'état précédent prime sur la première mesure.
        assertTrue(LuxHysteresis(initialDark = true).update(30f, 0))
        assertFalse(LuxHysteresis(initialDark = false).update(20f, 0))
    }

    @Test
    fun oscillation_around_threshold_never_switches() {
        val h = LuxHysteresis()
        assertFalse(h.update(100f, 0))
        var t = 0L
        repeat(200) { i ->
            t += 500
            // Va-et-vient autour du seuil sombre, sur 100 s.
            assertFalse(h.update(if (i % 2 == 0) 13f else 17f, t))
        }
        val d = LuxHysteresis(initialDark = true)
        repeat(200) { i -> assertTrue(d.update(if (i % 2 == 0) 38f else 42f, i * 500L)) }
    }

    @Test
    fun returns_light_with_hysteresis() {
        val h = LuxHysteresis()
        assertTrue(h.update(5f, 0))
        // Entre 15 et 40 lux : l'état ne change pas.
        assertTrue(h.update(25f, 9_000))
        assertTrue(h.update(25f, 60_000))
        // Au-dessus de 40 lux, après le délai.
        assertTrue(h.update(80f, 61_000))
        assertTrue(h.update(80f, 68_999))
        assertFalse(h.update(80f, 69_000))
    }

    @Test
    fun reset_keeps_theme_and_restarts_hold() {
        val h = LuxHysteresis()
        assertTrue(h.update(5f, 0))
        // Mesure périmée avant la mise en arrière-plan : le délai avait commencé.
        assertTrue(h.update(100f, 1_000))
        h.reset()
        // Au retour, le thème courant est gardé et le délai repart de la mesure fraîche.
        assertTrue(h.update(100f, 9_500))
        assertTrue(h.update(100f, 17_499))
        assertFalse(h.update(100f, 17_500))
    }
}
