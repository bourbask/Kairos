package app.kairos.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    /** Action « fait » du module ; absente = simple marque locale. */
    val done: CardDone? = null,
) {
    /** Identifiant unique dans le fil : deux modules peuvent émettre le même `id`. */
    val key: String get() = "$module/$id"
}

/** Outil `app_*` du module à lancer quand la carte est faite. */
@Serializable
data class CardDone(val tool: String, val args: JsonObject = JsonObject(emptyMap()), val choices: DoneChoices? = null)

/** Valeur demandée avant l'action, ajoutée aux arguments sous [key]. */
@Serializable
data class DoneChoices(
    val label: String = "",
    val key: String,
    val options: List<JsonElement> = emptyList(),
    val default: JsonElement? = null,
)

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

/** Zone de la maison, telle que le module la compte. */
@Serializable
data class Zone(val id: String, val label: String, val count: Int = 0, val urgent: Int = 0)

/**
 * Lot en stock. [qty] / [unit] en unité de base (g, ml, pièce) pour le calcul ;
 * [label] = quantité lisible ; [step] = pas du +/−.
 */
@Serializable
data class Lot(
    val id: Long,
    val name: String,
    val qty: Double = 0.0,
    val unit: String = "",
    val label: String = "",
    val step: Double = 1.0,
    val location: String = "",
    val expiry: String? = null,
    @SerialName("expiry_kind") val expiryKind: String? = null,
    val estimated: Boolean = false,
    val tone: Tone = Tone.Neutre,
)

@Serializable
data class Inventory(val zones: List<Zone> = emptyList(), val lots: List<Lot> = emptyList())

@Serializable
data class OrderLine(
    val index: Int,
    val name: String,
    val qty: Double = 0.0,
    val unit: String = "",
    val label: String = "",
    val location: String = "",
    val fresh: Boolean = false,
    val done: Boolean = false,
)

/** Commande retirée, à ranger ligne par ligne. */
@Serializable
data class Order(val id: Long, val store: String = "", val pickup: String? = null, val lines: List<OrderLine> = emptyList())

/**
 * Produit retrouvé (code-barres ou nom) ; [known] = false s'il est inconnu. [location] = zone
 * habituelle, [unit] = unité du produit (g, ml, pièce), [qty] = quantité proposée dans cette unité.
 */
@Serializable
data class Product(
    val known: Boolean = true,
    val name: String = "",
    val nature: String? = null,
    val fresh: Boolean = false,
    val location: String = "",
    val unit: String = PIECE,
    val qty: Double = 1.0,
)

const val PIECE = "pièce"

/** Réponse d'un outil d'écriture. */
@Serializable
data class ToolMessage(val message: String = "")

/** Dernier état connu de la maison ; [offline] = la dernière actualisation a échoué. */
@Serializable
data class MaisonSnapshot(
    val inventory: Inventory = Inventory(),
    val orders: List<Order> = emptyList(),
    val fetchedAt: String? = null,
    val offline: Boolean = false,
    /** Raison de la lecture seule quand le serveur répond mais pas le module. */
    val reason: String? = null,
)
