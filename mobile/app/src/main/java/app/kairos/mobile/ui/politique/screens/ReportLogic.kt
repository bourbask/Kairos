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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Signalement d'une erreur sur une affirmation ou sur toute autre information affichée : mêmes champs, bornes,
 * nettoyage, références et règle d'adresse que la validation du module et que le site, le module fait foi.
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

/** Choix proposés hors affirmation : ni verdict ni classement, pas d'élément numéroté. */
val INFO_KINDS: List<Pair<String, String>> = listOf(
    "fait" to "Un chiffre ou un fait",
    "source" to "Une source",
    "formulation" to "La formulation",
    "autre" to "Autre chose",
)

val INFO_BASES: List<Pair<String, String>> = REPORT_BASES.map { if (it.first == "carte") "carte" to "Une contradiction dans l'élément lui-même" else it }

/** Types d'information acceptés par le module hors affirmation. */
val INFO_TYPES: Set<String> = setOf(
    "declaration", "promesse", "evaluateur", "scrutin", "manche", "vote", "coherence", "bilan", "budget", "ecart", "niveau-de-vie",
    "revirement", "parti", "camp", "comparaison", "media", "argent", "probite", "ville", "rhetorique", "dossier", "aide", "theme", "bareme",
)

/** Ce que vise un signalement : une affirmation par son identifiant, ou une autre information affichée. */
sealed interface ReportTarget {
    /** Clé de la feuille et du dernier échec. */
    val key: String

    data class Affirmation(val claimId: String) : ReportTarget {
        override val key: String get() = claimId
    }

    /** [extrait] : texte affiché de l'élément ; [titre] : son intitulé, en tête de la feuille. */
    data class Info(val type: String, val ref: String, val extrait: String, val titre: String = "") : ReportTarget {
        override val key: String get() = "$type|$ref"
    }
}

/** Cible d'une information, extrait et titre ramenés à ce que le module accepte. */
fun infoTarget(type: String, ref: String, extrait: String, titre: String = ""): ReportTarget.Info =
    ReportTarget.Info(type, ref, infoExtrait(extrait), infoExtrait(titre))

private val TAGS = Regex("<[^>]*>")
private val MARKS = Regex("\\p{M}+")
private val NON_ALNUM = Regex("[^a-z0-9]+")
// Espaces Unicode explicites : le moteur d'Android ne connaît pas le drapeau « caractères Unicode ».
private val SPACES = Regex("[\\s\\p{Z}]+")
private val REF_PATTERN = Regex("^[a-z0-9][a-z0-9._:-]*$")

fun refOk(r: String): Boolean = r.length <= 120 && REF_PATTERN.matches(r)

/** Libellé normalisé : minuscules, sans accents, tout le reste en tirets, 80 caractères au plus. */
fun refSlug(s: String): String =
    Normalizer.normalize(cleanReport(s.replace(TAGS, "")), Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
        .replace(NON_ALNUM, "-").trimStart('-').take(80).trimEnd('-')

/**
 * Référence d'un élément, comme le site : son id s'il en a un ([label] null, en minuscules),
 * sinon `<prefix>:<slug du libellé>`, « :n » au n-ième doublon déjà noté dans [seen].
 */
fun infoRef(prefix: String, label: String? = null, seen: MutableMap<String, Int>? = null): String {
    var r = if (label == null) prefix.lowercase(Locale.ROOT) else "$prefix:${refSlug(label).ifEmpty { "element" }}"
    if (!refOk(r)) r = r.split(':').map(::refSlug).filter { it.isNotEmpty() }.joinToString(":").ifEmpty { "element" }
    if (seen != null) {
        val n = (seen[r] ?: 0) + 1
        seen[r] = n
        if (n > 1) r += ":$n"
    }
    return r.take(120).trimEnd(':', '.', '-')
}

/** Texte affiché, sans balises ni invisibles, espaces resserrés, 300 caractères au plus. */
fun infoExtrait(s: String): String {
    val t = cleanReport(s.replace(TAGS, "")).replace(SPACES, " ").codePointList()
    return if (t.size > 300) t.take(299).str().trimEnd() + "…" else t.str()
}

/** Information signalable : type connu, référence au motif, extrait de 4 à 300 caractères. */
fun infoOk(t: ReportTarget.Info): Boolean = t.type in INFO_TYPES && refOk(t.ref) && reportLength(t.extrait) in 4..300

fun reportKinds(t: ReportTarget): List<Pair<String, String>> = if (t is ReportTarget.Info) INFO_KINDS else REPORT_KINDS

fun reportBases(t: ReportTarget): List<Pair<String, String>> = if (t is ReportTarget.Info) INFO_BASES else REPORT_BASES

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
) {
    /** Ni texte du lecteur ni clé : rien de la saisie ne fuit dans une trace. */
    override fun toString(): String = "ReportForm(kind=$kind, target=$target, basis=$basis, oath=$oath)"
}

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
    if (cps.size > 300) return "Adresse de 300 caractères au plus."
    if (cps.any { white(it) || control(it) || invisible(it) }) return "Cette adresse n'est pas valide."
    if (!t.startsWith("https://")) return "Seules les adresses en https sont acceptées."
    val authority = t.removePrefix("https://").split('/', '?', '#').first()
    if ('@' in authority) return "L'adresse ne doit pas contenir d'identifiants."
    if (authority.startsWith('[')) return NOT_PUBLIC
    val host = authority.substringBefore(':')
    if (':' in authority && authority.substringAfter(':') != "443") return "L'adresse ne doit pas préciser de port."
    val labels = host.trimEnd('.').lowercase(Locale.ROOT).split('.')
    val wellFormed = labels.all { e -> e.isNotEmpty() && !e.startsWith('-') && !e.endsWith('-') && e.codePointList().all { alnum(it) || it == '-'.code } }
    if (!wellFormed) return "Cette adresse n'est pas valide."
    val tld = labels.last()
    // un TLD sans lettre est une adresse IP (décimale)
    val tldIp = tld.codePointList().none { Character.isAlphabetic(it) }
    if (labels.size < 2 || tldIp || tld in setOf("local", "internal", "lan", "localhost")) return NOT_PUBLIC
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
    // Un numéro par élément brut de `src`, comme le module ; un tuple plat `[libellé, url]` est une seule source.
    "source" -> when (val src = c.src) {
        is JsonArray -> if (src.isNotEmpty() && src.all { it is JsonPrimitive }) listOf(src) else src.toList()
        else -> emptyList()
    }.mapIndexed { i, e ->
        "s${i + 1}" to (sourcesOf(e).map { sourceParts(it, data).text }.firstOrNull { it.isNotBlank() } ?: "Source ${i + 1}")
    }
    else -> emptyList()
}

