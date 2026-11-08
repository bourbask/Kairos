package app.kairos.mobile.ui.marches

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.Decision
import app.kairos.mobile.data.DecisionOption
import app.kairos.mobile.data.FollowedMarket
import app.kairos.mobile.data.MarchesSnapshot
import app.kairos.mobile.data.MarketProposal
import app.kairos.mobile.data.Markets
import app.kairos.mobile.ui.decide.Choice
import app.kairos.mobile.ui.finances.dayLabel
import app.kairos.mobile.ui.finances.issueOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Logique pure de l'espace Marchés : tri, libellés des probabilités et variations,
 * pile des propositions, état hors ligne. Sans Compose ni réseau, testée sur la JVM.
 */

const val MARCHES = "marches"
const val FOLLOW = "suivre"
const val IGNORE = "ignorer"

private const val NBSP = ' '
private const val MINUS = '−'

/** Variation qui sert au tri et à la carte : 24 h, sinon 7 jours. */
private fun recent(m: FollowedMarket): Double? = if (m.closed) null else m.d1 ?: m.d7

/** Plus forte variation récente d'abord ; sans historique ensuite, dénouées en fin. */
fun sortedByVariation(markets: List<FollowedMarket>): List<FollowedMarket> =
    markets.sortedWith(
        compareBy<FollowedMarket> { it.closed }
            .thenBy { recent(it) == null }
            .thenByDescending { recent(it)?.let(::abs) ?: 0.0 }
            .thenBy { it.question },
    )

/** Question encore ouverte qui a le plus bougé sur 24 h, ou null. */
fun strongestToday(markets: List<FollowedMarket>): FollowedMarket? =
    markets.filter { !it.closed && it.d1 != null }.maxByOrNull { abs(it.d1!!) }

/** « 63 % », borné à [0, 100] ; « — » sans relevé. */
fun percentText(p: Double?): String =
    if (p == null || !p.isFinite()) "—" else "${(p.coerceIn(0.0, 1.0) * 100).roundToInt()}$NBSP%"

private fun points(d: Double): Long = if (d.isFinite()) (d * 100).roundToLong() else 0

/** « +5 pts », « −12 pts », « 0 pt » : variation en points de pourcentage. */
fun deltaText(d: Double): String {
    val n = points(d)
    val unit = if (abs(n) > 1) "pts" else "pt"
    return when {
        n > 0 -> "+$n $unit"
        n < 0 -> "$MINUS${-n} $unit"
        else -> "0 $unit"
    }
}

enum class Trend { Up, Down, Flat }

/** Sens d'une variation, tel qu'affiché (au point près). */
fun trendOf(d: Double): Trend = points(d).let { if (it > 0) Trend.Up else if (it < 0) Trend.Down else Trend.Flat }

/** Variation avec sa flèche : le sens se lit sans la couleur. */
fun deltaLabel(d: Double): String = when (trendOf(d)) {
    Trend.Up -> "▲ "
    Trend.Down -> "▼ "
    Trend.Flat -> "= "
} + deltaText(d)

/** Variation lue par les lecteurs d'écran : « hausse de 5 points sur 24 heures ». */
fun spokenDelta(d: Double, period: String): String {
    val n = abs(points(d))
    val over = "sur " + period.replace(" h", " heures").replace(" j", " jours")
    val amount = "de $n point${if (n > 1) "s" else ""}"
    return when (trendOf(d)) {
        Trend.Up -> "hausse $amount $over"
        Trend.Down -> "baisse $amount $over"
        Trend.Flat -> "stable $over"
    }
}

/** Lien d'une question à un scénario, en mots. */
fun signWord(sign: Int): String = if (sign >= 0) "soutient" else "contredit"

/** Variations à afficher (période, valeur) ; aucune pour une question dénouée. */
fun deltasOf(m: FollowedMarket): List<Pair<String, Double>> =
    if (m.closed) emptyList() else listOfNotNull(m.d1?.let { "24 h" to it }, m.d7?.let { "7 j" to it })

