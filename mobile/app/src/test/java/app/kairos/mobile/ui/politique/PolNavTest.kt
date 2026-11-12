package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.LIST_KEY
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PolNavTest {
    private fun obj(s: String) = PolJson.parseToJsonElement(s).jsonObject

    private val data = PolData(
        mapOf(
            "identites" to obj(
                """{"$LIST_KEY": [
                {"id":"a","nom":"Candidat A","parti":"Parti Bleu","alias":["Cand. A"]},
                {"id":"parti-bleu","nom":"Parti Bleu","parti":"Parti Bleu","alias":["Bleus"]},
                {"id":"vert","nom":"Vert"}
            ]}""",
            ),
            "budgets" to obj(
                """{"REF": 100, "PROGRAMS": [
                {"who":"Candidat A","debt":"98.5","deficit":3.1},
                {"who":"Mme B","debt":120,"hh":-50},
                {"who":"Référence","debt":110,"ref":true}
            ]}""",
            ),
            "votes" to obj("""{"AN_V":[{"t":"Loi 1","c":[]},{"t":"Loi 2","c":[]}],"PE_V":[{"t":"Texte 3","c":[]}]}"""),
            "affirmations" to obj(
                """{"CAMPS":["Bleus","Verts"],"CLAIMS":[
                {"theme":"Énergie","camp":"Cand. A","q":"Phrase 1","v":"solide"},
                {"theme":"Énergie","camp":"Bleus","q":"Phrase 2","v":"faux"},
                {"theme":"Santé","camp":"Verts","q":"Phrase 3","v":"partiel"}
            ]}""",
            ),
            "argent" to obj("""{"PARTIS":[["Parti Bleu", 1.2, 3.4, null]]}"""),
        ),
    )

    private fun count(id: String, d: PolData = data) = RUBRICS.single { it.id == id }.count(d)

    @Test
    fun rubric_counts_from_data() {
        assertEquals("2 programmes", count("budgets"))
        assertEquals("3 scrutins", count("votes"))
        assertEquals("3 phrases vérifiées", count("phrases"))
        assertEquals("2 thèmes · 2 camps", count("bulle"))
        assertEquals("aucune donnée", count("villes"))
        // Rien de chargé : chaque tuile reste lisible.
        val empty = PolData(emptyMap())
        RUBRICS.filter { it.id != "bulle" }.forEach { assertEquals(it.id, "aucune donnée", it.count(empty)) }
        assertEquals("aucune donnée · aucune donnée", bubbleLine(empty))
    }

    @Test
    fun identity_stats_skip_missing() {
        val a = identityStats(data, "a")
        assertEquals(listOf("Dette en 2032", "Phrases vérifiées"), a.map { it.label })
        assertEquals("98,5 %", a[0].value)
        assertEquals("sous la référence", a[0].sub)
        assertEquals("1 solide · 0 fragile ou faux", a[1].sub)
        // Situation nette absente (null) : pas de chiffre ; le parti a sa phrase.
        val p = identityStats(data, "parti-bleu")
        assertEquals(listOf("Phrases vérifiées"), p.map { it.label })
        assertEquals("0 solide · 1 fragile ou faux", p[0].sub)
        assertTrue(identityStats(data, "inconnu").isEmpty())
        assertTrue(identityStats(PolData(emptyMap()), "a").isEmpty())
        // Rattachements : programme + phrase pour la personne.
        assertEquals(2, identityLinks(data, "a").size)
        assertEquals(2, identityLinks(data, "parti-bleu").size)
    }

    @Test
    fun back_pops_route_then_closes() {
        val s = PolBackStack().go(PolRoute.Rubric("votes")).open(PolSheetState.Identity("a")).open(PolSheetState.Identity("parti-bleu"))
        assertEquals(PolRoute.Rubric("votes"), s.top)
        val one = s.back()!!
        assertEquals(listOf(PolSheetState.Identity("a")), one.sheets)
        val noSheet = one.back()!!
        assertTrue(noSheet.sheets.isEmpty())
        assertEquals(PolRoute.Rubric("votes"), noSheet.top)
        val home = noSheet.back()!!
        assertEquals(PolRoute.Home, home.top)
        assertNull("depuis l'accueil nu : l'espace se ferme", home.back())
        // Une vue ouverte depuis une feuille la remplace.
        val fromSheet = PolBackStack().open(PolSheetState.Identity("a")).go(PolRoute.Rubric("dit-vote"))
        assertTrue(fromSheet.sheets.isEmpty())
        assertEquals(PolBackStack(), fromSheet.back())
    }

    @Test
    fun same_route_twice_is_not_stacked() {
        val s = PolBackStack().go(PolRoute.Rubric("votes")).go(PolRoute.Rubric("votes"))
        assertEquals(listOf<PolRoute>(PolRoute.Rubric("votes")), s.routes)
        assertEquals(PolRoute.Home, s.back()!!.top)
    }

    @Test
    fun decode_is_memoized() {
        val a = data.decode<Claim>("affirmations", "CLAIMS")
        assertSame(a, data.decode<Claim>("affirmations", "CLAIMS"))
        assertEquals(3, a.size)
        assertEquals("autre sérialiseur, autre liste", 3, data.decode("affirmations", "CLAIMS", JsonObject.serializer()).size)
    }

    @Test
    fun home_offline_offers_retry_and_site() {
        assertEquals(listOf(HomeAction.Retry, HomeAction.OpenSite), homeActions(PolLoad.Down("Hors ligne.")))
        assertTrue(homeActions(PolLoad.Loading).isEmpty())
        assertFalse(HomeAction.Retry in homeActions(PolLoad.Ready(data)))
    }
}
