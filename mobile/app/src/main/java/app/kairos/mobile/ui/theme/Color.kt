package app.kairos.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Palette de Kairos, déclinée en clair et en sombre. */
object Palette {
    object Clair {
        val Fougere = Color(0xFF4F7942)
        val SousBois = Color(0xFF2F4A28)
        val Pousse = Color(0xFFE3EDDA)
        val Papier = Color(0xFFF7F5EF)
        val Surface = Color(0xFFFFFFFF)
        val Encre = Color(0xFF1F2620)
        val Ambre = Color(0xFFD9952B)
        val Brique = Color(0xFFB8463A)
        val Discret = Color(0xFF6B746A)
        val Trait = Color(0xFFE4E1D8)
    }

    object Sombre {
        val Fougere = Color(0xFF7FAF6E)
        val SousBois = Color(0xFFB9D8AA)
        val Pousse = Color(0xFF243324)
        val Papier = Color(0xFF121712)
        val Surface = Color(0xFF1B221B)
        val Encre = Color(0xFFE6EBE3)
        val Ambre = Color(0xFFE8AE4E)
        val Brique = Color(0xFFE07A6C)
        val Discret = Color(0xFF9AA597)
        val Trait = Color(0xFF2A332A)
    }

    /** Corps du personnage sur le bouton Kairos, identique dans les deux thèmes. */
    val CorpsKairos = Color(0xFFF7F5EF)
}

/*
 * Correspondance avec Material 3 : primary = Fougère, primaryContainer = Pousse
 * (texte Sous-bois), background = Papier, surface = Surface, texte = Encre,
 * tertiary = Ambre (attention), error = Brique (urgent),
 * onSurfaceVariant = texte discret, outlineVariant = trait de séparation.
 */
internal val LightColors: ColorScheme = with(Palette.Clair) {
    lightColorScheme(
        primary = Fougere, onPrimary = Color.White,
        primaryContainer = Pousse, onPrimaryContainer = SousBois,
        secondary = SousBois, onSecondary = Color.White,
        secondaryContainer = Pousse, onSecondaryContainer = SousBois,
        tertiary = Ambre, onTertiary = Color.White,
        error = Brique, onError = Color.White,
        background = Papier, onBackground = Encre,
        surface = Surface, onSurface = Encre,
        surfaceVariant = Pousse, onSurfaceVariant = Discret,
        surfaceContainerLowest = Surface, surfaceContainerLow = Surface,
        surfaceContainer = Surface, surfaceContainerHigh = Surface, surfaceContainerHighest = Pousse,
        outline = Discret, outlineVariant = Trait,
    )
}

internal val DarkColors: ColorScheme = with(Palette.Sombre) {
    darkColorScheme(
        primary = Fougere, onPrimary = Papier,
        primaryContainer = Pousse, onPrimaryContainer = SousBois,
        secondary = SousBois, onSecondary = Papier,
        secondaryContainer = Pousse, onSecondaryContainer = SousBois,
        tertiary = Ambre, onTertiary = Papier,
        error = Brique, onError = Papier,
        background = Papier, onBackground = Encre,
        surface = Surface, onSurface = Encre,
        surfaceVariant = Pousse, onSurfaceVariant = Discret,
        surfaceContainerLowest = Papier, surfaceContainerLow = Surface,
        surfaceContainer = Surface, surfaceContainerHigh = Surface, surfaceContainerHighest = Pousse,
        outline = Discret, outlineVariant = Trait,
    )
}
