package app.kairos.mobile.ui.politique.model

import app.kairos.mobile.data.PolIdentity
import app.kairos.mobile.ui.politique.fold
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

enum class IdKind { Personne, Parti, Camp }

@PublishedApi
internal val PolJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

@Serializable
private class Aliases(val id: String = "", val alias: List<String> = emptyList())

/** Fichiers de données assemblés, avec les résolutions d'identités et d'évaluateurs. */
class PolData(private val files: Map<String, JsonObject>) {

    fun file(id: String): JsonObject? = files[id]

    /** Éléments d'une liste (`path` : clés séparées par des points, `""` pour un fichier-liste) ; invalides ignorés un à un. */
    fun <T> decode(file: String, path: String, s: KSerializer<T>): List<T> {
        var cur: Any? = files[file]?.let { if (path.isEmpty()) it[LIST_KEY] else it }
        if (path.isNotEmpty()) for (k in path.split('.')) cur = (cur as? JsonObject)?.get(k)
        return (cur as? JsonArray).orEmpty().mapNotNull { e ->
            try { PolJson.decodeFromJsonElement(s, e) } catch (_: Exception) { null }
        }
    }

    inline fun <reified T> decode(file: String, path: String): List<T> = decode(file, path, PolJson.serializersModule.serializer<T>())

    val identities: List<PolIdentity> = decode("identites", "", PolIdentity.serializer())
    private val aliases: Map<String, List<String>> = decode("identites", "", Aliases.serializer()).associate { it.id to it.alias }
    private val byIdMap = identities.associateBy { it.id }
    private val keyIndex: Map<String, List<String>> = identities
        .flatMap { i -> (listOf(i.nom) + aliases[i.id].orEmpty()).filter { it.isNotBlank() }.map { fold(it) to i.id } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }

    val evaluators: Map<String, Evaluator> = decode<Evaluator>("evaluateurs", "EVALUATEURS").filter { it.id.isNotEmpty() }.associateBy { it.id }
    private val evKeys: List<Pair<String, Evaluator>> = evaluators.values
        .flatMap { e -> (e.alias + e.nom).filter { it.isNotBlank() }.map { fold(it) to e } }
        .sortedByDescending { it.first.length }

    val verifiedOn: String? = ((files["meta"]?.get("verifie_le")) as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun byId(id: String): PolIdentity? = byIdMap[id]

    /** Toutes les identités dont un alias (ou le nom) égale [label], accents et casse ignorés. */
    fun idsIn(label: String?): List<String> = label?.takeIf { it.isNotBlank() }?.let { keyIndex[fold(it)] }.orEmpty()

    fun idOf(label: String?): String? = idsIn(label).firstOrNull()

    fun kindOf(id: String): IdKind {
        val i = byIdMap[id]
        return when {
            i?.parti == null -> IdKind.Camp
            i.parti.isNotEmpty() && i.parti != i.nom -> IdKind.Personne
            else -> IdKind.Parti
        }
    }

    fun partyOf(id: String): String? {
        val p = byIdMap[id]?.parti?.takeIf { it.isNotEmpty() } ?: return null
        return idsIn(p).firstOrNull { it != id }
    }

    /** Évaluateur désigné par [explicit] (id), sinon par l'alias le plus long qui ouvre [label] et finit sur une fin de mot. */
    fun evFor(label: String?, explicit: String? = null): Evaluator? {
        explicit?.let { evaluators[it] }?.let { return it }
        val l = fold(label ?: return null)
        return evKeys.firstOrNull { (k, _) -> l.startsWith(k) && (l.length == k.length || !l[k.length].isLetterOrDigit()) }?.second
    }
}
