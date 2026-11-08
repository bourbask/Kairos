package app.kairos.mobile.ui.agenda

import app.kairos.mobile.data.AgendaDay
import app.kairos.mobile.data.AgendaEvent
import app.kairos.mobile.data.AgendaSnapshot
import app.kairos.mobile.data.AgendaSync
import app.kairos.mobile.data.AgendaWeek
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Upcoming
import app.kairos.mobile.ui.finances.issueOf
import app.kairos.mobile.ui.finances.syncLabel
import app.kairos.mobile.ui.kit.BlockKind
import app.kairos.mobile.ui.kit.TimeBlock
import app.kairos.mobile.ui.kit.TimeSpan
import app.kairos.mobile.ui.kit.clockText
import app.kairos.mobile.ui.kit.durationText
import app.kairos.mobile.ui.today.InFlight
import app.kairos.mobile.ui.today.refusal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/*
 * Logique pure de l'espace Agenda : bandeau de jours, geste de changement de jour,
 * formulaire d'ajout, état hors ligne, blocs de la frise. Sans Compose ni réseau.
 */

const val STRIP_DAYS = 7L

/** Sept jours à partir d'aujourd'hui. */
fun dayStrip(today: LocalDate): List<LocalDate> = (0 until STRIP_DAYS).map { today.plusDays(it) }

/**
 * Jour atteint par un glissement horizontal de [dx] commencé en [startX] sur une vue
 * de largeur [width] : ← suivant, → précédent, au-delà de [threshold]. Un geste parti
 * à moins de [edge] d'un bord appartient au système (retour) : ignoré. Bornes du bandeau tenues.
 */
fun swipeDay(selected: LocalDate, strip: List<LocalDate>, startX: Float, dx: Float, width: Float, edge: Float, threshold: Float): LocalDate {
    if (startX < edge || startX > width - edge || abs(dx) < threshold) return selected
    val i = strip.indexOf(selected).takeIf { it >= 0 } ?: return selected
    return strip.getOrNull(if (dx < 0) i + 1 else i - 1) ?: selected
}

/** Durée proposée à l'ajout ; [minutes] null = journée entière. */
enum class Span(val label: String, val minutes: Int?) { M30("30 min", 30), H1("1 h", 60), H2("2 h", 120), Day("Journée", null) }

enum class Cal(val id: String, val label: String) { Perso("perso", "Perso"), Travail("travail", "Travail") }

data class AddForm(
    val title: String = "",
    val day: LocalDate,
    val start: LocalTime? = null,
    val span: Span? = null,
    val calendar: Cal = Cal.Perso,
)

/** Même plafond que le module. */
private const val TITLE_MAX = 200

