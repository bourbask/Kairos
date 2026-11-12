package app.kairos.mobile.ui.politique

import app.kairos.mobile.data.KairosJson
import app.kairos.mobile.data.PolIdentity
import app.kairos.mobile.ui.politique.model.PolPage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    fun identities_keep_valid_avatars_only() {
        val ids = KairosJson.decodeFromJsonElement(ListSerializer(PolIdentity.serializer()), KairosJson.parseToJsonElement(text("accueil")).jsonObject["identites"]!!)
        assertEquals(listOf("a", "b", "c", "parti-bleu"), ids.map { it.id })
        assertNotNull("avatar valide gardé", avatarOf(ids[0].avatar))
        assertNull("avatar invalide retiré par le module", ids[2].avatar)
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
        assertEquals(15, all.size)
        for (n in all) assertTrue(n, KairosJson.parseToJsonElement(javaClass.getResource("/politique/$n")!!.readText()) is JsonObject)
    }
}
