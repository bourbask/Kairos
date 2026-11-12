package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.ui.politique.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PolDataTest {
    private fun page(name: String) =
        PolJson.decodeFromString(PolPage.serializer(), javaClass.getResource("/politique/$name.json")!!.readText())

    private fun obj(s: String) = PolJson.parseToJsonElement(s).jsonObject

    private val identities = """{"$LIST_KEY": [
        {"id":"a","nom":"Candidat A","parti":"Parti Bleu","alias":["Cand. A","Liste commune"]},
        {"id":"b","nom":"Candidat B","parti":"Parti Bleu","alias":["Liste commune"]},
        {"id":"parti-bleu","nom":"Parti Bleu","parti":"Parti Bleu","alias":["Bleus"]},
        {"id":"gauche","nom":"Gauche"},
        {"id":"mauvais"}, 12
    ]}"""

    private val evaluateurs = """{"EVALUATEURS": [
        {"id":"institut","nom":"Institut X","alias":["Institut X"],"independance":"3"},
        {"id":"institut-xyz","nom":"Institut Xyz","alias":["Institut Xyz"]}
    ]}"""

    private val data = PolData(mapOf("identites" to obj(identities), "evaluateurs" to obj(evaluateurs), "meta" to obj("""{"verifie_le":"2026-01-01"}""")))

    @Test
    fun merge_pages_restores_split_list() {
        val p0 = page("fichier-dit-vote-p0")
        val p1 = page("fichier-dit-vote-p1")
        val m = mergePages(listOf(p1, p0))
        val n0 = (p0.contenu as JsonObject)["SCRUTINS"]!!.jsonArray.size
        val n1 = (p1.contenu as JsonObject)["SCRUTINS"]!!.jsonArray.size
        assertEquals(n0 + n1, m["SCRUTINS"]!!.jsonArray.size)
        assertEquals("s-0", m["SCRUTINS"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertNotNull(m["ANNEXE"])
    }

    @Test
    fun loader_restarts_when_version_changes() = runBlocking {
        val p0 = page("fichier-dit-vote-p0")
        val p1 = page("fichier-dit-vote-p1")
        var calls = 0
        val d = loadPolData({ _, n ->
            calls++
            when {
                calls == 1 -> p0.copy(version = "v1", pages = 2)
                calls == 2 -> p1.copy(version = "v2")
                else -> (if (n == 0) p0 else p1).copy(version = "v3", pages = 2)
            }
        }, listOf("dit-vote"))
        assertEquals(4, calls)
        assertNotNull(d.file("dit-vote"))
        // Toujours instable : erreur après 2 essais.
        var k = 0
        try {
            loadPolData({ _, n -> p0.copy(version = "v${k++}", pages = 2, page = n) }, listOf("dit-vote"))
            fail()
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun loader_marks_unknown_file_absent() = runBlocking {
        val d = loadPolData({ id, _ ->
            if (id == "meta") PolPage(id, contenu = obj("""{"verifie_le":"x"}""")) else throw ApiError.Server(400, "Fichier inconnu.")
        }, listOf("meta", "villes"))
        assertNotNull(d.file("meta"))
        assertNull(d.file("villes"))
        assertEquals("x", d.verifiedOn)
        try {
            loadPolData({ _, _ -> throw ApiError.Offline }, listOf("meta"))
            fail()
        } catch (_: ApiError.Offline) {}
    }

    @Test
    fun id_of_folds_and_shared_alias() {
        assertEquals("a", data.idOf("CANDIDAT  Á"))
        assertEquals("a", data.idOf("candidat a"))
        assertEquals(listOf("a", "b"), data.idsIn("liste commune"))
        assertNull(data.idOf("inconnu"))
        assertNull(data.idOf(null))
        assertNull(data.idOf(""))
        assertTrue(data.idsIn("").isEmpty())
        assertEquals(5, data.identities.size) // « mauvais » (sans nom) gardé, 12 ignoré
    }

    @Test
    fun kind_and_party_of_identities() {
        assertEquals(IdKind.Personne, data.kindOf("a"))
        assertEquals(IdKind.Parti, data.kindOf("parti-bleu"))
        assertEquals(IdKind.Camp, data.kindOf("gauche"))
        assertEquals("parti-bleu", data.partyOf("a"))
        assertNull(data.partyOf("gauche"))
    }

    @Test
    fun ev_for_prefers_explicit_then_longest_alias() {
        assertEquals("institut", data.evFor("Institut X note 3")?.id)
        assertEquals("institut-xyz", data.evFor("Institut Xyz")?.id)
        assertEquals("institut-xyz", data.evFor("Institut X note 3", "institut-xyz")?.id)
        assertNull(data.evFor("Institut Xy"))
        assertNull(data.evFor(null))
    }

    @Test
    fun sources_of_all_shapes() {
        fun s(j: String) = sourcesOf(PolJson.parseToJsonElement(j))
        assertEquals(listOf(SourceRef("L", "u")), s("""["L","u"]"""))
        assertEquals(listOf(SourceRef("L", "u", "e"), SourceRef("M", null)), s("""[["L","u","e"],["M"]]"""))
        assertEquals(listOf(SourceRef("ofce", "u", "ofce")), s("""{"ev":"ofce","url":"u"}"""))
        assertEquals(listOf(SourceRef("Seule")), s("\"Seule\""))
        assertTrue(s("null").isEmpty() && s("[]").isEmpty() && s("3").isEmpty())
    }

    @Test
    fun models_tolerate_missing_and_wrong_types() {
        val d = PolData(mapOf("f" to obj("""{"L": [
            {"theme":"t","par":["x"],"lecture":{"postes":[{"quoi":"q","montant":12}]}},
            {"theme":"u","facts":"pas une liste"},
            {"theme":"v","v":null,"cache":5}
        ]}""")))
        val c = d.decode<Claim>("f", "L")
        assertEquals(listOf("t", "v"), c.map { it.theme })
        assertEquals("12", c[0].lecture!!.postes[0].montant)
        assertTrue(c[1].facts.isEmpty())
        assertEquals(3, data.evaluators["institut"]!!.independance)
        assertTrue(d.decode<Claim>("absent", "L").isEmpty() && d.decode<Claim>("f", "nope").isEmpty())
    }

    @Test
    fun sample_evaluators_decode() {
        val d = PolData(mapOf("evaluateurs" to mergePages(listOf(page("fichier-evaluateurs")))))
        assertEquals(listOf("institut-fictif-1"), d.evaluators.keys.toList().take(1))
        assertEquals("Sujet fictif", d.evaluators.values.first().historique.first().sujet)
        assertEquals(listOf("Source fictive"), d.evaluators.values.first().historique.first().src.let(::sourcesOf).map { it.label })
    }
}
