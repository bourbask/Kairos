package app.kairos.mobile.ui.today

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.Mark
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoneTest {
    private val meal = KairosJson.decodeFromString(
        Card.serializer(),
        """{"id":"soir","title":"Curry","module":"alimentation",
           "done":{"tool":"app_meal_done","args":{"day":"2026-11-12","slot":"soir"},
                   "choices":{"label":"Portions mangées","key":"portions","options":[1,2,3],"default":1}}}""",
    )

    /** Journal des effets, dans l'ordre. */
    private class Fake(val undo: Boolean = false, val fail: ApiError? = null) {
        val log = mutableListOf<String>()
        val mark: suspend (Mark?) -> Unit = { log += "mark $it" }
        val window: suspend () -> Boolean = { log += "window"; undo }
        val call: suspend (String, String, JsonObject) -> String = { m, t, a ->
            log += "call $m/$t $a"
            fail?.let { throw it }
            "Repas noté : Curry"
        }
    }

    @Test
    fun done_card_calls_tool_after_undo_window() = runBlocking {
        val f = Fake()
        val out = completeCard(meal, JsonPrimitive(2), f.mark, f.window, f.call)
        assertEquals(DoneOutcome.Done("Repas noté : Curry"), out)
        assertEquals(
            listOf("mark Fait", "window", """call alimentation/app_meal_done {"day":"2026-11-12","slot":"soir","portions":2}"""),
            f.log,
        )
        // Annulé pendant la fenêtre : rien ne part, la carte revient.
        val u = Fake(undo = true)
        assertEquals(DoneOutcome.Undone, completeCard(meal, null, u.mark, u.window, u.call))
        assertEquals(listOf("mark Fait", "window", "mark null"), u.log)
    }

    @Test
    fun done_choice_added_to_args() {
        val done = meal.done!!
        assertTrue(done.asks)
        assertEquals("""{"day":"2026-11-12","slot":"soir","portions":3}""", doneArgs(done, JsonPrimitive(3)).toString())
        // Sans choix explicite : la valeur par défaut, sinon la première option.
        assertEquals(JsonPrimitive(1), doneArgs(done, null)["portions"])
        val noDefault = done.copy(choices = done.choices!!.copy(default = null, options = listOf(JsonPrimitive(4))))
        assertFalse(noDefault.asks)
        assertEquals(JsonPrimitive(4), doneArgs(noDefault, null)["portions"])
        // Sans choices : les arguments tels quels.
        val plain = CardDone("app_lot_use", KairosJson.decodeFromString(JsonObject.serializer(), """{"lot":3}"""))
        assertFalse(plain.asks)
        assertEquals("""{"lot":3}""", doneArgs(plain, null).toString())
        assertEquals(listOf("1", "2", "3"), done.choices!!.options.map(::optionLabel))
    }

    @Test
    fun card_without_done_stays_local() = runBlocking {
        val f = Fake()
        val agenda = Card(id = "rdv", title = "Dentiste", module = "agenda")
        assertEquals(DoneOutcome.Local, completeCard(agenda, null, f.mark, f.window, f.call))
        // Marque locale posée, aucun appel.
        assertEquals(listOf("mark Fait", "window"), f.log)
    }

    @Test
    fun refusal_restores_card_with_message() = runBlocking {
        val f = Fake(fail = ApiError.Server(422, "Curry : déjà noté le 2026-11-12 (soir)."))
        assertEquals(DoneOutcome.Refused("Curry : déjà noté le 2026-11-12 (soir)."), completeCard(meal, null, f.mark, f.window, f.call))
        assertEquals("mark null", f.log.last())
        val off = Fake(fail = ApiError.Offline)
        val out = completeCard(meal, null, off.mark, off.window, off.call) as DoneOutcome.Refused
        assertTrue(out.message.contains("rien n'a été enregistré"))
        assertEquals("mark null", off.log.last())
    }
}
