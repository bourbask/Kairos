package app.kairos.mobile.ui.kit

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Logique pure de la boîte à outils : formatage, seuils, échelles, descriptions
 * d'accessibilité. Aucune dépendance à Compose, testée sur la JVM.
 */

/** Espace fine insécable (séparateur de milliers) et espace insécable (avant €), usage fr-FR. */
private const val THIN = ' '
private const val NBSP = ' '

/** Ce qui remplace un montant masqué : aucun chiffre, longueur fixe pour ne rien trahir. */
const val MASK = "•••"

private val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
    groupingSeparator = THIN
    decimalSeparator = ','
}

/**
 * Montant en euros au format français, formaté ici plutôt que par la locale
 * du système pour un rendu identique partout. Arrondi commercial.
 */
fun formatEuro(value: Double, cents: Boolean = true): String {
    if (!value.isFinite()) return "—$NBSP€"
    val rounded = BigDecimal.valueOf(value).setScale(if (cents) 2 else 0, RoundingMode.HALF_UP)
    // DecimalFormat n'est pas thread-safe : une instance par appel.
    return DecimalFormat(if (cents) "#,##0.00" else "#,##0", symbols).format(rounded) + "$NBSP€"
}

/** Montant affichable, ou masque sans chiffre. */
fun amountText(value: Double, masked: Boolean, cents: Boolean = true): String =
    if (masked) "$MASK$NBSP€" else formatEuro(value, cents)

/** Ton d'une valeur face à sa limite : neutre, attention, urgent. */
enum class Tone { Neutral, Attention, Urgent }

/** ≤ 80 % neutre, > 80 % attention, > 100 % urgent. Limite nulle : tout usage est un dépassement. */
fun barTone(value: Double, limit: Double): Tone = when {
    limit <= 0 -> if (value > 0) Tone.Urgent else Tone.Neutral
    value > limit -> Tone.Urgent
    value > limit * 0.8 -> Tone.Attention
    else -> Tone.Neutral
}

/** Part remplie d'une barre, bornée à [0, 1]. */
fun fill(value: Double, limit: Double): Float =
    if (limit <= 0 || !value.isFinite()) (if (value > 0) 1f else 0f) else (value / limit).toFloat().coerceIn(0f, 1f)

/**
 * Points d'une courbe normalisés dans [0, 1] : x réparti, y = 0 au minimum, 1 au maximum.
 * Série plate ou point unique : centrée. Valeurs non finies : traitées comme le minimum.
 */
fun linePoints(values: List<Double>): List<Pair<Float, Float>> {
    val finite = values.filter { it.isFinite() }
    if (values.isEmpty()) return emptyList()
    val min = finite.minOrNull() ?: 0.0
    val span = (finite.maxOrNull() ?: 0.0) - min
    val last = values.size - 1
    return values.mapIndexed { i, v ->
        val x = if (last == 0) 0.5f else i.toFloat() / last
        val y = if (span == 0.0) 0.5f else ((if (v.isFinite()) v else min) - min).div(span).toFloat()
        x to y
    }
}

/** Parts d'un anneau, de somme 1 ; négatifs comptés pour zéro ; tout à zéro → parts nulles. */
fun donutWeights(values: List<Double>): List<Float> {
    val clean = values.map { if (it.isFinite() && it > 0) it else 0.0 }
    val total = clean.sum()
    return clean.map { if (total == 0.0) 0f else (it / total).toFloat() }
}

private fun toneWord(tone: Tone) = when (tone) {
    Tone.Neutral -> "dans les limites"
    Tone.Attention -> "proche de la limite"
    Tone.Urgent -> "limite dépassée"
}

/* Descriptions pour les lecteurs d'écran : masquées, elles ne contiennent aucun chiffre. */

fun describeBar(label: String, value: Double, limit: Double, format: (Double) -> String, masked: Boolean): String {
    val tone = toneWord(barTone(value, limit))
    return if (masked) "$label, $tone" else "$label : ${format(value)} sur ${format(limit)}, $tone"
}

fun describeLine(label: String, values: List<Double>, format: (Double) -> String, masked: Boolean): String {
    if (values.isEmpty()) return "$label, aucune donnée"
    val first = values.first()
    val last = values.last()
    val trend = when {
        last > first -> "en hausse"
        last < first -> "en baisse"
        else -> "stable"
    }
    return if (masked) "$label, $trend" else "$label : de ${format(first)} à ${format(last)}, $trend"
}

fun describeDonut(label: String, parts: List<Pair<String, Double>>, format: (Double) -> String, masked: Boolean): String {
    val weights = donutWeights(parts.map { it.second })
    val items = parts.mapIndexed { i, (name, v) ->
        if (masked) name else "$name ${format(v)} (${(weights[i] * 100).roundToInt()} %)"
    }
    return "$label : ${items.joinToString(", ")}"
}
