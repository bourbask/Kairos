package app.kairos.mobile.ui.politique.screens

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
import kotlin.math.ceil
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

/** Groupe de [id] dans une chambre (`AN`, `PE`) : index et nom ; aucun : null. */
private fun PolData.groupOf(ch: String, id: String): Pair<Int, String>? {
    val groups = decode<String>("votes", "${ch}_G")
    return groupIndex(this, groups, id).takeIf { it >= 0 }?.let { it to groups[it] }
}

// ---------- Fiches des partis et des camps (fichiers facultatifs) ----------

@Serializable data class Rattachement(val camp: String? = null, val src: JsonElement? = null, val note: String? = null)
@Serializable data class Nuance(val code: String? = null, val bloc: String? = null, val src: JsonElement? = null)
@Serializable data class Datee(val date: String? = null, val src: JsonElement? = null)
@Serializable data class NomPrecedent(val nom: String = "", val jusqu_au: String? = null)
@Serializable data class Fondateurs(val valeur: List<String>? = null, val src: JsonElement? = null)
@Serializable data class Direction(val nom: String? = null, val titre: String? = null, val depuis: String? = null, val src: JsonElement? = null)
@Serializable data class Chambre(val rattaches: Int? = null, val groupe: String? = null, val note: String? = null, val src: JsonElement? = null)
@Serializable data class Europe(val elus: Int? = null, val groupe: String? = null, val src: JsonElement? = null)
@Serializable data class Sieges(val an: Chambre? = null, val senat: Chambre? = null, val pe: Europe? = null)
@Serializable data class AidePublique(val annee: Int? = null, val total: Double? = null, val note: String? = null, val src: JsonElement? = null)
@Serializable data class Adherents(val nombre: Int? = null, val date: String? = null, val src: JsonElement? = null)
@Serializable data class Jalon(val date: String? = null, val fait: String = "")

/** Fiche d'un parti (`partis.json`) : faits datés et sourcés ; toute valeur absente reste absente, jamais zéro. */
@Serializable
data class PartiFiche(
    val id: String = "", val nom: String = "", val rattachement: Rattachement? = null, val nuance_ministere: Nuance? = null,
    val creation: Datee? = null, val noms_precedents: List<NomPrecedent> = emptyList(), val fondateurs: Fondateurs? = null,
    val dirigeant: Direction? = null, val sieges: Sieges? = null, val aide_publique: AidePublique? = null,
    val adherents: Adherents? = null, val historique: List<Jalon> = emptyList(),
)

@Serializable data class Declare(val id: String = "", val date: String? = null, val src: JsonElement? = null)
@Serializable data class Declares(val nombre: Int? = null, val liste: List<Declare> = emptyList())
@Serializable data class CampSieges(val an_rattaches: Int? = null, val senat_rattaches: Int? = null, val pe_elus: Int? = null, val src: JsonElement? = null)
@Serializable data class CampAide(val annee: Int? = null, val total: Double? = null, val src: JsonElement? = null)
@Serializable data class Liste(val liste: String = "", val pct_exprimes: Double? = null, val sieges: Int? = null)
@Serializable data class Urnes(val listes: List<Liste> = emptyList(), val pct_exprimes: Double? = null, val sieges: Int? = null, val src: JsonElement? = null)

/** Chiffres d'un camp (`camps.json`) : sommes et décomptes de ses partis, jamais de moyenne. */
@Serializable
data class CampFiche(
    val id: String = "", val nom: String = "", val partis: List<String> = emptyList(), val candidats_declares: Declares? = null,
    val sieges: CampSieges? = null, val aide_publique: CampAide? = null, val europeennes_2024: Urnes? = null,
)

fun partiFiches(data: PolData): Map<String, PartiFiche> = data.decode<PartiFiche>("partis", "PARTIS").filter { it.id.isNotBlank() }.associateBy { it.id }
fun campFiches(data: PolData): List<CampFiche> = data.decode<CampFiche>("camps", "CAMPS").filter { it.id.isNotBlank() }

/** Voix d'un camp aux européennes : la somme publiée, sinon celle de ses listes (les pourcentages s'additionnent). */
fun campVotes(c: CampFiche): Double? = c.europeennes_2024?.let { u -> u.pct_exprimes ?: u.listes.mapNotNull { it.pct_exprimes }.takeIf { it.isNotEmpty() }?.sum() }

fun campSeats(c: CampFiche): Int? = c.europeennes_2024?.let { u -> u.sieges ?: u.listes.mapNotNull { it.sieges }.takeIf { it.isNotEmpty() }?.sum() }

// ---------- Choix des adversaires ----------

/**
 * Famille de duel : une personne qui a au moins une ligne d'indicateurs comparables ; un parti qui a une fiche,
 * un groupe à l'Assemblée ou des positions suivies ; un camp chiffré dans `camps.json`. Sinon : null.
 */