private fun within(s: String, min: Int, max: Int) = reportLength(s) in min..max

fun reportReady(f: ReportForm, c: Claim, data: PolData): Boolean {
    if (!validClaimId(c.id) || REPORT_KINDS.none { it.first == f.kind }) return false
    if (needsTarget(f.kind) && reportTargets(c, f.kind, data).none { it.first == f.target }) return false
    return reportCommonReady(f)
}

/** Hors affirmation : aucun élément numéroté à choisir. */
fun reportReady(f: ReportForm, t: ReportTarget.Info): Boolean =
    infoOk(t) && INFO_KINDS.any { it.first == f.kind } && reportCommonReady(f)

private fun reportCommonReady(f: ReportForm): Boolean {
    if (!f.oath) return false
    if (!within(f.what, 30, 400) || !within(f.fix, 10, 300)) return false
    return when (f.basis) {
        "lien" -> urlProblem(f.url) == null
        "ref" -> within(f.refWho, 2, 80) && within(f.refWhen, 0, 30) && within(f.refWhat, 4, 160)
        "carte" -> within(f.refIn, 4, 160)
        else -> false
    }
}

/** Arguments de `app_signalement_deposer` : seulement la cible et les champs de la base choisie. */
fun reportArgs(f: ReportForm, claimId: String): JsonObject = reportArgs(f, ReportTarget.Affirmation(claimId))

/** Une affirmation : `claim` et, pour un fait ou une source, `target` ; une autre information : `cible_*`, ni `claim` ni `target`. */
fun reportArgs(f: ReportForm, t: ReportTarget): JsonObject = buildJsonObject {
    when (t) {
        is ReportTarget.Affirmation -> put("claim", t.claimId)
        is ReportTarget.Info -> { put("cible_type", t.type); put("cible_ref", t.ref); put("extrait", cleanReport(t.extrait)) }
    }
    put("kind", f.kind)
    if (t is ReportTarget.Affirmation && needsTarget(f.kind)) put("target", f.target)
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
        return ReportOutcome.Refused(
            when {
                m.startsWith("claim : ") -> "Cette affirmation a été retirée ou modifiée depuis l'affichage : rechargez la page."
                m.startsWith("target : ") -> "Cet élément n'existe plus dans la carte : rechargez la page."
                else -> m
            },
        )
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

/** Le module refuse un corps de plus de 4 Ko : des caractères multi-octets peuvent y mener sans dépasser les bornes. */
fun oversize(args: JsonObject): String? =
    if (args.toString().toByteArray(Charsets.UTF_8).size > 4096) "Votre texte est trop long une fois encodé : raccourcissez-le un peu." else null

/** Compteur d'un champ : « n / max », et ce qui manque tant que le minimum n'est pas atteint. */
fun counterText(n: Int, min: Int, max: Int): String =
    "$n / $max" + if (n in 1 until min) (min - n).let { " · encore $it caractère${if (it > 1) "s" else ""} au moins" } else ""

/** Mention d'anonymat, puis les envois restants cette heure ([left] inconnu avant le premier envoi). */
fun anonymityNote(left: Int?, info: Boolean = false): String =
    "Anonyme : ni adresse IP, ni compte, ni identifiant ne sont gardés. Seuls le contenu, ${if (info) "l'information visée" else "l'affirmation visée"} et l'heure d'envoi sont enregistrés." +
        if (left == null) " 10 envois au plus par heure." else { val s = if (left > 1) "s" else ""; " $left envoi$s possible$s encore cette heure." }
