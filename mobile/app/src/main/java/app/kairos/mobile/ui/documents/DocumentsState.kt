package app.kairos.mobile.ui.documents

import app.kairos.mobile.data.DocDeadline
import app.kairos.mobile.data.DocExtraction
import app.kairos.mobile.data.DocHit
import app.kairos.mobile.data.DocNature
import app.kairos.mobile.data.DocPiece
import app.kairos.mobile.data.Documents
import app.kairos.mobile.data.DocumentsSnapshot
import app.kairos.mobile.ui.finances.issueOf
import app.kairos.mobile.ui.kit.Tone
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Logique pure de l'espace Documents (lecture seule) : échéances, pièces attendues,
 * classement, recherche, état hors ligne. Sans Compose ni réseau, testée sur la JVM.
 */

const val DOCUMENTS = "documents"

const val EMPTY_INDEX = "Aucun document indexé pour l'instant."

/** Seuil de l'ambre : une échéance à moins de 30 jours. */
private const val SOON_DAYS = 30L

private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun String.cap() = replaceFirstChar { it.titlecase(Locale.FRENCH) }

private fun plural(n: Int, word: String) = "$n $word${if (n > 1) "s" else ""}"

/** « 14/11/2026 » ; le texte tel quel s'il n'est pas une date. */
fun dayLabel(s: String): String = date(s)?.format(DAY) ?: s

/** « En retard de 3 j », « Aujourd'hui », « Demain », « Dans 5 j ». */
fun timeLeftLabel(at: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, at)
    return when {
        days < 0 -> "En retard de ${-days} j"
        days == 0L -> "Aujourd'hui"
        days == 1L -> "Demain"
        else -> "Dans $days j"
    }
}

/** En retard selon le module, ou date passée. */
fun isOverdue(d: DocDeadline, today: LocalDate): Boolean = d.overdue || date(d.date)?.isBefore(today) == true

/** Brique en retard, ambre sous 30 jours, neutre au-delà ou sans date lisible. */
fun deadlineTone(d: DocDeadline, today: LocalDate): Tone {
    if (isOverdue(d, today)) return Tone.Urgent
    val at = date(d.date) ?: return Tone.Neutral
    return if (ChronoUnit.DAYS.between(today, at) < SOON_DAYS) Tone.Attention else Tone.Neutral
}

/** Échéance à afficher : [note] = motif et organisme (vide pour une nature privée). */
data class DeadlineRow(val date: String, val day: String, val title: String, val note: String, val left: String?, val tone: Tone)

/** « Document privé · type » : ni organisme ni motif pour une nature privée. */
private fun privateTitle(d: DocDeadline) = listOfNotNull("Document privé", d.what.ifBlank { null }).joinToString(" · ")

fun deadlineRow(d: DocDeadline, today: LocalDate): DeadlineRow {
    val at = date(d.date)
    val left = at?.let {
        if (d.overdue && !it.isBefore(today)) "En retard" else timeLeftLabel(it, today)
    }
    val (title, note) = if (d.private) {
        privateTitle(d) to ""
    } else {
        d.what.ifBlank { "document" }.cap() to listOfNotNull(d.kind.ifBlank { null }?.cap(), d.org?.ifBlank { null }).joinToString(" · ")
    }
    return DeadlineRow(d.date, dayLabel(d.date), title, note, left, deadlineTone(d, today))
}

/** Retards d'abord (du plus ancien), puis à venir par date ; date illisible en fin. */
fun deadlineRows(docs: Documents, today: LocalDate): List<DeadlineRow> =
    docs.deadlines
        .sortedWith(compareBy<DocDeadline>({ if (isOverdue(it, today)) 0 else if (date(it.date) != null) 1 else 2 }, { date(it.date) }))
        .map { deadlineRow(it, today) }

fun overdueCount(docs: Documents, today: LocalDate): Int = docs.deadlines.count { isOverdue(it, today) }

fun overdueLabel(n: Int): String? = if (n > 0) "$n en retard" else null

/** Prochaine échéance pas encore passée, pour la carte Espaces. */
fun nextDeadline(docs: Documents, today: LocalDate): String? {
    val d = docs.deadlines
        .filter { !isOverdue(it, today) && date(it.date) != null }
        .minByOrNull { date(it.date)!! } ?: return null
    val left = timeLeftLabel(date(d.date)!!, today).replaceFirstChar { it.lowercase() }
    val head = if (d.private) privateTitle(d) else listOfNotNull(d.kind.ifBlank { null }?.cap(), d.what.ifBlank { "document" }).joinToString(" · ")
    return "$head · $left"
}

data class Pieces(val missing: List<DocPiece>, val present: List<DocPiece>)

/** Manquantes d'abord ; l'ordre de la liste de contrôle (par domaine) est gardé. */
fun pieces(docs: Documents): Pieces = docs.missing.partition { !it.present }.let { (m, p) -> Pieces(m, p) }

fun piecesHeadline(p: Pieces): String {
    val present = plural(p.present.size, "présente")
    return if (p.missing.isEmpty()) "Tout est là · $present" else "${p.missing.size} à fournir · $present"
}

/** Index vide ou extraction pas commencée : un état explicite plutôt que la liste des pièces. */
fun isEmptyIndex(docs: Documents): Boolean = docs.natures.isEmpty() || docs.extraction.total == 0

fun extractionLabel(e: DocExtraction): String =
    if (e.done < e.total) "${e.done} sur ${plural(e.total, "document")} lus" else "${plural(e.total, "document")} ${if (e.total > 1) "lus" else "lu"}"

fun natureNote(n: DocNature): String = listOfNotNull(
    plural(n.count, "document"),
    "privé".takeIf { n.private },
    "${n.unreadable} sans texte lisible".takeIf { !n.private && n.unreadable > 0 },
).joinToString(" · ")

fun hitNote(h: DocHit): String = listOfNotNull(h.nature.ifBlank { null }, h.date?.let(::dayLabel)).joinToString(" · ")

/**
 * Passage découpé en morceaux (texte, en évidence) ; marqueurs « » retirés. Un ouvrant
 * sans fermant met en évidence jusqu'à la fin, un fermant orphelin est ignoré.
 */
fun passageSpans(passage: String): List<Pair<String, Boolean>> {
    val out = mutableListOf<Pair<String, Boolean>>()
    val buf = StringBuilder()
    var bold = false
    fun flush() {
        if (buf.isEmpty()) return
        val last = out.lastOrNull()
        if (last != null && last.second == bold) out[out.lastIndex] = (last.first + buf) to bold else out += buf.toString() to bold
        buf.clear()
    }
    for (c in passage) {
        when (c) {
            '«' -> { flush(); bold = true }
            '»' -> { flush(); bold = false }
            else -> buf.append(c)
        }
    }
    flush()
    return out
}

fun canSearch(q: String): Boolean = q.isNotBlank()

/** Nouvel état après une lecture : la réponse remplace l'ancien ; sinon il est gardé avec la raison. */
fun documentsAfter(prev: DocumentsSnapshot, docs: Result<Documents>, now: String): DocumentsSnapshot =
    docs.fold(
        onSuccess = { DocumentsSnapshot(it, now, null) },
        onFailure = { prev.copy(issue = issueOf(it)) },
    )