fun duelKind(data: PolData, id: String?): IdKind? {
    if (id == null || data.byId(id) == null) return null
    return when (data.kindOf(id)) {
        IdKind.Personne -> IdKind.Personne.takeIf { comparaisonsOf(data).any { c -> data.rowOf(data.rowsOf(c), c.cle_identite, id) != null } }
        IdKind.Parti -> IdKind.Parti.takeIf { id in partiFiches(data) || data.groupOf("AN", id) != null || turnsOf(data, id).isNotEmpty() }
        IdKind.Camp -> IdKind.Camp.takeIf { campFiches(data).any { it.id == id } }
    }
}

/** Adversaires possibles d'une famille, dans l'ordre alphabétique des identités. */
fun duelRoster(data: PolData, kind: IdKind): List<String> = data.identities.map { it.id }.filter { duelKind(data, it) == kind }

fun duelReady(data: PolData, a: String?, b: String?): Boolean = a != null && b != null && a != b && duelKind(data, a).let { it != null && it == duelKind(data, b) }

/** Nom court : pour une personne, l'alias qui termine son nom ; pour un parti au nom long, son sigle. */
fun duelName(data: PolData, id: String): String {
    val x = data.byId(id) ?: return ""
    if (data.kindOf(id) != IdKind.Personne) return if (x.nom.length > 18) x.parti?.takeIf { it.isNotBlank() } ?: x.nom else x.nom
    return data.aliasesOf(id).firstOrNull { it.isNotBlank() && x.nom.endsWith(it) } ?: x.nom
}

/** Sous-titre : « Camp », le nom complet d'un parti affiché par son sigle (sinon « Parti »), le parti d'une personne. */
fun duelSub(data: PolData, id: String): String {
    val x = data.byId(id) ?: return ""
    return when (data.kindOf(id)) {
        IdKind.Camp -> "Camp"
        IdKind.Parti -> if (duelName(data, id) != x.nom) x.nom else "Parti"
        IdKind.Personne -> x.parti.orEmpty()
    }
}

/** Case « au hasard » : tirée au lancement. */
const val RANDOM = "?"

/** Sélection : deux places (P1, P2), chacune vide, un id ou [RANDOM] ; [active] : la place que remplit le prochain choix. */
data class Pick(val sel: List<String?> = listOf(null, null), val active: Int = 0) {
    val ready get() = sel.all { it != null }
}

/** Toucher une tuile : retouchée, elle sort ; sinon elle remplit la place active, à défaut P1, puis P2. [RANDOM] se pose toujours. */
fun pickTap(p: Pick, id: String): Pick {
    val s = p.sel.toMutableList()
    val at = if (id == RANDOM) -1 else s.indexOf(id)
    if (at >= 0) { s[at] = null; return Pick(s, at) }
    val i = if (s[p.active] == null) p.active else if (s[0] == null) 0 else 1
    s[i] = id
    return Pick(s, if (s[0] == null) 0 else if (s[1] == null) 1 else i)
}

/** Toucher un cadre : il se vide et devient la place active. */
fun slotTap(p: Pick, i: Int): Pick = Pick(p.sel.toMutableList().also { it[i] = null }, i)

