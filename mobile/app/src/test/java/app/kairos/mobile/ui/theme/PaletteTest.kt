package app.kairos.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    /** Texte courant et texte posé sur un aplat : 4,5:1 au moins, en clair comme en sombre. */
    @Test
    fun text_contrast_at_least_4_5() {
        for (p in ThemePalette.entries) for (dark in listOf(false, true)) {
            val r = p.roles(dark)
            val pairs = mapOf(
                "texte/fond" to (r.text to r.background),
                "texte/carte" to (r.text to r.surface),
                "discret/fond" to (r.discreet to r.background),
                "discret/carte" to (r.discreet to r.surface),
                "bouton" to (r.onPrimary to r.primary),
                "attention/fond" to (r.attentionText to r.background),
                "attention/carte" to (r.attentionText to r.surface),
                "conteneur" to (r.onPrimaryContainer to r.primaryContainer),
                "agenda travail" to (r.nuit to r.ciel),
            )
            for ((name, c) in pairs) {
                val ratio = contrast(c.first, c.second)
                assertTrue("${p.label} ${if (dark) "sombre" else "clair"} $name : $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun fougere_keeps_its_material_mapping() {
        val light = colorScheme(ThemePalette.Fougere.light, dark = false)
        val dark = colorScheme(ThemePalette.Fougere.dark, dark = true)
        assertEquals(Color(0xFF4F7942), light.primary)
        assertEquals(Color(0xFF2F4A28), light.secondary)
        assertEquals(Color.White, light.onError)
        assertEquals(Color(0xFF1F2620), light.onTertiary)
        assertEquals(Color(0xFF121712), dark.onPrimary)
        assertEquals(Color(0xFF121712), dark.onTertiary)
        assertEquals(Color(0xFF222A22), dark.surfaceContainerHigh)
        assertEquals(Color(0xFF121712), dark.surfaceContainerLowest)
    }

    @Test
    fun mix_rounds_like_the_prototype() {
        // Ombre du corps de Fougère : corps mêlé à 30 % du bouton.
        assertEquals(Color(0xFFC5D0BB), mix(Color(0xFFF7F5EF), Color(0xFF4F7942), 0.3f))
    }
}