/** Raison du refus du formulaire, ou null s'il peut partir ; une heure déjà passée aujourd'hui est refusée. */
fun formError(f: AddForm, now: LocalDateTime = LocalDateTime.now()): String? = when {
    f.title.isBlank() -> "Donne un titre."
    f.title.trim().length > TITLE_MAX -> "Titre trop long ($TITLE_MAX caractères au plus)."
    f.span == null -> "Choisis une durée."
    f.span != Span.Day && f.start == null -> "Choisis l'heure de début."
    f.span != Span.Day && f.day == now.toLocalDate() && f.start!! < now.toLocalTime().withSecond(0).withNano(0) -> "Cette heure est déjà passée."
    else -> null
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

/** Arguments de `app_event_add` ; formulaire supposé valide. */
fun addArgs(f: AddForm): JsonObject = buildJsonObject {
    put("title", f.title.trim())
    put("day", f.day.toString())
    put("calendar", f.calendar.id)
    val minutes = f.span?.minutes
    if (minutes == null) {
        put("all_day", true)
    } else {
        put("start", f.start!!.format(HHMM))
        put("minutes", minutes)
    }
}

sealed interface AddOutcome {
    data class Invalid(val message: String) : AddOutcome
    /** Un envoi est déjà en cours (double appui) : rien fait. */
    data object Ignored : AddOutcome
    data class Added(val message: String) : AddOutcome
    data class Refused(val message: String) : AddOutcome
}

private const val ADD_KEY = "agenda/add"

/**
 * Envoie l'événement une seule fois : un second appui pendant l'envoi est ignoré
 * ([busy] partagé par le panneau). [ApiError.Unauthorized] remonte.
 */
suspend fun submitEvent(
    form: AddForm,
    busy: InFlight,
    now: LocalDateTime = LocalDateTime.now(),
    call: suspend (JsonObject) -> String,
): AddOutcome {
    formError(form, now)?.let { return AddOutcome.Invalid(it) }
    if (!busy.claim(ADD_KEY)) return AddOutcome.Ignored
    return try {
        AddOutcome.Added(call(addArgs(form)))
    } catch (e: ApiError) {
        AddOutcome.Refused(refusal(e))
    } finally {
        busy.release(ADD_KEY)
    }
}

/**
 * Nouvel état après une lecture : ce qui a répondu remplace l'ancien, le reste est
 * gardé avec la raison. [week] null = pas relue ; [sync] null = pas de synchronisation ;
 * synchronisation sans réponse = échouée, la dernière réussie reste datée.
 * Les jours passés sortent du cache.
 */
fun agendaAfter(
    prev: AgendaSnapshot,
    day: Result<AgendaDay>,
    week: Result<AgendaWeek>?,
    sync: Result<AgendaSync>?,
    now: String,
    today: LocalDate,
): AgendaSnapshot {
    val d = day.getOrNull()
    val w = week?.getOrNull()
    val failure = day.exceptionOrNull() ?: week?.exceptionOrNull()
    val days = (if (d != null) prev.days + (d.day to d) else prev.days).filterKeys { it >= today.toString() }
    return AgendaSnapshot(
        days = days,
        week = w ?: prev.week,
        sync = sync?.getOrElse { AgendaSync(false, prev.sync?.at) } ?: prev.sync,
        fetchedAt = if (failure == null) now else prev.fetchedAt,
        issue = failure?.let(::issueOf),
    )
}

/** Bandeau quand la dernière synchronisation des calendriers a échoué, ou null. */
fun syncBanner(s: AgendaSnapshot): String? {
    val sync = s.sync ?: return null
    if (sync.synced) return null
    return "Agenda pas à jour — " + (sync.at?.let { "dernière synchro ${syncLabel(it)}" } ?: "jamais synchronisé")
}

/** Minutes depuis minuit pour « HH:MM », ou null. */
fun minutesOf(s: String?): Int? = s?.let { runCatching { LocalTime.parse(it).toSecondOfDay() / 60 }.getOrNull() }

/**
 * Blocs de la frise : travail et perso dans deux familles visuelles ; un événement
 * qui commence ou finit un autre jour s'étire jusqu'au bord (« jusqu'à », « dès »),
 * sans début ni fin ce jour-là il rejoint la ligne « journée ».
 */
fun blocksOf(events: List<AgendaEvent>): List<TimeBlock> = events.map { e ->
    val start = minutesOf(e.start)
    val end = minutesOf(e.end)
    val hours = when {
        start == null && end != null -> "jusqu'à ${clockText(end)}"
        start != null && end == null -> "dès ${clockText(start)}"
        else -> null
    }
    TimeBlock(
        title = e.title,
        start = start ?: 0,
        end = end ?: (24 * 60),
        subtitle = listOfNotNull(hours, e.location?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null },
        kind = if (e.calendar == "travail") BlockKind.Secondary else BlockKind.Primary,
        source = calendarLabel(e.calendar),
        allDay = e.allDay || (start == null && end == null),
    )
}

/** Plages libres de la frise, libellées « libre 2 h ». */
fun freeSpans(day: AgendaDay): List<TimeSpan> = day.free.mapNotNull { f ->
    val s = minutesOf(f.start) ?: return@mapNotNull null
    val e = minutesOf(f.end) ?: return@mapNotNull null
    TimeSpan(s, e, "libre ${durationText(f.minutes)}")
}

/** « perso » ou « travail », pour les lecteurs d'écran. */
fun calendarLabel(calendar: String): String = if (calendar == "travail") "travail" else "perso"

private val Fr = Locale.FRENCH
private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d", Fr)

/** « aujourd'hui », « demain », « mar. 10 ». */
fun relativeDay(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "aujourd'hui"
    today.plusDays(1) -> "demain"
    else -> day.format(SHORT_DAY)
}

/** Moment d'un événement à venir : « demain · 9 h ». */
fun upcomingWhen(u: Upcoming, today: LocalDate): String {
    val day = runCatching { LocalDate.parse(u.day) }.getOrNull()?.let { relativeDay(it, today) } ?: u.day
    return listOfNotNull(day, minutesOf(u.start)?.let(::clockText)).joinToString(" · ")
}

/** Libellé du mode de travail du jour (« sur site », « télétravail », « repos »). */
fun workLabel(work: String?): String? = work?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(Fr) }