/** Tire les cases [RANDOM] dans [pool] (déjà mélangé) : jamais l'adversaire d'en face ni deux fois le même. */
fun resolvePick(sel: List<String?>, pool: List<String>): List<String>? {
    val r = sel.toMutableList()
    r.indices.forEach { i -> if (r[i] == RANDOM) r[i] = pool.firstOrNull { it != r[1 - i] && it !in r } }
    return r.takeIf { it.all { v -> v != null && v != RANDOM } && it[0] != it[1] }?.map { it!! }
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

/** Sources d'un pied de carte : sans doublon d'adresse (ni de libellé sans adresse), [max] au plus. */
fun footSources(list: List<SourceRef>, max: Int): List<SourceRef> =
    list.filter { it.label.isNotBlank() || it.url != null }.distinctBy { it.url ?: it.label }.take(max)

// ---------- Format ----------

const val NO_DATA = "—"
const val NOT_GIVEN = "non renseigné"

/** Valeur dans son unité : sièges entiers, montants signés, pourcentages et millions à une décimale. */
fun fmtUnit(v: Double, unit: String): String = when {
    unit == "sièges" -> fmtNum(v, 0)
    unit == "M€" -> fmtNum(v, 1)
    "%" in unit -> fmtNum(v, 1)
    "Md€" in unit -> fmtSigned(v, 1)
    "€" in unit -> fmtSigned(v)
    else -> fmtNum(v, 1)
} + if (unit.isBlank()) "" else " " + unit.trim()

/** Écart signé de [v] à la référence [ref] nommée [refName] ; un écart entre pourcentages se compte en points ; nul à l'affichage : « égal ». */
fun gapLabel(v: Double, ref: Double, unit: String, refName: String): String =
    if (Math.round((v - ref) * 10) == 0L) "égal à « $refName »"
    else fmtSigned(v - ref, 1) + " " + (if ("%" in unit) "pt" else unit.trim()) + " par rapport à « $refName »"

/** `AAAA-MM-JJ` → `JJ/MM/AAAA`, `AAAA-MM` → `MM/AAAA` ; autre forme : telle quelle. */
fun slashDate(d: String?): String? = when {
    d.isNullOrBlank() -> null
    d.length == 10 && d[4] == '-' -> "${d.substring(8)}/${d.substring(5, 7)}/${d.substring(0, 4)}"
    d.length == 7 && d[4] == '-' -> "${d.substring(5)}/${d.substring(0, 4)}"
    else -> d
}

fun euros(v: Double?): String? = v?.let { fmtNum(it, 0) + " €" }

// ---------- Jauges ----------

/**
 * Jauge : valeurs des deux côtés, barres tracées depuis [base] (la référence [refName], sinon zéro) ;
 * [others] : les autres, en repères gris pour situer sans classer.
 */
class Gauge(val title: String, val unit: String, val a: Double?, val b: Double?, val others: List<Double>, val base: Double = 0.0, val refName: String? = null) {
    private val all get() = listOfNotNull(a, b) + others + base
    val min get() = all.min()
    val max get() = all.max()
    private val pad get() = (max - min) * 0.06
    private val lo get() = min - pad
    private val hi get() = max + pad

    /** Position de [v] sur la piste, de 0 à 1. */
    fun pos(v: Double): Double = if (hi > lo) ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.5

    fun label(v: Double?): String = v?.let { fmtUnit(it, unit) } ?: NO_DATA

    /** Écart de [v] à la référence ; sans référence : null (la valeur signée suffit). */
    fun gap(v: Double): String? = refName?.let { gapLabel(v, base, unit, it) }

    /** Bornes de l'échelle, sans l'unité. */
    val scale: Pair<String, String> get() = fmtUnit(min, unit).substringBefore(' ') to fmtUnit(max, unit).substringBefore(' ')

    val baseLabel: String get() = "trait : " + (refName?.let { "« $it » ${fmtNum(base, 1)}" } ?: "zéro")

    val avail get() = (a != null) to (b != null)
}

// ---------- Rounds ----------

/** Rubrique du détail : id (voir `RUBRICS`) et libellé de l'onglet. */
data class Rubric(val id: String, val label: String)

private val BUDGETS = Rubric("budgets", "Budgets")
private val BILAN = Rubric("bilan", "Bilan")
private val VOTES = Rubric("votes", "Votes")
private val TURNS = Rubric("revirements", "Revirements")
private val ARGENT = Rubric("argent", "Argent")
private val CARTE = Rubric("carte", "Carte")

/** Un round : [avail] dit si chaque côté a une donnée (couverture, jamais un score) ; [help] : paragraphes de l'aide. */
sealed interface DuelRound {
    val title: String
    val help: List<String>
    val rubric: Rubric
    val sources: List<SourceRef>
    val avail: Pair<Boolean, Boolean>
}

class FinRound(override val title: String, val gauges: List<Gauge>, override val help: List<String>, override val rubric: Rubric, override val sources: List<SourceRef>) : DuelRound {
    override val avail get() = gauges.any { it.a != null } to gauges.any { it.b != null }
}

/** Effet par ménage, puis ce que chaque programme sacrifie ([la], [lb] : balances ; [whoA], [whoB] : libellés de leurs lignes). */
class WalletRound(val gauge: Gauge, val la: Lecture?, val lb: Lecture?, val whoA: String?, val whoB: String?, override val sources: List<SourceRef>) : DuelRound {
    override val title = "Porte-monnaie"
    override val help = listOf(
        "Gain (+) ou perte (−) moyenne par ménage et par an : une moyenne cache des écarts.",
        "Dessous : glisser du contexte au détail (qui perd, combien, quelle décision).",
    )
    override val rubric = BUDGETS
    override val avail get() = (gauge.a != null || la != null) to (gauge.b != null || lb != null)
}

class GapRound(val a: List<Gap>, val b: List<Gap>) : DuelRound {
    override val title = "Annoncé contre crédité"
    override val help = listOf("Barre creuse : le chiffre annoncé par le candidat. Barre pleine : celui qu'un évaluateur extérieur retient.")
    override val rubric = BUDGETS
    override val sources get() = footSources((a + b).mapNotNull { g -> g.src?.takeIf { it.isNotBlank() }?.let { SourceRef(it, g.url, g.ev) } }, 6)
    override val avail get() = a.isNotEmpty() to b.isNotEmpty()
}

/** Position d'un côté : `[code, texte]` (`keep`, `flip`, `mix`) ; null : non trouvée. */
data class BilanLine(val o: String, val s: String, val a: List<String>?, val b: List<String>?)

class BilanRound(val lines: List<BilanLine>, override val sources: List<SourceRef> = emptyList()) : DuelRound {
    override val title = "Face au bilan sortant"
    override val help = listOf("Poursuivre, modifier ou inverser les grandes orientations du mandat sortant, d'après la position publique de chacun.")
    override val rubric = BILAN
    override val avail get() = lines.any { it.a != null } to lines.any { it.b != null }
}

/** Cas de comparaison d'un scrutin : aucun ne met en avant la ressemblance. */
enum class VCase(val label: String) {
    Same("Même vote"),
    Clash("Opposition nette : l'un pour, l'autre contre"),
    Part("Écart partiel : un groupe s'abstient ou se divise, l'autre vote"),
    None("Pas de comparaison : un groupe n'a pas voté"),
}

fun vcase(a: String, b: String): VCase = when {
    a == "N" || b == "N" -> VCase.None
    a == b -> VCase.Same
    setOf(a, b) == setOf("P", "C") -> VCase.Clash
    else -> VCase.Part
}

/** Codes de vote des deux groupes (`P`, `C`, `A`, `S`, `N`) ; [u] : lien du scrutin. */
data class VsLine(val t: String, val u: String?, val a: String, val b: String) {
    val case get() = vcase(a, b)
}

/** [viaParty] : groupes des partis des deux candidats. */
class VotesRound(val groupA: String, val groupB: String, val viaParty: Boolean, val lines: List<VsLine>) : DuelRound {
    override val title = "Votes à l'Assemblée"
    override val help = listOf("Groupes $groupA et $groupB : ${if (viaParty) "le groupe du parti de chaque candidat" else "le groupe de chaque parti"}.")
    override val rubric = VOTES
    override val sources = listOf(SourceRef("Assemblée nationale", "https://www.assemblee-nationale.fr/dyn/16/scrutins", "assemblee-nationale"))
    override val avail get() = true to true
    /** Scrutins où les deux groupes ont voté. */
    val both get() = lines.count { it.case != VCase.None }
    /** Oppositions nettes parmi eux : le chiffre de tête. */
    val clash get() = lines.count { it.case == VCase.Clash }
}

class TurnRound(val a: List<Turn>, val b: List<Turn>) : DuelRound {
    override val title = "Revirements suivis"
    override val help = listOf("Changer d'avis n'est pas un défaut. Toucher un sujet : avant, puis après.")
    override val rubric = TURNS
    override val sources get() = footSources((a + b).flatMap { sourcesOf(it.src) }.distinctBy { it.label }, 4)
    override val avail get() = a.isNotEmpty() to b.isNotEmpty()
}

/** Ligne d'une colonne de fiche. */
sealed interface Line

/** Valeur d'une rubrique : [v] null s'écrit [nr] ; vide : seulement les précisions [small]. */
data class KvVal(val v: String?, val small: String? = null, val nr: String = NOT_GIVEN)

data class Kv(val k: String, val vals: List<KvVal>) : Line {
    constructor(k: String, v: String?, small: String? = null, nr: String = NOT_GIVEN) : this(k, listOf(KvVal(v, small, nr)))
}

/** Camp retenu : [id] null s'écrit « aucun camp ». */
data class CampLine(val k: String, val id: String?) : Line
data class Tiles(val ids: List<String>) : Line
data class Big(val text: String) : Line
data class Note(val text: String) : Line

sealed interface CardBlock
data class Cols(val title: String, val a: List<Line>, val b: List<Line>) : CardBlock
data class GaugeBlock(val gauge: Gauge) : CardBlock

/** Fiche côte à côte (partis, camps) : sections en deux colonnes et jauges. */
class CardRound(
    override val title: String, val blocks: List<CardBlock>, override val help: List<String>, override val rubric: Rubric,
    override val sources: List<SourceRef>, override val avail: Pair<Boolean, Boolean>,
) : DuelRound

/** Rounds dont chaque côté a des données. */
fun coverage(rounds: List<DuelRound>): Pair<Int, Int> = rounds.count { it.avail.first } to rounds.count { it.avail.second }

/** Indicateurs du porte-monnaie ; les autres vont aux finances publiques. */
private val WALLET_FIELDS = setOf("hh")

private fun PolData.candidateRounds(a: String, b: String): List<DuelRound> {
    val out = mutableListOf<DuelRound>()
    val fin = mutableListOf<Gauge>()
    val finSrc = mutableListOf<SourceRef>()
    var wallet: WalletRound? = null
    val seen = mutableSetOf<String>()
    for (c in comparaisonsOf(this)) {
        val all = rowsOf(c)
        val ref = all.firstOrNull { it.isRef() }
        val rows = all.filter { !it.isRef() }
        val ra = rowOf(all, c.cle_identite, a)
        val rb = rowOf(all, c.cle_identite, b)
        val src = sourcesOfComp(c.source)
        for (ind in c.indicateurs.filter { it.champ.isNotBlank() }) {
            if (!seen.add(ind.champ + "|" + ind.unite)) continue
            val base = ref?.num(ind.champ)
            val g = Gauge(
                ind.libelle.ifBlank { ind.champ }, ind.unite, ra?.num(ind.champ), rb?.num(ind.champ),
                rows.filter { it !== ra && it !== rb }.mapNotNull { it.num(ind.champ) }, base ?: 0.0,
                base?.let { ref.str(c.cle_identite)?.ifBlank { null } ?: "Sans mesure" },
            )
            if (ind.champ in WALLET_FIELDS) {
                if (wallet != null) continue
                val la = ra?.lecture(); val lb = rb?.lecture()
                val avis = listOfNotNull(la, lb).flatMap { it.avis }.map { SourceRef(it.ev, it.url, it.ev) }
                wallet = WalletRound(g, la, lb, ra?.str(c.cle_identite), rb?.str(c.cle_identite), footSources(src + avis.distinctBy { it.ev }, 4))
            } else {
                fin += g
                finSrc += src
            }
        }
    }
    if (fin.isNotEmpty()) out += FinRound(
        "Finances publiques", fin,
        listOf("Projections pour 2032. Trait : la trajectoire « Sans mesure » ; zéro pour l'effet net.", "Repères gris : les autres candidats, pour situer sans classer."),
        BUDGETS, footSources(finSrc, 4),
    )
    wallet?.let { out += it }
    val gaps = decode<Gap>("budgets", "GAPS")
    val ga = gaps.filter { a in idsIn(it.who) }; val gb = gaps.filter { b in idsIn(it.who) }
    if (ga.isNotEmpty() || gb.isNotEmpty()) out += GapRound(ga, gb)
    val matrix = decode<MatrixRow>("bilan", "MATRIX")
    fun pos(r: MatrixRow, id: String) = r.c.entries.firstOrNull { id in idsIn(it.key) }?.value
    if (matrix.isNotEmpty()) out += BilanRound(
        matrix.map { BilanLine(it.o, it.s, pos(it, a), pos(it, b)) },
        footSources(matrix.flatMap { it.lecture?.avis.orEmpty() }.distinctBy { it.ev }.map { SourceRef(it.ev, it.url, it.ev) }, 4),
    )
    return out
}

private fun PolData.votesRound(a: String?, b: String?, viaParty: Boolean): VotesRound? {
    val ga = a?.let { groupOf("AN", it) } ?: return null
    val gb = b?.let { groupOf("AN", it) } ?: return null
    if (ga.second == gb.second) return null
    fun code(v: VoteRow, g: Pair<Int, String>) = v.c.getOrNull(g.first)?.getOrNull(0)?.takeIf { it in VOTE_WORDS } ?: "N"
    return VotesRound(ga.second, gb.second, viaParty, decode<VoteRow>("votes", "AN_V").map { v -> VsLine(v.t, v.u, code(v, ga), code(v, gb)) })
}

private val BLOCS = mapOf("EXG" to "extrême gauche", "GAU" to "gauche", "DIV" to "divers", "CENT" to "centre", "DTE" to "droite", "EXD" to "extrême droite")

private fun absent() = listOf<Line>(Note("Fiche absente."))

private fun origin(f: PartiFiche?): List<Line> = f?.let {
    listOf(
        Kv("Création", slashDate(f.creation?.date)),
        if (f.noms_precedents.isEmpty()) Kv("Prédécesseur", "aucun")
        else Kv("Prédécesseur", f.noms_precedents.map { n -> KvVal(n.nom, slashDate(n.jusqu_au)?.let { "jusqu'en $it" }) }),
        Kv("Fondateurs", f.fondateurs?.valeur?.takeIf { it.isNotEmpty() }?.joinToString(", ")),
        Kv("Dirigeant", f.dirigeant?.nom?.takeIf { it.isNotBlank() }, f.dirigeant?.let { d -> (d.titre.orEmpty() + (slashDate(d.depuis)?.let { ", depuis le $it" } ?: "")).ifBlank { null } }),
    )
} ?: absent()

private fun seats(f: PartiFiche?): List<Line> = f?.let {
    val an = f.sieges?.an
    listOf(
        if (an?.rattaches == null) Kv("Assemblée nationale", null, an?.note) else Kv("Assemblée nationale", "${fmtNum(an.rattaches.toDouble())} rattachés", an.groupe?.let { "groupe $it" }),
        Kv("Sénat", f.sieges?.senat?.rattaches?.let { "${fmtNum(it.toDouble())} rattachés" }),
        Kv("Parlement européen", f.sieges?.pe?.elus?.let { "${fmtNum(it.toDouble())} élus" }, f.sieges?.pe?.takeIf { it.elus != null }?.groupe),
    )
} ?: emptyList()

private fun means(f: PartiFiche?): List<Line> = f?.let {
    val ap = f.aide_publique
    val ad = f.adherents
    listOf(
        Kv("Aide publique ${ap?.annee ?: ""}".trim(), euros(ap?.total), if (ap?.total == null) ap?.note else null),
        Kv("Adhérents", ad?.nombre?.let { fmtNum(it.toDouble()) }, ad?.nombre?.let { "déclaré par le parti" + (slashDate(ad.date)?.let { d -> ", $d" } ?: "") }),
    )
} ?: emptyList()

private fun milestones(f: PartiFiche?): List<Line> = f?.let {
    if (f.historique.isEmpty()) listOf(Note("Aucun jalon.")) else f.historique.map { Kv(slashDate(it.date) ?: NO_DATA, it.fait) }
} ?: emptyList()

/** Libellé d'une référence réduit à ses deux premiers éléments (auteur, titre). */
private fun shortRef(label: String): String = label.split(",").take(2).joinToString(",")

private fun position(f: PartiFiche?): List<Line> = f?.let {
    val r = f.rattachement
    val refs = sourcesOf(r?.src)
    val m = f.nuance_ministere
    buildList {
        add(CampLine("Camp retenu", r?.camp))
        add(if (refs.isEmpty()) Kv("Sources", null, nr = "classement non établi par la littérature") else Kv("Sources", refs.map { KvVal("", shortRef(it.label)) }))
        r?.note?.takeIf { it.isNotBlank() && refs.isNotEmpty() }?.let { add(Kv("Note", "", it)) }
        add(Kv("Classement du ministère", m?.code?.takeIf { it.isNotBlank() }, m?.code?.let { "bloc ${BLOCS[m.bloc] ?: m.bloc ?: NOT_GIVEN}, pour information" }))
    }
} ?: absent()

private fun PolData.partyRounds(a: String, b: String): List<DuelRound> {
    val fiches = partiFiches(this)
    val f = listOf(fiches[a], fiches[b])
    if (f.all { it == null }) return emptyList()
    val avail = (f[0] != null) to (f[1] != null)
    fun src(vararg s: JsonElement?) = s.flatMap { sourcesOf(it) }
    val idSrc = f.filterNotNull().flatMap { x ->
        src(x.creation?.src, x.fondateurs?.src, x.dirigeant?.src, x.sieges?.an?.src, x.sieges?.pe?.src, x.aide_publique?.src, x.adherents?.src)
    }
    return listOf(
        CardRound(
            "Carte d'identité",
            listOf(
                Cols("Origines", origin(f[0]), origin(f[1])), Cols("Sièges", seats(f[0]), seats(f[1])),
                Cols("Moyens", means(f[0]), means(f[1])), Cols("Jalons", milestones(f[0]), milestones(f[1])),
            ),
            listOf("Faits datés et sourcés. Sièges : parlementaires rattachés au parti pour l'aide publique. Adhérents : chiffre déclaré par le parti. Une valeur absente est « non renseigné », jamais zéro."),
            ARGENT, footSources(idSrc, 6), avail,
        ),
        CardRound(
            "Positionnement", listOf(Cols("Camp et classement", position(f[0]), position(f[1]))),
            listOf("Le camp suit la littérature de science politique et les encyclopédies signées, même règle pour tous. Le classement du ministère de l'Intérieur est montré pour information : il ne fonde jamais le camp."),
            CARTE, footSources(f.filterNotNull().flatMap { sourcesOf(it.rattachement?.src) } + f.filterNotNull().flatMap { sourcesOf(it.nuance_ministere?.src) }, 6), avail,
        ),
    )
}

private const val CAMP_RULE = "Sommes et décomptes des partis du camp, jamais de moyenne. Un camp n'a pas de programme commun."

private fun PolData.campRounds(a: String, b: String): List<DuelRound> {
    val all = campFiches(this)
    val k = listOf(all.firstOrNull { it.id == a }, all.firstOrNull { it.id == b })
    val rest = all.filter { it.id != a && it.id != b }
    fun gauge(title: String, unit: String, f: (CampFiche) -> Double?) = Gauge(title, unit, k[0]?.let(f), k[1]?.let(f), rest.mapNotNull(f))
    fun fin(title: String, gauges: List<Gauge>, help: String, rubric: Rubric, src: List<SourceRef>) =
        CardRound(title, gauges.map(::GaugeBlock), listOf(CAMP_RULE, help), rubric, footSources(src, 6), gauges.any { it.a != null } to gauges.any { it.b != null })
    fun comp(c: CampFiche?): List<Line> = c?.let { listOf(Tiles(it.partis.filter { id -> byId(id) != null })) } ?: listOf(Note("Non renseigné."))
    fun cands(c: CampFiche?): List<Line> = c?.let {
        listOf(Big(c.candidats_declares?.nombre?.toString() ?: NO_DATA)) +
            (c.candidats_declares?.liste.orEmpty().takeIf { it.isNotEmpty() }?.map { x -> Kv(byId(x.id)?.nom ?: x.id, "", slashDate(x.date)?.let { "déclaré le $it" }) }
                ?: listOf(Note("Aucun candidat déclaré.")))
    } ?: emptyList()
    fun lists(c: CampFiche?): List<Line> = c?.europeennes_2024?.listes.orEmpty().map { l ->
        Kv(l.liste, l.pct_exprimes?.let { fmtNum(it, 2) + " %" }, l.sieges?.let { "$it siège${if (it > 1) "s" else ""}" })
    }
    val present = k.filterNotNull()
    val year = present.firstNotNullOfOrNull { it.aide_publique?.annee }
    return listOf(
        CardRound(
            "Composition", listOf(Cols("Partis membres", comp(k[0]), comp(k[1])), Cols("Candidats déclarés", cands(k[0]), cands(k[1]))),
            listOf(CAMP_RULE, "Partis rattachés selon la littérature ; candidats ayant déclaré leur candidature ou désignés par leur parti."), CARTE,
            footSources(sourcesOf(file("camps")?.get("regle_src")).take(2) + present.flatMap { c -> c.candidats_declares?.liste.orEmpty().flatMap { sourcesOf(it.src) } }, 6),
            (k[0] != null) to (k[1] != null),
        ),
        fin(
            "Élus",
            listOf(
                gauge("Assemblée nationale (rattachés)", "sièges") { it.sieges?.an_rattaches?.toDouble() },
                gauge("Sénat (rattachés)", "sièges") { it.sieges?.senat_rattaches?.toDouble() },
                gauge("Parlement européen", "sièges") { it.sieges?.pe_elus?.toDouble() },
            ),
            "Parlementaires rattachés aux partis du camp et députés européens. Repères gris : les autres camps.", CARTE, present.flatMap { sourcesOf(it.sieges?.src) },
        ),
        fin(
            "Financement public", listOf(gauge("Aide publique ${year ?: ""}".trim(), "M€") { c -> c.aide_publique?.total?.let { it / 1e6 } }),
            "Somme de l'aide de l'État versée aux partis du camp, au Journal officiel.", ARGENT, present.flatMap { sourcesOf(it.aide_publique?.src) },
        ),
        gauge("Voix des listes du camp, en % des exprimés", "%", ::campVotes).let { votes ->
            val seats = gauge("Sièges obtenus", "sièges") { campSeats(it)?.toDouble() }
            CardRound(
                "Urnes : européennes 2024", listOf(GaugeBlock(votes), GaugeBlock(seats), Cols("Listes", lists(k[0]), lists(k[1]))),
                listOf(CAMP_RULE, "Listes conduites par un parti du camp : leurs pourcentages s'additionnent."), CARTE,
                footSources(present.flatMap { sourcesOf(it.europeennes_2024?.src) }, 6), (votes.a != null) to (votes.b != null),
            )
        },
    )
}

/** Rounds d'un duel prêt ([duelReady]) ; un round sans donnée des deux côtés n'est pas affiché. */
fun duelRounds(data: PolData, a: String, b: String): List<DuelRound> {
    if (!duelReady(data, a, b)) return emptyList()
    val out = mutableListOf<DuelRound>()
    when (data.kindOf(a)) {
        IdKind.Camp -> out += data.campRounds(a, b)
        IdKind.Parti -> {
            out += data.partyRounds(a, b)
            data.votesRound(a, b, viaParty = false)?.let { out += it }
            val ta = turnsOf(data, a); val tb = turnsOf(data, b)
            if (ta.isNotEmpty() || tb.isNotEmpty()) out += TurnRound(ta, tb)
        }
        IdKind.Personne -> {
            out += data.candidateRounds(a, b)
            val pa = data.partyOf(a); val pb = data.partyOf(b)
            data.votesRound(pa, pb, viaParty = true)?.let { out += it }
            val ta = turnsOf(data, pa ?: a); val tb = turnsOf(data, pb ?: b)
            if (ta.isNotEmpty() || tb.isNotEmpty()) out += TurnRound(ta, tb)
        }
    }
    return out.filter { it.avail.first || it.avail.second }
}

// ---------- Police et pictogrammes pixel ----------

/** Police 5×7 des titres de jeu (VS, P1, ROUND, FIN) : « # » plein. */
private val GLYPHS: Map<Char, List<String>> = mapOf(
    'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"), 'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
    'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."), 'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
    'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"), 'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
    'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"), 'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'I' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"), 'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    'N' to listOf("#...#", "##..#", "##..#", "#.#.#", "#..##", "#..##", "#...#"), 'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."), 'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
    'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."), 'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
    'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."), 'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
    'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
    '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."), '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"), '3' to listOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
    '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."), '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."), '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."), '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    '?' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."), '!' to listOf("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."),
    ' ' to listOf("...", "...", "...", "...", "...", "...", "..."),
)

