package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.Banderole
import app.kairos.mobile.ui.politique.jeux.kit.Forme
import app.kairos.mobile.ui.politique.jeux.kit.GlyphMode
import app.kairos.mobile.ui.politique.jeux.kit.Glyphes
import app.kairos.mobile.ui.politique.jeux.kit.Led
import app.kairos.mobile.ui.politique.jeux.kit.LedEtat
import app.kairos.mobile.ui.politique.jeux.kit.PixelShape
import app.kairos.mobile.ui.politique.jeux.kit.Px
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import app.kairos.mobile.ui.politique.jeux.kit.cell
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.jeux.kit.pxButton
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class VoteD(
    val orateur: String = "", val parti: String = "", val chambre: String = "", val date: String = "",
    val scrutin: String = "", val objet: String? = null, val bonne: String = "",
)

/** Les trois votes possibles, ordre par défaut des touches. */
val VOTES = listOf("contre", "abstention", "pour")

/** Touches dans l'ordre de `params.choix` ; absent ou incomplet : [VOTES]. */
fun touchesVote(params: JsonObject): List<String> =
    (params["choix"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.sorted() == VOTES.sorted() } ?: VOTES

/** Texte de la banderole : intitulé, puis résumé s'il existe. */
fun banderoleVote(d: VoteD): String = d.scrutin + (d.objet?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

private val MOIS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

/** `AAAA-MM-JJ` en toutes lettres (« 27 novembre 2025 ») ; illisible : tel quel. */
fun dateLongue(iso: String): String {
    val p = iso.trim().split("-")
    val y = p.getOrNull(0)?.toIntOrNull() ?: return iso
    val m = p.getOrNull(1)?.toIntOrNull()?.let { MOIS.getOrNull(it - 1) }.orEmpty()
    val j = p.getOrNull(2)?.toIntOrNull()?.toString().orEmpty()
    return listOf(j, m, "$y").filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * Vote en cohérence : ce qu'un élu a dit est dans la fenêtre ; on appuie sur la touche du vote de son groupe
 * (contre rond rouge, abstention large neutre, pour carré bleu), voyant au-dessus. Touches à retour automatique.
 */
class LeVote : PolJeu<VoteD>("le-vote", VoteD.serializer()) {

    // ponytail: la fenêtre ne reçoit pas les données de l'espace (avatar, nom du groupe) ; la manette les dépose ici.
    // À retirer si `Fenetre` reçoit un jour la coque.
    private var donnees by mutableStateOf<PolData?>(null)

    override fun valide(d: VoteD) = d.bonne in VOTES && d.orateur.isNotBlank() && d.scrutin.isNotBlank()

    @Composable
    override fun Fenetre(m: Manche, d: VoteD, st: MancheEtat) {
        val sc = MaterialTheme.colorScheme
        val id = donnees?.byId(d.parti)
        val reduit = reducedMotion()
        val saut = remember(m.id) { Animatable(0f) }
        LaunchedEffect(st.solved) {
            if (st.solved && !reduit) saut.animateTo(0f, keyframes { durationMillis = 550; -8f at 165; 0f at 550 })
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            if (m.kicker.isNotBlank()) Kicker(m.kicker)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (id != null) Box(Modifier.offset { IntOffset(0, saut.value.dp.roundToPx()) }) {
                    IdentityBadge(id, 64.dp)
                    if (st.solved) Box(
                        Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(20.dp).clip(PixelShape(3.dp, 1)).background(sc.primary),
                        contentAlignment = Alignment.Center,
                    ) { Text("✓", color = sc.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
                }
                Column {
                    Text(frTypo(d.orateur), color = sc.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    val ou = listOfNotNull(id?.nom, listOf(d.chambre, dateLongue(d.date)).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() })
                    Text(frTypo(ou.joinToString(" · ")), color = sc.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp)
                }
            }
            Text(
                frTypo(m.q), Modifier.semantics { heading() },
                fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em, color = sc.onSurface,
            )
        }
    }

    @Composable
    override fun Manette(m: Manche, d: VoteD, st: MancheEtat, c: Coque) {
        val data = c.data
        SideEffect { donnees = data }
        val sc = MaterialTheme.colorScheme
        val forme = PixelShape(4.dp, 3)
        Column(
            Modifier.fillMaxWidth().background(mix(sc.surface, sc.onSurface, 0.20f), forme).padding(bottom = 2.dp)
                .background(mix(sc.surface, sc.onSurface, 0.09f), forme).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)
                .semantics(mergeDescendants = false) { contentDescription = "Vote de son groupe" },
        ) {
            Banderole(banderoleVote(d))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                touchesVote(c.params).forEach { v ->
                    val nom = v.replaceFirstChar { it.uppercase() }
                    val ecarte = v in st.out
                    val juste = st.solved && v == d.bonne
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Led(if (juste) LedEtat.Verte else if (ecarte) LedEtat.Rouge else LedEtat.Eteinte)
                        Box(Modifier.height(81.dp), contentAlignment = Alignment.BottomCenter) {
                            Touche(
                                nom + if (juste) " (bonne réponse)" else if (ecarte) " (écarté)" else "",
                                v, actif = !st.solved && !ecarte,
                            ) { if (v == d.bonne) c.juste() else c.faux(v, "Pas « $nom ».") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Touche de vote : bouton physique du kit qui garde sa teinte une fois verrouillée (écartée ou manche résolue) ;
 * verrouillée, elle ne s'enfonce plus. Retour automatique après l'appui.
 */
@Composable
private fun Touche(label: String, vote: String, actif: Boolean, onClick: () -> Unit) {
    val (forme, glyphe, kc) = when (vote) {
        "contre" -> Triple(Forme.Rond, Glyphes.minus, Teintes.rouge)
        "pour" -> Triple(Forme.Carre, Glyphes.plus, Teintes.bleu)
        else -> Triple(Forme.Large, Glyphes.zero, Teintes.neutre)
    }
    val b = remember(forme, glyphe) { pxButton(forme.w, forme.h, forme.r, glyphe, GlyphMode.Blanc) }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s = cellPx(3.dp)
    val sc = MaterialTheme.colorScheme
    val k = Color.Black
    val w = Color.White
    val col = mapOf(
        Px.Pl to mix(sc.surface, sc.onSurface, 0.11f), Px.Pld to mix(sc.surface, sc.onSurface, 0.24f),
        Px.Fl to mix(kc, k, 0.42f), Px.Fld to mix(kc, k, 0.58f),
        Px.T to kc, Px.Tl to mix(kc, w, 0.26f), Px.Td to mix(kc, k, 0.2f),
        Px.Ic to mix(kc, k, 0.5f), Px.Icl to mix(kc, w, 0.38f), Px.Iw to w, Px.Rf to w.copy(alpha = 0.85f),
    )
    val down = if (pressed && actif) 2 else 0
    val size = with(LocalDensity.current) { (b.w * s).toDp() to (b.h * s).toDp() }
    Box(
        Modifier.minimumInteractiveComponentSize()
            .clickable(src, indication = null, enabled = actif, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; if (!actif) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size.first, size.second).clearAndSetSemantics { }) {
            b.base.forEach { cell(it.x, it.y, col.getValue(it.k), s) }
            b.top.forEach { cell(it.x, it.y, col.getValue(it.k), s, dy = down) }
        }
    }
}
