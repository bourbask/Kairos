package app.kairos.mobile.ui.mascot

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotTest {

    private fun body(p: List<Pixel>) = p.filter { it.role == PixelRole.Corps }

    @Test
    fun sprite_has_forelock_and_wings() {
        for (state in MascotState.entries) {
            val p = MascotSprite.pixels(state, 0)
            val corps = body(p)
            val meche = p.filter { it.role == PixelRole.Meche }
            val ailes = p.filter { it.role == PixelRole.Aile }
            val pieds = p.filter { it.role == PixelRole.Pied }
            assertTrue("$state : mèche", meche.isNotEmpty())
            assertTrue("$state : mèche au-dessus du corps", meche.all { it.y < corps.minOf { c -> c.y } })
            assertTrue("$state : pieds", pieds.isNotEmpty())
            assertTrue("$state : aile à gauche", ailes.any { it.x < pieds.minOf { f -> f.x } })
            assertTrue("$state : aile à droite", ailes.any { it.x > pieds.maxOf { f -> f.x } })
        }
    }

    @Test
    fun eyes_change_with_state() {
        fun eyes(s: MascotState) =
            MascotSprite.pixels(s, 0).filter { it.role == PixelRole.Oeil }.map { it.x to it.y }.toSet()
        assertNotEquals(eyes(MascotState.Repos), eyes(MascotState.Ecoute))
    }

    @Test
    fun sprite_within_grid() {
        for (state in MascotState.entries) for (frame in 0 until 32) {
            assertTrue(MascotSprite.pixels(state, frame).all { it.x in 0..15 && it.y in 0..15 })
        }
    }
}

class MascotDescriptionTest {
    @Test
    fun state_is_told_in_words() {
        org.junit.Assert.assertEquals("à l'écoute", mascotDescription(MascotState.Ecoute, 0))
        org.junit.Assert.assertEquals("réfléchit", mascotDescription(MascotState.Reflechit, 0))
        org.junit.Assert.assertEquals("réponse arrivée", mascotDescription(MascotState.Agit, 0))
        org.junit.Assert.assertEquals("3 décisions en attente", mascotDescription(MascotState.Attend, 3))
        org.junit.Assert.assertEquals("1 décision en attente", mascotDescription(MascotState.Attend, 1))
        org.junit.Assert.assertEquals("au repos", mascotDescription(MascotState.Repos, 0))
    }
}