const val CLOSED = "Échéance passée"
const val NO_READING = "en attente de relevé"

/**
 * Gros chiffre d'une question : sa probabilité, sinon pourquoi elle manque.
 * Échéance passée : en attente du verdict, plus de variation (tranchée, elle quitte la liste).
 */
fun headline(m: FollowedMarket): String = if (m.closed) CLOSED else percentText(m.prob)

/** Sous-titre d'état : question sans relevé, ou rien. */
fun readingNote(m: FollowedMarket): String? = NO_READING.takeIf { m.prob == null && !m.closed }

fun endText(end: String?): String? = dayLabel(end)?.let { "échéance $it" }

/** « 12 k », « 1,2 M » : ordre de grandeur d'un volume. */
fun volumeText(v: Double): String = when {
    !v.isFinite() -> "—"
    v >= 1e6 -> "${"%.1f".format(java.util.Locale.FRENCH, v / 1e6)}${NBSP}M"
    v >= 1e3 -> "${(v / 1e3).roundToInt()}${NBSP}k"
    else -> v.roundToInt().toString()
}

/** Ne plus suivre, sous la forme d'un geste « fait » du fil : même fenêtre d'annulation. */
fun unfollowCard(m: FollowedMarket): Card = Card(
    id = m.id,
    title = m.question,
    module = MARCHES,
    done = CardDone("app_market_unfollow", buildJsonObject { put("id", m.id) }),
)

/** Propositions en cartes de la pile Décider : → suivre, ← ignorer. */
fun proposalCards(proposals: List<MarketProposal>): List<Decision> = proposals.map { p ->
    Decision(
        id = p.id,
        title = "Proposition",
        module = "Marchés",
        headline = percentText(p.prob),
        subtitle = p.question,
        body = listOfNotNull(
            p.outcome.takeIf { it.isNotBlank() }?.let { "issue « $it »" },
            p.volume?.let { "volume ${volumeText(it)}" },
            endText(p.end),
        ).joinToString(" · ").ifBlank { null },
        proposal = DecisionOption(FOLLOW, "Suivre cette question"),
        alternatives = listOf(DecisionOption(IGNORE, "Ignorer")),
    )
}

/** Envoie un choix de la pile : suivre passe par le module, ignorer reste sur l'appareil. */
suspend fun decideProposal(choice: Choice, call: suspend (tool: String, args: JsonObject) -> String): String =
    if (choice.option == FOLLOW) call("app_market_follow", buildJsonObject { put("id", choice.card.id) }) else ""

/** Propositions à trier : ni ignorées, ni déjà suivies. */
fun pendingProposals(s: MarchesSnapshot): List<MarketProposal> {
    val followed = s.markets?.followed.orEmpty().map { it.id }.toSet()
    return s.proposals.filter { it.id !in s.ignored && it.id !in followed }
}

/**
 * Nouvel état après une lecture : ce qui a répondu remplace l'ancien, le reste est
 * gardé avec la raison. Les propositions ignorées qui ne sont plus proposées, et les
 * détails des questions qui ne sont plus suivies, sont oubliés.
 */
fun marchesAfter(prev: MarchesSnapshot, markets: Result<Markets>, proposals: Result<List<MarketProposal>>, now: String): MarchesSnapshot {
    val p = proposals.getOrNull()
    val failure = markets.exceptionOrNull() ?: proposals.exceptionOrNull()
    return prev.copy(
        markets = markets.getOrNull() ?: prev.markets,
        details = markets.getOrNull()?.let { m -> prev.details.filterKeys { id -> m.followed.any { it.id == id } } } ?: prev.details,
        proposals = p ?: prev.proposals,
        ignored = p?.let { list -> prev.ignored.intersect(list.map { it.id }.toSet()) } ?: prev.ignored,
        fetchedAt = if (failure == null) now else prev.fetchedAt,
        issue = failure?.let(::issueOf),
    )
}
