package app.kairos.mobile.ui.politique

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.*
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MapLogicTest {
    private fun file(s: String) = PolJson.parseToJsonElement(s).jsonObject
    private fun data(vararg f: Pair<String, String>) = PolData(f.associate { (k, v) -> k to file(v) })
    private fun city(v: String, region: String, qui: String = "bleu", st: String = "conservee") = Commune(ville = v, lat = 46.0, lon = 2.0, region = region, qui = qui, statut = st)

    @Test fun parse_region_path() {
        val rings = parsePath("M10 10L50 10L50 50Z M60 60L70 60 L70.5 -2Z")
        assertEquals(2, rings.size)
        assertEquals(listOf(Offset(10f, 10f), Offset(50f, 10f), Offset(50f, 50f)), rings[0])
        assertEquals(Offset(70.5f, -2f), rings[1][2])
        assertEquals(Rect(10f, -2f, 70.5f, 60f), boundsOf(rings))
        assertTrue(contains(rings, Offset(45f, 20f)))
        assertFalse(contains(rings, Offset(15f, 40f)))
        // Commande hors contrat : le chemin s'arrête là, rien d'inventé.
        assertEquals(1, parsePath("M0 0L1 0L1 1Z C5 5 6 6 7 7").size)
        assertTrue(parsePath("").isEmpty() && parsePath("x y").isEmpty())
    }

    @Test fun project_matches_reference_point() {
        val p = Projection(lat0 = 46.5, k = 0.7, minx = -3.5, maxy = 51.0, s = 99.0)
        val at = project(p, 46.5, 2.0)
        assertEquals(485.1f, at.x, 1e-3f)
        assertEquals(445.5f, at.y, 1e-3f)
        assertEquals(Offset(0f, 0f), project(p, 51.0, -5.0))
    }

    @Test fun clusters_merge_overlapping_markers_only() {
        val a = Placed(city("A", "01"), Offset(0f, 0f))
        val b = Placed(city("B", "01"), Offset(10f, 0f))
        val c = Placed(city("C", "01"), Offset(100f, 0f))
        // 1 unité de carte par dp : A et B à 10 dp se chevauchent (seuil 13,3 dp), C reste seul.
        val near = clusterMarkers(listOf(a, b, c), unitsPerDp = 1f)
        assertEquals(2, near.size)
        assertEquals(listOf("A", "B"), near[0].cities.map { it.ville })
        assertEquals(Offset(5f, 0f), near[0].at)
        assertEquals(listOf("C"), near[1].cities.map { it.ville })
        // Zoom ×4 : 10 unités = 40 dp à l'écran, plus aucun chevauchement.
        assertEquals(3, clusterMarkers(listOf(a, b, c), unitsPerDp = 0.25f).size)
        // Dézoom : un groupe qui grossit absorbe aussi C (fusion répétée jusqu'à stabilité).
        assertEquals(1, clusterMarkers(listOf(a, b, c), unitsPerDp = 6f).size)
        assertTrue(clusterMarkers(emptyList(), 1f).isEmpty())
    }

    @Test fun region_bubbles_count_by_party() {
        val regions = listOf("r1", "r2", "r3")
        val placed = listOf(
            Placed(city("A1", "r1", "bleu"), Offset(0f, 0f)), Placed(city("A2", "r1", "vert"), Offset(30f, 0f)),
            Placed(city("A3", "r1", "bleu"), Offset(0f, 30f)), Placed(city("B1", "r2", "rouge"), Offset(50f, 50f)),
        )
        val b = regionBubbles(regions, placed)
        assertEquals(listOf(listOf("r1"), listOf("r2")), b.map { it.regions })
        assertEquals(Offset(10f, 10f), b[0].at)
        assertEquals(listOf("bleu" to 2, "vert" to 1), partyCounts(b[0].cities))
        assertEquals(listOf("rouge" to 1), partyCounts(b[1].cities))
    }

    @Test fun dom_codes_go_to_list() {
        assertTrue(isDom("04") && isDom("01"))
        assertFalse(isDom("11") || isDom("4") || isDom("") || isDom("004"))
        val d = data(
            "carte" to """{"viewBox":"0 0 100 100","projection":{"k":0.7,"minx":-3.5,"maxy":51,"s":99},"regions":[{"code":"11","nom":"Région A","d":"M0 0L9 0L9 9Z"},5]}""",
            "villes" to """{"COMMUNES":[{"ville":"Métropole","lat":46,"lon":2,"region":"11","qui":"bleu","statut":"gagnee"},
                {"ville":"Île","lat":-21,"lon":55,"region":"04","qui":"bleu","statut":"conservee"},
                {"ville":"Sans position","region":"11","qui":"vert","statut":"perdue"},
                {"ville":"","region":"11"},"x"]}""",
        )
        val m = villesModel(d)
        assertEquals(listOf("Métropole", "Île", "Sans position"), m.communes.map { it.ville })
        assertEquals(listOf("Métropole"), m.placed.map { it.c.ville })
        assertEquals(listOf("Île"), outreMer(m.communes).map { it.ville })
        assertEquals(listOf("Sans position"), m.unmapped.map { it.ville })
        assertEquals(Rect(0f, 0f, 100f, 100f), m.viewBox)
        assertEquals(listOf("11"), m.regions.map { it.code })
    }

    @Test fun unmapped_cities_are_listed_not_lost() {
        val l = listOf(city("Posée", "11"), city("Région inconnue", "99"), city("Sans région", ""), city("Île", "04"),
            Commune(ville = "Sans coordonnées", region = "11", lat = 45.0))
        assertEquals(listOf("Région inconnue", "Sans région", "Sans coordonnées"), unmapped(l, setOf("11")).map { it.ville })
        // Sans fond de carte : toute la métropole va dans la liste, l'outre-mer reste à part.
        assertEquals(4, unmapped(l, emptySet()).size)
        val noMap = villesModel(data("villes" to """{"COMMUNES":[{"ville":"V","insee":"1","lat":46,"lon":2,"region":"11"}]}"""))
        assertTrue(noMap.placed.isEmpty() && !noMap.hasMap)
        assertEquals(listOf("1"), noMap.unmapped.map { it.key })
        assertEquals("Sans code", Commune(ville = "Sans code").key)
    }

    @Test fun region_bubbles_never_overlap() {
        val placed = listOf(
            Placed(city("A1", "r1", "bleu"), Offset(0f, 0f)), Placed(city("A2", "r1", "vert"), Offset(0f, 0f)),
            Placed(city("B1", "r2", "rouge"), Offset(15f, 0f)), Placed(city("C1", "r3", "bleu"), Offset(200f, 0f)),
        )
        val bubbles = regionBubbles(listOf("r1", "r2", "r3"), placed)
        // 1 unité par dp : r1 (rayon 9,4 + 2,3) et r2 (8,95 + 2,3) à 15 dp se chevauchent ; r3 reste seule.
        val m = mergeOverlapping(bubbles, unitsPerDp = 1f)
        assertEquals(listOf(listOf("r1", "r2"), listOf("r3")), m.map { it.regions })
        assertEquals(3, m[0].cities.size)
        assertEquals(Offset(5f, 0f), m[0].at) // moyenne pondérée par les villes
        assertEquals(3, mergeOverlapping(bubbles, unitsPerDp = 0.5f).size)
    }

    @Test fun villes_survive_empty_and_wrong_types() {
        val empty = villesModel(PolData(emptyMap()))
        assertTrue(empty.communes.isEmpty() && empty.placed.isEmpty() && empty.regions.isEmpty() && !empty.hasMap)
        assertTrue(cityFactsOf(PolData(emptyMap())).isEmpty() && factSections(PolData(emptyMap())).isEmpty() && muniOf(PolData(emptyMap())).isEmpty())
        val bad = data("carte" to """{"viewBox":3,"projection":"x","regions":{"a":1}}""", "villes" to """{"COMMUNES":{"a":1},"VRN":[1,[null]],"VECO":"x","MUNI":[3]}""")
        val m = villesModel(bad)
        assertTrue(m.communes.isEmpty() && !m.hasMap)
        assertTrue(cityFactsOf(bad).isEmpty() && factSections(bad).isEmpty() && muniOf(bad).isEmpty())
    }

    @Test fun fact_sections_by_identity() {
        val d = data(
            "identites" to """{"_liste":[{"id":"bleu","nom":"Parti Bleu","parti":"Parti Bleu","alias":["Maire A (PB)","Commun"]},
                {"id":"vert","nom":"Parti Vert","parti":"Parti Vert","alias":["Maire B","Commun"]},
                {"id":"rouge","nom":"Parti Rouge","parti":"Parti Rouge"}]}""",
            "villes" to """{"VRN":[["Ville 1","Maire A (PB)","Fait 1","causal","Src","https://example.org/1"],["Ville 1","Maire A (PB)","Fait 2","cont","Src","https://example.org/2"]],
                "VECO":[["Ville 2","Maire B","Fait 3","desc","Src","https://example.org/3"],["Ville 3","Commun","Fait 4","cont","",""]],
                "MUNI":[{"t":"Parti Rouge","stg":["proc","10 villes"],"p":"<b>Gagnées :</b> …"},{"t":"Parti Bleu","p":"x"}]}""",
        )
        val s = factSections(d)
        assertEquals(listOf("bleu", "vert", null, "rouge"), s.map { it.id })
        assertEquals(listOf("Ville 1"), s[0].cities.map { it.first })
        assertEquals(2, s[0].cities[0].second.size)
        // Alias partagé : jamais rattaché à l'une au hasard.
        assertEquals(listOf("Ville 3"), s[2].cities.map { it.first })
        assertTrue(s[3].cities.isEmpty())
        assertEquals(2, factsFor(cityFactsOf(d), "Ville 1").size)
        assertEquals("Décision de justice" to "solide", factKind("causal"))
    }

    @Test fun party_labels_and_status() {
        val d = data("identites" to """{"_liste":[{"id":"bleu","nom":"Parti Bleu","parti":"Parti Bleu"},{"id":"long","nom":"Un nom de parti vraiment long","parti":"PL"},{"id":"cand","nom":"Candidat A","parti":"Parti Bleu"}]}""")
        assertEquals("Parti Bleu", quiLabel(d, "bleu"))
        assertEquals("PL", quiLabel(d, "long"))
        assertEquals("Candidat A", quiLabel(d, "cand"))
        assertEquals("Divers droite", quiLabel(d, "divers-droite"))
        assertEquals("Liste locale", quiLabel(d, "Liste locale"))
        assertEquals("Gagnée", statusLabel("gagnee")); assertEquals(STATUS_UNKNOWN, statusLabel("code-brut")); assertEquals(STATUS_UNKNOWN, statusLabel(""))
        val win = regionWindow(Rect(0f, 0f, 100f, 50f))
        assertEquals(Rect(-12f, -12f, 112f, 62f), win)
    }
}
