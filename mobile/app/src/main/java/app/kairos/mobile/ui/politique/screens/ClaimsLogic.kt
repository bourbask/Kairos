package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import java.text.Collator
import java.util.Locale

/** Logique pure des phrases vérifiées : filtres, pagination, « Hors de ma bulle ». Les phrases sont désignées par leur rang dans [claimsOf]. */

const val PHRASES_PAGE = 20
const val BULLE_PAGE = 12

val VERDICT_ORDER = listOf("solide", "partiel", "trompeur", "fragile", "faux", "non-documente")

private val VERDICT_DEFS = mapOf(
    "solide" to "établi par des données publiques",
    "partiel" to "une part exacte, une part non",
    "trompeur" to "exact, mais présenté pour faire croire autre chose",
    "fragile" to "repose sur peu de preuves ou des chiffres datés",
    "faux" to "contredit par les données",
    "non-documente" to "aucune donnée publique pour trancher",
)

fun verdictDefinition(v: String): String? = VERDICT_DEFS[v]

fun claimsOf(data: PolData): List<Claim> = data.decode<Claim>("affirmations", "CLAIMS")

/** Camps publiés, puis ceux que seules les phrases citent. */
fun campsOf(data: PolData, claims: List<Claim>): List<String> =
    (data.decode<String>("affirmations", "CAMPS") + claims.map { it.camp }).filter { it.isNotBlank() }.distinct()

// ---------- Phrases ----------

data class PhraseFilter(val v: String = "", val camp: String = "", val theme: String = "", val n: Int = PHRASES_PAGE)

class PhrasesState(val claims: List<Claim>, val camps: List<String>, val themes: List<String>, val verdicts: List<String>)

fun phrasesState(data: PolData): PhrasesState {
    val claims = claimsOf(data)
    val fr = Collator.getInstance(Locale.FRENCH)
    return PhrasesState(
        claims, campsOf(data, claims),
        claims.map { it.theme }.filter { it.isNotBlank() }.distinct().sortedWith(fr),
        VERDICT_ORDER.filter { v -> claims.any { it.v == v } },
    )
}

/** [items] : rangs des phrases affichées ; [total] : correspondances ; [more] : taille de la prochaine page. */
class PhrasesView(val items: List<Int>, val total: Int, val more: Int)

fun phrasesView(st: PhrasesState, f: PhraseFilter): PhrasesView {
    val hits = st.claims.indices.filter { i ->
        val c = st.claims[i]
        (f.v.isEmpty() || c.v == f.v) && (f.camp.isEmpty() || c.camp == f.camp) && (f.theme.isEmpty() || c.theme == f.theme)
    }
    val shown = hits.take(f.n)
    return PhrasesView(shown, hits.size, minOf(PHRASES_PAGE, hits.size - shown.size))
}

/** Nombre de phrases par verdict connu ([camp] vide : toutes) ; les verdicts absents valent 0. */
fun verdictTally(claims: List<Claim>, camp: String = ""): Map<String, Int> {
    val mine = claims.filter { camp.isEmpty() || it.camp == camp }
    return VERDICT_ORDER.associateWith { v -> mine.count { it.v == v } }
}

// ---------- Hors de ma bulle ----------

/** Rangs des phrases d'un autre camp sur le même thème. */
fun facing(claims: List<Claim>, i: Int): List<Int> {
    val c = claims.getOrNull(i) ?: return emptyList()
    return claims.indices.filter { claims[it].theme == c.theme && claims[it].camp != c.camp }
}

/** Étape 1 : seules les phrases qui ont au moins une réponse d'un autre camp ([camp] vide : tous) ; l'étape 2 n'est jamais vide. */
fun bubbleOffers(claims: List<Claim>, camp: String): List<Int> =
    claims.indices.filter { (camp.isEmpty() || claims[it].camp == camp) && facing(claims, it).isNotEmpty() }

fun bubbleThemes(claims: List<Claim>): List<String> = claims.map { it.theme }.filter { it.isNotBlank() }.distinct()

/** Étape 2 : par camp (sauf celui de la phrase), ses phrases sur le même thème, éventuellement aucune. */
fun facingColumns(claims: List<Claim>, i: Int, camps: List<String>): List<Pair<String, List<Int>>> {
    val c = claims.getOrNull(i) ?: return emptyList()
    val out = facing(claims, i)
    return camps.filter { it != c.camp }.map { k -> k to out.filter { claims[it].camp == k } }
}

/** Vue par thème : une colonne par camp. */
fun themeColumns(claims: List<Claim>, camps: List<String>, theme: String): List<Pair<String, List<Int>>> =
    camps.map { k -> k to claims.indices.filter { claims[it].theme == theme && claims[it].camp == k } }

// ---------- Détail et « À table » ----------

private val MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
private val DATE = Regex("^(\\d{4})-(\\d{2})(?:-(\\d{2}))?$")

/** `2026-03` → « mars 2026 », `2026-03-01` → « 1er mars 2026 » ; autre forme : telle quelle. */
fun fmtDate(d: String): String {
    val m = DATE.matchEntire(d.trim()) ?: return d
    val month = MONTHS.getOrNull(m.groupValues[2].toInt() - 1) ?: return d
    val day = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it == 1) "1er " else "$it " }.orEmpty()
    return "$day$month ${m.groupValues[1]}"
}

fun claimKicker(c: Claim): String = listOf(c.theme, c.date?.takeIf { it.isNotBlank() }?.let(::fmtDate)).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

/** Carte du mode « À table » : phrase, verdict, ce qu'elle cache, faits, sources. */
fun claimTableCard(c: Claim): TableCard = TableCard(
    kicker = "${c.theme} · entendu côté ${c.camp}",
    title = c.camp,
    quote = c.q,
    verdict = c.v,
    lines = listOfNotNull(c.cache?.takeIf { it.isNotBlank() }?.let { "Ce que la phrase cache : $it" }) + c.facts,
    source = sourcesOf(c.src).joinToString(" ; ") { it.label }.takeIf { it.isNotBlank() },
)
