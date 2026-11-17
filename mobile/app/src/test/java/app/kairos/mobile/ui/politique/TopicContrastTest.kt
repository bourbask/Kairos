package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.screens.duelInk
import app.kairos.mobile.ui.politique.screens.topic
import app.kairos.mobile.ui.theme.KairosColors
import app.kairos.mobile.ui.theme.ThemePalette
import app.kairos.mobile.ui.theme.colorScheme
import app.kairos.mobile.ui.theme.contrast
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sujets dépliables du duel : AA sur chaque palette, clair et sombre, ouvert et fermé. */
class TopicContrastTest {
    @Test
    fun topics_meet_aa_on_every_palette() {
        for (pal in ThemePalette.entries) for (dark in listOf(false, true)) for (open in listOf(false, true)) {
            val k = duelInk(colorScheme(pal, dark), KairosColors.of(pal, dark), if (dark) PolColors.Dark else PolColors.Light)
            val t = k.topic(open)
            val where = "$pal ${if (dark) "sombre" else "clair"} ${if (open) "ouvert" else "fermé"}"
            val text = contrast(t.fg, t.bg)
            val ring = contrast(t.ring, k.surf)
            println("%-28s texte %.2f  contour %.2f".format(where, text, ring))
            assertNotEquals("$where : fond confondu avec la carte", k.surf, t.bg)
            assertTrue("$where : texte $text < 4,5", text >= 4.5)
            assertTrue("$where : contour $ring < 3", ring >= 3.0)
        }
    }
}
