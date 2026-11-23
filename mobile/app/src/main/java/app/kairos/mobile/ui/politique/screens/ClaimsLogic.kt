package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.mimir.mimirOrigin
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.sourcesText
import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.kit.verdictLabel
import app.kairos.mobile.ui.politique.model.Bareme
import app.kairos.mobile.ui.politique.model.BaremeConfiance
import app.kairos.mobile.ui.politique.model.BaremeVerdict
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.Cite
import app.kairos.mobile.ui.politique.model.SourceRef
import kotlinx.serialization.json.JsonPrimitive
import app.kairos.mobile.ui.politique.model.VERDICT_SCALE
import app.kairos.mobile.ui.politique.model.normalizeVerdict
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.model.alphabetical
import java.text.Collator
import java.util.Locale

/** Logique pure des phrases vérifiées : filtres, pagination, « Hors de ma bulle ». Les phrases sont désignées par leur rang dans [claimsOf]. */

const val PHRASES_PAGE = 20
const val BULLE_PAGE = 12

val VERDICT_ORDER = VERDICT_SCALE

fun baremeOf(data: PolData): Bareme? {
    val verdicts = data.decode<BaremeVerdict>("bareme", "VERDICTS")
    if (verdicts.isEmpty()) return null
    val principe = (data.file("bareme")?.get("PRINCIPE") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return Bareme(verdicts, data.decode<BaremeConfiance>("bareme", "CONFIANCE").filter { it.niveau in 1..3 }, principe)
}

private fun Bareme?.entry(v: String): BaremeVerdict? = this?.verdicts?.firstOrNull { normalizeVerdict(it.id) == normalizeVerdict(v) }

fun verdictName(b: Bareme?, v: String): String = b.entry(v)?.libelle?.takeIf { it.isNotBlank() } ?: verdictLabel(v)

fun verdictDefinition(b: Bareme?, v: String): String? = b.entry(v)?.definition?.takeIf { it.isNotBlank() }

fun verdictThirds(b: Bareme?, v: String): Int = when (b.entry(v)?.famille) {
    "solide" -> 3
    "partiel" -> 2
    null -> when (normalizeVerdict(v)) { "vrai" -> 3; "plutot-vrai" -> 2; else -> 1 }
    else -> 1
}

fun hasWhy(c: Claim): Boolean = c.pourquoi.isNotBlank()

fun confidenceLevel(c: Claim): Int? = c.confiance?.takeIf { it in 1..3 }

fun supportFacts(c: Claim): List<Int> = c.appui.filter { it in 1..c.facts.size }.distinct()

private val CLAIM_ID = Regex("^[a-z0-9-]{3,60}$")

fun validClaimId(id: String): Boolean = CLAIM_ID.matches(id)

/** Lien public d'une affirmation : origine du lien du site, sans chemin, fragment ni jeton, puis l'ancre de la carte. */
fun claimLink(siteLink: String?, id: String): String? =
    if (siteLink == null || !validClaimId(id)) null else mimirOrigin(siteLink)?.let { "$it#a-$id" }

fun claimShareText(c: Claim, verdict: String, link: String): String = "« ${frTypo(c.q)} » : $verdict\n$link"

/** Nom accessible de la touche verdict, selon la face visible. */
fun verdictKeyLabel(verdict: String, hasWhy: Boolean, back: Boolean): String = when {
    !hasWhy -> "Verdict : $verdict. Justification en cours"
    back -> "$verdict : revenir aux faits"
    else -> "Verdict : $verdict. Pourquoi ?"
}

fun claimsOf(data: PolData): List<Claim> = data.decode<Claim>("affirmations", "CLAIMS").map { it.copy(v = normalizeVerdict(it.v)) }

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
        claims, campsOf(data, claims).alphabetical { it },
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

/** Rangs des phrases d'un autre camp sur le même thème ; camp ou thème vide : jamais comparable (aucune colonne ne les affiche). */
fun facing(claims: List<Claim>, i: Int): List<Int> {
    val c = claims.getOrNull(i)?.takeIf { it.camp.isNotBlank() && it.theme.isNotBlank() } ?: return emptyList()
    return claims.indices.filter { claims[it].theme == c.theme && claims[it].camp.isNotBlank() && claims[it].camp != c.camp }
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

const val PHRASE_TYPE = "Phrase type : elle résume des propos qui circulent, elle ne cite personne."

private val ISO_DAY = Regex("""\d{4}-\d{2}-\d{2}""")
private val HTTPS = Regex("""https://[^\s"'<>]+""")

/** Citation publiable : tous les champs présents, date AAAA-MM-JJ, lien https ; sinon on la traite comme absente. */
fun validCite(c: Claim): Cite? = c.cite?.takeIf { t ->
    listOf(t.auteur, t.support, t.emplacement, t.texte).all { it.isNotBlank() } && ISO_DAY.matches(t.date) && HTTPS.matches(t.lien)
}

/** D'où vient la phrase : citation réelle (auteur, date, emplacement, mots exacts) ou phrase type. */
fun claimOrigin(c: Claim): String = validCite(c)?.let { "Citée : ${it.auteur}, ${fmtDate(it.date)}, ${it.emplacement} : « ${frTypo(it.texte)} »" } ?: PHRASE_TYPE

fun softenedNote(c: Claim): String? = validCite(c)?.mot_adouci?.takeIf { it.isNotBlank() }?.let { "* Formulation adoucie. Mot d'origine : « $it »." }

fun citeSource(c: Claim): SourceRef? = validCite(c)?.let { SourceRef(it.support, it.lien) }

/** Camp affiché : une affirmation sans auteur établi par ses sources n'a pas de camp. */
fun campLabel(c: Claim): String = c.camp.ifBlank { "camp non établi" }

fun claimKicker(c: Claim): String = listOf(c.theme, c.date?.takeIf { it.isNotBlank() }?.let(::fmtDate)).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

/** Carte du mode « À table » : phrase, verdict, ce qu'elle cache, faits, sources. */
fun claimTableCard(data: PolData, c: Claim): TableCard = TableCard(
    kicker = if (c.camp.isBlank()) "${c.theme} · ${campLabel(c)}" else "${c.theme} · entendu côté ${c.camp}",
    title = campLabel(c),
    quote = c.q,
    verdict = c.v,
    lines = listOfNotNull(c.cache?.takeIf { it.isNotBlank() }?.let { "Ce que la phrase cache : $it" }) + c.facts,
    source = sourcesText(sourcesOf(c.src), data),
)

// ---------- Ça vient d'où ? ----------

/** Paquet « Ça vient d'où ? » : les phrases dont l'origine est publiée, mélangées par [seed] (une graine par session). */
fun originDeck(data: PolData, seed: Long): List<Claim> = claimsOf(data).filter { it.origine.isNotBlank() && it.v in VERDICT_SCALE && it.v != "non-documente" }.shuffled(kotlin.random.Random(seed))
