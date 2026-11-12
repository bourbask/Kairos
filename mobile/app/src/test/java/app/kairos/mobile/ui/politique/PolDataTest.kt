package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.ui.politique.kit.sourceName
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
        } catch (_: PolLoadException) {}
    }

    @Test
    fun loader_restarts_when_file_shrinks() = runBlocking {
        val p0 = page("fichier-dit-vote-p0")
        var calls = 0
        val d = loadPolData({ _, n ->
            calls++
            when {
                calls == 1 -> p0.copy(version = "v1", pages = 2)
                calls == 2 -> throw ApiError.Server(422, "$POL_PAGE_OUT_OF_RANGE : le fichier compte 1 page(s).")
                else -> p0.copy(version = "v2", pages = 1, page = n)
            }
        }, listOf("dit-vote"))
        assertEquals(3, calls)
        assertNotNull(d.file("dit-vote"))
        // Hors bornes sur la page 0 : vraie erreur, remontée telle quelle.
        try {
            loadPolData({ _, _ -> throw ApiError.Server(422, POL_PAGE_OUT_OF_RANGE) }, listOf("dit-vote"))
            fail()
        } catch (_: ApiError.Server) {}
    }

    @Test
    fun loader_marks_unknown_file_absent() = runBlocking {
        val d = loadPolData({ id, _ ->
            if (id == "meta") PolPage(id, contenu = obj("""{"verifie_le":"x"}""")) else throw ApiError.Server(400, POL_UNKNOWN_FILE)
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
        assertNull(data.idOf("liste commune"))
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

    private fun ids(json: String, vararg extra: Pair<String, String>) =
        PolData(mapOf("identites" to obj("""{"$LIST_KEY": $json}""")) + extra.associate { it.first to obj(it.second) })

    @Test
    fun kind_prefers_explicit_type() {
        val d = ids("""[
            {"id":"x","nom":"Parti Bleu","parti":"Parti Bleu","type":"personne"},
            {"id":"y","nom":"Candidat A","parti":"PB","type":"Parti"},
            {"id":"z","nom":"Candidat C","parti":"PB","type":"camp"},
            {"id":"w","nom":"Gauche","type":"inconnu"},
            {"id":"v","nom":"Candidat D","parti":"PB","type":3}
        ]""")
        assertEquals(IdKind.Personne, d.kindOf("x"))
        assertEquals(IdKind.Parti, d.kindOf("y"))
        assertEquals(IdKind.Camp, d.kindOf("z"))
        assertEquals(IdKind.Camp, d.kindOf("w")) // type inconnu : déduit, sans parti
        assertEquals(IdKind.Personne, d.kindOf("v")) // type mal typé : déduit
    }

    @Test
    fun kind_fallback_party_with_acronym() {
        val d = ids(
            """[
            {"id":"pb","nom":"Parti Bleu","parti":"PB","alias":["PB","Les Bleus"]},
            {"id":"a","nom":"Candidat A","parti":"PB","alias":["Candidat A","A"]},
            {"id":"b","nom":"Candidat B","parti":"PB","alias":["Candidat B"]},
            {"id":"c","nom":"Candidat C","parti":"PB","alias":["Cand. C"]}
        ]""",
            "bilan" to """{"CANDS":["A"]}""",
            "budgets" to """{"PROGRAMS":[{"who":"Candidat B"},{"who":"Parti Bleu","ref":true}]}""",
        )
        assertEquals(IdKind.Parti, d.kindOf("pb"))
        assertEquals(IdKind.Personne, d.kindOf("a")) // alias candidat du bilan
        assertEquals(IdKind.Personne, d.kindOf("b")) // candidat d'un programme
        assertEquals(IdKind.Personne, d.kindOf("c")) // ni parti ni nom dans les alias
        assertEquals("pb", d.partyOf("a"))
        assertNull(d.partyOf("pb"))
        assertEquals(listOf("a", "b", "c"), d.identities.filter { d.partyOf(it.id) == "pb" }.map { it.id })
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
        // Sans libellé, jamais l'adresse brute comme nom : l'hôte (https) ou « Source ».
        assertEquals(listOf(SourceRef("", "http://x.example.org/a")), s("""{"url":"http://x.example.org/a"}"""))
        assertEquals("Source", sourceName(s("""{"url":"http://x.example.org/a"}""").single()))
        assertEquals("example.org", sourceName(s("""{"url":"https://www.example.org/a"}""").single()))
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
