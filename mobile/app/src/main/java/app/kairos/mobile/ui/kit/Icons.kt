package app.kairos.mobile.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Icônes de Kairos, toutes au même trait, à poser avec `Icon(KairosIcons.Check, "…")` :
 * la couleur vient du contenu courant (teinte), jamais de l'icône.
 */
object KairosIcons {
    val ChevronBack by lazy { icon("Retour", mirrored = true) { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    val ChevronForward by lazy { icon("Suivant", mirrored = true) { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }
    val ChevronDown by lazy { icon("Fermer") { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) } }
    val Check by lazy { icon("Fait") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    val Plus by lazy { icon("Ajouter") { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) } }
    val Refresh by lazy {
        icon("Actualiser") {
            // Cercle ouvert de 315°, flèche au bout du trait.
            moveTo(20f, 12f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 17.66f, y1 = 6.34f)
            moveTo(17.66f, 2.34f); lineTo(17.66f, 6.34f); lineTo(13.66f, 6.34f)
        }
    }
    val Gear by lazy {
        icon("Réglages") {
            circle(12f, 12f, 3f)
            circle(12f, 12f, 6.5f)
            repeat(8) { i ->
                val a = i * PI / 4
                moveTo(12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat())
                lineTo(12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
            }
        }
    }
    val Eye by lazy { icon("Afficher") { eye() } }
    val Warning by lazy {
        icon("Alerte") {
            moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close()
            moveTo(12f, 10f); verticalLineTo(14f)
            moveTo(12f, 17f); verticalLineTo(17.01f)
        }
    }
    val Search by lazy { icon("Chercher") { circle(10.5f, 10.5f, 6f); moveTo(15f, 15f); lineTo(20f, 20f) } }
    val Trash by lazy {
        icon("Supprimer") {
            moveTo(4f, 7f); horizontalLineTo(20f)
            moveTo(9f, 7f); verticalLineTo(4f); horizontalLineTo(15f); verticalLineTo(7f)
            moveTo(6f, 7f); lineTo(7f, 20f); horizontalLineTo(17f); lineTo(18f, 7f)
            moveTo(10f, 11f); verticalLineTo(16f)
            moveTo(14f, 11f); verticalLineTo(16f)
        }
    }
    val EyeOff by lazy { icon("Masquer") { eye(); moveTo(4f, 4f); lineTo(20f, 20f) } }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        close()
    }

    private fun PathBuilder.eye() {
        moveTo(2f, 12f)
        curveTo(5f, 6f, 19f, 6f, 22f, 12f)
        curveTo(19f, 18f, 5f, 18f, 2f, 12f)
        close()
        circle(12f, 12f, 3f)
    }

    private fun icon(name: String, mirrored: Boolean = false, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).path(
            // Couleur de gabarit : remplacée par la teinte de `Icon`.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        ).build()
}
