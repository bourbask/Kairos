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
    /** Plat d'une carte repas. */
    val dish: String? = null,
    /** Action « fait » du module ; absente = simple marque locale. */
    val done: CardDone? = null,
    /** État connu du module (« fait ») : prime sur l'absence de marque locale. */
    val state: String? = null,
) {
    /** Identifiant unique dans le fil : deux modules peuvent émettre le même `id`. */
    val key: String get() = "$module/$id"

    /** Marque affichée : la marque locale, sinon « fait » si le module le sait déjà. */
    fun markIn(marks: Map<String, Mark>): Mark? = marks[key] ?: Mark.Fait.takeIf { state == "fait" }
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

/**
 * Dernier état connu des finances, source par source (cycle, patrimoine).
 * `*At` = dernière réponse complète de la source ; `*Issue` = pourquoi elle
 * n'est pas à jour (hors ligne, module indisponible, réponse partielle), null si elle l'est.
 */
@Serializable
data class FinancesSnapshot(
    val cycle: FinanceCycle? = null,
    val wealth: FinanceWealth? = null,
    val cycleAt: String? = null,
    val wealthAt: String? = null,
    val cycleIssue: String? = null,
    val wealthIssue: String? = null,
) {
    val fetchedAt: String? get() = listOfNotNull(cycleAt, wealthAt).maxOrNull()
    val offline: Boolean get() = cycleIssue != null || wealthIssue != null
}

/* Espace Agenda : outils `app_agenda_day`, `app_agenda_week`, `app_agenda_sync`. Heures en HH:MM locales. */

/** Événement vu depuis un jour : [start] / [end] absents quand ils tombent un autre jour. */
@Serializable
data class AgendaEvent(
    val uid: String,
    val title: String,
    val start: String? = null,
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String? = null,
    /** « perso » ou « travail ». */
    val calendar: String = "perso",
)

@Serializable
data class FreeSlot(val start: String, val end: String, val minutes: Int = 0)

@Serializable
data class AgendaTask(val id: Long, val title: String, val due: String? = null)

@Serializable
data class AgendaDay(
    val day: String,
    val events: List<AgendaEvent> = emptyList(),
    val free: List<FreeSlot> = emptyList(),
    val tasks: List<AgendaTask> = emptyList(),
)

@Serializable
data class WeekDay(val day: String, val events: List<AgendaEvent> = emptyList(), @SerialName("busy_minutes") val busyMinutes: Int = 0)

@Serializable
data class Upcoming(val uid: String, val title: String, val day: String, val start: String? = null, val calendar: String = "perso")

@Serializable
data class AgendaWeek(val days: List<WeekDay> = emptyList(), val upcoming: List<Upcoming> = emptyList())

/** Issue de la dernière synchronisation des calendriers ; [at] = dernière réussie. */
@Serializable
data class AgendaSync(val synced: Boolean = false, val at: String? = null)

/**
 * Dernier état connu de l'agenda : jours lus (par date), semaine, synchronisation.
 * [fetchedAt] = dernière lecture réussie ; [issue] = pourquoi la dernière a échoué.
 */
@Serializable
data class AgendaSnapshot(
    val days: Map<String, AgendaDay> = emptyMap(),
    val week: AgendaWeek? = null,
    val sync: AgendaSync? = null,
    val fetchedAt: String? = null,
    val issue: String? = null,
)

/*
 * Espace Marchés : outils `app_markets`, `app_market`, `app_market_proposals`,
 * `app_market_follow`, `app_market_unfollow`. Probabilités et variations entre 0 et 1.
 */

/**
 * Question suivie : [prob] = probabilité de l'issue suivie [outcome], absente avant le
 * premier relevé ; [d1] / [d7] = variation sur 24 h / 7 jours, absentes sans historique ;
 * [spark] = une valeur par jour, 30 au plus ; [closed] = échéance passée, pas encore
 * tranchée (une question tranchée quitte la liste).
 */
@Serializable
data class FollowedMarket(
    val id: String,
    val question: String = "",
    val outcome: String = "",
    val prob: Double? = null,
    val d1: Double? = null,
    val d7: Double? = null,
    val spark: List<Double> = emptyList(),
    val end: String? = null,
    val closed: Boolean = false,
)

/** Question liée à un scénario ; [sign] > 0 = elle le soutient, < 0 = elle le contredit. */
@Serializable
data class ScenarioLink(val id: String, val sign: Int = 1)

@Serializable
data class Scenario(val name: String, val links: List<ScenarioLink> = emptyList())

@Serializable
data class MarketAlert(val id: String = "", val text: String = "", val at: String? = null)

@Serializable
data class Markets(
    val followed: List<FollowedMarket> = emptyList(),
    val scenarios: List<Scenario> = emptyList(),
    val alerts: List<MarketAlert> = emptyList(),
)

@Serializable
data class MarketOutcome(val name: String, val prob: Double? = null)

@Serializable
data class ProbPoint(val at: String, val prob: Double = 0.0)

/** Volume échangé ce jour-là (écart d'un jour sur l'autre). */
@Serializable
data class VolumePoint(val day: String, val value: Double = 0.0)

/** Ce que change une hausse, une baisse. */
@Serializable
data class Projection(val up: String? = null, val down: String? = null)

@Serializable
data class MarketDetail(
    val id: String,
    val question: String = "",
    val outcomes: List<MarketOutcome> = emptyList(),
    val history: List<ProbPoint> = emptyList(),
    val volume: List<VolumePoint> = emptyList(),
    val projection: Projection? = null,
    val scenarios: List<Scenario> = emptyList(),
)

@Serializable
data class MarketProposal(
    val id: String,
    val question: String = "",
    val outcome: String = "",
    val prob: Double? = null,
    val volume: Double? = null,
    val end: String? = null,
)

/**
 * Dernier état connu des marchés ; [details] = détails déjà ouverts, relisibles hors ligne ;
 * [ignored] = propositions écartées sur l'appareil (le module n'a pas d'outil pour ça).
 * [fetchedAt] = dernière lecture complète ; [issue] = pourquoi la dernière ne l'a pas été.
 */
@Serializable
data class MarchesSnapshot(
    val markets: Markets? = null,
    val proposals: List<MarketProposal> = emptyList(),
    val details: Map<String, MarketDetail> = emptyMap(),
    val ignored: Set<String> = emptySet(),
    val fetchedAt: String? = null,
    val issue: String? = null,
)

/** Intention à traiter : proposée ou acceptée, avec son échéance. */
@Serializable
data class Intention(val id: Long, val what: String = "", val expires: String? = null, val state: String = "", val constraints: String? = null)

/** Entrée du journal ; [sensitive] : masquée par défaut, jamais gardée hors ligne. */
@Serializable
data class JournalEntry(val id: Long, val at: String = "", val kind: String = "", val text: String = "", val sensitive: Boolean = false)

/** Fait connu sur l'utilisateur ; le préfixe de [key] en donne le thème. */
@Serializable
data class Fact(val key: String, val value: String = "", val updated: String? = null)

@Serializable
data class FactValue(val value: String = "", val at: String = "")

@Serializable
data class ConversationSummary(
    val id: Long,
    val at: String = "",
    val summary: String? = null,
    val topics: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
)

/** Vue de la mémoire pour l'application (`app_memory`). */
@Serializable
data class Memory(
    val intentions: List<Intention> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val facts: List<Fact> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
)

/** Dernier état de l'espace Mémoire, gardé hors ligne sans les entrées sensibles. */
@Serializable
data class MemoireSnapshot(
    val memory: Memory? = null,
    val fetchedAt: String? = null,
    val issue: String? = null,
)

/** Reprise en cours dans le lecteur ; [key] opaque, rendue telle quelle au module. */
@Serializable
data class ResumeItem(val key: String, val title: String = "", @SerialName("left_min") val leftMin: Int? = null)

/** Élément prêt à regarder ; [subs] : `vf`, `st_fr`, `absents` ou `recherche`. */
@Serializable
data class ReadyItem(val key: String, val title: String = "", val kind: String = "", val subs: String = "", val minutes: Int? = null)

/** Téléchargement ; [progress] entre 0 et 1, [state] : `en_cours`, `bloque`, `attente`, `pause`, `erreur`. */
@Serializable
data class DownloadItem(
    val title: String = "",
    val progress: Double = 0.0,
    @SerialName("eta_min") val etaMin: Int? = null,
    val state: String = "",
    val why: String? = null,
)

@Serializable
data class Downloads(val items: List<DownloadItem> = emptyList(), val paused: Boolean = false)

/** Sortie suivie ; [day] AAAA-MM-JJ et [time] HH:MM à l'heure locale du module. */
@Serializable
data class Release(val day: String, val time: String? = null, val title: String = "", val what: String = "", val have: Boolean = false)

@Serializable
data class MediaRequest(val title: String = "", val state: String = "")

/** Section tombée et sa raison, sans nom de service. */
@Serializable
data class SectionError(val section: String = "", val message: String = "")

/** Vue de l'espace Médias (`app_medias`) ; une section null est tombée, sa raison est dans [errors]. */
@Serializable
data class Medias(
    @SerialName("continue") val resume: List<ResumeItem>? = null,
    val ready: List<ReadyItem>? = null,
    val downloads: Downloads? = null,
    val upcoming: List<Release>? = null,
    val requests: List<MediaRequest>? = null,
    val errors: List<SectionError> = emptyList(),
)

/** Résultat de recherche (`app_media_search`) ; [mediaType] : `tv` ou `movie`. */
@Serializable
data class MediaResult(
    val id: Long,
    @SerialName("media_type") val mediaType: String = "",
    val title: String = "",
    val year: Int? = null,
    val have: Boolean = false,
    val requested: Boolean = false,
)

/** Dernier état de l'espace Médias, gardé hors ligne. */
@Serializable
data class MediasSnapshot(
    val medias: Medias? = null,
    val fetchedAt: String? = null,
    val issue: String? = null,
)

/** Échéance d'un document ; [date] AAAA-MM-JJ, [org] absent pour une nature privée. */
@Serializable
data class DocDeadline(
    val date: String,
    val kind: String = "",
    val what: String = "",
    val org: String? = null,
    val private: Boolean = false,
    val overdue: Boolean = false,
)

/** Pièce attendue de la liste de contrôle ; [present] vrai aussi pour « pas encore requise ». */
@Serializable
data class DocPiece(val domain: String = "", val item: String = "", val present: Boolean = false)

/** Nature (dossier) : nombre de documents et ceux sans texte lisible. */
@Serializable
data class DocNature(val name: String = "", val count: Int = 0, val unreadable: Int = 0, val private: Boolean = false)

@Serializable
data class DocExtraction(val done: Int = 0, val total: Int = 0)

/** Vue de l'espace Documents (`app_documents`), lecture seule. */
@Serializable
data class Documents(
    val deadlines: List<DocDeadline> = emptyList(),
    val missing: List<DocPiece> = emptyList(),
    val natures: List<DocNature> = emptyList(),
    val extraction: DocExtraction = DocExtraction(),
)

/** Résultat de recherche (`app_documents_search`) ; [passage] : mots trouvés entre « ». */
@Serializable
data class DocHit(val name: String = "", val nature: String = "", val date: String? = null, val passage: String = "")

/** Dernier état de l'espace Documents, gardé hors ligne. */
@Serializable
data class DocumentsSnapshot(
    val documents: Documents? = null,
    val fetchedAt: String? = null,
    val issue: String? = null,
)
