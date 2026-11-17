package app.kairos.mobile.ui.politique.jeux.kit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.kairos.mobile.ui.theme.KairosColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Couleurs des jeux, en deux familles.
 * Sémantiques, fixes quelle que soit la palette : vert = juste, rouge = faux, ambre = entre les deux (et l'or de l'étoile).
 * Navigation, tirées de la palette : panneau « suivante » et mer de la carte ; elles basculent sur le ciel / nuit de la
 * palette (puis sur un gris) quand la couleur principale se confondrait avec le vert ou le rouge sémantiques.
 * La couleur principale elle-même (`colorScheme.primary`) reste celle de la sélection, des points du pager, des barres.
 */
@Immutable
class CouleursJeux(
    /** Vert : coche, bonne réponse marquée, liseré. */
    val juste: Color,
    /** Fond vert du panneau réponse et des pièces justes. */
    val justeFond: Color,
    /** Texte sur [justeFond]. */
    val justeEncre: Color,
    /** Texte ou glyphe sur [juste]. */
    val surJuste: Color,
    val faux: Color,
    val ambre: Color,
    /** Panneau « suivante » (et mer de la carte) : fond, puis flèche / trait. */
    val suivant: Color,
    val suivantEncre: Color,
) {
    /** Échelle d'intensité 1 à 4 : vert, ambre, ambre-rouge, rouge. */
    fun echelle(n: Int): Color = when (n) {
        1 -> juste
        2 -> ambre
        3 -> mix(ambre, faux, 0.5f)
        else -> faux
    }

    companion object {
        val current: CouleursJeux
            @Composable @ReadOnlyComposable get() {
                val s = MaterialTheme.colorScheme
                val k = KairosColors.current
                val dark = s.background.luminance() < 0.5f
                val sem = if (dark) SEMANTIQUE_SOMBRE else SEMANTIQUE_CLAIR
                val (fond, encre) = teinteSuivant(s.primary, s.primaryContainer, s.onPrimaryContainer, k.ciel, k.nuit, mix(s.surface, s.onSurface, 0.1f), s.onSurfaceVariant, dark)
                return CouleursJeux(sem.juste, sem.justeFond, sem.justeEncre, sem.surJuste, sem.faux, sem.ambre, fond, encre)
            }
    }
}

/** Teintes sémantiques validées (maquette, palette Fougère) : identiques pour toutes les palettes. */
class Semantique(val juste: Color, val justeFond: Color, val justeEncre: Color, val surJuste: Color, val faux: Color, val ambre: Color)

val SEMANTIQUE_CLAIR = Semantique(Color(0xFF4F7942), Color(0xFFE3EDDA), Color(0xFF2F4A28), Color.White, Color(0xFFB8463A), Color(0xFFD9952B))
val SEMANTIQUE_SOMBRE = Semantique(Color(0xFF7FAF6E), Color(0xFF243324), Color(0xFFB9D8AA), Color(0xFF121712), Color(0xFFE07A6C), Color(0xFFE8AE4E))

/** Teinte (0 à 360°) et saturation (0 à 1, au sens HSV) d'une couleur. */
fun teinte(c: Color): Pair<Float, Float> {
    val r = c.red
    val g = c.green
    val b = c.blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    if (d == 0f) return 0f to 0f
    val h = when (mx) {
        r -> ((g - b) / d).mod(6f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return h to (d / mx)
}

/** Écart de teinte circulaire, en degrés (0 à 180). */
fun ecartTeinte(a: Float, b: Float): Float = abs(a - b).let { min(it, 360f - it) }

/** Saturation sous laquelle une couleur passe pour neutre : elle ne se confond avec aucune couleur sémantique. */
const val NEUTRE_SAT = 0.15f
/** Écarts de teinte minimaux au vert et au rouge sémantiques. */
const val ECART_VERT = 45f
const val ECART_ROUGE = 30f

/** [c] se confond-elle avec le vert ou le rouge sémantiques ? */
fun procheSemantique(c: Color, sem: Semantique = SEMANTIQUE_CLAIR): Boolean {
    val (h, sat) = teinte(c)
    if (sat < NEUTRE_SAT) return false
    return ecartTeinte(h, teinte(sem.juste).first) < ECART_VERT || ecartTeinte(h, teinte(sem.faux).first) < ECART_ROUGE
}

/**
 * Panneau « suivante » : conteneur principal et son encre ; si la principale se confond avec le vert ou le rouge
 * sémantiques, le ciel / nuit de la palette ; s'il s'y confond aussi, le gris de la surface.
 */
fun teinteSuivant(
    primary: Color, container: Color, onContainer: Color, ciel: Color, nuit: Color, neutre: Color, neutreEncre: Color, dark: Boolean,
): Pair<Color, Color> {
    val sem = if (dark) SEMANTIQUE_SOMBRE else SEMANTIQUE_CLAIR
    return when {
        !procheSemantique(primary, sem) -> container to onContainer
        !procheSemantique(nuit, sem) -> ciel to nuit
        else -> neutre to neutreEncre
    }
}
