package app.kairos.mobile.ui.politique.jeux

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import kotlinx.coroutines.flow.first

private val Context.jeuxStore by preferencesDataStore("politique-jeux")
private val PROGRESSION = stringPreferencesKey("progression")

/** Progression des jeux, sur l'appareil ; illisible : repart de zéro. */
class JeuxStore(private val context: Context) {
    suspend fun load(): Progression = context.jeuxStore.data.first()[PROGRESSION]
        ?.let { runCatching { PolJson.decodeFromString(Progression.serializer(), it) }.getOrNull() } ?: Progression()

    suspend fun save(p: Progression) {
        context.jeuxStore.edit { it[PROGRESSION] = PolJson.encodeToString(Progression.serializer(), p) }
    }
}

/** Fin de partie affichée à la place de la carte : [cartes] de la partie jouée. */
data class FinPartie(val fin: Fin, val cartes: List<String>)

/** Jeu jouable : publié, enregistré, avec au moins une manche valide. */
class JeuPret(val slug: String, val jeu: Jeu, val impl: PolJeu<*>, val vivier: List<Manche>) {
    val ids: List<String> = vivier.map { it.id }
    fun manche(id: String?): Manche? = vivier.firstOrNull { it.id == id }
}

/**
 * Jeux de l'espace pour une session : progression (persistée par [save]), manche en cours et fin de partie par jeu,
 * invitation à glisser. Les thèmes et leurs viviers viennent de [data].
 */
@Stable
class JeuxSession(data: PolData, private val save: (Progression) -> Unit) {
    val jeux: Map<String, Jeu> = jeuxOf(data)
    val themes: List<Theme> = themesOf(data, jeux)

    /** Jeux jouables par slug ; un slug publié sans implémentation reste absent (carte « Bientôt »). */
    val prets: Map<String, JeuPret> = jeux.mapNotNull { (slug, j) ->
        val impl = JEUX_REGISTRE[slug] ?: return@mapNotNull null
        val v = j.manches.filter { impl.ok(it, j.params) }
        if (v.isEmpty()) null else slug to JeuPret(slug, j, impl, v)
    }.toMap()

    var prog by mutableStateOf(Progression())
        private set
    var loaded by mutableStateOf(false)
        private set
    var invitation by mutableStateOf(Invitation())
    val etats = mutableStateMapOf<String, MancheEtat>()
    val fins = mutableStateMapOf<String, FinPartie>()

    fun restore(p: Progression) { prog = p; loaded = true }

    private fun set(p: Progression) { prog = p; save(p) }

    fun theme(id: String): Theme? = themes.firstOrNull { it.id == id }
    fun themeOf(slug: String): Theme? = themes.firstOrNull { slug in it.jeux }

    fun viviers(t: Theme): Map<String, List<String>> = t.jeux.mapNotNull { s -> prets[s]?.let { s to it.ids } }.toMap()
    fun avance(t: Theme): Avance = avanceTheme(prog, viviers(t))
    fun avance(slug: String): Avance = prets[slug]?.let { avanceJeu(prog, slug, it.ids) } ?: Avance(0, 0, 0)

    /** Manche courante du jeu (reprise ou nouvelle partie) ; null : fin affichée, ou jeu absent. */
    fun etat(slug: String): MancheEtat? {
        if (!loaded || slug in fins) return null
        val g = prets[slug] ?: return null
        etats[slug]?.takeIf { it.manche.id == prog.parties[slug]?.courante }?.let { return it }
        val partie = reprendre(prog, slug, g.ids)
        if (partie == null) {
            fins[slug] = FinPartie(Fin.Vivier, emptyList())
            return null
        }
        if (prog.parties[slug] != partie) set(prog.avecPartie(slug, partie))
        return MancheEtat(g.manche(partie.courante)!!).also { etats[slug] = it }
    }

    /** Position dans la partie : (n, total). */
    fun position(slug: String): Pair<Int, Int> = prog.parties[slug]?.let { it.pos + 1 to it.cartes.size } ?: (1 to 1)

    /** Bonne réponse : la carte compte ; renvoie le palier franchi. */
    fun juste(slug: String, st: MancheEtat): Palier? {
        if (st.solved) return null
        st.solved = true
        val t = themeOf(slug) ?: return null
        val (p, palier) = resoudre(prog, t.id, viviers(t), slug, st.manche.id, st.err == 0)
        set(p)
        return palier
    }

    fun faux(st: MancheEtat, cle: String) {
        if (st.solved) return
        st.err++
        st.out += cle
        st.react++
    }

    /** Carte suivante, ou fin de partie. */
    fun suivant(slug: String) {
        val g = prets[slug] ?: return
        val (p, fin) = avancer(prog, slug, g.ids)
        val cards = prog.parties[slug]?.cartes.orEmpty()
        set(p)
        etats.remove(slug)
        if (fin != null) fins[slug] = FinPartie(fin, cards)
    }

    /** « Continuer » (nouvelle partie) ou « Recommencer » (nouveau cycle). */
    fun relancer(slug: String) {
        val g = prets[slug] ?: return
        val fin = fins.remove(slug)?.fin
        set(if (fin == Fin.Vivier) recommencer(prog, slug, g.ids) else prog.avecPartie(slug, nouvellePartie(prog, slug, g.ids)))
    }
}
