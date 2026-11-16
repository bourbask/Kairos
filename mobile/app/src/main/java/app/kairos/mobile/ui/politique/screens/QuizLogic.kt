package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.normalizeVerdict
import kotlin.math.abs

/** Logique pure du jeu « Vrai ou faux ? » : réponses, gestes, retours, paquets. Les cartes sont désignées par leur rang dans [claimsOf]. */

enum class Guess(val label: String) { Vrai("Vrai"), PlutotVrai("Plutôt vrai"), PlutotFaux("Plutôt faux"), Faux("Faux") }

/** Réponse attendue ; null = hors jeu (non documenté, verdict inconnu). */
fun expectedAnswer(verdict: String): Guess? = when (normalizeVerdict(verdict)) {
    "vrai" -> Guess.Vrai
    "plutot-vrai" -> Guess.PlutotVrai
    "trompeur", "plutot-faux", "non-prouve" -> Guess.PlutotFaux
    "faux" -> Guess.Faux
    else -> null
}

/**
 * Geste lâché : déplacement en dp, vitesse en dp/ms. La distance décide, ou une vitesse
 * franche après un début de course ; haut et bas exigent de rester près de l'axe vertical.
 */
fun swipeDirection(dx: Float, dy: Float, vx: Float, vy: Float): Guess? = when {
    dx > 100f || (vx > 0.5f && dx > 30f) -> Guess.Vrai
    dx < -100f || (vx < -0.5f && dx < -30f) -> Guess.Faux
    abs(dx) >= 80f -> null
    dy < -90f || (vy < -0.5f && dy < -30f) -> Guess.PlutotVrai
    dy > 90f || (vy > 0.5f && dy > 30f) -> Guess.PlutotFaux
    else -> null
}

/** Carte déjà retournée : un glissement latéral suffisant passe à la suivante. */
fun swipeToNext(dx: Float, vx: Float): Boolean = abs(dx) > 90f || abs(vx) > 0.5f

enum class Tone { Ok, Near, Ko }
class Feedback(val tone: Tone, val text: String)

/** Même côté de l'échelle (vrai / plutôt vrai, faux / plutôt faux) : « presque ». */
private fun Guess.trueSide() = this == Guess.Vrai || this == Guess.PlutotVrai

fun quizFeedback(verdict: String, guess: Guess): Feedback {
    val exp = expectedAnswer(verdict)
    return when {
        guess == exp -> Feedback(Tone.Ok, "Bien vu : c'était votre réponse.")
        exp != null && guess.trueSide() == exp.trueSide() -> Feedback(Tone.Near, "Presque : c'était « ${exp.label} ».")
        else -> Feedback(Tone.Ko, "Vous aviez dit « ${guess.label} ».")
    }
}

/** Thèmes ayant au moins une phrase jouable, et nombre de phrases hors jeu. */
fun quizThemes(claims: List<Claim>): List<String> =
    claims.filter { expectedAnswer(it.v) != null }.map { it.theme }.filter { it.isNotBlank() }.distinct()

fun outOfPlay(claims: List<Claim>): Int = claims.count { expectedAnswer(it.v) == null }

/** Paquet d'un thème (vide = tous) : les camps alternent, aucun n'enchaîne plusieurs cartes. */
fun quizDeck(claims: List<Claim>, camps: List<String>, theme: String): List<Int> {
    val pool = claims.indices.filter { expectedAnswer(claims[it].v) != null && (theme.isEmpty() || claims[it].theme == theme) }
    val by = (camps + "").distinct().map { k -> pool.filter { claims[it].camp == k }.toMutableList() }
    val out = ArrayList<Int>()
    while (by.any { it.isNotEmpty() }) for (l in by) if (l.isNotEmpty()) out += l.removeAt(0)
    return out
}

fun quizEndMessage(ok: Int, n: Int): String = when {
    ok == n -> "Sans faute."
    ok * 10 >= n * 7 -> "Belle lecture des faits."
    ok * 10 >= n * 4 -> "Des réflexes justes, et quelques surprises."
    else -> "Les données surprennent souvent : c'est le but."
}

/** Progression de la barre : `answered` réponses données sur `n` ; l'icône de résultat se remplit sur la page de résultat. */
class QuizProgress(val fraction: Float, val resultReached: Boolean)

fun quizProgress(answered: Int, n: Int, atResult: Boolean): QuizProgress =
    QuizProgress(if (n <= 0) 0f else (answered.toFloat() / n).coerceIn(0f, 1f), atResult)
