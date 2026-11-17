package app.kairos.mobile.ui.politique

import androidx.compose.ui.graphics.Color
import app.kairos.mobile.ui.politique.jeux.kit.ECART_VERT
import app.kairos.mobile.ui.politique.jeux.kit.SEMANTIQUE_CLAIR
import app.kairos.mobile.ui.politique.jeux.kit.SEMANTIQUE_SOMBRE
import app.kairos.mobile.ui.politique.jeux.kit.ecartTeinte
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.jeux.kit.procheSemantique
import app.kairos.mobile.ui.politique.jeux.kit.teinte
import app.kairos.mobile.ui.politique.jeux.kit.teinteSuivant
import app.kairos.mobile.ui.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JeuxCouleursTest {
    private fun suivant(p: ThemePalette, dark: Boolean) = p.roles(dark).let { r ->
        teinteSuivant(r.primary, r.primaryContainer, r.onPrimaryContainer, r.ciel, r.nuit, mix(r.surface, r.text, 0.1f), r.discreet, dark)
    }

    @Test fun hue_basics() {
        assertEquals(0f, teinte(Color(0xFFFF0000)).first, 0.01f)
        assertEquals(120f, teinte(Color(0xFF00FF00)).first, 0.01f)
        assertEquals(0f, teinte(Color.Gray).second, 0.01f)
        assertEquals(20f, ecartTeinte(350f, 10f), 0.01f)
        // Le vert sémantique se confond avec lui-même, pas un gris ni un bleu.
        assertTrue(procheSemantique(SEMANTIQUE_CLAIR.juste))
        assertTrue(procheSemantique(SEMANTIQUE_CLAIR.faux))
        assertFalse(procheSemantique(Color(0xFF808080)))
        assertFalse(procheSemantique(Color(0xFF2F4B7C)))
    }

    @Test fun next_panel_follows_palette_unless_green_or_red() {
        for (p in ThemePalette.entries) for (dark in listOf(false, true)) {
            val r = p.roles(dark)
            val sem = if (dark) SEMANTIQUE_SOMBRE else SEMANTIQUE_CLAIR
            val (fond, encre) = suivant(p, dark)
            val label = "${p.name} ${if (dark) "sombre" else "clair"}"
            // Jamais vert sur vert ni rouge : l'encre du panneau reste loin des teintes sémantiques.
            assertFalse(label, procheSemantique(encre, sem))
            if (procheSemantique(r.primary, sem)) {
                assertEquals(label, r.ciel, fond)
                assertEquals(label, r.nuit, encre)
            } else {
                assertEquals(label, r.primaryContainer, fond)
                assertEquals(label, r.onPrimaryContainer, encre)
            }
            // Couleur principale verte : à plus de [ECART_VERT] du vert sémantique sinon.
            val ecart = ecartTeinte(teinte(r.primary).first, teinte(sem.juste).first)
            if (fond == r.primaryContainer && teinte(r.primary).second >= 0.15f) assertTrue(label, ecart >= ECART_VERT)
        }
        // Fougère : panneau « suivante » inchangé (ciel / nuit), dans les deux modes.
        assertEquals(ThemePalette.Fougere.light.ciel, suivant(ThemePalette.Fougere, false).first)
        assertEquals(ThemePalette.Fougere.dark.ciel, suivant(ThemePalette.Fougere, true).first)
        // Les autres palettes : couleur de la palette.
        listOf(ThemePalette.Estampe, ThemePalette.Bruyere, ThemePalette.Graphite).forEach {
            assertEquals(it.name, it.light.primaryContainer, suivant(it, false).first)
        }
    }

    @Test fun semantic_colors_are_the_validated_ones() {
        // Mêmes teintes que la palette par défaut (maquette validée), quelle que soit la palette choisie.
        assertEquals(ThemePalette.Fougere.light.primaryContainer, SEMANTIQUE_CLAIR.justeFond)
        assertEquals(ThemePalette.Fougere.dark.primaryContainer, SEMANTIQUE_SOMBRE.justeFond)
        assertEquals(ThemePalette.Fougere.light.urgent, SEMANTIQUE_CLAIR.faux)
        assertEquals(ThemePalette.Fougere.light.attention, SEMANTIQUE_CLAIR.ambre)
    }
}
