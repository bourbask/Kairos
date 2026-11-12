package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.model.alphabetical
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    val who: String = "", val what: String = "", val claimed: Double? = null, val credited: Double? = null, val unit: String = "",
    val inverse: Boolean = false, val labels: List<String> = emptyList(), val src: String? = null, val url: String? = null,
    val ev: String? = null, val lecture: Lecture? = null,
)

@Serializable
data class Measure(
    val who: String = "", val hh: Double? = null, val m: String = "", val b: String = "", val f: String = "",
    val lecture: Lecture? = null,
)

@Serializable
data class BilanRow(
    val l: String = "", val s: String? = null, val v: Double? = null, val total: Boolean = false, val cls: String? = null,
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
    return if (num == null || den == null || den == 0.0) null else num / den
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

/** Un élément (objet ou nombre) d'un fichier, à un chemin pointé ; absent ou illisible : null. */
internal fun <T> PolData.one(file: String, path: String, s: KSerializer<T>): T? {
    var cur: JsonElement? = file(file)
    for (k in path.split('.')) cur = (cur as? JsonObject)?.get(k)
    return cur?.let { try { PolJson.decodeFromJsonElement(s, it) } catch (_: Exception) { null } }
}

/** Échelle de repli, sans échelle publiée : celle des valeurs, zéro compris. */
private fun fallbackScale(rows: List<BilanRow>, unit: String): Scale =
    axisBounds(null, rows.mapNotNull { it.v } + 0.0).let { (lo, hi) -> Scale(lo, hi, unit) }

fun nameOf(who: String) = who.substringBefore(" · ")

/** Dette de référence : valeur publiée, sinon celle de la ligne de référence des programmes. */
fun PolData.refDebt(programs: List<Program>): Double? = one("budgets", "REF", Double.serializer()) ?: programs.firstOrNull { it.ref }?.debt

/** Position d'une dette face à la référence : -1 dessous, 0 égale, 1 dessus. */
fun sideOf(debt: Double, ref: Double): Int = debt.compareTo(ref)

/** Budgets : [stem] faux = la section « tige » est vide ; écarts et balance ne dépendent pas d'elle. */
data class BudgetsState(val programs: List<Program>, val gaps: List<Gap>, val ref: Double?, val axis: Scale?, val cands: List<Program>, val gapCards: List<TableCard>) {
    val stem get() = cands.isNotEmpty() && ref != null
    val below get() = cands.count { ref != null && sideOf(it.debt!!, ref) < 0 }
    val above get() = cands.count { ref != null && sideOf(it.debt!!, ref) > 0 }
    val withDebt get() = programs.filter { it.debt != null }
}

fun budgetsState(data: PolData): BudgetsState {
    val programs = data.decode<Program>("budgets", "PROGRAMS").let { l -> l.filter { it.ref } + l.filter { !it.ref }.alphabetical { it.who } }
    // Écart incomplet gardé (sa balance compte) : la valeur manquante s'affiche « non trouvé », jamais zéro.
    val gaps = data.decode<Gap>("budgets", "GAPS").alphabetical { it.who }
    val cards = gaps.map { g ->
        val (a, b) = gapLabels(g)
        TableCard("Annoncé contre crédité", g.what, lines = listOf("${nameOf(g.who)} : ${g.claimed?.let { fmtNum(it, 1) } ?: NO_VALUE} → ${g.credited?.let { fmtNum(it, 1) } ?: NO_VALUE} ${g.unit}".trim(), "$a / $b"), source = g.src)
    }
    return BudgetsState(programs, gaps, data.refDebt(programs), data.one("budgets", "AXIS", Scale.serializer()), programs.filter { !it.ref && it.debt != null }, cards)
}

data class LivingState(val salaires: List<BilanRow>, val scale: Scale, val programs: List<Program>, val ref: Double?, val measures: List<Measure>, val cards: List<TableCard>) {
    val maxAbs get() = programs.maxOfOrNull { abs(it.hh!!) } ?: 0.0
    val empty get() = salaires.isEmpty() && programs.isEmpty() && measures.isEmpty()
    /** Lignes dessinées : sans valeur, hors des barres (jamais un zéro) ; la balance garde toutes les lignes. */
    val salaireBars get() = salaires.filter { it.v != null }
}

fun livingState(data: PolData): LivingState {
    val all = data.decode<Program>("budgets", "PROGRAMS")
    val measures = data.decode<Measure>("niveau-de-vie", "MEASURES").alphabetical { it.who }
    val cards = measures.map { m ->
        TableCard("Pouvoir d'achat", nameOf(m.who), lines = listOf(m.hh?.let { "${fmtSigned(it)} € par an" } ?: "Par ménage : $NO_VALUE") + m.m.split(" ; ").filter { it.isNotBlank() } + listOf("Pour qui : ${m.b}", "Financé par : ${m.f}"))
    }
    val salaires = data.decode<BilanRow>("niveau-de-vie", "SALAIRES.rows")
    return LivingState(
        salaires, data.one("niveau-de-vie", "SALAIRES.scale", Scale.serializer()) ?: fallbackScale(salaires, " %"),
        all.filter { !it.ref && it.hh != null }.alphabetical { it.who }, data.refDebt(all), measures, cards,
    )
}

val MARK_LABEL = mapOf("keep" to "Poursuit", "flip" to "Inverse", "mix" to "Modifie")

data class BilanState(val ofce: List<BilanRow>, val ipp: List<BilanRow>, val ofceScale: Scale, val ippScale: Scale, val cands: List<String>, val matrix: List<MatrixRow>, val cards: List<TableCard>) {
    val empty get() = ofce.isEmpty() && ipp.isEmpty() && matrix.isEmpty()
    val ofceBars get() = ofce.filter { it.v != null }
    val ippBars get() = ipp.filter { it.v != null }
}

fun bilanState(data: PolData): BilanState {
    val cands = data.decode("bilan", "CANDS", String.serializer()).alphabetical { it }
    val matrix = data.decode<MatrixRow>("bilan", "MATRIX")
    val cards = matrix.map { r ->
        TableCard("Face au bilan sortant · ${r.s}", r.o, lines = cands.map { c -> r.c[c]?.let { x -> "$c — ${MARK_LABEL[x.firstOrNull()] ?: "Position"} : ${x.getOrNull(1).orEmpty()}" } ?: "$c : non trouvé" })
    }
    val ofce = data.decode<BilanRow>("bilan", "OFCE.rows")
    val ipp = data.decode<BilanRow>("bilan", "IPP.rows")
    return BilanState(
        ofce, ipp,
        data.one("bilan", "OFCE.scale", Scale.serializer()) ?: fallbackScale(ofce, " pt"), data.one("bilan", "IPP.scale", Scale.serializer()) ?: fallbackScale(ipp, " %"),
        cands, matrix, cards,
    )
}
