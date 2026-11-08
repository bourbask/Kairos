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
 * Une carte de [pending] (geste en cours) garde sa place, avec sa marque.
 */
fun timeline(cards: List<Card>, marks: Map<String, Mark>, now: LocalTime, pending: Set<String> = emptySet()): List<TimelineItem> {
    // Geste en cours : la carte reste à sa place, marquée ; elle ne descend qu'une fois l'issue connue.
    val (marked, open) = cards.partition { it.key in marks && it.key !in pending }
    fun phase(c: Card, p: Phase) = when (marks[c.key]) {
        Mark.Fait -> Phase.Fait
        Mark.PlusTard -> Phase.PlusTard
        null -> p
    }
    val timed = open.filter { it.at() != null }.sortedBy { it.at() }
    val untimed = open.filter { it.at() == null }
    val current = timed.indexOfLast { it.at()!! <= now }.coerceAtLeast(0)
    return buildList {
        timed.drop(current).forEachIndexed { i, c -> add(TimelineItem(c, phase(c, if (i == 0) Phase.Maintenant else Phase.Ensuite))) }
        untimed.forEach { add(TimelineItem(it, phase(it, Phase.Ensuite))) }
        timed.take(current).forEach { add(TimelineItem(it, phase(it, Phase.Passe))) }
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
