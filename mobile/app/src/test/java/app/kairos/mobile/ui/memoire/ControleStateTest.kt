package app.kairos.mobile.ui.memoire

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Controle
import app.kairos.mobile.data.Fact
import app.kairos.mobile.data.Habitude
import app.kairos.mobile.data.JournalEntry
import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.Souvenir
import app.kairos.mobile.data.ToolMessage
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.completeCard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControleStateTest {
    private val fait = Souvenir("fait", "cafe.prefere", "Exemple de fait")
    private val note = Souvenir("journal", "12", "Exemple d'entrée", sensitive = true, depuis = "2026-11-03", devenir = "oubli")
    private val habitude = Habitude(4, "Exemple d'habitude", etat = "nee", force = 0.6)

    @Test
    fun controle_parses_module_json() {
        val c = KairosJson.decodeFromString(
            Controle.serializer(),
            """{"ancres_max":20,
               "ancres":[{"nature":"fait","ref":"cafe.prefere","texte":"Exemple","sensitive":false,"depuis":null,"devenir":null,"protege":false},
                         {"nature":"synthese","ref":"2025","texte":"Exemple d'année","protege":true}],
               "estompes":[{"nature":"journal","ref":"12","texte":"Exemple","sensitive":true,"depuis":"2026-11-03T08:00:00","devenir":"condensation"}],
               "estompes_total":140,
               "habitudes":[{"id":4,"libelle":"Exemple","etat":"nee","force":0.25,"confirmee":true,"rejetee":false,"estompee":true,"ancienne_depuis":"2026-11-01"}]}""",
        )
        assertEquals(20, c.ancresMax)
        assertEquals(listOf("cafe.prefere", "2025"), c.ancres.map { it.ref })
        assertTrue(c.ancres[1].protege)
        assertNull(c.ancres[0].depuis)
        assertTrue(c.estompes.single().sensitive)
        assertEquals("condensation", c.estompes.single().devenir)
        assertEquals(140, c.estompesTotal)
        val h = c.habitudes.single()
        assertEquals(0.25, h.force, 0.0)
        assertTrue(h.confirmee && h.estompee && !h.rejetee)
        assertEquals("2026-11-01", h.ancienneDepuis)

        // Champs absents : valeurs par défaut.
        val empty = KairosJson.decodeFromString(Controle.serializer(), "{}")
        assertEquals(Controle(), empty)
        assertEquals(20, empty.ancresMax)
        val bare = KairosJson.decodeFromString(Souvenir.serializer(), """{"nature":"fait","ref":"x"}""")
        assertEquals("", bare.texte)
        assertFalse(bare.sensitive || bare.protege)
        // Module plus ancien, sans marque d'ancre.
        assertFalse(KairosJson.decodeFromString(Fact.serializer(), """{"key":"cafe.prefere","value":"Exemple"}""").anchored)
        assertFalse(KairosJson.decodeFromString(JournalEntry.serializer(), """{"id":1}""").anchored)
        assertTrue(KairosJson.decodeFromString(Fact.serializer(), """{"key":"cafe.prefere","anchored":true}""").anchored)
    }

    @Test
    fun controle_keeps_last_on_failure() {
        val c = Controle(ancres = listOf(fait))
        val ok = controleAfter(ControleView(), Result.success(c))
        assertEquals(ControleView(c, null), ok)
        val down = controleAfter(ok, Result.failure(ApiError.Offline))
        assertEquals(c, down.data)
        assertEquals("Hors ligne", down.issue)
        val old = controleAfter(down, Result.failure(ApiError.Server(404, "x", bare = true)))
        assertEquals(c, old.data)
        assertEquals(ApiError.Server.OUTDATED, old.issue)
        // Rien encore lu : la raison seule.
        assertEquals(ControleView(null, "Hors ligne"), controleAfter(ControleView(), Result.failure(ApiError.Offline)))
        // Une nouvelle lecture efface la raison.
        assertNull(controleAfter(old, Result.success(Controle())).issue)
    }

    @Test
    fun gestures_target_app_tools() {
        val a = anchorCard("fait", "cafe.prefere", true)
        assertEquals("ancre/fait/cafe.prefere", a.id)
        assertEquals(MEMOIRE, a.module)
        assertEquals("app_souvenir_anchor", a.done!!.tool)
        assertEquals(setOf("nature", "ref", "ancre"), a.done!!.args.keys)
        assertEquals("fait", a.done!!.args["nature"]!!.jsonPrimitive.content)
        assertEquals("cafe.prefere", a.done!!.args["ref"]!!.jsonPrimitive.content)
        assertTrue(a.done!!.args["ancre"]!!.jsonPrimitive.boolean)
        assertFalse(anchorCard("journal", "12", false).done!!.args["ancre"]!!.jsonPrimitive.boolean)

        val r = reviveCard(note)
        assertEquals("raviver/journal/12", r.id)
        assertEquals("app_souvenir_revive", r.done!!.tool)
        assertEquals(setOf("nature", "ref"), r.done!!.args.keys)
        assertEquals("12", r.done!!.args["ref"]!!.jsonPrimitive.content)

        val h = habitCard(habitude, confirm = false)
        assertEquals("habitude/4", h.id)
        assertEquals("app_habitude_set", h.done!!.tool)
        assertEquals(setOf("id", "confirmee"), h.done!!.args.keys)
        assertEquals(4L, h.done!!.args["id"]!!.jsonPrimitive.long)
        assertFalse(h.done!!.args["confirmee"]!!.jsonPrimitive.boolean)
        assertTrue(habitCard(habitude, confirm = true).done!!.args["confirmee"]!!.jsonPrimitive.boolean)

        val f = habitErasure(4)
        assertEquals("habitude/4", f.id)
        assertEquals("app_habitude_forget", f.done!!.tool)
        assertEquals(setOf("id"), f.done!!.args.keys)
        assertTrue(listOf(a, r, h, f).all { it.module == MEMOIRE })
    }

    @Test
    fun optimistic_updates() {
        val autre = Souvenir("conversation", "3", "Exemple de conversation")
        val c = Controle(ancres = listOf(fait), estompes = listOf(note, autre), estompesTotal = 140, habitudes = listOf(habitude, habitude.copy(id = 5)))

        val revived = withoutControl(c, reviveCard(note))
        assertEquals(listOf(autre), revived.estompes)
        assertEquals(139, revived.estompesTotal)

        // Ancre posée : quitte « s'estompe » ; la liste des ancres vient de la relecture.
        val anchored = withoutControl(c, anchorCard("conversation", "3", true))
        assertEquals(listOf(note), anchored.estompes)
        assertEquals(139, anchored.estompesTotal)
        assertEquals(c.ancres, anchored.ancres)

        val unanchored = withoutControl(c, anchorCard("fait", "cafe.prefere", false))
        assertTrue(unanchored.ancres.isEmpty())
        assertEquals(c.estompes, unanchored.estompes)

        val confirmed = withoutControl(c, habitCard(habitude, true)).habitudes.first()
        assertTrue(confirmed.confirmee && !confirmed.rejetee)
        val rejected = withoutControl(c, habitCard(habitude.copy(confirmee = true), false)).habitudes.first()
        assertTrue(rejected.rejetee && !rejected.confirmee)
        assertEquals(c.habitudes[1], withoutControl(c, habitCard(habitude, true)).habitudes[1])

        assertEquals(listOf(5L), withoutControl(c, habitErasure(4)).habitudes.map { it.id })
        // Élément déjà parti : rien ne bouge, le total non plus.
        assertEquals(140, withoutControl(c, reviveCard(Souvenir("fait", "absent"))).estompesTotal)
    }

    @Test
    fun labels() {
        assertEquals("Toujours retenu · 3/20", anchorsTitle(Controle(ancres = List(3) { fait })))
        assertEquals("Estompé depuis le 03/11/2026 · sera résumé", fadedHint(note.copy(devenir = "condensation")))
        assertEquals("Estompé depuis le 03/11/2026 · sera oublié", fadedHint(note))
        assertEquals("Estompé depuis le 03/11/2026", fadedHint(note.copy(depuis = "2026-11-03T22:00:00", devenir = null)))
        assertEquals("Estompé", fadedHint(note.copy(depuis = null)))
        assertEquals("Estompé", fadedHint(note.copy(depuis = "demain")))
        assertEquals(
            "ancienne depuis le 01/11/2026 · s'estompe · à confirmer",
            habitStatus(habitude.copy(ancienneDepuis = "2026-11-01", estompee = true)),
        )
        assertEquals("active · confirmée", habitStatus(habitude.copy(confirmee = true)))
        assertEquals("rejetée", habitStatus(habitude.copy(rejetee = true, estompee = true)))
        assertEquals("Fait", natureLabel("fait"))
        assertEquals("Résumé", natureLabel("synthese"))
        assertEquals("Déduction", natureLabel("deduction"))
        assertEquals("autre", natureLabel("autre"))
    }

    @Test
    fun sensitive_faded_hidden_by_default() {
        assertEquals("Exemple de fait", controlText(fait, emptySet(), masked = false))
        assertEquals("Exemple de fait", controlText(fait, emptySet(), masked = true))
        assertNull(controlText(note, emptySet(), masked = false))
        assertNull(controlText(note, setOf("journal/13", "fait/12"), masked = false))
        assertEquals("Exemple d'entrée", controlText(note, setOf("journal/12"), masked = false))
        assertNull(controlText(note, setOf("journal/12"), masked = true))
    }

    @Test
    fun refused_gesture_restores_item() = runBlocking {
        val hidden = mutableSetOf<String>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val card = reviveCard(note)
        fun mark(on: Boolean) { if (on) hidden += card.id else hidden -= card.id }

        // Annulé dans la fenêtre : rien ne part, l'élément revient.
        val undone = completeCard(card, null, mark = { mark(it != null) }, undoWindow = { assertTrue(card.id in hidden); true }, call = { _, t, a -> calls += t to a; ToolMessage() })
        assertEquals(DoneOutcome.Undone, undone)
        assertTrue(hidden.isEmpty() && calls.isEmpty())

        // Effacé entre-temps : refus du module, message tel quel, l'élément n'est plus caché.
        val gone = completeCard(card, null, mark = { mark(it != null) }, undoWindow = { false }, call = { m, t, a ->
            assertEquals(MEMOIRE, m)
            calls += t to a
            throw ApiError.Server(422, "souvenir inconnu ou déjà effacé")
        })
        assertEquals(DoneOutcome.Refused("souvenir inconnu ou déjà effacé"), gone)
        assertTrue(hidden.isEmpty())
        assertEquals("app_souvenir_revive", calls.single().first)

        val offline = completeCard(card, null, mark = { mark(it != null) }, undoWindow = { false }, call = { _, _, _ -> throw ApiError.Offline })
        assertEquals(DoneOutcome.Refused("Serveur injoignable : rien n'a été enregistré."), offline)
        assertTrue(hidden.isEmpty())
    }
}
