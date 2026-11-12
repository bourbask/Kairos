package app.kairos.mobile.ui.politique.model

import app.kairos.mobile.data.ApiError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Une page d'un fichier de données (`app_politique_fichier`). [contenu] : objet, ou liste pour un fichier-liste. */
@Serializable
data class PolPage(
    val id: String,
    val page: Int = 0,
    val pages: Int = 1,
    val version: String = "",
    val contenu: JsonElement = JsonObject(emptyMap()),
)

val POL_FILES: List<String> = listOf(
    "meta", "identites", "affirmations", "argent", "bilan", "budgets", "dit-vote", "dossiers", "evaluateurs",
    "carte", "medias", "niveau-de-vie", "probite", "revirements", "rhetorique", "villes", "votes",
)

/** Clé sous laquelle un fichier dont le contenu est une liste (jamais découpée) est rangé. */
const val LIST_KEY = "_liste"

private const val UNKNOWN_FILE = "Fichier inconnu."
private const val ATTEMPTS = 2

/**
 * Réassemble les pages d'un fichier. La liste découpée est celle que la page 0 porte (vide) et que
 * les suivantes complètent : toute liste présente sous le même chemin est concaténée, les autres clés
 * viennent de la page 0.
 */
fun mergePages(pages: List<PolPage>): JsonObject {
    val sorted = pages.sortedBy { it.page }
    val first = sorted.firstOrNull()?.contenu ?: return JsonObject(emptyMap())
    if (first is JsonArray) return JsonObject(mapOf(LIST_KEY to first))
    return mergeObjects(sorted.map { it.contenu as? JsonObject ?: JsonObject(emptyMap()) })
}

private fun mergeObjects(parts: List<JsonObject>): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    for (p in parts) for ((k, v) in p) {
        val prev = out[k]
        out[k] = when {
            prev is JsonArray && v is JsonArray -> JsonArray(prev + v)
            prev is JsonObject && v is JsonObject -> mergeObjects(listOf(prev, v))
            prev == null -> v
            else -> prev // clé scalaire : celle de la page 0
        }
    }
    return JsonObject(out)
}

private suspend fun loadFile(id: String, fetch: suspend (String, Int) -> PolPage): JsonObject? {
    repeat(ATTEMPTS) {
        val first = try {
            fetch(id, 0)
        } catch (e: ApiError.Server) {
            if (e.message == UNKNOWN_FILE) return null else throw e
        }
        val pages = mutableListOf(first)
        var stable = true
        for (n in 1 until first.pages) {
            val p = fetch(id, n)
            if (p.version != first.version) { stable = false; break }
            pages += p
        }
        if (stable) return mergePages(pages)
    }
    throw IllegalStateException("Fichier modifié pendant la lecture : $id")
}

/** Charge les fichiers en parallèle ; un fichier inconnu du module est simplement absent. */
suspend fun loadPolData(
    fetch: suspend (id: String, page: Int) -> PolPage,
    files: List<String> = POL_FILES,
): PolData = coroutineScope {
    val loaded = files.map { id -> async { loadFile(id, fetch)?.let { id to it } } }.awaitAll()
    PolData(loaded.filterNotNull().toMap())
}
