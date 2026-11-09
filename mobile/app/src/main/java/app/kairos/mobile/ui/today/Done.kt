package app.kairos.mobile.ui.today

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.Mark
import app.kairos.mobile.data.ToolMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface DoneOutcome {
    /** Pas d'action du module : la marque locale suffit. */
    data object Local : DoneOutcome
    data object Undone : DoneOutcome

    /** Déjà en cours pour cette carte (double tap) : rien fait. */
    data object Ignored : DoneOutcome
    /** [outcome] : issue rendue par le module (voir [ToolMessage.outcome]), null s'il n'en donne pas. */
    data class Done(val message: String, val outcome: String? = null) : DoneOutcome
    data class Refused(val message: String) : DoneOutcome
}

/**
 * Cartes dont un geste est en cours (fenêtre d'annulation ou envoi) : un second geste est ignoré.
 * [keys] est observable : le fil garde ces cartes à leur place tant que l'issue n'est pas connue.
 */
class InFlight {
    private val _keys = MutableStateFlow(emptySet<String>())
    val keys: StateFlow<Set<String>> = _keys

    @Synchronized fun claim(key: String): Boolean {
        if (key in _keys.value) return false
        _keys.value += key
        return true
    }

    @Synchronized fun release(key: String) {
        _keys.value -= key
    }
}

/**
 * Gestes en cours d'un espace et éléments cachés en attendant leur issue, gardés hors de
 * l'écran (dans [app.kairos.mobile.data.Repository]) : un écran rouvert pendant la fenêtre
 * d'annulation garde l'élément caché et ignore un second geste.
 */
class Pending<K> {
    val busy = InFlight()
    private val _hidden = MutableStateFlow(emptySet<K>())
    val hidden: StateFlow<Set<K>> = _hidden

    fun hide(key: K, on: Boolean) = _hidden.update { if (on) it + key else it - key }
}

/** « Remettre dans le fil » : pas pour une carte dont le module a déjà agi, l'effet ne se défait pas d'ici. */
fun restorable(item: TimelineItem): Boolean = !(item.phase == Phase.Fait && (item.card.done != null || item.card.state == "fait"))

/** Un choix n'est demandé que s'il y a vraiment à choisir. */
val CardDone.asks: Boolean get() = (choices?.options?.size ?: 0) > 1

/** Arguments de l'outil, avec la valeur choisie (sinon le défaut, sinon la première option). */
fun doneArgs(done: CardDone, choice: JsonElement?): JsonObject {
    val c = done.choices ?: return done.args
    val value = choice ?: c.default ?: c.options.firstOrNull() ?: return done.args
    return JsonObject(done.args + (c.key to value))
}

fun optionLabel(e: JsonElement): String = (e as? JsonPrimitive)?.content ?: e.toString()

/**
 * « Fait » sur [card] : marque locale tout de suite, fenêtre d'annulation ([undoWindow],
 * vrai = annulé), puis l'outil du module s'il y en a un. Refus : la carte revient.
 * [ApiError.Unauthorized] remonte, carte revenue. Un geste déjà en cours sur la carte ([busy]) : ignoré.
 */
suspend fun completeCard(
    card: Card,
    choice: JsonElement?,
    mark: suspend (Mark?) -> Unit,
    undoWindow: suspend () -> Boolean,
    call: suspend (module: String, tool: String, args: JsonObject) -> ToolMessage,
    busy: InFlight = InFlight(),
): DoneOutcome {
    if (!busy.claim(card.key)) return DoneOutcome.Ignored
    try {
        return run(card, choice, mark, undoWindow, call)
    } finally {
        busy.release(card.key)
    }
}

private suspend fun run(
    card: Card,
    choice: JsonElement?,
    mark: suspend (Mark?) -> Unit,
    undoWindow: suspend () -> Boolean,
    call: suspend (String, String, JsonObject) -> ToolMessage,
): DoneOutcome {
    mark(Mark.Fait)
    if (undoWindow()) {
        mark(null)
        return DoneOutcome.Undone
    }
    val done = card.done ?: return DoneOutcome.Local
    return try {
        call(card.module, done.tool, doneArgs(done, choice)).let { DoneOutcome.Done(it.message, it.outcome) }
    } catch (e: ApiError) {
        mark(null)
        DoneOutcome.Refused(refusal(e))
    }
}

/**
 * Message à annoncer après un geste abouti : seul l'imprévu (issue autre que `done`).
 * Module sans issue (plus ancien que l'application) : [quiet] juge sur le libellé.
 */
fun DoneOutcome.Done.surprise(quiet: (String) -> Boolean): String? = when (outcome) {
    null -> message.takeUnless(quiet)
    "done" -> null
    else -> message
}

/** Raison lisible d'une écriture qui n'a pas abouti ; [ApiError.Unauthorized] remonte. */
fun refusal(e: ApiError): String = when (e) {
    ApiError.Unauthorized -> throw e
    ApiError.Offline -> "Serveur injoignable : rien n'a été enregistré."
    ApiError.NoReply -> "Pas de réponse du serveur : vérifie avant de réessayer."
    is ApiError.Server -> if (e.code == 422) e.message ?: "Refusé." else "${e.unavailable()}."
}
