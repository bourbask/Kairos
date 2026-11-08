package app.kairos.mobile.ui.kit

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
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

/**
 * ≤ 80 % neutre, > 80 % attention, > 100 % urgent. Limite nulle : tout usage est un dépassement.
 * Comparaison sans division ni facteur 0,8 (non représentable) : la frontière exacte reste neutre.
 */
fun barTone(value: Double, limit: Double): Tone = when {
    limit <= 0 -> if (value > 0) Tone.Urgent else Tone.Neutral
    value > limit -> Tone.Urgent
    value * 5 > limit * 4 -> Tone.Attention
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

/* Frise horaire : minutes depuis minuit, placement relatif à la plage [startHour, endHour]. */

/** Famille visuelle d'un bloc, pour distinguer deux sources sans nommer laquelle. */
enum class BlockKind { Primary, Secondary }

/** Bloc de la frise, bornes en minutes depuis minuit ; [allDay] l'affiche en ligne du haut. */
data class TimeBlock(
    val title: String,
    val start: Int,
    val end: Int,
    val subtitle: String? = null,
    val tone: Tone = Tone.Neutral,
    val kind: BlockKind = BlockKind.Primary,
    val allDay: Boolean = false,
)

/** Plage mise en valeur (ex. créneau libre), avec son libellé affiché. */
data class TimeSpan(val start: Int, val end: Int, val label: String)

/** Bloc placé : [top] / [bottom] dans [0, 1] de la plage, colonne [column] sur [columns]. */
data class PlacedBlock(val index: Int, val top: Float, val bottom: Float, val column: Int, val columns: Int)

data class TimelineLayout(val allDay: List<TimeBlock>, val placed: List<PlacedBlock>)

/** Position verticale d'une minute dans la plage, bornée à [0, 1]. */
fun minuteFraction(minute: Int, startHour: Int, endHour: Int): Float {
    val span = (endHour - startHour) * 60
    return if (span <= 0) 0f else ((minute - startHour * 60).toFloat() / span).coerceIn(0f, 1f)
}

/**
 * Sépare les blocs « journée » et place les autres. Les blocs qui se chevauchent
 * (bornes qui se touchent exclues) forment un groupe partagé en colonnes côte à côte ;
 * chacun prend la première colonne libre. Un bloc hors de la plage est omis.
 * Un bloc court occupe au moins [minMinutes] à l'écran : cette durée sert au calcul
 * des chevauchements, la vraie fin reste son bas.
 */
fun timelineLayout(blocks: List<TimeBlock>, startHour: Int, endHour: Int, minMinutes: Int = 20): TimelineLayout {
    fun effEnd(b: TimeBlock) = maxOf(b.end, b.start + minMinutes)
    val timed = blocks.withIndex()
        .filter { !it.value.allDay && minuteFraction(it.value.start, startHour, endHour) < minuteFraction(it.value.end, startHour, endHour) }
        .sortedWith(compareBy({ it.value.start }, { -it.value.end }))
    val placed = mutableListOf<PlacedBlock>()
    var group = mutableListOf<Pair<IndexedValue<TimeBlock>, Int>>()
    val columnEnds = mutableListOf<Int>()
    var groupEnd = Int.MIN_VALUE
    fun flush() {
        val n = columnEnds.size
        group.forEach { (b, col) ->
            placed += PlacedBlock(
                b.index,
                minuteFraction(b.value.start, startHour, endHour),
                minuteFraction(b.value.end, startHour, endHour),
                col,
                n,
            )
        }
        group = mutableListOf()
        columnEnds.clear()
        groupEnd = Int.MIN_VALUE
    }
    for (b in timed) {
        if (b.value.start >= groupEnd) flush()
        val free = columnEnds.indexOfFirst { it <= b.value.start }
        val col = if (free >= 0) free else columnEnds.size.also { columnEnds += 0 }
        columnEnds[col] = effEnd(b.value)
        group += b to col
        groupEnd = maxOf(groupEnd, effEnd(b.value))
    }
    flush()
    return TimelineLayout(blocks.filter { it.allDay }, placed.sortedBy { it.index })
}

/** Position du trait de l'heure, seulement si [day] est aujourd'hui et l'heure dans la plage. */
fun nowFraction(day: LocalDate, now: LocalDateTime, startHour: Int, endHour: Int): Float? {
    if (now.toLocalDate() != day) return null
    val minute = now.hour * 60 + now.minute
    return if (minute < startHour * 60 || minute > endHour * 60) null else minuteFraction(minute, startHour, endHour)
}

/** Attente jusqu'au début de la minute suivante, pour rafraîchir un affichage à la minute. */
fun millisToNextMinute(now: LocalDateTime): Long =
    60_000L - (now.second * 1000L + now.nano / 1_000_000)

/** « 9 h », « 9 h 05 ». */
fun clockText(minute: Int): String {
    val h = minute / 60
    val m = minute % 60
    return if (m == 0) "$h h" else "$h h ${m.toString().padStart(2, '0')}"
}

/** « 45 min », « 2 h », « 1 h 30 ». */
fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h ${m.toString().padStart(2, '0')}"
    }
}

fun describeBlock(b: TimeBlock): String {
    val time = if (b.allDay) "toute la journée" else "de ${clockText(b.start)} à ${clockText(b.end)}"
    val tone = when (b.tone) {
        Tone.Neutral -> null
        Tone.Attention -> "à surveiller"
        Tone.Urgent -> "important"
    }
    return listOfNotNull(b.title, time, b.subtitle, tone).joinToString(", ")
}
