package app.kairos.mobile.ui.mascot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scènes de l'espace Politique (gravité, paliers) : images de référence produites
 * par la maquette validée (scenes-politique-golden.json, personnage Kairos),
 * comparées par rôle de pixel.
 */
class PolitiqueScenesTest {

    private val scenes = mapOf(
        "repos" to SceneKind.Repos, "zen" to SceneKind.Zen, "interrogation" to SceneKind.Interrogation,
        "juge" to SceneKind.Juge, "panique" to SceneKind.Panique,
        "premier-coup" to SceneKind.PremierCoup, "moitie" to SceneKind.Moitie, "complet" to SceneKind.Complet,
    )

    private fun decode(rows: List<String>) = rows.map { r -> r.replace(Regex("\\d+")) { ".".repeat(it.value.toInt()) } }

    @Test
    fun frames_match_prototype() {
        val golden = Json.parseToJsonElement(javaClass.classLoader!!.getResource("scenes-politique-golden.json")!!.readText()).jsonObject
        assertEquals(scenes.keys, golden.keys)
        for ((name, frames) in golden) {
            val scene = scenes.getValue(name)
            assertEquals("$name : nombre d'images", frames.jsonArray.size, scene.ms.size)
            frames.jsonArray.forEachIndexed { i, fr ->
                val t = fr.jsonObject["t"]!!.jsonPrimitive.int
                val want = decode(fr.jsonObject["rows"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertEquals("$name image $i", want, scene.frame(MascotKind.Kairos, i, t).flatten().rows())
            }
        }
    }

    @Test
    fun every_mascot_plays_every_frame() {
        for (scene in scenes.values) {
            assertTrue("$scene : image clé", scene.key in scene.ms.indices)
            for (kind in MascotKind.entries) for (i in scene.ms.indices) {
                val fr = scene.frame(kind, i, 0)
                assertTrue("$scene $kind $i", fr.mascot.cells.any { it != Grid.EMPTY })
            }
        }
    }

    @Test
    fun gravity_levels_map_to_scenes() {
        assertEquals(
            listOf(SceneKind.Repos, SceneKind.Zen, SceneKind.Interrogation, SceneKind.Juge, SceneKind.Panique),
            (0..4).map(::gravityScene),
        )
        assertEquals(SceneKind.Repos, gravityScene(-3))
        assertEquals(SceneKind.Panique, gravityScene(9))
    }
}
