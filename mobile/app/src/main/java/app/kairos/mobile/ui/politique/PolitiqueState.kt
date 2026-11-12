package app.kairos.mobile.ui.politique

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.PolCompare
import app.kairos.mobile.data.PolFiche
import app.kairos.mobile.data.PolGroup
import app.kairos.mobile.data.PolHint
import app.kairos.mobile.data.PolHome
import app.kairos.mobile.data.PolIdentity
import app.kairos.mobile.data.PolRubric
import app.kairos.mobile.data.PolRubricSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Module de la tête et identifiant de l'espace (scène du personnage comprise). */
const val POLITIQUE = "politique"

/** Valeur absente d'une comparaison : jamais zéro. */
const val NOT_GIVEN = "non renseigné"

/** Bornes de la recherche : longueur de la requête, groupes et entrées affichés. */
const val SEARCH_MIN = 2
const val SEARCH_MAX = 200
const val SEARCH_GROUPS = 12
const val SEARCH_PER_GROUP = 20

/** Bornes de l'affichage générique : entrées par liste, lignes et colonnes d'un tableau. */
const val MAX_ENTRIES = 150
const val MAX_ROWS = 30
const val MAX_COLS = 5

/** Texte sans accents ni casse, espaces resserrés : comparaison des clés et libellés. */
fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim().replace(Regex("\\s+"), " ")

// --- Accueil ---------------------------------------------------------------------------

sealed interface HomeLoad {
    data object Loading : HomeLoad
    data class Ready(val home: PolHome) : HomeLoad
    data class Down(val reason: String) : HomeLoad
}

/** Actions de l'accueil ; le site et le partage restent ouverts quel que soit l'état du module. */
enum class HomeAction { Comparer, Verifier, OuvrirSite, Partager, Acces }

fun homeActions(load: HomeLoad): List<HomeAction> {
    val home = (load as? HomeLoad.Ready)?.home
    return listOfNotNull(
        HomeAction.Comparer.takeIf { home != null && home.identites.size >= 2 && home.themes.isNotEmpty() },
        HomeAction.Verifier.takeIf { home != null },
        HomeAction.OuvrirSite,
        HomeAction.Partager,
        HomeAction.Acces,
    )
}

/** Raison lisible d'un échec du module ; [ApiError.Unauthorized] remonte. */
fun politiqueIssue(e: ApiError): String = when (e) {
    ApiError.Unauthorized -> throw e
    ApiError.Offline -> "Hors ligne : serveur injoignable."
    ApiError.NoReply -> "Pas de réponse du serveur."
    is ApiError.Server -> when {
        e.outdated -> "${ApiError.Server.OUTDATED}."
        e.code == 404 -> "Module Politique absent du serveur."
        e.code == 422 -> e.message ?: "Refusé."
        e.unreadable -> "${ApiError.Server.UNREADABLE} : serveur ou application à mettre à jour."
        else -> "${e.unavailable()}."
    }
}

private val CHECKED: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

/** « Données vérifiées le 9 novembre 2026 » ; date illisible : telle quelle ; absente : null. */
fun checkedLabel(raw: String?): String? {
    val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val day = runCatching { LocalDate.parse(v.take(10)).format(CHECKED) }.getOrDefault(v)
    return "Données vérifiées le $day"
}

/** Nature d'une rubrique, pour les vues dédiées. */
enum class RubricKind { Generic, Declarations, Votes, Revirements, DitVote, Rhetorique, Bulle }

/** Vue demandée par une indication de présentation ; inconnue : générique. */
fun hintKind(vue: String): RubricKind = when (vue) {
    "verifications" -> RubricKind.Declarations
    "rhetorique" -> RubricKind.Rhetorique
    "revirements" -> RubricKind.Revirements
    "votes" -> RubricKind.Votes
    "bulle" -> RubricKind.Bulle
    else -> RubricKind.Generic
}

/**
 * Indication d'une liste de rubrique : la vue de lecture d'abord, « bulle » seulement si elle
 * est la seule (une même liste peut se lire des deux façons).
 */
