package app.kairos.mobile.ui.talk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kairos.mobile.ui.mascot.MascotState

/**
 * Parole en cours. [Finishing] : doigt relevé, on attend le texte définitif
 * de la reconnaissance avant d'envoyer.
 */
enum class TalkMode { Idle, Recording, Locked, Cancelled, Finishing }

/** Ce que l'appelant doit faire après une transition. */
sealed interface TalkEffect {
    data object StartListening : TalkEffect
    data object StopListening : TalkEffect
    data object CancelListening : TalkEffect

    /** Seuil de glissement franchi : retour haptique. */
    data object Threshold : TalkEffect
    data class Send(val text: String, val handsFree: Boolean) : TalkEffect
}

/**
 * Machine d'états du bouton Kairos, sans dépendance à Android :
 * appui long = enregistrer, glisser ↑ = verrouiller (mains libres, texte
 * modifiable), glisser ← = annuler, relâcher = envoyer.
 * [density] convertit les seuils (dp) en pixels.
 */
class TalkController(density: Float) {
    private val lockPx = LOCK_DP * density
    private val cancelPx = CANCEL_DP * density

    var mode by mutableStateOf(TalkMode.Idle)
        private set

    /** Texte reconnu et validé (phrases terminées, ou texte corrigé à la main). */
    var committed by mutableStateOf("")
        private set

    /** Phrase en cours de reconnaissance, affichée en direct. */
    var partial by mutableStateOf("")
        private set

    var listening by mutableStateOf(false)
        private set

    /** Progression des glissements, de 0 à 1, pour l'affichage. */
    var lockProgress by mutableFloatStateOf(0f)
        private set
    var cancelProgress by mutableFloatStateOf(0f)
        private set

    val transcript: String get() = join(committed, partial)

    /** Appui long sur Kairos. */
    fun pressStart(): List<TalkEffect> {
        if (mode != TalkMode.Idle) return emptyList()
        reset(TalkMode.Recording)
        return listOf(listen())
    }

    /** Équivalent visible de l'appui long : directement en mains libres. */
    fun startLocked(): List<TalkEffect> {
        if (mode != TalkMode.Idle) return emptyList()
        reset(TalkMode.Locked)
        return listOf(listen())
    }

    /** Déplacement cumulé depuis l'appui (px, y vers le bas). */
    fun drag(dx: Float, dy: Float): List<TalkEffect> {
        if (mode != TalkMode.Recording) return emptyList()
        lockProgress = (-dy / lockPx).coerceIn(0f, 1f)
        cancelProgress = (-dx / cancelPx).coerceIn(0f, 1f)
        return when {
            -dx >= cancelPx -> {
                mode = TalkMode.Cancelled
                buildList {
                    if (listening) add(TalkEffect.CancelListening)
                    add(TalkEffect.Threshold)
                }.also { listening = false }
            }
            -dy >= lockPx -> {
                mode = TalkMode.Locked
                lockProgress = 1f
                cancelProgress = 0f
                listOf(TalkEffect.Threshold)
            }
            else -> emptyList()
        }
    }

    /** Doigt relevé (ou geste interrompu). */
    fun pressEnd(): List<TalkEffect> = when (mode) {
        TalkMode.Recording ->
            if (listening) {
                mode = TalkMode.Finishing
                listOf(TalkEffect.StopListening)
            } else {
                finish(handsFree = false)
            }
        TalkMode.Cancelled -> {
            reset(TalkMode.Idle)
            emptyList()
        }
        else -> emptyList()
    }

    fun onPartial(text: String) {
        if (listening) partial = text
    }

    /** Fin d'une phrase reconnue. */
    fun onFinal(text: String): List<TalkEffect> {
        if (!listening) return emptyList()
        // Certains moteurs rendent un résultat vide après l'arrêt : on garde ce qui s'affichait.
        committed = join(committed, text.ifBlank { partial })
        partial = ""
        listening = false
        return when (mode) {
            // Doigt toujours posé : on continue d'écouter la suite.
            TalkMode.Recording -> listOf(listen())
            TalkMode.Finishing -> finish(handsFree = false)
            else -> emptyList()
        }
    }

    /** Échec de la reconnaissance ; [retry] = rien entendu, on peut réessayer. */
    fun onError(retry: Boolean): List<TalkEffect> {
        if (!listening) return emptyList()
        committed = transcript
        partial = ""
        listening = false
        return when (mode) {
            TalkMode.Recording ->
                if (retry) {
                    listOf(listen())
                } else {
                    // Repli sur le texte modifiable : le clavier prend le relais.
                    mode = TalkMode.Locked
                    emptyList()
                }
            TalkMode.Finishing -> finish(handsFree = false)
            else -> emptyList()
        }
    }

    /** Correction à la main, en mode verrouillé. */
    fun edit(text: String) {
        if (mode != TalkMode.Locked) return
        committed = text
        partial = ""
    }

    /** Reprendre la dictée en mode verrouillé ; le texte s'ajoute à la suite. */
    fun resume(): List<TalkEffect> {
        if (mode != TalkMode.Locked || listening) return emptyList()
        return listOf(listen())
    }

    /** Bouton « Envoyer » du mode verrouillé. */
    fun send(): List<TalkEffect> {
        if (mode != TalkMode.Locked) return emptyList()
        val stop = if (listening) listOf(TalkEffect.CancelListening) else emptyList()
        return stop + finish(handsFree = true)
    }

    /** Bouton « Annuler », retour, ou écran quitté. */
    fun cancel(): List<TalkEffect> {
        val stop = if (listening) listOf(TalkEffect.CancelListening) else emptyList()
        reset(TalkMode.Idle)
        return stop
    }

    private fun listen(): TalkEffect {
        listening = true
        return TalkEffect.StartListening
    }

    private fun finish(handsFree: Boolean): List<TalkEffect> {
        val text = transcript.trim()
        reset(TalkMode.Idle)
        return if (text.isEmpty()) emptyList() else listOf(TalkEffect.Send(text, handsFree))
    }

    private fun reset(to: TalkMode) {
        mode = to
        committed = ""
        partial = ""
        listening = false
        lockProgress = 0f
        cancelProgress = 0f
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        const val LOCK_DP = 80f
        const val CANCEL_DP = 100f
    }
}

/** Expression du personnage : écoute, réfléchit, réponse non lue, décisions en attente, repos. */
fun mascotFor(mode: TalkMode, busy: Boolean, decisions: Int, unread: Boolean): MascotState = when {
    mode == TalkMode.Recording || mode == TalkMode.Locked || mode == TalkMode.Finishing -> MascotState.Ecoute
    busy -> MascotState.Reflechit
    unread -> MascotState.Agit
    decisions > 0 -> MascotState.Attend
    else -> MascotState.Repos
}
