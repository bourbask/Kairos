package app.kairos.mobile.ui.politique.jeux.jeu

import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/* Logique pure des jeux du thème « Mesure » : formes de `d` et de `params`, contrôles, géométrie pixel. */

// ---------- Paramètres communs ----------

/** Cran ou niveau nommé (`params.crans` du gravitomètre, `params.niveaux` d'Indépendant ?). */
@Serializable
data class CranNomme(val n: Int = 0, val nom: String = "", val definition: String = "")

@Serializable
data class CransParams(val crans: List<CranNomme> = emptyList(), val niveaux: List<CranNomme> = emptyList())

@Serializable
data class MoletteParams(val unite: String = "", val crans: List<Double> = emptyList())

internal fun <T> JsonObject.params(ser: kotlinx.serialization.KSerializer<T>, defaut: T): T =
    runCatching { PolJson.decodeFromJsonElement(ser, this) }.getOrDefault(defaut)

/** Nom du cran [n] ; inconnu : vide. */
fun List<CranNomme>.nom(n: Int): String = firstOrNull { it.n == n }?.nom.orEmpty()

// ---------- Gravitomètre / Indépendant ? ----------

/** `d` des jeux à 4 crans : cran juste, 1 à 4 ; lignes facultatives sous le nom (Indépendant ?). */
@Serializable
data class CranD(val bonne: Int = 0, val argent: String = "", val lien: String = "")

fun cranValide(d: CranD) = d.bonne in 1..4

/** Premier cran libre (non écarté) en partant de 1 ; tous écartés : 1. */
fun premierLibre(out: Collection<String>): Int = (1..4).firstOrNull { it.toString() !in out } ?: 1

// ---------- La juste somme ----------

/** `d` de la juste somme : montant retenu et fourchette acceptée, bornes incluses. */
@Serializable
data class SommeD(
    val sens: String = "",
    val valeur: Double = 0.0,
    val valeurTexte: String = "",
    val min: Double = Double.NaN,
    val max: Double = Double.NaN,
)

fun sommeValide(d: SommeD) = !d.min.isNaN() && !d.max.isNaN() && d.min <= d.max

fun sommeJuste(v: Double, d: SommeD) = v >= d.min && v <= d.max

/** Après une erreur sur [v] : true s'il fallait monter, false descendre. */
fun sommeMonter(v: Double, d: SommeD) = v < d.min

/** Nombre au format français, sans décimale inutile : 60.0 → « 60 », 11.3 → « 11,3 ». */
fun fr(x: Double): String = if (x == Math.rint(x) && abs(x) < 1e15) x.toLong().toString() else x.toString().replace('.', ',')

fun milliards(v: Double) = "${fr(v)} milliard${if (v > 1) "s" else ""}"

fun sommeEtiquette(v: Double, d: SommeD) = "Vous : ${fr(v)} · juste de ${fr(d.min)} à ${fr(d.max)}"

/** Chiffres des rouleaux : trois, complétés de zéros à gauche (valeur entière). */
fun rouleaux(v: Double): List<Int> = v.roundToInt().coerceIn(0, 999).toString().padStart(3, '0').map { it - '0' }

/** Molette vue de face : logement 20×52, cylindre 16×48 ; stries qui défilent selon l'angle [phi] (degrés). */
const val MOL_W = 20
const val MOL_H = 52
const val MOL_DA = 18f

/** Rôles des cellules de la molette (index de couleur), ou -1 (vide). */
enum class Mol { Sd, Sl, B0, B1, B2, B3, Rg, Rl }

fun molette(phi: Float): Array<Mol?> {
    val g = arrayOfNulls<Mol>(MOL_W * MOL_H)
    fun put(x: Int, y: Int, k: Mol) { g[y * MOL_W + x] = k }
    val w = 16
    val h = 48
    val r = h / 2f
    for (x in 0 until MOL_W) { put(x, 0, Mol.Sd); put(x, 1, Mol.Sd) }
    for (y in 2 until MOL_H - 2) { put(0, y, Mol.Sd); put(1, y, Mol.Sd) }
    for (x in 0 until MOL_W) { put(x, MOL_H - 1, Mol.Sl); put(x, MOL_H - 2, Mol.Sl) }
    for (y in 2 until MOL_H - 2) { put(MOL_W - 1, y, Mol.Sl); put(MOL_W - 2, y, Mol.Sl) }
    fun ang(s: Float) = (asin(s.coerceIn(-1f, 1f)) * 180f / PI.toFloat())
    for (y in 0 until h) {
        val s0 = (y - r) / r
        val s1 = (y + 1 - r) / r
        val sm = (s0 + s1) / 2
        val a0 = ang(s0)
        val a1 = ang(s1)
        val k = ceil((a0 + phi) / MOL_DA)
        val rg = k * MOL_DA - phi < a1 && abs(sm) < 0.97f
        val k2 = ceil((ang((y - 1 - r) / r) + phi) / MOL_DA)
        val under = !rg && y > 0 && k2 * MOL_DA - phi < a0 && abs(sm) < 0.9f
        val band = when {
            abs(sm) > 0.82f -> Mol.B3
            abs(sm) > 0.5f -> Mol.B2
            sm > -0.4f && sm < -0.05f -> Mol.B0
            else -> Mol.B1
        }
        for (x in 0 until w) {
            val edge = x == 0 || x == w - 1
            put(x + 2, y + 2, if (rg && !edge) Mol.Rg else if (under && !edge) Mol.Rl else if (edge) Mol.B3 else band)
        }
    }
    return g
}

