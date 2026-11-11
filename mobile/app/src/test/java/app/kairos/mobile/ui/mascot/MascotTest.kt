package app.kairos.mobile.ui.mascot

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
