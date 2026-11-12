package app.kairos.mobile.ui.politique.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.kairos.mobile.ui.politique.model.Row
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.alphabetical
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
    val ville: String = "", val insee: String = "", val lat: Double? = null, val lon: Double? = null, val region: String = "",
    val qui: String = "", val statut: String = "", val detail: String = "", val src: JsonElement? = null,
) {
    /** Identifiant stable : code commune, sinon nom. */
    val key: String get() = insee.ifBlank { ville }
}

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

/** Bulle de région(s) ([regions] non vide), bulle-compteur ou repère seul ([cities] d'un élément). */
data class MapGroup(val regions: List<String>, val cities: List<Commune>, val at: Offset)

/** Rayon dessiné d'une bulle, en dp : grossit avec le nombre de villes pour une région. */
fun bubbleRadiusDp(g: MapGroup): Float = if (g.regions.isNotEmpty()) 8.5f + min(4.5f, g.cities.size * 0.45f) else 8.5f

/**
 * [placed] : communes posées sur une région du fond ; [unmapped] : communes de métropole sans place
 * sur la carte (région inconnue du fond, sans coordonnées, ou fond absent), listées à part.
 */
data class VillesModel(
    val viewBox: Rect, val regions: List<RegionGeo>, val communes: List<Commune>, val placed: List<Placed>,
    val unmapped: List<Commune>, val source: JsonElement?,
) {
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
    val codes = if (proj == null || vb.width <= 0 || vb.height <= 0) emptySet() else regions.map { it.code }.toSet()
    val off = unmapped(communes, codes)
    val placed = communes.filter { !isDom(it.region) && it !in off }.map { Placed(it, project(proj!!, it.lat!!, it.lon!!)) }
    return VillesModel(vb, regions, communes, placed, off, data.file("carte")?.get("source"))
}

/** Communes de métropole impossibles à poser : région absente de [regionCodes] ou coordonnées manquantes. */
fun unmapped(communes: List<Commune>, regionCodes: Set<String>): List<Commune> =
    communes.filter { !isDom(it.region) && (it.region !in regionCodes || it.lat == null || it.lon == null) }

private fun mean(l: List<Placed>) = Offset(l.sumOf { it.at.x.toDouble() }.toFloat() / l.size, l.sumOf { it.at.y.toDouble() }.toFloat() / l.size)

/** Emprise à l'écran, en dp : 7 pour un repère, 11 pour une bulle-compteur, rayon extérieur pour une bulle de région. */
private fun footDp(g: MapGroup): Float = when {
    g.regions.isNotEmpty() -> bubbleRadiusDp(g) + 2.3f
    g.cities.size > 1 -> 11f
    else -> 7f
}

/** Vue pays : une bulle par région, au barycentre de ses villes. */
fun regionBubbles(codes: List<String>, placed: List<Placed>): List<MapGroup> =
    codes.mapNotNull { r -> placed.filter { it.c.region == r }.takeIf { it.isNotEmpty() }?.let { MapGroup(listOf(r), it.map(Placed::c), mean(it)) } }

/** Vue région : repères seuls, puis fusionnés tant qu'ils se chevauchent à l'écran. */
fun clusterMarkers(placed: List<Placed>, unitsPerDp: Float): List<MapGroup> =
    mergeOverlapping(placed.map { MapGroup(emptyList(), listOf(it.c), it.at) }, unitsPerDp)

/**
 * Fusionne les groupes dont les emprises se chevauchent à l'écran, jusqu'à stabilité : aucun point caché.
 * [unitsPerDp] : unités de carte par dp au zoom courant. Le centre fusionné est la moyenne pondérée par les villes.
 */
// ponytail: fusion par paires répétée, O(n³) au pire (une quinzaine de groupes) ; grille si le volume grossit.
fun mergeOverlapping(groups: List<MapGroup>, unitsPerDp: Float): List<MapGroup> {
    val g = groups.toMutableList()
    var hit = true
    while (hit) {
        hit = false
        loop@ for (i in g.indices) for (j in i + 1 until g.size) {
            val a = g[i]; val b = g[j]
            if (hypot(a.at.x - b.at.x, a.at.y - b.at.y) < (footDp(a) + footDp(b)) * unitsPerDp * 0.95f) {
                val na = a.cities.size.toFloat(); val nb = b.cities.size.toFloat()
                g[i] = MapGroup((a.regions + b.regions).distinct(), a.cities + b.cities, (a.at * na + b.at * nb) / (na + nb))
                g.removeAt(j); hit = true
                break@loop
            }
        }
    }
    return g
}

/** Partis présents et leur nombre de villes, du plus représenté au moins représenté. */
fun partyCounts(cities: List<Commune>): List<Pair<String, Int>> =
    cities.groupingBy { it.qui }.eachCount().toList().sortedByDescending { it.second }

/** Fenêtre de zoom sur une région : sa boîte, plus une marge de 12 % du grand côté. */
fun regionWindow(b: Rect): Rect = b.inflate(max(b.width, b.height) * 0.12f)

// ---------- Libellés ----------

val STATUSES: List<Pair<String, String>> = listOf("gagnee" to "Gagnée", "conservee" to "Conservée", "ralliee" to "Maire rallié", "perdue" to "Perdue", "echec" to "Échec")
/** Statut non reconnu : libellé neutre, jamais le code brut. */
const val STATUS_UNKNOWN = "Issue non précisée"
fun statusLabel(st: String): String = STATUSES.firstOrNull { it.first == st }?.second ?: STATUS_UNKNOWN

private val QUI = mapOf("divers-droite" to "Divers droite", "divers-gauche" to "Divers gauche", "divers-centre" to "Divers centre")

/** Nom court de la liste élue (sigle si le nom est long) ; sinon libellé générique ou brut. */
fun quiLabel(data: PolData, qui: String): String {
    val i = data.byId(qui) ?: return QUI[qui] ?: qui
    return if (i.nom.length > 18) i.parti?.takeIf { it.isNotBlank() } ?: i.nom else i.nom
}

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

/** Faits groupés par identité du maire, puis identités des municipales sans aucun fait ; chaque groupe par ordre alphabétique. */
fun factSections(data: PolData): List<FactSection> {
    val facts = cityFactsOf(data)
    val byId = facts.groupBy { data.idOf(it.maire) }
    val done = byId.keys.filterNotNull().toSet()
    val name = { id: String? -> id?.let { data.byId(it)?.nom }.orEmpty() }
    val withFacts = byId.entries.alphabetical { name(it.key) }.sortedBy { it.key == null }.map { (id, l) -> FactSection(id, l.groupBy { it.ville }.toList()) }
    val pending = muniOf(data).mapNotNull { data.idOf(it.t) }.distinct().filter { it !in done }.alphabetical(name).map { FactSection(it, emptyList()) }
    return withFacts + pending
}
