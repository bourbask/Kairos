package app.kairos.mobile.ui.politique.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Modèles typés des fichiers de données : tout est facultatif, un champ absent ou mal typé n'invalide pas le reste. */

@Serializable
data class Poste(val quoi: String = "", val montant: String? = null, val qui: String? = null)

@Serializable
data class Avis(val ev: String = "", val dit: String = "", val url: String? = null)

@Serializable
data class Lecture(
    val mesure: String = "",
    val angle_mort: String = "",
    val angle_mort_court: String = "",
    val contrepartie: String = "",
    val contrepartie_courte: String = "",
    val postes: List<Poste> = emptyList(),
    val ordre_grandeur: String = "",
    val avis: List<Avis> = emptyList(),
    val verdict: String = "",
)

/** [src] : forme libre, à lire avec [sourcesOf]. */
@Serializable
data class Claim(
    val theme: String = "",
    val camp: String = "",
    val q: String = "",
    val v: String = "",
    val facts: List<String> = emptyList(),
    val src: JsonElement? = null,
    val cache: String? = null,
    val origine: String = "",
    val par: List<String> = emptyList(),
    val date: String? = null,
    val lecture: Lecture? = null,
)

@Serializable
data class Dirigeant(val nom: String = "", val role: String = "", val parcours: String = "")

@Serializable
data class Historique(val annee: Int? = null, val sujet: String = "", val constat: String = "", val src: JsonElement? = null)

@Serializable
data class Controverse(val t: String = "", val src: JsonElement? = null)

@Serializable
data class Evaluator(
    val id: String = "",
    val nom: String = "",
    val type: String = "",
    val statut: String = "",
    val financement: List<String> = emptyList(),
    val dirigeants: List<Dirigeant> = emptyList(),
    val orientation: String = "",
    val independance: Int? = null,
    val justification: String = "",
    val tendance: String = "",
    val historique: List<Historique> = emptyList(),
    val controverses: List<Controverse> = emptyList(),
    val alias: List<String> = emptyList(),
    val src: JsonElement? = null,
)

data class SourceRef(val label: String, val url: String? = null, val ev: String? = null)

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** `[libellé, url]`, `[[libellé, url, ev?], …]`, `{ev, url}` (ou `{nom, url}`) ou chaîne seule. */
fun sourcesOf(e: JsonElement?): List<SourceRef> = when (e) {
    is JsonPrimitive -> listOfNotNull(e.text()?.takeIf { it.isNotBlank() }?.let { SourceRef(it) })
    is JsonObject -> {
        val url = e["url"].text()
        val ev = e["ev"].text()
        val label = (e["label"] ?: e["nom"]).text() ?: ev ?: url
        listOfNotNull(label?.let { SourceRef(it, url, ev) })
    }
    is JsonArray ->
        if (e.isNotEmpty() && e.all { it is JsonPrimitive }) {
            e[0].text()?.let { listOf(SourceRef(it, e.getOrNull(1).text(), e.getOrNull(2).text())) }.orEmpty()
        } else {
            e.flatMap { sourcesOf(it) }
        }
    else -> emptyList()
}

/** Ligne-tableau des données (`["libellé", 1.5, null…]`) : lecture par position, tolérante. */
class Row(private val a: List<JsonElement>) {
    fun s(i: Int): String = (a.getOrNull(i) as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun d(i: Int): Double? = (a.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
}
