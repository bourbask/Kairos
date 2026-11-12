package app.kairos.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.pow

/** Rôles d'une palette dans un mode (clair ou sombre). */
@Immutable
data class ThemeRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val attention: Color,
    /** Texte au ton attention : lisible sur le fond et sur les cartes. */
    val attentionText: Color,
    val urgent: Color,
    val discreet: Color,
    val divider: Color,
    /** Calendrier travail, distinct du conteneur principal (perso). */
    val ciel: Color,
    val nuit: Color,
    /** Fond du bouton Kairos. */
    val btn: Color,
    /** Corps du personnage posé sur le bouton. */
    val body: Color,
    /** Carte surélevée en sombre ; dérivée de la carte si absente. */
    val surfaceHigh: Color? = null,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

/**
 * Palettes au choix, chacune déclinée en clair et en sombre ; [ink] encre les
 * traits du personnage dans les deux modes. Fougère reste la palette par défaut.
 */
enum class ThemePalette(val label: String, val mood: String, val ink: Color, val light: ThemeRoles, val dark: ThemeRoles) {
    Fougere(
        "Fougère", "Sous-bois, papier, ambre.", c(0x1F2620),
        ThemeRoles(
            c(0x4F7942), c(0xFFFFFF), c(0xE3EDDA), c(0x2F4A28), c(0xF7F5EF), c(0xFFFFFF), c(0x1F2620),
            c(0xD9952B), c(0x8A5A12), c(0xB8463A), c(0x636B62), c(0xE4E1D8), c(0xDCE7F0), c(0x23384A),
            btn = c(0x4F7942), body = c(0xF7F5EF),
        ),
        ThemeRoles(
            c(0x7FAF6E), c(0x121712), c(0x243324), c(0xB9D8AA), c(0x121712), c(0x1B221B), c(0xE6EBE3),
            c(0xE8AE4E), c(0xE8AE4E), c(0xE07A6C), c(0x9AA597), c(0x404A40), c(0x2A3340), c(0xC9D8EA),
            // Bouton un peu plus sombre que la couleur principale : corps clair à 3:1 au moins.
            btn = c(0x6E9860), body = c(0xF7F5EF), surfaceHigh = c(0x222A22),
        ),
    ),
    Estampe(
        "Estampe", "Gravure sur bois : indigo profond, papier de riz, cachet vermillon.", c(0x1A1F2B),
        ThemeRoles(
            c(0x2F4B7C), c(0xFFFFFF), c(0xDDE4F1), c(0x1B2D4F), c(0xF2F1EC), c(0xFFFFFF), c(0x1A1F2B),
            c(0xD19A2A), c(0x7A560E), c(0xC2381F), c(0x5D6372), c(0xE0DED6), c(0xE2EDE6), c(0x1F3B2E),
            btn = c(0x2F4B7C), body = c(0xF2F1EC),
        ),
        ThemeRoles(
            c(0xA3B9E3), c(0x101828), c(0x22314F), c(0xCCD8F0), c(0x11141B), c(0x1A1E28), c(0xE6E8EE),
            c(0xE5B65A), c(0xE5B65A), c(0xF28B73), c(0x9BA1AF), c(0x353B49), c(0x22332A), c(0xCFE3D6),
            btn = c(0x3B5C95), body = c(0xF2F1EC),
        ),
    ),
    Bruyere(
        "Bruyère", "Lande en fin d'été : mauve de bruyère, granit, fleur de genêt.", c(0x241D22),
        ThemeRoles(
            c(0x7B4B6A), c(0xFFFFFF), c(0xF1E3EB), c(0x4A2640), c(0xF6F3F3), c(0xFFFFFF), c(0x241D22),
            c(0xD6A514), c(0x6E5500), c(0xB13846), c(0x685F66), c(0xE6DEE1), c(0xDFE9E1), c(0x2B3F30),
            btn = c(0x7B4B6A), body = c(0xF6F3F3),
        ),
        ThemeRoles(
            c(0xD7A9C7), c(0x2C1426), c(0x3A2534), c(0xF1D7E7), c(0x161214), c(0x201A1E), c(0xEEE7EB),
            c(0xE6C04A), c(0xE6C04A), c(0xF08591), c(0xAA9FA6), c(0x40353B), c(0x26332A), c(0xD0E3D5),
            btn = c(0x86557A), body = c(0xF6F3F3),
        ),
    ),
    Graphite(
        "Graphite", "Carnet d'architecte : mine de plomb, calque, trait de surligneur.", c(0x1B1E21),
        ThemeRoles(
            c(0x3B4248), c(0xFFFFFF), c(0xE3E6E8), c(0x23282C), c(0xF3F4F2), c(0xFFFFFF), c(0x1B1E21),
            c(0xE6B80F), c(0x6B5500), c(0xC2371F), c(0x5E656B), c(0xDCDFE1), c(0xE4EDF6), c(0x1F3A55),
            btn = c(0x3B4248), body = c(0xF3F4F2),
        ),
        ThemeRoles(
            c(0xC5CCD2), c(0x15181B), c(0x2C3237), c(0xDCE1E5), c(0x121416), c(0x1B1E21), c(0xE8EAEC),
            c(0xF2CC3D), c(0xF2CC3D), c(0xF2816A), c(0x9CA3A9), c(0x363B40), c(0x22303D), c(0xCFE0F0),
            btn = c(0x4A535B), body = c(0xF3F4F2),
        ),
    ),
    ;

