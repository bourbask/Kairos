package app.kairos.mobile.ui.marches

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.ToolMessage
import app.kairos.mobile.data.FollowedMarket
import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.MarchesSnapshot
import app.kairos.mobile.data.MarketProposal
import app.kairos.mobile.data.Markets
import app.kairos.mobile.ui.decide.DeckState
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.completeCard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NBSP = ' '

class MarchesStateTest {
    private fun m(id: String, d1: Double? = null, d7: Double? = null, closed: Boolean = false) =
        FollowedMarket(id = id, question = "Q $id", outcome = "Oui", prob = 0.5, d1 = d1, d7 = d7, closed = closed)

    @Test
    fun sorted_by_abs_variation() {
        val sorted = sortedByVariation(
            listOf(
                m("calme", d1 = 0.01),
                m("chute", d1 = -0.12),
                m("neuf"),
                m("hausse", d1 = 0.05),
                m("semaine", d7 = 0.30),
                m("dénoué", d1 = 0.50, closed = true),
            ),
        )
        // Variation sur 24 h d'abord, sinon 7 jours ; sans historique puis dénouées en fin.
        assertEquals(listOf("semaine", "chute", "hausse", "calme", "neuf", "dénoué"), sorted.map { it.id })
        assertEquals("chute", strongestToday(sorted)?.id)
        assertNull(strongestToday(listOf(m("neuf"), m("semaine", d7 = 0.3))))
    }

    @Test
    fun percent_and_delta_format() {
        assertEquals("63${NBSP}%", percentText(0.63))
        assertEquals("0${NBSP}%", percentText(-0.2))
        assertEquals("100${NBSP}%", percentText(1.4))
        assertEquals("—", percentText(Double.NaN))
        assertEquals("+5 pts", deltaText(0.05))
        assertEquals("−12 pts", deltaText(-0.12))
        assertEquals("+1 pt", deltaText(0.01))
        assertEquals("0 pt", deltaText(0.001))
        assertEquals(Trend.Up, trendOf(0.05))
        assertEquals(Trend.Down, trendOf(-0.12))
        assertEquals(Trend.Flat, trendOf(0.004))
        // Jamais la couleur seule : flèche et texte.
        assertEquals("▲ +5 pts", deltaLabel(0.05))
        assertEquals("▼ −12 pts", deltaLabel(-0.12))
        assertEquals("= 0 pt", deltaLabel(0.0))
        val open = m("a", d1 = 0.05, d7 = -0.12)
        assertEquals(listOf("24 h" to 0.05, "7 j" to -0.12), deltasOf(open))
        assertEquals(emptyList<Pair<String, Double>>(), deltasOf(m("neuf")))
    }

    @Test
    fun closed_shows_outcome() {
        // Échéance passée, verdict pas encore tombé : plus de variation.
        val closed = m("x", d1 = 0.2, d7 = 0.3, closed = true).copy(outcome = "Non")
        assertEquals("Échéance passée", headline(closed))
        assertEquals(emptyList<Pair<String, Double>>(), deltasOf(closed))
        assertNull(readingNote(closed))
        assertEquals("50${NBSP}%", headline(m("y")))
        assertNull(readingNote(m("y")))
        // Pas encore de relevé : ni pourcentage ni variation.
        val fresh = KairosJson.decodeFromString(FollowedMarket.serializer(), """{"id":"n","question":"Q","outcome":"Oui","prob":null,"spark":[],"closed":false}""")
        assertNull(fresh.prob)
        assertEquals("—", headline(fresh))
        assertEquals("en attente de relevé", readingNote(fresh))
        assertEquals(emptyList<Pair<String, Double>>(), deltasOf(fresh))
    }

