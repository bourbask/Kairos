package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.model.PolData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Logique pure des votes, revirements et « dit / voté ». Tout champ est facultatif : une ligne mal formée reste lisible. */

@Serializable
data class VoteRow(val t: String = "", val d: String = "", val n: String? = null, val u: String? = null, val c: List<List<String>> = emptyList())

@Serializable
data class Parole(val qui: String = "", val parti: String? = null, val date: String = "", val dit: String = "", val url: String? = null, val cadre: String = "hors-seance")

@Serializable
data class Verdict(val qui: String = "", val v: String = "", val explication: String = "", val base: String? = null)

@Serializable
data class Scrutin(
    val id: String = "", val chambre: String = "", val date: String = "", val titre: String = "", val objet: String = "",
    val resultat: String = "", val url: String? = null, val note: String? = null,
    val positions: Map<String, List<String>> = emptyMap(), val paroles: List<Parole> = emptyList(), val verdicts: List<Verdict> = emptyList(),
)

data class DvItem(val scrutin: Scrutin, val verdict: Verdict)

/** [c] cohérents, [a] ambigus, [e] écarts, [t] = c + a + e ; [sp] « sans déclaration », jamais compté dans [t]. */
data class DvCount(val c: Int, val a: Int, val e: Int, val sp: Int, val t: Int)

val CHAMBERS = listOf("AN" to "Assemblée nationale", "PE" to "Parlement européen")
val VOTE_WORDS = mapOf("P" to "Pour", "C" to "Contre", "A" to "Abstention", "S" to "Divisé", "N" to "Pas de vote")
val DV_ORDER = listOf("ecart", "ambigu", "coherent", "sans-parole")
val DV_LABELS = mapOf("coherent" to "Cohérent", "ambigu" to "Ambigu", "ecart" to "Écart", "sans-parole" to "Aucune déclaration trouvée")
val BASE_LABELS = mapOf("hors-seance" to "Parole publique", "seance" to "Ligne annoncée en séance")

fun scrutinsOf(data: PolData): List<Scrutin> = data.decode<Scrutin>("dit-vote", "SCRUTINS")

/** Verdicts d'un parti, tous scrutins confondus ; [partyId] introuvable ou vide : rien. */
fun dvFor(data: PolData, partyId: String?): List<DvItem> =
    if (partyId.isNullOrEmpty()) emptyList() else scrutinsOf(data).flatMap { s -> s.verdicts.filter { it.qui == partyId }.map { DvItem(s, it) } }

internal fun Verdict.baseOf() = if (base == "seance") "seance" else "hors-seance"

/** Un verdict « sans déclaration » n'est jamais compté ; [base] : le taux principal est « hors-seance » (absence de base comprise). */
fun dvCount(items: List<DvItem>, base: String = "hors-seance"): DvCount {
    val m = items.map { it.verdict }.filter { it.v != "sans-parole" && it.baseOf() == base }
    fun n(k: String) = m.count { it.v == k }
    return DvCount(c = n("coherent"), a = n("ambigu"), e = n("ecart"), sp = items.count { it.verdict.v == "sans-parole" }, t = n("coherent") + n("ambigu") + n("ecart"))
}

/** Écarts d'abord, puis ambigus ; le plus récent en tête. */
fun dvGaps(items: List<DvItem>): List<DvItem> =
    items.filter { it.verdict.v == "ecart" || it.verdict.v == "ambigu" }
        .sortedWith(compareBy<DvItem> { DV_ORDER.indexOf(it.verdict.v) }.thenByDescending { it.scrutin.date })

/** Scrutins d'une assemblée, récents d'abord ; [filter] : "", "ecart" ou "ambigu". */
fun scrutinsFor(all: List<Scrutin>, chambre: String, filter: String): List<Scrutin> =
    all.filter { it.chambre == chambre && (filter.isEmpty() || it.verdicts.any { v -> v.v == filter }) }.sortedByDescending { it.date }

fun dvParties(l: List<Scrutin>): List<String> = l.flatMap { s -> s.verdicts.map { it.qui } }.distinct()

