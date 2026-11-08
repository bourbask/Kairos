package app.kairos.mobile.ui.decide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.kairos.mobile.data.Decision
import kotlin.math.abs

enum class Swipe { Right, Left, Up, Down }

private fun dominant(o: Offset, limit: Float): Swipe? = when {
    abs(o.x) >= abs(o.y) && o.x > limit -> Swipe.Right
    abs(o.x) >= abs(o.y) && o.x < -limit -> Swipe.Left
    o.y < -limit -> Swipe.Up
    o.y > limit -> Swipe.Down
    else -> null
}

/**
 * Geste validé : carte glissée au-delà de [threshold], ou lancée plus vite que
 * [flingSpeed] dans le sens où elle se déplace déjà.
 */
fun swipeOf(offset: Offset, velocity: Offset, threshold: Float, flingSpeed: Float): Swipe? {
    dominant(offset, threshold)?.let { return it }
    val flick = dominant(velocity, flingSpeed) ?: return null
    val along = when (flick) {
        Swipe.Right -> offset.x
        Swipe.Left -> -offset.x
        Swipe.Up -> -offset.y
        Swipe.Down -> offset.y
    }
    return flick.takeIf { along > 0f }
}

/**
 * Réponse donnée à une carte, pas encore confirmée par le serveur.
 * Comparée par identité : refaire le même choix après une annulation en crée un nouveau.
 */
class Choice(val card: Decision, val option: String)

/**
 * Pile de décisions, sans réseau ni horloge : l'écran envoie chaque [Choice]
 * de [outbox] après le délai d'annulation, en le retirant par [dispatch].
 * Une seule action annulable : la dernière, tant que son envoi n'est pas parti.
 * ponytail: choix en attente gardés en mémoire seulement ; si le processus meurt
 * avant l'envoi, la carte revient simplement à la prochaine actualisation.
 */
class DeckState(cards: List<Decision>) {
    var cards by mutableStateOf(cards)
        private set
    val total = cards.size
    var done by mutableIntStateOf(0)
        private set
    var alternativesOpen by mutableStateOf(false)
        private set

    /** Raison du dernier refus, affichée sur la carte revenue en tête. */
    var message by mutableStateOf<String?>(null)
        private set
    var outbox by mutableStateOf<List<Choice>>(emptyList())
        private set
    var closed by mutableStateOf(false)
        private set

    private class Undo(val cards: List<Decision>, val done: Int, val choice: Choice?)
    private var last by mutableStateOf<Undo?>(null)

    val current: Decision? get() = cards.firstOrNull()
    val finished: Boolean get() = cards.isEmpty()
    val canUndo: Boolean get() = last?.let { it.choice == null || it.choice in outbox } == true

    /** → : accepter la proposition ; sans proposition, choisir parmi les alternatives. */
    fun swipeRight() {
        val proposal = current?.proposal
        if (proposal == null) swipeLeft() else choose(proposal.id)
    }

    /** ← : refuser la proposition, les alternatives s'affichent. */
    fun swipeLeft() {
        if (current != null) alternativesOpen = true
    }

    fun dismissAlternatives() {
        alternativesOpen = false
    }

    fun choose(option: String) {
        val card = current ?: return
        val choice = Choice(card, option)
        last = Undo(cards, done, choice)
        cards = cards.drop(1)
        done++
        outbox = outbox + choice
        alternativesOpen = false
        message = null
    }

    /** ↑ : la carte passe en fin de pile ; son expiration ne bouge pas. */
    fun later() {
        val card = current ?: return
        last = Undo(cards, done, null)
        cards = cards.drop(1) + card
        alternativesOpen = false
        message = null
    }

    /** Annule la dernière action si son envoi n'est pas parti ; rend vrai si annulée. */
    fun undo(): Boolean {
        if (!canUndo) return false
        val u = last ?: return false
        cards = u.cards
        done = u.done
        u.choice?.let { outbox = outbox - it }
        last = null
        alternativesOpen = false
        return true
    }

    /** Le choix part au serveur : il n'est plus annulable. Faux s'il a été annulé entre-temps. */
    fun dispatch(choice: Choice): Boolean {
        if (choice !in outbox) return false
        outbox = outbox - choice
        return true
    }

    /** Refus du serveur : la carte revient en tête, avec la raison. */
    fun refused(choice: Choice, reason: String) {
        cards = listOf(choice.card) + cards.filterNot { it.id == choice.card.id && it.module == choice.card.module }
        done = (done - 1).coerceAtLeast(0)
        message = reason
        // L'instantané d'annulation ne connaît pas la carte revenue : il la perdrait.
        last = null
    }

    /** ↓ : ferme la pile ; rend les choix encore en attente, à envoyer sans délai. */
    fun close(): List<Choice> {
        closed = true
        val flushed = outbox
        outbox = emptyList()
        last = null
        return flushed
    }
}

/** Texte à afficher, ou rien s'il est vide ou blanc. */
fun shown(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