/** Rôle d'une cellule de texte pixel, dans l'ordre de dessin : ombre portée, contour encre, dessus éclairci, plein. */
enum class PxRole { Shadow, Outline, Light, Fill }

/** Image pixel : [w] × [h] cellules ; cellules (x, y, rôle) dans l'ordre de dessin. */
class PxImage(val w: Int, val h: Int, val cells: List<Triple<Int, Int, PxRole>>)

private fun Long.x() = (this shr 32).toInt()
private fun Long.y() = toInt()
private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

/** Cellules pleines d'une grille (« # »). */
fun gridCells(grid: List<String>): Set<Long> = buildSet { grid.forEachIndexed { y, r -> r.forEachIndexed { x, c -> if (c == '#') add(key(x, y)) } } }

/** Cellules d'un texte en police 5×7 ; [bold] double le trait. Caractère inconnu : espace. */
fun pxTextCells(text: String, bold: Boolean): Triple<Set<Long>, Int, Int> {
    val cells = mutableSetOf<Long>()
    var x = 0
    for (ch in text.uppercase()) {
        val g = GLYPHS[ch] ?: GLYPHS.getValue(' ')
        g.forEachIndexed { y, r -> r.forEachIndexed { i, c -> if (c == '#') { cells += key(x + i, y); if (bold) cells += key(x + i + 1, y) } } }
        x += g[0].length + if (bold) 2 else 1
    }
    return Triple(cells, x - 1, 7)
}