    @Test
    fun unfollow_undo_window() = runBlocking {
        val market = m("abc")
        val hidden = mutableSetOf<String>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val card = unfollowCard(market)

        // Annulé dans la fenêtre : rien ne part, la question revient.
        val undone = completeCard(
            card, null,
            mark = { if (it == null) hidden -= market.id else hidden += market.id },
            undoWindow = { assertTrue(market.id in hidden); true },
            call = { _, tool, args -> calls += tool to args; ToolMessage() },
        )
        assertEquals(DoneOutcome.Undone, undone)
        assertTrue(hidden.isEmpty())
        assertTrue(calls.isEmpty())

        // Fenêtre écoulée : désabonnement par identifiant.
        val done = completeCard(
            card, null,
            mark = { if (it == null) hidden -= market.id else hidden += market.id },
            undoWindow = { false },
            call = { module, tool, args -> calls += tool to args; assertEquals(MARCHES, module); ToolMessage("Ne suit plus.") },
        )
        assertEquals(DoneOutcome.Done("Ne suit plus."), done)
        assertEquals("app_market_unfollow", calls.single().first)
        assertEquals("abc", calls.single().second["id"]!!.jsonPrimitive.content)
        assertEquals(setOf("abc"), hidden)
    }

    @Test
    fun proposals_deck_follow_and_ignore() = runBlocking {
        val proposals = listOf(
            MarketProposal("p1", "Question 1", "Oui", 0.42, volume = 12_000.0, end = "2026-12-31"),
            MarketProposal("p2", "Question 2", "Non", 0.8),
            MarketProposal("p3", "Question 3", "Oui", 0.1),
        )
        val deck = DeckState(proposalCards(proposals))
        assertEquals("42${NBSP}%", deck.current!!.headline)

        deck.swipeRight()
        assertEquals(FOLLOW, deck.outbox.single().option)
        deck.choose(IGNORE)
        assertEquals(listOf(FOLLOW, IGNORE), deck.outbox.map { it.option })
        // Annulation : la dernière carte revient.
        assertTrue(deck.undo())
        assertEquals("p2", deck.current!!.id)

        val calls = mutableListOf<Pair<String, String>>()
        val follow = deck.outbox.single()
        assertEquals("Suivie.", decideProposal(follow) { tool, args -> calls += tool to args["id"]!!.jsonPrimitive.content; "Suivie." })
        deck.choose(IGNORE)
        // Ignorer reste sur l'appareil : rien ne part au module.
        assertEquals("", decideProposal(deck.outbox.last()) { _, _ -> error("aucun appel pour ignorer") })
        assertEquals(listOf("app_market_follow" to "p1"), calls)

        // Propositions ignorées ou déjà suivies : hors de la pile.
        val s = MarchesSnapshot(markets = Markets(followed = listOf(m("p3"))), proposals = proposals, ignored = setOf("p2"))
        assertEquals(listOf("p1"), pendingProposals(s).map { it.id })
    }

    @Test
    fun offline_keeps_last_state() {
        val markets = KairosJson.decodeFromString(
            Markets.serializer(),
            """{"followed":[{"id":"a","question":"Q","outcome":"Oui","prob":0.63,"d1":0.05,"spark":[0.5,0.58,0.63],"end":"2026-12-31","closed":false},
                {"id":"b","question":"Neuve","outcome":"Oui","prob":0.2,"spark":[],"closed":false}],
               "scenarios":[{"name":"S","links":[{"id":"a","sign":1},{"id":"b","sign":-1}]}],
               "alerts":[{"id":"x","text":"Bascule","at":"2026-11-08T07:00:00"}]}""",
        )
        assertNull(markets.followed[1].d1)
        assertEquals(-1, markets.scenarios.single().links[1].sign)
        val proposals = KairosJson.decodeFromString(ListSerializer(MarketProposal.serializer()), """[{"id":"p","question":"P","outcome":"Oui","prob":0.4}]""")

        val fresh = marchesAfter(MarchesSnapshot(ignored = setOf("p", "ancienne")), Result.success(markets), Result.success(proposals), "2026-11-08T08:00")
        assertEquals(markets, fresh.markets)
        assertNull(fresh.issue)
        assertEquals("2026-11-08T08:00", fresh.fetchedAt)
        // Les ignorées qui ne sont plus proposées sont oubliées.
        assertEquals(setOf("p"), fresh.ignored)

        val down = marchesAfter(fresh, Result.failure(ApiError.Offline), Result.failure(ApiError.Offline), "2026-11-08T09:00")
        assertEquals(markets, down.markets)
        assertEquals(proposals, down.proposals)
        assertEquals("Hors ligne", down.issue)
        assertEquals("2026-11-08T08:00", down.fetchedAt)
        assertEquals(setOf("p"), down.ignored)

        // Propositions seules indisponibles : les questions suivies sont à jour, le reste gardé et signalé.
        val half = marchesAfter(down, Result.success(markets), Result.failure(ApiError.Server(503, "x")), "2026-11-08T10:00")
        assertEquals(proposals, half.proposals)
        assertEquals("Module indisponible", half.issue)
        assertEquals("2026-11-08T08:00", half.fetchedAt)
        assertFalse(half.markets == null)
    }

