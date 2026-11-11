package app.kairos.mobile.ui.politique

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.PolCompare
import app.kairos.mobile.data.PolFiche
import app.kairos.mobile.data.PolHome
import app.kairos.mobile.data.PolIdentity
import app.kairos.mobile.data.PolRubric
import app.kairos.mobile.data.PolSearch
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.SpaceScaffold
import app.kairos.mobile.ui.kit.StatePill
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.mimir.ShareDialog
import app.kairos.mobile.ui.mimir.SharesPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Vues de l'espace, empilées au-dessus de l'accueil. */
private sealed interface Route {
    data class Fiche(val id: String) : Route
    data class Compare(val preset: String?) : Route
    data object Verify : Route
    data object DitVote : Route
    data class Rubric(val id: String, val titre: String) : Route
    data object Shares : Route
    data object Site : Route
}

private val Corner = RoundedCornerShape(16.dp)

/**
 * Espace Politique : données publiques sourcées, en lecture seule, servies par le module
 * [POLITIQUE]. Accueil (recherche, identités, Comparer, Vérifier, rubriques), fiche à onglets,
 * comparaison, vérification, dit / voté, rubriques. Rien n'est gardé sur l'appareil : tout est
 * relu à l'ouverture. Le site complet ([site]) et le partage restent accessibles même quand le
 * module ne répond pas. Retour : vue précédente, puis fermeture de l'espace.
 */
@Composable
fun PolitiqueScreen(
    repository: Repository,
    padding: PaddingValues,
    appScope: CoroutineScope,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
    site: @Composable (onClose: () -> Unit) -> Unit,
) {
    val stack = remember { mutableStateListOf<Route>() }
    fun push(r: Route) = stack.add(r)
    fun pop() = stack.removeLastOrNull()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    var reload by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<HomeLoad>(HomeLoad.Loading) }
    var sharing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        load = HomeLoad.Loading
        load = try {
            HomeLoad.Ready(repository.lookup(POLITIQUE, "app_politique_accueil", PolHome.serializer(), JsonObject(emptyMap())))
        } catch (_: ApiError.Unauthorized) {
            unauthorized()
            return@LaunchedEffect
        } catch (e: ApiError) {
            HomeLoad.Down(politiqueIssue(e))
        }
    }
    val home = (load as? HomeLoad.Ready)?.home
    val route = stack.lastOrNull()

    // Avant le site : ses propres retours (page précédente, formulaires) passent d'abord.
    BackHandler(enabled = route != null) { pop() }
    if (route == Route.Site) {
        site { pop() }
        return
    }

    val title = when (route) {
        null -> "Politique"
        is Route.Fiche -> home?.identites?.firstOrNull { it.id == route.id }?.nom ?: "Fiche"
        is Route.Compare -> "Comparer"
        Route.Verify -> "Vérifier"
        Route.DitVote -> "Dit / voté"
        is Route.Rubric -> route.titre
        Route.Shares -> "Accès partagés"
        Route.Site -> ""
    }
    SpaceScaffold(
        title,
        onBack = { if (route == null) onClose() else pop() },
        padding = padding,
        backLabel = if (route == null) "Fermer" else "Retour",
        actions = {
            IconButton(onClick = { sharing = true }) {
                Icon(KairosIcons.Share, contentDescription = "Partager le site", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(KairosIcons.Gear, contentDescription = "Site et accès", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ouvrir le site") }, onClick = {
                        menu = false
                        push(Route.Site)
                    })
                    if (route != Route.Shares) DropdownMenuItem(text = { Text("Accès partagés") }, onClick = {
                        menu = false
                        push(Route.Shares)
                    })
                    if (route == null) DropdownMenuItem(text = { Text("Recharger") }, onClick = {
                        menu = false
                        reload++
                    })
                }
            }
        },
    ) { inner ->
        val bottom = inner.calculateBottomPadding()
        if (route == Route.Shares) {
            SharesPanel(repository, appScope, bottom, onUnauthorized)
            return@SpaceScaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = bottom),
        ) {
            when (route) {
                null -> Home(load, onRetry = { reload++ }, onGo = ::push)
                is Route.Fiche -> FicheView(repository, route.id, home, onUnauthorized, onCompare = { push(Route.Compare(route.id)) })
                is Route.Compare -> CompareView(repository, home, route.preset, onUnauthorized)
                Route.Verify -> VerifyView(repository, home, onUnauthorized)
                Route.DitVote -> DitVoteView(repository, home, onUnauthorized)
                is Route.Rubric -> RubricView(repository, route.id, home, onUnauthorized)
                Route.Shares, Route.Site -> Unit
            }
        }
    }

    if (sharing) ShareDialog(repository, onDismiss = { sharing = false }, onUnauthorized = onUnauthorized)
}

