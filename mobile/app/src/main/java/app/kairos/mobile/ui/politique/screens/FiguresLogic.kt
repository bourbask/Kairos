package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.SourceRef
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** Modèles des rubriques chiffrées (budgets, niveau de vie, bilan sortant) : tout est facultatif. */

@Serializable
data class Scale(val min: Double = 0.0, val max: Double = 1.0, val unit: String = "")

@Serializable
data class Program(
    val who: String = "", val party: String = "", val ref: Boolean = false,
    val debt: Double? = null, val deficit: Double? = null, val net: Double? = null, val hh: Double? = null,
    val lecture: Lecture? = null,
)

@Serializable
data class Gap(
    val who: String = "", val what: String = "", val claimed: Double = 0.0, val credited: Double = 0.0, val unit: String = "",
    val inverse: Boolean = false, val labels: List<String> = emptyList(), val src: String? = null, val url: String? = null,
    val ev: String? = null, val lecture: Lecture? = null,
)

@Serializable
data class Measure(
    val who: String = "", val hh: Double = 0.0, val m: String = "", val b: String = "", val f: String = "",
    val lecture: Lecture? = null,
)

@Serializable
data class BilanRow(
    val l: String = "", val s: String? = null, val v: Double = 0.0, val total: Boolean = false, val cls: String? = null,
    val lecture: Lecture? = null,
)

/** Position d'un candidat sur une orientation : `[code, texte]` avec code `keep`, `flip` ou `mix`. */
@Serializable
data class MatrixRow(
    val o: String = "", val s: String = "", val c: Map<String, List<String>> = emptyMap(), val lecture: Lecture? = null,
)

/** Fraction de la piste (0 à 1) : début et largeur d'une barre. */
data class Span(val start: Double, val width: Double)

private fun floor5(v: Double) = floor(v / 5) * 5
private fun ceil5(v: Double) = ceil(v / 5) * 5

/** Bornes de l'axe : celles publiées, élargies (au multiple de 5) si une valeur ou la référence en sort. */
fun axisBounds(axis: Scale?, values: List<Double>): Pair<Double, Double> {
    if (values.isEmpty()) return (axis?.min ?: 0.0) to (axis?.max ?: 1.0)
    val lo = values.min()
    val hi = values.max()
    if (axis == null) return floor5(lo).let { a -> a to ceil5(hi).let { if (it <= a) a + 5 else it } }
    return (if (lo < axis.min) floor5(lo) else axis.min) to (if (hi > axis.max) ceil5(hi) else axis.max)
}

/** Graduations tous les 5 dans l'axe. */
fun axisTicks(min: Double, max: Double): List<Double> =
    generateSequence(ceil5(min)) { it + 5 }.takeWhile { it <= max }.toList()

/** Position de [v] sur l'axe, bornée à la piste. */
fun axisPos(v: Double, min: Double, max: Double): Double = if (max > min) ((v - min) / (max - min)).coerceIn(0.0, 1.0) else 0.0

/** Jauge divergente centrée : le zéro est au milieu, la plus grande valeur absolue ([maxAbs]) remplit une moitié. */
fun householdBar(hh: Double, maxAbs: Double): Span {
    val w = if (maxAbs > 0) (abs(hh) / maxAbs * 0.5).coerceAtMost(0.5) else 0.0
    return Span(if (hh >= 0) 0.5 else 0.5 - w, w)
}

/** Barre à partir du zéro d'une échelle [min]..[max] (le zéro est hors piste si l'échelle est positive). */
fun signedBar(v: Double, min: Double, max: Double): Span {
    val z = axisPos(0.0, min, max)
    val p = axisPos(v, min, max)
    return Span(minOf(p, z), abs(p - z))
}

/** Rapport annoncé / crédité (÷), ou estimé / annoncé si [Gap.inverse] (×) ; null si le diviseur est nul. */
fun gapRatio(g: Gap): Double? {
    val (num, den) = if (g.inverse) g.credited to g.claimed else g.claimed to g.credited
    return if (den == 0.0) null else num / den
}

fun gapRatioLabel(g: Gap): String {
    val r = gapRatio(g) ?: return "—"
    return (if (g.inverse) "×" else "÷") + fmtNum(r, if (r < 3) 1 else 0)
}

/** Libellés des deux barres d'un écart. */
fun gapLabels(g: Gap): Pair<String, String> =
    if (g.labels.size >= 2) g.labels[0] to g.labels[1] else "Annoncé" to if (g.inverse) "Estimé" else "Crédité"

private const val NBSP = ' '

/** Nombre à la française : virgule décimale, espace insécable entre milliers ; décimales superflues supprimées si [dec] = 0. */
fun fmtNum(v: Double, dec: Int = 0): String {
    val scale = Math.pow(10.0, dec.toDouble())
    val r = (abs(v) * scale).roundToLong()
    val int = (r / scale.toLong()).toString().reversed().chunked(3).joinToString(NBSP.toString()).reversed()
    val frac = if (dec > 0) "," + (r % scale.toLong()).toString().padStart(dec, '0') else ""
    return (if (v < 0 && r != 0L) "−" else "") + int + frac
}

fun fmtSigned(v: Double, dec: Int = 0): String = (if (v > 0) "+" else "") + fmtNum(v, dec)

/** « Libellé (https://…) ; complément » : source lisible, lien et complément éventuel. */
class ParsedSource(val ref: SourceRef, val rest: String)

fun parseSource(s: String?): ParsedSource {
    val text = s.orEmpty().trim()
    val m = Regex("""^(.*?)\s*\((https://[^)\s]+)\)\s*(.*)$""").find(text) ?: return ParsedSource(SourceRef(text), "")
    return ParsedSource(SourceRef(m.groupValues[1].trim().ifEmpty { m.groupValues[2] }, m.groupValues[2]), m.groupValues[3].trimStart(';', ',', ' '))
}
