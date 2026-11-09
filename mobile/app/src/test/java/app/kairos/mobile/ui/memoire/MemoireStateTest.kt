package app.kairos.mobile.ui.memoire

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.ToolMessage
import app.kairos.mobile.data.ConversationSummary
import app.kairos.mobile.data.Fact
import app.kairos.mobile.data.Intention
import app.kairos.mobile.data.JournalEntry
import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.Memory
import app.kairos.mobile.data.MemoireSnapshot
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.completeCard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MemoireStateTest {
    private val now = LocalDateTime.parse("2026-11-08T10:00:00")

    @Test
    fun intentions_sorted_by_expiry_with_time_left() {
        val list = listOf(
            Intention(1, "semaine", expires = "2026-11-15T10:00:00", state = "proposee"),
            Intention(2, "ce soir", expires = "2026-11-08T15:00:00.5", state = "acceptee"),
            Intention(3, "échue", expires = "2026-11-08T09:00:00", state = "proposee"),
            Intention(4, "sans échéance", state = "proposee"),
            Intention(5, "bientôt", expires = "2026-11-08T10:20:00", state = "proposee"),
            Intention(6, "remise à plus tard", expires = "2026-11-09T10:00:00", state = "proposee"),
        )
        // L'échéance la plus proche d'abord ; échue : disparaît ; sans échéance en fin ; « plus tard » : cachée aujourd'hui.
        assertEquals(setOf(6L), laterIds(setOf(laterKey(6), "memoire/9", "agenda/intention/3")))
        val open = openIntentions(list, now, later = setOf(6L))
        assertEquals(listOf(5L, 2L, 1L, 4L), open.map { it.id })
        assertEquals("dans 20 min", timeLeft(open[0].expires, now))
        assertEquals("dans 5 h", timeLeft(open[1].expires, now))
        assertEquals("dans 7 j", timeLeft(open[2].expires, now))
        assertNull(timeLeft(null, now))
        assertEquals("échue", timeLeft("2026-11-08T09:00:00", now))
        // La carte de l'espace compte toutes les intentions ouvertes, « plus tard » comprises.
        assertEquals(5, openIntentions(list, now).size)
        assertEquals("bientôt", openIntentions(list, now).first().what)
    }

    @Test
    fun sensitive_hidden_by_default() {
        val plain = JournalEntry(1, "2026-11-08T08:00:00", "note", "Courses faites", sensitive = false)
        val secret = JournalEntry(2, "2026-11-08T09:00:00", "note", "Rendez-vous médical", sensitive = true)
        assertEquals("Courses faites", shownText(plain, revealed = emptySet(), masked = false))
        // Masquée par défaut, révélée par un tap, toujours masquée quand les montants le sont.
        assertNull(shownText(secret, revealed = emptySet(), masked = false))
        assertEquals("Rendez-vous médical", shownText(secret, revealed = setOf(2L), masked = false))
        assertNull(shownText(secret, revealed = setOf(2L), masked = true))
        assertEquals("Courses faites", shownText(plain, revealed = emptySet(), masked = true))
        // La marque arrive du module telle quelle.
        val parsed = KairosJson.decodeFromString(JournalEntry.serializer(), """{"id":7,"at":"x","kind":"note","text":"t","sensitive":true}""")
        assertNull(shownText(parsed, emptySet(), masked = false))
    }

    @Test
    fun refused_intention_removed_with_message() = runBlocking {
        val i = Intention(9, "rappeler", expires = "2026-11-09T10:00:00", state = "proposee")
        val hidden = mutableSetOf<Long>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        fun mark(m: Any?) { if (m == null) hidden -= i.id else hidden += i.id }

        // Annulé dans la fenêtre : rien ne part, la carte revient.
        val undone = completeCard(intentionCard(i, REFUSE), null, mark = { mark(it) }, undoWindow = { assertTrue(i.id in hidden); true }, call = { _, t, a -> calls += t to a; ToolMessage() })
        assertEquals(DoneOutcome.Undone, undone)
        assertTrue(hidden.isEmpty())
        assertTrue(calls.isEmpty())

        // Fenêtre écoulée : refus envoyé au module, la carte reste partie, message affiché.
        val done = completeCard(intentionCard(i, REFUSE), null, mark = { mark(it) }, undoWindow = { false }, call = { module, t, a ->
            assertEquals(MEMOIRE, module)
            calls += t to a
            ToolMessage("Intention refusée.", "done")
        })
        assertEquals(DoneOutcome.Done("Intention refusée.", "done"), done)
        assertNull(intentionNotice(done as DoneOutcome.Done))
        assertEquals("app_intention_set", calls.single().first)
        assertEquals(9L, calls.single().second["id"]!!.jsonPrimitive.long)
        assertEquals("refusee", calls.single().second["state"]!!.jsonPrimitive.content)
        assertEquals(setOf(9L), hidden)

        // Déjà échue ou traitée : le module répond un message, pas une erreur ; la carte disparaît aussi.
        hidden.clear()
        val stale = completeCard(intentionCard(i, ACCEPT), null, mark = { mark(it) }, undoWindow = { false }, call = { _, _, a ->
            assertEquals("acceptee", a["state"]!!.jsonPrimitive.content)
            ToolMessage(STALE, "stale")
        })
        assertEquals(DoneOutcome.Done(STALE, "stale"), stale)
        // Seul l'imprévu s'annonce, décidé sur l'issue et non sur le libellé.
        assertEquals(STALE, intentionNotice(stale as DoneOutcome.Done))
        assertEquals("Autre libellé.", intentionNotice(DoneOutcome.Done("Autre libellé.", "stale")))
        // Module plus ancien, sans issue : repli sur le libellé.
        assertEquals(STALE, intentionNotice(DoneOutcome.Done(STALE)))
        assertNull(intentionNotice(DoneOutcome.Done("Intention acceptée.")))
        assertEquals(setOf(9L), hidden)
        // Retirée de l'état gardé, sans attendre la relecture.
        val s = MemoireSnapshot(memory = Memory(intentions = listOf(i, i.copy(id = 10))))
        assertEquals(listOf(10L), withoutIntention(s, 9).memory!!.intentions.map { it.id })

        // Refus du serveur : la carte revient, avec la raison.
        val refused = completeCard(intentionCard(i, REFUSE), null, mark = { mark(it) }, undoWindow = { false }, call = { _, _, _ -> throw ApiError.Offline })
        assertTrue(refused is DoneOutcome.Refused)
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun erasures_call_app_tools_and_leave_state() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val erased = mutableSetOf<String>()
        for (c in listOf(journalErasure(7), factErasure("sante.poids"), conversationErasure(3))) {
            // Annulé dans la fenêtre : rien ne part, l'élément revient.
            val undone = completeCard(c, null, mark = { if (it == null) erased -= c.id else erased += c.id }, undoWindow = { true }, call = { _, t, a -> calls += t to a; ToolMessage() })
            assertEquals(DoneOutcome.Undone, undone)
            assertTrue(c.id !in erased && calls.none { it.first == c.done!!.tool })
            completeCard(c, null, mark = { if (it == null) erased -= c.id else erased += c.id }, undoWindow = { false }, call = { m, t, a ->
                assertEquals(MEMOIRE, m)
                calls += t to a
                ToolMessage("ok")
            })
        }
        assertEquals(listOf("app_journal_delete", "app_fact_forget", "app_conversation_delete"), calls.map { it.first })
        assertEquals(7L, calls[0].second["id"]!!.jsonPrimitive.long)
        assertEquals("sante.poids", calls[1].second["key"]!!.jsonPrimitive.content)
        assertEquals(3L, calls[2].second["id"]!!.jsonPrimitive.long)
        assertEquals(3, erased.size)

        val s = MemoireSnapshot(
            memory = Memory(
                journal = listOf(JournalEntry(7), JournalEntry(8)),
                facts = listOf(Fact("sante.poids", "80"), Fact("sante.taille", "180")),
                conversations = listOf(ConversationSummary(3), ConversationSummary(4)),
            ),
        )
        val m = listOf(journalErasure(7), factErasure("sante.poids"), conversationErasure(3)).fold(s, ::withoutErased).memory!!
        assertEquals(listOf(8L), m.journal.map { it.id })
        assertEquals(listOf("sante.taille"), m.facts.map { it.key })
        assertEquals(listOf(4L), m.conversations.map { it.id })
    }

    @Test
    fun facts_grouped_by_prefix() {
        val groups = factGroups(
            listOf(
                Fact("sante.allergie", "pollen"),
                Fact("alimentation.regime", "végétarien"),
                Fact("sante.groupe_sanguin", "O+"),
                Fact("prenom", "Alex"),
            ),
        )
        assertEquals(listOf("Alimentation", "Santé", "Général"), groups.map { it.theme })
        assertEquals(listOf("sante.allergie", "sante.groupe_sanguin"), groups[1].facts.map { it.key })
        assertEquals("groupe sanguin", factLabel("sante.groupe_sanguin"))
        assertEquals("prenom", factLabel("prenom"))
        assertEquals("Travail", themeLabel("travail"))
    }

    @Test
    fun fact_edit_validation() {
        assertEquals("La valeur ne peut pas être vide.", factEditError("", "pollen"))
        assertEquals("La valeur ne peut pas être vide.", factEditError("   ", "pollen"))
        assertEquals("Valeur inchangée.", factEditError(" pollen ", "pollen"))
        assertNull(factEditError("acariens", "pollen"))
        val args = factSetArgs("sante.allergie", "  acariens ")
        assertEquals("sante.allergie", args["key"]!!.jsonPrimitive.content)
        assertEquals("acariens", args["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun offline_cache_excludes_sensitive() {
        val memory = KairosJson.decodeFromString(
            Memory.serializer(),
            """{"intentions":[{"id":1,"what":"appeler","expires":"2026-11-13T10:00:00","state":"proposee","constraints":"avant midi"}],
               "journal":[{"id":3,"at":"2026-11-08T08:00:00","kind":"note","text":"public","sensitive":false},
                          {"id":4,"at":"2026-11-08T09:00:00","kind":"note","text":"secret","sensitive":true}],
               "facts":[{"key":"sante.allergie","value":"pollen","updated":"2026-11-01T08:00:00"}],
               "conversations":[{"id":1,"at":"2026-11-07T20:00:00","summary":"Bilan","topics":["a"],"decisions":["d"]},{"id":2,"at":"2026-11-06T20:00:00","topics":[]}]}""",
        )
        assertEquals("avant midi", memory.intentions.single().constraints)
        assertNull(memory.conversations[1].summary)

        val fresh = memoireAfter(MemoireSnapshot(), Result.success(memory), "2026-11-08T10:00")
        // En mémoire vive : tout, la sensible restant masquée à l'affichage.
        assertEquals(2, fresh.memory!!.journal.size)
        assertNull(fresh.issue)
        // Sur le disque : jamais l'entrée sensible.
        val cached = cacheable(fresh)
        assertEquals(listOf(3L), cached.memory!!.journal.map { it.id })
        assertTrue("secret" !in KairosJson.encodeToString(MemoireSnapshot.serializer(), cached))
        assertEquals(memory.facts, cached.memory.facts)

        // Hors ligne : dernier état gardé, raison signalée.
        val down = memoireAfter(cached, Result.failure(ApiError.Offline), "2026-11-08T11:00")
        assertEquals(cached.memory, down.memory)
        assertEquals("Hors ligne", down.issue)
        assertEquals("2026-11-08T10:00", down.fetchedAt)
    }
}
