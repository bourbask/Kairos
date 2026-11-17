package app.kairos.mobile.ui.politique.jeux.jeu

import kotlinx.serialization.json.JsonObject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.Emplacement
import app.kairos.mobile.ui.politique.jeux.kit.PixelIcon
import app.kairos.mobile.ui.politique.jeux.kit.TUILE_HAUTEUR
import app.kairos.mobile.ui.politique.jeux.kit.Tuile
import app.kairos.mobile.ui.politique.jeux.kit.rememberGlisserDeposer
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.frTypo
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class Paire(val media: String = "", val proprietaire: String = "", val groupe: String? = null)

/** `d` de `les-liens` : paires média / propriétaire, dans l'ordre des tuiles. */
@Serializable
data class LiensD(val paires: List<Paire> = emptyList())

/** Ordre d'affichage des emplacements : permutation de 0 until [n] qui ne laisse aucune paire en face (n ≥ 2). */
fun ordreEmplacements(n: Int, rnd: Random = Random): List<Int> {
    if (n < 2) return List(n) { it }
    while (true) {
        val o = List(n) { it }.shuffled(rnd)
        if (o.indices.none { o[it] == it }) return o
    }
}

/** Fenêtre des jeux « Relie » : pictogramme translucide facultatif, sur-titre, question en petit corps. */
@Composable
internal fun FenetreRelie(m: Manche, picto: String? = null) {
    val pri = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        if (picto != null) PixelIcon(picto, pri.copy(alpha = 0.16f), Modifier.padding(12.dp).size(112.dp))
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            if (m.kicker.isNotBlank()) Kicker(frTypo(m.kicker))
            Text(
                frTypo(m.q), Modifier.semantics { heading() },
                fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * À qui ça appartient ? Tuiles média en relief à glisser dans l'emplacement en creux de leur propriétaire
 * (ou appui sur la tuile puis sur l'emplacement). Bonne paire : la tuile se loge ; mauvaise : elle revient.
 */
class LesLiens : PolJeu<LiensD>("les-liens", LiensD.serializer()) {

    // Trois rangées au plus pour tenir dans la manette ; propriétaires distincts, sinon une bonne paire serait refusée.
    override fun valide(d: LiensD, params: JsonObject) = d.paires.size in 2..3 &&
        d.paires.none { it.media.isBlank() || it.proprietaire.isBlank() } &&
        d.paires.distinctBy { it.media }.size == d.paires.size &&
        d.paires.distinctBy { it.proprietaire }.size == d.paires.size

    @Composable
    override fun Fenetre(m: Manche, d: LiensD, st: MancheEtat, c: Coque) = FenetreRelie(m)

    @Composable
    override fun Manette(m: Manche, d: LiensD, st: MancheEtat, c: Coque) {
        val p = d.paires
        // Gardé dans l'état de la manche : survit au défilement de l'écran.
        val e = remember(st) { st.jeu as? Liens ?: Liens(ordreEmplacements(p.size)).also { st.jeu = it } }
        val ordre = e.ordre
        val loges = e.loges
        val gd = rememberGlisserDeposer(c) { tuile, cible ->
            val i = tuile.removePrefix("m").toInt()
            val o = cible.removePrefix("o").toInt()
            if (i != o) {
                c.faux("$i>$o", "${p[i].media} n'appartient pas à ${p[o].proprietaire}.")
                return@rememberGlisserDeposer false
            }
            loges += i
            if (loges.size == p.size) c.juste() else c.annoncer("${p[i].media} logé chez ${p[o].proprietaire}.")
            true
        }
        val fait = { i: Int -> st.solved || i in loges }
        val sv = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Kicker("Média", Modifier.weight(1f).padding(horizontal = 2.dp))
                Kicker("Propriétaire", Modifier.weight(1f).padding(horizontal = 2.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Tuiles au-dessus des emplacements pendant le glisser.
                Column(Modifier.weight(1f).zIndex(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    p.indices.forEach { i ->
                        Box(Modifier.fillMaxWidth().height(TUILE_HAUTEUR)) {
                            if (!fait(i)) Tuile("m$i", gd, p[i].media, enabled = !st.solved) { Ligne(p[i].media) }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ordre.forEach { o ->
                        val plein = fait(o)
                        Emplacement(
                            "o$o", gd,
                            "Emplacement ${p[o].proprietaire}" + if (plein) " : ${p[o].media} logé" else "",
                            plein = if (plein) ({
                                Column {
                                    Ligne(p[o].media)
                                    Text(p[o].proprietaire, color = sv, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.04.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }) else null,
                        ) {
                            Text(p[o].proprietaire, Modifier.padding(horizontal = 6.dp), color = sv, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                                lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Ligne(t: String) = Text(t, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, lineHeight = 16.sp,
    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Ordre mélangé des emplacements et médias logés, pour la manche. */
private class Liens(val ordre: List<Int>) {
    val loges = mutableStateListOf<Int>()
}