fun hintFor(hints: List<PolHint>?, rubrique: String, chemin: String): PolHint? {
    val mine = hints.orEmpty().filter { it.rubrique == rubrique && it.chemin == chemin }
    return mine.firstOrNull { it.vue != "bulle" } ?: mine.firstOrNull()
}

/** Nature d'une rubrique : ses indications d'abord, sinon devinée à son identifiant ou son titre. */
fun rubricKind(id: String, titre: String = "", hints: List<PolHint> = emptyList()): RubricKind {
    val mine = hints.filter { it.rubrique == id }
    if (mine.isNotEmpty()) return hintKind((mine.firstOrNull { it.vue != "bulle" } ?: mine.first()).vue)
    val k = fold("$id $titre")
    return when {
        "bulle" in k -> RubricKind.Bulle
        "rhetori" in k -> RubricKind.Rhetorique
        "dit" in k && "vot" in k -> RubricKind.DitVote
        "declar" in k || "verif" in k -> RubricKind.Declarations
        "revire" in k -> RubricKind.Revirements
        "vote" in k -> RubricKind.Votes
        else -> RubricKind.Generic
    }
}

/**
 * Cartes des rubriques : Dit / voté en tête (vue composée, dès qu'une identité existe), les
 * rubriques dans l'ordre du module, Hors de ma bulle en dernier (carte large). Une rubrique
 * « dit / voté » publiée telle quelle est couverte par la vue composée.
 */
data class RubricCard(val id: String?, val titre: String, val resume: String, val kind: RubricKind, val wide: Boolean = false)

fun homeCards(home: PolHome): List<RubricCard> {
    val hints = home.presentations
    val cards = home.rubriques.flatMap { r ->
        val kind = rubricKind(r.id, r.titre, hints)
        val own = RubricCard(r.id, r.titre.ifBlank { r.id }, cardSummary(r), kind, wide = kind == RubricKind.Bulle)
        // Une liste lue aussi « hors de ma bulle » : carte large en plus de la rubrique.
        val bulle = RubricCard(r.id, "Hors de ma bulle", "Les affirmations par thème, puis par camp", RubricKind.Bulle, wide = true)
            .takeIf { kind != RubricKind.Bulle && hints.any { it.rubrique == r.id && it.vue == "bulle" } }
        listOfNotNull(own, bulle)
    }
    val ditVote = RubricCard(null, "Dit / voté", "Propos face aux votes", RubricKind.DitVote)
        .takeIf { home.identites.isNotEmpty() }
    return listOfNotNull(ditVote) + cards.filter { it.kind != RubricKind.DitVote && !it.wide } + cards.filter { it.wide }
}

private fun cardSummary(r: PolRubricSummary): String =
    r.resume?.takeIf { it.isNotBlank() } ?: if (r.entrees == 1) "1 entrée" else "${r.entrees} entrées"

// --- Comparer --------------------------------------------------------------------------

/** Sélection d'identités : un appui ajoute ou retire ; trois au plus. */
fun toggleCompare(selected: List<String>, id: String): List<String> = when {
    id in selected -> selected - id
    selected.size >= 3 -> selected
    else -> selected + id
}

fun compareReady(selected: List<String>, theme: String?): Boolean = selected.size in 2..3 && theme != null

data class Cell(val text: String, val source: String?, val url: String?) {
    val given: Boolean get() = text != NOT_GIVEN
}

data class CompareRow(val label: String, val cells: List<Cell>)

data class CompareTable(val theme: String, val columns: List<PolIdentity>, val rows: List<CompareRow>)

/**
 * Tableau dans l'ordre de [ids] ; une identité sans valeur, ou une valeur nulle ou vide,
 * s'affiche « non renseigné ». Noms pris dans la réponse, à défaut dans [known].
 */
