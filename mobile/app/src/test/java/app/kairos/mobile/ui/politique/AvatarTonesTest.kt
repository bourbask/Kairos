package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.theme.SceneColors
import app.kairos.mobile.ui.theme.ThemePalette
import app.kairos.mobile.ui.theme.contrast
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

}
