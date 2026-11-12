package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.kit.sourceName
import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.own
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.sqrt

/** Duel : ne compare que le comparable, côte à côte, sans jamais désigner de vainqueur. */

@Serializable
data class Indicateur(val champ: String = "", val libelle: String = "", val unite: String = "")

/** Indicateurs comparables d'une collection (`rubrique`.`chemin`), rattachés par le champ `cle_identite`. */
@Serializable
data class Comparaison(
    val id: String = "", val titre: String = "", val rubrique: String = "", val chemin: String = "", val cle_identite: String = "",
    val indicateurs: List<Indicateur> = emptyList(), val source: JsonElement? = null,
)

fun comparaisonsOf(data: PolData): List<Comparaison> = data.decode<Comparaison>("comparaisons", "")
    .filter { c -> c.rubrique.isNotBlank() && c.chemin.isNotBlank() && c.cle_identite.isNotBlank() && c.indicateurs.any { it.champ.isNotBlank() } }

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.isRef(): Boolean = (this["ref"] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.lecture(): Lecture? = this["lecture"]?.let { runCatching { PolJson.decodeFromJsonElement(Lecture.serializer(), it) }.getOrNull() }

private fun PolData.rowsOf(c: Comparaison): List<JsonObject> = decode(c.rubrique, c.chemin, JsonObject.serializer())

/** Ligne propre à [id] parmi celles qui le citent (bloc commun : celle qui porte son nom). */
private fun PolData.rowOf(rows: List<JsonObject>, key: String, id: String): JsonObject? =
    rows.filter { !it.isRef() && id in idsIn(it.str(key)) }.own(this, id) { it.str(key).orEmpty() }

/** Ligne propre à un parti ([own]) ; [bloc] : montant partagé avec d'autres. */
private class Own<T>(val row: T, val bloc: Boolean)

/** [Own.bloc] : le libellé de la ligne désigne aussi une autre identité. */
private fun <T> PolData.ownRow(rows: List<T>, id: String, label: (T) -> String): Own<T>? =
    rows.filter { id in idsIn(label(it)) }.own(this, id, label)?.let { r -> Own(r, idsIn(label(r)).any { it != id }) }

private fun PolData.aideRow(id: String) = ownRow(aideOf(this).rows, id) { it.l }
private fun PolData.partiRow(id: String) = ownRow(partisOf(this), id) { it.parti }

/** Note « montant du bloc » pour chaque côté dont la ligne est celle d'un bloc commun. */
private fun PolData.blocNote(a: String, ra: Own<*>?, b: String, rb: Own<*>?, label: (Any?) -> String): String =
    listOf(a to ra, b to rb).filter { it.second?.bloc == true }
        .joinToString(" ") { (id, o) -> "${duelName(this, id)} : montant du bloc « ${shortLabel(label(o!!.row))} »." }

/** Groupe de [id] dans une chambre (`AN`, `PE`) : index et nom ; aucun : null. */
private fun PolData.groupOf(ch: String, id: String): Pair<Int, String>? {
    val groups = decode<String>("votes", "${ch}_G")
    return groupIndex(this, groups, id).takeIf { it >= 0 }?.let { it to groups[it] }
}

/**
 * Famille de duel : une personne qui a au moins une ligne d'indicateurs comparables, un parti qui a une aide,
 * des comptes ou un groupe parlementaire ; sinon (camp, inconnu, rien de comparable) : null.
 */
fun duelKind(data: PolData, id: String?): IdKind? {
    if (id == null || data.byId(id) == null) return null
    return when (data.kindOf(id)) {
        IdKind.Personne -> IdKind.Personne.takeIf { comparaisonsOf(data).any { c -> data.rowOf(data.rowsOf(c), c.cle_identite, id) != null } }
        IdKind.Parti -> IdKind.Parti.takeIf {
            data.aideRow(id) != null || data.partiRow(id) != null || data.groupOf("AN", id) != null || data.groupOf("PE", id) != null
        }
        IdKind.Camp -> null
    }
}

fun duelRoster(data: PolData, kind: IdKind): List<String> = data.identities.map { it.id }.filter { duelKind(data, it) == kind }

fun duelReady(data: PolData, a: String?, b: String?): Boolean = a != null && b != null && a != b && duelKind(data, a).let { it != null && it == duelKind(data, b) }

/** Nom court : pour une personne, l'alias qui termine son nom ; pour un parti au nom long, son sigle. */
fun duelName(data: PolData, id: String): String {
    val x = data.byId(id) ?: return ""
    if (data.kindOf(id) != IdKind.Personne) return if (x.nom.length > 18) x.parti?.takeIf { it.isNotBlank() } ?: x.nom else x.nom
    return data.aliasesOf(id).firstOrNull { it.isNotBlank() && x.nom.endsWith(it) } ?: x.nom
}

// ---------- Couleurs ----------

/** Distance (RVB) en deçà de laquelle deux couleurs d'identité se confondent : le second côté est alors hachuré. */
const val CLOSE_COLORS = 70.0
private const val NEUTRAL = 0x8A938A

private fun rgb(hex: String?): Int = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: NEUTRAL

/** Distance euclidienne entre deux `#RRGGBB` ; couleur absente ou illisible : gris neutre. */
fun colorDistance(a: String?, b: String?): Double {
    val x = rgb(a); val y = rgb(b)
    fun ch(v: Int, s: Int) = ((v shr s) and 0xFF).toDouble()
    return sqrt(listOf(16, 8, 0).sumOf { s -> (ch(x, s) - ch(y, s)).let { it * it } })
}

fun hatchSecond(a: String?, b: String?): Boolean = colorDistance(a, b) < CLOSE_COLORS

// ---------- Sources ----------

private val SRC = Regex("""^(.*?)\s*\((https://[^)\s]+)\)\s*(.*)$""")

/** « Libellé (https://…) ; complément » → libellé et lien ; sans lien, le texte entier. */
fun parseSrc(s: String): SourceRef = SRC.matchEntire(s.trim())?.let { m ->
    SourceRef(m.groupValues[1].trim().ifEmpty { m.groupValues[2].substringAfter("://").substringBefore('/') }, m.groupValues[2])
} ?: SourceRef(s.trim())

private fun sourcesOfComp(e: JsonElement?): List<SourceRef> =
    (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { listOf(parseSrc(it)) } ?: sourcesOf(e)

/** Sources d'une rubrique chiffrée : celles des comparaisons qui la lisent, sans doublon. */
fun rubricSources(data: PolData, rubrique: String): List<SourceRef> =
    comparaisonsOf(data).filter { it.rubrique == rubrique }.flatMap { sourcesOfComp(it.source) }.distinct()

// ---------- Rounds ----------

/** Un round : [avail] dit si chaque côté a une donnée (couverture, jamais un score) ; [rubric] : rubrique du détail. */
sealed interface DuelRound {
    val title: String
    val help: String
    val avail: Pair<Boolean, Boolean>
    val rubric: String?
}

/** Ce que chaque programme sacrifie : la balance des deux côtés, quand l'un au moins en a une. */
data class Sacrifice(val a: Lecture?, val whoA: String?, val b: Lecture?, val whoB: String?)

/**
 * Barres tracées depuis [base] (la référence « sans mesure », sinon zéro) ; [others] : les autres lignes, pour situer sans classer.
 * [gapFormat] : avec une référence, l'écart signé qu'une barre représente (sinon la valeur, signée, est elle-même l'écart à zéro).
 */
class BarRound(
    override val title: String, override val help: String, val a: Double?, val b: Double?, val others: List<Double>, val base: Double,
    val format: (Double) -> String, val sources: List<SourceRef> = emptyList(), override val rubric: String? = null, val sacrifice: Sacrifice? = null,
    val gapFormat: ((Double) -> String)? = null,
) : DuelRound {
    override val avail get() = (a != null) to (b != null)
    private val all get() = listOfNotNull(a, b) + others + base
    /** Plus petite et plus grande valeur (référence comprise) : les bornes affichées. */
    val min get() = all.min()
    val max get() = all.max()
    private val pad get() = (max - min) * 0.06
    private val lo get() = min - pad
    private val hi get() = max + pad

    /** Position de [v] sur la piste, de 0 à 1. */
    fun pos(v: Double): Double = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.5

    fun label(v: Double?): String = v?.let(format) ?: NO_VALUE

    /** Écart de [v] à la référence, signé ; sans référence : null. */
    fun gap(v: Double): String? = gapFormat?.invoke(v)

    fun tableCard(nameA: String, nameB: String) = TableCard(
        "Duel · $title", "$nameA face à $nameB", lines = listOf("$nameA : ${label(a)}${a?.let(::gap)?.let { " ($it)" }.orEmpty()}", "$nameB : ${label(b)}${b?.let(::gap)?.let { " ($it)" }.orEmpty()}", help).filter { it.isNotBlank() },
        source = sources.joinToString(" ; ") { sourceName(it) }.ifBlank { null },
    )
}

/** Position d'un côté : `[code, texte]` (`keep`, `flip`, `mix`) ; null : non trouvée. */
data class BilanLine(val o: String, val a: List<String>?, val b: List<String>?)

class BilanRound(val lines: List<BilanLine>) : DuelRound {
    override val title = "Face au bilan sortant"
    override val help = "Poursuivre ou inverser les grandes orientations du mandat sortant."
    override val avail get() = lines.any { it.a != null } to lines.any { it.b != null }
    override val rubric = "bilan"
}

class GapRound(val a: List<Gap>, val b: List<Gap>) : DuelRound {
    override val title = "Annoncé contre crédité"
    override val help = "La promesse face au chiffrage extérieur."
    override val avail get() = a.isNotEmpty() to b.isNotEmpty()
    override val rubric = "budgets"
}

/** Codes de vote des deux groupes (`P`, `C`, `A`, `S`, `N`) et leurs détails. */
data class VsLine(val t: String, val a: String, val b: String, val da: String, val db: String) {
    val both get() = a != "N" && b != "N"
    val same get() = both && a == b
}

/** [viaParty] : groupes des partis des deux candidats. */
class VotesRound(val chambre: String, val groupA: String?, val groupB: String?, val viaParty: Boolean, val lines: List<VsLine>) : DuelRound {
    override val title = if (chambre == "AN") "Votes à l'Assemblée" else "Votes au Parlement européen"
    override val help = if (groupA != null && groupB != null) "Groupes $groupA et $groupB${if (viaParty) " (groupe du parti)" else ""}." else ""
    override val avail get() = (groupA != null) to (groupB != null)
    override val rubric = "votes"
    val both get() = lines.count { it.both }
    val same get() = lines.count { it.same }
}

/** Dit / voté des partis ; un côté sans aucun verdict comptable n'a pas de donnée (jamais un « 0 / 0 »). */
class DvRound(val partyA: String?, val partyB: String?, val a: List<DvItem>, val b: List<DvItem>, viaParty: Boolean) : DuelRound {
    override val title = "Dit / voté"
    override val help = "Le vote suit-il la parole ? Assemblée et Parlement européen, déclarations dans les six mois autour du vote${if (viaParty) " (parti du candidat)" else ""}."
    override val avail get() = (dvCount(a).t > 0) to (dvCount(b).t > 0)
    override val rubric = "dit-vote"
}

class TurnRound(val a: List<Turn>, val b: List<Turn>) : DuelRound {
    override val title = "Revirements suivis"
    override val help = "Changer d'avis n'est pas un défaut : regardez ce qui change et pourquoi."
    override val avail get() = a.isNotEmpty() to b.isNotEmpty()
    override val rubric = "revirements"
}

/** Mise en page des côtés d'un round : deux colonnes, ou onglets glissables un côté à la fois. */
enum class RoundLayout { SideBySide, Switcher }

/** Au-delà, le texte d'un côté ne tient plus dans une demi-largeur de téléphone. */
const val LONG_SIDE = 120

/** Rédigé : balance, bilan, chiffrage ; liste : dit / voté et revirements selon la longueur de leurs textes. */
fun layoutFor(round: DuelRound): RoundLayout {
    val long = when (round) {
        is BarRound -> round.sacrifice.let { it != null && (it.a != null || it.b != null) }
        is BilanRound, is GapRound -> true
        is VotesRound -> false
        is DvRound -> listOf(round.a, round.b).any { side -> side.filter { it.verdict.v == "ecart" }.sumOf { it.scrutin.titre.length } > LONG_SIDE }
        is TurnRound -> listOf(round.a, round.b).any { side -> side.sumOf { it.s.length + 3 } > LONG_SIDE }
    }
    return if (long) RoundLayout.Switcher else RoundLayout.SideBySide
}

/** Choix : retouché, un adversaire sort (le second devient premier) ; au-delà de deux, le second est remplacé. */
fun pickToggle(sel: List<String>, id: String): List<String> = when {
    id in sel -> sel - id
    sel.size < 2 -> sel + id
    else -> listOf(sel[0], id)
}

/** Légendes de couverture du bandeau ; sans round, aucune (jamais « 0/0 »). */
fun coverageCaption(rounds: List<DuelRound>): Pair<String, String>? =
    if (rounds.isEmpty()) null else coverage(rounds).let { (a, b) -> "Données $a/${rounds.size}" to "Données $b/${rounds.size}" }

/** Rounds dont chaque côté a des données. */
fun coverage(rounds: List<DuelRound>): Pair<Int, Int> = rounds.count { it.avail.first } to rounds.count { it.avail.second }

private val IND_HELP = mapOf(
    "debt" to "Dette publique projetée. Barre tracée depuis la référence « sans mesure ».",
    "deficit" to "Déficit public projeté. Barre tracée depuis la référence « sans mesure ».",
    "net" to "Positif : les mesures améliorent le solde public chaque année. Négatif : elles le creusent.",
    "hh" to "Gain (+) ou perte (−) moyenne par ménage et par an. Une moyenne cache des écarts.",
)

/** Valeur dans son unité : pourcentage, montant signé, ou nombre suivi de l'unité. */
fun fmtUnit(v: Double, unit: String): String = when {
    "%" in unit -> fmtNum(v, 1)
    "Md€" in unit -> fmtSigned(v, 1)
    "€" in unit -> fmtSigned(v)
    else -> fmtNum(v, 1)
} + if (unit.isBlank()) "" else " " + unit.trim()

/** Écart signé de [v] à la référence [ref] nommée [refName] ; un écart entre pourcentages se compte en points. */
fun gapLabel(v: Double, ref: Double, unit: String, refName: String): String =
    fmtSigned(v - ref, 1) + " " + (if ("%" in unit) "pt" else unit.trim()) + " par rapport à « $refName »"

private fun PolData.indicatorRounds(a: String, b: String): List<BarRound> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<BarRound>()
    for (c in comparaisonsOf(this)) {
        val all = rowsOf(c)
        val ref = all.firstOrNull { it.isRef() }
        val rows = all.filter { !it.isRef() }
        val ra = rowOf(all, c.cle_identite, a)
        val rb = rowOf(all, c.cle_identite, b)
        var first = true
        for (ind in c.indicateurs.filter { it.champ.isNotBlank() }) {
            if (!seen.add(ind.champ + "|" + ind.unite)) continue
            val sacrifice = if (first && (ra?.lecture() != null || rb?.lecture() != null))
                Sacrifice(ra?.lecture(), ra?.str(c.cle_identite), rb?.lecture(), rb?.str(c.cle_identite)) else null
            first = false
            out += BarRound(
                ind.libelle.ifBlank { ind.champ }, IND_HELP[ind.champ].orEmpty(), ra?.num(ind.champ), rb?.num(ind.champ),
                rows.filter { it !== ra && it !== rb }.mapNotNull { it.num(ind.champ) }, ref?.num(ind.champ) ?: 0.0,
                { v -> fmtUnit(v, ind.unite) }, sourcesOfComp(c.source), c.rubrique, sacrifice,
                gapFormat = ref?.num(ind.champ)?.let { base -> { v -> gapLabel(v, base, ind.unite, ref.str(c.cle_identite)?.ifBlank { null } ?: "sans mesure") } },
            )
        }
    }
    return out
}

private fun PolData.votesRound(ch: String, a: String, b: String, viaParty: Boolean): VotesRound? {
    if (decode<String>("votes", "${ch}_G").isEmpty()) return null
    val ga = groupOf(ch, a); val gb = groupOf(ch, b)
    fun cell(v: VoteRow, g: Pair<Int, String>?, i: Int) = g?.let { v.c.getOrNull(it.first)?.getOrNull(i) } ?: if (i == 0) "N" else ""
    val lines = if (ga == null || gb == null) emptyList() else decode<VoteRow>("votes", "${ch}_V").map { v ->
        VsLine(v.t, cell(v, ga, 0).ifEmpty { "N" }, cell(v, gb, 0).ifEmpty { "N" }, cell(v, ga, 1), cell(v, gb, 1))
    }
    return VotesRound(ch, ga?.second, gb?.second, viaParty, lines)
}


/** Rounds d'un duel prêt ([duelReady]) ; sinon aucun. */
fun duelRounds(data: PolData, a: String, b: String): List<DuelRound> {
    if (!duelReady(data, a, b)) return emptyList()
    val out = mutableListOf<DuelRound>()
    val person = data.kindOf(a) == IdKind.Personne
    if (person) {
        out += data.indicatorRounds(a, b)
        val matrix = data.decode<MatrixRow>("bilan", "MATRIX")
        fun pos(r: MatrixRow, id: String) = r.c.entries.firstOrNull { id in data.idsIn(it.key) }?.value
        if (matrix.isNotEmpty()) out += BilanRound(matrix.map { BilanLine(it.o, pos(it, a), pos(it, b)) })
        val gaps = data.decode<Gap>("budgets", "GAPS")
        val ga = gaps.filter { a in data.idsIn(it.who) }; val gb = gaps.filter { b in data.idsIn(it.who) }
        if (ga.isNotEmpty() || gb.isNotEmpty()) out += GapRound(ga, gb)
        val pa = data.partyOf(a); val pb = data.partyOf(b)
        if (pa != null && pb != null && pa != pb) data.votesRound("AN", pa, pb, viaParty = true)?.let { out += it }
    } else {
        val aide = aideOf(data).rows
        if (aide.isNotEmpty()) {
            val (ra, rb) = data.aideRow(a) to data.aideRow(b)
            val note = data.blocNote(a, ra, b, rb) { (it as AideRow).l }
            out += BarRound(
                "Aide publique", "Versée par l'État, calculée sur les voix aux dernières législatives et le nombre de parlementaires. $note".trim(),
                ra?.row?.v, rb?.row?.v, aide.filter { it !== ra?.row && it !== rb?.row }.mapNotNull { it.v }, 0.0, ::moneyLabel, rubric = "argent",
            )
        }
        val partis = partisOf(data)
        if (partis.isNotEmpty()) {
            val (oa, ob) = data.partiRow(a) to data.partiRow(b)
            val (ra, rb) = oa?.row to ob?.row
            val note = data.blocNote(a, oa, b, ob) { (it as PartiComptes).parti }
            listOf<Triple<String, String, (PartiComptes) -> Double?>>(
                Triple("Dons et cotisations", "Ce que le parti tire de ses adhérents et donateurs.") { it.dons },
                Triple("Dette du parti", "Ce que le parti doit.") { it.dette },
                Triple("Situation nette", "Patrimoine moins dettes. Un patrimoine immobilier ancien la gonfle.") { it.net },
            ).forEach { (t, h, f) ->
                out += BarRound(t, "$h $note".trim(), ra?.let(f), rb?.let(f), partis.filter { it !== ra && it !== rb }.mapNotNull(f), 0.0,
                    if (t == "Situation nette") ::netLabel else ::moneyLabel, rubric = "argent")
            }
        }
        listOf("AN", "PE").forEach { ch -> data.votesRound(ch, a, b, viaParty = false)?.let { out += it } }
    }
    val pA = if (person) data.partyOf(a) else a
    val pB = if (person) data.partyOf(b) else b
    val dA = dvFor(data, pA); val dB = dvFor(data, pB)
    if (dA.isNotEmpty() || dB.isNotEmpty()) out += DvRound(pA, pB, dA, dB, person)
    val tA = turnsOf(data, a); val tB = turnsOf(data, b)
    if (tA.isNotEmpty() || tB.isNotEmpty()) out += TurnRound(tA, tB)
    return out
}
