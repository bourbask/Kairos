package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.jeux.JEUX_REGISTRE
import app.kairos.mobile.ui.politique.jeux.jeu.LeFinanceur
import app.kairos.mobile.ui.politique.jeux.jeu.LesLiens
import app.kairos.mobile.ui.politique.jeux.jeu.PIECE
import app.kairos.mobile.ui.politique.jeux.jeu.TIRELIRE
import app.kairos.mobile.ui.politique.jeux.jeu.ordreEmplacements
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Jeux du thème « Relie » : manches de la forme publiée, valeurs fictives. */
class JeuxRelieTest {
    private fun d(json: String): JsonObject = PolJson.parseToJsonElement(json).jsonObject

    private val liens = LesLiens()
    private val financeur = LeFinanceur()

    private val manchesLiens = d(
        """{"paires":[{"media":"Média A","proprietaire":"Groupe Alpha","groupe":"Holding A"},
            {"media":"Média B","proprietaire":"Famille Exemple","groupe":null},
            {"media":"Média C","proprietaire":"Société Fictive","groupe":"Holding C"}],"inconnu":1}""",
    )

    private val mancheFinanceur = d(
        """{"pieces":[{"montant":11.32,"montantTexte":"11,32","tirelire":"Parti B"},
            {"montant":-6.68,"montantTexte":"−6,68","tirelire":"Parti A"},
            {"montant":14.8,"montantTexte":"+14,8","tirelire":"Parti C"}],
            "tirelires":["Parti A","Parti C","Parti B"]}""",
    )

    @Test fun registered() {
        assertTrue(JEUX_REGISTRE["les-liens"] is LesLiens)
        assertTrue(JEUX_REGISTRE["le-financeur"] is LeFinanceur)
    }

    // ---------- Les liens ----------

    @Test fun liens_decodes_published_shape() {
        val v = liens.decoder(manchesLiens)!!
        assertEquals(3, v.paires.size)
        assertEquals("Famille Exemple", v.paires[1].proprietaire)
        assertNull(v.paires[1].groupe)
    }

    @Test fun liens_rejects_unplayable() {
        assertNull(liens.decoder(d("""{"paires":[]}""")))
        assertNull(liens.decoder(d("""{"paires":[{"media":"A","proprietaire":"X"}]}""")))
        assertNull(liens.decoder(d("""{"paires":[{"media":"A","proprietaire":"X"},{"media":"B","proprietaire":""}]}""")))
        // Deux médias au même propriétaire : une bonne paire serait refusée.
        assertNull(liens.decoder(d("""{"paires":[{"media":"A","proprietaire":"X"},{"media":"B","proprietaire":"X"}]}""")))
        assertNull(liens.decoder(d("""{"paires":[{"media":"A","proprietaire":"X"},{"media":"A","proprietaire":"Y"}]}""")))
        val quatre = (1..4).joinToString(",") { """{"media":"M$it","proprietaire":"P$it"}""" }
        assertNull(liens.decoder(d("""{"paires":[$quatre]}""")))
    }

    @Test fun owners_never_face_their_media() {
        repeat(200) { seed ->
            for (n in 2..3) {
                val o = ordreEmplacements(n, Random(seed))
                assertEquals((0 until n).toList(), o.sorted())
                assertTrue(o.indices.none { o[it] == it })
            }
        }
        assertEquals(listOf(0), ordreEmplacements(1))
    }

    // ---------- Le financeur ----------

    @Test fun financeur_decodes_and_matches() {
        val v = financeur.decoder(mancheFinanceur)!!
        assertTrue(v.juste(0, 2))
        assertTrue(v.juste(1, 0))
        assertTrue(v.juste(2, 1))
        assertFalse(v.juste(0, 0))
        assertFalse(v.juste(2, 2))
        assertEquals(listOf(1, 2, 0), v.tirelires.indices.map(v::pieceDe))
        assertEquals(-6.68, v.pieces[1].montant, 1e-9)
    }

    @Test fun financeur_rejects_unplayable() {
        // Tirelire sans pièce, pièce sans tirelire.
        assertNull(financeur.decoder(d("""{"pieces":[{"montantTexte":"1","tirelire":"A"},{"montantTexte":"2","tirelire":"Z"}],"tirelires":["A","B"]}""")))
        // Deux pièces pour une même tirelire.
        assertNull(financeur.decoder(d("""{"pieces":[{"montantTexte":"1","tirelire":"A"},{"montantTexte":"2","tirelire":"A"}],"tirelires":["A","B"]}""")))
        // Montant à afficher absent.
        assertNull(financeur.decoder(d("""{"pieces":[{"montant":1,"tirelire":"A"},{"montantTexte":"2","tirelire":"B"}],"tirelires":["A","B"]}""")))
        assertNull(financeur.decoder(d("""{"pieces":[{"montantTexte":"1","tirelire":"A"}],"tirelires":["A"]}""")))
        assertNull(financeur.decoder(d("""{"tirelires":["A","B"]}""")))
    }

    @Test fun pixel_drawings_have_expected_size() {
        assertEquals(24, PIECE.size)
        assertTrue(PIECE.all { it.length == 22 })
        // Tranche crantée : alternance des deux teintes dans le bas de la pièce.
        assertTrue(PIECE.any { "eE" in it })
        assertEquals(25, TIRELIRE.size)
        assertTrue(TIRELIRE.all { it.length == 34 })
        // Fente : arête sombre puis fond.
        assertTrue('K' in TIRELIRE[4] && 'k' in TIRELIRE[5])
    }
}
