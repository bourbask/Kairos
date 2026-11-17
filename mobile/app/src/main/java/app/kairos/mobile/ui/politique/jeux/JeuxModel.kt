package app.kairos.mobile.ui.politique.jeux

import app.kairos.mobile.ui.politique.DOORS
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.sourcesOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Fichier des jeux (`jeux`) : thèmes de l'espace et jeux, tout facultatif, un élément invalide est ignoré seul. */
const val JEUX_FILE = "jeux"

@Serializable
data class Reponse(
    val titre: String = "",
    /** Code de verdict ; null ou vide : pas d'étiquette. */
    val verdict: String? = null,
    val texte: String = "",
    val cache: String? = null,
    /** Libellé du pied de source pour le lecteur d'écran ; absent : « Source ». */
    val libelle: String? = null,
    /** `[[libellé, url, ev?], …]` ou toute forme lue par [sourcesOf]. */
    val sources: JsonElement? = null,
) {
    val refs: List<SourceRef> get() = sourcesOf(sources)
}

/** Une carte d'un jeu : [d] est propre au jeu, décodé par lui (voir [PolJeu]). */
@Serializable
data class Manche(
    val id: String = "",
    val kicker: String = "",
    val q: String = "",
    val d: JsonObject = JsonObject(emptyMap()),
    val reponse: Reponse = Reponse(),
)

@Serializable
data class Jeu(
    val nom: String = "",
    val sousTitre: String = "",
    val theme: String = "",
    val manette: String = "",
    /** Constantes du jeu communes à toutes les manches (crans, unités, libellés). */
    val params: JsonObject = JsonObject(emptyMap()),
    val manches: List<Manche> = emptyList(),
)

@Serializable
private class ThemeData(val id: String = "", val nom: String = "", val question: String = "", val jeux: List<JsonElement> = emptyList())

/** Thème de l'espace : rubriques de la porte de même id, nom et jeux tirés des données. */
class Theme(val id: String, val nom: String, val question: String, val jeux: List<String>, val rubrics: List<String>)

/** Jeux publiés, par slug ; manches sans id écartées, ids en double : la première gardée. */
fun jeuxOf(data: PolData): Map<String, Jeu> {
    val raw = data.file(JEUX_FILE)?.get("JEUX") as? JsonObject ?: return emptyMap()
    return raw.mapNotNull { (slug, v) ->
        val o = v as? JsonObject ?: return@mapNotNull null
        val j = runCatching { PolJson.decodeFromJsonElement(Jeu.serializer(), JsonObject(o - "manches")) }.getOrNull() ?: return@mapNotNull null
        // Manches une à une : une invalide n'emporte pas le jeu.
        val manches = (o["manches"] as? JsonArray).orEmpty().mapNotNull { runCatching { PolJson.decodeFromJsonElement(Manche.serializer(), it) }.getOrNull() }
        slug to j.copy(manches = manches.filter { m -> m.id.isNotBlank() }.distinctBy { m -> m.id })
    }.toMap()
}

private fun JsonElement.slug(): String? = when (this) {
    is JsonPrimitive -> takeIf { it.isString }?.contentOrNull
    is JsonObject -> (this["slug"] as? JsonPrimitive)?.contentOrNull
    else -> null
}?.takeIf { it.isNotBlank() }

/**
 * Les quatre thèmes, dans l'ordre des portes : nom et question publiés, sinon ceux de la porte ;
 * jeux listés par le thème, sinon les jeux qui le déclarent ([Jeu.theme]), dans l'ordre du fichier.
 */
fun themesOf(data: PolData, jeux: Map<String, Jeu> = jeuxOf(data)): List<Theme> {
    val published = data.decode("jeux", "THEMES", ThemeData.serializer()).associateBy { it.id }
    return DOORS.map { d ->
        val t = published[d.id]
        val listed = t?.jeux?.mapNotNull { it.slug() }.orEmpty()
        val games = listed.ifEmpty { jeux.filterValues { it.theme == d.id }.keys.toList() }
        Theme(d.id, t?.nom?.takeIf { it.isNotBlank() } ?: d.kicker, t?.question?.takeIf { it.isNotBlank() } ?: d.question, games, d.rubrics)
    }
}
