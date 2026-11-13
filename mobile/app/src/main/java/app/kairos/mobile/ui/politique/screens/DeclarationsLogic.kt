package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.kit.sourceName
import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.alphabetical
import app.kairos.mobile.ui.politique.model.fold
import app.kairos.mobile.ui.politique.model.sourcesOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Logique pure des paroles publiques face aux faits. */

/** [qui] : id d'identité ou nom ; [source] : `[libellé, url, minutage?]` ; [preuves] : `[[libellé, url, ev?], …]`. */
@Serializable
data class Declaration(
    val id: String = "",
    val qui: String = "",
    val parti: String? = null,
    val fonction: String = "",
    val pouvoir: String = "",
    val date: String = "",
    val contexte: String = "",
    val citation: String = "",
    val source: JsonElement? = null,
    val verdict: String = "",
    val faits: List<String> = emptyList(),
    val preuves: JsonElement? = null,
    val reponse: String? = null,
    val procede: String? = null,
)

/** Du plus objectif au moins : la parole contre elle-même, la justice, un document officiel, une preuve publiée ; puis exact, non vérifiable. */
val DECL_VERDICTS = listOf("auto-contradiction", "contredit-justice", "contredit-document", "contredit-preuve", "exact", "non-verifiable")

/** Rang de solidité ; verdict inconnu : après tous les autres. */
fun verdictStrength(v: String): Int = DECL_VERDICTS.indexOf(v).let { if (it < 0) DECL_VERDICTS.size else it }

/** La parole du gouvernement engage l'État : marquée, jugée sur la même grille. */
fun isGovernment(d: Declaration): Boolean = fold(d.pouvoir) == "gouvernement"

/** Identité de l'auteur, seulement si elle est reconnue sans ambiguïté. */
fun whoOf(data: PolData, d: Declaration): String? = data.byId(d.qui)?.id ?: data.idOf(d.qui)

/** Camp (parti) : celui publié, sinon celui de la fiche de l'auteur. */
fun campOf(data: PolData, d: Declaration): String? {
    d.parti?.takeIf { it.isNotBlank() }?.let { p -> (data.byId(p)?.id ?: data.idOf(p))?.let { return it } }
    val who = whoOf(data, d) ?: return null
    return if (data.kindOf(who) == IdKind.Parti) who else data.partyOf(who)
}

/** Déclarations publiées (citation requise), par date décroissante puis solidité du verdict ; sans date : à la fin. */
fun declarationsOf(data: PolData): List<Declaration> =
    data.decode<Declaration>("declarations", "DECLARATIONS").filter { it.citation.isNotBlank() }
        .sortedWith(compareByDescending<Declaration> { it.date.trim() }.thenBy { verdictStrength(it.verdict) })

/** [campIds] : camp de chaque déclaration ; [camps] : ids des partis cités, ordre alphabétique ; [verdicts] : présents, dans l'ordre de solidité. */
class DeclState(val list: List<Declaration>, val campIds: List<String?>, val camps: List<String>, val verdicts: List<String>)

fun declState(data: PolData): DeclState {
    val list = declarationsOf(data)
    val campIds = list.map { campOf(data, it) }
    return DeclState(
        list, campIds,
        campIds.filterNotNull().distinct().alphabetical { data.byId(it)?.nom.orEmpty() },
        DECL_VERDICTS.filter { v -> list.any { it.verdict == v } },
    )
}

/** Filtres vides : tout. [pouvoir] : « gouvernement » ou « opposition ». */
data class DeclFilter(val pouvoir: String = "", val verdict: String = "", val camp: String = "")

/** Rangs (dans [DeclState.list]) des déclarations retenues. */
fun declView(st: DeclState, f: DeclFilter): List<Int> = st.list.indices.filter { i ->
    val d = st.list[i]
    (f.pouvoir.isEmpty() || fold(d.pouvoir) == f.pouvoir) && (f.verdict.isEmpty() || d.verdict == f.verdict) &&
        (f.camp.isEmpty() || st.campIds[i] == f.camp)
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** Source de la citation ; le minutage rejoint le libellé (jamais lu comme un évaluateur). */
fun citationSource(d: Declaration): SourceRef? {
    val a = d.source as? JsonArray ?: return sourcesOf(d.source).firstOrNull()
    val label = a.getOrNull(0).str()
    val url = a.getOrNull(1).str()
    if (label == null && url == null) return null
    return SourceRef(listOfNotNull(label, a.getOrNull(2).str()).joinToString(" · "), url)
}

fun preuvesOf(d: Declaration): List<SourceRef> = sourcesOf(d.preuves)

/** Nom de l'auteur : sa fiche, sinon le nom publié. */
fun whoName(data: PolData, d: Declaration): String = whoOf(data, d)?.let { data.byId(it)?.nom } ?: d.qui

/** Sur-titre : « Gouvernement » le cas échéant, fonction, date ; seulement ce qui est publié. */
fun declKicker(d: Declaration, withGov: Boolean = true): String =
    listOfNotNull("Gouvernement".takeIf { withGov && isGovernment(d) }, d.fonction.trim().takeIf { it.isNotEmpty() }, d.date.trim().takeIf { it.isNotEmpty() }?.let(::fmtDate))
        .joinToString(" · ")

/** Carte « À table » : auteur, citation, verdict, faits, réponse de l'intéressé, sources. */
fun declTableCard(data: PolData, d: Declaration): TableCard = TableCard(
    kicker = declKicker(d),
    title = whoName(data, d),
    quote = d.citation,
    verdict = d.verdict,
    lines = d.faits.filter { it.isNotBlank() } + listOfNotNull(d.reponse?.takeIf { it.isNotBlank() }?.let { "Sa réponse : $it" }),
    source = (listOfNotNull(citationSource(d)) + preuvesOf(d)).joinToString(" ; ") { sourceName(it) }.takeIf { it.isNotBlank() },
)
