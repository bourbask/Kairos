package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.ui.geometry.Offset
import app.kairos.mobile.ui.politique.screens.Projection
import app.kairos.mobile.ui.politique.screens.RegionGeo
import app.kairos.mobile.ui.politique.screens.contains
import app.kairos.mobile.ui.politique.screens.isDom
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.hypot

/* Sur la carte : fond de France en cellules (régions de `carte`), encarts d'outre-mer, épingles, loupe. Calculs purs. */

@Serializable
data class Epingle(val ville: String = "", val lat: Double = Double.NaN, val lon: Double = Double.NaN, val region: String = "", val regionNom: String = "")

@Serializable
data class VilleD(val choix: List<Epingle> = emptyList(), val bonne: Int = -1)

/** Unités de carte par cellule ; grille [CARTE_W] × [CARTE_H] cellules ; hauteur d'un encart, en cellules. */
const val CARTE_U = 15
const val CARTE_W = 67
const val CARTE_H = 65
const val ENCART_H = 16

/** Projection du fond quand `carte` n'en publie pas. */
val PROJ_DEFAUT = Projection(lat0 = 46.5, k = 0.688354575693754, minx = -3.5130869257765016, maxy = 51.088402305103116, s = 99.07302754200374)

/**
 * Encart d'outre-mer : silhouette simplifiée (le fond n'a que la métropole), [w] cellules de large, emprise
 * [ouest], [est], [nord], [sud] pour placer les épingles ; [bas] : île calée en bas à droite.
 */
class Encart(val code: String, val nom: String, val w: Int, val ouest: Double, val est: Double, val nord: Double, val sud: Double, val g: List<String>, val bas: Boolean = false) {
    val gw = g.first().length
    val gh = g.size
    /** Décalage de la silhouette dans l'encart : la tête d'une épingle garde sa place au-dessus de l'île. */
    val ox = if (bas) w - gw else (w - gw) / 2
    val oy = if (bas) ENCART_H - gh else 4 + (ENCART_H - 4 - gh) / 2
}

val ENCARTS = listOf(
    Encart("01", "Guadeloupe", 18, -61.81, -61.17, 16.51, 15.83, listOf(".##......###..", "####....#####.", "####...######.", ".####.######..", ".#####.####...", "..####..##....", "..###......##.", "...#.......##.")),
    Encart("02", "Martinique", 18, -61.23, -60.81, 14.88, 14.39, listOf("..##....", "..####..", ".#####..", "..#####.", "...####.", "..######", ".#######", "..######", "...##.##", "....#...")),
    Encart("03", "Guyane", 18, -54.6, -51.6, 5.8, 2.1, listOf("#####.....", "#######...", "########..", "#########.", "#########.", ".########.", ".#######..", "..######..", "..####....", "...##.....")),
    Encart("04", "La Réunion", 32, 55.21, 55.84, -20.87, -21.39, listOf("....######....", "..##########..", ".############.", "##############", "##############", "##############", ".############.", ".############.", "..##########..", "....######...."), bas = true),
    Encart("06", "Mayotte", 18, 45.0, 45.3, -12.63, -13.0, listOf("...##.", "..###.", "..###.", ".####.", "####..", ".###.#", "..###.", "..##..", "...#..")),
)

/** Largeur cumulée des encarts, en cellules (sans les espaces entre eux). */
val ENCARTS_W = ENCARTS.sumOf { it.w }

/** Teinte (0 à 3) d'une région : deux voisines n'ont jamais la même ; outre-mer : 3. */
private val TEINTES = mapOf("11" to 2, "24" to 0, "27" to 3, "28" to 1, "32" to 0, "44" to 1, "52" to 2, "53" to 0, "75" to 3, "76" to 0, "84" to 1, "93" to 2, "94" to 0)
fun teinte(code: String): Int = TEINTES[code] ?: if (isDom(code)) 3 else Math.floorMod(code.hashCode(), 4)

