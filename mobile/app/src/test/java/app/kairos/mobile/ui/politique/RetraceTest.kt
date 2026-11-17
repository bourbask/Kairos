package app.kairos.mobile.ui.politique

import androidx.compose.ui.geometry.Offset
import app.kairos.mobile.ui.politique.jeux.JEUX_REGISTRE
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.jeu.AvantApres
import app.kairos.mobile.ui.politique.jeux.jeu.CARTE_H
import app.kairos.mobile.ui.politique.jeux.jeu.CARTE_W
import app.kairos.mobile.ui.politique.jeux.jeu.ENCARTS
import app.kairos.mobile.ui.politique.jeux.jeu.ENCART_H
import app.kairos.mobile.ui.politique.jeux.jeu.LOUPE
import app.kairos.mobile.ui.politique.jeux.jeu.LaVille
import app.kairos.mobile.ui.politique.jeux.jeu.LeVote
import app.kairos.mobile.ui.politique.jeux.jeu.PROJ_DEFAUT
import app.kairos.mobile.ui.politique.jeux.jeu.VOTES
import app.kairos.mobile.ui.politique.jeux.jeu.banderoleVote
import app.kairos.mobile.ui.politique.jeux.jeu.cransAA
import app.kairos.mobile.ui.politique.jeux.jeu.dateLongue
import app.kairos.mobile.ui.politique.jeux.jeu.encartsX
import app.kairos.mobile.ui.politique.jeux.jeu.grilleRegions
import app.kairos.mobile.ui.politique.jeux.jeu.margeHaute
import app.kairos.mobile.ui.politique.jeux.jeu.plusProche
import app.kairos.mobile.ui.politique.jeux.jeu.pointe
import app.kairos.mobile.ui.politique.jeux.jeu.pointeMetropole
import app.kairos.mobile.ui.politique.jeux.jeu.teinte
import app.kairos.mobile.ui.politique.jeux.jeu.touchesVote
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.RegionGeo
import app.kairos.mobile.ui.politique.screens.boundsOf
import app.kairos.mobile.ui.politique.screens.parsePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jeux du thème Retrace : manches de `jeux.json` (personnes remplacées par des valeurs fictives). */
class RetraceTest {
    private fun o(json: String): JsonObject = PolJson.parseToJsonElement(json).jsonObject

    // ---------- Vote en cohérence ----------

    private val vote = o(
        """{"orateur":"Exemple Orateur","parti":"exemple","chambre":"Assemblée nationale","date":"2025-11-27",
        "scrutin":"Nationalisation d'une entreprise","objet":"Proposition de nationaliser les sites français.","bonne":"abstention"}""",
    )

    @Test fun vote_decodes_real_round() {
        val d = LeVote().decoder(vote)!!
        assertEquals("abstention", d.bonne)
        assertEquals("Nationalisation d'une entreprise · Proposition de nationaliser les sites français.", banderoleVote(d))
        assertEquals("Nationalisation d'une entreprise", banderoleVote(d.copy(objet = null)))
        assertEquals("Nationalisation d'une entreprise", banderoleVote(d.copy(objet = " ")))
    }

    @Test fun vote_refuses_bad_rounds() {
        val g = LeVote()
        assertNull(g.decoder(o("""{"orateur":"A","scrutin":"S","bonne":"oui"}""")))
        assertNull(g.decoder(o("""{"scrutin":"S","bonne":"pour"}""")))
        assertNull(g.decoder(o("""{"orateur":"A","bonne":"pour"}""")))
        assertNull(g.decoder(o("""{"orateur":"A","scrutin":"S","bonne":3}""")))
        assertNotNull(g.decoder(o("""{"orateur":"A","scrutin":"S","bonne":"contre","inconnu":1}""")))
    }

    @Test fun vote_keys_follow_params() {
        assertEquals(VOTES, touchesVote(o("""{"choix":["contre","abstention","pour"]}""")))
        assertEquals(listOf("pour", "abstention", "contre"), touchesVote(o("""{"choix":["pour","abstention","contre"]}""")))
        assertEquals(VOTES, touchesVote(o("""{"choix":["pour","contre"]}""")))
        assertEquals(VOTES, touchesVote(o("{}")))
    }

