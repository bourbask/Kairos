package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.model.Claim
import kotlin.math.abs

/** Logique pure du jeu « Vrai ou faux ? » : réponses, gestes, retours, paquets. Les cartes sont désignées par leur rang dans [claimsOf]. */

enum class Guess(val label: String) { Vrai("Vrai"), Mais("Vrai mais…"), Partiel("En partie"), Faux("Faux") }

/** Réponse attendue ; null = hors jeu (non documenté, verdict inconnu). */
fun expectedAnswer(verdict: String): Guess? = when (verdict) {
    "solide" -> Guess.Vrai
    "trompeur" -> Guess.Mais
    "partiel" -> Guess.Partiel
    "fragile", "faux" -> Guess.Faux
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
    dy < -90f || (vy < -0.5f && dy < -30f) -> Guess.Partiel
    dy > 90f || (vy > 0.5f && dy > 30f) -> Guess.Mais
    else -> null
}

/** Carte déjà retournée : un glissement latéral suffisant passe à la suivante. */
fun swipeToNext(dx: Float, vx: Float): Boolean = abs(dx) > 90f || abs(vx) > 0.5f

enum class Tone { Ok, Near, Ko }
class Feedback(val tone: Tone, val text: String)

fun quizFeedback(verdict: String, guess: Guess): Feedback = when {
    guess == expectedAnswer(verdict) ->
        Feedback(Tone.Ok, if (verdict == "fragile") "Bien vu : fragile, donc à ranger côté faux." else "Bien vu : c'était votre réponse.")
    verdict == "trompeur" && guess == Guess.Vrai -> Feedback(Tone.Near, "Presque : c'est exact, mais trompeur. Lisez ce que la phrase cache.")
    verdict == "solide" && guess == Guess.Mais -> Feedback(Tone.Near, "Prudent, mais pas de piège cette fois : la phrase tient.")
    else -> Feedback(Tone.Ko, "Vous aviez dit « ${guess.label} ».")
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
