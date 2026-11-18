package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.kit.quoted
import app.kairos.mobile.ui.politique.screens.Owner
import app.kairos.mobile.ui.politique.screens.Program
import app.kairos.mobile.ui.politique.screens.ReportQuota
import app.kairos.mobile.ui.politique.screens.ReportReceipt
import app.kairos.mobile.ui.politique.screens.VoteRow
import app.kairos.mobile.ui.politique.screens.claimsOf
import app.kairos.mobile.ui.politique.screens.declarationsOf
import app.kairos.mobile.ui.politique.screens.fmtNum
import app.kairos.mobile.ui.politique.screens.graviteLabel
import app.kairos.mobile.ui.politique.screens.originDeck
import app.kairos.mobile.ui.politique.screens.promessesOf
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Vues pleine page de l'espace, empilées au-dessus de l'accueil. */
sealed interface PolRoute {
    data object Home : PolRoute
    data class Rubric(val id: String) : PolRoute
    /** Page d'une porte de l'accueil ([Door.id]). */
    data class Door(val id: String) : PolRoute
    data object Quiz : PolRoute
    data class Duel(val a: String? = null, val b: String? = null) : PolRoute
    data object Site : PolRoute
    data object Shares : PolRoute
}

/** Feuilles ouvertes par-dessus la vue courante ; chaque écran ajoute les siennes. */
sealed interface PolSheetState {
    data class Identity(val id: String) : PolSheetState
    data class Evaluator(val id: String) : PolSheetState
    /** Procédé rhétorique, par son nom. */
    data class Rhet(val t: String) : PolSheetState
    data class Claim(val index: Int) : PolSheetState
    data class Scrutin(val id: String) : PolSheetState
    data class Balance(val lecture: Lecture, val title: String, val who: String?) : PolSheetState
    /** [key] : code commune, sinon nom (voir `Commune.key`). */
    data class City(val key: String) : PolSheetState
    /** Villes d'une même bulle, par clé. */
    data class Cluster(val villes: List<String>) : PolSheetState
    /** Barème des verdicts et de l'indice de confiance. */
    data object Bareme : PolSheetState
    /** Source signée par un nom sans fiche d'évaluateur. */
    data class NoFiche(val name: String) : PolSheetState
    /** Signalement d'une erreur sur une affirmation, par son identifiant. */
    data class Report(val claimId: String) : PolSheetState
}

/** Ce qu'un écran peut demander à la coque. */
interface PolNavigator {
    fun go(r: PolRoute)
    /** Remplace la vue courante : le retour ne repasse pas par elle. */
    fun replace(r: PolRoute)
    fun sheet(s: PolSheetState)
    fun table(cards: List<TableCard>, start: Int)
    /** Lien du site enregistré sur l'appareil ; null s'il n'y en a pas. */
    fun siteLink(): String? = null
    /** Dépose un signalement ([reportArgs]) ; lève [app.kairos.mobile.data.ApiError]. */
    suspend fun report(args: JsonObject): ReportReceipt
    /** Lance [block] sur la durée de l'espace, pas de la feuille : un envoi en cours survit à sa fermeture. */
    fun launch(block: suspend () -> Unit)
    /** Quota d'envoi, gardé quand la feuille de signalement se ferme. */
    var reportQuota: ReportQuota
}

/**
 * Pile de navigation : les feuilles d'abord, puis les vues ; `null` au retour depuis l'accueil
 * nu, l'espace se ferme. Une vue ouverte depuis une feuille la remplace (une feuille couvre tout).
 */
data class PolBackStack(val routes: List<PolRoute> = emptyList(), val sheets: List<PolSheetState> = emptyList()) {
    val top: PolRoute get() = routes.lastOrNull() ?: PolRoute.Home

    /** Même vue qu'au sommet (double appui) : rien n'est empilé. */
    fun go(r: PolRoute): PolBackStack = when (r) {
        PolRoute.Home -> PolBackStack()
        top -> PolBackStack(routes)
        else -> PolBackStack(routes + r)
    }

    fun replace(r: PolRoute): PolBackStack = PolBackStack(routes.dropLast(1)).go(r)

    fun open(s: PolSheetState): PolBackStack = copy(sheets = sheets + s)

