package app.kairos.mobile.ui.memoire

import app.kairos.mobile.data.Card
import app.kairos.mobile.data.CardDone
import app.kairos.mobile.data.Fact
import app.kairos.mobile.data.Intention
import app.kairos.mobile.data.JournalEntry
import app.kairos.mobile.data.Memory
import app.kairos.mobile.data.MemoireSnapshot
import app.kairos.mobile.ui.finances.issueOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/*
 * Logique pure de l'espace Mémoire : intentions à traiter, journal (entrées sensibles
 * masquées), faits par thème, état hors ligne. Sans Compose ni réseau, testée sur la JVM.
 */

const val MEMOIRE = "memoire"
const val ACCEPT = "acceptee"
const val REFUSE = "refusee"

/** Réponse du module quand l'intention n'est plus à traiter : la carte disparaît sans erreur. */
const val STALE = "Cette intention est échue ou déjà traitée."

/** Échéances proposées à l'ajout rapide : libellé, jours. */
val DEADLINES = listOf("Aujourd'hui" to 0, "3 jours" to 3, "1 semaine" to 7, "1 mois" to 30)

/** Horodatage du module (avec ou sans décalage) à l'heure de [zone]. */
fun localTimeOf(s: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? = s?.let {
    runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(it.take(19)) }.getOrNull()
}

/**
 * Intentions à afficher : l'échéance la plus proche d'abord, sans échéance en fin. Une
 * intention échue disparaît sans relance ; [later] (remises à plus tard aujourd'hui) aussi.
 */
fun openIntentions(list: List<Intention>, now: LocalDateTime, later: Set<Long> = emptySet()): List<Intention> =
    list.filter { it.id !in later && localTimeOf(it.expires)?.isAfter(now) != false }
        .sortedWith(compareBy<Intention> { localTimeOf(it.expires) == null }.thenBy { localTimeOf(it.expires) })

/** « dans 20 min », « dans 5 h », « dans 3 j », « échue » ; null sans échéance lisible. */
fun timeLeft(expires: String?, now: LocalDateTime): String? {
    val at = localTimeOf(expires) ?: return null
    val left = Duration.between(now, at)
    return when {
        left.isNegative || left.isZero -> "échue"
        left.toHours() < 1 -> "dans ${left.toMinutes().coerceAtLeast(1)} min"
        left.toDays() < 1 -> "dans ${left.toHours()} h"
        else -> "dans ${left.toDays()} j"
    }
}

fun stateLabel(state: String): String = when (state) {
    "acceptee" -> "acceptée"
    "proposee" -> "proposée"
    "ouverte" -> "ouverte"
    else -> state
}

/** Clé de la marque « plus tard » d'une intention (marques du jour, locales à l'appareil). */
fun laterKey(id: Long): String = "$LATER$id"

/** Intentions remises à plus tard, d'après les clés marquées du jour. */
fun laterIds(keys: Set<String>): Set<Long> = keys.filter { it.startsWith(LATER) }.mapNotNull { it.removePrefix(LATER).toLongOrNull() }.toSet()

private const val LATER = "$MEMOIRE/intention/"

/** Accepter ou refuser, sous la forme d'un geste « fait » du fil : même fenêtre d'annulation. */
fun intentionCard(i: Intention, state: String): Card = Card(
    id = i.id.toString(),
    title = i.what,
    module = MEMOIRE,
    done = CardDone("app_intention_set", buildJsonObject {
        put("id", i.id)
        put("state", state)
    }),
)

/** Retire une intention traitée de l'état gardé, sans attendre la relecture. */
fun withoutIntention(s: MemoireSnapshot, id: Long): MemoireSnapshot =
    s.copy(memory = s.memory?.let { m -> m.copy(intentions = m.intentions.filterNot { it.id == id }) })

/** Effacements définitifs, sous la forme d'un geste « fait » : même fenêtre d'annulation. */
fun journalErasure(id: Long): Card = erasure("journal/$id", "app_journal_delete", buildJsonObject { put("id", id) })
fun factErasure(key: String): Card = erasure("fait/$key", "app_fact_forget", buildJsonObject { put("key", key) })
fun conversationErasure(id: Long): Card = erasure("conversation/$id", "app_conversation_delete", buildJsonObject { put("id", id) })

private fun erasure(id: String, tool: String, args: JsonObject) = Card(id = id, title = "", module = MEMOIRE, done = CardDone(tool, args))

/** Retire de l'état gardé l'élément effacé par [c], sans attendre la relecture. */
fun withoutErased(s: MemoireSnapshot, c: Card): MemoireSnapshot {
    val (kind, ref) = c.id.split('/', limit = 2)
    return s.copy(memory = s.memory?.let { m ->
        when (kind) {
            "journal" -> m.copy(journal = m.journal.filterNot { it.id.toString() == ref })
            "fait" -> m.copy(facts = m.facts.filterNot { it.key == ref })
            "conversation" -> m.copy(conversations = m.conversations.filterNot { it.id.toString() == ref })
            else -> m
        }
    })
}

/** Texte d'une entrée, ou null si elle reste masquée : sensible, pas révélée, ou montants masqués. */
fun shownText(e: JournalEntry, revealed: Set<Long>, masked: Boolean): String? =
    if (e.sensitive && (masked || e.id !in revealed)) null else e.text

data class FactGroup(val theme: String, val facts: List<Fact>)

private const val GENERAL = "Général"

/** Thème lisible d'un préfixe de clé. */
fun themeLabel(prefix: String): String = when (prefix) {
    "sante" -> "Santé"
    else -> prefix.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

/** Nom d'un fait sans son thème : « groupe sanguin ». */
fun factLabel(key: String): String = key.substringAfter('.').replace('_', ' ')

/** Faits groupés par thème (préfixe de clé), thèmes et clés triés ; sans préfixe : « Général », en fin. */
fun factGroups(facts: List<Fact>): List<FactGroup> =
    facts.groupBy { if ('.' in it.key) themeLabel(it.key.substringBefore('.')) else GENERAL }
        .map { (theme, list) -> FactGroup(theme, list.sortedBy { it.key }) }
        .sortedWith(compareBy<FactGroup> { it.theme == GENERAL }.thenBy { it.theme })

/** Raison de refuser une correction, ou null si elle peut partir. */
fun factEditError(value: String, current: String): String? = when {
    value.isBlank() -> "La valeur ne peut pas être vide."
    value.trim() == current.trim() -> "Valeur inchangée."
    else -> null
}

fun factSetArgs(key: String, value: String): JsonObject = buildJsonObject {
    put("key", key)
    put("value", value.trim())
}

/** Nouvel état après une lecture : la réponse remplace l'ancien ; sinon il est gardé avec la raison. */
fun memoireAfter(prev: MemoireSnapshot, memory: Result<Memory>, now: String): MemoireSnapshot =
    memory.fold(
        onSuccess = { MemoireSnapshot(it, now, null) },
        onFailure = { prev.copy(issue = issueOf(it)) },
    )

/** Ce qui peut être écrit sur l'appareil : jamais une entrée sensible. */
fun cacheable(s: MemoireSnapshot): MemoireSnapshot =
    s.copy(memory = s.memory?.let { m -> m.copy(journal = m.journal.filterNot { it.sensitive }) })
