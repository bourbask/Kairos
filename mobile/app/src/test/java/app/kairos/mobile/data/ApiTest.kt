package app.kairos.mobile.data

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Exemple du contrat partagé, tel que la tête le relaie (avec `module`). */
internal const val DAYS_JSON = """[{"day": "2026-11-08", "work": "repos", "cards": [
  {"id": "repas-2026-11-08-midi", "kind": "info", "time": "12:15",
   "title": "Midi · Soupe de carcasse", "body": "Frigo, réchauffer 5 min",
   "chips": [{"text": "champignons à finir", "tone": "attention"}],
   "detail": "texte long optionnel (recette…)", "module": "alimentation", "inconnu": 1},
  {"id": "agenda-1", "title": "Sans heure", "module": "agenda"}]},
 {"day": "2026-11-09", "work": "sur site", "cards": []}]"""

class ApiTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.close() }

    private fun api() = KairosApi(server.url("/").toString(), "secret-token", OkHttpClient())

    @Test
    fun api_sends_bearer() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"busy": true, "decisions": 3}""").build())
        val status = api().status()
        assertEquals(Status(busy = true, decisions = 3), status)
        val req = server.takeRequest()
        assertEquals("Bearer secret-token", req.headers["Authorization"])
        assertEquals("/app/v1/status", req.url.encodedPath)
    }

    @Test
    fun api_maps_401_to_unauthorized() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        try {
            api().days(LocalDate.of(2026, 11, 8), 2)
            fail("401 attendu")
        } catch (e: ApiError) {
            assertEquals(ApiError.Unauthorized, e)
        }
    }

    @Test
    fun api_parses_days_contract() = runBlocking {
        server.enqueue(MockResponse.Builder().body(DAYS_JSON).build())
        val days = api().days(LocalDate.of(2026, 11, 8), 2)
        val req = server.takeRequest()
        assertEquals("/app/v1/days", req.url.encodedPath)
        assertEquals("2026-11-08", req.url.queryParameter("from"))
        assertEquals("2", req.url.queryParameter("days"))
        assertEquals(2, days.size)
        val card = days[0].cards[0]
        assertEquals("12:15", card.time)
        assertEquals("alimentation", card.module)
        assertEquals(Chip("champignons à finir", Tone.Attention), card.chips.single())
        assertEquals("texte long optionnel (recette…)", card.detail)
        val bare = days[0].cards[1]
        assertNull(bare.time)
        assertNull(bare.body)
        assertEquals("sur site", days[1].work)
    }

    @Test
    fun api_maps_server_error_message() = runBlocking {
        server.enqueue(MockResponse.Builder().code(422).body("""{"message": "Libellé trop générique"}""").build())
        try {
            api().decide("finances", "op 1", "Loisirs")
            fail("422 attendu")
        } catch (e: ApiError.Server) {
            assertEquals(422, e.code)
            assertEquals("Libellé trop générique", e.message)
        }
        val req = server.takeRequest()
        assertEquals("/app/v1/decisions/finances/op%201", req.url.encodedPath)
        assertEquals("""{"choice":"Loisirs"}""", req.body?.utf8())
    }
}
