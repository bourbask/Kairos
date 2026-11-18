package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.ui.politique.kit.sourceParts
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.politiqueIssue
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Signalement d'une erreur sur une affirmation : mêmes champs, bornes, nettoyage et règle d'adresse
 * que la validation du module, qui refait tout et fait foi.
 */

val REPORT_KINDS: List<Pair<String, String>> = listOf(
    "verdict" to "Le verdict",
    "fait" to "Un fait",
    "source" to "Une source",
    "formulation" to "La formulation de l'affirmation",
    "classement" to "Le classement (thème ou camp)",
)

val REPORT_BASES: List<Pair<String, String>> = listOf(
    "lien" to "Une source, avec son lien",
    "ref" to "Une source, sans lien",
    "carte" to "Une contradiction dans la carte elle-même",
)

data class ReportForm(
    val kind: String = "",
    val target: String = "",
    val what: String = "",
    val basis: String = "",
    val url: String = "",
    val refWho: String = "",
    val refWhen: String = "",
    val refWhat: String = "",
    val refIn: String = "",
    val fix: String = "",
    val oath: Boolean = false,
    /** Clé d'idempotence : un renvoi du même formulaire garde la même référence. */
    val idem: String = UUID.randomUUID().toString(),
)

/** Caractères invisibles ou de direction du texte (bidi, largeur nulle, BOM). */
private fun invisible(cp: Int): Boolean =
    cp == 0x061C || cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x2064 || cp in 0x2066..0x2069 || cp == 0xFEFF

private fun control(cp: Int): Boolean = Character.getType(cp) == Character.CONTROL.toInt()