    fun roles(dark: Boolean) = if (dark) this.dark else light
}

/*
 * Correspondance avec Material 3 : primary = principale, primaryContainer = conteneur,
 * background = fond, surface = carte, texte = encre, tertiary = attention (fond ; texte
 * posé dessus = encre en clair, fond en sombre), error = urgent, onSurfaceVariant = texte
 * discret, outlineVariant = trait. secondaryContainer reprend le conteneur principal :
 * c'est le fond des boutons tonals, segments et puces sélectionnés, qui suivent la palette.
 * Le calendrier travail (ciel/nuit) passe par [KairosColors], hors des rôles Material.
 * Chaque rôle est posé explicitement : aucun ne retombe sur la palette de base Material.
 * Le texte « attention » sur fond clair passe par [KairosColors.attentionText].
 */
internal fun colorScheme(palette: ThemePalette, dark: Boolean): ColorScheme = with(palette.roles(dark)) {
    val high = if (dark) surfaceHigh ?: mix(surface, text, 0.035f) else surface
    // Sur la surface inversée (l'encre), la principale de l'autre mode reste lisible.
    val inversePrimary = palette.roles(!dark).primary
    val onAccent = if (dark) background else text
    // Rôles « fixes » : identiques en clair et en sombre, tirés des tons clairs de la palette.
    val l = palette.light
    val fixed = l.primaryContainer
    val fixedDim = mix(l.primaryContainer, l.primary, 0.25f)
    ColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = onPrimaryContainer, onSecondary = if (dark) background else onPrimary,
        secondaryContainer = primaryContainer, onSecondaryContainer = onPrimaryContainer,
        tertiary = attention, onTertiary = onAccent,
        tertiaryContainer = mix(surface, attention, 0.25f), onTertiaryContainer = text,
        background = background, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = primaryContainer, onSurfaceVariant = discreet,
        surfaceTint = primary,
        inverseSurface = text, inverseOnSurface = background,
        error = urgent, onError = if (dark) background else onPrimary,
        errorContainer = mix(surface, urgent, 0.2f), onErrorContainer = text,
        outline = discreet, outlineVariant = divider,
        scrim = Color.Black,
        surfaceBright = high, surfaceDim = background,
        surfaceContainer = surface, surfaceContainerHigh = high, surfaceContainerHighest = primaryContainer,
        surfaceContainerLow = surface, surfaceContainerLowest = if (dark) background else surface,
        primaryFixed = fixed, primaryFixedDim = fixedDim,
        onPrimaryFixed = l.onPrimaryContainer, onPrimaryFixedVariant = l.primary,
        secondaryFixed = fixed, secondaryFixedDim = fixedDim,
        onSecondaryFixed = l.onPrimaryContainer, onSecondaryFixedVariant = l.primary,
        tertiaryFixed = mix(l.surface, l.attention, 0.25f), tertiaryFixedDim = mix(l.surface, l.attention, 0.5f),
        onTertiaryFixed = l.text, onTertiaryFixedVariant = l.attentionText,
    )
}