// --- Accueil ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Home(load: HomeLoad, onRetry: () -> Unit, onGo: (Route) -> Unit) {
    when (load) {
        HomeLoad.Loading -> Spinner("Chargement de l'espace Politique")
        is HomeLoad.Down -> {
            StatusBanner("Politique indisponible", tone = Tone.Urgent, detail = load.reason, actionLabel = "Réessayer", onAction = onRetry)
            Spacer(Modifier.size(12.dp))
            Caption("Le site reste consultable en entier, et ses accès partageables.")
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { onGo(Route.Site) }) { Text("Ouvrir le site") }
        }
        is HomeLoad.Ready -> {
            val home = load.home
            val actions = homeActions(load)
            // Champ d'appel : la recherche elle-même vit dans « Vérifier ».
            Surface(
                onClick = { onGo(Route.Verify) },
                shape = Corner,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Chercher un thème ou une phrase entendue" },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(KairosIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Un thème, une phrase entendue…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            checkedLabel(home.checkedOn)?.let { Caption(it, Modifier.padding(top = 8.dp)) }

            if (home.identites.isNotEmpty()) {
                SectionHeader("Candidats et partis")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    home.identites.forEach { id -> IdentityTile(id) { onGo(Route.Fiche(id.id)) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (HomeAction.Comparer in actions) Button(onClick = { onGo(Route.Compare(null)) }, modifier = Modifier.weight(1f)) { Text("Comparer") }
                if (HomeAction.Verifier in actions) FilledTonalButton(onClick = { onGo(Route.Verify) }, modifier = Modifier.weight(1f)) { Text("Vérifier une phrase") }
            }

            val cards = homeCards(home)
            if (cards.isNotEmpty()) SectionHeader("Rubriques")
            val (wide, narrow) = cards.partition { it.wide }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                narrow.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { c -> RubricTile(c, Modifier.weight(1f)) { onGo(c.route()) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                wide.forEach { c -> RubricTile(c, Modifier.fillMaxWidth()) { onGo(c.route()) } }
            }
            if (home.identites.isEmpty() && cards.isEmpty()) Caption("Aucune donnée publiée pour l'instant.", Modifier.padding(top = 16.dp))
        }
    }
}

private fun RubricCard.route(): Route = if (id == null) Route.DitVote else Route.Rubric(id, titre)

@Composable
private fun IdentityTile(id: PolIdentity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Corner,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(96.dp).semantics(mergeDescendants = true) { contentDescription = listOfNotNull(id.nom, id.parti).joinToString(", ") },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IdentityBadge(id, 44.dp)
            Spacer(Modifier.size(6.dp))
            Text(id.nom, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            id.parti?.let { Caption(it, maxLines = 1) }
        }
    }
}

@Composable
private fun RubricTile(c: RubricCard, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Corner,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(c.titre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Caption(c.resume, maxLines = 2)
        }
    }
}

// --- Fiche -----------------------------------------------------------------------------

