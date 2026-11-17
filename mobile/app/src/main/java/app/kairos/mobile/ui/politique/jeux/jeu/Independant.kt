package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.Dp
import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.BoutonPixel
import app.kairos.mobile.ui.politique.jeux.kit.Led
import app.kairos.mobile.ui.politique.jeux.kit.LedEtat
import app.kairos.mobile.ui.politique.jeux.kit.cell
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.PolColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.min

private val CHIFFRES = mapOf(
    1 to listOf(".#.", "##.", ".#.", ".#.", "###"), 2 to listOf("##.", "..#", ".#.", "#..", "###"),
    3 to listOf("##.", "..#", ".#.", "..#", "##."), 4 to listOf("#.#", "#.#", "###", "..#", "..#"),
)

/**
 * Indépendant ? : balance romaine. Le sac (l'argent de l'évaluateur) pend au bras court ; on pose le poids sur l'un des
 * 4 crans d'indépendance (glisser sur le fléau ou touches colorées), puis Valider. Le juste cran remet le fléau
 * d'aplomb ; un faux cran le fait vaciller, du même mouvement quel que soit le cran. Le poids démarre sur le cran 1.
 */
class Independant : PolJeu<CranD>("independant", CranD.serializer()) {

    override fun valide(d: CranD, params: JsonObject) = cranValide(d)

    @Composable
    override fun Fenetre(m: Manche, d: CranD, st: MancheEtat, c: Coque) = FenetreMesure(m, grand = true, lignes = listOf(d.argent, d.lien))

    @Composable
    override fun Manette(m: Manche, d: CranD, st: MancheEtat, c: Coque) {
        val niveaux = remember(c.params) { c.params.params(CransParams.serializer(), CransParams()).niveaux }
        val scope = rememberCoroutineScope()
        var sel by remember(st) { mutableIntStateOf(premierLibre(st.out)) }
        var poids by remember(st) { mutableIntStateOf(if (st.solved) d.bonne else sel) }
        var ko by remember(st) { mutableStateOf(false) }
        var dir by remember(st) { mutableIntStateOf(0) }
        var pas by remember(st) { mutableStateOf<Job?>(null) }
        val angle = remember(st) { Animatable(if (st.solved) 0f else ROM_REPOS) }
        val n = if (st.solved) d.bonne else sel
        val poser = { i: Int ->
            if (!st.solved && i.toString() !in st.out && i != sel) {
                dir = i - sel
                sel = i
                ko = false
                c.cran()
                c.annoncer("Niveau $i : ${niveaux.nom(i)}.")
                // Le poids glisse le long du bras, cran par cran.
                pas?.cancel()
                pas = scope.launch {
                    if (c.reduit) { poids = i; return@launch }
                    val sens = if (poids < i) 1 else -1
                    while (poids != i) { poids += sens; delay(45) }
                }
            }
        }
        // Faux : le poids reste sur le cran écarté, le fléau vacille, puis le poids revient au premier cran libre.
        val react0 = remember(st) { st.react }
        LaunchedEffect(st.react) {
            if (st.react <= react0) return@LaunchedEffect
            ko = true
            pas?.cancel()
            if (!c.reduit) for (a in listOf(3f, -9f, ROM_REPOS)) angle.animateTo(a, tween(100, easing = FastOutSlowInEasing))
            delay(if (c.reduit) 0 else 120)
            dir = 0
            // Le joueur a pu choisir un autre cran pendant la secousse : on ne le déplace que s'il reste sur un cran écarté.
            if (sel.toString() in st.out) sel = premierLibre(st.out)
            poids = sel
        }
        // Juste : le fléau remonte d'aplomb, léger dépassement.
        LaunchedEffect(st.solved) {
            if (!st.solved) return@LaunchedEffect
            pas?.cancel()
            poids = d.bonne
            if (c.reduit) angle.snapTo(0f) else for (a in listOf(1.5f, 0f)) angle.animateTo(a, tween(160, easing = FastOutSlowInEasing))
        }
        val ok = !st.solved && sel.toString() !in st.out
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val s = min(cellPx(3.dp), maxOf(1, floor(constraints.maxWidth.toFloat() / ROM_W).toInt()))
            val dens = LocalDensity.current
            val w = with(dens) { (ROM_W * s).toDp() }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Romaine(angle.value, poids, st.out, s, enabled = !st.solved, onCran = poser)
                Box(Modifier.width(w).height(69.dp)) {
                    BoutonPixel("Valider", onClick = {
                        if (ok) { if (sel == d.bonne) c.juste() else c.faux(sel.toString(), "Pas le niveau $sel.") }
                    }, enabled = ok, cellule = 2.7.dp)
                    // Une touche par cran, jamais plus large que l'écart entre deux crans (écrans étroits).
                    val largeur = with(dens) { minOf(54.dp, (18 * s).toDp() - 2.dp) }
                    for (i in 1..4) {
                        val out = i.toString() in st.out
                        Touche(
                            i, appuyee = n == i, ecartee = out, enabled = !st.solved && !out,
                            label = "Niveau $i : ${niveaux.nom(i)}" + if (out) " (écarté)" else "",
                            onClick = { poser(i) },
                            modifier = Modifier.offset(x = with(dens) { ((ROM_PX + ROM_D[i]) * s).toDp() } - largeur / 2, y = 10.dp),
                            largeur = largeur,
                        )
                    }
                }
                Row(Modifier.width(w).height(48.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Led(if (st.solved) LedEtat.Verte else if (ko) LedEtat.Rouge else LedEtat.Eteinte)
                    Etiquette(n, "Niveau $n", niveaux.nom(n), dir, c.reduit, Modifier.weight(1f), taille = 14)
                }
            }
        }
    }
}

