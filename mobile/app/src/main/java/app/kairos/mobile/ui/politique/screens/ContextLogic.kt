package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.Row
import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Probité, argent, médias : tout est facultatif, une donnée absente ou mal typée donne une liste vide. */

const val NO_VALUE = "non trouvé"
const val FACTS_MISSING = "Faits non précisés dans la source"
const val PRESUMPTION = "Toute personne est présumée innocente tant qu'elle n'est pas définitivement condamnée."

// ---------- Procédures ----------

/** Étape d'une procédure sur l'échelle de cinq crans ; [relaxe] : fin favorable, pas une peine. */
data class Stage(val label: String, val step: Int, val relaxe: Boolean = false)

private val STAGES = mapOf(
    "enq" to Stage("Enquête", 1), "mex" to Stage("Mise en examen", 2), "proc" to Stage("Procès", 3),
    "rel" to Stage("Relaxe", 4, relaxe = true), "nd" to Stage("Condamnation non définitive", 4), "def" to Stage("Condamnation définitive", 5),
)

fun procedureStage(code: String?): Stage? = STAGES[code]

/** Teinte d'état d'un code d'étape ; null : neutre. */
fun stageTone(code: String?): String? = when (code) { "mex", "nd" -> "partiel"; "def" -> "fragile"; "rel" -> "solide"; else -> null }

/** Dossier de lobbying, de financement ou d'élection municipale ; [stg] : `[code, libellé]`. */
@Serializable
data class Dossier(val t: String = "", val stg: List<String> = emptyList(), val p: String = "", val s: JsonElement? = null) {
    val code: String? get() = stg.getOrNull(0)
    val label: String get() = stg.getOrNull(1).orEmpty()
}

data class Affaire(val who: String, val party: String, val faits: String, val stg: String, val etat: String, val date: String, val url: String) {
    val stage: Stage? get() = procedureStage(stg)
    val factsText: String get() = faits.ifBlank { FACTS_MISSING }
}

private fun PolData.rows(file: String, path: String): List<Row> = decode(file, path, JsonArray.serializer()).map(::Row)

fun affairesOf(data: PolData): List<Affaire> = data.rows("probite", "AFF")
    .map { Affaire(it.s(0), it.s(1), it.s(2), it.s(3), it.s(4), it.s(5), it.s(6)) }
    .filter { listOf(it.who, it.party, it.faits, it.etat, it.url).any(String::isNotBlank) }

fun lobbyOf(data: PolData): List<Dossier> = data.decode<Dossier>("probite", "LOBBY").filter { it.t.isNotBlank() }

fun financingOf(data: PolData): List<Dossier> = data.decode<Dossier>("argent", "FIN").filter { it.t.isNotBlank() }

/** Identité d'un libellé seulement s'il n'en désigne qu'une : jamais l'une au hasard. */
fun PolData.uniqueId(label: String?): String? = idsIn(label).singleOrNull()

/** Initiales (3 au plus) des mots à majuscule. */
fun initials(name: String): String = name.split(Regex("[\\s-]+")).filter { it.firstOrNull()?.isUpperCase() == true }.map { it.first() }.joinToString("").take(3)

fun affaireTableCard(a: Affaire): TableCard = TableCard(
    kicker = "Probité · ${a.stage?.label ?: "Procédure"}",
    title = listOf(a.who, a.party).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Procédure" },
    lines = listOf(a.factsText, a.etat, PRESUMPTION).filter { it.isNotBlank() },
)

fun dossierTableCard(kind: String, d: Dossier): TableCard = TableCard(
    kicker = listOf(kind, d.label).filter { it.isNotBlank() }.joinToString(" · "),
    title = d.t,
    lines = listOf(d.p).filter { it.isNotBlank() },
    source = sourcesOf(d.s).joinToString(" ; ") { it.label }.takeIf { it.isNotBlank() },
)

// ---------- Agenda ----------

data class AgendaItem(val date: String, val text: String, val inDays: Int?, val key: Boolean)

private val DAY = Regex("(\\d{2})/(\\d{2})/(\\d{4})")

fun parseDay(s: String): LocalDate? = DAY.find(s)?.destructured?.let { (d, m, y) -> runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() }

private val KEY_EVENT = Regex("\\btour\\b", RegexOption.IGNORE_CASE)

/** Chronologique ; sans jour exact, la date reste telle quelle et l'élément passe après les datés. « Dans N jours » seulement à venir. */
fun agendaOf(data: PolData): List<AgendaItem> {
    val today = data.verifiedOn?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
    return data.rows("probite", "AGENDA").filter { it.s(1).isNotBlank() }
        .map { r -> Triple(r, parseDay(r.s(0)), r.s(1)) }
        .sortedBy { it.second ?: LocalDate.MAX }
        .map { (r, day, text) ->
            val days = if (today != null && day != null) ChronoUnit.DAYS.between(today, day).toInt().takeIf { it >= 0 } else null
            AgendaItem(r.s(0), text, days, KEY_EVENT.containsMatchIn(text))
        }
}

