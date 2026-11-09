package app.kairos.mobile.ui.medias

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.DownloadItem
import app.kairos.mobile.data.Downloads
import app.kairos.mobile.data.MediaResult
import app.kairos.mobile.data.Medias
import app.kairos.mobile.data.MediasSnapshot
import app.kairos.mobile.data.Release
import app.kairos.mobile.ui.finances.issueOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Logique pure de l'espace Médias : à regarder (reprises, prêts, sans sous-titres FR à part),
 * téléchargements, sorties à venir, recherche et demandes, problèmes, état hors ligne.
 * Sans Compose ni réseau, testée sur la JVM.
 */

const val MEDIAS = "medias"

/** Sections de `app_medias`, telles que nommées dans `errors`. */
const val CONTINUE = "continue"
const val READY = "ready"
const val DOWNLOADS = "downloads"
const val UPCOMING = "upcoming"
const val REQUESTS = "requests"

/** Élément à regarder : [note] = temps restant, ou sous-titres et durée. */
data class Watch(val key: String, val title: String, val note: String)

/** Reprises, prêts avec du français, et prêts sans sous-titres FR (regroupés à part). */
data class WatchList(val resume: List<Watch>, val ready: List<Watch>, val noFrench: List<Watch>) {
    val main: List<Watch> get() = resume + ready
}

fun hasFrench(subs: String): Boolean = subs == "vf" || subs == "st_fr"

fun subsLabel(subs: String): String = when (subs) {
    "vf" -> "VF"
    "st_fr" -> "ST FR"
    "recherche" -> "Sous-titres en recherche"
    else -> "Sans sous-titres FR"
}

/** « 12 min », « 1 h », « 2 h 05 ». */
fun durationLabel(min: Int): String = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${"%02d".format(min % 60)}"
}

/** Reprises d'abord ; un élément repris n'est pas répété parmi les prêts. [hidden] : gestes « vu » en cours. */
fun toWatch(m: Medias, hidden: Set<String> = emptySet()): WatchList {
    val resume = m.resume.orEmpty().filter { it.key !in hidden }
        .map { Watch(it.key, it.title, it.leftMin?.let { l -> "Reste ${durationLabel(l)}" } ?: "En cours") }
    val taken = m.resume.orEmpty().map { it.key }.toSet() + hidden
    val (fr, other) = m.ready.orEmpty().filter { it.key !in taken }.partition { hasFrench(it.subs) }
    fun watch(r: app.kairos.mobile.data.ReadyItem) =
        Watch(r.key, r.title, listOfNotNull(subsLabel(r.subs), r.minutes?.let(::durationLabel)).joinToString(" · "))
    return WatchList(resume, fr.map(::watch), other.map(::watch))
}

/** Nombre d'éléments à regarder, pour la carte Espaces. */
fun readyCount(m: Medias): Int = toWatch(m).let { it.main.size + it.noFrench.size }

/** « vu » sous la forme d'un geste « fait » : même fenêtre d'annulation que le fil. */
fun watchedCard(w: Watch): Card = Card(
    id = w.key,
    title = w.title,
    module = MEDIAS,
    done = CardDone("app_media_watched", buildJsonObject { put("key", w.key) }),
)

/** Message à afficher après « vu » : seul l'imprévu (déjà vu, introuvable) s'annonce. */
fun watchedNotice(message: String): String? = message.takeUnless { it.endsWith("marqué vu.") }

/** Retire un élément vu de l'état gardé, sans attendre la relecture. */
fun withoutWatched(s: MediasSnapshot, key: String): MediasSnapshot = s.copy(
    medias = s.medias?.let { m ->
        m.copy(resume = m.resume?.filterNot { it.key == key }, ready = m.ready?.filterNot { it.key == key })
    },
)

/** « 50 % », borné à [0, 100]. */
fun progressLabel(p: Double): String = "${(if (p.isNaN()) 0.0 else p.coerceIn(0.0, 1.0) * 100).roundToInt()} %"

fun isStuck(d: DownloadItem): Boolean = d.state == "bloque" || d.state == "erreur"

fun stuck(d: Downloads): Int = d.items.count(::isStuck)

/** État d'un téléchargement, lisible sans la barre. */
fun downloadLine(d: DownloadItem): String = when (d.state) {
    "erreur" -> listOfNotNull("Erreur", d.why).joinToString(" : ")
    "bloque" -> listOfNotNull("Bloqué", d.why).joinToString(" : ")
    "pause" -> "En pause"
    "attente" -> "En attente"
    else -> listOfNotNull(progressLabel(d.progress), d.etaMin?.let { "reste ${durationLabel(it)}" }).joinToString(" · ")
}

private fun plural(n: Int, word: String) = "$n $word${if (n > 1) "s" else ""}"

/** Résumé de la file : en pause (sans liste si vide), sinon nombre d'éléments et de bloqués. */
fun downloadsHeadline(d: Downloads): String {
    val count = d.items.size
    if (d.paused) return if (count == 0) "En pause" else "En pause · ${plural(count, "élément")}"
    if (count == 0) return "Rien en téléchargement"
    val k = stuck(d)
    return plural(count, "élément") + if (k > 0) " · ${plural(k, "bloqué")}" else ""
}

