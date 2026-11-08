package app.kairos.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayFrameTest {
    private val w = 1000f
    private val h = 2000f
    private val card = Frame(40f, 600f, 960f, 780f, 50f)
    private val eps = 0.01f

    private fun assertFrame(expected: Frame, actual: Frame) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
        assertEquals(expected.corner, actual.corner, eps)
    }

    @Test
    fun morph_starts_on_source_and_ends_full_screen() {
        assertFrame(card, morph(0f, card, w, h))
        assertFrame(Frame(0f, 0f, w, h, 0f), morph(1f, card, w, h))
    }

    @Test
    fun morph_grows_monotonically() {
        var last = morph(0f, card, w, h)
        for (i in 1..20) {
            val f = morph(i / 20f, card, w, h)
            assertTrue(f.left <= last.left && f.top <= last.top && f.right >= last.right && f.bottom >= last.bottom)
            last = f
        }
    }

    @Test
    fun slide_enters_from_the_end_edge() {
        assertFrame(Frame(w, 0f, 2 * w, h, 0f), slide(0f, w, h, towardsLeft = true))
        assertFrame(Frame(-w, 0f, 0f, h, 0f), slide(0f, w, h, towardsLeft = false))
        assertFrame(Frame(0f, 0f, w, h, 0f), slide(1f, w, h, towardsLeft = true))
    }

    @Test
    fun back_gesture_shrinks_shifts_and_rounds() {
        val full = Frame(0f, 0f, w, h, 0f)
        assertFrame(full, backGesture(full, 0f, fromLeft = true, maxShift = 30f, maxCorner = 80f))
        val left = backGesture(full, 1f, fromLeft = true, maxShift = 30f, maxCorner = 80f)
        assertEquals(w * 0.9f, left.width, eps)
        assertEquals(h * 0.9f, left.bottom - left.top, eps)
        assertEquals(80f, left.corner, eps)
        // Geste depuis la gauche : le panneau s'écarte vers la droite, et inversement.
        assertEquals(w / 2 + 30f, (left.left + left.right) / 2, eps)
        val right = backGesture(full, 1f, fromLeft = false, maxShift = 30f, maxCorner = 80f)
        assertEquals(w / 2 - 30f, (right.left + right.right) / 2, eps)
    }

    @Test
    fun back_shift_follows_material_and_never_goes_negative() {
        assertEquals(1080f / 20f - 8f * 2.625f, backShift(1080f, 2.625f), eps)
        assertEquals(0f, backShift(100f, 3f), eps)
    }

    @Test
    fun fades_are_bounded_and_ordered() {
        assertEquals(0f, containerAlpha(0f), eps)
        assertEquals(1f, containerAlpha(0.2f), eps)
        assertEquals(0f, contentAlpha(0.1f), eps)
        assertEquals(1f, contentAlpha(1f), eps)
        assertEquals(1f, colorFraction(1f), eps)
        // Le conteneur couvre la source avant que le contenu n'apparaisse.
        assertTrue(containerAlpha(0.15f) == 1f && contentAlpha(0.15f) == 0f)
    }

    @Test
    fun recede_scales_the_screen_below() {
        assertEquals(1f, recede(0f), eps)
        assertEquals(1f - RECEDE, recede(1f), eps)
    }
}
