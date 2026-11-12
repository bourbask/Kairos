package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Vues pleine page de l'espace, empilées au-dessus de l'accueil. */
sealed interface PolRoute {
    data object Home : PolRoute
    data class Rubric(val id: String) : PolRoute
    data object Quiz : PolRoute
    data class Duel(val a: String? = null, val b: String? = null) : PolRoute
    data object Site : PolRoute
    data object Shares : PolRoute
}

/** Feuilles ouvertes par-dessus la vue courante ; chaque écran ajoute les siennes. */
sealed interface PolSheetState {
    data class Identity(val id: String) : PolSheetState
    data class Evaluator(val id: String) : PolSheetState
    data class Claim(val index: Int) : PolSheetState
    data class Scrutin(val id: String) : PolSheetState
    data class Balance(val lecture: Lecture, val title: String, val who: String?) : PolSheetState
    /** [key] : code commune, sinon nom (voir `Commune.key`). */
    data class City(val key: String) : PolSheetState
    /** Villes d'une même bulle, par clé. */
    data class Cluster(val villes: List<String>) : PolSheetState
}

/** Ce qu'un écran peut demander à la coque. */
interface PolNavigator {
    fun go(r: PolRoute)
    fun sheet(s: PolSheetState)
    fun table(cards: List<TableCard>, start: Int)
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

enum class TileKind { Tile, Wide, Link }

/** Entrée de l'accueil vers une rubrique ; [count] : compte lisible tiré des données. */
class RubricTile(val id: String, val title: String, val hook: String, val kind: TileKind = TileKind.Tile, val count: (PolData) -> String)

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
    RubricTile("budgets", "Budgets 2032", "Où mène chaque programme, face à « ne rien faire »") { d ->
        d.decode("budgets", "PROGRAMS", JsonObject.serializer()).count { it["ref"]?.jsonPrimitive?.booleanOrNull != true }.or("programme", "programmes")
    },
    RubricTile("niveau-de-vie", "Niveau de vie", "Ce qu'on vous donne, ce que ça coûte") { it.size("niveau-de-vie", "MEASURES").or("programme", "programmes") },
    RubricTile("votes", "Votes", "Ce qu'ils votent, pas ce qu'ils disent") { (it.size("votes", "AN_V") + it.size("votes", "PE_V")).or("scrutin", "scrutins") },
    RubricTile("dit-vote", "Dit / voté", "La parole face au vote, parti par parti") { it.size("dit-vote", "SCRUTINS").or("scrutin croisé", "scrutins croisés") },
    RubricTile("revirements", "Revirements", "Qui a changé d'avis, et sur quoi") { it.size("revirements", "RV").or("position suivie", "positions suivies") },
    RubricTile("bilan", "Bilan sortant", "D'où vient la dette, qui continue la ligne") { it.size("bilan", "MATRIX").or("orientation", "orientations") },
    RubricTile("rhetorique", "Rhétorique", "Les procédés, en cartes à retourner") { it.size("rhetorique", "RHET").or("procédé", "procédés") },
    RubricTile("probite", "Probité", "Les affaires, étape par étape") { it.decode<kotlinx.serialization.json.JsonArray>("probite", "AFF").size.or("procédure", "procédures") },
    RubricTile("argent", "Argent", "Qui finance partis et campagnes") { it.size("argent", "FIN").or("dossier", "dossiers") },
    RubricTile("medias", "Médias", "Qui possède quoi, ce qui est prouvé") { it.size("medias", "OWNERS").or("propriétaire", "propriétaires") },
    RubricTile("villes", "Villes", "La carte des mairies, et ce qu'ils y font", TileKind.Wide) { it.size("villes", "COMMUNES").or("ville suivie", "villes suivies") },
    RubricTile("bulle", "Hors de ma bulle", "Les mêmes thèmes, lus depuis chaque camp, côte à côte.", TileKind.Wide, ::bubbleLine),
    RubricTile("phrases", "Phrases", "Ce que chaque phrase entendue cache vraiment.", TileKind.Link) { it.size("affirmations", "CLAIMS").or("phrase vérifiée", "phrases vérifiées") },
    RubricTile("dossiers", "Dossiers", "Un contenu viral, repris phrase par phrase.", TileKind.Link) { it.size("dossiers", "DOSSIERS").or("dossier", "dossiers") },
    RubricTile("evaluateurs", "Qui chiffre ?", "Les évaluateurs, leur argent, leur indépendance.", TileKind.Link) { it.evaluators.size.or("évaluateur", "évaluateurs") },
)

fun rubricTitle(id: String): String = RUBRICS.firstOrNull { it.id == id }?.title ?: "Politique"
