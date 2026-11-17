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

    @Test fun sectors_match_the_four_directions_and_down_is_neutral() {
        assertEquals(Guess.Vrai, quizSector(100f, 0f))
        assertEquals(Guess.Vrai, quizSector(100f, 100f)) // −45°
        assertEquals(Guess.PlutotVrai, quizSector(60f, -60f))
        assertEquals(Guess.PlutotFaux, quizSector(-60f, -60f))
        assertEquals(Guess.Faux, quizSector(-100f, 0f))
        assertEquals(Guess.Faux, quizSector(-100f, 100f)) // −135°
        assertNull(quizSector(0f, 100f))
        assertNull(quizSector(30f, 100f))
        // Bornes : 25° bascule à haut-droite, 155° à gauche.
        assertEquals(Guess.Vrai, quizSector(100f, -46f))
        assertEquals(Guess.PlutotVrai, quizSector(100f, -47f))
        assertEquals(Guess.Faux, quizSector(-100f, -46f))
    }

    @Test fun levels_and_release_thresholds() {
        assertEquals(0, quizLevel(10f, 10f))
        assertEquals(1, quizLevel(30f, 0f))
        assertEquals(2, quizLevel(0f, -60f))
        assertEquals(3, quizLevel(-100f, 0f))
        assertEquals(Guess.Vrai, quizRelease(101f, 0f, 0f, 0f))
        assertEquals(Guess.PlutotFaux, quizRelease(-71f, -71f, 0f, 0f))
        assertNull(quizRelease(60f, 0f, 0f, 0f))
        // Vitesse franche après un début de course ; sans course, rien.
        assertEquals(Guess.PlutotVrai, quizRelease(25f, -25f, 0.5f, -0.5f))
        assertNull(quizRelease(20f, 0f, 0.9f, 0f))
        // Vers le bas : jamais de réponse, même loin et vite.
        assertNull(quizRelease(0f, 200f, 0f, 2f))
        assertTrue(swipeToNext(91f, 0f, 0f, 0f)); assertTrue(swipeToNext(-20f, 5f, -0.6f, 0f)); assertFalse(swipeToNext(50f, 0f, 0.2f, 0f))
        // Faits et sources : un défilement vertical, même un peu de biais, reste un défilement.
        assertFalse(swipeToNext(30f, 120f, 0.2f, 1.5f))
        assertFalse(swipeToNext(0f, 91f, 0f, 0f))
        assertFalse(backDragTakes(10f, 12f)); assertTrue(backDragTakes(12f, 10f))
    }

    @Test fun verdict_note_only_when_it_differs_from_the_game_label() {
        assertEquals("Trompeur", verdictNote("trompeur"))
        assertEquals("Non prouvé", verdictNote("non-prouve"))
        assertNull(verdictNote("plutot-faux"))
        assertNull(verdictNote("vrai"))
    }

    @Test fun arrows_and_leds() {
        val r = arrowCells(Arrow.R).toSet()
        // Mirror exact : la gauche est la droite retournée.
        assertEquals(r.map { (x, y) -> 8 - x to y }.toSet(), arrowCells(Arrow.L).toSet())
        assertEquals(arrowCells(Arrow.UR).map { (x, y) -> 8 - x to y }.toSet(), arrowCells(Arrow.UL).toSet())
        assertTrue((8 to 4) in r && (0 to 4) in r && (0 to 0) !in r)
        val led = ledCells(Arrow.UR)
        assertTrue(led.all { (x, y, _) -> x in 0 until LED_SIZE && y in 0 until LED_SIZE })
        // Chaque cellule de flèche devient lentille ; bague, puis halos autour.
        assertEquals(arrowCells(Arrow.UR).size, led.count { it.third in setOf(LedRole.Ln, LedRole.Lh, LedRole.Ls) })
        assertTrue(LedRole.entries.all { k -> led.any { it.third == k } })
        assertEquals(led.sortedBy { it.third }, led)
    }

    @Test fun rear_pile_pictogram_writes_left_aligned_lines() {
        val w = 130; val h = 220
        assertTrue(rearCells(w, h, 0, 0).all { it == -1 })
        val g = rearCells(w, h, 3, 5)
        val txt = RearInk.Txt.ordinal
        val rows = (0 until h).filter { y -> (0 until w).any { g[y * w + it] == txt } }
        assertTrue(rows.isNotEmpty())
        // Fausse écriture alignée à gauche : départ à la marge du tract (25 dp, environ 8 cellules), fin avant la marge droite.
        val (x0, _) = cardOrigin(w, h)
        val xs = rows.flatMap { y -> (0 until w).filter { g[y * w + it] == txt } }.map { it - x0 }
        assertTrue(xs.min().toString(), xs.min() in 4..12)
        assertTrue(xs.max().toString(), xs.max() < TRACT_W - 4)
        assertTrue(g.any { it == RearInk.Band.ordinal } && g.any { it == RearInk.Ear.ordinal } && g.any { it == RearInk.Shade.ordinal })
    }

    @Test fun turn_lands_flat_without_bounce() {
        assertEquals(1.0, TURN_KEYS.last().first, 0.0)
        assertEquals(TURN_KEYS.sortedBy { it.first }, TURN_KEYS)
        val w = 318; val h = 441
        val start = turnFrame(.05, w, h, TurnFace.Res)
        val end = turnFrame(1.0, w, h, TurnFace.Res)
        // Posée : à plat, sans soulèvement, face d'arrivée (pictogramme des faits) seule visible.
        assertEquals((TURN_PAD / 3), end.runs.minOf { it.y })
        assertTrue(end.runs.none { it.ink == TurnInk.Acc })
        assertTrue(start.runs.any { it.ink == TurnInk.Acc })
        // Coin corné : rabat sombre en haut à droite au départ comme à l'arrivée (le verso a aussi son coin).
        val right = (TURN_PAD + w / 2) / 3
        assertTrue(start.runs.any { it.ink == TurnInk.Ear && it.x > right })
        assertTrue(end.runs.any { it.ink == TurnInk.Ear && it.x > right })
        assertTrue(end.runs.none { it.ink == TurnInk.Ear && it.x < right })
        assertEquals(.16f, end.shadowAlpha, 1e-6f)
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
