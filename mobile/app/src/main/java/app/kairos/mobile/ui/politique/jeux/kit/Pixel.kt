package app.kairos.mobile.ui.politique.jeux.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* Pixel art des manettes : cellules en pixels entiers, aplats, ombrage en marches, aucun anti-crénelage. */

/** Mélange sRGB, comme `color-mix(in srgb, a, b t)`. */
fun mix(a: Color, b: Color, t: Float): Color = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

/** Taille d'une cellule en pixels entiers (au moins 1). */
@Composable
@ReadOnlyComposable
fun cellPx(cell: Dp): Int = with(LocalDensity.current) { max(1, cell.toPx().roundToInt()) }

/** Une cellule (ou une rangée de [w] cellules) en [x], [y], cellule de [s] px. */
fun DrawScope.cell(x: Int, y: Int, c: Color, s: Int, w: Int = 1, dy: Int = 0) =
    drawRect(c, Offset((x * s).toFloat(), ((y + dy) * s).toFloat()), Size((w * s).toFloat(), s.toFloat()))

/** Teintes des boutons physiques (éclaircies, validées) et jetons dérivés du thème. */
object Teintes {
    private val dark @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val valid: Color @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF95BC86) else Color(0xFF6C9460)
    val rouge: Color @Composable @ReadOnlyComposable get() = if (dark) Color(0xFFC95A5F) else Color(0xFFCC4A50)
    val bleu: Color @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF5B76C4) else Color(0xFF5470C8)
    /** Touche neutre (abstention). */
    val neutre: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.let { mix(it.onSurfaceVariant, it.surface, 0.26f) }
    /** Bouton désactivé. */
    val eteint: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.let { mix(it.outlineVariant, it.onSurfaceVariant, 0.22f) }
    val ombre: Color @Composable @ReadOnlyComposable get() = if (dark) Color(0x66000000) else Color(0x221F2620)
}

// ---------- Glyphes ----------

object Glyphes {
    val check = listOf("........##", "........##", ".......##.", "##....##..", ".##..##...", "..####....", "...##.....")
    val plus = listOf("...##...", "...##...", "########", "########", "...##...", "...##...")
    val minus = listOf("########", "########")
    val zero = listOf(".####.", "##..##", "##..##", "##..##", "##..##", ".####.")
    val play = listOf("##......", "####....", "######..", "########", "######..", "####....", "##......")
    val redo = listOf(
        "...######...", "..#......#..", ".#........#.", "#..........#", "#..........#", "#.......#####",
        "#........###.", "#.........#..", ".#..........", "..#......#..", "...######...",
    )
    val star = listOf("....#....", "....#....", "...###...", "#########", ".#######.", "..#####..", "..#####..", ".##...##.", ".#.....#.")
    val fleche = listOf(
        "................", ".......#........", ".......##.......", ".......###......", ".......####.....", ".###########....",
        ".############...", ".#############..", ".#############..", ".############...", ".###########....", ".......####.....",
        ".......###......", ".......##.......", ".......#........", "................",
    )
}

private fun List<String>.at(x: Int, y: Int): Char = getOrNull(y)?.getOrNull(x) ?: '.'

/** Grille pixel ([Glyphes], [PIXEL_ICONS]) à la couleur [color], centrée, cellule entière la plus grande qui tient. */
@Composable
fun PixelGrid(grid: List<String>, color: Color, modifier: Modifier = Modifier) {
    val gw = grid.maxOfOrNull { it.length } ?: 0
    val gh = grid.size
    Canvas(modifier.clearAndSetSemantics { }) {
        if (gw == 0) return@Canvas
        val s = max(1, floor(min(size.width / gw, size.height / gh)).toInt())
        val ox = ((size.width - gw * s) / 2).roundToInt()
        val oy = ((size.height - gh * s) / 2).roundToInt()
        for (y in 0 until gh) for (x in 0 until gw) {
            val a = when (grid.at(x, y)) { '#' -> 1f; '+' -> 0.45f; else -> continue }
            drawRect(color.copy(alpha = color.alpha * a), Offset((ox + x * s).toFloat(), (oy + y * s).toFloat()), Size(s.toFloat(), s.toFloat()))
        }
    }
}

/** Pictogramme pixel par nom ([PIXEL_ICONS]) ; inconnu : rien. */
@Composable
fun PixelIcon(name: String, color: Color, modifier: Modifier = Modifier) {
    PIXEL_ICONS[name]?.let { PixelGrid(it, color, modifier) }
}

// ---------- Bouton physique ----------

