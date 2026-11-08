package app.kairos.mobile.ui.finances

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Envelope
import app.kairos.mobile.data.FinanceCycle
import app.kairos.mobile.data.FinanceWealth
import app.kairos.mobile.data.FinancesSnapshot
import app.kairos.mobile.data.Freshness
import app.kairos.mobile.ui.kit.MASK
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Logique pure de l'espace Finances : saisie, tri, sections visibles, état
 * hors ligne, libellés. Sans Compose ni réseau, testée sur la JVM.
 */

/** Plafond accepté par le module. */
private const val AMOUNT_MAX = 1_000_000_000.0

private val AMOUNT = Regex("""\d+([.,]\d{1,2})?""")

/**
 * Montant saisi (« 12,50 », « 1 200 », « 40 € ») en euros, ou null s'il n'est pas
 * strictement positif, lisible et sous le plafond : rien ne part au serveur.
 */
fun parseAmount(input: String): Double? {
    val s = input.filterNot { it.isWhitespace() || it == ' ' || it == ' ' }.removeSuffix("€")
    if (!AMOUNT.matches(s)) return null
    return s.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= AMOUNT_MAX }
}

/** Raison affichée quand une réponse n'a rapporté qu'une partie des sections. */
const val PARTIAL = "Données partielles"

/** Une source mise à jour : valeur, date de dernière réponse complète, problème éventuel. */
private data class Source<T>(val value: T?, val at: String?, val issue: String?)

/**
 * Fusion d'une réponse avec l'état précédent. Échec : tout est gardé, avec la raison.
 * Réussite : [fill] reprend les sections omises (source indisponible côté module)
 * dans l'état précédent et dit s'il a dû le faire ; la réponse est alors partielle,
 * et la date de dernière réponse complète ne bouge pas.
 */
private fun <T> merged(prev: T?, prevAt: String?, next: Result<T>, now: String, fill: (T, T) -> Pair<T, Boolean>): Source<T> {
    val value = next.getOrElse { e -> return Source(prev, prevAt, issueOf(e)) }
    val (out, partial) = if (prev == null) value to false else fill(value, prev)
    return if (partial) Source(out, prevAt, PARTIAL) else Source(out, now, null)
}

internal fun issueOf(e: Throwable): String = (e as? ApiError.Server)?.let {
    when {
        it.outdated -> ApiError.Server.OUTDATED
        it.unreadable -> ApiError.Server.UNREADABLE
        else -> "Module indisponible"
    }
} ?: "Hors ligne"

/** [new] s'il est là ; sinon [old], en notant dans [used] qu'une section a été reprise. */
private class Fallback {
    var used = false

    fun <V> pick(new: V?, old: V?): V? {
        if (new == null && old != null) used = true
        return new ?: old
    }
}

/**
 * Nouvel état après une actualisation, source par source : ce qui a répondu
 * remplace l'ancien section par section, le reste est gardé et signalé.
 */
fun financesAfter(prev: FinancesSnapshot, cycle: Result<FinanceCycle>, wealth: Result<FinanceWealth>, now: String): FinancesSnapshot {
    val c = merged(prev.cycle, prev.cycleAt, cycle, now) { new, old ->
        val f = Fallback()
        new.copy(alerts = f.pick(new.alerts, old.alerts), freshness = new.freshness ?: old.freshness) to f.used
    }
    val w = merged(prev.wealth, prev.wealthAt, wealth, now) { new, old ->
        val f = Fallback()
        new.copy(
            netWorth = f.pick(new.netWorth, old.netWorth),
            debt = f.pick(new.debt, old.debt),
            history = f.pick(new.history, old.history),
            loans = f.pick(new.loans, old.loans),
            portfolio = f.pick(new.portfolio, old.portfolio),
            // Fraîcheur bancaire portée par le cycle : reprise sans rendre la réponse partielle.
            freshness = new.freshness ?: old.freshness,
        ) to f.used
    }
    return FinancesSnapshot(c.value, w.value, c.at, w.at, c.issue, w.issue)
}

/** Bandeau d'une source pas à jour (titre, date de dernière réponse complète), ou null. */
fun sourceBanner(issue: String?, at: String?): Pair<String, String?>? =
    issue?.let { it to at?.let { a -> "Dernière mise à jour complète : ${syncLabel(a)}" } }

