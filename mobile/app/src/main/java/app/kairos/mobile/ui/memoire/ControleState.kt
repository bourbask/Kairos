package app.kairos.mobile.ui.memoire

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.Controle
import app.kairos.mobile.data.Habitude
import app.kairos.mobile.data.Souvenir
import app.kairos.mobile.ui.finances.issueOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * Logique pure de la section « Ce que je retiens » : ancres, habitudes apprises, souvenirs
 * qui s'estompent. Sans Compose ni réseau ; l'état n'est jamais écrit sur l'appareil.
 */

/** Dernière lecture de la section et, s'il y a lieu, la raison de ne pas l'avoir rafraîchie. */
data class ControleView(val data: Controle? = null, val issue: String? = null)

/** Nouvel état après une lecture : la réponse remplace l'ancien ; sinon il est gardé avec la raison. */
fun controleAfter(prev: ControleView, r: Result<Controle>): ControleView =
    r.fold(onSuccess = { ControleView(it) }, onFailure = { prev.copy(issue = issueOf(it)) })

private const val ANCHOR = "app_souvenir_anchor"
private const val REVIVE = "app_souvenir_revive"
private const val HABIT = "app_habitude_set"
private const val HABIT_FORGET = "app_habitude_forget"

private fun gesture(id: String, tool: String, args: JsonObject) = Card(id = id, title = "", module = MEMOIRE, done = CardDone(tool, args))

fun anchorCard(nature: String, ref: String, ancre: Boolean): Card = gesture("ancre/$nature/$ref", ANCHOR, buildJsonObject {
    put("nature", nature)
    put("ref", ref)
    put("ancre", ancre)
})

fun reviveCard(s: Souvenir): Card = gesture("raviver/${s.nature}/${s.ref}", REVIVE, buildJsonObject {
    put("nature", s.nature)
    put("ref", s.ref)
})

fun habitCard(h: Habitude, confirm: Boolean): Card = gesture("habitude/${h.id}", HABIT, buildJsonObject {
    put("id", h.id)
    put("confirmee", confirm)
})

fun habitErasure(id: Long): Card = gesture("habitude/$id", HABIT_FORGET, buildJsonObject { put("id", id) })

/** Applique le geste [card] à l'état gardé, sans attendre la relecture. */
fun withoutControl(c: Controle, card: Card): Controle {
    val done = card.done ?: return c
    val args = done.args
    fun str(k: String) = args[k]?.jsonPrimitive?.content
    fun flag(k: String) = args[k]?.jsonPrimitive?.boolean == true
    fun same(s: Souvenir) = s.nature == str("nature") && s.ref == str("ref")
    fun withoutFaded(): Controle {
        val left = c.estompes.filterNot(::same)
        return c.copy(estompes = left, estompesTotal = c.estompesTotal - (c.estompes.size - left.size))
    }
    val habit = str("id")?.toLongOrNull()
    return when (done.tool) {
        REVIVE -> withoutFaded()
        ANCHOR -> if (flag("ancre")) withoutFaded() else c.copy(ancres = c.ancres.filterNot(::same))
        HABIT -> c.copy(habitudes = c.habitudes.map { if (it.id == habit) it.copy(confirmee = flag("confirmee"), rejetee = !flag("confirmee")) else it })
        HABIT_FORGET -> c.copy(habitudes = c.habitudes.filterNot { it.id == habit })
        else -> c
    }
}

fun anchorsTitle(c: Controle): String = "Toujours retenu · ${c.ancres.size}/${c.ancresMax}"

private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** « 03/11/2026 » d'une date ou d'un horodatage du module ; null s'il est illisible. */
private fun dayOf(s: String?): String? =
    (localTimeOf(s)?.toLocalDate() ?: s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() })?.format(DAY)

fun fadedHint(s: Souvenir): String {
    val day = dayOf(s.depuis) ?: return "Estompé"
    val fate = when (s.devenir) {
        "condensation" -> " · sera résumé"
        "oubli" -> " · sera oublié"
        else -> ""
    }
    return "Estompé depuis le $day$fate"
}

fun habitStatus(h: Habitude): String {
    if (h.rejetee) return "rejetée"
    val age = if (h.ancienneDepuis == null) "active" else dayOf(h.ancienneDepuis)?.let { "ancienne depuis le $it" } ?: "ancienne"
    return listOfNotNull(age, "s'estompe".takeIf { h.estompee }, if (h.confirmee) "confirmée" else "à confirmer").joinToString(" · ")
}

/** Clé de révélation d'un souvenir sensible. */
fun controlKey(s: Souvenir): String = "${s.nature}/${s.ref}"

/** Texte d'un souvenir, ou null s'il reste masqué : même règle que le journal. */
fun controlText(s: Souvenir, revealed: Set<String>, masked: Boolean): String? =
    if (s.sensitive && (masked || controlKey(s) !in revealed)) null else s.texte

fun natureLabel(n: String): String = when (n) {
    "fait" -> "Fait"
    "journal" -> "Journal"
    "conversation" -> "Conversation"
    "synthese" -> "Résumé"
    "deduction" -> "Déduction"
    else -> n
}
