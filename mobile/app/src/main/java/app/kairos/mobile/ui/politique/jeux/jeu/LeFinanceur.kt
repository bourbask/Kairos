package app.kairos.mobile.ui.politique.jeux.jeu

import app.kairos.mobile.ui.politique.jeux.kit.CouleursJeux
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.GlisserDeposer
import app.kairos.mobile.ui.politique.jeux.kit.Led
import app.kairos.mobile.ui.politique.jeux.kit.LedEtat
import app.kairos.mobile.ui.politique.jeux.kit.PixelShape
import app.kairos.mobile.ui.politique.jeux.kit.cell
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.jeux.kit.cible
import app.kairos.mobile.ui.politique.jeux.kit.glissable
import app.kairos.mobile.ui.politique.jeux.kit.rememberGlisserDeposer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.floor
import kotlin.math.hypot

@Serializable
data class Piece(val montant: Double = 0.0, val montantTexte: String = "", val tirelire: String = "")

/** `d` de `le-financeur` : pièces dans l'ordre du plateau, tirelires dans l'ordre d'affichage. */
@Serializable
data class FinanceurD(val pieces: List<Piece> = emptyList(), val tirelires: List<String> = emptyList()) {
    /** La pièce [piece] va dans la tirelire [tirelire]. */
    fun juste(piece: Int, tirelire: Int): Boolean = pieces[piece].tirelire == tirelires[tirelire]

    /** Pièce destinée à la tirelire [tirelire]. */
    fun pieceDe(tirelire: Int): Int = pieces.indexOfFirst { it.tirelire == tirelires[tirelire] }
}

/** Pièce 22×24 : disque gravé (anneau, reflet), tranche crantée de deux rangées. Codes : h r d i f e E x. */
internal val PIECE: List<String> = run {
    val g = Array(24) { CharArray(22) { '.' } }
    for (y in 0 until 24) for (x in 0 until 22) {
        val dx = x + 0.5f - 11
        val df = y + 0.5f - 11
        val de = y + 0.5f - 13
        val r2 = 10.7f * 10.7f
        if (dx * dx + df * df <= r2) {
            val r = hypot(dx, df)
            g[y][x] = if (r > 8.9f) (if (dx + df < -4) 'h' else if (dx + df > 4) 'd' else 'r') else if (r > 7.9f) 'i' else 'f'
        } else if (dx * dx + de * de <= r2) g[y][x] = if (x % 2 == 1) 'e' else 'E'
    }
    listOf(6 to 4, 7 to 4, 5 to 5).forEach { (x, y) -> g[y][x] = 'x' }
    g.map { String(it) }
}

/** Tirelire 34×25 : couvercle à fente, caisse ombrée, plaque en creux, socle. */
internal val TIRELIRE: List<String> = run {
    val g = Array(25) { CharArray(34) { '.' } }
    fun box(x0: Int, y0: Int, x1: Int, y1: Int, c: Char) { for (y in y0..y1) for (x in x0..x1) g[y][x] = c }
    box(1, 3, 32, 3, 'T'); box(0, 4, 33, 6, 'L'); box(0, 6, 33, 6, 'M')
    box(10, 4, 23, 5, 'k'); box(10, 4, 23, 4, 'K')
    box(1, 7, 32, 21, 'B'); box(1, 7, 1, 21, 'b'); box(32, 7, 32, 21, 'c'); box(1, 21, 32, 21, 'c')
    box(4, 10, 29, 19, 'p'); box(4, 10, 29, 10, 'q'); box(4, 10, 4, 19, 'q'); box(5, 19, 29, 19, 'P'); box(29, 11, 29, 19, 'P')
    box(2, 22, 31, 23, 'D'); box(4, 24, 29, 24, 'D')
    g.map { String(it) }
}

private val CELLULE = 3.dp

/** Cellule effective : [CELLULE], réduite quand la manette est trop étroite pour les tirelires (écran de 360 dp). */
private val LocalCellule = compositionLocalOf { CELLULE }

/** Animation en trois crans (pixel). */
private val Crans = Easing { floor(it * 3f) / 3f }

@Composable
private fun Grille(g: List<String>, couleurs: Map<Char, Color>, modifier: Modifier = Modifier) {
    val s = cellPx(LocalCellule.current)
    val d = LocalDensity.current
    Canvas(modifier.size(with(d) { (g[0].length * s).toDp() }, with(d) { (g.size * s).toDp() }).clearAndSetSemantics { }) {
        g.forEachIndexed { y, l -> l.forEachIndexed { x, k -> couleurs[k]?.let { cell(x, y, it, s) } } }
    }
}