/** Part consommée ; prévu nul et dépense engagée = dépassement maximal. */
private fun pressure(e: Envelope): Double = when {
    e.planned > 0 -> e.spent / e.planned
    e.spent > 0 -> Double.POSITIVE_INFINITY
    else -> 0.0
}

/** Enveloppes les plus entamées d'abord, puis par nom. */
fun envelopesByPressure(envelopes: List<Envelope>): List<Envelope> =
    envelopes.sortedWith(compareByDescending<Envelope> { pressure(it) }.thenBy { it.poste })

enum class CycleSection { Summary, Envelopes, Charges, Cushion, Alerts }

enum class WealthSection { NetWorth, History, Loans, Portfolio }

/** Sections du cycle à montrer : une source absente ou vide ne laisse pas de titre orphelin. */
fun cycleSections(c: FinanceCycle): List<CycleSection> = listOfNotNull(
    CycleSection.Summary.takeIf { c.available != null },
    CycleSection.Envelopes.takeIf { c.envelopes.isNotEmpty() },
    CycleSection.Charges.takeIf { c.charges.isNotEmpty() },
    CycleSection.Cushion.takeIf { c.cushion != null },
    CycleSection.Alerts.takeIf { !c.alerts.isNullOrEmpty() },
)

fun wealthSections(w: FinanceWealth): List<WealthSection> = listOfNotNull(
    WealthSection.NetWorth.takeIf { w.netWorth != null },
    WealthSection.History.takeIf { !w.history.isNullOrEmpty() },
    WealthSection.Loans.takeIf { !w.loans.isNullOrEmpty() },
    WealthSection.Portfolio.takeIf { w.portfolio?.let { it.buckets.isNotEmpty() || it.lines.isNotEmpty() } == true },
)

/** Libellé d'une catégorie de risque ; inconnue → son nom. */
fun bucketLabel(name: String): String = when (name) {
    "high" -> "Dynamique"
    "mid" -> "Équilibré"
    "low" -> "Prudent"
    else -> name
}

/** Avancement du cycle à [today], de 0 (début) à 1 (fin) ; null si les dates manquent. */
fun cycleProgress(start: String?, end: String?, today: LocalDate): Float? {
    val s = date(start) ?: return null
    val e = date(end) ?: return null
    val total = ChronoUnit.DAYS.between(s, e)
    if (total <= 0) return 1f
    return (ChronoUnit.DAYS.between(s, today).toFloat() / total).coerceIn(0f, 1f)
}

private val DIGITS = Regex("""\p{Nd}+([\s  .,/:]*\p{Nd}+)*""")

/**
 * Texte libre du module (alertes, raisons) : masqué, tout nombre est remplacé.
 * ponytail: dates et quantités sont masquées avec les montants, le texte ne dit pas lequel est lequel.
 */
fun maskDigits(text: String, masked: Boolean): String = if (masked) DIGITS.replace(text, MASK) else text

/** Horodatage avec décalage (RFC 3339) ramené à l'heure de [zone] ; sans décalage, pris tel quel. */
private fun localTime(s: String, zone: ZoneId): LocalDateTime? =
    runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(s.take(19)) }.getOrNull()

private fun date(s: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    s?.let { localTime(it, zone)?.toLocalDate() ?: runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")

/** « 12/11 » pour une date AAAA-MM-JJ ; sinon le texte tel quel. */
fun dayLabel(s: String?, zone: ZoneId = ZoneId.systemDefault()): String? = s?.let { date(it, zone)?.format(DAY_MONTH) ?: it }

/** « nov. 2026 » pour AAAA-MM ; sinon le texte tel quel. */
fun monthLabel(s: String): String =
    runCatching { YearMonth.parse(s.take(7)).let { "${it.month.getDisplayName(TextStyle.SHORT, Locale.FRENCH)} ${it.year}" } }.getOrDefault(s)

/** Bandeau de fraîcheur bancaire (titre, détail), ou null si les données sont à jour. */
fun freshnessBanner(f: Freshness?): Pair<String, String?>? {
    f ?: return null
    val title = when {
        f.consentExpired -> "Accès bancaire à renouveler"
        f.stale -> "Données bancaires à rafraîchir"
        else -> return null
    }
    return title to f.lastSync?.let { "Dernière synchronisation : ${syncLabel(it)}" }
}

internal fun syncLabel(s: String, zone: ZoneId = ZoneId.systemDefault()): String =
    localTime(s, zone)?.format(DateTimeFormatter.ofPattern("dd/MM à HH:mm")) ?: dayLabel(s, zone) ?: s
