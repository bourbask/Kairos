package app.kairos.mobile.ui.today

import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.Mark
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface DoneOutcome {
    /** Pas d'action du module : la marque locale suffit. */
    data object Local : DoneOutcome
    data object Undone : DoneOutcome
    data class Done(val message: String) : DoneOutcome
    data class Refused(val message: String) : DoneOutcome
}

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
 * [ApiError.Unauthorized] remonte, carte revenue.
 */
suspend fun completeCard(
    card: Card,
    choice: JsonElement?,
    mark: suspend (Mark?) -> Unit,
    undoWindow: suspend () -> Boolean,
    call: suspend (module: String, tool: String, args: JsonObject) -> String,
): DoneOutcome {
    mark(Mark.Fait)
    if (undoWindow()) {
        mark(null)
        return DoneOutcome.Undone
    }
    val done = card.done ?: return DoneOutcome.Local
    return try {
        DoneOutcome.Done(call(card.module, done.tool, doneArgs(done, choice)))
    } catch (e: ApiError) {
        mark(null)
        when (e) {
            ApiError.Unauthorized -> throw e
            ApiError.Offline -> DoneOutcome.Refused("Serveur injoignable : rien n'a été enregistré.")
            ApiError.NoReply -> DoneOutcome.Refused("Pas de réponse du serveur : vérifie avant de réessayer.")
            is ApiError.Server -> DoneOutcome.Refused(if (e.code == 422) e.message ?: "Refusé." else "${e.unavailable()}.")
        }
    }
}