/** Région de chaque cellule (ligne par ligne), échantillonnée au centre de la cellule ; null : mer. */
fun grilleRegions(regions: List<RegionGeo>): Array<String?> = Array(CARTE_W * CARTE_H) { i ->
    val p = Offset((i % CARTE_W) * CARTE_U + CARTE_U / 2f, (i / CARTE_W) * CARTE_U + CARTE_U / 2f)
    regions.firstOrNull { it.bounds.contains(p) && contains(it.rings, p) }?.code
}

/** Épingle jouable : coordonnées lues, et en métropole ou dans un encart connu. */
fun epingleOk(e: Epingle): Boolean = e.ville.isNotBlank() && e.lat.isFinite() && e.lon.isFinite() &&
    (!isDom(e.region) || ENCARTS.any { it.code == e.region })

/** Pointe d'une épingle en métropole, en cellules, sans marge haute. */
fun pointeMetropole(p: Projection, lat: Double, lon: Double): Offset =
    Offset(((lon * p.k - p.minx) * p.s / CARTE_U).toFloat(), ((p.maxy - lat) * p.s / CARTE_U).toFloat())

/** Marge de mer en haut (cellules) pour que les épingles de métropole (7 cellules de haut) soient entières ; au moins 3. */
fun margeHaute(p: Projection, epingles: List<Epingle>): Int =
    epingles.filter { !isDom(it.region) }.maxOfOrNull { ceil(7 - pointeMetropole(p, it.lat, it.lon).y).toInt() }?.coerceAtLeast(3) ?: 3

/** Gauche de chaque encart, en px : cellule [s], [ecart] px entre deux encarts. */
fun encartsX(s: Int, ecart: Int): List<Int> = ENCARTS.runningFold(0) { x, e -> x + e.w * s + ecart }.dropLast(1)

/**
 * Pointe de l'épingle en px dans la manette : carte de [CARTE_H] + [oy] cellules, puis [ecart] px, puis la rangée
 * d'encarts (même [ecart] entre eux). Cellule de [s] px.
 */
fun pointe(e: Epingle, p: Projection, s: Int, oy: Int, ecart: Int): Offset {
    val i = ENCARTS.indexOfFirst { it.code == e.region }
    if (i < 0) return pointeMetropole(p, e.lat, e.lon).let { Offset(it.x * s, (it.y + oy) * s) }
    val d = ENCARTS[i]
    val x = encartsX(s, ecart)[i] + (d.ox + (e.lon - d.ouest) / (d.est - d.ouest) * d.gw) * s
    val y = (CARTE_H + oy) * s + ecart + (d.oy + (d.nord - e.lat) / (d.nord - d.sud) * d.gh) * s
    return Offset(x.toFloat(), y.toFloat())
}

/** Épingle la plus proche de [x], [y] : distance à sa tête, [tete] px au-dessus de la pointe. */
fun plusProche(pointes: List<Offset>, x: Float, y: Float, tete: Float): Int =
    pointes.indices.minByOrNull { hypot(pointes[it].x - x, pointes[it].y - tete - y) } ?: 0

/** Épingle 5 × 7 : tête ronde à reflet (`b`), ombrée (`d`), pointe (`e`) sur la ville ; `o` transparent. */
val EPINGLE = listOf("oaaao", "abaad", "aaaad", "oaddo", "..d..", "..e..", "..e..")

/** Loupe 17 × 17 : verre (`v`) à reflet (`h`), anneau (`r`, `D` ombré), manche en marches (`m`, `M`) ; centre du verre en 6,5. */
val LOUPE: List<String> = (0 until 17).map { y ->
    (0 until 17).joinToString("") { x ->
        val d = hypot(x + 0.5 - 6.5, y + 0.5 - 6.5)
        when {
            d <= 4.6 -> if (x + y < 8 && d > 2.6 && d < 3.8) "h" else "v"
            d <= 6.4 -> if (x + y > 13) "D" else "r"
            x >= 11 && y >= 11 && kotlin.math.abs(x - y) <= 1 && x <= 15 && y <= 15 -> if (x > y) "M" else "m"
            else -> "."
        }
    }
}