    @Test fun long_dates() {
        assertEquals("27 novembre 2025", dateLongue("2025-11-27"))
        assertEquals("1 janvier 2026", dateLongue("2026-01-01"))
        assertEquals("mars 2026", dateLongue("2026-03"))
        assertEquals("hier", dateLongue("hier"))
    }

    // ---------- Avant, après ----------

    private val crans = o(
        """{"crans":[{"n":0,"nom":"Constance","glose":"même position"},{"n":1,"nom":"Inflexion","glose":"position ajustée"},
        {"n":2,"nom":"Revirement","glose":"position abandonnée"}]}""",
    )

    @Test fun avant_apres_decodes_and_labels() {
        val g = AvantApres()
        val d = g.decoder(o("""{"bonne":2}"""))!!
        val m = Manche("exemple-euro", "Parti · Sujet", "2017 : position d'hier", o("""{"bonne":2}"""))
        assertEquals("Revirement", g.etiquette(m, d, MancheEtat(m), crans))
        assertNull(g.etiquette(m, d, MancheEtat(m), o("{}")))
        assertNull(g.decoder(o("""{"bonne":3}""")))
        assertNull(g.decoder(o("""{"bonne":-1}""")))
        assertNull(g.decoder(o("{}")))
    }

    @Test fun crans_by_rank_whatever_order() {
        val c = cransAA(o("""{"crans":[{"n":2,"nom":"R"},{"n":0,"nom":"C"},"x"]}"""))
        assertEquals(listOf("C", "", "R"), c.map { it.nom })
        assertEquals("position ajustée", cransAA(crans)[1].glose)
    }

    // ---------- Sur la carte ----------

    private val ville = o(
        """{"choix":[{"ville":"Brest","lat":48.4085,"lon":-4.4996,"region":"53","regionNom":"Bretagne"},
        {"ville":"Vierzon","lat":47.2351,"lon":2.0776,"region":"24","regionNom":"Centre-Val de Loire"},
        {"ville":"Liévin","lat":50.4231,"lon":2.7702,"region":"32","regionNom":"Hauts-de-France"},
        {"ville":"Clermont-Ferrand","lat":45.787,"lon":3.1127,"region":"84","regionNom":"Auvergne-Rhône-Alpes"}],"bonne":2}""",
    )
    private val outreMer = o(
        """{"choix":[{"ville":"Pointe-à-Pitre","lat":16.2351,"lon":-61.5379,"region":"01","regionNom":"Guadeloupe"},
        {"ville":"Matoury","lat":4.8281,"lon":-52.3498,"region":"03","regionNom":"Guyane"},
        {"ville":"Mamoudzou","lat":-12.7875,"lon":45.1964,"region":"06","regionNom":"Mayotte"},
        {"ville":"Le Lamentin","lat":14.6231,"lon":-60.9923,"region":"02","regionNom":"Martinique"}],"bonne":2}""",
    )

    @Test fun ville_decodes_real_rounds() {
        val g = LaVille()
        assertEquals(2, g.decoder(ville)!!.bonne)
        assertEquals("Mamoudzou", g.decoder(outreMer)!!.let { it.choix[it.bonne].ville })
    }

    @Test fun ville_refuses_bad_rounds() {
        val g = LaVille()
        assertNull(g.decoder(o("""{"choix":[{"ville":"A","lat":1,"lon":1,"region":"05"},{"ville":"B","lat":1,"lon":1,"region":"53"}],"bonne":0}""")))
        assertNull(g.decoder(o("""{"choix":[{"ville":"A","lon":1,"region":"53"},{"ville":"B","lat":1,"lon":1,"region":"53"}],"bonne":0}""")))
        assertNull(g.decoder(o("""{"choix":[{"ville":"","lat":1,"lon":1,"region":"53"},{"ville":"B","lat":1,"lon":1,"region":"53"}],"bonne":0}""")))
        assertNull(g.decoder(o("""{"choix":[{"ville":"A","lat":1,"lon":1,"region":"53"}],"bonne":0}""")))
        assertNull(g.decoder(JsonObject(ville + ("bonne" to PolJson.parseToJsonElement("4")))))
    }