/** Habillage : contour encre d'une cellule, ombre portée d'une cellule en bas à droite, dessus éclairci sur les 40 % du haut. */
fun pxImage(raw: Set<Long>, w: Int, h: Int, outline: Boolean = false, shadow: Boolean = false, two: Boolean = false): PxImage {
    val pad = if (outline) 1 else 0
    val cells = raw.map { key(it.x() + pad, it.y() + pad) }.toSet()
    val out = ArrayList<Triple<Int, Int, PxRole>>()
    val ring = if (outline) cells.flatMap { k -> (-1..1).flatMap { i -> (-1..1).map { j -> key(k.x() + i, k.y() + j) } } }.toSet() else cells
    if (shadow) ring.forEach { out += Triple(it.x() + 1, it.y() + 1, PxRole.Shadow) }
    if (outline) ring.forEach { out += Triple(it.x(), it.y(), PxRole.Outline) }
    val top = ceil(h * 0.4).toInt()
    cells.forEach { out += Triple(it.x(), it.y(), if (two && it.y() - pad < top) PxRole.Light else PxRole.Fill) }
    val ext = if (shadow) 1 else 0
    return PxImage(w + 2 * pad + ext, h + 2 * pad + ext, out)
}

fun pxText(text: String, bold: Boolean = false, outline: Boolean = false, shadow: Boolean = false, two: Boolean = false): PxImage =
    pxTextCells(text, bold).let { (c, w, h) -> pxImage(c, w, h, outline, shadow, two) }

