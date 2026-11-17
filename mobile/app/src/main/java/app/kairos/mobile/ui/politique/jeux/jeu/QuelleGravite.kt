package app.kairos.mobile.ui.politique.jeux.jeu

import app.kairos.mobile.ui.politique.jeux.kit.CouleursJeux
import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.BoutonValider
import app.kairos.mobile.ui.politique.jeux.kit.Cadran
import app.kairos.mobile.ui.politique.jeux.kit.MascotteGravite
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.frTypo

/**
 * Le gravitomètre : cadran à aiguille sur 4 crans, mascotte à côté qui réagit au cran ; on règle puis Valider.
 * Le cadran démarre sur le cran 1 ; un cran refusé est hachuré.
 */
class QuelleGravite : PolJeu<CranD>("quelle-gravite", CranD.serializer()) {

    override fun valide(d: CranD, params: JsonObject) = cranValide(d)

    @Composable
    override fun Fenetre(m: Manche, d: CranD, st: MancheEtat, c: Coque) = FenetreMesure(m)

    @Composable
    override fun Manette(m: Manche, d: CranD, st: MancheEtat, c: Coque) {
        val crans = remember(c.params) { c.params.params(CransParams.serializer(), CransParams()).crans }
        var choix by remember(st) { mutableIntStateOf(1) }
        val lv = if (st.solved) d.bonne else choix
        val ecartes = st.out.mapNotNull { it.toIntOrNull() }.toSet()
        val sc = MaterialTheme.colorScheme
        val gc = CouleursJeux.current.echelle(lv)
        val fond by animateColorAsState(mix(sc.surface, gc, lv * 0.05f), tween(if (c.reduit) 0 else 250), label = "fond")
        val bord by animateColorAsState(mix(sc.outlineVariant, gc, lv * 0.14f), tween(if (c.reduit) 0 else 250), label = "bord")
        // Frémissement du haut selon le cran atteint ; secousse franche à l'erreur.
        val dx = remember(st) { Animatable(0f) }
        var dir by remember(st) { mutableIntStateOf(0) }
        LaunchedEffect(st, choix) { if (!c.reduit && lv > 1 && !st.solved) dx.secouer(FREMIR.getValue(lv)) }
        val react0 = remember(st) { st.react }
        LaunchedEffect(st.react) { if (st.react > react0 && !c.reduit) dx.secouer(SECOUSSE) }
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(fond).border(2.dp, bord, shape).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.offset { IntOffset(dx.value.dp.roundToPx(), 0) }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MascotteGravite(lv)
                Cadran(
                    lv,
                    onNiveau = { n -> if (n != choix) { dir = n - choix; choix = n; c.cran() } },
                    Modifier.weight(1f), enabled = !st.solved, ecartes = ecartes, description = "Gravité",
                    libelle = { n -> "Cran $n : ${crans.nom(n)}" },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Etiquette(lv, "Cran $lv" + if (lv in ecartes && !st.solved) " · écarté" else "", crans.nom(lv), dir, c.reduit, Modifier.weight(1f).height(58.dp))
                BoutonValider(
                    onClick = { if (lv == d.bonne) c.juste() else c.faux(lv.toString(), "Pas le cran $lv.") },
                    enabled = !st.solved && lv !in ecartes,
                )
            }
        }
    }
}

private val SECOUSSE = listOf(-5f, 5f, -3f, 0f) to 420
private val FREMIR = mapOf(2 to (listOf(-1f, 1f, 0f) to 300), 3 to (listOf(-2.5f, 2.5f, -1.5f, 0f) to 340), 4 to SECOUSSE)

/** Parcourt les décalages [pts] (dp) en [pas.second] ms au total, puis revient à 0. */
internal suspend fun Animatable<Float, *>.secouer(pas: Pair<List<Float>, Int>) {
    val (pts, ms) = pas
    snapTo(0f)
    pts.forEach { animateTo(it, tween(ms / pts.size)) }
}

private val Decel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Ligne d'état sous la manette : sur-titre mono et nom du cran, qui entre par le haut ou le bas selon le sens du réglage. */
@Composable
internal fun Etiquette(cle: Int, haut: String, nom: String, dir: Int, reduit: Boolean, modifier: Modifier = Modifier, taille: Int = 16) {
    AnimatedContent(
        cle to (haut to nom), modifier.clipToBounds().clearAndSetSemantics { },
        transitionSpec = {
            if (reduit || dir == 0) ContentTransform(EnterTransition.None, ExitTransition.None)
            else slideInVertically(tween(250, easing = Decel)) { h -> if (dir > 0) h else -h } togetherWith ExitTransition.None
        },
        contentAlignment = Alignment.CenterStart, label = "etiquette",
    ) { (_, t) ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Text(t.first.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.08.em)
            Text(frTypo(t.second), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = taille.sp, lineHeight = (taille * 1.15f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Fenêtre des jeux de mesure : sur-titre, question en 18 sp, lignes d'appoint en 14 sp. */
@Composable
internal fun FenetreMesure(m: Manche, grand: Boolean = false, lignes: List<String> = emptyList()) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        if (m.kicker.isNotBlank()) Kicker(m.kicker)
        Text(
            frTypo(m.q), Modifier.semantics { heading() },
            fontSize = if (grand) 21.sp else 18.sp, lineHeight = if (grand) 27.sp else 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em,
            color = MaterialTheme.colorScheme.onSurface,
        )
        lignes.filter { it.isNotBlank() }.forEach { Text(frTypo(it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 19.sp) }
    }
}