/** Métal des pièces : l'ambre de l'app éclairci, comme les boutons. */
@Composable
private fun metal(): Pair<Color, Map<Char, Color>> {
    val co = mix(CouleursJeux.current.ambre, Color.White, 0.22f)
    val or = { n: Float -> mix(co, Color.Black, n) }
    return or(0.66f) to mapOf(
        'f' to co, 'r' to or(0.1f), 'h' to mix(co, Color.White, 0.4f), 'd' to or(0.3f), 'i' to or(0.2f),
        'e' to or(0.44f), 'E' to or(0.58f), 'x' to Color.White.copy(alpha = 0.85f),
    )
}

/** Cellules d'un dessin en dp (la cellule est arrondie au pixel). */
@Composable
private fun cellsDp(n: Int): Dp = with(LocalDensity.current) { (n * cellPx(LocalCellule.current)).toDp() }

/**
 * La tirelire : pièces en relief (montants) dans un plateau en creux, à glisser dans la fente de la bonne tirelire
 * (ou appui sur la pièce puis sur la tirelire). Bonne fente : la pièce s'y enfonce, le montant s'inscrit sur la plaque.
 */
class LeFinanceur : PolJeu<FinanceurD>("le-financeur", FinanceurD.serializer()) {

    // Correspondance une pièce pour une tirelire, sinon une bonne réponse serait refusée.
    override fun valide(d: FinanceurD, params: JsonObject) = d.pieces.size in 2..3 &&
        d.tirelires.size == d.pieces.size &&
        d.tirelires.none { it.isBlank() } && d.tirelires.distinct().size == d.tirelires.size &&
        d.pieces.none { it.montantTexte.isBlank() } &&
        d.pieces.map { it.tirelire }.toSet() == d.tirelires.toSet()

    @Composable
    override fun Fenetre(m: Manche, d: FinanceurD, st: MancheEtat, c: Coque) = FenetreRelie(m, picto = slug)

    @Composable
    override fun Manette(m: Manche, d: FinanceurD, st: MancheEtat, c: Coque) {
        val unite = (c.params["unite"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val somme = { i: Int -> "${d.pieces[i].montantTexte} $unite".trim() }
        // Gardé dans l'état de la manche : survit au défilement de l'écran.
        val e = remember(st) { st.jeu as? Tirelires ?: Tirelires().also { st.jeu = it } }
        val faites = e.faites
        var just by e.just
        val gd = rememberGlisserDeposer(c) { tuile, cible ->
            val i = tuile.removePrefix("p").toInt()
            val j = cible.removePrefix("t").toInt()
            val nom = d.tirelires[j]
            if (!d.juste(i, j)) {
                c.faux("$i>$nom", "Pas $nom.")
                return@rememberGlisserDeposer false
            }
            faites += i
            just = i
            if (faites.size == d.pieces.size) c.juste() else c.annoncer("${somme(i)} : $nom.")
            true
        }
        val fait = { i: Int -> st.solved || i in faites }
        // Voyant rouge le temps de l'erreur seulement.
        var rouge by remember(st) { mutableStateOf(false) }
        val react0 = remember(st) { st.react }
        LaunchedEffect(st.react) {
            if (st.react <= react0) return@LaunchedEffect
            rouge = true
            delay(900)
            rouge = false
        }
        val (encre, metaux) = metal()
        val sc = MaterialTheme.colorScheme
        val creux = mix(sc.surface, sc.onSurface, 0.08f)
        val creuxH = mix(sc.surface, sc.onSurface, 0.24f)
        val creuxB = mix(sc.surface, Color.White, 0.55f)
        // Tirelires de 34 cellules côte à côte, 8 dp d'écart au moins : la cellule rétrécit pour tenir en largeur.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dens = LocalDensity.current
        val n = d.tirelires.size.coerceAtLeast(1)
        val cellMax = with(dens) { ((maxWidth - 8.dp * (n - 1)).toPx() / (34 * n)).toInt().coerceAtLeast(1).toDp() }
        CompositionLocalProvider(LocalCellule provides minOf(CELLULE, cellMax)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Plateau en creux, au-dessus des tirelires pendant le glisser.
            Row(
                Modifier.fillMaxWidth().zIndex(1f)
                    .drawBehind {
                        val k = 3.dp.toPx().toInt().coerceAtLeast(1).toFloat()
                        val path = (PixelShape(steps = 1).createOutline(size, layoutDirection, this) as Outline.Generic).path
                        clipPath(path) {
                            drawRect(creux)
                            drawRect(creuxH, size = Size(size.width, k)); drawRect(creuxH, size = Size(k, size.height))
                            drawRect(creuxB, Offset(0f, size.height - k), Size(size.width, k)); drawRect(creuxB, Offset(size.width - k, 0f), Size(k, size.height))
                        }
                    }
                    .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceAround) {
                    d.pieces.indices.forEach { i ->
                        if (fait(i)) Spacer(Modifier.size(cellsDp(22), cellsDp(24)))
                        else PieceMobile("p$i", gd, somme(i), d.pieces[i].montantTexte, unite, metaux, encre, !st.solved)
                    }
                }
                Led(if (st.solved) LedEtat.Verte else if (rouge) LedEtat.Rouge else LedEtat.Eteinte)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                d.tirelires.forEachIndexed { j, nom ->
                    val i = d.pieceDe(j)
                    Tirelire("t$j", gd, nom, if (fait(i)) somme(i) else null, seat = just == i && !c.reduit, metaux, enabled = !st.solved)
                }
            }
        }
        }
        }
    }
}

