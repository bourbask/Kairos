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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.concurrent.TimeUnit

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

    @Test
    fun api_tool_passthrough() = runBlocking {
        server.enqueue(MockResponse.Builder().body(
            """{"zones":[{"id":"frigo","label":"Frigo","count":1,"urgent":1}],
               "lots":[{"id":4,"name":"Lait","qty":1000,"unit":"ml","label":"1 l","step":100,"location":"frigo",
                        "expiry":"2026-11-09","expiry_kind":"dlc","estimated":false,"tone":"urgent"}]}""",
        ).build())
        val inv = api().tool("alimentation", "app_inventory", Inventory.serializer())
        assertEquals(Lot(4, "Lait", 1000.0, "ml", "1 l", 100.0, "frigo", "2026-11-09", "dlc", false, Tone.Urgent), inv.lots.single())
        val req = server.takeRequest()
        assertEquals("/app/v1/tools/alimentation/app_inventory", req.url.encodedPath)
        assertEquals("{}", req.body?.utf8())

        server.enqueue(MockResponse.Builder().code(422).body("""{"message":"Lot 4 introuvable."}""").build())
        try {
            api().tool("alimentation", "app_lot_use", ToolMessage.serializer(), buildJsonObject { put("lot", 4) })
            fail("422 attendu")
        } catch (e: ApiError.Server) {
            assertEquals(422 to "Lot 4 introuvable.", e.code to e.message)
        }
        assertEquals("""{"lot":4}""", server.takeRequest().body?.utf8())
    }

    private suspend fun failure(block: suspend () -> Unit): ApiError =
        try {
            block()
            throw AssertionError("erreur attendue")
        } catch (e: ApiError) {
            e
        }

    @Test
    fun api_tool_404_without_message_means_outdated_server() = runBlocking {
        // Tête antérieure au passe-plat : 404 nu, sans raison.
        server.enqueue(MockResponse.Builder().code(404).build())
        val bare = failure { api().tool("alimentation", "app_inventory", Inventory.serializer()) } as ApiError.Server
        assertEquals("Serveur à mettre à jour", bare.unavailable())
        server.enqueue(MockResponse.Builder().code(404).body("""{"message":"module inconnu"}""").build())
        val unknown = failure { api().tool("alimentation", "app_inventory", Inventory.serializer()) } as ApiError.Server
        assertEquals("Module indisponible, réessaie plus tard", unknown.unavailable())
        server.enqueue(MockResponse.Builder().code(503).build())
        val down = failure { api().tool("alimentation", "app_inventory", Inventory.serializer()) } as ApiError.Server
        assertEquals("Module indisponible, réessaie plus tard", down.unavailable())
    }

    @Test
    fun api_timeout_is_no_reply() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("{}").build())
        val slow = KairosApi(server.url("/").toString(), "t", OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build())
        assertEquals(ApiError.NoReply, failure { slow.tool("alimentation", "app_add", ToolMessage.serializer()) })
        server.close()
        assertEquals(ApiError.Offline, failure { api().tool("alimentation", "app_add", ToolMessage.serializer()) })
    }

    @Test
    fun api_product_contract() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"known":false}""").build())
        assertEquals(false, api().tool("alimentation", "app_product", Product.serializer()).known)
        server.enqueue(MockResponse.Builder().body(
            """{"known":true,"name":"Riz","nature":null,"fresh":false,"location":"placard","unit":"g","qty":1000}""",
        ).build())
        assertEquals(Product(true, "Riz", null, false, "placard", "g", 1000.0), api().tool("alimentation", "app_product", Product.serializer()))
    }

    @Test
    fun app_release_absent_is_null() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(api().appRelease())
        server.enqueue(MockResponse.Builder().body("""{"version": "0.4.0", "size": 3}""").build())
        assertEquals(AppRelease("0.4.0", 3), api().appRelease())
        assertEquals("/app/v1/app", server.takeRequest().url.encodedPath)
    }

    @Test
    fun app_download_writes_file_with_auth() = runBlocking {
        server.enqueue(MockResponse.Builder().body("abc").build())
        val file = java.io.File.createTempFile("kairos", ".apk")
        var seen = 0L
        api().downloadApp(file) { seen = it }
        assertEquals("abc", file.readText())
        assertEquals(3L, seen)
        val req = server.takeRequest()
        assertEquals("/app/v1/app.apk", req.url.encodedPath)
        assertEquals("Bearer secret-token", req.headers["Authorization"])
        file.delete()
        Unit
    }

    @Test
    fun app_download_local_write_failure_is_not_offline() = runBlocking {
        server.enqueue(MockResponse.Builder().body("abc").build())
        // Un dossier à la place du fichier : écriture impossible côté téléphone.
        val dir = java.nio.file.Files.createTempDirectory("kairos").toFile()
        try {
            api().downloadApp(dir) {}
            fail("écriture impossible attendue")
        } catch (e: ApiError) {
            fail("erreur locale prise pour une erreur réseau : $e")
        } catch (_: java.io.IOException) {
        } finally {
            dir.delete()
        }
    }
}