/** Scène de la romaine (108×34 cellules de [s] px) ; glisser le doigt le long du fléau pose le poids sur le cran libre le plus proche. */
@Composable
private fun Romaine(deg: Float, poids: Int, out: List<String>, s: Int, enabled: Boolean, onCran: (Int) -> Unit) {
    val sc = MaterialTheme.colorScheme
    val dens = LocalDensity.current
    val poser by rememberUpdatedState(onCran)
    val outs by rememberUpdatedState(out)
    val cells = remember(deg, poids, out.toList()) { romaine(deg, poids, out) }
    val on = sc.onSurfaceVariant
    val att = sc.tertiary
    val k = Color.Black
    val b = Color.White
    val cols = mapOf(
        Rom.Hk to mix(on, k, 0.25f), Rom.Ch to mix(on, k, 0.1f), Rom.Bt to mix(on, b, 0.4f), Rom.Bm to on, Rom.Bd to mix(on, k, 0.45f),
        Rom.Nk to mix(on, k, 0.65f), Rom.Bo to mix(on, b, 0.75f), Rom.Pv to mix(on, b, 0.7f),
        Rom.Sd to mix(att, k, 0.45f), Rom.Sk to mix(att, k, 0.25f), Rom.Ss to att, Rom.Sl to mix(att, b, 0.45f), Rom.Sx to mix(att, k, 0.18f), Rom.Se to mix(att, k, 0.4f),
        Rom.Wd to mix(on, k, 0.7f), Rom.Ww to mix(on, k, 0.3f), Rom.Wl to mix(on, b, 0.3f), Rom.Wx to mix(on, k, 0.5f),
    )
    Canvas(
        Modifier.size(with(dens) { (ROM_W * s).toDp() }, with(dens) { (ROM_H * s).toDp() })
            .clipToBounds()
            .clearAndSetSemantics { }
            .pointerInput(enabled, s) {
                if (!enabled) return@pointerInput
                fun at(x: Float) = romaineCran(x / s - ROM_PX, outs)?.let(poser)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    at(down.position.x)
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        at(ch.position.x)
                    }
                }
            },
    ) {
        cells.forEach { (r, l) -> val col = cols.getValue(r); l.forEach { (x, y) -> cell(x, y, col, s) } }
    }
}

