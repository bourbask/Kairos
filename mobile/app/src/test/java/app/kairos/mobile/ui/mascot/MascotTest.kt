package app.kairos.mobile.ui.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotTest {

    private fun cells(state: MascotState, f: Int, role: Char): List<Pair<Int, Int>> {
        val g = MascotKind.Kairos.frame(state, f)
        return (0 until 16).flatMap { y -> (0 until 16).filter { x -> g[x, y] == role }.map { it to y } }
    }

    @Test
    fun kairos_has_forelock_and_wings() {
        for (state in MascotState.entries) {
            val body = cells(state, 0, 'F')
            val wings = cells(state, 0, 'h').filter { (_, y) -> y > 10 }
            assertTrue("$state : mèche au-dessus du corps", body.any { (_, y) -> y < 5 })
            assertTrue("$state : aile à gauche", wings.any { (x, _) -> x < 4 })
            assertTrue("$state : aile à droite", wings.any { (x, _) -> x > 11 })
        }
    }

    @Test
    fun eyes_change_with_state() {
        assertNotEquals(cells(MascotState.Repos, 0, 'D'), cells(MascotState.Ecoute, 0, 'D'))
    }

    @Test
    fun scene_rest_counter_repeats_without_one_off_squash() {
        assertEquals(9L, restFrame(9, 24))
        assertEquals(24L + 9, restFrame(24 + 9, 24))
        assertEquals(24L + 9, restFrame(5 * 24 + 9, 24))
        // Après le premier tour, chaque image de repos se redessine comme au premier tour, sauf l'écrasement.
        val kind = MascotKind.Lithos
        val p = kind.restPeriod()
        for (f in 0 until p) {
            val first = kind.frame(MascotState.Repos, f).rows()
            val later = kind.frame(MascotState.Repos, f + p).rows()
            if (f == 9) assertNotEquals(first, later) else assertEquals(first, later)
        }
        for (other in MascotKind.entries) for (f in 0 until other.restPeriod()) {
            if (other != MascotKind.Lithos) assertEquals("$other $f", other.frame(MascotState.Repos, f).rows(), other.frame(MascotState.Repos, f + other.restPeriod()).rows())
        }
    }

    @Test
    fun scene_cache_keeps_static_layers_once_per_frame() {
        val scene = SceneKind.Finances
        val kind = MascotKind.Kairos
        for (i in scene.ms.indices) for (t in 0 until 2 * kind.restPeriod()) SpriteCache.scene(scene, false, kind, i, t)
        val (statics, mascots) = SpriteCache.sceneSizes()
        assertEquals(scene.ms.size, statics)
        assertEquals(scene.ms.size * 2 * kind.restPeriod(), mascots)
        // Une autre scène remplace la précédente.
        SpriteCache.scene(SceneKind.Agenda, false, kind, 0, 0)
        assertEquals(1 to 1, SpriteCache.sceneSizes())
        // Mêmes calques qu'un calcul direct.
        val direct = scene.frame(kind, 3, 5)
        val cached = SpriteCache.scene(scene, false, kind, 3, 5)
        assertEquals(direct.back.cells.count { it != Grid.EMPTY }, cached[0].size)
        assertEquals(direct.mascot.cells.count { it != Grid.EMPTY }, cached[1].size)
        assertEquals(direct.front.cells.count { it != Grid.EMPTY }, cached[2].size)
    }

    @Test
    fun every_frame_is_drawn() {
        for (kind in MascotKind.entries) for (state in MascotState.entries) for (f in 0 until kind.timing(state).n) {
            assertTrue("$kind $state $f", kind.frame(state, f).cells.any { it != Grid.EMPTY })
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
