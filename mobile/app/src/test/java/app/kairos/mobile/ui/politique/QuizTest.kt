package app.kairos.mobile.ui.politique

import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.screens.*
import org.junit.Assert.*
import org.junit.Test

class QuizTest {
    @Test fun expected_answer_per_verdict() {
        assertEquals(Guess.Vrai, expectedAnswer("vrai"))
        assertEquals(Guess.PlutotVrai, expectedAnswer("plutot-vrai"))
        listOf("trompeur", "plutot-faux", "non-prouve").forEach { assertEquals(Guess.PlutotFaux, expectedAnswer(it)) }
        // Anciens ids.
        assertEquals(Guess.Vrai, expectedAnswer("solide"))
        assertEquals(Guess.PlutotVrai, expectedAnswer("partiel"))
        assertEquals(Guess.PlutotFaux, expectedAnswer("fragile"))
        assertEquals(Guess.Faux, expectedAnswer("faux"))
        assertNull(expectedAnswer("non-documente"))
        assertNull(expectedAnswer("bizarre"))
    }

    @Test fun swipe_direction_thresholds() {
        assertEquals(Guess.Vrai, swipeDirection(101f, 0f, 0f, 0f))
        assertEquals(Guess.Faux, swipeDirection(-101f, 10f, 0f, 0f))
        assertNull(swipeDirection(60f, 0f, 0f, 0f))
        // Vitesse franche après un début de course ; sans course, rien.
        assertEquals(Guess.Vrai, swipeDirection(40f, 0f, 0.6f, 0f))
        assertNull(swipeDirection(20f, 0f, 0.9f, 0f))
        assertEquals(Guess.Faux, swipeDirection(-40f, 0f, -0.6f, 0f))
        assertEquals(Guess.PlutotVrai, swipeDirection(0f, -91f, 0f, 0f))
        assertEquals(Guess.PlutotFaux, swipeDirection(10f, 91f, 0f, 0f))
        assertEquals(Guess.PlutotFaux, swipeDirection(0f, 40f, 0f, 0.6f))
        assertNull(swipeDirection(0f, 80f, 0f, 0f))
        // Trop de côté pour monter ou descendre.
        assertNull(swipeDirection(85f, -120f, 0f, 0f))
        assertTrue(swipeToNext(91f, 0f)); assertTrue(swipeToNext(0f, -0.6f)); assertFalse(swipeToNext(50f, 0.2f))
    }

    @Test fun progress_reaches_end_on_last_answer() {
        assertEquals(0f, quizProgress(0, 5, false).fraction, 0f)
        assertEquals(0.6f, quizProgress(3, 5, false).fraction, 1e-6f)
        // 5/5 : dernière réponse donnée, barre pleine, résultat pas encore atteint.
        val last = quizProgress(5, 5, false)
        assertEquals(1f, last.fraction, 0f); assertFalse(last.resultReached)
        assertTrue(quizProgress(5, 5, true).resultReached)
        assertEquals(0f, quizProgress(0, 0, false).fraction, 0f)
    }

    @Test fun near_feedback_for_trompeur_answered_vrai() {
        assertEquals(Tone.Ok, quizFeedback("trompeur", Guess.PlutotFaux).tone)
        val f = quizFeedback("trompeur", Guess.Faux)
        assertEquals(Tone.Near, f.tone)
        assertTrue(f.text.startsWith("Presque"))
        assertEquals(Tone.Near, quizFeedback("vrai", Guess.PlutotVrai).tone)
        assertEquals(Tone.Ko, quizFeedback("trompeur", Guess.Vrai).tone)
        assertEquals("Vous aviez dit « Faux ».", quizFeedback("vrai", Guess.Faux).text)
    }

    private fun c(theme: String, camp: String, v: String) = Claim(theme = theme, camp = camp, v = v)
    // 0 T1/A vrai, 1 T1/A faux, 2 T1/B faux, 3 T2/B trompeur, 4 T1/A non-documente, 5 T2/A plutot-vrai
    private val claims = listOf(c("T1", "A", "vrai"), c("T1", "A", "faux"), c("T1", "B", "faux"), c("T2", "B", "trompeur"), c("T1", "A", "non-documente"), c("T2", "A", "plutot-vrai"))

    @Test fun deck_alternates_camps_and_skips_out_of_play() {
        assertEquals(listOf(0, 2, 1), quizDeck(claims, listOf("A", "B"), "T1"))
        assertEquals(listOf(0, 2, 1, 3, 5), quizDeck(claims, listOf("A", "B"), ""))
        assertEquals(listOf("T1", "T2"), quizThemes(claims))
        assertEquals(1, outOfPlay(claims))
        assertTrue(quizDeck(claims, listOf("A", "B"), "absent").isEmpty())
    }

    @Test fun end_message_by_ratio() {
        assertEquals("Sans faute.", quizEndMessage(5, 5))
        assertEquals("Belle lecture des faits.", quizEndMessage(7, 10))
        assertEquals("Des réflexes justes, et quelques surprises.", quizEndMessage(4, 10))
        assertEquals("Les données surprennent souvent : c'est le but.", quizEndMessage(0, 10))
    }
}