/** Mélange de deux couleurs, canal par canal, arrondi à l'entier comme la maquette. */
fun mix(a: Color, b: Color, t: Float): Color {
    fun ch(x: Float, y: Float) = floor((x + (y - x) * t) * 255f + 0.5f) / 255f
    return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue))
}

/** Rapport de contraste WCAG entre deux couleurs opaques. */
fun contrast(a: Color, b: Color): Double {
    fun lum(c: Color) = listOf(c.red to 0.2126, c.green to 0.7152, c.blue to 0.0722).sumOf { (v, w) ->
        val x = v.toDouble()
        w * if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    val x = lum(a)
    val y = lum(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

/**
 * Teintes du personnage et de ses scènes, toutes tirées du thème, par rôle de pixel :
 * B fond du bouton · F corps · h ombre du corps · m demi-teinte · s fond assombri ·
 * D encre · A accent · a accent sombre · R urgent.
 */
@Immutable
class SceneColors(btn: Color, body: Color, ink: Color, attention: Color, urgent: Color, eye: Color = ink) {
    /** Indexé comme [ROLES]. */
    val byRole: List<Color> = listOf(
        btn, body, mix(body, btn, 0.3f), mix(body, btn, 0.58f), mix(btn, ink, 0.34f), eye, attention, mix(attention, ink, 0.42f), urgent,
    )

    operator fun get(role: Char): Color = byRole[ROLES.indexOf(role)]

    companion object {
        const val ROLES = "BFhmsDAaR"

        fun of(palette: ThemePalette, dark: Boolean): SceneColors = with(palette.roles(dark)) {
            SceneColors(btn, body, palette.ink, attention, urgent)
        }

        /**
         * Personnage posé sur le fond de l'écran : corps couleur du bouton, fond de l'écran
         * à la place du bouton. Le bouton sombre, trop proche du fond dans certaines palettes
         * (moins de 3:1), cède la place à la couleur principale. Yeux à l'encre ou couleur
         * papier, selon ce qui se détache le mieux du corps.
         */
        fun onPage(palette: ThemePalette, dark: Boolean): SceneColors = with(palette.roles(dark)) {
            val body = if (contrast(btn, background) >= 3.0) btn else primary
            val eye = if (contrast(palette.ink, body) >= contrast(this.body, body)) palette.ink else this.body
            SceneColors(background, body, palette.ink, attention, urgent, eye)
        }
    }
}

/** Rôles propres à Kairos, absents de Material 3. */
@Immutable
data class KairosColors(
    /** Texte au ton attention (« en retard », « à finir vite ») : 4,5:1 au moins sur le fond. */
    val attentionText: Color,
    /** Fond du bouton Kairos. */
    val btn: Color,
    /** Corps du personnage sur le bouton. */
    val body: Color,
    /** Teintes du personnage et de ses scènes sur le bouton. */
    val scene: SceneColors,
    /** Teintes du personnage posé sur le fond de l'écran. */
    val page: SceneColors,
    /** Fond du calendrier travail, distinct de la palette pour séparer les deux agendas. */
    val ciel: Color,
    /** Texte posé sur [ciel]. */
    val nuit: Color,
) {
    companion object {
        /** Rôles du thème courant. */
        val current: KairosColors
            @Composable @ReadOnlyComposable get() = LocalKairosColors.current

        fun of(palette: ThemePalette, dark: Boolean): KairosColors = with(palette.roles(dark)) {
            KairosColors(attentionText, btn, body, SceneColors.of(palette, dark), SceneColors.onPage(palette, dark), ciel, nuit)
        }
    }
}

internal val LocalKairosColors = staticCompositionLocalOf { KairosColors.of(ThemePalette.Fougere, false) }
