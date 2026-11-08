package app.kairos.mobile.ui.today

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.Mark
import java.time.LocalTime

enum class Phase { Maintenant, Ensuite, Passe, Fait, PlusTard }

data class TimelineItem(val card: Card, val phase: Phase)

private fun Card.at(): LocalTime? = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/**
 * Ordre du fil du jour : l'élément en cours en tête, puis la suite, les
 * éléments sans heure, ceux déjà passés, et enfin ceux marqués (fait, plus tard).
 * L'élément en cours est le dernier dont l'heure est atteinte, sinon le prochain.
 */
fun timeline(cards: List<Card>, marks: Map<String, Mark>, now: LocalTime): List<TimelineItem> {
    val (marked, open) = cards.partition { it.key in marks }
    val timed = open.filter { it.at() != null }.sortedBy { it.at() }
    val untimed = open.filter { it.at() == null }
    val current = timed.indexOfLast { it.at()!! <= now }.coerceAtLeast(0)
    return buildList {
        timed.drop(current).forEachIndexed { i, c -> add(TimelineItem(c, if (i == 0) Phase.Maintenant else Phase.Ensuite)) }
        untimed.forEach { add(TimelineItem(it, Phase.Ensuite)) }
        timed.take(current).forEach { add(TimelineItem(it, Phase.Passe)) }
        marked.forEach { add(TimelineItem(it, if (marks[it.key] == Mark.Fait) Phase.Fait else Phase.PlusTard)) }
    }
}

fun greeting(now: LocalTime): String = when (now.hour) {
    in 5..11 -> "Bonjour"
    in 12..17 -> "Bon après-midi"
    else -> "Bonsoir"
}

/** Ligne du lendemain d'après le rythme de travail transmis par le serveur. */
fun tomorrowLine(work: String?): String? = when (work) {
    null, "" -> null
    "sur site" -> "Demain sur site"
    "télétravail" -> "Demain en télétravail"
    "repos" -> "Demain repos"
    else -> "Demain · $work"
}

/** Temps estimé pour traiter la pile, à raison d'une dizaine de secondes par carte. */
fun decisionsEta(count: Int): String = "≈ ${maxOf(1, (count * 10 + 59) / 60)} min"
