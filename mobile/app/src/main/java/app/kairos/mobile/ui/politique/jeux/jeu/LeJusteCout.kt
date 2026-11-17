package app.kairos.mobile.ui.politique.jeux.jeu

import app.kairos.mobile.ui.politique.jeux.kit.CouleursJeux
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.BoutonValider
import app.kairos.mobile.ui.politique.jeux.kit.PixelShape
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import app.kairos.mobile.ui.politique.jeux.kit.cell
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Chiffres pixel 5×7 des rouleaux. */
private val DIG = listOf(
    listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
    listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    listOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
    listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    listOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
)

/** Déplacement du doigt par cran (dp), seuil d'un glisser (dp). */
private const val PAS_DP = 9f
private const val SEUIL_DP = 4f
private val Decel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * La juste somme : compteur mécanique à rouleaux entraîné par une molette crantée verticale (crans de `params.crans`),
 * puis Valider. Une fourchette est acceptée ; après une erreur, la flèche allumée dit s'il fallait monter ou descendre.
 * La molette démarre sur le premier cran.
 */
class LeJusteCout : PolJeu<SommeD>("le-juste-cout", SommeD.serializer()) {

    /** Fourchette lisible et atteignable par au moins un cran de la molette. */
    override fun valide(d: SommeD, params: JsonObject) = sommeValide(d) && crans(params).any { sommeJuste(it, d) }

    /** Cran choisi, gardé dans l'état de la manche pour l'étiquette de la réponse. */
    private fun choix(st: MancheEtat): MutableIntState = st.jeu as? MutableIntState ?: mutableIntStateOf(0).also { st.jeu = it }

    private fun crans(p: JsonObject) = p.params(MoletteParams.serializer(), MoletteParams()).crans

    @Composable
    override fun Fenetre(m: Manche, d: SommeD, st: MancheEtat, c: Coque) = FenetreMesure(m)

    override fun etiquette(m: Manche, d: SommeD, st: MancheEtat, params: JsonObject): String? =
        crans(params).getOrNull((st.jeu as? MutableIntState)?.intValue ?: -1)?.let { sommeEtiquette(it, d) }

