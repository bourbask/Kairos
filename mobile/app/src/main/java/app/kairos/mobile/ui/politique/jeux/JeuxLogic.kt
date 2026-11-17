package app.kairos.mobile.ui.politique.jeux

import kotlinx.serialization.Serializable
import kotlin.random.Random

/* Logique pure de la coque des jeux : piste de la fenêtre, invitation à glisser, parties, progression, paliers. */

// ---------- Piste : [question][interstice][réponse][suivant] ----------

/** Interstice blanc → vert entre question et réponse, en dp. */
const val GAP_DP = 25f
/** Au-delà de la réponse, la piste suit cette part du doigt. */
const val RESIST = 0.35f
/** Butée dure du tirage vers « suivant » : part de la largeur de la fenêtre ; seuil = butée. */
const val CAP = 0.25f
/** Changement de panneau : distance, ou lancer plus court mais rapide. */
const val SWITCH_DP = 56f
const val FLING_DP = 20f
const val FLING_DP_PER_S = 500f
/** Délai entre la bonne réponse et le glissement vers la réponse. */
const val AUTO_SLIDE_MS = 650L

/** Panneaux de la piste. */
enum class Page { Question, Reponse, Suivant }

/** Position de repos de la piste pour [p] (px, négatif vers la gauche). */
fun restX(p: Page, width: Float, gap: Float): Float = when (p) {
    Page.Question -> 0f
    Page.Reponse -> -(width + gap)
    Page.Suivant -> -(2 * width + gap)
}

/** Amorti : linéaire jusqu'à 80 % de la butée, puis freinage quadratique qui l'atteint exactement. */
fun damp(raw: Float, cap: Float): Float {
    val k = 0.8f * cap
    if (raw <= k) return raw
    val t = ((raw - k) / (0.25f * cap)).coerceAtMost(1f)
    return k + 0.2f * cap * (1 - (1 - t) * (1 - t))
}

/** Décalage de la piste pendant le geste ([dx] cumulé du doigt) et tirage armé (butée atteinte). */
data class Drag(val offset: Float, val armed: Boolean, /** Part de la butée atteinte, 0 à 1 (tirage vers « suivant »). */ val pull: Float)

fun dragOffset(page: Page, dx: Float, width: Float, gap: Float): Drag {
    val span = width + gap
    return when {
        page == Page.Question -> Drag(if (dx < 0) maxOf(dx, -span) else dx * RESIST, false, 0f)
        dx > 0 -> Drag(minOf(dx, span), false, 0f)
        else -> {
            val cap = CAP * width
            val o = damp(-dx * RESIST, cap)
            Drag(-o, o >= cap - 0.01f, if (cap > 0) o / cap else 0f)
        }
    }
}

/** Destination au relâcher. [v] : vitesse du doigt (px/s) ; [dp] : px par dp. */
fun release(page: Page, dx: Float, v: Float, armed: Boolean, dp: Float): Page {
    val far = SWITCH_DP * dp
    val near = FLING_DP * dp
    val fast = FLING_DP_PER_S * dp
    return when (page) {
        Page.Question -> if (dx < -far || (v < -fast && dx < -near)) Page.Reponse else Page.Question
        else -> when {
            dx > far || (v > fast && dx > near) -> Page.Question
            armed -> Page.Suivant
            else -> Page.Reponse
        }
    }
}

// ---------- Invitation à glisser vers « suivant » ----------

/**
 * Invitation, pour la session : 10 s après l'affichage de la réponse (ou le dernier geste), puis toutes les 3 s,
 * 3 fois au plus ; arrêt définitif dès un tirage d'au moins 25 % de la butée.
 */
data class Invitation(val n: Int = 0, val off: Boolean = false) {
    /** Délai avant la prochaine, null : plus aucune. [encore] : juste après une invitation. */
    fun delay(encore: Boolean): Long? = if (off || n >= MAX) null else if (encore) WAIT else FIRST
    fun played(): Invitation = copy(n = n + 1, off = n + 1 >= MAX)
    fun pulled(fraction: Float): Invitation = if (fraction >= SEEN) copy(off = true) else this

    companion object {
        const val MAX = 3
        const val FIRST = 10_000L
        const val WAIT = 3_000L
        const val DUR = 1_100L
        /** Amplitude : part de la butée révélée. */
        const val AMP = 0.45f
        const val SEEN = 0.25f
    }
}

// ---------- Parties et progression ----------

/** Cartes d'une partie au plus. */
const val PARTIE = 6

/** Clé d'une carte : jeu et id de manche. */
fun carte(slug: String, id: String) = "$slug#$id"

@Serializable
data class Partie(val cartes: List<String> = emptyList(), val pos: Int = 0) {
    val courante: String? get() = cartes.getOrNull(pos)
}

/** Paliers joués par la mascotte. */
enum class Palier(val scene: String) { PremierCoup("premier-coup"), Moitie("moitie"), Complet("complet") }

/**
 * Progression persistée. [faites] et [premier] : clés [carte] ; [paliers] : « thème:moitie », « thème:complet » ;
 * [cycle] : ids joués dans le cycle courant de chaque jeu ; [parties] : partie en cours par jeu.
 */
@Serializable
data class Progression(
    val faites: Set<String> = emptySet(),
    val premier: Set<String> = emptySet(),
    val paliers: Set<String> = emptySet(),
    val cycle: Map<String, List<String>> = emptyMap(),
    val parties: Map<String, Partie> = emptyMap(),
)

