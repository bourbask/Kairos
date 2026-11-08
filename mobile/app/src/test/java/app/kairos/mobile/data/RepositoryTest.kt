package app.kairos.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.RecordedRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 11, 8)

    @Test
    fun today_keeps_last_state_when_offline() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(DAYS_JSON).build())
        server.enqueue(MockResponse.Builder().body("[]").build())
        val dir = tmp.newFolder()
        val repo = Repository(KairosApi(server.url("/").toString(), "t", OkHttpClient()), dir) { today }

        repo.refresh()
        val fresh = repo.snapshot.value
        assertFalse(fresh.offline)
        assertEquals(2, fresh.days.size)

        server.close()
        repo.refresh()
        val stale = repo.snapshot.value
        assertTrue(stale.offline)
        assertEquals(fresh.days, stale.days)

        // Le dernier état survit au redémarrage de l'application.
        val reopened = Repository(KairosApi("http://127.0.0.1:1/", "t", OkHttpClient()), dir) { today }
        reopened.load()
        assertEquals(fresh.days, reopened.snapshot.value.days)
    }

    @Test
    fun maison_keeps_last_state_when_offline() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body(
            """{"zones":[],"lots":[{"id":1,"name":"Riz","qty":500,"unit":"g","label":"500 g","step":50,"location":"placard"}]}""",
        ).build())
        server.enqueue(MockResponse.Builder().body("""[{"id":3,"store":"drive","lines":[{"index":0,"name":"Lait"}]}]""").build())
        val dir = tmp.newFolder()
        val repo = Repository(KairosApi(server.url("/").toString(), "t", OkHttpClient()), dir) { today }

        repo.refreshMaison()
        val fresh = repo.maison.value
        assertFalse(fresh.offline)
        assertEquals("Riz", fresh.inventory.lots.single().name)
        assertEquals(3L, fresh.orders.single().id)

        server.close()
        repo.refreshMaison()
        assertTrue(repo.maison.value.offline)
        assertEquals(fresh.inventory, repo.maison.value.inventory)

        val reopened = Repository(KairosApi("http://127.0.0.1:1/", "t", OkHttpClient()), dir) { today }
        reopened.load()
        assertEquals(fresh.inventory, reopened.maison.value.inventory)
        reopened.clear()
        assertEquals(MaisonSnapshot(), Repository(KairosApi("http://127.0.0.1:1/", "t", OkHttpClient()), dir).apply { load() }.maison.value)
    }

    @Test
    fun maison_unavailable_module_is_not_offline() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().code(503).build())
        val repo = Repository(KairosApi(server.url("/").toString(), "t", OkHttpClient()), tmp.newFolder()) { today }
        repo.refreshMaison()
        assertTrue(repo.maison.value.offline)
        assertEquals("Module indisponible", repo.maison.value.reason)
        server.close()
        repo.refreshMaison()
        assertEquals(null, repo.maison.value.reason)
    }

    @Test
    fun maison_outdated_server_says_so() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().code(404).build())
        val repo = Repository(KairosApi(server.url("/").toString(), "t", OkHttpClient()), tmp.newFolder()) { today }
        repo.refreshMaison()
        assertEquals("Serveur à mettre à jour", repo.maison.value.reason)
        server.close()
    }

    @Test
    fun maison_refreshes_never_go_back_in_time() = runBlocking {
        val server = MockWebServer().apply { start() }
        val inventories = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath.endsWith("app_orders")) {
                    MockResponse.Builder().body("[]").build()
                } else if (inventories.getAndIncrement() == 0) {
                    // Première réponse lente : elle ne doit pas écraser la suivante.
                    MockResponse.Builder().headersDelay(400, TimeUnit.MILLISECONDS)
                        .body("""{"lots":[{"id":1,"name":"ancien"}]}""").build()
                } else {
                    MockResponse.Builder().body("""{"lots":[{"id":1,"name":"récent"}]}""").build()
                }
        }
        val repo = Repository(KairosApi(server.url("/").toString(), "t", OkHttpClient()), tmp.newFolder()) { today }
        val first = launch(Dispatchers.IO) { repo.refreshMaison() }
        delay(100)
        val second = launch(Dispatchers.IO) { repo.refreshMaison() }
        first.join()
        second.join()
        assertEquals("récent", repo.maison.value.inventory.lots.single().name)
        server.close()
    }

    @Test
    fun marks_persist_per_day() = runBlocking {
        val dir = tmp.newFolder()
        val api = KairosApi("http://127.0.0.1:1/", "t", OkHttpClient())
        Repository(api, dir) { today }.mark("2026-11-08", "agenda/a", Mark.Fait)
        val again = Repository(api, dir) { today }.apply { load() }
        assertEquals(mapOf("2026-11-08" to mapOf("agenda/a" to Mark.Fait)), again.marks.value)
        // Les jours passés sont oubliés.
        val tomorrow = Repository(api, dir) { today.plusDays(1) }.apply { load() }
        assertEquals(emptyMap<String, Map<String, Mark>>(), tomorrow.marks.value)
    }

    @Test
    fun disk_errors_never_crash() = runBlocking {
        // Le « dossier » d'état est un fichier : aucune lecture ni écriture possible.
        val notADir = tmp.newFile()
        val repo = Repository(KairosApi("http://127.0.0.1:1/", "t", OkHttpClient()), notADir) { today }
        repo.mark("2026-11-08", "agenda/a", Mark.Fait)
        repo.refresh()
        assertTrue(repo.snapshot.value.offline)
        assertEquals(Mark.Fait, repo.marks.value["2026-11-08"]?.get("agenda/a"))
    }

    @Test
    fun drop_removes_decision_by_module_and_id() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse.Builder().body("[]").build())
        server.enqueue(MockResponse.Builder().body(DECISIONS_JSON).build())
        val repo = Repository(KairosApi(server.url("/").toString(), "t", OkHttpClient()), tmp.newFolder()) { today }
        repo.refresh()
        repo.drop("finances", "op-1")
        assertEquals(listOf("alimentation/op-1", "finances/op-2"), repo.snapshot.value.decisions.map { "${it.module}/${it.id}" })
        server.close()
    }
}

private const val DECISIONS_JSON = """[
  {"id": "op-1", "title": "A", "module": "finances"},
  {"id": "op-1", "title": "B", "module": "alimentation"},
  {"id": "op-2", "title": "C", "module": "finances"}
]"""