fun compareTable(c: PolCompare, ids: List<String>, known: List<PolIdentity>): CompareTable {
    val named = c.identites.mapNotNull { e ->
        when (e) {
            is JsonObject -> (e["id"] as? JsonPrimitive)?.content?.let { id -> id to (e["nom"] as? JsonPrimitive)?.content }
            is JsonPrimitive -> e.content to null
            else -> null
        }
    }.toMap()
    val columns = ids.map { id ->
        val base = known.firstOrNull { it.id == id } ?: PolIdentity(id, id)
        named[id]?.let { base.copy(nom = it) } ?: base
    }
    val theme = when (val t = c.theme) {
        is JsonObject -> (t["titre"] as? JsonPrimitive)?.content ?: (t["id"] as? JsonPrimitive)?.content.orEmpty()
        is JsonPrimitive -> t.content
        else -> ""
    }
    val rows = c.lignes.map { line ->
        CompareRow(
            listOfNotNull(line.libelle, line.unite?.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" "),
            ids.map { id ->
                val v = line.valeurs.firstOrNull { it.id == id }
                val text = v?.valeur?.let(::scalar)?.takeIf { it.isNotBlank() }
                    ?.let { listOfNotNull(it, line.unite?.takeIf { u -> u.isNotBlank() }).joinToString(" ") }
                Cell(text ?: NOT_GIVEN, v?.source?.let(::sourceText), v?.source?.let(::sourceUrl))
            },
        )
    }
    return CompareTable(theme, columns, rows)
}

// --- Recherche -------------------------------------------------------------------------

/** Requête envoyée : nettoyée, [SEARCH_MAX] caractères au plus ; trop courte : rien. */
fun searchTerm(q: String): String? = q.trim().replace(Regex("\\s+"), " ").take(SEARCH_MAX).takeIf { it.length >= SEARCH_MIN }

/** Résultats bornés ; les déclarations vérifiées d'abord, le reste dans l'ordre du module. */
fun boundedResults(groups: List<PolGroup>, hints: List<PolHint> = emptyList()): List<PolGroup> =
    groups.filter { it.entrees.isNotEmpty() }
        .sortedBy { if (rubricKind(it.key, it.titre, hints) == RubricKind.Declarations) 0 else 1 }
        .take(SEARCH_GROUPS)
        .map { it.copy(entrees = it.entrees.take(SEARCH_PER_GROUP)) }

/**
 * Recherche à la frappe : une saisie attend [delayMs] sans nouvelle frappe avant de partir ;
 * la dernière saisie gagne, une réponse ancienne n'est jamais affichée.
 */
class DebouncedSearch<T>(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> T,
    private val onUnauthorized: () -> Unit,
    private val delayMs: Long = 400,
) {
    var results by mutableStateOf<T?>(null)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun type(q: String) {
        job?.cancel()
        searching = false
        failed = null
        val term = searchTerm(q)
        if (term == null) {
            results = null
            return
        }
        job = scope.launch {
            delay(delayMs)
            searching = true
            try {
                val r = search(term)
                ensureActive()
                results = r
            } catch (_: ApiError.Unauthorized) {
                onUnauthorized()
            } catch (e: ApiError) {
                ensureActive()
                failed = politiqueIssue(e)
            } finally {
                // Abandonnée : l'indicateur appartient déjà à la saisie suivante.
                if (isActive) searching = false
            }
        }
    }
}

// --- Affichage générique des entrées ---------------------------------------------------

private val TITLE_KEYS = listOf("titre", "nom", "procede", "intitule", "libelle", "candidat", "parti", "theme", "affirmation")
private val SOURCE_KEYS = setOf("source", "sources", "lien", "liens", "url")

/** Libellé d'une clé : « niveau_de_vie » → « Niveau de vie ». */
fun humanize(key: String): String = key.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

private val BREAK = Regex("<(br|/?p|/?div|/?li)\\b[^>]*>", RegexOption.IGNORE_CASE)
private val TAG = Regex("<[a-zA-Z/!][^>]*(>|$)")
private val ENTITIES = mapOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&amp;" to "&")

/**
 * Texte des données sans balises : le HTML présent dans une valeur n'est jamais interprété
 * (ni lien ni mise en forme), seul son texte reste ; entités courantes décodées après coup.
 */
