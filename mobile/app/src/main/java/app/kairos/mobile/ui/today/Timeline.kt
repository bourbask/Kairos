package app.kairos.mobile.ui.today

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.Mark
import java.time.LocalTime

enum class Phase { Maintenant, Ensuite, Passe, Fait, PlusTard }

data class TimelineItem(val card: Card, val phase: Phase)

private fun Card.at(): LocalTime? = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/**
 * Ordre du fil du jour : chronologique et stable, les éléments sans heure à la fin.
 * Marquer un élément (fait, plus tard) ne déplace jamais aucune carte : il change
 * seulement d'aspect. « Maintenant » est le dernier élément ouvert dont l'heure est
 * atteinte, à condition qu'aucun élément ouvert ne le suive déjà dans le temps ;
 * sinon le prochain élément ouvert. Un élément marqué n'est jamais « maintenant ».
 * [pending] (geste en cours) est gardé pour compatibilité : l'ordre ne dépend plus des marques.
 */
@Suppress("UNUSED_PARAMETER")
fun timeline(cards: List<Card>, marks: Map<String, Mark>, now: LocalTime, pending: Set<String> = emptySet()): List<TimelineItem> {
    val ordered = cards.filter { it.at() != null }.sortedBy { it.at() } + cards.filter { it.at() == null }
    val timed = ordered.filter { it.at() != null }
    val open = timed.filter { marks[it.key] == null }
    // Dernier élément ouvert commencé, sauf si un élément plus récent (déjà commencé) a été marqué :
    // on est alors passé à la suite. Sinon, le prochain élément ouvert. Jamais un élément sans heure.
    val started = open.lastOrNull { it.at()!! <= now }
    val movedOn = started != null && timed.any { marks[it.key] != null && it.at()!! > started.at()!! && it.at()!! <= now }
    val current = if (started != null && !movedOn) started else open.firstOrNull { it.at()!! > now }
    val currentAt = current?.at()
    return ordered.map { c ->
        val phase = when (marks[c.key]) {
            Mark.Fait -> Phase.Fait
            Mark.PlusTard -> Phase.PlusTard
            null -> when {
                c === current -> Phase.Maintenant
                c.at() == null -> Phase.Ensuite
                currentAt != null && c.at()!! < currentAt -> Phase.Passe
                currentAt == null && c.at()!! <= now -> Phase.Passe
                else -> Phase.Ensuite
            }
        }
        TimelineItem(c, phase)
    }
}

/** Position de l'élément « maintenant » (pour y amener le fil à l'ouverture), -1 s'il n'y en a pas. */
fun currentIndex(items: List<TimelineItem>): Int = items.indexOfFirst { it.phase == Phase.Maintenant }

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
