package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.BoutonPixel
import app.kairos.mobile.ui.politique.jeux.kit.Forme
import app.kairos.mobile.ui.politique.jeux.kit.Glyphes
import app.kairos.mobile.ui.politique.jeux.kit.Led
import app.kairos.mobile.ui.politique.jeux.kit.LedEtat
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** `d` des jeux à choix : libellés (chaînes, ou objets portant `nom` / `ville`) et index de la bonne réponse. */
@Serializable
data class ChoixD(val choix: List<JsonElement> = emptyList(), val bonne: Int = -1) {
    val libelles: List<String> get() = choix.map { e ->
        when (e) {
            is JsonPrimitive -> e.contentOrNull.orEmpty()
            is JsonObject -> ((e["nom"] ?: e["ville"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> ""
        }
    }
}

/**
 * Jeu de référence du contrat : une touche physique par choix, voyant au-dessus (rouge écarté, vert juste).
 * Sert aussi de jeu provisoire pour tout jeu à choix pas encore livré.
 */
class Exemple(slug: String, private val citation: Boolean = false) : PolJeu<ChoixD>(slug, ChoixD.serializer()) {

    override fun valide(d: ChoixD, params: JsonObject) = d.choix.size in 2..4 && d.bonne in d.choix.indices && d.libelles.none { it.isBlank() }

    override val glyphe: Boolean get() = citation

    @Composable
    override fun Manette(m: Manche, d: ChoixD, st: MancheEtat, c: Coque) {
        val noms = d.libelles
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            noms.forEachIndexed { i, nom ->
                val cle = i.toString()
                val ecarte = cle in st.out
                val juste = st.solved && i == d.bonne
                Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Led(if (juste) LedEtat.Verte else if (ecarte) LedEtat.Rouge else LedEtat.Eteinte)
                    BoutonPixel(
                        nom + if (juste) " (bonne réponse)" else if (ecarte) " (écarté)" else "",
                        onClick = { if (i == d.bonne) c.juste() else c.faux(cle, "Pas « $nom ».") },
                        glyphe = if (juste) Glyphes.check else Glyphes.zero, couleur = Teintes.bleu, forme = Forme.Rond, mode = app.kairos.mobile.ui.politique.jeux.kit.GlyphMode.Blanc,
                        enabled = !st.solved && !ecarte, maintenu = ecarte || juste, cellule = 2.dp,
                    )
                    Text(
                        nom, Modifier.clearAndSetSemantics { }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
