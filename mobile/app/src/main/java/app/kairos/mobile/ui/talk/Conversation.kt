package app.kairos.mobile.ui.talk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.ChatEntry
import app.kairos.mobile.data.KairosApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fusionne deux pages de `/chat` : sans doublon, dans l'ordre du serveur (identifiants croissants). */
fun mergeChat(old: List<ChatEntry>, new: List<ChatEntry>): List<ChatEntry> =
    if (new.isEmpty()) old else (old + new).distinctBy { it.id }.sortedBy { it.id }

/** Borne de la tête sur un message entrant, en caractères Unicode. */
const val MAX_INPUT_CHARS = 8000

/** Coupe comme le serveur : la bulle en attente est exactement le message qui reviendra. */
fun capInput(text: String): String =
    if (text.codePointCount(0, text.length) <= MAX_INPUT_CHARS) text
    else text.substring(0, text.offsetByCodePoints(0, MAX_INPUT_CHARS))

/** Message non parti, gardé dans le fil avec sa raison ; « Réessayer » le renvoie. */
data class Failed(val text: String, val note: String)

/** Raison courte d'un envoi en échec, dite sur la bulle. */
fun failedNote(e: ApiError): String = when (e) {
    ApiError.NoReply -> "Envoi incertain : vérifie avant de renvoyer"
    ApiError.Offline, ApiError.Unauthorized -> "Non envoyé : serveur injoignable"
    is ApiError.Server -> if (e.code == 503) "Non envoyé : Kairos a trop à faire" else "Non envoyé : ${e.message}"
}

/** Réponse attendue : première réponse de Kairos après [after], jusqu'à [deadline] (ms epoch). */
data class PendingReply(val after: Long, val deadline: Long)

/**
 * Fil de conversation partagé avec le salon, lu depuis la tête.
 * Sondage court (2 s) seulement tant qu'une réponse est attendue ; sinon un
 * état léger (`/status`) tant que l'application est au premier plan.
 * [onReply] reçoit chaque nouvelle réponse et indique si la question a été
 * posée en mains libres.
 */
class Conversation(
    private val api: KairosApi,
    parent: CoroutineScope,
    private val onUnauthorized: () -> Unit,
    private val onReply: (ChatEntry, handsFree: Boolean) -> Unit,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    var entries by mutableStateOf<List<ChatEntry>>(emptyList())
        private set

    /** Envoyés, pas encore revenus du serveur : affichés en attente. */
    var pending by mutableStateOf<List<String>>(emptyList())
        private set

    /** Envois en échec : bulles « Non envoyé · Réessayer ». */
    var failed by mutableStateOf<List<Failed>>(emptyList())
        private set

    /** Brouillon de la saisie. */
    var draft by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var decisions by mutableIntStateOf(0)
        private set
    var awaiting by mutableStateOf(false)
        private set

    /** Réponse arrivée pendant que la conversation était fermée. */
    var unread by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set

    /** Message éphémère à afficher, consommé par [consumeNotice]. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Écran de conversation affiché. */
    var visible = false
        set(value) {
            field = value
            if (value) {
                unread = false
                scope.launch { guard { entries = mergeChat(entries, api.chat(null, HISTORY)) } }
            }
        }

    private var reply: PendingReply? = null
    private var handsFree = false
    private var foreground = false
    private var watchJob: Job? = null
    private var statusJob: Job? = null

    /** Un seul sondage à la fois : sinon deux appels au même curseur livrent deux fois la même réponse. */
    private val pollLock = Mutex()

    fun consumeNotice() {
        notice = null
    }

    fun send(text: String, handsFree: Boolean) {
        val message = capInput(text.trim())
        if (message.isEmpty()) return
        pending = pending + message
        scope.launch {
            try {
                // Curseur à jour avant l'envoi : une ancienne réponse ne passe pas pour la nouvelle.
                if (entries.isEmpty()) entries = api.chat(null, HISTORY) else poll()
                val after = entries.lastOrNull()?.id ?: 0
                api.postChat(message)
                offline = false
                busy = true
                reply = PendingReply(after, System.currentTimeMillis() + WAIT_MS)
                this@Conversation.handsFree = handsFree
                awaiting = true
                watch()
            } catch (e: ApiError) {
                pending = pending - message
                if (e == ApiError.Unauthorized) onUnauthorized() else failed = failed + Failed(message, failedNote(e))
            }
        }
    }

    /** Renvoie un message en échec. */
    fun retry(f: Failed) {
        failed = failed - f
        send(f.text, handsFree = false)
    }

    /** Reprend un message en échec dans la saisie, pour le corriger. */
    fun edit(f: Failed) {
        failed = failed - f
        if (draft.isBlank()) draft = f.text
    }

    /** Premier plan : état léger régulier, et reprise de l'attente d'une réponse. */
    fun foreground(on: Boolean) {
        foreground = on
        statusJob?.cancel()
        watchJob?.cancel()
        if (!on) return
        statusJob = scope.launch {
            while (isActive) {
                guard {
                    status()
                    if (visible) poll()
                }
                delay(STATUS_MS)
            }
        }
        if (awaiting) watch()
    }

    /** Relit l'état léger sans attendre le prochain tour (après une décision). */
    fun refreshStatus() {
        scope.launch { guard { status() } }
    }

    /** Réponse encore attendue, à confier à la surveillance en arrière-plan. */
    fun pendingReply(): PendingReply? = reply?.takeIf { awaiting && it.deadline > System.currentTimeMillis() }

    fun close() = scope.cancel()

    private fun watch() {
        if (!foreground || watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (awaiting) {
                delay(POLL_MS)
                val r = reply ?: break
                guard {
                    poll()
                    status()
                }
                if (!busy && entries.any { it.role == KAIROS && it.id > r.after }) {
                    awaiting = false
                } else if (System.currentTimeMillis() > r.deadline) {
                    awaiting = false
                    notice = "Toujours pas de réponse : elle s'affichera ici dès qu'elle arrive."
                }
            }
            handsFree = false
        }
    }

    private suspend fun status() {
        val s = api.status()
        busy = s.busy
        decisions = s.decisions
        offline = false
    }

    internal suspend fun poll() = pollLock.withLock {
        val last = entries.lastOrNull()?.id
        val fresh = api.chat(last, PAGE)
        if (fresh.isEmpty()) return@withLock
        entries = mergeChat(entries, fresh)
        // Message revenu du serveur : il quitte la file d'attente locale.
        fresh.filter { it.role == USER }.forEach { e -> pending = pending - e.text.trim() }
        val replies = fresh.filter { it.role == KAIROS }
        if (replies.isNotEmpty() && !visible) unread = true
        replies.forEach { onReply(it, handsFree) }
        offline = false
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiError) {
            when (e) {
                ApiError.Unauthorized -> onUnauthorized()
                ApiError.Offline, ApiError.NoReply -> offline = true
                is ApiError.Server -> Unit
            }
        }
    }

    private companion object {
        const val HISTORY = 50
        const val PAGE = 200
        const val POLL_MS = 2_000L
        const val STATUS_MS = 20_000L
        const val WAIT_MS = 10 * 60_000L
        const val USER = "user"
        const val KAIROS = "kairos"
    }
}