fun stripTags(s: String): String {
    var t = s.replace(BREAK, " ").replace(TAG, "")
    for ((k, v) in ENTITIES) t = t.replace(k, v)
    return t.replace(Regex("[ \\t]+"), " ").trim()
}

/** Valeur simple en texte ; liste de valeurs simples jointe ; autre forme : null. */
fun scalar(e: JsonElement): String? = when (e) {
    JsonNull -> null
    is JsonPrimitive -> if (e.isString) stripTags(e.content) else e.content
    is JsonArray -> e.takeIf { a -> a.all { it is JsonPrimitive } }?.mapNotNull(::scalar)?.joinToString(" · ")
    is JsonObject -> null
}

/** Valeur en texte, à toute profondeur : listes jointes, paires et lignes séparées. */
fun plain(e: JsonElement): String? = when (e) {
    JsonNull -> null
    is JsonPrimitive -> scalar(e)?.takeIf { it.isNotBlank() }
    is JsonArray -> e.mapNotNull(::plain).joinToString(if (e.all { it is JsonPrimitive }) " · " else " ; ").ifEmpty { null }
    is JsonObject -> e.entries.mapNotNull { (k, v) -> plain(v)?.let { "${humanize(k)} : $it" } }.joinToString(" · ").ifEmpty { null }
}

/** Texte d'une source : chaîne, objet (titre, nom, url), paire [titre, adresse] ou liste. */
fun sourceText(e: JsonElement): String? = when (e) {
    JsonNull -> null
    is JsonPrimitive -> scalar(e)?.takeIf { it.isNotBlank() }
    is JsonArray -> {
        // Paire titre + adresse : le titre suffit, l'adresse s'ouvre à part.
        val named = e.filter { it !is JsonPrimitive || sourceUrl(it) == null }.takeIf { it.isNotEmpty() && e.all { x -> x is JsonPrimitive } } ?: e
        named.mapNotNull(::sourceText).joinToString(" · ").ifEmpty { null }
    }
    is JsonObject -> listOf("titre", "nom", "libelle", "url", "lien").firstNotNullOfOrNull { k -> (e[k] as? JsonPrimitive)?.let(::scalar)?.takeIf { it.isNotBlank() } }
}

/** Première adresse web d'une source ; tout autre schéma est ignoré (contenu non fiable). */
fun sourceUrl(e: JsonElement): String? = when (e) {
    is JsonPrimitive -> e.content.trim().takeIf { u -> u.toHttpUrlOrNull() != null && (u.startsWith("https://", true) || u.startsWith("http://", true)) }
    is JsonArray -> e.firstNotNullOfOrNull(::sourceUrl)
    is JsonObject -> listOf("url", "lien").firstNotNullOfOrNull { k -> e[k]?.let(::sourceUrl) }
}

data class Field(val label: String, val text: String)

data class Table(val label: String, val columns: List<String>, val rows: List<List<String>>)

data class EntryView(
    val title: String?,
    val verdict: String?,
    val fields: List<Field>,
    val tables: List<Table>,
    val source: String?,
    val url: String?,
)

/** Rôles affichés en champs libellés, dans cet ordre, avant les champs sans rôle. */
private val ROLE_LABELS = listOf(
    "faits" to "Faits",
    "definition" to "Définition",
    "reperer" to "Le repérer",
    "exemple" to "Exemple",
    "question" to "La question à poser",
    "date" to "Date",
    "dit" to "A dit",
    "vote" to "A voté",
    "theme" to "Thème",
    "camp" to "Camp",
)

/**
 * Une entrée quelconque en carte. Les rôles de [hint] passent d'abord (titre, verdict, source,
 * champs libellés) ; à défaut, titre (premier champ titre connu), verdict, champs simples avec
 * leur libellé, listes d'objets en petits tableaux, objets imbriqués aplatis d'un niveau,
 * source à part. [exclude] : clés montrées ailleurs.
 */
