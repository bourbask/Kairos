package app.kairos.mobile.ui.mascot

import androidx.compose.ui.graphics.toArgb
import app.kairos.mobile.ui.theme.SceneColors
import app.kairos.mobile.ui.theme.ThemePalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le portage reproduit la maquette validée case pour case : images de référence
 * produites par la maquette (sprites-golden.json), comparées par rôle de pixel.
 */
class SpriteFidelityTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("sprites-golden.json")!!.readText(),
    ).jsonObject

    /** Une suite de cases vides est notée par sa longueur. */
    private fun decode(rows: List<String>) = rows.map { r -> r.replace(Regex("\\d+")) { ".".repeat(it.value.toInt()) } }

    private fun rows(a: kotlinx.serialization.json.JsonElement) = decode(a.jsonArray.map { it.jsonPrimitive.content })

    private val ids = mapOf(
        "kairos" to MascotKind.Kairos, "galet" to MascotKind.Lithos, "hulotte" to MascotKind.Glaux,
        "boulon" to MascotKind.Talos, "pepin" to MascotKind.Tosk, "flammeche" to MascotKind.Logi,
    )
    private val states = mapOf(
        "repos" to MascotState.Repos, "ecoute" to MascotState.Ecoute, "reflechit" to MascotState.Reflechit,
        "agit" to MascotState.Agit, "attend" to MascotState.Attend,
    )

    @Test
    fun mascot_frames_match_prototype() {
        var count = 0
        for ((id, byState) in golden["mascots"]!!.jsonObject) {
            val kind = ids.getValue(id)
            for ((st, frames) in byState.jsonObject) {
                val state = states.getValue(st)
                assertEquals("$id $st : nombre d'images", frames.jsonArray.size, kind.timing(state).n)
                frames.jsonArray.forEachIndexed { f, rows ->
                    assertEquals("$id $st image $f", rows(rows), kind.frame(state, f, pad = 2).rows())
                    count++
                }
            }
        }
        assertEquals(6, golden["mascots"]!!.jsonObject.size)
        assert(count > 100)
    }

    @Test
    fun scene_frames_match_prototype() {
        val scenes = golden["scenes"]!!.jsonObject
        assertEquals(8, scenes.size)
        for ((key, byMascot) in scenes) {
            val scene = SceneKind.entries.first { key.startsWith(it.name.lowercase()) }
            val down = key.endsWith("-baisse")
            for ((id, frames) in byMascot.jsonObject) {
                val kind = ids.getValue(id)
                assertEquals("$key : nombre d'images", frames.jsonArray.size, scene.ms.size)
                frames.jsonArray.forEachIndexed { i, fr ->
                    val t = fr.jsonObject["t"]!!.jsonPrimitive.int
                    assertEquals("$key $id image $i", rows(fr.jsonObject["rows"]!!), scene.frame(kind, i, t, down).flatten().rows())
                }
            }
        }
    }

    @Test
    fun scene_colors_match_prototype() {
        val all = golden["colors"]!!.jsonObject
        assertEquals("chaque palette, en clair et en sombre", ThemePalette.entries.size * 2, all.size)
        for ((key, roles) in all) {
            val (id, mode) = key.split('-')
            val palette = ThemePalette.entries.first { it.name.lowercase() == id.replace("è", "e") }
            val colors = SceneColors.of(palette, mode == "dark")
            for (role in SceneColors.ROLES) {
                // Écart voulu : bouton de Fougère sombre assombri (corps à 3:1), et les teintes qui en dérivent.
                if (key == "fougere-dark" && role in "Bhms") continue
                val hex = roles.jsonObject[role.toString()]!!.jsonPrimitive.content
                assertEquals("$key $role", hex.removePrefix("#").toLong(16).toInt() or 0xFF000000.toInt(), colors[role].toArgb())
            }
        }
    }
}