// ---------- Argent ----------

/** Montant en M€ : deux décimales sous 10, une au-delà ; valeur absente : « non trouvé ». */
fun moneyLabel(v: Double?): String = v?.let { fmtNum(it, if (kotlin.math.abs(it) >= 10) 1 else 2) + " M€" } ?: NO_VALUE

fun netLabel(v: Double?): String = v?.let { fmtSigned(it, 1) + " M€" } ?: NO_VALUE

/** Part de [max] occupée par [v], bornée à 0..1 ; valeur absente ou échelle nulle : 0. */
fun barFraction(v: Double?, max: Double): Float = if (v == null || max <= 0 || v.isNaN()) 0f else (v / max).coerceIn(0.0, 1.0).toFloat()

@Serializable
data class AideRow(val l: String = "", val v: Double? = null)

/** Aide publique : lignes et plafond de la piste (celui de l'échelle publiée, élargi pour laisser la place au chiffre). */
data class Aide(val rows: List<AideRow>, val max: Double)

fun aideOf(data: PolData): Aide {
    val rows = data.decode<AideRow>("argent", "AIDE.rows").filter { it.l.isNotBlank() }
    val max = data.one("argent", "AIDE.scale.max", Double.serializer())?.takeIf { it > 0 }?.times(1.28) ?: rows.mapNotNull { it.v }.maxOrNull()?.takeIf { it > 0 }?.times(1.28) ?: 1.0
    return Aide(rows, max)
}

/** Libellé d'un bloc nommé « Nom (précisions) » : sans les précisions. */
fun shortLabel(l: String): String = l.substringBefore(" (").trim().ifEmpty { l }

data class PartiComptes(val parti: String, val dons: Double?, val dette: Double?, val net: Double?)

fun partisOf(data: PolData): List<PartiComptes> = data.rows("argent", "PARTIS").filter { it.s(0).isNotBlank() }.map { PartiComptes(it.s(0), it.d(1), it.d(2), it.d(3)) }

data class Campagne(val cand: String, val score: String, val depense: Double?, val rembourse: Double?, val note: String)

fun campagnesOf(data: PolData): List<Campagne> = data.rows("argent", "C22").filter { it.s(0).isNotBlank() }.map { Campagne(it.s(0), it.s(1), it.d(2), it.d(3), it.s(4)) }

/** Plus grand montant des campagnes : la piste commune des barres. */
fun campagnesMax(rows: List<Campagne>): Double = rows.flatMap { listOfNotNull(it.depense, it.rembourse) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0

// ---------- Médias ----------

@Serializable
data class Owner(val o: String = "", val g: String = "", val m: List<String> = emptyList(), val n: String = "")

@Serializable
data class Unsellable(val o: String = "", val n: String = "")

data class AudRow(val name: String, val before: Double?, val now: Double?)
data class Audience(val rows: List<AudRow>, val max: Double)
data class RegItem(val date: String, val text: String, val note: String)
data class Study(val kind: String, val author: String, val country: String, val text: String) {
    val kindLabel: String get() = studyLabel(kind)
}
data class Poll(val institute: String, val owner: String, val clients: String)

private val STUDY_LABELS = mapOf("causal" to "Effet causal", "cont" to "Mesure du contenu", "desc" to "Descriptif")
fun studyLabel(kind: String): String = STUDY_LABELS[kind] ?: "Étude"

fun studyTableCard(s: Study): TableCard = TableCard(
    kicker = listOf(s.kindLabel, s.country).filter { it.isNotBlank() }.joinToString(" · "),
    title = s.author,
    lines = listOf(s.text).filter { it.isNotBlank() },
)

data class MediasState(
    val owners: List<Owner>, val unsellable: List<Unsellable>, val audience: Audience,
    val reg: List<RegItem>, val studies: List<Study>, val polls: List<Poll>,
)

fun mediasState(data: PolData): MediasState {
    val aud = data.rows("medias", "AUD.rows").filter { it.s(0).isNotBlank() }.map { AudRow(it.s(0), it.d(1), it.d(2)) }
    val max = data.one("medias", "AUD.max", Double.serializer())?.takeIf { it > 0 }
        ?: aud.flatMap { listOfNotNull(it.before, it.now) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    return MediasState(
        owners = data.decode<Owner>("medias", "OWNERS").filter { it.o.isNotBlank() },
        unsellable = data.decode<Unsellable>("medias", "PROTECTED").filter { it.o.isNotBlank() },
        audience = Audience(aud, max),
        reg = data.rows("medias", "REG").filter { it.s(1).isNotBlank() }.map { RegItem(it.s(0), it.s(1), it.s(2)) },
        studies = data.rows("medias", "STUDIES").filter { it.s(3).isNotBlank() }.map { Study(it.s(0), it.s(1), it.s(2), it.s(3)) },
        polls = data.rows("medias", "POLLS").filter { it.s(0).isNotBlank() }.map { Poll(it.s(0), it.s(1), it.s(2)) },
    )
}
