package app.kairos.mobile.ui.politique.model

import app.kairos.mobile.data.PolIdentity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.text.Normalizer

/** Texte sans accents ni casse, espaces resserrés : comparaison des clés et libellés. */
fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim().replace(Regex("\\s+"), " ")

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
    private val decoded = java.util.concurrent.ConcurrentHashMap<Triple<String, String, KSerializer<*>>, List<*>>()

    /** Décodé une fois par (fichier, chemin, sérialiseur) : les écrans relisent les mêmes listes à chaque affichage. */
    @Suppress("UNCHECKED_CAST")
    fun <T> decode(file: String, path: String, s: KSerializer<T>): List<T> =
        decoded.computeIfAbsent(Triple(file, path, s)) { decodeNow(file, path, s) } as List<T>

    private fun <T> decodeNow(file: String, path: String, s: KSerializer<T>): List<T> {
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

    fun aliasesOf(id: String): List<String> = aliases[id].orEmpty()

    /** Toutes les identités dont un alias (ou le nom) égale [label], accents et casse ignorés. */
    fun idsIn(label: String?): List<String> = label?.takeIf { it.isNotBlank() }?.let { keyIndex[fold(it)] }.orEmpty()

    fun idOf(label: String?): String? = idsIn(label).firstOrNull()

    /** Libellés des candidats (bilan, programmes hors référence), repliés. */
    private val candidates: Set<String> by lazy {
        (decode<String>("bilan", "CANDS") +
            decode("budgets", "PROGRAMS", JsonObject.serializer())
                .filter { (it["ref"] as? JsonPrimitive)?.content != "true" }
                .mapNotNull { (it["who"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
            .map(::fold).filter { it.isNotEmpty() }.toSet()
    }

    private val kinds = java.util.concurrent.ConcurrentHashMap<String, IdKind>()

    /**
     * Type publié (`type`) d'abord ; sinon déduit : camp sans parti ; parti si le nom est le parti ;
     * personne si un alias est un candidat ; parti si le parti ou le nom figure dans les alias ; sinon personne.
     */
    fun kindOf(id: String): IdKind = kinds.computeIfAbsent(id) {
        val i = byIdMap[id] ?: return@computeIfAbsent IdKind.Camp
        when (i.type?.let(::fold)) {
            "personne" -> return@computeIfAbsent IdKind.Personne
            "parti" -> return@computeIfAbsent IdKind.Parti
            "camp" -> return@computeIfAbsent IdKind.Camp
        }
        val parti = i.parti?.let(::fold).orEmpty()
        val al = aliases[id].orEmpty().map(::fold)
        when {
            parti.isEmpty() -> IdKind.Camp
            parti == fold(i.nom) -> IdKind.Parti
            al.any { it in candidates } -> IdKind.Personne
            parti in al || fold(i.nom) in al -> IdKind.Parti
            else -> IdKind.Personne
        }
    }

    /** Parti d'une personne : l'identité de type parti au même sigle, sinon celle que désigne son libellé de parti. */
    fun partyOf(id: String): String? {
        if (kindOf(id) != IdKind.Personne) return null
        val p = byIdMap[id]?.parti?.takeIf { it.isNotEmpty() } ?: return null
        return identities.firstOrNull { it.id != id && kindOf(it.id) == IdKind.Parti && fold(it.parti.orEmpty()) == fold(p) }?.id
            ?: idsIn(p).firstOrNull { it != id && kindOf(it) == IdKind.Parti }
    }

    /** Évaluateur désigné par [explicit] (id), sinon par l'alias le plus long qui ouvre [label] et finit sur une fin de mot. */
    fun evFor(label: String?, explicit: String? = null): Evaluator? {
        explicit?.let { evaluators[it] }?.let { return it }
        val l = fold(label ?: return null)
        return evKeys.firstOrNull { (k, _) -> l.startsWith(k) && (l.length == k.length || !l[k.length].isLetterOrDigit()) }?.second
    }
}
