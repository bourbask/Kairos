package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.PixelShape
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.frTypo
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** `d` de « trente-secondes » : trois origines possibles, index de la bonne. */
@Serializable
data class FichesD(val choix: List<String> = emptyList(), val bonne: Int = -1)

/** Haut de chaque fiche dans le tiroir (dp) : en gradins, la plus au fond la plus haute ; 48 dp de prise chacune. */
val FICHES_HAUT = intArrayOf(12, 60, 108)

/** Retrait latéral de chaque fiche (dp) : la plus au fond la plus étroite. */
val FICHES_RETRAIT = intArrayOf(15, 9, 3)

/** Position de l'onglet de chaque fiche, en part de la largeur. */
val FICHES_ONGLET = floatArrayOf(0.10f, 0.44f, 0.76f)

/** Haut de la façade du tiroir et hauteur totale (dp). */
private const val FACADE = 147
private const val TIROIR = 187

/** Rectangle à coins en marches en haut seulement (onglet, fond du tiroir). */
private class HautEnMarches(private val step: androidx.compose.ui.unit.Dp = 3.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { step.toPx().roundToInt().coerceAtLeast(1).toFloat() }
        return Outline.Generic(Path().apply {
            moveTo(0f, s); lineTo(s, s); lineTo(s, 0f); lineTo(size.width - s, 0f); lineTo(size.width - s, s); lineTo(size.width, s)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        })
    }
}

/** Tiroir de classeur à fiches d'archive ; on tire la fiche de l'origine de l'idée. */
class TrenteSecondes : PolJeu<FichesD>("trente-secondes", FichesD.serializer()) {

    override fun valide(d: FichesD) = d.choix.size == 3 && d.bonne in d.choix.indices && d.choix.none { it.isBlank() }

    override val glyphe = true

    @Composable
    override fun Manette(m: Manche, d: FichesD, st: MancheEtat, c: Coque) {
        val sc = MaterialTheme.colorScheme
        val att = sc.tertiary
        val bois = mix(att, Color.Black, 0.38f)
        val laiton = mix(att, Color.White, 0.18f)
        val papier = mix(sc.surface, att, 0.09f)
        val trait = mix(bois, Color.Black, 0.62f)
        val appui = rememberAppui(m.id, c, 180) { i -> if (i == d.bonne) c.juste() else c.faux(i.toString(), "Pas « ${d.choix[i]} ».") }
        Box(Modifier.fillMaxWidth().height(TIROIR.dp)) {
            // Fond du tiroir.
            Box(
                Modifier.padding(top = 34.dp).fillMaxWidth().height(113.dp).clip(HautEnMarches()).background(mix(bois, Color.Black, 0.35f))
                    .drawBehind {
                        val e = ent(3.dp)
                        val c9 = ent(9.dp)
                        val flanc = mix(bois, Color.Black, 0.1f)
                        aplat(flanc, 0f, 0f, c9, size.height); aplat(flanc, size.width - c9, 0f, c9, size.height)
                        cadre(trait, e)
                    }.clearAndSetSemantics { },
            )
            d.choix.forEachIndexed { i, o ->
                Fiche(o, i, juste = st.solved && i == d.bonne, sortie = appui.piece == i, st, c, papier, trait) { appui.jouer(i) }
            }
            // Façade : porte-étiquette et poignée.
            Box(
                Modifier.zIndex(5f).padding(top = FACADE.dp).fillMaxWidth().height((TIROIR - FACADE).dp).clip(PixelShape(steps = 1)).background(bois)
                    .drawBehind {
                        val e = ent(3.dp)
                        biseau(mix(bois, Color.White, 0.22f), mix(bois, Color.Black, 0.35f), 2 * e)
                        cadre(trait, e)
                    }.clearAndSetSemantics { },
            ) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(84.dp, 15.dp).background(papier).drawBehind {
                        val e = ent(3.dp)
                        cadre(laiton, 2 * e)
                        cadre(trait, e)
                        aplat(mix(papier, sc.onSurface, 0.25f), ent(15.dp), 2 * e, size.width - 2 * ent(15.dp), ent(2.dp))
                    },
                )
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 25.dp).size(54.dp, 9.dp).drawBehind {
                        val e = ent(3.dp)
                        aplat(laiton, 0f, 0f, size.width, 2 * e)
                        aplat(laiton, e, 2 * e, size.width - 2 * e, size.height - 2 * e)
                        aplat(trait, e, size.height - e, size.width - 2 * e, e)
                    },
                )
            }
        }
    }
}

@Composable
private fun Fiche(o: String, i: Int, juste: Boolean, sortie: Boolean, st: MancheEtat, c: Coque, papier: Color, trait: Color, onClick: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    val cle = i.toString()
    val ecarte = cle in st.out
    val actif = !st.solved && !ecarte
    val src = remember { MutableInteractionSource() }
    val presse by src.collectIsPressedAsState()
    val tremble = rememberTremblement(st, cle, c.reduit)
    val pap = when { juste -> sc.primaryContainer; ecarte -> mix(sc.surface, sc.onSurfaceVariant, 0.22f); else -> papier }
    val onglet = when { juste -> sc.primary; ecarte -> sc.error; else -> mix(sc.tertiary, sc.surface, 0.25f) }
    val encre = when { juste -> sc.onPrimaryContainer; ecarte -> sc.onSurfaceVariant; else -> sc.onSurface }
    val rouge = Teintes.rouge
    val haut = when { juste || sortie -> (-6).dp; presse && actif -> (-3).dp; else -> 0.dp }
    val etat = if (juste) " (bonne réponse)" else if (ecarte) " (écarté)" else ""
    BoxWithConstraints(
        Modifier.zIndex(1f + i).padding(top = FICHES_HAUT[i].dp - 9.dp, start = FICHES_RETRAIT[i].dp, end = FICHES_RETRAIT[i].dp)
            .fillMaxWidth().height(85.dp)
            .offset(y = haut).graphicsLayer { translationX = tremble.value.dp.toPx() }
            .clickable(src, indication = null, enabled = actif && !sortie, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = o + etat },
    ) {
        // Ombre portée en marches sur la fiche de derrière.
        if (i > 0) Box(
            Modifier.padding(top = 3.dp, start = 3.dp, end = 3.dp).fillMaxWidth().height(6.dp).drawBehind {
                val e = ent(3.dp)
                aplat(Color.Black.copy(alpha = 0.18f), 0f, 0f, size.width, e)
                aplat(Color.Black.copy(alpha = 0.38f), 0f, e, size.width, e)
            },
        )
        // Papier.
        Box(
            Modifier.padding(top = 9.dp).fillMaxWidth().height(76.dp).clip(PixelShape(steps = 1)).background(pap).drawBehind {
                val e = ent(3.dp)
                biseau(mix(pap, Color.White, 0.55f), mix(pap, Color.Black, 0.14f), 2 * e)
                cadre(trait, e)
                aplat(mix(rouge, pap, 0.35f), e, ent(36.dp), size.width - 2 * e, e)
            },
        ) {
            Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    frTypo(o), Modifier.clearAndSetSemantics { }, color = encre, fontSize = 13.sp, lineHeight = 14.5.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Onglet.
        Box(
            Modifier.offset(x = maxWidth * FICHES_ONGLET[i]).size(42.dp, 12.dp).clip(HautEnMarches()).background(onglet).drawBehind {
                val e = ent(3.dp)
                aplat(trait, 0f, 0f, size.width, e)
                aplat(trait, 0f, 0f, e, size.height)
                aplat(trait, size.width - e, 0f, e, size.height)
            },
        )
    }
}