    fun back(): PolBackStack? = when {
        sheets.isNotEmpty() -> copy(sheets = sheets.dropLast(1))
        routes.isNotEmpty() -> copy(routes = routes.dropLast(1))
        else -> null
    }
}

/** Chargement des données de l'espace. */
sealed interface PolLoad {
    data object Loading : PolLoad
    data class Ready(val data: PolData) : PolLoad
    data class Down(val reason: String) : PolLoad
}

enum class HomeAction { Retry, OpenSite }

/** Hors ligne : réessayer, et le site reste ouvrable. */
fun homeActions(load: PolLoad): List<HomeAction> = if (load is PolLoad.Down) listOf(HomeAction.Retry, HomeAction.OpenSite) else emptyList()

/** Entrée de l'accueil vers une rubrique ; [count] : compte lisible tiré des données. */
class RubricTile(val id: String, val title: String, val hook: String, val count: (PolData) -> String)

/** « 0 scrutin », « 1 scrutin », « 3 scrutins ». */
fun plural(n: Int, one: String, many: String): String = "$n ${if (n > 1) many else one}"

private fun PolData.size(file: String, path: String): Int = decode(file, path, JsonObject.serializer()).size

/** Compte d'une tuile ; zéro : rien de publié. */
private fun Int.or(one: String, many: String) = if (this == 0) "aucune donnée" else plural(this, one, many)

