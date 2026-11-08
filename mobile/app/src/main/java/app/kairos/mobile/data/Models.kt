package app.kairos.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lecture tolérante : champs inconnus ignorés, valeurs inattendues ramenées au défaut. */
internal val KairosJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
enum class Tone {
    @SerialName("neutre") Neutre,
    @SerialName("attention") Attention,
    @SerialName("urgent") Urgent,
}

@Serializable
data class Chip(val text: String, val tone: Tone = Tone.Neutre)

/** Élément du fil du jour, fourni par un module. */
@Serializable
data class Card(
    val id: String,
    val title: String,
    val kind: String = "info",
    val time: String? = null,
    val body: String? = null,
    val chips: List<Chip> = emptyList(),
    val detail: String? = null,
    val module: String = "",
) {
    /** Identifiant unique dans le fil : deux modules peuvent émettre le même `id`. */
    val key: String get() = "$module/$id"
}

@Serializable
data class Day(val day: String, val work: String? = null, val cards: List<Card> = emptyList())

@Serializable
data class DecisionOption(val id: String, val label: String, val reason: String? = null)

/** Question posée par un module ; disparaît d'elle-même après [expires]. */
@Serializable
data class Decision(
    val id: String,
    val title: String,
    val module: String = "",
    val headline: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val proposal: DecisionOption? = null,
    val alternatives: List<DecisionOption> = emptyList(),
    val expires: String? = null,
)

@Serializable
data class Status(val busy: Boolean = false, val decisions: Int = 0)

@Serializable
data class ChatEntry(val id: Long, val at: String, val role: String, val source: String = "", val text: String)

/** Dernier état connu ; [offline] = le serveur n'a pas répondu à la dernière actualisation. */
@Serializable
data class Snapshot(
    val days: List<Day> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val fetchedAt: String? = null,
    val offline: Boolean = false,
)

/** État local d'un élément du fil, propre à l'appareil. */
@Serializable
enum class Mark { Fait, PlusTard }