fun entryView(e: JsonElement, hint: PolHint? = null, exclude: Set<String> = emptySet()): EntryView {
    if (e !is JsonObject) return EntryView(null, null, listOfNotNull(plain(e)?.let { Field("", it) }), emptyList(), null, null)
    val keys = e.keys.filter { fold(it) !in exclude.map(::fold) }
    val roles = hint?.champs.orEmpty().filterValues { it in keys && e[it] != JsonNull }
    val titleKey = roles["titre"] ?: TITLE_KEYS.firstNotNullOfOrNull { t -> keys.firstOrNull { fold(it) == t && e[it] is JsonPrimitive && e[it] != JsonNull } }
    val verdictKey = roles["verdict"] ?: keys.firstOrNull { fold(it) == "verdict" }
    val sources = roles["source"]?.let(::listOf) ?: keys.filter { fold(it) in SOURCE_KEYS }
    val fields = ROLE_LABELS.mapNotNull { (role, label) -> roles[role]?.let { k -> plain(e[k]!!)?.let { Field(label, it) } } }.toMutableList()
    val tables = mutableListOf<Table>()
    val used = setOfNotNull(titleKey, verdictKey) + sources + roles.values
    for (k in keys) {
        if (k in used) continue
        when (val v = e[k]!!) {
            JsonNull -> Unit
            is JsonObject -> v.entries.mapNotNull { (sk, sv) -> scalar(sv)?.let { "${humanize(sk)} : $it" } }
                .takeIf { it.isNotEmpty() }?.let { fields += Field(humanize(k), it.joinToString(" · ")) }
            is JsonArray -> if (v.isNotEmpty() && v.all { it is JsonObject }) tables += table(humanize(k), v) else scalar(v)?.takeIf { it.isNotBlank() }?.let { fields += Field(humanize(k), it) }
            is JsonPrimitive -> scalar(v)?.takeIf { it.isNotBlank() }?.let { fields += Field(humanize(k), it) }
        }
    }
    val src = sources.mapNotNull { e[it] }
    return EntryView(
        titleKey?.let { e[it] }?.let(::plain),
        verdictKey?.let { e[it] }?.let(::plain),
        fields,
        tables,
        src.mapNotNull(::sourceText).joinToString(" · ").ifEmpty { null },
        src.firstNotNullOfOrNull(::sourceUrl),
    )
}

private fun table(label: String, list: JsonArray): Table {
    val objects = list.map { it as JsonObject }
    val columns = objects.flatMap { o -> o.keys.filter { scalar(o[it]!!) != null } }.distinct().take(MAX_COLS)
    val rows = objects.take(MAX_ROWS).map { o -> columns.map { c -> o[c]?.let(::scalar).orEmpty() } }
    return Table(label, columns.map(::humanize), rows)
}

/** Une liste de la rubrique ; [path] = son chemin pour les rattachements ; [notes] : valeurs simples. */
data class Section(val label: String, val path: String, val entries: List<JsonElement>, val notes: List<Field> = emptyList())

/** Contenu d'une rubrique découpé en listes (objets imbriqués suivis sur trois niveaux). */
fun sections(contenu: JsonElement?, path: String = "", depth: Int = 0): List<Section> = when (contenu) {
    null, JsonNull -> emptyList()
    is JsonArray -> listOf(Section(path.substringAfterLast('.').let(::humanize), path, contenu.take(MAX_ENTRIES)))
    is JsonPrimitive -> listOf(Section("", path, emptyList(), listOf(Field("", scalar(contenu).orEmpty()))))
    is JsonObject -> {
        val notes = contenu.entries.mapNotNull { (k, v) -> if (v is JsonPrimitive || (v is JsonArray && v.all { it is JsonPrimitive })) scalar(v)?.let { Field(humanize(k), it) } else null }
        val head = if (notes.isNotEmpty()) listOf(Section("", path, emptyList(), notes)) else emptyList()
        head + contenu.entries.flatMap { (k, v) ->
            val p = if (path.isEmpty()) k else "$path.$k"
            when {
                v is JsonArray && v.any { it !is JsonPrimitive } -> sections(v, p, depth + 1)
                v is JsonObject && depth < 2 -> sections(v, p, depth + 1)
                v is JsonObject -> listOf(Section(humanize(k), p, listOf(v)))
                else -> emptyList()
            }
        }
    }
}