/** Thèmes et camps des phrases vérifiées, pour « Hors de ma bulle » et le quiz. */
fun bubbleLine(data: PolData): String {
    val themes = data.decode("affirmations", "CLAIMS", JsonObject.serializer()).mapNotNull { (it["theme"] as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet().size
    val camps = data.decode<String>("affirmations", "CAMPS").size
    return "${themes.or("thème", "thèmes")} · ${camps.or("camp", "camps")}"
}

val RUBRICS: List<RubricTile> = listOf(
    RubricTile("budgets", "Budgets", "Où mène chaque programme, face à « ne rien faire »") { d ->
        d.decode("budgets", "PROGRAMS", JsonObject.serializer()).count { it["ref"]?.jsonPrimitive?.booleanOrNull != true }.or("programme", "programmes")
    },
    RubricTile("niveau-de-vie", "Niveau de vie", "Ce qu'on vous donne, ce que ça coûte") { it.size("niveau-de-vie", "MEASURES").or("programme", "programmes") },
    RubricTile("votes", "Votes", "Ce qu'ils votent, pas ce qu'ils disent") { (it.size("votes", "AN_V") + it.size("votes", "PE_V")).or("scrutin", "scrutins") },
    RubricTile("dit-vote", "Dit / voté", "La parole face au vote, parti par parti") { it.size("dit-vote", "SCRUTINS").or("scrutin croisé", "scrutins croisés") },
    RubricTile("revirements", "Revirements", "Qui a changé d'avis, et sur quoi") { it.size("revirements", "RV").or("position suivie", "positions suivies") },
    RubricTile("bilan", "Bilans", "D'où vient la dette, qui continue la ligne") { it.size("bilan", "MATRIX").or("orientation", "orientations") },
    RubricTile("rhetorique", "Rhétorique", "Les procédés, en cartes à retourner") { it.size("rhetorique", "RHET").or("procédé", "procédés") },
    RubricTile("probite", "Probité", "Les affaires, étape par étape") { it.decode<kotlinx.serialization.json.JsonArray>("probite", "AFF").size.or("procédure", "procédures") },
    RubricTile("argent", "Argent", "Qui finance partis et campagnes") { it.size("argent", "FIN").or("dossier", "dossiers") },
    RubricTile("medias", "Médias", "Qui possède quoi, ce qui est prouvé") { it.size("medias", "OWNERS").or("propriétaire", "propriétaires") },
    RubricTile("villes", "Résultats locaux", "La carte des mairies, et ce qu'ils y font") { it.size("villes", "COMMUNES").or("ville suivie", "villes suivies") },
    RubricTile("declarations", "Paroles publiques", "Ce qu'ils ont dit, face aux faits") { declarationsOf(it).size.or("déclaration", "déclarations") },
    RubricTile("bulle", "Hors de ma bulle", "Les mêmes thèmes, lus depuis chaque camp, côte à côte.", ::bubbleLine),
    RubricTile("phrases", "Phrases vérifiées", "Ce que chaque phrase entendue cache vraiment.") { it.size("affirmations", "CLAIMS").or("phrase vérifiée", "phrases vérifiées") },
    RubricTile("dossiers", "Dossiers", "Un contenu viral, repris phrase par phrase.") { it.size("dossiers", "DOSSIERS").or("dossier", "dossiers") },
    RubricTile("evaluateurs", "Qui chiffre ?", "Les évaluateurs, leur argent, leur indépendance.") { it.evaluators.size.or("évaluateur", "évaluateurs") },
    RubricTile("promesses", "Gravité des promesses", "De la simple divergence à l'atteinte aux droits.") { promessesOf(it).size.or("promesse", "promesses") },
    RubricTile("origines", "Ça vient d'où ?", "D'où vient l'idée, ce qu'elle cache : une carte, trente secondes.") { originDeck(it, 0).size.or("carte", "cartes") },
)

fun rubricTitle(id: String): String = RUBRICS.firstOrNull { it.id == id }?.title ?: "Politique"

/** Thème de l'espace (nom de repli : [kicker], le nom publié dans `jeux` prime) : [kicker] court, [question] en titre, ses rubriques (ids de [RUBRICS]) dans l'ordre des pastilles. */
class Door(val id: String, val kicker: String, val question: String, val rubrics: List<String>)

/** Toute rubrique est dans exactement une porte. */
val DOORS: List<Door> = listOf(
    Door("verifier", "Démêle", "Le vrai du faux dans les discours", listOf("phrases", "declarations", "dossiers", "rhetorique", "origines", "bulle")),
    Door("chiffrer", "Mesure", "Le poids réel des promesses", listOf("budgets", "promesses", "niveau-de-vie", "evaluateurs")),
    Door("bilan", "Retrace", "Leurs actes, face à leurs paroles", listOf("bilan", "votes", "dit-vote", "revirements", "villes")),
    Door("pouvoirs", "Relie", "Qui possède, qui finance", listOf("argent", "probite", "medias")),
)

fun doorOf(rubric: String): Door? = DOORS.firstOrNull { rubric in it.rubrics }

/** Compteur d'une pastille : le nombre d'un compte simple (« 12 scrutins ») ; compte composé ou vide : aucun. */
fun pillCount(count: String): Int? = count.takeIf { '·' !in it }?.substringBefore(' ')?.toIntOrNull()

/** Accroches possibles d'une porte, tirées des données publiées. */
private fun teasers(data: PolData, door: Door): List<String> = when (door.id) {
    "verifier" -> claimsOf(data).filter { it.q.isNotBlank() && it.v != "non-documente" }.map { "${quoted(it.q)} Vrai ou faux ?" }
    "chiffrer" -> promessesOf(data).filter { it.gravite in 1..4 }.map { p -> listOfNotNull(data.byId(p.qui)?.nom ?: p.qui.takeIf(String::isNotBlank), p.promesse.trim().trimEnd('.')).joinToString(" : ") + " · ${graviteLabel(p.gravite)}" }
        .ifEmpty { data.decode<Program>("budgets", "PROGRAMS").filter { !it.ref && it.who.isNotBlank() && it.debt != null }.map { "${it.who} : dette publique à ${fmtNum(it.debt!!, 1)} % du PIB à l'horizon" } }
    "bilan" -> (data.decode<VoteRow>("votes", "AN_V") + data.decode<VoteRow>("votes", "PE_V")).filter { it.t.isNotBlank() }.map { "Scrutin : ${it.t.trim()}" }
    "pouvoirs" -> data.decode<Owner>("medias", "OWNERS").filter { it.o.isNotBlank() && it.m.isNotEmpty() }.map { "${it.o} : ${it.m.take(3).joinToString(", ")}" }
    else -> emptyList()
}

/** Accroche « du jour » d'une porte : la même toute la journée, une autre le lendemain ; rien de publié : null. */
fun dailyTeaser(data: PolData, door: Door, day: LocalDate): String? =
    teasers(data, door).takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(day.toEpochDay() + door.id.hashCode(), it.size.toLong()).toInt()] }