    @Test
    fun times_in_local_zone() {
        val paris = java.time.ZoneId.of("Europe/Paris")
        // Horodatage RFC 3339 en UTC : affiché à l'heure locale.
        assertEquals("08/11 à 08:00", app.kairos.mobile.ui.finances.syncLabel("2026-11-08T07:00:00+00:00", paris))
        assertEquals("08/11 à 08:00", app.kairos.mobile.ui.finances.syncLabel("2026-11-08T07:00:00Z", paris))
        assertEquals("08/11 à 10:00", app.kairos.mobile.ui.finances.syncLabel("2026-11-08T09:00:00Z", paris))
        // Heure locale sans décalage : inchangée.
        assertEquals("08/11 à 07:00", app.kairos.mobile.ui.finances.syncLabel("2026-11-08T07:00:00", paris))
        // Échéance à 23 h 30 UTC le 31/12 : déjà le 01/01 à Paris.
        assertEquals("01/01", app.kairos.mobile.ui.finances.dayLabel("2026-12-31T23:30:00+00:00", paris))
        assertEquals("31/12", app.kairos.mobile.ui.finances.dayLabel("2026-12-31", paris))
    }

    @Test
    fun details_pruned_to_followed() {
        val detail = app.kairos.mobile.data.MarketDetail("gone")
        val prev = MarchesSnapshot(details = mapOf("a" to detail.copy(id = "a"), "gone" to detail))
        val fresh = marchesAfter(prev, Result.success(Markets(followed = listOf(m("a")))), Result.success(emptyList()), "t")
        assertEquals(setOf("a"), fresh.details.keys)
        // Sans réponse : rien n'est oublié.
        val down = marchesAfter(prev, Result.failure(ApiError.Offline), Result.failure(ApiError.Offline), "t")
        assertEquals(setOf("a", "gone"), down.details.keys)
    }

    @Test
    fun unreadable_reply_is_not_unavailable() {
        val down = marchesAfter(MarchesSnapshot(), Result.failure(ApiError.Server(200, "Réponse illisible")), Result.success(emptyList()), "t")
        assertEquals("Réponse illisible du module", down.issue)
        assertEquals("Module indisponible", marchesAfter(MarchesSnapshot(), Result.failure(ApiError.Server(503, "x")), Result.success(emptyList()), "t").issue)
    }

    @Test
    fun spoken_labels() {
        assertEquals("hausse de 5 points sur 24 heures", spokenDelta(0.05, "24 h"))
        assertEquals("baisse de 1 point sur 7 jours", spokenDelta(-0.01, "7 j"))
        assertEquals("stable sur 24 heures", spokenDelta(0.0, "24 h"))
        assertEquals("soutient", signWord(1))
        assertEquals("contredit", signWord(-1))
    }
}