/** Logo VS dessiné à la main : traits de deux cases. */
val VS_GRID: List<String> = run {
    val v = listOf("##....##", "##....##", "##....##", "##....##", ".##..##.", ".##..##.", "..####..", "..####..", "...##...", "...##...")
    val s = listOf(".#######", "########", "##......", "##......", "#######.", ".#######", "......##", "......##", "########", "#######.")
    v.indices.map { v[it] + "." + s[it] }
}

fun vsImage(shadow: Boolean = true): PxImage = pxImage(gridCells(VS_GRID), VS_GRID[0].length, VS_GRID.size, outline = true, shadow = shadow, two = true)

/** Petits pictogrammes pixel (grilles 8×8, coupe 10×10) qui suivent la couleur du texte. */
val DUEL_ICONS: Map<String, List<String>> = mapOf(
    "fwd" to listOf("...#....", "...##...", "...###..", "#######.", "#######.", "...###..", "...##...", "...#...."),
    "back" to listOf("....##..", "...##...", "..##....", ".##.....", ".##.....", "..##....", "...##...", "....##.."),
    "chev" to listOf("........", "..##....", "...##...", "....##..", "....##..", "...##...", "..##....", "........"),
    "ext" to listOf("........", "..######", "......##", ".....#.#", "....#..#", "...#...#", "..#.....", ".#......"),
    "info" to listOf("...##...", "...##...", "........", "..###...", "...##...", "...##...", "...##...", "..####.."),
    "swap" to listOf("....#...", "....##..", "#######.", "....##..", "..##....", ".#######", "..##....", "...#...."),
    "eq" to listOf("........", "........", "#######.", "........", "........", "#######.", "........", "........"),
    "bolt" to listOf("....###.", "...###..", "..###...", ".######.", "...###..", "..###...", ".###....", "##......"),
    "half" to listOf("########", "####...#", "####...#", "####...#", "####...#", "####...#", "####...#", "########"),
    "box" to listOf("........", "........", "..####..", "..#..#..", "..#..#..", "..####..", "........", "........"),
    "cup" to listOf("..######..", "##.####.##", "#..####..#", ".#.####.#.", "..######..", "...####...", "....##....", "....##....", "..######..", "..######.."),
)