/** Voyant fléché 9×5 : 'o' contour, 'i' intérieur, '.' vide. */
fun fleche(haut: Boolean): List<String> = (0 until 5).map { j ->
    (0 until 9).joinToString("") { i ->
        val r = if (haut) j else 4 - j
        when {
            abs(i - 4) > r -> "."
            abs(i - 4) == r || j == (if (haut) 4 else 0) -> "o"
            else -> "i"
        }
    }
}

// ---------- Romaine ----------

/** Pivot (cellules), distance au pivot de chaque cran (0 : repos), angle de repos, taille de la scène. */
const val ROM_PX = 22
const val ROM_PY = 9
val ROM_D = intArrayOf(7, 22, 40, 58, 76)
const val ROM_REPOS = -5f
const val ROM_W = 108
const val ROM_H = 34

/** Cran libre le plus proche de [cx] (cellules depuis le pivot) ; aucun libre : null. */
fun romaineCran(cx: Float, out: Collection<String>): Int? =
    (1..4).filter { it.toString() !in out }.minByOrNull { abs(ROM_D[it] - cx) }

private val SAC = listOf(
    ".....ddd.....", "....dkkkd....", ".....ddd.....", "...dssssxd...", "..dslsssssxd.", ".dslss€€€sxd.", ".dsls€ssssxd.",
    ".ds€€€€sssxd.", ".dss€ssssxxd.", ".dsss€€€sxxd.", "..dssssssxd..", "..ddddddddd..",
)
private val POIDS = listOf("...ddd...", "...d.d...", ".ddddddd.", ".dllwwxd.", "dlwwwwwxd", "dwwwwwwxd", "dwwwwwwxd", "dwwwwwxxd", "ddddddddd")

/** Rôles des cellules de la romaine, dans l'ordre de dessin. */
enum class Rom { Hk, Bt, Nk, Bo, Bm, Bd, Pv, Ch, Sd, Sk, Ss, Sl, Sx, Se, Wd, Wl, Ww, Wx }

/**
 * Romaine à l'angle [deg] (négatif : bras long relevé), poids au cran [n] (0 : repos), crans [out] marqués :
 * cellules (x, y) par rôle, à dessiner dans l'ordre de [Rom].
 */
fun romaine(deg: Float, n: Int, out: Collection<String>): Map<Rom, List<Pair<Int, Int>>> {
    val t = deg * PI.toFloat() / 180f
    val cs = cos(t)
    val sn = sin(t)
    val c = sortedMapOf<Rom, MutableList<Pair<Int, Int>>>()
    fun put(k: Rom, x: Int, y: Int) { c.getOrPut(k) { ArrayList() } += x to y }
    fun at(d: Float, k: Int) = (ROM_PX + d * cs - k * sn).roundToInt() to (ROM_PY + d * sn + k * cs).roundToInt()
    // Anneau et sangle du crochet, fixes au-dessus du pivot.
    listOf(-1 to 0, 0 to 0, 1 to 0, -1 to 1, 1 to 1, -1 to 2, 0 to 2, 1 to 2).forEach { (i, j) -> put(Rom.Hk, ROM_PX + i, j) }
    for (y in 3 until ROM_PY - 1) put(Rom.Hk, ROM_PX, y)
    put(Rom.Hk, ROM_PX - 1, ROM_PY - 2); put(Rom.Hk, ROM_PX + 1, ROM_PY - 2); put(Rom.Hk, ROM_PX, ROM_PY - 2)
    // Fléau : arête claire (crans en creux), corps, flanc sombre ; la priorité départage les cellules communes.
    val grid = LinkedHashMap<Pair<Int, Int>, Pair<Rom, Int>>()
    fun set(p: Pair<Int, Int>, k: Rom, pr: Int) { val o = grid[p]; if (o == null || o.second <= pr) grid[p] = k to pr }
    var d = -17f
    while (d <= 84f) {
        val notch = (1..4).firstOrNull { abs(d - ROM_D[it]) < 0.9f }
        set(at(d, -1), if (notch != null) (if (notch.toString() in out) Rom.Bo else Rom.Nk) else Rom.Bt, if (notch != null) 3 else 1)
        set(at(d, 0), Rom.Bm, 2)
        set(at(d, 1), Rom.Bm, 2)
        set(at(d, 2), Rom.Bd, 2)
        d += 0.25f
    }
    grid.forEach { (p, v) -> put(v.first, p.first, p.second) }
    put(Rom.Pv, ROM_PX, ROM_PY)
    // Sac au bras court.
    val (hx, hy) = at(-14f, 2)
    for (y in hy + 1 until hy + 3) put(Rom.Ch, hx, y)
    SAC.forEachIndexed { j, row ->
        row.forEachIndexed { i, v ->
            val k = when (v) { 'd' -> Rom.Sd; 'k' -> Rom.Sk; 's' -> Rom.Ss; 'l' -> Rom.Sl; 'x' -> Rom.Sx; '€' -> Rom.Se; else -> null }
            if (k != null) put(k, hx - 6 + i, hy + 3 + j)
        }
    }
    // Poids pendu à son cran.
    val (wx, wy) = at(ROM_D[n.coerceIn(0, 4)].toFloat(), 2)
    put(Rom.Ch, wx, wy + 1)
    POIDS.forEachIndexed { j, row ->
        row.forEachIndexed { i, v ->
            val k = when (v) { 'd' -> Rom.Wd; 'l' -> Rom.Wl; 'w' -> Rom.Ww; 'x' -> Rom.Wx; else -> null }
            if (k != null) put(k, wx - 4 + i, wy + 2 + j)
        }
    }
    return c
}