/** (groupe, code, détail) du parti [qui] dans ce scrutin : le groupe dont un alias désigne ce parti ([idsOf] : alias → identifiants). */
fun positionOf(s: Scrutin, qui: String, idsOf: (String) -> List<String>): Triple<String, String, String>? {
    val g = s.positions.keys.firstOrNull { qui in idsOf(it) } ?: return null
    val p = s.positions.getValue(g)
    return Triple(g, p.getOrElse(0) { "" }, p.getOrElse(1) { "" })
}

fun positionOf(data: PolData, s: Scrutin, qui: String) = positionOf(s, qui) { data.idsIn(it) }

/** Votes d'un groupe : (code, nombre de scrutins) dans l'ordre pour, contre, abstention, divisé, absent ; les nuls sont omis. */
fun voteCounts(rows: List<VoteRow>, g: Int): List<Pair<String, Int>> =
    VOTE_WORDS.keys.map { k -> k to rows.count { it.c.getOrNull(g)?.getOrNull(0) == k } }.filter { it.second > 0 }

/** Index du groupe que désigne un alias de [id] ; aucun : -1. */
fun groupIndex(data: PolData, groups: List<String>, id: String): Int = groups.indexOfFirst { id in data.idsIn(it) }

// ---------- Revirements ----------

/** Position suivie : [p] personne citée, [a] avant, [b] après, [k] `R` revirement, `I` inflexion, `C` constance. */
@Serializable
data class Turn(val camp: String = "", val p: String = "", val s: String = "", val a: String = "", val b: String = "", val k: String = "", val src: JsonElement? = null)

/** Votes à rebours d'un camp : `[code, texte]` avec code `coherent`, `explique` ou `ecart`. */
@Serializable
data class CohGroup(val camp: String = "", val items: List<List<String>> = emptyList())

/** Positions suivies d'une identité : par personne citée, sinon par camp. */
fun turnsOf(data: PolData, id: String): List<Turn> =
    data.decode<Turn>("revirements", "RV").filter { r -> id in data.idsIn(r.p.takeIf { data.idsIn(it).isNotEmpty() } ?: r.camp) }

val TURN_KINDS = listOf("R" to "Revirement", "I" to "Inflexion", "C" to "Constance")

fun revirementCamps(rv: List<Turn>): List<String> = rv.map { it.camp }.distinct()

fun revirementCounts(rv: List<Turn>, camp: String): Map<String, Int> = TURN_KINDS.associate { (k, _) -> k to rv.count { it.camp == camp && it.k == k } }

fun revirementLine(counts: Map<String, Int>): String =
    TURN_KINDS.joinToString(" · ") { (k, l) -> val n = counts[k] ?: 0; "$n ${l.lowercase()}${if (n > 1) "s" else ""}" }

private val WHEN = Regex("""^([^:]{2,48}?) : (.+)$""")

/** « 2017 : texte » → (« 2017 », « texte ») ; sans date, ("", texte). */
fun splitWhen(s: String): Pair<String, String> = WHEN.matchEntire(s)?.let { it.groupValues[1] to it.groupValues[2] } ?: ("" to s)

/** Message de la liste d'écarts vide : sans verdict hors séance, rien ne se juge ; ce n'est jamais une cohérence. */
fun noGapsMessage(k: DvCount, seance: DvCount): String =
    if (k.t == 0) NO_PUBLIC_STATEMENT +
        (if (seance.t > 0) " Seule la ligne annoncée en séance est documentée, signalée plus haut comme moins probante." else "")
    else (if (k.t == 1) "Sur le scrutin où" else "Sur les ${k.t} scrutins où") + " une déclaration publique a été trouvée, le vote suit la parole." +
        when (k.sp) {
            0 -> ""
            1 -> " L'autre reste sans déclaration : ce n'est pas une preuve de cohérence."
            else -> " Les ${k.sp} autres restent sans déclaration : ce n'est pas une preuve de cohérence."
        }

const val NO_PUBLIC_STATEMENT = "Aucune déclaration publique trouvée hors séance : impossible de juger."

class RateLabel(val figure: String, val text: String)

/** Taux hors séance à afficher ; `null` quand rien n'est comptable (afficher [NO_PUBLIC_STATEMENT], jamais « 0 / 0 »). */
fun publicRateLabel(k: DvCount): RateLabel? =
    if (k.t == 0) null else RateLabel("${k.c} / ${k.t}", "scrutins où le vote suit ce que le parti dit en public")
