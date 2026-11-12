package app.kairos.mobile.ui.politique.kit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.kairos.mobile.ui.theme.contrast

private fun c(hex: Long) = Color(0xFF000000 or hex)

/** Couleurs d'état de l'espace : jamais celles d'un parti. Les textes, fonds et traits courants viennent du thème Kairos. */
@Immutable
class PolColors(
    val solide: Color, val solideBg: Color, val partiel: Color, val partielBg: Color,
    val fragile: Color, val fragileBg: Color, val trompeur: Color, val trompeurBg: Color, val faux: Color,
    val pour: Color, val pourBg: Color, val contre: Color, val contreBg: Color,
    /** Budget sous / au-dessus de la référence : aucun jugement de valeur. */
    val low: Color, val high: Color,
    /** Indépendance des évaluateurs, niveaux 1 à 3 : fond puis encre (le niveau 4 est un contour). */
    val ind1: Color, val ind1Ink: Color, val ind2: Color, val ind2Ink: Color, val ind3: Color, val ind3Ink: Color,
    val tableBg: Color, val tableInk: Color,
) {
    companion object {
        val Light = PolColors(
            c(0x1E6A48), c(0xDCEFE3), c(0x865A0E), c(0xF6EAD0), c(0xA3361F), c(0xF8E0D9), c(0x5E4B8B), c(0xECE6F5), c(0x7E1F14),
            c(0x2C5E8E), c(0xE0EAF5), c(0x8B3A62), c(0xF4E1EA), c(0x237870), c(0xAD4F2B),
            c(0x23384A), c(0xFFFFFF), c(0x5B7891), c(0xFFFFFF), c(0xD5DFE8), c(0x23384A),
            c(0xF7F5EF), c(0x141A15),
        )
        val Dark = PolColors(
            c(0x74CE9C), c(0x1A3326), c(0xE7BE62), c(0x382D14), c(0xF08B79), c(0x3D211B), c(0xBFA8EC), c(0x2C2440), c(0xFF9C8A),
            c(0x8FB5E3), c(0x1E2C3E), c(0xE591BC), c(0x3A1F2D), c(0x5CC2B7), c(0xEC8F68),
            c(0xC9D8EA), c(0x121712), c(0x7F9DB8), c(0x121712), c(0x2E3B48), c(0xDCE7F0),
            c(0x0E120E), c(0xF2F5EF),
        )

        /** Suit le thème Kairos en cours (clair ou sombre), pas seulement celui du système. */
        val current: PolColors
            @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Dark else Light
    }
}

private val INK = c(0x1F2620)

/** Encre ou blanc, selon le meilleur contraste WCAG sur [bg]. */
fun textOn(bg: Color): Color = if (contrast(bg, INK) >= contrast(bg, Color.White)) INK else Color.White

/** L'adresse si c'est une URL https sans espace ni guillemet ; sinon null (la source reste lisible, non ouvrable). */
fun safeHttps(url: String?): String? =
    url?.trim()?.takeIf { Regex("""https://[^\s"'<>]+""").matches(it) }