fun pauseAction(d: Downloads): String = if (d.paused) "reprise" else "pause"

fun pauseButton(d: Downloads): String = if (d.paused) "Reprendre" else "Mettre en pause"

fun pauseArgs(d: Downloads): JsonObject = pauseArgs(pauseAction(d))

fun pauseArgs(action: String): JsonObject = buildJsonObject { put("action", action) }

/** Gestes proposés (libellé, action) ; file illisible ([d] null) : état inconnu, les deux restent possibles. */
fun pauseChoices(d: Downloads?): List<Pair<String, String>> =
    if (d == null) listOf("Mettre en pause" to "pause", "Reprendre" to "reprise") else listOf(pauseButton(d) to pauseAction(d))

data class DayGroup(val day: String, val label: String, val releases: List<Release>)

private val DAY = DateTimeFormatter.ofPattern("EEEE dd/MM", Locale.FRENCH)

private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

/** « aujourd'hui », « demain », « mercredi 11/11 » ; le texte tel quel s'il n'est pas une date. */
private fun dayWord(s: String, today: LocalDate): String = when (val d = date(s)) {
    null -> s
    today -> "aujourd'hui"
    today.plusDays(1) -> "demain"
    else -> d.format(DAY)
}

/** Sorties groupées par jour, dans l'ordre, puis par heure ; dates locales du module. */
fun upcomingDays(list: List<Release>, today: LocalDate): List<DayGroup> =
    list.sortedWith(compareBy<Release> { it.day }.thenBy { it.time ?: "" })
        .groupBy { it.day }
        .map { (day, r) -> DayGroup(day, dayWord(day, today).replaceFirstChar { it.titlecase(Locale.FRENCH) }, r) }

fun releaseNote(r: Release): String =
    listOfNotNull(r.time, r.what.ifBlank { null }, "déjà là".takeIf { r.have }).joinToString(" · ")

private val EPISODE = Regex("""S\d+E\d+""")

/** Prochaine sortie pas encore là, pour la carte Espaces : « Série S02E05 · demain 00:30 ». */
fun nextRelease(m: Medias, today: LocalDate): String? {
    val r = m.upcoming.orEmpty()
        .filter { !it.have && (date(it.day)?.isBefore(today) != true) }
        .minWithOrNull(compareBy<Release> { it.day }.thenBy { it.time ?: "" }) ?: return null
    val name = if (EPISODE.matches(r.what)) "${r.title} ${r.what}" else r.title
    return "$name · ${listOfNotNull(dayWord(r.day, today), r.time).joinToString(" ")}"
}

/** Raison de la première des [sections] tombée, ou null. */
fun sectionIssue(m: Medias, vararg sections: String): String? = m.errors.firstOrNull { it.section in sections }?.message

/** Bandeau des problèmes : services en panne (une fois chacun), téléchargements bloqués. */
fun problems(m: Medias): List<String> {
    val down = m.errors.map { it.message.replaceFirstChar { c -> c.titlecase(Locale.FRENCH) } }.distinct()
    val k = m.downloads?.let(::stuck) ?: 0
    return down + listOfNotNull(if (k > 0) "${plural(k, "téléchargement")} ${if (k > 1) "bloqués" else "bloqué"}" else null)
}

/** État d'un résultat de recherche : seul [Open] est un bouton, les autres une pastille. */
enum class Ask(val label: String, val enabled: Boolean) {
    Have("Déjà là", false),
    Requested("Déjà demandé", false),
    Open("Demander", true),
}

fun askKey(r: MediaResult): String = "${r.mediaType}/${r.id}"

/** [asked] : réponses du module aux demandes faites depuis l'appareil, sans attendre une nouvelle recherche. */
fun askState(r: MediaResult, asked: Map<String, Ask>): Ask = when {
    r.have -> Ask.Have
    r.requested -> Ask.Requested
    else -> asked[askKey(r)] ?: Ask.Open
}

/** État d'après le message du module : un titre déjà disponible n'est pas « demandé ». */
fun askAfter(message: String): Ask = if ("déjà disponible" in message) Ask.Have else Ask.Requested

fun resultNote(r: MediaResult): String =
    listOfNotNull(if (r.mediaType == "tv") "Série" else "Film", r.year?.toString()).joinToString(" · ")

fun requestArgs(r: MediaResult): JsonObject = buildJsonObject {
    put("id", r.id)
    put("media_type", r.mediaType)
}

/** Le module ignore une requête de moins de 2 caractères utiles. */
fun canSearch(q: String): Boolean = q.trim().length >= 2

/** Nouvel état après une lecture : la réponse remplace l'ancien ; sinon il est gardé avec la raison. */
fun mediasAfter(prev: MediasSnapshot, medias: Result<Medias>, now: String): MediasSnapshot =
    medias.fold(
        onSuccess = { MediasSnapshot(it, now, null) },
        onFailure = { prev.copy(issue = issueOf(it)) },
    )