private fun white(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

private fun String.codePointList(): List<Int> = codePoints().toArray().toList()

private fun List<Int>.str(): String = buildString { this@str.forEach { appendCodePoint(it) } }

/** NFC, sans caractères de contrôle (sauf saut de ligne) ni invisibles, sans espaces en bord. */
fun cleanReport(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFC).codePointList()
        .filter { it == '\n'.code || !(control(it) || invisible(it)) }
        .str().trim()

/** Longueur en caractères (points de code), après nettoyage. */
fun reportLength(s: String): Int = cleanReport(s).let { it.codePointCount(0, it.length) }

private const val NOT_PUBLIC = "Indiquez l'adresse publique d'un site (pas une adresse IP ni un nom local)."

private fun alnum(cp: Int): Boolean = Character.isAlphabetic(cp) || when (Character.getType(cp)) {
    Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
    else -> false
}

/** Adresse https, nom de domaine public, sans identifiants, port absent ou 443 : null si acceptée, sinon le problème. */
fun urlProblem(u: String): String? {
    val t = u.trim()
    if (t.isEmpty()) return "Indiquez l'adresse de la source."
    val cps = t.codePointList()
    if (cps.size > 300 || cps.any { white(it) || control(it) || invisible(it) }) return "Cette adresse n'est pas valide."
    if (!t.startsWith("https://")) return "Seules les adresses en https sont acceptées."
    val authority = t.removePrefix("https://").split('/', '?', '#').first()
    if ('@' in authority) return "L'adresse ne doit pas contenir d'identifiants."
    if (authority.startsWith('[')) return NOT_PUBLIC
    val host = authority.substringBefore(':')
    if (':' in authority && authority.substringAfter(':') != "443") return "L'adresse ne doit pas préciser de port."
    val labels = host.trimEnd('.').lowercase(Locale.ROOT).split('.')
    val tld = labels.last()
    val wellFormed = labels.all { e -> e.isNotEmpty() && !e.startsWith('-') && !e.endsWith('-') && e.codePointList().all { alnum(it) || it == '-'.code } }
    // un TLD sans lettre est une adresse IP (décimale)
    val tldIp = tld.codePointList().none { Character.isAlphabetic(it) }
    if (labels.size < 2 || !wellFormed || tldIp || tld in setOf("local", "internal", "lan", "localhost")) return NOT_PUBLIC
    return null
}

private fun String.firstCodePoints(n: Int): String {
    val c = codePointList()
    return if (c.size > n) c.take(n).str() + "…" else this
}

private fun needsTarget(kind: String) = kind == "fait" || kind == "source"

/** Éléments visables d'une carte : faits `f1…`, sources `s1…` (numérotés à partir de 1, comptés comme le module). */
fun reportTargets(c: Claim, kind: String, data: PolData): List<Pair<String, String>> = when (kind) {
    "fait" -> c.facts.mapIndexed { i, t -> "f${i + 1}" to "Fait ${i + 1} : ${t.trim().firstCodePoints(70)}" }
    "source" -> {
        val refs = sourcesOf(c.src)
        refs.take(minOf(refs.size, (c.src as? JsonArray)?.size ?: 0))
            .mapIndexed { i, s -> "s${i + 1}" to sourceParts(s, data).text.ifBlank { "Source ${i + 1}" } }
    }
    else -> emptyList()
}

private fun within(s: String, min: Int, max: Int) = reportLength(s) in min..max

fun reportReady(f: ReportForm, c: Claim, data: PolData): Boolean {
    if (!validClaimId(c.id) || !f.oath || REPORT_KINDS.none { it.first == f.kind }) return false
    if (needsTarget(f.kind) && reportTargets(c, f.kind, data).none { it.first == f.target }) return false
    if (!within(f.what, 30, 400) || !within(f.fix, 10, 300)) return false
    return when (f.basis) {
        "lien" -> urlProblem(f.url) == null
        "ref" -> within(f.refWho, 2, 80) && within(f.refWhen, 0, 30) && within(f.refWhat, 4, 160)
        "carte" -> within(f.refIn, 4, 160)
        else -> false
    }
}

/** Arguments de `app_signalement_deposer` : seulement la cible et les champs de la base choisie. */
fun reportArgs(f: ReportForm, claimId: String): JsonObject = buildJsonObject {
    put("claim", claimId)
    put("kind", f.kind)
    if (needsTarget(f.kind)) put("target", f.target)
    put("what", cleanReport(f.what))
    put("basis", f.basis)
    when (f.basis) {
        // L'adresse est vérifiée brute, comme côté module : elle part telle quelle.
        "lien" -> put("url", f.url.trim())
        "ref" -> { put("ref_who", cleanReport(f.refWho)); put("ref_when", cleanReport(f.refWhen)); put("ref_what", cleanReport(f.refWhat)) }
        "carte" -> put("ref_in", cleanReport(f.refIn))
    }
    put("fix", cleanReport(f.fix))
    put("oath", true)
    put("idem", f.idem)
}

@Serializable
data class ReportReceipt(val reference: String = "", val recu_a: Long = 0, val restant: Int? = null)

sealed interface ReportOutcome {
    data class Received(val ref: String, val at: Long, val left: Int?) : ReportOutcome
    data class Limited(val minutes: Int) : ReportOutcome
    data object Closed : ReportOutcome
    data class Refused(val message: String) : ReportOutcome
    data class Failed(val message: String) : ReportOutcome
}

private val LIMIT = Regex("""Limite atteinte : réessayer dans (\d+) minute\(s\)\.""")

fun reportOutcome(e: ApiError): ReportOutcome {
    if (e is ApiError.Server && e.code == 422) {
        val m = e.message.orEmpty()
        LIMIT.matchEntire(m)?.let { return ReportOutcome.Limited(it.groupValues[1].toInt()) }
        if (m == "Plafond du jour atteint." || m == "signalements non configurés") return ReportOutcome.Closed
        return ReportOutcome.Refused(m)
    }
    if (e == ApiError.NoReply) return ReportOutcome.Failed("Pas de réponse du serveur. Renvoyer garde la même référence.")
    return ReportOutcome.Failed(politiqueIssue(e))
}

/** Envois restants cette heure ([left], inconnu avant le premier envoi) et fin de la limite ([until], ms). */
data class ReportQuota(val left: Int? = null, val until: Long = 0)

fun ReportQuota.after(o: ReportOutcome, nowMs: Long): ReportQuota = when (o) {
    is ReportOutcome.Received -> ReportQuota(left = o.left, until = 0)
    is ReportOutcome.Limited -> copy(until = nowMs + o.minutes * 60_000L)
    else -> this
}

/** Minutes à attendre (arrondies au supérieur), null si l'envoi est possible. */
fun ReportQuota.waitMinutes(nowMs: Long): Int? =
    (until - nowMs).takeIf { it > 0 }?.let { ((it + 59_999) / 60_000).toInt() }

private val RECEIPT_TIME = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.FRENCH)

/** [at] : secondes depuis l'époque, comme `recu_a`. */
fun receiptLine(ref: String, at: Long, zone: ZoneId): String =
    "Référence $ref, reçu le ${RECEIPT_TIME.format(Instant.ofEpochSecond(at).atZone(zone))}."