/** Rôle d'une cellule du bouton ; l'ordre est l'ordre de dessin. */
enum class Px { Pl, Pld, Fl, Fld, T, Tl, Td, Ic, Icl, Iw, Rf }

data class PxCell(val x: Int, val y: Int, val k: Px)

/** Bouton calculé : [w]×[h] cellules ; [base] (platine, flanc) fixe, [top] (dessus) qui s'enfonce. */
class PxButton(val w: Int, val h: Int, val base: List<PxCell>, val top: List<PxCell>)

/** Glyphe incrusté (sombre, liseré clair dessous) ou blanc. */
enum class GlyphMode { Grave, Blanc }

/** Formes validées : dessus en cellules, coins. */
enum class Forme(val w: Int, val h: Int, val r: Int) { Valider(18, 18, 3), Carre(20, 20, 4), Rond(20, 20, 10), Large(24, 14, 4) }

private fun inRR(x: Int, y: Int, w: Int, h: Int, r: Int): Boolean {
    val px = x + 0.5f
    val py = y + 0.5f
    val cx = px.coerceIn(r.toFloat(), (w - r).toFloat())
    val cy = py.coerceIn(r.toFloat(), (h - r).toFloat())
    return (px - cx) * (px - cx) + (py - cy) * (py - cy) <= r * r + 0.3f
}

/** Dessus [w]×[h] à coins [r], flanc de 3 rangées, platine de 2 cellules ; glyphe centré. */
fun pxButton(w: Int, h: Int, r: Int, glyph: List<String>, mode: GlyphMode): PxButton {
    val depth = 3
    val bw = w + 4
    val bh = h + 4 + depth
    val base = ArrayList<PxCell>()
    for (y in 0 until bh) for (x in 0 until bw) if (inRR(x, y, bw, bh, r + 2)) base += PxCell(x, y, if (y >= bh - 1) Px.Pld else Px.Pl)
    for (y in 0 until h) for (x in 0 until w) if (inRR(x, y, w, h, r)) {
        base += PxCell(x + 2, y + 2 + depth, if (x >= w - 2 && !inRR(x + 2, y, w, h, r)) Px.Fld else Px.Fl)
    }
    val ins = { x: Int, y: Int -> x >= 0 && y >= 0 && x < w && y < h && inRR(x, y, w, h, r) }
    val gw = glyph.firstOrNull()?.length ?: 0
    val gh = glyph.size
    val gx = (w - gw) / 2
    val gy = (h - gh) / 2
    val isG = { x: Int, y: Int -> glyph.at(x - gx, y - gy) == '#' && x - gx in 0 until gw }
    val top = ArrayList<PxCell>()
    for (y in 0 until h) for (x in 0 until w) {
        if (!ins(x, y)) continue
        var k = when {
            !ins(x, y + 1) || !ins(x + 1, y) -> Px.Td
            !ins(x, y - 1) || !ins(x - 1, y) -> Px.Tl
            else -> Px.T
        }
        if (isG(x, y)) k = if (mode == GlyphMode.Blanc) Px.Iw else Px.Ic
        else if (isG(x, y - 1)) k = if (mode == GlyphMode.Blanc) Px.Ic else Px.Icl
        top += PxCell(x + 2, y + 2, k)
    }
    val rx = max(2, (r * 0.7f).roundToInt())
    listOf(rx to 2, rx + 1 to 2, rx to 3).forEach { (x, y) -> if (ins(x, y) && !isG(x, y)) top += PxCell(x + 2, y + 2, Px.Rf) }
    return PxButton(bw, bh, base.sortedBy { it.k }, top.sortedBy { it.k })
}

/** Couleurs des rôles pour la teinte [kc]. */
@Composable
@ReadOnlyComposable
private fun pxColors(kc: Color): Map<Px, Color> {
    val s = MaterialTheme.colorScheme
    val k = Color.Black
    val w = Color.White
    return mapOf(
        Px.Pl to mix(s.surface, s.onSurface, 0.11f), Px.Pld to mix(s.surface, s.onSurface, 0.24f),
        Px.Fl to mix(kc, k, 0.42f), Px.Fld to mix(kc, k, 0.58f),
        Px.T to kc, Px.Tl to mix(kc, w, 0.26f), Px.Td to mix(kc, k, 0.2f),
        Px.Ic to mix(kc, k, 0.5f), Px.Icl to mix(kc, w, 0.38f), Px.Iw to w, Px.Rf to w.copy(alpha = 0.85f),
    )
}

