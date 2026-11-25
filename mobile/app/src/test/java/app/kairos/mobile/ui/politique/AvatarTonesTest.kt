package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.theme.SceneColors
import app.kairos.mobile.ui.theme.ThemePalette
import app.kairos.mobile.ui.theme.contrast
import app.kairos.mobile.ui.theme.mix
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarTonesTest {
    private fun scenes() = ThemePalette.entries.flatMap { p -> listOf(false, true).flatMap { d -> listOf(SceneColors.of(p, d), SceneColors.onPage(p, d)) } }

    private fun pack(role: Char) = AVATAR_ROLES.indexOf(role) shl 16

    @Test fun deeper_tone_is_darker_and_keeps_eyes_readable() {
        scenes().forEach { s ->
            val body = avatarColor(pack('F'), s, s['A'])
            val deep = avatarColor(pack('G'), s, s['A'])
            val eye = avatarColor(pack('D'), s, s['A'])
            assertTrue("plus foncé que le corps", deep.luminance() < body.luminance())
            assertTrue("yeux lisibles sur la teinte foncée", contrast(eye, deep) >= 3.0)
            assertTrue("ombre de la teinte foncée encore plus foncée ou proche", avatarColor(pack('H'), s, s['A']) != deep)
        }
    }

    @Test fun new_roles_are_accepted() {
        val row = "GGHHrr" + ".".repeat(10)
        val img = JsonArray(List(16) { JsonPrimitive(row) })
        assertNotNull(avatarOf(JsonObject(mapOf("images" to JsonArray(listOf(img))))))
        assertEquals(HAIR_COLORS['r'], avatarColor(pack('r'), scenes().first(), scenes().first()['A']))
    }

    @Test fun roles_packed_by_index() {
        assertEquals("FhDACgbyrGHPp", AVATAR_ROLES)
        AVATAR_ROLES.forEachIndexed { i, c -> assertEquals(c, AVATAR_ROLES[pack(c) shr 16]); assertEquals(i, pack(c) shr 16) }
    }

    private fun skinAvatar(peau: JsonPrimitive?): Avatar? {
        val img = JsonArray(List(16) { JsonPrimitive("PPpp" + ".".repeat(12)) })
        val m = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("images" to JsonArray(listOf(img)))
        if (peau != null) m["peau"] = peau
        return avatarOf(JsonObject(m))
    }

    @Test fun skin_tone_from_peau() {
        val s = scenes().first()
        val a = skinAvatar(JsonPrimitive(3))!!
        assertEquals(SKIN_COLORS[2], avatarColor(pack('P'), s, s['A'], a.skin))
        assertEquals(mix(SKIN_COLORS[2], s['B'], 0.3f), avatarColor(pack('p'), s, s['A'], a.skin))
        assertEquals(SKIN_COLORS[0], skinAvatar(JsonPrimitive(1))!!.skin)
        assertEquals(SKIN_COLORS[5], skinAvatar(JsonPrimitive(6))!!.skin)
    }

    @Test fun skin_without_valid_peau_falls_back_to_body() {
        val s = scenes().first()
        listOf(null, JsonPrimitive(0), JsonPrimitive(7), JsonPrimitive(2.5), JsonPrimitive("3")).forEach { peau ->
            val a = skinAvatar(peau)
            assertNotNull("peau invalide : avatar gardé", a)
            assertEquals(null, a!!.skin)
            assertEquals(s['F'], avatarColor(pack('P'), s, s['A'], a.skin))
            assertEquals(s['h'], avatarColor(pack('p'), s, s['A'], a.skin))
        }
    }

}