/** Pictogramme d'un cas de comparaison de vote. */
val VCase.icon: String get() = when (this) { VCase.Same -> "eq"; VCase.Clash -> "bolt"; VCase.Part -> "half"; VCase.None -> "box" }

/** Diagonale en marches du VS plein écran : abscisses (fraction de largeur) des 17 marches, de 62 % en haut à 38 % en bas. */
fun vsStairs(n: Int = 16): List<Float> = (0..n).map { i -> (62f - i.toFloat() / n * 24f) / 100f }

/** Poignée de l'aide : hauteur suivie par paliers de [step], bornée au tiroir. */
fun gripHeight(start: Float, dy: Float, max: Float, step: Float): Float =
    (Math.round((start + dy) / step) * step).coerceIn(0f, max)

/** Lâcher de la poignée : ouvert au-delà de 60 % (depuis ouvert) ou 35 % (depuis fermé). */
fun gripOpens(cur: Float, max: Float, fromOpen: Boolean): Boolean = cur > max * if (fromOpen) 0.6f else 0.35f

/** Fenêtre coulissante : lâcher au-delà de 30 % de la largeur change de panneau. */
fun windowRelease(page: Int, dx: Float, width: Float): Int = when {
    dx < -width * 0.3f -> 1
    dx > width * 0.3f -> 0
    else -> page
}