/** Identités rattachées à l'entrée [index] de la liste [path] ; chemin vide : seule liste publiée. */
fun attachedIds(r: PolRubric, path: String, index: Int): List<String> {
    val byPath = r.rattachements[path] ?: r.rattachements.values.singleOrNull()?.takeIf { path.isEmpty() }
    val row = (byPath as? JsonArray)?.getOrNull(index) as? JsonArray ?: return emptyList()
    return row.mapNotNull { (it as? JsonPrimitive)?.content }
}

// --- Dit / voté ------------------------------------------------------------------------

private fun saidKey(k: String) = fold(k).let { it.startsWith("dit") || "declar" in it || "propos" in it }
private fun votedKey(k: String) = fold(k).let { "vot" in it && !it.startsWith("dit") }

/** Une entrée à afficher, avec l'indication de sa liste. */
data class Shown(val entry: JsonElement, val hint: PolHint?)

/** Propos et vote reliés par la donnée elle-même (une entrée porte les deux). */
data class SaidVoted(val said: String, val voted: String, val rest: JsonElement, val hint: PolHint? = null)

/**
 * Dit / voté d'une fiche : les paires que la donnée établit ; sinon, les déclarations d'un
 * côté, votes et revirements de l'autre, sans lien supposé. Rôles `dit` / `vote` des
 * indications d'abord ; liste sans indication : noms de champs devinés.
 */
data class DitVote(val pairs: List<SaidVoted>, val said: List<Shown>, val voted: List<Shown>)

fun ditVote(f: PolFiche, hints: List<PolHint> = emptyList()): DitVote {
    val all = f.rubriques.flatMap { g -> g.entrees.map { Triple(g, it, hintFor(hints, g.key, it.chemin)) } }
    val pairs = all.mapNotNull { (_, pe, h) ->
        val o = pe.entree as? JsonObject ?: return@mapNotNull null
        val sk = if (h != null) h.champs["dit"]?.takeIf { it in o } else o.keys.firstOrNull(::saidKey)
        val vk = if (h != null) h.champs["vote"]?.takeIf { it in o } else o.keys.firstOrNull(::votedKey)
        val s = sk?.let { o[it] }?.let(::plain)
        val v = vk?.let { o[it] }?.let(::plain)
        if (s != null && v != null) SaidVoted(s, v, JsonObject(o.filterKeys { it != sk && it != vk }), h) else null
    }
    if (pairs.isNotEmpty()) return DitVote(pairs, emptyList(), emptyList())
    fun of(vararg kinds: RubricKind) = all
        .filter { (g, _, h) -> (h?.let { hintKind(it.vue) } ?: rubricKind(g.key, g.titre, hints)) in kinds }
        .map { (_, pe, h) -> Shown(pe.entree, h) }
    return DitVote(emptyList(), of(RubricKind.Declarations), of(RubricKind.Votes, RubricKind.Revirements))
}

// --- Hors de ma bulle ------------------------------------------------------------------

private val THEME_KEYS = setOf("theme", "sujet")
private val CAMP_KEYS = setOf("camp", "bord", "cote")

/** Un thème : affirmations par camp, dans l'ordre de la donnée, et ce qui concerne tout le thème. */
data class BulleTheme(val theme: String, val camps: List<Pair<String, List<JsonElement>>>, val notes: List<JsonElement>)

/**
 * Affirmations groupées par thème puis par camp ; à plat (`theme`, `camp` sur chaque entrée)
 * ou imbriquées (une entrée par thème avec une liste d'objets portant `camp`). Avec [hint] :
 * sa liste seulement, et ses rôles `theme` / `camp` désignent les champs. Aucune forme
 * reconnue : null (affichage générique).
 */
