package app.kairos.mobile.ui.spaces

import app.kairos.mobile.data.AgendaWeek
import app.kairos.mobile.ui.agenda.upcomingWhen
import app.kairos.mobile.ui.finances.PARTIAL
import java.time.LocalDate

/** État d'une rangée Espaces : toujours dessiné, jamais une erreur déguisée en vide. */
enum class SpaceLoad { Loading, Ready, Down }

/** [issue] : pourquoi la dernière lecture n'est pas complète ; une réponse partielle reste montrée. */
fun spaceLoad(hasData: Boolean, issue: String?): SpaceLoad = when {
    issue != null && issue != PARTIAL -> SpaceLoad.Down
    hasData || issue != null -> SpaceLoad.Ready
    else -> SpaceLoad.Loading
}

/** Problème le plus grave de plusieurs sources : une panne passe avant une réponse partielle. */
fun worstIssue(vararg issues: String?): String? = issues.filterNotNull().let { l -> l.firstOrNull { it != PARTIAL } ?: l.firstOrNull() }

/** « 1 dépassée », « 3 dépassées ». */
fun countLabel(n: Int, word: String): String = "$n $word${if (n > 1) "s" else ""}"

/** Ligne d'état de l'agenda : sans la semaine lue, on ne sait pas ce qui vient (jamais « rien »). */
fun agendaStatus(week: AgendaWeek?, today: LocalDate): String {
    week ?: return "À venir : pas encore lu"
    val next = week.upcoming.firstOrNull() ?: return "Rien de prévu"
    return "${next.title} · ${upcomingWhen(next, today)}"
}
