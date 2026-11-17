package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.jeux.JEUX_REGISTRE
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.jeu.Empreinte
import app.kairos.mobile.ui.politique.jeux.jeu.FICHES_HAUT
import app.kairos.mobile.ui.politique.jeux.jeu.LeProcede
import app.kairos.mobile.ui.politique.jeux.jeu.POIGNEE_TAMPON
import app.kairos.mobile.ui.politique.jeux.jeu.QuiLaDit
import app.kairos.mobile.ui.politique.jeux.jeu.TEXTURE_ENCRE
import app.kairos.mobile.ui.politique.jeux.jeu.TamponsD
import app.kairos.mobile.ui.politique.jeux.jeu.TrenteSecondes
import app.kairos.mobile.ui.politique.jeux.jeu.empreinte
import app.kairos.mobile.ui.politique.jeux.jeuxOf
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JeuxDemeleTest {
    private val jeux = jeuxOf(PolData(mapOf("jeux" to PolJson.parseToJsonElement(javaClass.getResource("/jeux-demele.json")!!.readText()).jsonObject)))
    private fun d(s: String) = PolJson.parseToJsonElement(s).jsonObject

    @Test fun registered() {
        assertTrue(JEUX_REGISTRE["qui-l-a-dit"] is QuiLaDit)
        assertTrue(JEUX_REGISTRE["le-procede"] is LeProcede)
        assertTrue(JEUX_REGISTRE["trente-secondes"] is TrenteSecondes)
    }

    @Test fun portraits() {
        val g = QuiLaDit()
        val (a, b) = jeux.getValue("qui-l-a-dit").manches
        val pa = g.decoder(a.d)!!
        assertEquals("personne-a", pa.choix[pa.bonne].id)
        assertNull(pa.choix[1].parti)
        // Trois choix seulement : écartée.
        assertNull(g.decoder(b.d))
        assertNull(g.decoder(d("""{"choix":[{"id":"a","nom":"A"},{"id":"b","nom":"B"},{"id":"c","nom":"C"},{"id":"d","nom":"D"}],"bonne":4}""")))
        assertNull(g.decoder(d("""{"choix":[{"id":"a","nom":""},{"id":"b","nom":"B"},{"id":"c","nom":"C"},{"id":"d","nom":"D"}],"bonne":0}""")))
        assertNull(g.decoder(d("""{"choix":"pas une liste","bonne":0}""")))
    }

    @Test fun tampons() {
        val g = LeProcede()
        val (a, b) = jeux.getValue("le-procede").manches
        val da = g.decoder(a.d)!!
        assertEquals(1, da.bonne)
        assertNull(da.contexte)
        assertEquals("Déformer l'adversaire", g.etiquette(a, da, MancheEtat(a), JsonObject(emptyMap())))
        assertEquals("Contexte d'exemple.", g.decoder(b.d)!!.contexte)
        assertNull(g.etiquette(a, da.copy(famille = ""), MancheEtat(a), JsonObject(emptyMap())))
        assertNull(g.decoder(d("""{"choix":["A","B"],"bonne":0}""")))
        assertNull(g.decoder(d("""{"choix":["A","B","C"],"bonne":-1}""")))
    }

    @Test fun empreinte_follows_last_strike() {
        val t = TamponsD(listOf("A", "B", "C"), bonne = 2)
        assertNull(empreinte(t, false, emptyList()))
        assertEquals(Empreinte("B", false), empreinte(t, false, listOf("0", "1")))
        assertEquals(Empreinte("C", true), empreinte(t, true, listOf("0", "1")))
        assertNull(empreinte(t, false, listOf("x")))
    }

    @Test fun stamp_pixel_art() {
        assertEquals(17, POIGNEE_TAMPON.size)
        assertTrue(POIGNEE_TAMPON.all { it.length == 16 })
        // Boule symétrique en contour, reflet en haut à gauche, col puis évasement en bas.
        assertEquals('o', POIGNEE_TAMPON[5][0])
        assertEquals('w', POIGNEE_TAMPON[2][4])
        assertEquals("......nnnm......", POIGNEE_TAMPON[13])
        assertEquals("....nnnnnnnm....", POIGNEE_TAMPON[16])
        assertEquals(256, TEXTURE_ENCRE.size)
        assertTrue(TEXTURE_ENCRE.count { it == 0 } in 10..50)
    }

    @Test fun fiches() {
        val g = TrenteSecondes()
        val (a, b) = jeux.getValue("trente-secondes").manches
        assertEquals(1, g.decoder(a.d)!!.bonne)
        assertEquals("Ce qu'elle cache, exemple.", a.reponse.cache)
        // Origine vide : écartée.
        assertNull(g.decoder(b.d))
        // Chaque fiche garde au moins 48 dp de prise au-dessus de la suivante.
        FICHES_HAUT.toList().zipWithNext().forEach { (h, n) -> assertTrue(n - h >= 48) }
    }
}