    @Composable
    override fun Manette(m: Manche, d: SommeD, st: MancheEtat, c: Coque) {
        val p = remember(c.params) { c.params.params(MoletteParams.serializer(), MoletteParams()) }
        val n = p.crans
        if (n.isEmpty()) return
        val etat = remember(st) { choix(st) }
        var i by etat
        val v = n[i.coerceIn(0, n.size - 1)]
        val essaye = !st.solved && fr(v) in st.out
        val monter = st.out.lastOrNull()?.takeIf { !st.solved }?.replace(',', '.')?.toDoubleOrNull()?.let { sommeMonter(it, d) }
        val dx = remember(st) { Animatable(0f) }
        val react0 = remember(st) { st.react }
        LaunchedEffect(st.react) { if (st.react > react0 && !c.reduit) dx.secouer(listOf(-5f, 5f, -3f, 0f) to 350) }
        val valider = {
            if (!st.solved && !essaye) {
                if (sommeJuste(v, d)) c.juste()
                else c.faux(fr(v), "Pas ${milliards(v)} : ${if (sommeMonter(v, d)) "plus" else "moins"}.")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Compteur(v, p.unite, essaye, st.solved, c.reduit, Modifier.offset { IntOffset(dx.value.dp.roundToPx(), 0) })
                BoutonValider(onClick = valider, enabled = !st.solved && !essaye)
            }
            Column(
                Modifier.offset { IntOffset(dx.value.dp.roundToPx(), 0) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Fleche(haut = true, on = monter == true)
                Molette(
                    i, n.size, enabled = !st.solved,
                    onCran = { k -> if (k != i) { i = k; c.cran() } },
                    etat = "${milliards(n[i.coerceIn(0, n.size - 1)])} d'euros" + if (essaye) ", déjà essayé" else "",
                )
                Fleche(haut = false, on = monter == false)
            }
        }
    }
}

/** Compteur à trois rouleaux (fenêtres 7×11 cellules de 4 dp) et unité ; hachuré si déjà essayé, teinté si juste. */
@Composable
private fun Compteur(v: Double, unite: String, essaye: Boolean, juste: Boolean, reduit: Boolean, modifier: Modifier) {
    val sc = MaterialTheme.colorScheme
    val ink = sc.onSurface
    val surf = sc.surface
    val corps = if (juste) CouleursJeux.current.let { mix(it.justeFond, it.juste, 0.3f) } else mix(surf, ink, 0.16f)
    val ombre = Teintes.ombre
    val shape = PixelShape()
    Box(modifier.clearAndSetSemantics { }) {
        Box(Modifier.matchParentSize().offset(y = 3.dp).clip(shape).background(ombre))
        Row(
            Modifier.clip(shape).background(corps)
                .drawBehind {
                    val b = 3.dp.toPx()
                    drawRect(mix(surf, ink, 0.06f), Offset.Zero, Size(size.width, b))
                    drawRect(mix(surf, ink, 0.06f), Offset.Zero, Size(b, size.height))
                    drawRect(mix(surf, ink, 0.30f), Offset(0f, size.height - b), Size(size.width, b))
                    drawRect(mix(surf, ink, 0.30f), Offset(size.width - b, 0f), Size(b, size.height))
                }
                .padding(9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            rouleaux(v).forEach { Rouleau(it, essaye, reduit) }
            Text(unite, Modifier.padding(start = 3.dp), color = ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
    }
}

/** Un rouleau : bande 0 à 9 qui défile jusqu'au chiffre [n]. */
@Composable
private fun Rouleau(n: Int, essaye: Boolean, reduit: Boolean) {
    val sc = MaterialTheme.colorScheme
    val ink = sc.onSurface
    val surf = sc.surface
    val pos by animateFloatAsState(n.toFloat(), tween(if (reduit) 0 else 180, easing = Decel), label = "rouleau")
    val s = cellPx(4.dp)
    val wDp = with(LocalDensity.current) { (7 * s).toDp() }
    val hDp = with(LocalDensity.current) { (11 * s).toDp() }
    Canvas(Modifier.size(wDp, hDp).clip(androidx.compose.ui.graphics.RectangleShape)) {
        drawRect(surf)
        if (essaye) for (j in 0 until 11) for (k in 0 until 7) if ((k + j) % 2 == 1) cell(k, j, mix(surf, ink, 0.10f), s)
        val col = if (essaye) mix(surf, ink, 0.40f) else ink
        val off = (pos * 11 * s).roundToInt()
        for (dgt in 0..9) {
            val top = dgt * 11 * s - off
            if (top > 11 * s || top + 11 * s < 0) continue
            DIG[dgt].forEachIndexed { j, row ->
                row.forEachIndexed { k, ch -> if (ch == '#') drawRect(col, Offset(((k + 1) * s).toFloat(), (top + (j + 2) * s).toFloat()), Size(s.toFloat(), s.toFloat())) }
            }
        }
        // Ombres internes haute et basse, reflet sous le bord haut.
        drawRect(mix(surf, ink, 0.20f), Offset.Zero, Size(size.width, s.toFloat()))
        drawRect(mix(surf, ink, 0.07f).copy(alpha = 0.5f), Offset(0f, s.toFloat()), Size(size.width, s.toFloat()))
        drawRect(mix(surf, ink, 0.12f), Offset(0f, size.height - s), Size(size.width, s.toFloat()))
    }
}

/** Voyant fléché 9×5 (cellules de 3 dp) : rouge allumé quand il indique le sens. */
@Composable
private fun Fleche(haut: Boolean, on: Boolean) {
    val sc = MaterialTheme.colorScheme
    val g = remember(haut) { fleche(haut) }
    val s = cellPx(3.dp)
    val fo = if (on) Color(0xFFB3261E) else mix(sc.surface, sc.onSurface, 0.34f)
    val fi = if (on) Color(0xFFFF3B30) else mix(sc.surface, sc.onSurface, 0.18f)
    val d = LocalDensity.current
    Canvas(Modifier.size(with(d) { (9 * s).toDp() }, with(d) { (5 * s).toDp() }).clearAndSetSemantics { }) {
        g.forEachIndexed { j, row -> row.forEachIndexed { i, ch -> if (ch != '.') cell(i, j, if (ch == 'o') fo else fi, s) } }
    }
}

/**
 * Molette verticale, cellules de 3 dp : glisser fait tourner d'un cran tous les 9 dp, la molette suit le doigt ;
 * appui court : moitié haute +1, basse −1. [i] : cran courant sur [n].
 */
@Composable
private fun Molette(i: Int, n: Int, enabled: Boolean, onCran: (Int) -> Unit, etat: String) {
    val sc = MaterialTheme.colorScheme
    val s = cellPx(3.dp)
    val d = LocalDensity.current
    val pas = with(d) { PAS_DP.dp.toPx() }
    val seuil = with(d) { SEUIL_DP.dp.toPx() }
    val set by rememberUpdatedState(onCran)
    val cur by rememberUpdatedState(i)
    var phi by remember { mutableStateOf<Float?>(null) }
    val angle = phi ?: (i * MOL_DA)
    val cells = remember(angle) { molette(angle) }
    val a = if (enabled) 1f else 0.7f
    val cols = mapOf(
        Mol.Sd to mix(sc.surface, Color.Black, 0.22f), Mol.Sl to mix(sc.surface, Color.White, 0.45f),
        Mol.B0 to mix(sc.onSurfaceVariant, Color.White, 0.34f), Mol.B1 to mix(sc.onSurfaceVariant, Color.White, 0.16f),
        Mol.B2 to sc.onSurfaceVariant, Mol.B3 to mix(sc.onSurfaceVariant, Color.Black, 0.3f),
        Mol.Rg to mix(sc.onSurfaceVariant, Color.Black, 0.58f), Mol.Rl to mix(sc.onSurfaceVariant, Color.White, 0.52f),
    ).mapValues { it.value.copy(alpha = a) }
    Canvas(
        Modifier.size(with(d) { (MOL_W * s).toDp() }, with(d) { (MOL_H * s).toDp() })
            .semantics {
                contentDescription = "Molette du montant"
                stateDescription = etat
                progressBarRangeInfo = ProgressBarRangeInfo(i.toFloat(), 0f..(n - 1).toFloat(), (n - 2).coerceAtLeast(0))
                if (!enabled) disabled() else setProgress { t -> set(t.roundToInt().coerceIn(0, n - 1)); true }
            }
            .pointerInput(enabled, n) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val i0 = cur
                    var moved = 0f
                    var up = down.position
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { up = ch.position; break }
                        ch.consume()
                        val dy = down.position.y - ch.position.y
                        moved = maxOf(moved, abs(dy))
                        if (moved < seuil) continue
                        val p = (i0 + dy / pas).coerceIn(0f, (n - 1).toFloat())
                        set(p.roundToInt())
                        phi = p * MOL_DA
                    }
                    phi = null
                    if (moved < seuil) set((cur + if (up.y < size.height / 2) 1 else -1).coerceIn(0, n - 1))
                }
            },
    ) {
        for (y in 0 until MOL_H) for (x in 0 until MOL_W) cells[y * MOL_W + x]?.let { cell(x, y, cols.getValue(it), s) }
    }
}
