package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.kit.indLevel
import app.kairos.mobile.ui.politique.kit.safeHttps
import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Logique pure de la rhétorique, des dossiers et des évaluateurs. */

// ---------- Rhétorique ----------

/** [ex] : `[qui, texte, url?]` ; [aussi] : chaîne ou liste. Formes libres, lues avec [rhetExamples] et [aussiOf]. */
@Serializable
data class Rhet(
    val t: String = "",
    val famille: String = "",
    val d: String = "",
    val spot: String = "",
    val ex: List<JsonElement> = emptyList(),
    val ask: String = "",
    val aussi: JsonElement? = null,
)

class RhetExample(val who: String, val text: String, val url: String?)

fun rhetOf(data: PolData): List<Rhet> = data.decode<Rhet>("rhetorique", "RHET").filter { it.t.isNotBlank() }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Exemples lisibles : un texte est requis ; l'adresse n'est gardée que si c'est du https. */
fun rhetExamples(r: Rhet): List<RhetExample> = r.ex.mapNotNull { e ->
    val a = e as? JsonArray ?: return@mapNotNull null
    val text = a.getOrNull(1).str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    RhetExample(a.getOrNull(0).str().orEmpty(), text, safeHttps(a.getOrNull(2).str()))
}

fun aussiOf(r: Rhet): String = when (val a = r.aussi) {
    is JsonArray -> a.mapNotNull { it.str()?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", ")
    else -> a.str()?.trim().orEmpty()
}

const val OTHER_FAMILY = "Autres"

/** Famille d'un procédé : son nom, « Autres » s'il n'en a pas. */
fun familyLabel(r: Rhet): String = r.famille.trim().ifEmpty { OTHER_FAMILY }

/** Une famille et le rang (dans la liste complète) de ses procédés ; familles dans l'ordre de première apparition. */
class RhetFamily(val label: String, val cards: List<Int>)

fun rhetFamilies(list: List<Rhet>): List<RhetFamily> =
    list.indices.groupBy { familyLabel(list[it]) }.map { (k, v) -> RhetFamily(k, v) }

/** Procédé par son nom, à défaut sans tenir compte de la casse. */
fun rhetFind(list: List<Rhet>, t: String): Rhet? = list.firstOrNull { it.t == t } ?: list.firstOrNull { it.t.equals(t.trim(), ignoreCase = true) }

private val SPOT_LEAD = Regex("^\\s*<b>\\s*À repérer\\s*:?\\s*</b>\\s*:?\\s*")

/** « À repérer » est rendu en intitulé : on ne garde que le corps. */
fun spotBody(spot: String): String = spot.replace(SPOT_LEAD, "").trim()

/** Cartes « À table » : une par procédé, définition, repère et question. */
fun rhetTableCards(list: List<Rhet>): List<TableCard> = list.map { r ->
    TableCard(
        kicker = familyLabel(r), title = r.t,
        lines = listOf(r.d, spotBody(r.spot).takeIf { it.isNotBlank() }?.let { "À repérer : $it" }, r.ask.takeIf { it.isNotBlank() }?.let { "La question à poser : $it" })
            .filterNotNull().filter { it.isNotBlank() },
    )
}

// ---------- Dossiers ----------

@Serializable
data class ContentSource(val nom: String = "", val ev: String? = null, val type: String = "", val date: String = "", val relaye_par: String? = null)

@Serializable
data class ContentItem(
    val dit: String = "",
    val verdict: String = "",
    val faits: List<String> = emptyList(),
    val procede: String? = null,
    val src: JsonElement? = null,
    val cache: String? = null,
    val lecture: Lecture? = null,
)

@Serializable
data class ContentDossier(val id: String = "", val titre: String = "", val source: ContentSource = ContentSource(), val contexte: String = "", val items: List<ContentItem> = emptyList())

fun contentDossiersOf(data: PolData): List<ContentDossier> = data.decode<ContentDossier>("dossiers", "DOSSIERS").filter { it.titre.isNotBlank() }

private val SOURCE_TYPES = mapOf(
    "public" to "Organisme public", "academique" to "Recherche universitaire", "think-tank" to "Think tank",
    "patronal" to "Organisation patronale", "syndical" to "Syndicat", "militant" to "Organisation militante",
    "candidat" to "Parole de candidat", "media" to "Média", "sondage" to "Institut de sondage", "international" to "Organisation internationale",
)

/** Nature d'une source ou d'un évaluateur : libellé de la table, [fallback] si le type est vide ou inconnu. */
fun sourceTypeLabel(type: String, fallback: String = "Contenu"): String = SOURCE_TYPES[type.trim()] ?: fallback

/** Verdicts connus présents, dans l'ordre d'affichage, avec leur effectif ; un verdict inconnu ne compte que dans le total. */
fun dossierTally(items: List<ContentItem>): List<Pair<String, Int>> =
    VERDICT_ORDER.map { v -> v to items.count { it.verdict == v } }.filter { it.second > 0 }

/** Bandeau des verdicts : un segment par affirmation, même un verdict inconnu. */
fun dossierBar(items: List<ContentItem>): List<Pair<String, Int>> =
    dossierTally(items) + items.filter { it.verdict !in VERDICT_ORDER }.size.let { if (it > 0) listOf("" to it) else emptyList() }

// ---------- Évaluateurs ----------

/** Niveaux 1 à 4 puis 0 (non qualifié), seulement ceux qui ont des évaluateurs ; noms triés à la française. */
fun evaluatorsByLevel(evs: Collection<Evaluator>): List<Pair<Int, List<Evaluator>>> =
    (listOf(1, 2, 3, 4, 0)).map { n -> n to evs.filter { indLevel(it) == n }.sortedWith(compareBy(FR) { it.nom }) }.filter { it.second.isNotEmpty() }

fun hasTendency(e: Evaluator): Boolean = e.tendance.isNotBlank() && !e.tendance.trim().equals("non mesurée", ignoreCase = true)

/** Sous-titre d'une ligne : nature, et « tendance documentée » le cas échéant. */
fun evaluatorSub(e: Evaluator): String = listOf(sourceTypeLabel(e.type, e.type), "tendance documentée".takeIf { hasTendency(e) }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