@Composable
private fun FicheView(repository: Repository, id: String, home: PolHome?, onUnauthorized: () -> Unit, onCompare: () -> Unit) {
    Fetch(id, onUnauthorized, { repository.lookup(POLITIQUE, "app_politique_fiche", PolFiche.serializer(), buildJsonObject { put("id", id) }) }) { f ->
        val who = f.identite
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IdentityBadge(who, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(who.nom, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                who.parti?.let { Caption(it) }
            }
        }
        val tabs = ficheTabs(f)
        Caption(if (tabs.isEmpty()) "Aucune entrée rattachée." else "${tabs.sumOf { it.entrees.size }} entrées sourcées, par rubrique.")
        var tab by remember(id) { mutableIntStateOf(0) }
        Chips(tabs.map { it.titre.ifBlank { it.key } }, tab) { tab = it }
        tabs.getOrNull(tab)?.entrees?.forEach { EntryCard(it) }
        if (home != null && home.themes.isNotEmpty() && home.identites.size >= 2) {
            Button(onClick = onCompare, modifier = Modifier.padding(top = 8.dp)) { Text("Comparer avec…") }
        }
    }
}

// --- Comparer --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompareView(repository: Repository, home: PolHome?, preset: String?, onUnauthorized: () -> Unit) {
    if (home == null) return Caption("Accueil indisponible : reviens en arrière et réessaie.")
    var selected by remember { mutableStateOf(listOfNotNull(preset)) }
    var theme by remember { mutableStateOf(home.themes.firstOrNull()?.id) }
    var source by remember { mutableStateOf<Cell?>(null) }

    SectionHeader("2 ou 3 identités")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        home.identites.forEach { id ->
            FilterChip(
                selected = id.id in selected,
                onClick = { selected = toggleCompare(selected, id.id) },
                label = { Text(id.nom) },
                enabled = id.id in selected || selected.size < 3,
            )
        }
    }
    SectionHeader("Thème")
    Chips(home.themes.map { it.titre.ifBlank { it.id } }, home.themes.indexOfFirst { it.id == theme }) { theme = home.themes[it].id }

    val t = theme
    if (!compareReady(selected, t) || t == null) return Caption("Choisis 2 ou 3 identités et un thème.", Modifier.padding(top = 12.dp))
    val args = buildJsonObject {
        put("ids", JsonArray(selected.map(::JsonPrimitive)))
        put("theme", t)
    }
    Fetch(selected to t, onUnauthorized, { repository.lookup(POLITIQUE, "app_politique_comparer", PolCompare.serializer(), args) }) { c ->
        val table = compareTable(c, selected, home.identites)
        if (table.rows.isEmpty()) return@Fetch Caption("Aucun indicateur publié pour ce thème.", Modifier.padding(top = 12.dp))
        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.2f))
            table.columns.forEach { Text(it.nom, Modifier.weight(1f).padding(4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
        }
        table.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Text(row.label, Modifier.weight(1.2f).padding(4.dp), style = MaterialTheme.typography.bodySmall)
                row.cells.forEachIndexed { i, cell ->
                    TextButton(
                        onClick = { source = cell },
                        enabled = cell.source != null || cell.url != null,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = "${table.columns[i].nom}, ${row.label} : ${cell.text}" + if (cell.source != null) ", voir la source" else ""
                        },
                    ) {
                        Text(
                            cell.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (cell.given) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Caption("Chaque case ouvre sa source.", Modifier.padding(top = 6.dp))
    }
    source?.let { c -> SourceDialog(c.source, c.url) { source = null } }
}

// --- Vérifier --------------------------------------------------------------------------

@Composable
private fun VerifyView(repository: Repository, home: PolHome?, onUnauthorized: () -> Unit) {
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val search = remember {
        DebouncedSearch(scope, { q -> repository.lookup(POLITIQUE, "app_politique_chercher", PolSearch.serializer(), buildJsonObject { put("q", q) }) }, { unauthorized() })
    }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = {
            query = it
            search.type(it)
        },
        label = { Text("Un thème, une phrase entendue…") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    if (search.searching) Caption("Recherche…", Modifier.padding(top = 6.dp))
    search.failed?.let { StatusBanner(it, Modifier.padding(top = 8.dp)) }
    val r = search.results ?: return
    val groups = boundedResults(r.resultats)
    if (groups.isEmpty()) return Caption("Rien dans les données sur ces mots.", Modifier.padding(top = 12.dp))
    val (checked, others) = groups.partition { rubricKind(it.key, it.titre) == RubricKind.Declarations }
    if (checked.isNotEmpty()) SectionHeader("Ce qu'en disent les données")
    checked.forEach { g -> g.entrees.forEach { EntryCard(it, identitiesOf(it, home)) } }
    if (others.isNotEmpty()) SectionHeader("Ailleurs dans les rubriques")
    others.forEach { g ->
        Text(g.titre.ifBlank { summaryOf(home, g.key)?.titre ?: g.key }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
        g.entrees.forEach { EntryCard(it, identitiesOf(it, home)) }
    }
}

/** Identités nommées dans les champs simples d'une entrée de recherche (aucun rattachement fourni). */
private fun identitiesOf(e: JsonElement, home: PolHome?): List<PolIdentity> {
    val o = e as? JsonObject ?: return emptyList()
    val values = o.values.mapNotNull { scalar(it)?.let(::fold) }.toSet()
    return home?.identites.orEmpty().filter { fold(it.nom) in values || fold(it.id) in values }
}

// --- Dit / voté ------------------------------------------------------------------------

@Composable
private fun DitVoteView(repository: Repository, home: PolHome?, onUnauthorized: () -> Unit) {
    val ids = home?.identites.orEmpty()
    if (ids.isEmpty()) return Caption("Aucune identité publiée.")
    var tab by remember { mutableIntStateOf(0) }
    Chips(ids.map { it.nom }, tab) { tab = it }
    val id = ids[tab.coerceIn(0, ids.size - 1)].id
    Fetch(id, onUnauthorized, { repository.lookup(POLITIQUE, "app_politique_fiche", PolFiche.serializer(), buildJsonObject { put("id", id) }) }) { f ->
        val d = ditVote(f)
        if (d.pairs.isNotEmpty()) {
            d.pairs.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quote("A dit", p.said, Modifier.weight(1f))
                    Quote("A voté", p.voted, Modifier.weight(1f))
                }
                val rest = entryView(p.rest)
                (rest.fields.map { it.text } + listOfNotNull(rest.source)).takeIf { it.isNotEmpty() }?.let { Caption(it.joinToString(" · "), Modifier.padding(4.dp)) }
            }
            return@Fetch
        }
        if (d.said.isEmpty() && d.voted.isEmpty()) return@Fetch Caption("Ni déclaration ni vote rattachés.", Modifier.padding(top = 12.dp))
        Caption("La donnée ne relie pas ces propos à ces votes : les deux listes côte à côte.", Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                SectionHeader("Déclarations")
                d.said.forEach { EntryCard(it) }
            }
            Column(Modifier.weight(1f)) {
                SectionHeader("Votes, revirements")
                d.voted.forEach { EntryCard(it) }
            }
        }
    }
}

@Composable
private fun Quote(label: String, text: String, modifier: Modifier) = Card(modifier) {
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

// --- Rubrique --------------------------------------------------------------------------

@Composable
private fun RubricView(repository: Repository, id: String, home: PolHome?, onUnauthorized: () -> Unit) {
    Fetch(id, onUnauthorized, { repository.lookup(POLITIQUE, "app_politique_rubrique", PolRubric.serializer(), buildJsonObject { put("id", id) }) }) { r ->
        summaryOf(home, id)?.resume?.let { Caption(it, Modifier.padding(bottom = 4.dp)) }
        val themes = if (rubricKind(r.id.ifEmpty { id }, r.titre) == RubricKind.Bulle) bulle(r.contenu) else null
        if (themes != null) return@Fetch BulleView(themes)
        val parts = sections(r.contenu)
        if (parts.isEmpty()) return@Fetch Caption("Rubrique vide.")
        parts.forEach { s ->
            if (s.label.isNotBlank()) SectionHeader(s.label)
            s.notes.forEach { n -> Caption(listOf(n.label, n.text).filter { it.isNotBlank() }.joinToString(" : ")) }
            s.entries.forEachIndexed { i, e ->
                EntryCard(e, attachedIds(r, s.path, i).mapNotNull { a -> home?.identites?.firstOrNull { it.id == a } })
            }
        }
    }
}

@Composable
private fun BulleView(themes: List<BulleTheme>) {
    var tab by remember { mutableIntStateOf(0) }
    Chips(themes.map { it.theme }, tab) { tab = it }
    val t = themes[tab.coerceIn(0, themes.size - 1)]
    t.camps.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (camp, claims) ->
                Column(Modifier.weight(1f)) {
                    Text(camp, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
                    claims.forEach { EntryCard(it) }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (t.notes.isNotEmpty()) SectionHeader("Pour tout le thème")
    t.notes.forEach { EntryCard(it) }
}

// --- Briques communes ------------------------------------------------------------------

/**
 * Lecture ponctuelle d'un outil, relancée quand [key] change : chargement, erreur avec
 * « Réessayer », puis [content].
 */
@Composable
private fun <T : Any> Fetch(key: Any, onUnauthorized: () -> Unit, fetch: suspend () -> T, content: @Composable (T) -> Unit) {
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val current by rememberUpdatedState(fetch)
    var attempt by remember(key) { mutableIntStateOf(0) }
    var value by remember(key) { mutableStateOf<T?>(null) }
    var issue by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, attempt) {
        issue = null
        try {
            value = current()
        } catch (_: ApiError.Unauthorized) {
            unauthorized()
        } catch (e: ApiError) {
            issue = politiqueIssue(e)
        }
    }
    val v = value
    when {
        issue != null -> StatusBanner("Indisponible", tone = Tone.Urgent, detail = issue, actionLabel = "Réessayer", onAction = { attempt++ })
        v == null -> Spinner("Chargement")
        else -> content(v)
    }
}

@Composable
private fun Spinner(label: String) = Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.semantics { contentDescription = label })
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) = Text(
    text,
    modifier = modifier,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
)

/** Onglets en pastilles défilantes ; [selected] hors bornes : aucun. */
@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (labels.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l -> FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(l) }) }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
}

