package app.kairos.mobile.ui.politique.jeux.jeu

import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.BoutonValider
import app.kairos.mobile.ui.politique.jeux.kit.Led
import app.kairos.mobile.ui.politique.jeux.kit.LedEtat
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.screens.Projection
import app.kairos.mobile.ui.politique.screens.RegionGeo
import app.kairos.mobile.ui.politique.screens.RegionShape
import app.kairos.mobile.ui.politique.screens.boundsOf
import app.kairos.mobile.ui.politique.screens.one
import app.kairos.mobile.ui.politique.screens.parsePath
import app.kairos.mobile.ui.theme.KairosColors
import kotlin.math.roundToInt

private val Glisse = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * Sur la carte : carte de France pixel (régions en aplats, ombre de côte) et encarts d'outre-mer, quatre épingles aux
 * vraies coordonnées. On promène une loupe : elle s'accroche à l'épingle la plus proche, nomme la ville et éclaire sa
 * région ; Valider. La loupe part sur la première épingle.
 */
class LaVille : PolJeu<VilleD>("la-ville", VilleD.serializer()) {

    override fun valide(d: VilleD, params: JsonObject) = d.choix.size in 2..6 && d.bonne in d.choix.indices && d.choix.all(::epingleOk)

    @Composable
    override fun Manette(m: Manche, d: VilleD, st: MancheEtat, c: Coque) {
        val sc = MaterialTheme.colorScheme
        val kc = KairosColors.current
        val density = LocalDensity.current
        val data = c.data
        val proj = remember(data) { data.one("carte", "projection", Projection.serializer()) ?: PROJ_DEFAUT }
        // ponytail: grille calculée au premier affichage (≈ 4 000 tests point-dans-région) ; hors fil principal si ça se sent.
        val grille = remember(data) {
            grilleRegions(data.decode("carte", "regions", RegionShape.serializer()).map { r -> parsePath(r.d).let { RegionGeo(r.code, r.nom, it, boundsOf(it)) } })
        }
        var sel by remember(m.id) { mutableIntStateOf(0) }
        val i = if (st.solved) d.bonne else sel
        val e = d.choix[i]
        val out = "$i" in st.out && !st.solved

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val oy = remember(m.id, proj) { margeHaute(proj, d.choix) }
            val ecart = with(density) { 4.dp.roundToPx() }
            val vGap = with(density) { 5.dp.roundToPx() }
            val colGap = with(density) { 12.dp.roundToPx() }
            val w = constraints.maxWidth
            val s = minOf(cellPx(3.dp), (w - ecart * (ENCARTS.size - 1)) / ENCARTS_W, (with(density) { 260.dp.roundToPx() } - vGap) / (CARTE_H + oy + ENCART_H)).coerceAtLeast(1)
            val mapW = CARTE_W * s
            val mapH = (CARTE_H + oy) * s
            val h = mapH + vGap + ENCART_H * s
            val pts = remember(m.id, s, oy, proj) { d.choix.map { pointe(it, proj, s, oy, ecart) } }
            val medX = remember(s, ecart) { encartsX(s, ecart) }
            val tete = 4.5f * s

            val loupe = remember(m.id) { Animatable(pts[i] - Offset(0f, tete), Offset.VectorConverter) }
            var drag by remember(m.id) { mutableStateOf<Offset?>(null) }
            // Lâchée : la loupe repart de là où le doigt l'a laissée.
            val lachee = remember(m.id) { arrayOfNulls<Offset>(1) }
            LaunchedEffect(i, drag == null, pts) {
                if (drag != null) return@LaunchedEffect
                lachee[0]?.let { loupe.snapTo(it); lachee[0] = null }
                val to = pts[i] - Offset(0f, tete)
                if (c.reduit) loupe.snapTo(to) else loupe.animateTo(to, tween(200, easing = Glisse))
            }
            val choisir = { n: Int ->
                if (n != sel) { sel = n; c.cran() }
            }
            val valider = {
                if ("$sel" !in st.out) {
                    if (sel == d.bonne) c.juste() else c.faux("$sel", "Pas ${d.choix[sel].ville}.")
                }
            }

            val ink = sc.onSurface
            val surf = sc.surface
            fun inkP(p: Float) = mix(surf, ink, p)
            val tintes = listOf(inkP(0.09f), inkP(0.14f), inkP(0.19f), inkP(0.24f))
            val bord = inkP(0.34f)
            val cote = mix(kc.ciel, kc.nuit, 0.22f)
            val allume = mix(surf, sc.primary, 0.30f)
            val allumeBord = mix(surf, sc.primary, 0.55f)
            val reg = e.region
            val ombre = Teintes.ombre
            val att = sc.tertiary

            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                Canvas(
                    Modifier.matchParentSize()
                        .semantics {
                            contentDescription = "Carte des villes"
                            stateDescription = "${e.ville}, ${e.regionNom}" + if (out) ", écarté" else ""
                            progressBarRangeInfo = ProgressBarRangeInfo(i.toFloat(), 0f..(d.choix.size - 1).toFloat(), (d.choix.size - 2).coerceAtLeast(0))
                            if (st.solved) disabled() else setProgress { v -> choisir(v.roundToInt().coerceIn(0, d.choix.size - 1)); true }
                        }
                        .pointerInput(st.solved, pts) {
                            if (st.solved) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // La colonne de droite (lecture, Valider) garde ses propres appuis.
                                if (down.position.x > mapW + colGap / 2 && down.position.y < mapH) return@awaitEachGesture
                                down.consume()
                                fun at(p: Offset) {
                                    val q = Offset(p.x.coerceIn(0f, w.toFloat()), p.y.coerceIn(0f, h.toFloat()))
                                    drag = q
                                    lachee[0] = q
                                    choisir(plusProche(pts, q.x, q.y, tete))
                                }
                                try {
                                    at(down.position)
                                    while (true) {
                                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        ch.consume()
                                        at(ch.position)
                                    }
                                } finally {
                                    drag = null
                                }
                                c.annoncer(d.choix[sel].ville + if ("$sel" in st.out) ", déjà écarté." else ".")
                            }
                        },
                ) {
                    // Métropole : mer, régions, frontières, ombre de côte.
                    bloc(0, 0, mapW, mapH, s, kc.ciel)
                    for (y in 0 until CARTE_H) for (x in 0 until CARTE_W) {
                        val code = grille[y * CARTE_W + x]
                        val px = x * s
                        val py = (y + oy) * s
                        if (code == null) {
                            if (y > 0 && grille[(y - 1) * CARTE_W + x] != null) rect(px, py, s, s, cote)
                            continue
                        }
                        val r = if (x + 1 < CARTE_W) grille[y * CARTE_W + x + 1] else null
                        val b = if (y + 1 < CARTE_H) grille[(y + 1) * CARTE_W + x] else null
                        val bd = (r != null && r != code) || (b != null && b != code)
                        rect(px, py, s, s, if (code == reg) (if (bd) allumeBord else allume) else if (bd) bord else tintes[teinte(code)])
                    }
                    // Encarts d'outre-mer.
                    val top = mapH + vGap
                    val lisere = mix(kc.ciel, kc.nuit, 0.14f)
                    ENCARTS.forEachIndexed { k, m2 ->
                        val x0 = medX[k]
                        val l = (s / 3f).roundToInt().coerceAtLeast(1)
                        bloc(x0, top, m2.w * s, ENCART_H * s, s, lisere)
                        bloc(x0 + l, top + l, m2.w * s - 2 * l, ENCART_H * s - 2 * l, s, kc.ciel)
                        val on = m2.code == reg
                        m2.g.forEachIndexed { y, row ->
                            row.forEachIndexed { x, ch ->
                                val px = x0 + (m2.ox + x) * s
                                val py = top + (m2.oy + y) * s
                                if (ch == '#') rect(px, py, s, s, if (on) allume else tintes[3])
                                else if (y > 0 && m2.g[y - 1][x] == '#') rect(px, py, s, s, cote)
                            }
                            if (y == m2.gh - 1) row.forEachIndexed { x, ch -> if (ch == '#') rect(x0 + (m2.ox + x) * s, top + (m2.oy + y + 1) * s, s, s, cote) }
                        }
                    }
                    // Loupe, sous les épingles.
                    val lc = drag ?: loupe.value
                    val lx = (lc.x - 6.5f * s).roundToInt()
                    val ly = (lc.y - 6.5f * s).roundToInt()
                    sprite(LOUPE, lx, ly + s, s) { k -> if (k == 'v' || k == 'h') null else ombre }
                    sprite(LOUPE, lx, ly, s) { k ->
                        when (k) {
                            'v' -> mix(kc.ciel, Color.White, 0.4f).copy(alpha = 0.28f)
                            'h' -> Color.White.copy(alpha = 0.6f)
                            'r' -> inkP(0.70f)
                            'D' -> inkP(0.85f)
                            'm' -> mix(att, Color.Black, 0.3f)
                            else -> mix(att, Color.Black, 0.5f)
                        }
                    }
                    // Épingles : la pointe sur la ville.
                    d.choix.indices.forEach { j ->
                        val pc = when {
                            st.solved && j == d.bonne -> sc.primary
                            "$j" in st.out -> sc.error
                            j == i -> att
                            else -> inkP(0.58f)
                        }
                        val a = if ("$j" in st.out && !(st.solved && j == d.bonne)) 0.55f else 1f
                        val ex = (pts[j].x - 2.5f * s).roundToInt()
                        val ey = (pts[j].y - 7f * s).roundToInt()
                        sprite(EPINGLE, ex + s, ey + s, s) { k -> if (k == 'o') null else ombre.copy(alpha = ombre.alpha * a) }
                        sprite(EPINGLE, ex, ey, s) { k ->
                            when (k) {
                                'a' -> pc
                                'b' -> Color.White.copy(alpha = 0.85f)
                                'd' -> mix(pc, Color.Black, 0.3f)
                                'e' -> inkP(0.62f)
                                else -> null
                            }?.let { it.copy(alpha = it.alpha * a) }
                        }
                    }
                }
                // Noms des encarts.
                ENCARTS.forEachIndexed { k, m2 ->
                    Text(
                        m2.nom.uppercase(),
                        Modifier.offset { IntOffset(medX[k] + 5.dp.roundToPx(), mapH + vGap + 4.dp.roundToPx()) }.clearAndSetSemantics { },
                        color = mix(kc.nuit, kc.ciel, 0.25f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = 8.sp, lineHeight = 8.sp, letterSpacing = 0.03.em, maxLines = 1, softWrap = false,
                    )
                }
                // Lecture et Valider, à droite de la carte.
                Column(
                    Modifier.offset { IntOffset(mapW + colGap, 0) }.size(with(density) { (w - mapW - colGap).coerceAtLeast(0).toDp() }, with(density) { mapH.toDp() }),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(frTypo(e.ville), color = if (out) sc.onSurfaceVariant else sc.onSurface, fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            e.regionNom.uppercase(), color = sc.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp, lineHeight = 13.sp, letterSpacing = 0.05.em,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Led(if (st.solved) LedEtat.Verte else if (out) LedEtat.Rouge else LedEtat.Eteinte, cellule = 2.dp)
                        BoutonValider(valider, enabled = !st.solved && !out)
                    }
                }
            }
        }
    }
}

private fun DrawScope.rect(x: Int, y: Int, w: Int, h: Int, c: Color) =
    drawRect(c, Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()))

/** Rectangle aux coins en deux marches d'une cellule [s]. */
private fun DrawScope.bloc(x: Int, y: Int, w: Int, h: Int, s: Int, c: Color) {
    rect(x + 2 * s, y, w - 4 * s, s, c)
    rect(x + s, y + s, w - 2 * s, s, c)
    rect(x, y + 2 * s, w, h - 4 * s, c)
    rect(x + s, y + h - 2 * s, w - 2 * s, s, c)
    rect(x + 2 * s, y + h - s, w - 4 * s, s, c)
}

/** Grille de lettres à la cellule [s], coin en [x], [y] px ; [couleur] null : rien. */
private fun DrawScope.sprite(g: List<String>, x: Int, y: Int, s: Int, couleur: (Char) -> Color?) =
    g.forEachIndexed { j, row -> row.forEachIndexed { k, ch -> if (ch != '.') couleur(ch)?.let { rect(x + k * s, y + j * s, s, s, it) } } }
