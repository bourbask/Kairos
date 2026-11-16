package app.kairos.mobile.ui.politique.screens

import app.kairos.mobile.ui.politique.kit.NEUTRAL_LABEL
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.alphabetical
import app.kairos.mobile.ui.politique.model.sourcesOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.text.Collator
import java.util.Locale

/** Logique pure de la gravité des promesses. */

/** [qui] : id d'identité (ou nom) ; [fondement] : `[libellé, url]` ; [src] : `[[libellé, url, ev?], …]`. */
@Serializable
data class Promesse(
    val id: String = "",
    val qui: String = "",
    val theme: String = "",
    val promesse: String = "",
    val gravite: Int = 0,
    val pourquoi: String = "",
    val fondement: JsonElement? = null,
    val src: JsonElement? = null,
)

class Gravite(val n: Int, val label: String, val sens: String)

/** Quatre marches, de la divergence ordinaire à l'atteinte aux droits. */
val GRAVITES = listOf(
    Gravite(1, "Désaccord normal", "Arbitrage de valeurs, chiffrage contesté."),
    Gravite(2, "Promesse creuse", "Non financée, irréaliste, ou déjà tentée sans résultat."),
    Gravite(3, "Contraire au droit", "Exige une révision constitutionnelle, ou la sortie ou la violation d'un traité ou de la CEDH."),
    Gravite(4, "Atteinte aux droits fondamentaux", "Touche aux libertés fondamentales ou à l'État de droit."),
)

fun graviteLabel(n: Int): String = GRAVITES.firstOrNull { it.n == n }?.label ?: NEUTRAL_LABEL

fun fondementOf(p: Promesse): SourceRef? = sourcesOf(p.fondement).firstOrNull()

/** [who] : identité reconnue de chaque promesse ; [ids] : identités citées, ordre alphabétique ; [graves] : marches présentes, de la plus grave. */
class PromState(val list: List<Promesse>, val who: List<String?>, val ids: List<String>, val themes: List<String>, val graves: List<Int>)

/** Promesses publiées (texte requis), de la plus grave ; même marche : par thème ; gravité inconnue : à la fin. */
fun promessesOf(data: PolData): List<Promesse> {
    val fr = Collator.getInstance(Locale.FRENCH)
    return data.decode<Promesse>("promesses", "PROMESSES").filter { it.promesse.isNotBlank() }
        .sortedWith(compareByDescending<Promesse> { it.gravite.takeIf { g -> g in 1..4 } ?: 0 }.thenBy(fr) { it.theme }.thenBy { it.id })
}

fun promWho(data: PolData, p: Promesse): String? = data.byId(p.qui)?.id ?: data.idOf(p.qui)

fun promState(data: PolData): PromState {
    val list = promessesOf(data)
    val who = list.map { promWho(data, it) }
    return PromState(
        list, who,
        who.filterNotNull().distinct().alphabetical { data.byId(it)?.nom.orEmpty() },
        list.map { it.theme.trim() }.filter { it.isNotEmpty() }.distinct().sortedWith(Collator.getInstance(Locale.FRENCH)),
        GRAVITES.map { it.n }.reversed().filter { g -> list.any { it.gravite == g } },
    )
}

/** Filtres vides (0 pour la gravité) : tout. */
data class PromFilter(val qui: String = "", val gravite: Int = 0, val theme: String = "")

/** Rangs (dans [PromState.list]) des promesses retenues. */
fun promView(st: PromState, f: PromFilter): List<Int> = st.list.indices.filter { i ->
    val p = st.list[i]
    (f.qui.isEmpty() || st.who[i] == f.qui) && (f.gravite == 0 || p.gravite == f.gravite) && (f.theme.isEmpty() || p.theme.trim() == f.theme)
}