/**
 * Bouton physique pixel : platine, flanc, dessus ombré en marches qui descend de deux cellules à l'appui
 * (retour automatique) ; [maintenu] le garde enfoncé (choix écarté). [enabled] faux : grisé et inerte ;
 * [verrouille] : inerte mais garde sa teinte (touche d'une manche résolue).
 * Cible tactile d'au moins 48 dp ; [label] lu par le lecteur d'écran.
 */
@Composable
fun BoutonPixel(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyphe: List<String> = Glyphes.check,
    couleur: Color = Teintes.valid,
    forme: Forme = Forme.Valider,
    mode: GlyphMode = GlyphMode.Grave,
    enabled: Boolean = true,
    maintenu: Boolean = false,
    verrouille: Boolean = false,
    cellule: Dp = 3.dp,
) {
    val actif = enabled && !verrouille
    val b = remember(forme, glyphe, mode) { pxButton(forme.w, forme.h, forme.r, glyphe, mode) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s = cellPx(cellule)
    val colors = pxColors(if (enabled) couleur else Teintes.eteint)
    val down = if ((pressed && actif) || maintenu) 2 else 0
    val sizeDp = with(LocalDensity.current) { (b.w * s).toDp() to (b.h * s).toDp() }
    Box(
        modifier.minimumInteractiveComponentSize()
            .clickable(src, indication = null, enabled = actif, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; if (!actif) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(sizeDp.first, sizeDp.second)) {
            b.base.forEach { cell(it.x, it.y, colors.getValue(it.k), s) }
            b.top.forEach { cell(it.x, it.y, colors.getValue(it.k), s, dy = down) }
        }
    }
}

/** Valider : bouton carré vert à coche incrustée. */
@Composable
fun BoutonValider(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String = "Valider") =
    BoutonPixel(label, onClick, modifier, enabled = enabled)

// ---------- Voyant ----------

enum class LedEtat { Eteinte, Rouge, Verte }

private enum class Lc { Ha1, Ha2, Ha3, Bz, Bzl, Dm, Dd, Hl }

/** Grille 22×22 du voyant : dôme, bague, halo en trois anneaux. */
private val LED_CELLS: List<Triple<Int, Int, Lc>> = buildList {
    for (y in 0 until 22) for (x in 0 until 22) {
        val dx = x + 0.5f - 11
        val dy = y + 0.5f - 11
        val d = hypot(dx, dy)
        val k = when {
            d <= 4.6f -> if ((dx - 1) * (dx - 1) + (dy - 1) * (dy - 1) > 9.5f && dx + dy > 1) Lc.Dd else Lc.Dm
            d <= 6.1f -> if (dx + dy < -1) Lc.Bzl else Lc.Bz
            d <= 7.6f -> Lc.Ha1
            d <= 9.1f -> Lc.Ha2
            d <= 10.6f -> Lc.Ha3
            else -> null
        }
        if (k != null) add(Triple(x, y, k))
    }
    listOf(8 to 8, 9 to 8, 8 to 9).forEach { (x, y) -> add(Triple(x, y, Lc.Hl)) }
}

/** Voyant pixel : éteint, rouge (erreur) ou vert (bonne réponse), avec halo. Décoratif : l'état se dit ailleurs. */
@Composable
fun Led(etat: LedEtat, modifier: Modifier = Modifier, cellule: Dp = 2.2.dp) {
    val s = cellPx(cellule)
    val sc = MaterialTheme.colorScheme
    val lc = when (etat) { LedEtat.Rouge -> Color(0xFFFF3B30); LedEtat.Verte -> Color(0xFF3DDC5A); LedEtat.Eteinte -> null }
    val col = mapOf(
        Lc.Bz to mix(sc.surface, sc.onSurface, 0.34f), Lc.Bzl to mix(sc.surface, sc.onSurface, 0.14f),
        Lc.Dm to (lc ?: mix(sc.surface, sc.onSurface, 0.62f)), Lc.Dd to (lc?.let { mix(it, Color.Black, 0.32f) } ?: mix(sc.surface, sc.onSurface, 0.82f)),
        Lc.Hl to Color.White.copy(alpha = if (lc != null) 0.9f else 0.55f),
        Lc.Ha1 to (lc?.copy(alpha = 0.42f) ?: Color.Transparent), Lc.Ha2 to (lc?.copy(alpha = 0.24f) ?: Color.Transparent),
        Lc.Ha3 to (lc?.copy(alpha = 0.1f) ?: Color.Transparent),
    )
    val side = with(LocalDensity.current) { (22 * s).toDp() }
    Canvas(modifier.size(side).clearAndSetSemantics { }) {
        LED_CELLS.forEach { (x, y, k) -> cell(x, y, col.getValue(k), s) }
    }
}
