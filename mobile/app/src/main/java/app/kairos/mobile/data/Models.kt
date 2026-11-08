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

/** Dernier APK déposé sur la tête (`GET /app/v1/app`). */
@Serializable
data class AppRelease(val version: String, val size: Long)

/* Espace Finances : outils `app_finance_cycle`, `app_finance_wealth`, `app_afford`. Montants en euros. */

/** Fraîcheur des données bancaires. */
@Serializable
data class Freshness(
    val stale: Boolean = false,
    @SerialName("consent_expired") val consentExpired: Boolean = false,
    @SerialName("last_sync") val lastSync: String? = null,
)

/** Enveloppe d'un poste : prévu face à dépensé sur le cycle. */
@Serializable
data class Envelope(val poste: String, val planned: Double = 0.0, val spent: Double = 0.0)

@Serializable
data class Charge(val name: String, val amount: Double = 0.0, val date: String? = null)

@Serializable
data class Cushion(val balance: Double = 0.0, val floor: Double = 0.0)

@Serializable
data class FinanceAlert(val id: String = "", val title: String = "", val details: String = "", val remedy: String? = null)

/** Cycle budgétaire ; un champ absent = section indisponible. */
@Serializable
data class FinanceCycle(
    val start: String? = null,
    val end: String? = null,
    val today: String? = null,
    @SerialName("days_left") val daysLeft: Int? = null,
    val available: Double? = null,
    val balance: Double? = null,
    val envelopes: List<Envelope> = emptyList(),
    val charges: List<Charge> = emptyList(),
    val cushion: Cushion? = null,
    val alerts: List<FinanceAlert>? = null,
    val postes: List<String> = emptyList(),
    val freshness: Freshness? = null,
)

@Serializable
data class NetWorthPoint(val month: String, @SerialName("net_worth") val netWorth: Double = 0.0)

@Serializable
data class Loan(
    val name: String,
    val balance: Double = 0.0,
    val payment: Double? = null,
    @SerialName("payoff_month") val payoffMonth: String? = null,
)

/** Catégorie de risque du portefeuille ; [weight] cible, absent si inconnu. */
@Serializable
data class Bucket(val name: String, val weight: Double? = null, val amount: Double = 0.0)

@Serializable
data class PortfolioLine(val name: String, val amount: Double? = null, val bucket: String? = null)

@Serializable
data class Portfolio(val total: Double? = null, val buckets: List<Bucket> = emptyList(), val lines: List<PortfolioLine> = emptyList())

/** Patrimoine ; chaque section est absente si sa source n'a pas répondu. */
@Serializable
data class FinanceWealth(
    @SerialName("net_worth") val netWorth: Double? = null,
    val debt: Double? = null,
    val history: List<NetWorthPoint>? = null,
    val loans: List<Loan>? = null,
    val portfolio: Portfolio? = null,
    val freshness: Freshness? = null,
)

@Serializable
data class Cut(val poste: String? = null, val amount: Double = 0.0)

/** Verdict « puis-je me permettre » : [after] = reste à vivre après la dépense. */
@Serializable
data class Affordability(val ok: Boolean = false, val after: Double = 0.0, val reason: String? = null, val cuts: List<Cut> = emptyList())

/** Dernier état connu des finances ; [offline] = la dernière actualisation n'a pas tout rapporté. */
@Serializable
data class FinancesSnapshot(
    val cycle: FinanceCycle? = null,
    val wealth: FinanceWealth? = null,
    val fetchedAt: String? = null,
    val offline: Boolean = false,
    val reason: String? = null,
)