/**
 * Entrée quelconque : titre, verdict (mot et couleur), champs libellés, petits tableaux,
 * identités rattachées, et la source, qui s'ouvre quand c'est une adresse web.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(e: JsonElement, identities: List<PolIdentity> = emptyList()) {
    val v = remember(e) { entryView(e) }
    val context = LocalContext.current
    Card {
        Box(Modifier.semantics(mergeDescendants = true) {}) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                v.verdict?.let { StatePill(it, tone = when (verdictTone(it)) { VerdictTone.Ok -> Tone.Neutral; VerdictTone.Mixed -> Tone.Attention; VerdictTone.Wrong -> Tone.Urgent }) }
                v.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                v.fields.forEach { f ->
                    Text(
                        if (f.label.isEmpty()) f.text else "${f.label} : ${f.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        v.tables.forEach { t -> MiniTable(t) }
        if (identities.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { identities.forEach { StatePill(it.nom) } }
        }
        v.source?.let { s ->
            val url = v.url
            Text(
                "Source : $s",
                style = MaterialTheme.typography.labelSmall,
                color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (url != null) {
                    Modifier.padding(top = 2.dp).semantics { contentDescription = "Ouvrir la source : $s"; role = Role.Button }
                        .clickable { openSource(context, url) }
                } else {
                    Modifier.padding(top = 2.dp)
                },
            )
        }
    }
}

@Composable
private fun MiniTable(t: Table) {
    Text(t.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth()) { t.columns.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    t.rows.forEach { r -> Row(Modifier.fillMaxWidth()) { r.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) } } }
}

@Composable
private fun SourceDialog(text: String?, url: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source") },
        text = { Text(text ?: url.orEmpty()) },
        confirmButton = {
            if (url != null) TextButton(onClick = { onDismiss(); openSource(context, url) }) { Text("Ouvrir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Ouvre une source web hors de l'application ; adresse déjà vérifiée (http ou https). */
private fun openSource(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Aucun navigateur : rien.
    }
}
