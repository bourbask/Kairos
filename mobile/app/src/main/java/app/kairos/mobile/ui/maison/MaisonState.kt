package app.kairos.mobile.ui.maison

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kairos.mobile.data.Lot
import app.kairos.mobile.data.MaisonSnapshot
import app.kairos.mobile.data.Order
import app.kairos.mobile.data.Tone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/** Zones du plan, dans l'ordre d'affichage : identifiant stocké → libellé. */
val ZONES = listOf("frigo" to "Frigo", "congelateur" to "Congélo", "placard" to "Placard", "etagere" to "Étagère", "corbeille" to "Table")

/** « retrait 10/11 » pour une date AAAA-MM-JJ ; rien si absente ou illisible. */
fun pickupLabel(pickup: String?): String? =
    runCatching { LocalDate.parse(pickup) }.getOrNull()?.let { "retrait ${it.format(DAY_MONTH)}" }

data class ZoneView(val id: String, val label: String, val count: Int, val urgent: Int)

/** Action sur un lot, pas encore confirmée par le serveur. Comparée par identité. */
sealed class LotAction(val lot: Lot) {
    /** Consommé en entier. */
    class Use(lot: Lot) : LotAction(lot)

    /** Déplacé ([location]) ou recompté ([qty], unité de base). */
    class Update(lot: Lot, val location: String? = null, val qty: Double? = null) : LotAction(lot)

    val tool: String get() = if (this is Use) "app_lot_use" else "app_lot_update"

    val args: JsonObject get() = buildJsonObject {
        put("lot", lot.id)
        if (this@LotAction is Update) {
            location?.let { put("location", it) }
            qty?.let { put("qty", it) }
        }
    }
}

/**
 * Inventaire affiché, sans réseau ni horloge. Chaque action s'applique tout de
 * suite à l'écran (liste et compteurs du plan), par-dessus le dernier état du
 * serveur. Confirmée ([confirmed]), elle reste appliquée jusqu'à ce qu'un état lu
 * après coup arrive ([settle]) : une actualisation partie avant, ou un échec qui ne
 * rend que l'ancien état, ne la fait pas revenir en arrière. Refusée ([refused]),
 * le lot revient, avec la raison. Seule la dernière consommation est annulable, tant que
 * l'écran ne l'a pas envoyée ([dispatch]). Hors ligne, tout est en lecture seule.
 */
class MaisonState(snapshot: MaisonSnapshot = MaisonSnapshot()) {
    private var base by mutableStateOf(snapshot)
    private var pending by mutableStateOf<List<LotAction>>(emptyList())
    private var sent by mutableStateOf<Set<LotAction>>(emptySet())
    private var acked by mutableStateOf<Set<LotAction>>(emptySet())
    private var undoable by mutableStateOf<LotAction.Use?>(null)
    private var selected by mutableStateOf<String?>(null)

    /** Raison du dernier refus, à montrer une fois. */
    var message by mutableStateOf<String?>(null)
        private set

    val offline: Boolean get() = base.offline
    val canAct: Boolean get() = !offline

    /** Le serveur a répondu au moins une fois (ici ou lors d'une session précédente). */
    val known: Boolean get() = base.fetchedAt != null

    val lots: List<Lot> get() = pending.fold(base.inventory.lots, ::applied)
    val total: Int get() = lots.size

    /** Commandes qui ont encore des lignes à ranger. */
    val orders: List<Order> get() = base.orders.filter { o -> o.lines.any { !it.done } }

    /** Commande du bandeau : retrait le plus tardif, puis la dernière enregistrée. */
    val banner: Order? get() = orders.maxWithOrNull(compareBy<Order>({ it.pickup.orEmpty() }, { it.id }))

    val zones: List<ZoneView>
        get() {
            val all = lots
            val labels = base.inventory.zones.associate { it.id to it.label }
            return ZONES.map { (id, label) ->
                val here = all.filter { it.location == id }
                ZoneView(id, labels[id] ?: label, here.size, here.count { it.tone == Tone.Urgent })
            }
        }

    /** Zone ouverte : choisie, sinon la première qui a des urgences, sinon la première non vide. */
    val zone: String
        get() = selected ?: zones.let { z -> (z.firstOrNull { it.urgent > 0 } ?: z.firstOrNull { it.count > 0 } ?: z.first()).id }

    val canUndo: Boolean get() = undoable?.let { it in pending && it !in sent } == true

    fun lotsIn(zone: String): List<Lot> = lots.filter { it.location == zone }

    fun select(zone: String) {
        selected = zone
    }

    /** Nouvel état du serveur ; les actions encore en route restent appliquées. */
    fun load(snapshot: MaisonSnapshot) {
        base = snapshot
    }