    @Test fun northern_pins_stay_whole() {
        val d = LaVille().decoder(ville)!!
        // Pointe la plus au nord du fond : rangée 0 ; une épingle fait 7 cellules de haut.
        assertEquals(0f, pointeMetropole(PROJ_DEFAUT, PROJ_DEFAUT.maxy, 0.0).y, 1e-4f)
        val m = margeHaute(PROJ_DEFAUT, d.choix)
        assertTrue(d.choix.all { pointeMetropole(PROJ_DEFAUT, it.lat, it.lon).y + m >= 7 })
        assertEquals(3, margeHaute(PROJ_DEFAUT, d.choix.filter { it.lat < 49 }))
        assertEquals(7, margeHaute(PROJ_DEFAUT, listOf(d.choix[0].copy(lat = PROJ_DEFAUT.maxy))))
        assertEquals(3, margeHaute(PROJ_DEFAUT, LaVille().decoder(outreMer)!!.choix))
    }

    @Test fun pins_land_inside_map_or_inset() {
        val s = 3
        val ecart = 4
        val oy = 3
        LaVille().decoder(ville)!!.choix.forEach {
            val p = pointe(it, PROJ_DEFAUT, s, oy, ecart)
            assertTrue(it.ville, p.x in 0f..(CARTE_W * s).toFloat() && p.y in 0f..((CARTE_H + oy) * s).toFloat())
        }
        val xs = encartsX(s, ecart)
        LaVille().decoder(outreMer)!!.choix.forEach { e ->
            val k = ENCARTS.indexOfFirst { it.code == e.region }
            val p = pointe(e, PROJ_DEFAUT, s, oy, ecart)
            val top = (CARTE_H + oy) * s + ecart
            assertTrue(e.ville, p.x in xs[k].toFloat()..(xs[k] + ENCARTS[k].w * s).toFloat())
            assertTrue(e.ville, p.y in top.toFloat()..(top + ENCART_H * s).toFloat())
        }
    }

    @Test fun magnifier_snaps_to_nearest_pin() {
        val pts = listOf(Offset(10f, 10f), Offset(100f, 10f), Offset(10f, 100f))
        assertEquals(1, plusProche(pts, 90f, 5f, 0f))
        assertEquals(2, plusProche(pts, 0f, 200f, 0f))
        // Distance mesurée à la tête, au-dessus de la pointe.
        assertEquals(0, plusProche(pts, 10f, 0f, 10f))
        assertEquals(0, plusProche(emptyList(), 0f, 0f, 0f))
    }

    @Test fun grid_samples_cell_centres() {
        val carre = parsePath("M0 0L30 0L30 30L0 30Z")
        val g = grilleRegions(listOf(RegionGeo("53", "Région A", carre, boundsOf(carre))))
        assertEquals(CARTE_W * CARTE_H, g.size)
        assertEquals(listOf("53", "53", null), listOf(g[0], g[1], g[2]))
        assertEquals(listOf("53", "53", null), listOf(g[CARTE_W], g[CARTE_W + 1], g[2 * CARTE_W]))
        assertEquals(4, g.count { it != null })
    }

    @Test fun neighbours_and_insets_tinted() {
        assertEquals(3, teinte("06"))
        assertTrue(teinte("99") in 0..3)
        assertTrue(teinte("53") != teinte("52"))
    }

    @Test fun magnifier_sprite() {
        assertEquals(17, LOUPE.size)
        assertTrue(LOUPE.all { it.length == 17 })
        assertEquals('v', LOUPE[6][6])
        assertEquals('M', LOUPE[14][15])
    }

    @Test fun registry_holds_retrace_games() {
        assertTrue(JEUX_REGISTRE["le-vote"] is LeVote)
        assertTrue(JEUX_REGISTRE["avant-apres"] is AvantApres)
        assertTrue(JEUX_REGISTRE["la-ville"] is LaVille)
    }
}
