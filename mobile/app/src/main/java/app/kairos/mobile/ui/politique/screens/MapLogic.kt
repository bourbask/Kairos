package app.kairos.mobile.ui.politique.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.kairos.mobile.ui.politique.Row
import app.kairos.mobile.ui.politique.model.PolData
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.text.Collator
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Villes : fond de carte, communes suivies, faits de gestion locale. Tout est facultatif. */

@Serializable
data class Projection(val lat0: Double = 0.0, val k: Double = 1.0, val minx: Double = 0.0, val maxy: Double = 0.0, val s: Double = 1.0)

@Serializable
data class RegionShape(val code: String = "", val nom: String = "", val d: String = "")

@Serializable
data class Commune(
    val ville: String = "", val lat: Double? = null, val lon: Double? = null, val region: String = "",
    val qui: String = "", val statut: String = "", val detail: String = "", val src: JsonElement? = null,
)

/** Coordonnées des données vers le repère du fond de carte. */
fun project(p: Projection, lat: Double, lon: Double): Offset = Offset(((lon * p.k - p.minx) * p.s).toFloat(), ((p.maxy - lat) * p.s).toFloat())

private val TOKEN = Regex("""[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

/** Chemin `M x y L x y … Z` en anneaux de points ; une autre commande arrête la lecture. */
fun parsePath(d: String): List<List<Offset>> {
    val rings = mutableListOf<MutableList<Offset>>()
    var pending: Float? = null
    for (t in TOKEN.findAll(d).map { it.value }) {
        when {
            t == "M" -> { rings += mutableListOf<Offset>(); pending = null }
            t == "L" || t == "Z" -> pending = null
            t.first().isLetter() -> break
            rings.isEmpty() -> break
            else -> {
                val v = t.toFloatOrNull() ?: break
                pending = pending?.let { rings.last() += Offset(it, v); null } ?: v
            }
        }
    }
    return rings.filter { it.isNotEmpty() }
}

fun boundsOf(rings: List<List<Offset>>): Rect {
    val pts = rings.flatten()
    if (pts.isEmpty()) return Rect.Zero
    return Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
}

/** Point dans la surface (règle pair-impair, tous anneaux confondus). */
fun contains(rings: List<List<Offset>>, p: Offset): Boolean {
    var inside = false
    for (r in rings) {
        var j = r.size - 1
        for (i in r.indices) {
            val a = r[i]; val b = r[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
    }
    return inside
}

/** Codes de région à deux chiffres commençant par 0 : outre-mer, présenté en liste. */
fun isDom(code: String): Boolean = Regex("0\\d").matches(code)

fun outreMer(communes: List<Commune>): List<Commune> = communes.filter { isDom(it.region) }

data class RegionGeo(val code: String, val nom: String, val rings: List<List<Offset>>, val bounds: Rect)

data class Placed(val c: Commune, val at: Offset)

/** Bulle de région, bulle-compteur ou repère seul ([cities] d'un élément). */
data class MapGroup(val region: String?, val cities: List<Commune>, val at: Offset)

data class VillesModel(val viewBox: Rect, val regions: List<RegionGeo>, val communes: List<Commune>, val placed: List<Placed>, val source: JsonElement?) {
    val hasMap: Boolean get() = regions.isNotEmpty() && viewBox.width > 0 && viewBox.height > 0
}

fun villesModel(data: PolData): VillesModel {
    val regions = data.decode<RegionShape>("carte", "regions").filter { it.code.isNotBlank() }
        .map { r -> parsePath(r.d).let { RegionGeo(r.code, r.nom, it, boundsOf(it)) } }.filter { it.rings.isNotEmpty() }
    val vb = data.one("carte", "viewBox", String.serializer())?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }
        ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }?.let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) }
        ?: regions.map { it.bounds }.reduceOrNull { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
        ?: Rect.Zero
    val proj = data.one("carte", "projection", Projection.serializer())
    val communes = data.decode<Commune>("villes", "COMMUNES").filter { it.ville.isNotBlank() }
    val placed = if (proj == null) emptyList() else communes.filter { !isDom(it.region) && it.lat != null && it.lon != null }.map { Placed(it, project(proj, it.lat!!, it.lon!!)) }
    return VillesModel(vb, regions, communes, placed, data.file("carte")?.get("source"))
}

private fun mean(l: List<Placed>) = Offset(l.sumOf { it.at.x.toDouble() }.toFloat() / l.size, l.sumOf { it.at.y.toDouble() }.toFloat() / l.size)

/** Vue pays : une bulle par région, au barycentre de ses villes. */
fun regionBubbles(codes: List<String>, placed: List<Placed>): List<MapGroup> =
    codes.mapNotNull { r -> placed.filter { it.c.region == r }.takeIf { it.isNotEmpty() }?.let { MapGroup(r, it.map(Placed::c), mean(it)) } }

/**
 * Vue région : repères fusionnés tant que leurs emprises se chevauchent à l'écran. [unitsPerDp] :
 * unités de carte par dp au zoom courant (emprise 7 dp pour un repère, 11 pour une bulle).
 */
// ponytail: fusion par paires répétée, O(n³) au pire sur une région (une quinzaine de villes) ; grille si le volume grossit.
fun clusterMarkers(placed: List<Placed>, unitsPerDp: Float): List<MapGroup> {
    val groups = placed.map { mutableListOf(it) }.toMutableList()
    val at = groups.map { it[0].at }.toMutableList()
    fun foot(i: Int) = (if (groups[i].size > 1) 11f else 7f) * unitsPerDp
    var hit = true
    while (hit) {
        hit = false
        loop@ for (i in groups.indices) for (j in i + 1 until groups.size) {
            if (hypot(at[i].x - at[j].x, at[i].y - at[j].y) < (foot(i) + foot(j)) * 0.95f) {
                groups[i] += groups[j]; at[i] = mean(groups[i])
                groups.removeAt(j); at.removeAt(j); hit = true
                break@loop
            }
        }
    }
    return groups.mapIndexed { i, g -> MapGroup(null, g.map(Placed::c), at[i]) }
}

/** Partis présents et leur nombre de villes, du plus représenté au moins représenté. */
fun partyCounts(cities: List<Commune>): List<Pair<String, Int>> =
    cities.groupingBy { it.qui }.eachCount().toList().sortedByDescending { it.second }

/** Fenêtre de zoom sur une région : sa boîte, plus une marge de 12 % du grand côté. */
fun regionWindow(b: Rect): Rect = b.inflate(max(b.width, b.height) * 0.12f)

// ---------- Libellés ----------

val STATUSES: List<Pair<String, String>> = listOf("gagnee" to "Gagnée", "conservee" to "Conservée", "ralliee" to "Maire rallié", "perdue" to "Perdue", "echec" to "Échec")
fun statusLabel(st: String): String = STATUSES.firstOrNull { it.first == st }?.second ?: st

private val QUI = mapOf("divers-droite" to "Divers droite", "divers-gauche" to "Divers gauche", "divers-centre" to "Divers centre")

/** Nom court de la liste élue (sigle si le nom est long) ; sinon libellé générique ou brut. */
fun quiLabel(data: PolData, qui: String): String {
    val i = data.byId(qui) ?: return QUI[qui] ?: qui
    return if (i.nom.length > 18) i.parti?.takeIf { it.isNotBlank() } ?: i.nom else i.nom
}

fun partySum(data: PolData, cities: List<Commune>): String =
    cities.groupingBy { quiLabel(data, it.qui) }.eachCount().toList().sortedByDescending { it.second }.joinToString(", ") { "${it.second} ${it.first}" }

val FR: Collator = Collator.getInstance(Locale.FRENCH)

// ---------- Bilans locaux ----------

/** Fait de gestion locale : ville, maire (libellé), fait, nature (`cont`, `causal`, `desc`), source. */
data class CityFact(val ville: String, val maire: String, val text: String, val kind: String, val srcLabel: String, val srcUrl: String)

fun cityFactsOf(data: PolData): List<CityFact> = (data.decode("villes", "VRN", JsonArray.serializer()) + data.decode("villes", "VECO", JsonArray.serializer()))
    .map(::Row).map { CityFact(it.s(0), it.s(1), it.s(2), it.s(3), it.s(4), it.s(5)) }.filter { it.ville.isNotBlank() && it.text.isNotBlank() }

fun factsFor(facts: List<CityFact>, ville: String): List<CityFact> = facts.filter { it.ville == ville }

/** Nature d'un fait : libellé et teinte d'état. */
fun factKind(k: String): Pair<String, String?> = when (k) {
    "cont" -> "Fait vérifiable" to "pour"
    "causal" -> "Décision de justice" to "solide"
    "desc" -> "Avis ou chiffre contesté" to "partiel"
    else -> "Fait" to null
}
val FACT_KINDS = listOf("cont", "causal", "desc")

fun muniOf(data: PolData): List<Dossier> = data.decode<Dossier>("villes", "MUNI").filter { it.t.isNotBlank() }

/** Bilan local d'une identité ([id] null : maire non rattaché à une seule identité) ; [cities] vide : pas encore de bilan. */
data class FactSection(val id: String?, val cities: List<Pair<String, List<CityFact>>>)

/** Faits groupés par identité du maire, puis identités des municipales sans aucun fait. */
fun factSections(data: PolData): List<FactSection> {
    val facts = cityFactsOf(data)
    val byId = facts.groupBy { data.uniqueId(it.maire) }
    val done = byId.keys.filterNotNull().toSet()
    val withFacts = byId.entries.sortedBy { it.key == null }.map { (id, l) -> FactSection(id, l.groupBy { it.ville }.toList()) }
    val pending = muniOf(data).mapNotNull { data.uniqueId(it.t) }.distinct().filter { it !in done }.map { FactSection(it, emptyList()) }
    return withFacts + pending
}