    fun consume(lot: Lot): LotAction.Use? {
        if (!canAct || lots.none { it.id == lot.id }) return null
        return LotAction.Use(lot).also {
            pending = pending + it
            undoable = it
            message = null
        }
    }

    fun move(lot: Lot, to: String): LotAction? {
        if (!canAct || to == lot.location || ZONES.none { it.first == to }) return null
        return update(LotAction.Update(lot, location = to))
    }

    /** Nouvelle quantité (unité de base) ; zéro vaut consommé, annulable. */
    fun setQty(lot: Lot, qty: Double): LotAction? {
        if (!canAct) return null
        if (qty <= 0.0) return consume(lot)
        if (abs(qty - lot.qty) < 1e-9) return null
        return update(LotAction.Update(lot, qty = qty))
    }

    private fun update(a: LotAction): LotAction {
        pending = pending + a
        message = null
        return a
    }

    /** Annule la dernière consommation si elle n'est pas partie ; rend vrai si annulée. */
    fun undo(): Boolean {
        if (!canUndo) return false
        pending = pending - undoable!!
        undoable = null
        return true
    }

    /** L'action part au serveur : elle n'est plus annulable. Faux si annulée entre-temps ou déjà partie. */
    fun dispatch(a: LotAction): Boolean {
        if (a !in pending || a in sent) return false
        sent = sent + a
        if (undoable === a) undoable = null
        return true
    }

    /** Le serveur a accepté : l'effet reste appliqué jusqu'à [settle]. */
    fun confirmed(a: LotAction) {
        if (a in pending) acked = acked + a
    }

    /**
     * État du serveur lu après les confirmations reçues jusqu'ici : il les reflète,
     * elles cessent d'être rejouées par-dessus.
     */
    fun settle(snapshot: MaisonSnapshot) {
        base = snapshot
        acked.forEach(::forget)
    }

    /** Le serveur a refusé : le lot revient tel qu'il était, avec la raison. */
    fun refused(a: LotAction, reason: String) {
        forget(a)
        message = reason
    }

    fun consumeMessage() {
        message = null
    }

    /** L'écran se ferme : rend les actions pas encore parties, à envoyer sans délai. */
    fun flush(): List<LotAction> {
        val out = pending.filter { it !in sent }
        sent = sent + out
        undoable = null
        return out
    }

    private fun forget(a: LotAction) {
        pending = pending - a
        sent = sent - a
        acked = acked - a
        if (undoable === a) undoable = null
    }
}

private fun applied(lots: List<Lot>, a: LotAction): List<Lot> = when (a) {
    is LotAction.Use -> lots.filterNot { it.id == a.lot.id }
    is LotAction.Update -> lots.map { l ->
        if (l.id != a.lot.id) {
            l
        } else {
            l.copy(
                location = a.location ?: l.location,
                qty = a.qty ?: l.qty,
                label = a.qty?.let { formatQty(it, l.unit) } ?: l.label,
            )
        }
    }
}

/**
 * Un cran de +/− depuis [value] (unité de base), jamais sous zéro. Arrondi au
 * millième : des pas décimaux répétés ne laissent pas de reste flottant.
 */
fun stepQty(value: Double, step: Double, up: Boolean): Double {
    val s = step.takeIf { it > 0 } ?: 1.0
    return (((if (up) value + s else value - s) * 1000).roundToLong() / 1000.0).coerceAtLeast(0.0)
}

/** Nombre à deux décimales au plus, virgule française (comme le module). */
private fun num(x: Double): String {
    val r = (x * 100).roundToLong() / 100.0
    return (if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()).replace('.', ',')
}

/** Quantité lisible, au format du module (« 1,2 kg », « 3 pièces »). */
fun formatQty(qty: Double, unit: String): String = when (unit) {
    "g" -> if (qty >= 1000) "${num(qty / 1000)} kg" else "${num(qty)} g"
    "ml" -> if (qty >= 1000) "${num(qty / 1000)} l" else "${num(qty)} ml"
    "pièce" -> "${num(qty)} pièce${if (qty > 1) "s" else ""}"
    else -> "${num(qty)} $unit".trim()
}

private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")

/** Échéance courte (« demain », « 2 j », « 19/11 ») ; « ~ » = date estimée. Rien sans date. */
fun expiryText(lot: Lot, today: LocalDate): String? {
    val d = lot.expiry?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val n = ChronoUnit.DAYS.between(today, d)
    val text = when {
        n < 0 -> return "passée"
        n == 0L -> "aujourd'hui"
        n == 1L -> "demain"
        n < 7 -> "$n j"
        else -> d.format(DAY_MONTH)
    }
    return if (lot.estimated) "~$text" else text
}