/** Avancement d'un jeu ou d'un thème : [n] cartes faites sur [m], dont [f] du premier coup. */
data class Avance(val n: Int, val m: Int, val f: Int) {
    val etoile: Boolean get() = m > 0 && f == m
    operator fun plus(o: Avance) = Avance(n + o.n, m + o.m, f + o.f)
}

fun avanceJeu(p: Progression, slug: String, vivier: List<String>): Avance =
    Avance(vivier.count { carte(slug, it) in p.faites }, vivier.size, vivier.count { carte(slug, it) in p.premier })

/** [viviers] : ids des manches de chaque jeu du thème. */
fun avanceTheme(p: Progression, viviers: Map<String, List<String>>): Avance =
    viviers.entries.fold(Avance(0, 0, 0)) { a, (slug, v) -> a + avanceJeu(p, slug, v) }

/**
 * Nouvelle partie : jusqu'à [PARTIE] cartes hors du cycle courant, jamais vues d'abord, chaque groupe mélangé.
 * Vivier épuisé dans ce cycle : null.
 */
fun nouvellePartie(p: Progression, slug: String, vivier: List<String>, rnd: Random = Random.Default): Partie? {
    val cyc = p.cycle[slug].orEmpty().toSet()
    val avail = vivier.filter { it !in cyc }
    if (avail.isEmpty()) return null
    val (neuves, vues) = avail.partition { carte(slug, it) !in p.faites }
    return Partie((neuves.shuffled(rnd) + vues.shuffled(rnd)).take(PARTIE))
}

/** Partie à reprendre : celle enregistrée si elle est en cours et ses cartes existent toujours, sinon une nouvelle. */
fun reprendre(p: Progression, slug: String, vivier: List<String>, rnd: Random = Random.Default): Partie? =
    p.parties[slug]?.takeIf { it.courante != null && it.cartes.all { c -> c in vivier } } ?: nouvellePartie(p, slug, vivier, rnd)

fun Progression.avecPartie(slug: String, partie: Partie?): Progression =
    copy(parties = if (partie == null) parties - slug else parties + (slug to partie))

/**
 * Carte résolue : comptée une fois (« premier coup » si sa première résolution est sans erreur), ajoutée au cycle.
 * Palier : complet, sinon moitié franchie, chacun une fois par thème ; sinon premier coup ; rien si la carte était déjà faite.
 */
fun resoudre(
    p: Progression, theme: String, viviers: Map<String, List<String>>, slug: String, id: String, sansErreur: Boolean,
): Pair<Progression, Palier?> {
    val key = carte(slug, id)
    val fresh = key !in p.faites
    val before = avanceTheme(p, viviers)
    val cyc = p.cycle[slug].orEmpty().let { if (id in it) it else it + id }
    val q = p.copy(
        faites = p.faites + key,
        premier = if (fresh && sansErreur) p.premier + key else p.premier,
        cycle = p.cycle + (slug to cyc),
    )
    if (!fresh) return q to null
    val after = avanceTheme(q, viviers)
    val full = "$theme:complet"
    val half = "$theme:moitie"
    return when {
        after.m > 0 && after.n == after.m && full !in q.paliers -> q.copy(paliers = q.paliers + full) to Palier.Complet
        after.m > 0 && before.n * 2 < after.m && after.n * 2 >= after.m && half !in q.paliers -> q.copy(paliers = q.paliers + half) to Palier.Moitie
        sansErreur -> q to Palier.PremierCoup
        else -> q to null
    }
}

/** Fin de partie : vivier épuisé dans le cycle (« Recommencer »), ou simple partie finie (« Continuer »). */
enum class Fin { Partie, Vivier }

/** Carte suivante de la partie ; fin de partie : [Fin], la partie est retirée. */
fun avancer(p: Progression, slug: String, vivier: List<String>): Pair<Progression, Fin?> {
    val partie = p.parties[slug] ?: return p to Fin.Partie
    val next = partie.copy(pos = partie.pos + 1)
    if (next.courante != null) return p.avecPartie(slug, next) to null
    val done = p.cycle[slug].orEmpty().toSet()
    return p.avecPartie(slug, null) to if (vivier.all { it in done }) Fin.Vivier else Fin.Partie
}

/** « Recommencer » : nouveau cycle (la progression reste acquise), puis nouvelle partie. */
fun recommencer(p: Progression, slug: String, vivier: List<String>, rnd: Random = Random.Default): Progression {
    val q = p.copy(cycle = p.cycle - slug)
    return q.avecPartie(slug, nouvellePartie(q, slug, vivier, rnd))
}

/** En-tête : « 3/24 cartes · ★ 2 du premier coup » (étoile dessinée à part) ; cases pleines de la barre de 12. */
fun progressCells(a: Avance, cells: Int = 12): Int = if (a.m == 0) 0 else Math.round(a.n.toFloat() / a.m * cells)

fun progressLabel(a: Avance): String = "${a.n} cartes sur ${a.m}" + if (a.f > 0) ", dont ${a.f} trouvées du premier coup" else ""

/**
 * Annonce à poser dans la région vivante : un message identique au précédent n'y changerait rien et ne serait pas relu ;
 * il alterne donc avec une variante suivie d'un espace sans chasse, invisible et muet.
 */
fun annonceNeuve(precedente: String, msg: String): String = if (precedente == msg) msg + "\u200B" else msg
