package app.kairos.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/** Réglage du thème ; clair par défaut, automatique selon la luminosité ambiante. */
enum class ThemeMode { Clair, Sombre, Auto }

private val KairosShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun KairosTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = KairosTypography, shapes = KairosShapes) {
        // Texte posé hors d'une Surface : couleur du thème, pas le noir par défaut de Compose.
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalKairosColors provides if (dark) DarkKairosColors else LightKairosColors,
            content = content,
        )
    }
}