fun bulle(contenu: JsonElement?, hint: PolHint? = null): List<BulleTheme>? {
    val items = sections(contenu).filter { hint == null || it.path == hint.chemin }.flatMap { it.entries }.filterIsInstance<JsonObject>()
    val themeKeys = hint?.champs?.get("theme")?.let { setOf(fold(it)) } ?: THEME_KEYS
    val campKeys = hint?.champs?.get("camp")?.let { setOf(fold(it)) } ?: CAMP_KEYS
    fun key(o: JsonObject, names: Set<String>) = o.keys.firstOrNull { fold(it) in names }
    val flat = mutableListOf<Triple<String, String, JsonElement>>()
    val notes = linkedMapOf<String, MutableList<JsonElement>>()
    for (o in items) {
        val t = key(o, themeKeys)?.let { o[it] }?.let(::scalar) ?: continue
        val c = key(o, campKeys)?.let { o[it] }?.let(::scalar)
        if (c != null) {
            flat += Triple(t, c, JsonObject(o.filterKeys { k -> fold(k) !in themeKeys && fold(k) !in campKeys }))
            continue
        }
        val nested = o.entries.firstOrNull { (_, v) -> v is JsonArray && v.isNotEmpty() && v.all { it is JsonObject && key(it, campKeys) != null } }
        nested?.value?.jsonArrayItems()?.forEach { p ->
            flat += Triple(t, scalar(p[key(p, campKeys)!!]!!).orEmpty(), JsonObject(p.filterKeys { fold(it) !in campKeys }))
        }
        val rest = o.filterKeys { k -> fold(k) !in themeKeys && k != nested?.key }
        if (rest.isNotEmpty()) notes.getOrPut(t) { mutableListOf() } += JsonObject(rest)
    }
    if (flat.isEmpty()) return null
    val themes = (flat.map { it.first } + notes.keys).distinct()
    return themes.map { t ->
        val mine = flat.filter { it.first == t }
        BulleTheme(t, mine.map { it.second }.distinct().map { c -> c to mine.filter { it.second == c }.map { it.third } }, notes[t].orEmpty())
    }
}

private fun JsonElement.jsonArrayItems(): List<JsonObject> = (this as JsonArray).map { it as JsonObject }

/** Ton d'un verdict : la couleur double le mot, jamais seule. */
enum class VerdictTone { Ok, Mixed, Wrong }

fun verdictTone(v: String): VerdictTone {
    val k = fold(v)
    return when {
        "faux" in k || "trompeur" in k || "errone" in k -> VerdictTone.Wrong
        k == "vrai" || k == "exact" || k == "coherent" -> VerdictTone.Ok
        else -> VerdictTone.Mixed
    }
}

// --- Fiche -----------------------------------------------------------------------------

/** Onglets de la fiche : rubriques non vides, dans l'ordre du module. */
fun ficheTabs(f: PolFiche): List<PolGroup> = f.rubriques.filter { it.entrees.isNotEmpty() }

/** Identités nommées dans les champs simples d'une entrée de recherche (aucun rattachement fourni). */
fun identitiesOf(e: JsonElement, home: PolHome?): List<PolIdentity> {
    val o = e as? JsonObject ?: return emptyList()
    val values = o.values.mapNotNull { scalar(it)?.let(::fold) }.toSet()
    return home?.identites.orEmpty().filter { fold(it.nom) in values || fold(it.id) in values }
}

/**
 * Parti d'une personne, publié comme identité à part (identifiant ou nom égal à son champ
 * `parti`) : la fiche y renvoie sans en reprendre les entrées.
 */
fun partyOf(who: PolIdentity, home: PolHome?): PolIdentity? {
    val p = who.parti?.let(::fold)?.takeIf { it.isNotEmpty() } ?: return null
    return home?.identites?.firstOrNull { it.id != who.id && (fold(it.id) == p || fold(it.nom) == p) }
}

/** Résumé de l'accueil d'une rubrique, retrouvé par son identifiant. */
fun summaryOf(home: PolHome?, id: String): PolRubricSummary? = home?.rubriques?.firstOrNull { it.id == id }