/**
 * Touche colorée d'un niveau (cible 54 dp) : plateau 42×36 dp à la teinte du niveau, flanc de 9 dp ; enfoncée quand le
 * poids est sur ce cran, à plat et hachurée quand le niveau est écarté.
 */
@Composable
private fun Touche(n: Int, appuyee: Boolean, ecartee: Boolean, enabled: Boolean, label: String, onClick: () -> Unit, modifier: Modifier, largeur: Dp = 54.dp) {
    val sc = MaterialTheme.colorScheme
    val pc = PolColors.current
    val (kb0, ki0) = when (n) { 1 -> pc.ind1 to pc.ind1Ink; 2 -> pc.ind2 to pc.ind2Ink; 3 -> pc.ind3 to pc.ind3Ink; else -> sc.surface to sc.onSurface }
    val gris = mix(sc.outlineVariant, sc.onSurfaceVariant, 0.22f)
    val kb = if (ecartee) gris else kb0
    val ki = if (ecartee) sc.surface else ki0
    val contour = n == 4 && !ecartee
    Box(
        modifier.size(largeur, 54.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; selected = appuyee },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = 1.dp.toPx()
            val pw = minOf(42 * u, size.width - 4 * u)
            val ph = 36 * u
            val x0 = (size.width - pw) / 2
            val dy = when { ecartee -> 9 * u; appuyee -> 6 * u; else -> 0f }
            val flanc = when { ecartee -> 0f; appuyee -> 3 * u; else -> 9 * u }
            val fond = when {
                n == 4 && appuyee -> mix(sc.surface, sc.onSurface, 0.16f)
                appuyee -> mix(kb, Color.Black, 0.2f)
                else -> kb
            }
            val ombre = if (n == 4) mix(sc.surface, sc.onSurface, 0.5f) else mix(kb, Color.Black, if (appuyee) 0.5f else 0.38f)
            if (flanc > 0) drawRect(ombre, Offset(x0, dy + ph), Size(pw, flanc))
            drawRect(fond, Offset(x0, dy), Size(pw, ph))
            if (ecartee) {
                // Hachures en marches de 3 dp.
                val c3 = 3 * u
                var j = 0
                while (j * c3 < ph) {
                    var i = 0
                    while (i * c3 < pw) {
                        if ((i + j) % 2 == 1) drawRect(mix(kb, sc.surface, 0.45f), Offset(x0 + i * c3, dy + j * c3), Size(minOf(c3, pw - i * c3), minOf(c3, ph - j * c3)))
                        i++
                    }
                    j++
                }
            }
            if (contour) {
                val t = 3 * u
                drawRect(sc.onSurface, Offset(x0, dy), Size(pw, t)); drawRect(sc.onSurface, Offset(x0, dy + ph - t), Size(pw, t))
                drawRect(sc.onSurface, Offset(x0, dy), Size(t, ph)); drawRect(sc.onSurface, Offset(x0 + pw - t, dy), Size(t, ph))
            }
            if (!ecartee) drawRect(Color.White.copy(alpha = if (appuyee) 0.25f else 0.55f), Offset(x0 + 3 * u, dy + 3 * u), Size(6 * u, 3 * u))
            val s = 4.dp.roundToPx()
            val gx = (x0 + (pw - 3 * s) / 2).toInt()
            val gy = (dy + (ph - 5 * s) / 2).toInt()
            CHIFFRES.getValue(n).forEachIndexed { y, row ->
                row.forEachIndexed { x, ch -> if (ch == '#') drawRect(ki, Offset((gx + x * s).toFloat(), (gy + y * s).toFloat()), Size(s.toFloat(), s.toFloat())) }
            }
        }
    }
}

