package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.kit.indLevel
import app.kairos.mobile.ui.politique.kit.safeHttps
import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.Niveau
import app.kairos.mobile.ui.politique.model.normalizeVerdict
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
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

/** [type] : vide pour une analyse, `synthese` pour une synthèse ; un type inconnu s'affiche comme une analyse. */
@Serializable
data class ContentDossier(
    val id: String = "",
    val titre: String = "",
    val source: ContentSource = ContentSource(),
    val contexte: String = "",
    val items: List<ContentItem> = emptyList(),
    val type: String = "",
    val etat: String = "",
    val sections: List<JsonElement> = emptyList(),
)

fun contentDossiersOf(data: PolData): List<ContentDossier> = data.decode<ContentDossier>("dossiers", "DOSSIERS").filter { it.titre.isNotBlank() }
        .map { d -> d.copy(items = d.items.map { it.copy(verdict = normalizeVerdict(it.verdict)) }) }

// ---------- Synthèses ----------

@Serializable
data class SynthPara(val texte: String = "", val src: JsonElement? = null)

@Serializable
data class SynthRow(val cellules: List<String> = emptyList(), val src: JsonElement? = null)

@Serializable
data class SynthTable(val colonnes: List<String> = emptyList(), val lignes: List<SynthRow> = emptyList())

@Serializable
data class SynthSection(val titre: String = "", val paragraphes: List<SynthPara> = emptyList(), val tableau: SynthTable? = null, val notes: List<SynthPara> = emptyList())

fun isSynthese(d: ContentDossier): Boolean = d.type.trim() == "synthese"

private fun List<SynthPara>.clean() = filter { it.texte.isNotBlank() }

/** Sections lisibles, dans l'ordre : une section invalide ou sans contenu est écartée seule. */
fun synthSections(d: ContentDossier): List<SynthSection> = if (!isSynthese(d)) emptyList() else d.sections.mapNotNull { e ->
    val s = try { PolJson.decodeFromJsonElement(SynthSection.serializer(), e) } catch (_: Exception) { return@mapNotNull null }
    val t = s.tableau?.takeIf { it.colonnes.isNotEmpty() && stackedRows(it).isNotEmpty() }
    s.copy(paragraphes = s.paragraphes.clean(), tableau = t, notes = s.notes.clean()).takeIf { it.paragraphes.isNotEmpty() || it.tableau != null }
}

/** Ligne de tableau empilée pour un écran étroit : première cellule en titre, puis « colonne : cellule ». */
class StackedRow(val title: String, val fields: List<Pair<String, String>>, val src: JsonElement?)

/** Cellules lues colonne par colonne : vide omise, en trop ignorée ; ligne vide retirée. */
fun stackedRows(t: SynthTable): List<StackedRow> = t.lignes.mapNotNull { r ->
    val cells = t.colonnes.indices.map { r.cellules.getOrNull(it)?.trim().orEmpty() }
    if (cells.all { it.isEmpty() }) return@mapNotNull null
    StackedRow(cells[0], t.colonnes.drop(1).zip(cells.drop(1)).filter { it.second.isNotEmpty() }, r.src)
}

/** « Synthèse », suivi de la date de l'état des connaissances quand elle est connue. */
fun syntheseKicker(d: ContentDossier): String =
    listOfNotNull("Synthèse", d.etat.trim().takeIf { it.isNotEmpty() }?.let { "État au ${fmtDate(it)}" }).joinToString(" · ")

private val SOURCE_TYPES = mapOf(
    "public" to "Organisme public", "academique" to "Recherche universitaire", "think-tank" to "Think tank",
    "patronal" to "Organisation patronale", "syndical" to "Syndicat", "militant" to "Organisation militante",
    "candidat" to "Candidat", "entreprise" to "Entreprise", "media" to "Média", "sondage" to "Institut de sondage", "international" to "Organisation internationale",
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

/** Palier affiché dans la feuille d'un producteur : sa fiche, ou l'explication des niveaux. */
enum class Tier { Fiche, Niveaux }

/** Retour : des niveaux à la fiche ; depuis la fiche, null (la feuille se ferme). */
fun Tier.back(): Tier? = if (this == Tier.Niveaux) Tier.Fiche else null

/** Un niveau expliqué : ses producteurs (noms triés), et s'il est celui du producteur d'où l'on vient. */
class LevelView(val niveau: Niveau, val producers: List<Evaluator>, val highlighted: Boolean)

/** Niveaux dans l'ordre publié ; [hl] : niveau mis en évidence (0 pour une source « ? »), null pour aucun. */
fun levelViews(data: PolData, hl: Int?): List<LevelView> {
    val by = evaluatorsByLevel(data.evaluators.values).toMap()
    return data.niveaux.map { LevelView(it, by[it.n].orEmpty(), it.n == hl) }
}
