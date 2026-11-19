package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.kit.evA11yLabel
import app.kairos.mobile.ui.politique.kit.noFicheA11y
import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.screens.Tier
import app.kairos.mobile.ui.politique.screens.back
import app.kairos.mobile.ui.politique.screens.levelViews
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Paliers des sources : niveaux lus dans les données, repli, palier affiché, cas « ? ». */
class NiveauxTest {
    private fun data(json: String) = PolData(mapOf("evaluateurs" to PolJson.parseToJsonElement(json).jsonObject))

    private val withLevels = data("""{
      "PRINCIPE": " Principe fictif. ",
      "provisoire": true,
      "NIVEAUX": [
        {"n":1,"libelle":"Un fictif","definition":"Déf 1","criteres":["c1","c2"]},
        {"n":2,"libelle":"Deux fictif","definition":"Déf 2"},
        {"n":3,"libelle":"Trois fictif","definition":"Déf 3","criteres":[]},
        {"n":4,"libelle":"Quatre fictif","definition":"Déf 4"},
        {"n":0,"libelle":"Zéro fictif","definition":"Déf 0"},
        {"n":7,"libelle":"Hors bornes"},
        {"n":2,"libelle":"Doublon"},
        "cassé"
      ],
      "EVALUATEURS": [
        {"id":"b","nom":"Beta","independance":3},
        {"id":"a","nom":"Alpha","independance":3},
        {"id":"z","nom":"Zeta"},
        {"id":"x","nom":"Hors","independance":9}
      ]}""")

    private val legacy = data("""{"EVALUATEURS": [{"id":"a","nom":"Alpha","independance":2}]}""")

    @Test fun levels_read_from_data_in_published_order() {
        assertEquals(listOf(1, 2, 3, 4, 0), withLevels.niveaux.map { it.n })
        assertEquals("Deux fictif", withLevels.levelLabel(2))
        assertEquals("Zéro fictif", withLevels.levelLabel(0))
        assertEquals(listOf("c1", "c2"), withLevels.niveaux[0].criteres)
        assertEquals("Principe fictif.", withLevels.principe)
        assertTrue(withLevels.levelsProvisional)
    }

    @Test fun missing_levels_fall_back_to_built_in_labels() {
        assertEquals(listOf(1, 2, 3, 4, 0), legacy.niveaux.map { it.n })
        assertEquals("Indépendant, orientation connue", legacy.levelLabel(2))
        assertEquals("Non qualifié", legacy.levelLabel(0))
        assertTrue(legacy.niveaux.all { it.definition.isEmpty() && it.criteres.isEmpty() })
        assertTrue(legacy.principe.startsWith("Le niveau décrit les liens"))
        assertFalse(legacy.levelsProvisional)
        // Libellé vide dans les données : repli sur le libellé intégré.
        assertEquals("Partisan ou militant", data("""{"NIVEAUX":[{"n":4,"libelle":" "}]}""").levelLabel(4))
    }

    @Test fun a11y_label_uses_data_label() {
        assertEquals(
            "Alpha, évaluateur. Indépendance niveau 3 sur 4 : Trois fictif. Ouvrir sa fiche",
            evA11yLabel(withLevels.evaluators.getValue("a"), withLevels),
        )
        assertTrue(evA11yLabel(Evaluator(nom = "N"), legacy).contains("non qualifiée"))
        assertEquals("Source fictive, source pas encore analysée. Indépendance non qualifiée. Ouvrir sa fiche", noFicheA11y("Source fictive"))
    }

    @Test fun level_views_list_producers_and_highlight_origin() {
        val v = levelViews(withLevels, hl = 3)
        assertEquals(listOf(1, 2, 3, 4, 0), v.map { it.niveau.n })
        assertEquals(listOf("Alpha", "Beta"), v[2].producers.map { it.nom })
        assertEquals(listOf(3), v.filter { it.highlighted }.map { it.niveau.n })
        // Sans niveau valable : rangé sous « non qualifié ».
        assertEquals(listOf("Hors", "Zeta"), v[4].producers.map { it.nom })
        assertTrue(v[0].producers.isEmpty())
        // Source sans fiche (« ? ») : le niveau mis en évidence est « non qualifié ».
        assertEquals(listOf(0), levelViews(withLevels, hl = 0).filter { it.highlighted }.map { it.niveau.n })
        assertTrue(levelViews(withLevels, hl = null).none { it.highlighted })
    }

    @Test fun back_goes_to_fiche_then_closes() {
        assertEquals(Tier.Fiche, Tier.Niveaux.back())
        assertNull(Tier.Fiche.back())
    }
}
