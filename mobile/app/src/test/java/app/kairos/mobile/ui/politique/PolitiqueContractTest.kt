package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolPage
import app.kairos.mobile.ui.politique.model.mergePages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réponses réelles du module sur ses fixtures fictives (`resources/politique`, régénérées par
 * le test `contract_samples_match_app_resources` du module), lues avec le décodeur de l'application.
 */
class PolitiqueContractTest {
    private fun text(name: String) = javaClass.getResource("/politique/$name.json")!!.readText()

    @Test
    fun identities_file_decodes_and_invalid_avatar_is_refused() {
        // Le fichier des identités arrive tel quel : l'application écarte elle-même un avatar mal formé.
        val page = KairosJson.decodeFromString(PolPage.serializer(), text("fichier-identites"))
        val data = PolData(mapOf("identites" to mergePages(listOf(page))))
        assertEquals(listOf("a", "b", "c", "parti-bleu"), data.identities.map { it.id })
        assertNotNull("avatar valide gardé", avatarOf(data.byId("a")!!.avatar))
        assertNull("avatar invalide (15 lignes) écarté", avatarOf(data.byId("c")!!.avatar))
    }

    @Test
    fun file_pages_decode_with_lookup_json() {
        // Le décodeur de Repository.lookup : objet, liste découpée, version.
        val p0 = KairosJson.decodeFromString(PolPage.serializer(), text("fichier-dit-vote-p0"))
        assertEquals(0, p0.page)
        assertEquals(2, p0.pages)
        assertTrue(p0.version.isNotEmpty())
        assertTrue((p0.contenu as JsonObject)["SCRUTINS"] is JsonArray)
        val ev = KairosJson.decodeFromString(PolPage.serializer(), text("fichier-evaluateurs"))
        assertEquals(1, ev.pages)
        // Racine en liste (fichier des identités) : gardée telle quelle.
        val list = KairosJson.decodeFromString(PolPage.serializer(), """{"id":"identites","page":0,"pages":1,"version":"v","contenu":[{"id":"a"}]}""")
        assertTrue(list.contenu is JsonArray)
    }

    @Test
    fun every_sample_decodes() {
        val all = javaClass.getResource("/politique")!!.let { java.io.File(it.toURI()).list()!!.sorted() }
        assertEquals(5, all.size)
        for (n in all) assertTrue(n, KairosJson.parseToJsonElement(javaClass.getResource("/politique/$n")!!.readText()) is JsonObject)
    }
}
