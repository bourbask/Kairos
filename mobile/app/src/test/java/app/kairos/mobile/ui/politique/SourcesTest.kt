package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.kit.sourceParts
import app.kairos.mobile.ui.politique.kit.sourcesText
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.model.SourceRef
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SourcesTest {
    private val data = PolData(mapOf("evaluateurs" to PolJson.parseToJsonElement("""{"EVALUATEURS": [
        {"id":"institut","nom":"Institut X","alias":["Institut X"],"independance":"3"}
    ]}""").jsonObject))

    private fun parts(label: String, url: String? = null, ev: String? = null) = sourceParts(SourceRef(label, url, ev), data)

    @Test fun evaluator_left_article_right_only_with_https() {
        val p = parts("Institut X, rapport annuel", "https://www.exemple.org/r")
        assertEquals("institut", p.ev?.id)
        assertEquals("rapport annuel", p.article)
        assertEquals("Institut X · rapport annuel", p.text)
        // Adresse non https : plus de partie droite.
        val http = parts("Institut X, rapport annuel", "http://exemple.org/r")
        assertNull(http.article); assertNull(http.url)
        assertEquals("Institut X", http.text)
        // Libellé réduit à l'id : la partie droite est le domaine, jamais l'id.
        assertEquals("Institut X · exemple.org", parts("institut", "https://exemple.org/r", "institut").text)
    }

    @Test fun without_evaluator_name_is_plain_left_part() {
        val p = parts("Journal fictif", "https://exemple.org/a")
        assertNull(p.ev)
        assertEquals("Journal fictif", p.who); assertEquals("exemple.org", p.article)
        assertEquals("Journal fictif", parts("Journal fictif").text)
        assertEquals("exemple.org", parts("", "https://exemple.org/a").text)
        assertEquals("Source", parts(" ", "http://exemple.org").text)
    }

    @Test fun sources_text_joins_labels() {
        assertEquals("Institut X · exemple.org ; Journal fictif", sourcesText(listOf(SourceRef("Institut X", "https://exemple.org"), SourceRef("Journal fictif")), data))
        assertNull(sourcesText(emptyList(), data))
    }

    /** Toute source passe par l'étiquette commune : la lecture d'une source et l'ouverture d'un lien restent dans le kit. */
    @Test fun no_screen_reads_sources_outside_the_kit() {
        val root = File("src/main/java/app/kairos/mobile/ui/politique")
        assertTrue("racine des écrans introuvable : ${root.absolutePath}", root.isDirectory)
        val allowed = setOf("kit/PolKit.kt", "model/PolData.kt")
        val banned = Regex("""\b(sourceName|sourceRest|evFor|openUrl)\(""")
        val hits = root.walk().filter { it.extension == "kt" && it.relativeTo(root).invariantSeparatorsPath !in allowed }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (banned.containsMatchIn(l)) "${f.relativeTo(root)}:${i + 1}" else null } }
            .toList()
        assertEquals("rendu de source hors de SourceButton", emptyList<String>(), hits)
    }
}
