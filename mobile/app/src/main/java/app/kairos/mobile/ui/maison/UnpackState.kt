package app.kairos.mobile.ui.maison

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kairos.mobile.data.Order
import app.kairos.mobile.data.OrderLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Ligne traitée, pas encore confirmée par le serveur : rangée dans [location]
 * (avec [expiry] si lue), ou en rupture ([missing]). Comparée par identité.
 */
class Stow(val order: Long, val line: OrderLine, val location: String?, val expiry: LocalDate?, val missing: Boolean) {
    /** Arguments de `app_order_line`. */
    val args: JsonObject get() = buildJsonObject {
        put("order", order)
        put("index", line.index)
        if (missing) put("missing", true)
        location?.let { put("location", it) }
        expiry?.let { put("expiry", it.toString()) }
    }
}

/**
 * Déballage d'une commande, une ligne à la fois, sans réseau ni horloge.
 * Chaque ligne : zone ([place], la zone proposée par défaut), puis date si le
 * produit est frais ([date], « pas de date » compris) ; ou rupture ([missing]).
 * Une ligne traitée part tout de suite à l'écran ; l'écran l'envoie après le
 * délai d'annulation ([dispatch]). Un refus du serveur ([refused]) la remet en tête.
 */
class UnpackState(order: Order, private val zones: List<Pair<String, String>> = ZONES) {
    enum class Step { Zone, Date }

    private val id = order.id
    val total = order.lines.size
    private val before = order.lines.count { it.done }

    private var queue by mutableStateOf(order.lines.filter { !it.done })
    private var handled by mutableStateOf<List<Stow>>(emptyList())
    private var sent by mutableStateOf<Set<Stow>>(emptySet())

    var step by mutableStateOf(Step.Zone)
        private set

    /** Zone choisie pour la ligne en cours, en attente de sa date. */
    var zone by mutableStateOf<String?>(null)
        private set

    /** Raison du dernier refus, montrée avec la ligne revenue. */
    var message by mutableStateOf<String?>(null)
        private set

    val current: OrderLine? get() = queue.firstOrNull()
    val next: OrderLine? get() = queue.getOrNull(1)
    val done: Int get() = before + handled.size
    val finished: Boolean get() = current == null

    /** Zone habituelle de la ligne en cours, si c'est une zone du plan. */
    val proposed: String? get() = current?.location?.takeIf { l -> zones.any { it.first == l } }

    val canUndo: Boolean get() = handled.lastOrNull()?.let { it !in sent } == true

    /** Range la ligne en cours dans [to] ; produit frais : passe d'abord par la date (rend null). */
    fun place(to: String? = proposed): Stow? {
        val line = current ?: return null
        if (to == null || zones.none { it.first == to }) return null
        if (line.fresh) {
            zone = to
            step = Step.Date
            return null
        }
        return handle(Stow(id, line, to, null, missing = false))
    }

    /** Date lue et confirmée, ou null = pas de date. */
    fun date(expiry: LocalDate?): Stow? {
        val line = current ?: return null
        val to = zone?.takeIf { step == Step.Date } ?: return null
        return handle(Stow(id, line, to, expiry, missing = false))
    }

    /** Retour au choix de la zone. */
    fun back() {
        step = Step.Zone
        zone = null
    }

    /** Rupture : rien à ranger. */
    fun missing(): Stow? = current?.let { handle(Stow(id, it, null, null, missing = true)) }

    private fun handle(s: Stow): Stow {
        queue = queue.drop(1)
        handled = handled + s
        back()
        message = null
        return s
    }

    /** Remet en tête la dernière ligne traitée, si elle n'est pas partie. */
    fun undo(): Boolean {
        if (!canUndo) return false
        val last = handled.last()
        handled = handled.dropLast(1)
        queue = listOf(last.line) + queue
        back()
        return true
    }

    /** La ligne part au serveur : plus annulable. Faux si annulée ou déjà partie. */
    fun dispatch(s: Stow): Boolean {
        if (s !in handled || s in sent) return false
        sent = sent + s
        return true
    }

    /**
     * Le serveur a refusé : la ligne revient avec la raison, en tête, ou juste
     * après la ligne dont la date est en cours (l'étape n'est pas perdue).
     * Ligne déjà traitée côté serveur ([alreadyHandled]) : c'est fait, rien ne revient.
     */
    fun refused(s: Stow, reason: String) {
        if (s !in handled || alreadyHandled(reason)) return
        handled = handled - s
        sent = sent - s
        queue = if (step == Step.Date) queue.take(1) + s.line + queue.drop(1) else listOf(s.line) + queue
        message = reason
    }

    companion object {
        /** Commande à ranger après [current], par identifiant, en boucle ; null s'il n'y en a pas d'autre. */
        fun nextOrder(orders: List<Order>, current: Long): Order? {
            val pending = orders.filter { o -> o.id != current && o.lines.any { !it.done } }.sortedBy { it.id }
            return pending.firstOrNull { it.id > current } ?: pending.firstOrNull()
        }

        /** Refus de `app_order_line` qui signifie « déjà fait » (double envoi, autre appareil, commande close). */
        fun alreadyHandled(reason: String): Boolean =
            reason.trimEnd().endsWith("déjà traité.") || reason.trimEnd().endsWith("pas à ranger.")
    }

    /** L'écran est fermé : un refus ne peut plus remettre la ligne à l'écran. */
    var closed = false
        private set

    /** L'écran se ferme : rend ce qui n'est pas parti, à envoyer sans délai. */
    fun flush(): List<Stow> {
        closed = true
        val out = handled.filter { it !in sent }
        sent = sent + out
        return out
    }
}
