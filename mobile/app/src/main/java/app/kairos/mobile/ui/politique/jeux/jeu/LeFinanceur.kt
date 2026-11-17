package app.kairos.mobile.ui.politique.jeux.jeu

import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import app.kairos.mobile.ui.politique.jeux.kit.rememberGlisserDeposer
import app.kairos.mobile.ui.theme.Motion
import kotlinx.coroutines.launch
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

/** Animation en trois crans (pixel). */
private val Crans = Easing { floor(it * 3f) / 3f }

@Composable
private fun Grille(g: List<String>, couleurs: Map<Char, Color>, modifier: Modifier = Modifier) {
    val s = cellPx(CELLULE)
    val d = LocalDensity.current
    Canvas(modifier.size(with(d) { (g[0].length * s).toDp() }, with(d) { (g.size * s).toDp() }).clearAndSetSemantics { }) {
        g.forEachIndexed { y, l -> l.forEachIndexed { x, k -> couleurs[k]?.let { cell(x, y, it, s) } } }
    }
}

/** Métal des pièces : l'ambre de l'app éclairci, comme les boutons. */
@Composable
private fun metal(): Pair<Color, Map<Char, Color>> {
    val co = mix(MaterialTheme.colorScheme.tertiary, Color.White, 0.22f)
    val or = { n: Float -> mix(co, Color.Black, n) }
    return or(0.66f) to mapOf(
        'f' to co, 'r' to or(0.1f), 'h' to mix(co, Color.White, 0.4f), 'd' to or(0.3f), 'i' to or(0.2f),
        'e' to or(0.44f), 'E' to or(0.58f), 'x' to Color.White.copy(alpha = 0.85f),
    )
}

/** Cellules d'un dessin en dp (la cellule est arrondie au pixel). */
@Composable
private fun cellsDp(n: Int): Dp = with(LocalDensity.current) { (n * cellPx(CELLULE)).toDp() }

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
        val faites = remember(m.id) { mutableStateListOf<Int>() }
        var just by remember(m.id) { mutableStateOf<Int?>(null) }
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
        val (encre, metaux) = metal()
        val sc = MaterialTheme.colorScheme
        val creux = mix(sc.surface, sc.onSurface, 0.08f)
        val creuxH = mix(sc.surface, sc.onSurface, 0.24f)
        val creuxB = mix(sc.surface, Color.White, 0.55f)
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
                        else PieceMobile("p$i", gd, somme(i), d.pieces[i].montantTexte, unite, metaux, encre, !st.solved, c.reduit)
                    }
                }
                Led(if (st.solved) LedEtat.Verte else if (st.react > 0) LedEtat.Rouge else LedEtat.Eteinte)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                d.tirelires.forEachIndexed { j, nom ->
                    val i = d.pieceDe(j)
                    Tirelire("t$j", gd, nom, if (fait(i)) somme(i) else null, seat = just == i && !c.reduit, metaux, c.reduit, enabled = !st.solved)
                }
            }
        }
    }
}

/** Pièce à attraper : se soulève au glisser, revient si refusée ; appui = saisie. */
@Composable
private fun PieceMobile(
    id: String, gd: GlisserDeposer, label: String, texte: String, unite: String,
    metaux: Map<Char, Color>, encre: Color, enabled: Boolean, reduit: Boolean,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var lifted by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val sel = gd.selection == id
    val en by rememberUpdatedState(enabled)
    val ombre = app.kairos.mobile.ui.politique.jeux.kit.Teintes.ombre
    val haut = lifted || sel
    Box(
        Modifier.zIndex(if (haut) 1f else 0f).size(cellsDp(22), cellsDp(24))
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                translationX = off.value.x
                translationY = off.value.y - if (haut) 6.dp.toPx() else 0f
            }
            .pointerInput(id) {
                val marge = with(density) { 8.dp.toPx() }
                detectDragGestures(
                    onDragStart = { if (en) lifted = true },
                    onDrag = { ch, dv ->
                        if (!en) return@detectDragGestures
                        ch.consume()
                        scope.launch { off.snapTo(off.value + dv) }
                        gd.survol = gd.cibleSous(bounds.center + off.value + dv, marge)
                    },
                    onDragEnd = {
                        val hot = gd.survol
                        gd.survol = null
                        lifted = false
                        val ok = hot != null && gd.poser(id, hot)
                        scope.launch {
                            if (ok || reduit) off.snapTo(Offset.Zero)
                            else off.animateTo(Offset.Zero, tween(250, easing = Motion.EmphasizedDecelerate))
                        }
                    },
                    onDragCancel = { gd.survol = null; lifted = false; scope.launch { off.snapTo(Offset.Zero) } },
                )
            }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Button, onClickLabel = if (sel) "Reposer" else "Saisir") { gd.saisir(id) }
            .semantics { contentDescription = label + if (sel) ", saisie : choisissez une tirelire" else ""; selected = sel },
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
    metaux: Map<Char, Color>, reduit: Boolean, enabled: Boolean,
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
    val shake = remember { Animatable(0f) }
    val refus = gd.refus[id] ?: 0
    LaunchedEffect(refus) {
        if (refus > 0 && !reduit) shake.animateTo(0f, keyframes { durationMillis = 350; -5f at 87; 5f at 175; -3f at 262 })
    }
    val enfonce = remember { Animatable(0f) }
    LaunchedEffect(plein, seat) {
        if (plein && seat) { enfonce.snapTo(-14f); enfonce.animateTo(0f, tween(200, easing = Crans)) }
    }
    Box(
        Modifier.size(cellsDp(34), cellsDp(25))
            .onGloballyPositioned { if (!plein && enabled) gd.cibles[id] = it.boundsInRoot() else gd.cibles.remove(id) }
            .graphicsLayer { translationX = shake.value.dp.toPx() }
            .clickable(enabled = enabled && !plein, role = Role.Button, onClickLabel = "Glisser ici") {
                val t = gd.selection
                if (t != null) gd.poser(t, id) else gd.annoncerVide()
            }
            .semantics { contentDescription = "Tirelire $nom" + (montant?.let { " : $it" } ?: "") },
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
            Text(nom, color = sc.onSurface, fontSize = if (nom.length > 9) 11.sp else 12.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.01).em, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
            if (montant != null) Text(montant, color = sc.onSurfaceVariant, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}
