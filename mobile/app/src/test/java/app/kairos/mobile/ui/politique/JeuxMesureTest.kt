package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.jeux.JEUX_REGISTRE
import app.kairos.mobile.ui.politique.jeux.jeu.CransParams
import app.kairos.mobile.ui.politique.jeux.jeu.MOL_H
import app.kairos.mobile.ui.politique.jeux.jeu.MOL_W
import app.kairos.mobile.ui.politique.jeux.jeu.Mol
import app.kairos.mobile.ui.politique.jeux.jeu.MoletteParams
import app.kairos.mobile.ui.politique.jeux.jeu.ROM_D
import app.kairos.mobile.ui.politique.jeux.jeu.ROM_H
import app.kairos.mobile.ui.politique.jeux.jeu.ROM_REPOS
import app.kairos.mobile.ui.politique.jeux.jeu.ROM_W
import app.kairos.mobile.ui.politique.jeux.jeu.Rom
import app.kairos.mobile.ui.politique.jeux.jeu.SommeD
import app.kairos.mobile.ui.politique.jeux.jeu.fleche
import app.kairos.mobile.ui.politique.jeux.jeu.fr
import app.kairos.mobile.ui.politique.jeux.jeu.milliards
import app.kairos.mobile.ui.politique.jeux.jeu.molette
import app.kairos.mobile.ui.politique.jeux.jeu.nom
import app.kairos.mobile.ui.politique.jeux.jeu.params
import app.kairos.mobile.ui.politique.jeux.jeu.premierLibre
import app.kairos.mobile.ui.politique.jeux.jeu.romaine
import app.kairos.mobile.ui.politique.jeux.jeu.romaineCran
import app.kairos.mobile.ui.politique.jeux.jeu.rouleaux
import app.kairos.mobile.ui.politique.jeux.jeu.sommeEtiquette
import app.kairos.mobile.ui.politique.jeux.jeu.sommeJuste
import app.kairos.mobile.ui.politique.jeux.jeu.sommeMonter
import app.kairos.mobile.ui.politique.jeux.jeuxOf
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JeuxMesureTest {
    private val jeux = jeuxOf(PolData(mapOf("jeux" to PolJson.parseToJsonElement(javaClass.getResource("/jeux-mesure.json")!!.readText()).jsonObject)))

    private fun jouables(slug: String) = jeux.getValue(slug).manches.filter { JEUX_REGISTRE.getValue(slug).decoder(it.d, jeux.getValue(slug).params) != null }.map { it.id }

    @Test fun registered() = listOf("quelle-gravite", "le-juste-cout", "independant").forEach { assertNotNull(it, JEUX_REGISTRE[it]) }

    @Test fun invalid_rounds_are_dropped() {
        assertEquals(listOf("g-ok"), jouables("quelle-gravite"))
        assertEquals(listOf("s-ok"), jouables("le-juste-cout"))
        assertEquals(listOf("institut-exemple"), jouables("independant"))
    }

    @Test fun params_decode() {
        val g = jeux.getValue("quelle-gravite").params.params(CransParams.serializer(), CransParams())
        assertEquals("Cran trois", g.crans.nom(3))
        assertEquals("", g.crans.nom(7))
        val i = jeux.getValue("independant").params.params(CransParams.serializer(), CransParams())
        assertEquals("Niveau quatre", i.niveaux.nom(4))
        val m = jeux.getValue("le-juste-cout").params.params(MoletteParams.serializer(), MoletteParams())
        assertEquals("Md€", m.unite)
        assertEquals(29, m.crans.size)
        assertEquals(200.0, m.crans.last(), 0.0)
    }

    @Test fun somme_range_and_direction() {
        val d = SommeD(min = 60.0, max = 100.0)
        assertTrue(sommeJuste(60.0, d))
        assertTrue(sommeJuste(100.0, d))
        assertFalse(sommeJuste(50.0, d))
        assertFalse(sommeJuste(120.0, d))
        assertTrue(sommeMonter(50.0, d))
        assertFalse(sommeMonter(120.0, d))
        assertEquals("Vous : 70 · juste de 60 à 100", sommeEtiquette(70.0, d))
        assertEquals("11,3", fr(11.3))
        assertEquals("1 milliard", milliards(1.0))
        assertEquals("2 milliards", milliards(2.0))
        assertEquals(listOf(0, 0, 7), rouleaux(7.0))
        assertEquals(listOf(2, 0, 0), rouleaux(200.0))
    }

    @Test fun premier_libre_and_romaine_cran() {
        assertEquals(1, premierLibre(emptyList()))
        assertEquals(3, premierLibre(listOf("1", "2")))
        assertEquals(1, premierLibre(listOf("1", "2", "3", "4")))
        assertEquals(1, romaineCran(0f, emptyList()))
        assertEquals(4, romaineCran(200f, emptyList()))
        assertEquals(3, romaineCran(45f, listOf("2")))
        assertNull(romaineCran(30f, listOf("1", "2", "3", "4")))
    }

    @Test fun pixel_geometry_fits() {
        val g = molette(0f)
        assertEquals(MOL_W * MOL_H, g.size)
        assertTrue(Mol.Rg in g.toList())
        assertEquals(listOf("....o....", "...oio...", "..oiiio..", ".oiiiiio.", "ooooooooo"), fleche(true))
        // Romaine au repos comme d'aplomb : tout tient dans la scène (l'oscillation déborde : scène rognée).
        listOf(ROM_REPOS, 0f).forEach { a ->
            (0..4).forEach { n ->
                val r = romaine(a, n, listOf("2"))
                r.values.flatten().forEach { (x, y) -> assertTrue("$a $n $x,$y", x in 0 until ROM_W && y in 0 until ROM_H) }
                assertTrue(Rom.Ww in r && Rom.Ss in r && Rom.Bo in r)
            }
        }
    }
}
