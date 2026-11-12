package app.kairos.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val light = colorScheme(ThemePalette.Fougere, dark = false)
        val dark = colorScheme(ThemePalette.Fougere, dark = true)
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

    /** Hors du bouton : le corps se détache du fond, les yeux du corps, 3:1 au moins. */
    @Test
    fun mascot_reads_on_page() {
        for (p in ThemePalette.entries) for (dark in listOf(false, true)) {
            val c = SceneColors.onPage(p, dark)
            val mode = if (dark) "sombre" else "clair"
            assertEquals(p.roles(dark).background, c['B'])
            assertTrue("${p.label} $mode corps/fond", contrast(c['F'], c['B']) >= 3.0)
            assertTrue("${p.label} $mode yeux/corps", contrast(c['D'], c['F']) >= 3.0)
        }
    }

    /** Sur le bouton, le corps clair du personnage se détache à 3:1 au moins. */
    @Test
    fun mascot_reads_on_button() {
        for (p in ThemePalette.entries) for (dark in listOf(false, true)) {
            val c = SceneColors.of(p, dark)
            val ratio = contrast(c['F'], c['B'])
            assertTrue("${p.label} ${if (dark) "sombre" else "clair"} corps/bouton : $ratio", ratio >= 3.0)
        }
    }

    /** Tous les rôles d'un ColorScheme, lus par réflexion : un rôle ajouté à Material est couvert d'office. */
    private fun roles(s: ColorScheme): Map<String, Color> = ColorScheme::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
        .associate { it.name.removePrefix("get").substringBefore('-') to Color((it.invoke(s) as Long).toULong()) }

    /** Couleurs partagées volontairement entre palettes : blanc des cartes et des textes sur aplat, noir du voile. */
    private val shared = setOf(Color.White, Color.Black)

    /** Aucun rôle ne retombe sur la palette de base de Material (violets par défaut). */
    @Test
    fun every_role_is_set_by_the_palette() {
        for (dark in listOf(false, true)) {
            val baseline = roles(if (dark) darkColorScheme() else lightColorScheme()).values.toSet() - shared
            for (p in ThemePalette.entries) {
                val r = roles(colorScheme(p, dark))
                assertTrue("aucun rôle lu", r.size >= 48)
                for ((name, c) in r) {
                    assertTrue("${p.label} ${mode(dark)} $name reste au défaut Material", c !in baseline)
                    assertNotEquals("${p.label} ${mode(dark)} $name non défini", Color.Unspecified, c)
                }
            }
        }
    }

    /** Une palette autre que Fougère ne garde aucune couleur propre à Fougère. */
    @Test
    fun no_fougere_colour_leaks_into_other_palettes() {
        for (dark in listOf(false, true)) {
            val fougere = roles(colorScheme(ThemePalette.Fougere, dark)).values.toSet() - shared
            for (p in ThemePalette.entries - ThemePalette.Fougere) {
                for ((name, c) in roles(colorScheme(p, dark))) {
                    assertTrue("${p.label} ${mode(dark)} $name reprend une couleur de Fougère", c !in fougere)
                }
            }
        }
    }

    /** Boutons tonals, segments et puces sélectionnés : fond tiré du conteneur principal de la palette. */
    @Test
    fun selected_controls_follow_the_palette() {
        for (p in ThemePalette.entries) for (dark in listOf(false, true)) {
            val s = colorScheme(p, dark)
            assertEquals(p.roles(dark).primaryContainer, s.secondaryContainer)
            assertTrue("${p.label} ${mode(dark)}", contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5)
        }
    }

    /** Changer de palette change le schéma : chaque couple palette × mode donne un schéma distinct. */
    @Test
    fun switching_palette_switches_the_scheme() {
        val schemes = ThemePalette.entries.flatMap { p -> listOf(false, true).map { colorScheme(p, it) } }
        for (i in schemes.indices) for (j in schemes.indices) if (i < j) {
            assertNotEquals(roles(schemes[i]), roles(schemes[j]))
            assertNotEquals(schemes[i].secondaryContainer, schemes[j].secondaryContainer)
        }
        // Le thème Kairos suit la même clé : calendrier travail propre à chaque palette.
        for (dark in listOf(false, true)) {
            assertEquals(ThemePalette.Bruyere.roles(dark).ciel, KairosColors.of(ThemePalette.Bruyere, dark).ciel)
        }
    }

    private fun mode(dark: Boolean) = if (dark) "sombre" else "clair"
}
