package app.kairos.mobile.data

import kotlinx.coroutines.runBlocking
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