/** Pièce à attraper : se soulève au glisser, revient si refusée ; appui = saisie. */
@Composable
private fun PieceMobile(
    id: String, gd: GlisserDeposer, label: String, texte: String, unite: String,
    metaux: Map<Char, Color>, encre: Color, enabled: Boolean,
) {
    val ombre = app.kairos.mobile.ui.politique.jeux.kit.Teintes.ombre
    val haut = gd.leve(id)
    Box(
        Modifier.size(cellsDp(22), cellsDp(24))
            .glissable(id, gd, label, enabled),
    ) {
        // Ombre portée dure, plus longue quand la pièce est levée.
        Grille(PIECE, metaux.mapValues { ombre }, Modifier.offset(y = if (haut) 9.dp else 3.dp))
        Grille(PIECE, metaux)
        Column(Modifier.size(cellsDp(22)).clearAndSetSemantics { }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(texte, color = encre, fontSize = 14.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, maxLines = 1)
            if (unite.isNotEmpty()) Text(unite, color = encre, fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em)
        }
    }
}

/** Tirelire cible : fente éclairée au survol, tremble au refus ; pleine, la pièce dépasse de la fente et le montant s'inscrit. */
@Composable
private fun Tirelire(
    id: String, gd: GlisserDeposer, nom: String, montant: String?, seat: Boolean,
    metaux: Map<Char, Color>, enabled: Boolean,
) {
    val sc = MaterialTheme.colorScheme
    val ink = { n: Float -> mix(sc.surface, sc.onSurface, n) }
    val hot = gd.survol == id
    val couleurs = mapOf(
        'T' to ink(0.05f), 'L' to ink(0.13f), 'M' to ink(0.22f),
        'k' to if (hot) sc.primary else ink(0.52f), 'K' to if (hot) sc.primary else ink(0.68f),
        'B' to ink(0.1f), 'b' to ink(0.04f), 'c' to ink(0.2f), 'D' to ink(0.28f),
        'p' to if (hot) mix(sc.surface, sc.primary, 0.22f) else ink(0.03f), 'q' to ink(0.24f), 'P' to sc.surface,
    )
    val plein = montant != null
    val enfonce = remember { Animatable(0f) }
    LaunchedEffect(plein, seat) {
        if (plein && seat) { enfonce.snapTo(-14f); enfonce.animateTo(0f, tween(200, easing = Crans)) }
    }
    Box(
        Modifier.size(cellsDp(34), cellsDp(25))
            .cible(id, gd, "Tirelire $nom" + (montant?.let { " : $it" } ?: ""), enabled = enabled && !plein),
    ) {
        if (plein) Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-15).dp).size(cellsDp(22), 30.dp).clipToBounds()
                .graphicsLayer { translationY = enfonce.value.dp.toPx() },
        ) { Grille(PIECE, metaux) }
        Grille(TIRELIRE, couleurs)
        // Plaque en creux : nom, puis montant reçu.
        Column(
            Modifier.offset(cellsDp(5), cellsDp(11)).size(cellsDp(24), cellsDp(8)).clearAndSetSemantics { },
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(nom, Modifier.weight(1f, fill = false), color = sc.onSurface, fontWeight = FontWeight.Bold, lineHeight = 1.05.em,
                letterSpacing = (-0.01).em, maxLines = 2, textAlign = TextAlign.Center,
                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 12.5.sp, stepSize = 0.5.sp))
            if (montant != null) Text(montant, color = sc.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 10.sp, stepSize = 0.5.sp))
        }
    }
}

/** Pièces logées dans la manche, et la dernière arrivée. */
private class Tirelires {
    val faites = mutableStateListOf<Int>()
    val just = mutableStateOf<Int?>(null)
}
