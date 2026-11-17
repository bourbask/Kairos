package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import app.kairos.mobile.ui.politique.jeux.kit.Levier
import app.kairos.mobile.ui.politique.jeux.kit.PixelGrid
import app.kairos.mobile.ui.politique.jeux.kit.PixelIcon
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class AvantApresD(val bonne: Int = -1)

@Serializable
data class CranAA(val n: Int = -1, val nom: String = "", val glose: String = "")

/** Les trois crans de `params.crans` par rang (0 constance, 1 inflexion, 2 revirement) ; un cran absent : vide. */
fun cransAA(params: JsonObject): List<CranAA> {
    val lus = (params["crans"] as? JsonArray).orEmpty().mapNotNull { runCatching { PolJson.decodeFromJsonElement(CranAA.serializer(), it) }.getOrNull() }
    return (0..2).map { n -> lus.firstOrNull { it.n == n } ?: CranAA(n) }
}

/** Pictogrammes 12 × 11 des crans : trait droit (même cap), vaguelette (ajustement), demi-tour ; croix : cran écarté. */
private val PICTOS = listOf(
    listOf("............", "............", "........#...", "........##..", "........###.", "############", "############", "........###.", "........##..", "........#...", "............"),
    listOf("............", "............", "........#...", "........##..", "..##....###.", ".####..#####", "##..########", "#....##.###.", "........##..", "........#...", "............"),
    listOf("##########..", "###########.", "..........##", "...#......##", "..##......##", ".###......##", "############", "###########.", ".###........", "..##........", "...#........"),
)
private val CROIX = listOf("............", "............", "..##....##..", "..###..###..", "...######...", "....####....", "....####....", "...######...", "..###..###..", "..##....##..", "............")

/**
 * Avant, après : la position d'hier est dans la fenêtre ; on pousse un levier sur trois crans (constance, inflexion,
 * revirement : plus il va loin, plus la position a bougé), puis Valider. Le levier part sur le premier cran.
 */
class AvantApres : PolJeu<AvantApresD>("avant-apres", AvantApresD.serializer()) {

    override fun valide(d: AvantApresD) = d.bonne in 0..2

    override fun etiquette(m: Manche, d: AvantApresD, st: MancheEtat, params: JsonObject): String? =
        cransAA(params)[d.bonne].nom.takeIf { it.isNotBlank() }

    @Composable
    override fun Fenetre(m: Manche, d: AvantApresD, st: MancheEtat) {
        val sc = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize()) {
            PixelIcon("avant-apres", sc.primary.copy(alpha = 0.16f), Modifier.padding(12.dp).size(112.dp))
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                if (m.kicker.isNotBlank()) Kicker(m.kicker)
                Text(
                    frTypo(m.q), Modifier.semantics { heading() },
                    fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em, color = sc.onSurface,
                )
                Text("Et aujourd'hui ?", color = sc.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    }

    @Composable
    override fun Manette(m: Manche, d: AvantApresD, st: MancheEtat, c: Coque) {
        val sc = MaterialTheme.colorScheme
        val crans = remember(c.params) { cransAA(c.params) }
        var pos by remember(m.id) { mutableIntStateOf(0) }
        val v = if (st.solved) d.bonne else pos
        val out = "$v" in st.out && !st.solved
        val secousse = remember(m.id) { Animatable(0f) }
        LaunchedEffect(st.react) {
            if (st.react > 0 && !c.reduit) secousse.animateTo(0f, keyframes { durationMillis = 350; -5f at 87; 5f at 175; -3f at 262 })
        }
        val valider = {
            if ("$pos" !in st.out) {
                if (pos == d.bonne) c.juste() else c.faux("$pos", "Pas « ${crans[pos].nom.lowercase()} ».")
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Pictogrammes au droit des crans du levier (même marge que sa manette : 6 cellules).
            val marge = 6 * cellPx(3.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().height(33.dp).clearAndSetSemantics { }) {
                val w = constraints.maxWidth
                val demi = with(LocalDensity.current) { 18.dp.roundToPx() }
                (0..2).forEach { i ->
                    val ecarte = "$i" in st.out
                    val col = when {
                        ecarte -> sc.error.copy(alpha = 0.75f)
                        i == v -> sc.onSurface
                        else -> mix(sc.onSurfaceVariant, sc.surface, 0.3f)
                    }
                    PixelGrid(
                        if (ecarte) CROIX else PICTOS[i], col,
                        Modifier.offset { IntOffset(marge + (w - 2 * marge) * i / 2 - demi, 0) }.size(36.dp, 33.dp),
                    )
                }
            }
            Levier(
                3, v, onValeur = { pos = it },
                Modifier.offset { IntOffset(secousse.value.dp.roundToPx(), 0) },
                enabled = !st.solved,
                description = frTypo(m.q),
                libelle = { n -> "${crans[n].nom} : ${crans[n].glose}" + if ("$n" in st.out) ", écarté" else "" },
                onCran = c::cran,
                couleur = sc.tertiary,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clearAndSetSemantics { }) {
                    Text(crans[v].nom, color = if (out) sc.onSurfaceVariant else sc.onSurface, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        crans[v].glose.uppercase(), color = sc.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em,
                    )
                }
                Led(if (st.solved) LedEtat.Verte else if (out) LedEtat.Rouge else LedEtat.Eteinte)
                BoutonValider(valider, enabled = !st.solved && !out)
            }
        }
    }
}
