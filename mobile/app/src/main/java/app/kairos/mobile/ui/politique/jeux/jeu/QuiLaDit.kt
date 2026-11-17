package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.data.PolIdentity
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.PixelShape
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.frTypo
import kotlinx.serialization.Serializable

/** Une personnalité proposée : `id` d'identité (avatar), nom, parti (facultatif). */
@Serializable
data class Personnalite(val id: String = "", val nom: String = "", val parti: String? = null)

/** `d` de « qui-l-a-dit » : quatre personnalités, index de l'auteur. */
@Serializable
data class PortraitsD(val choix: List<Personnalite> = emptyList(), val bonne: Int = -1)

/** Quatre personnages en boutons physiques pixel, avec leur avatar ; on désigne l'auteur de la phrase. */
class QuiLaDit : PolJeu<PortraitsD>("qui-l-a-dit", PortraitsD.serializer()) {

    override fun valide(d: PortraitsD) = d.choix.size == 4 && d.bonne in d.choix.indices && d.choix.none { it.nom.isBlank() }

    override val glyphe = true

    @Composable
    override fun Manette(m: Manche, d: PortraitsD, st: MancheEtat, c: Coque) {
        val appui = rememberAppui(m.id, c, 0) { i -> if (i == d.bonne) c.juste() else c.faux(i.toString(), "Pas ${d.choix[i].nom}.") }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.choix.indices.chunked(2).forEach { ligne ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ligne.forEach { i ->
                        Portrait(d.choix[i], i.toString(), juste = st.solved && i == d.bonne, st, c, Modifier.weight(1f)) { appui.jouer(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Portrait(p: Personnalite, cle: String, juste: Boolean, st: MancheEtat, c: Coque, modifier: Modifier, onClick: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    val ecarte = cle in st.out
    val actif = !st.solved && !ecarte
    val src = remember { MutableInteractionSource() }
    val presse by src.collectIsPressedAsState()
    val tremble = rememberTremblement(st, cle, c.reduit)
    val saut = rememberSaut(juste, c.reduit)
    val kc = when {
        juste -> sc.primaryContainer
        ecarte -> mix(sc.outlineVariant, sc.onSurfaceVariant, 0.18f)
        else -> sc.tertiaryContainer
    }
    val flanc = if (juste) mix(sc.primary, Color.Black, 0.25f) else mix(kc, sc.onSurface, 0.3f)
    val encre = when {
        juste -> sc.onPrimaryContainer
        ecarte -> sc.onSurfaceVariant
        else -> sc.onSurface
    }
    val clair = if (juste) mix(sc.primaryContainer, Color.White, 0.55f) else mix(kc, Color.White, 0.6f)
    val sombre = if (juste) mix(sc.primary, sc.primaryContainer, 0.4f) else mix(kc, sc.onSurface, 0.14f)
    val bas = if ((presse && actif) || ecarte) 6.dp else 0.dp
    val forme = PixelShape()
    val identite = c.data.byId(p.id) ?: PolIdentity(p.id, p.nom, p.parti)
    val etat = if (juste) " (bonne réponse)" else if (ecarte) " (écarté)" else ""
    Box(
        modifier.height(73.dp)
            .clickable(src, indication = null, enabled = actif, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = p.nom + (p.parti?.let { ", $it" } ?: "") + etat },
    ) {
        // Ombre dure, flanc, puis dessus qui s'enfonce.
        if (!ecarte) Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(64.dp).clip(forme).background(Teintes.ombre))
        Box(Modifier.fillMaxWidth().padding(top = 9.dp).height(64.dp).clip(forme).background(flanc))
        Row(
            Modifier.offset(y = bas).fillMaxWidth().height(64.dp).clip(forme).background(kc)
                .drawBehind {
                    val e = ent(3.dp)
                    biseau(clair, sombre, e)
                    if (juste) cadre(sc.primary, e)
                    else if (!ecarte) aplat(Color.White.copy(alpha = 0.75f), 3 * e, 2 * e, 3 * e, e)
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.graphicsLayer { translationX = tremble.value.dp.toPx(); translationY = saut.value.dp.toPx() }) {
                IdentityBadge(identite, 40.dp, Modifier.alpha(if (ecarte) 0.6f else 1f))
                if (juste || ecarte) Box(
                    Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(20.dp).clip(PixelShape(steps = 1))
                        .background(if (juste) sc.primary else sc.error).clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) { Text(if (juste) "✓" else "✗", color = sc.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
            }
            Column(Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(frTypo(p.nom), color = encre, fontSize = 13.5.sp, lineHeight = 15.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                p.parti?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        frTypo(it), color = if (juste) encre.copy(alpha = 0.8f) else sc.onSurfaceVariant, fontSize = 12.sp, lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
