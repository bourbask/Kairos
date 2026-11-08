package app.kairos.mobile.ui.medias

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.ui.kit.rememberNotifier
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.DownloadItem
import app.kairos.mobile.data.Downloads
import app.kairos.mobile.data.MediaRequest
import app.kairos.mobile.data.MediaResult
import app.kairos.mobile.data.Medias
import app.kairos.mobile.data.Release
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.finances.syncLabel
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.maison.ChevronBackIcon
import app.kairos.mobile.ui.talk.IconButton
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.InFlight
import app.kairos.mobile.ui.today.Pill
import app.kairos.mobile.ui.kit.SwipeRow
import app.kairos.mobile.ui.today.completeCard
import app.kairos.mobile.ui.today.refusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

private const val UNDO_MS = 4_000L
private val RowCorner = 16.dp
private val RESULTS = ListSerializer(MediaResult.serializer())

/**
 * Espace Médias : à regarder (glisser → « vu », bouton « Vu », annulation 4 s ; sans
 * sous-titres FR à part), téléchargements (barres, bouton pause / reprise), sorties des
 * 7 prochains jours, demandes (recherche puis « Demander »), bandeau des problèmes.
 * Une section tombée donne sa raison sans masquer les autres ; dernier état gardé hors ligne.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediasScreen(
    repository: Repository,
    padding: PaddingValues,
    appScope: CoroutineScope,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val snapshot by repository.medias.collectAsState()
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val snackbar = remember { SnackbarHostState() }
    val undo = rememberNotifier(snackbar)
    var refreshing by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val busy = remember { InFlight() }

    fun refresh(force: Boolean = true) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refreshMedias(force)
            } catch (_: ApiError.Unauthorized) {
                unauthorized()
            } finally {
                refreshing = false
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        refresh(force = false)
        onPauseOrDispose {}
    }

    fun notice(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    /** Geste ponctuel (pause, demande) ; rend vrai si le module l'a pris. */
    suspend fun write(tool: String, args: JsonObject): Boolean = try {
        notice(repository.tool(MEDIAS, tool, args))
        repository.refreshMedias()
        true
    } catch (_: ApiError.Unauthorized) {
        unauthorized()
        false
    } catch (e: ApiError) {
        notice(refusal(e))
        false
    }

    // Depuis appScope : quitter l'espace pendant la fenêtre n'abandonne pas le geste.
    fun watched(w: Watch) {
        appScope.launch {
            val out = try {
                completeCard(
                    watchedCard(w),
                    null,
                    mark = { m -> hidden = if (m == null) hidden - w.key else hidden + w.key },
                    undoWindow = { undo.undo("Vu · ${w.title}") },
                    call = repository::tool,
                    busy = busy,
                )
            } catch (_: ApiError.Unauthorized) {
                return@launch unauthorized()
            }
            when (out) {
                is DoneOutcome.Done -> {
                    // Vu, déjà vu ou disparu : l'élément quitte la liste dans tous les cas.
                    repository.dropWatched(w.key)
                    try {
                        repository.refreshMedias()
                    } catch (_: ApiError.Unauthorized) {
                        return@launch unauthorized()
                    }
                    if (repository.medias.value.issue == null) hidden = hidden - w.key
                    watchedNotice(out.message)?.let { snackbar.showSnackbar(it) }
                }
                is DoneOutcome.Refused -> snackbar.showSnackbar(out.message)
                else -> Unit
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(label = "Retour aux espaces", onClick = onClose) { ChevronBackIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Médias",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                ) {
                    snapshot.issue?.let { issue ->
                        StatusBanner(issue, detail = snapshot.fetchedAt?.let { "Dernier état : ${syncLabel(it)}" })
                        Spacer(Modifier.height(8.dp))
                    }
                    val m = snapshot.medias
                    if (m == null) {
                        Caption(
                            if (snapshot.issue != null) "${snapshot.issue}, et rien en mémoire pour l'instant." else "Chargement…",
                            Modifier.padding(vertical = 24.dp),
                        )
                    } else {
                        problems(m).takeIf { it.isNotEmpty() }?.let { StatusBanner(it.joinToString(" · "), tone = Tone.Urgent) }
                        ToWatch(m, toWatch(m, hidden), ::watched)
                        DownloadsSection(m) { d -> write("app_downloads_control", pauseArgs(d)) }
                        Upcoming(m, LocalDate.now())
                        Requests(
                            m,
                            search = { q -> repository.lookup(MEDIAS, "app_media_search", RESULTS, buildJsonObject { put("query", q) }) },
                            onRequest = { r -> write("app_media_request", requestArgs(r)) },
                            onNote = ::notice,
                            onUnauthorized = unauthorized,
                        )
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp),
        ) { data ->
            Snackbar(
                data,
                shape = MaterialTheme.shapes.medium,
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                actionColor = MaterialTheme.colorScheme.primaryContainer,
            )
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/** Raison d'une section tombée ; les autres restent affichées. */
@Composable
private fun SectionDown(reason: String) = Caption("Indisponible · $reason", Modifier.padding(bottom = 6.dp))

@Composable
private fun Group(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

@Composable
private fun ToWatch(m: Medias, list: WatchList, onWatched: (Watch) -> Unit) {
    SectionHeader("À regarder")
    sectionIssue(m, CONTINUE, READY)?.let { SectionDown(it) }
    if (list.main.isEmpty() && list.noFrench.isEmpty() && m.resume != null && m.ready != null) Caption("Rien de prêt pour l'instant.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.main.forEach { w -> key(w.key) { WatchRow(w, onWatched) } }
    }
    if (list.noFrench.isNotEmpty()) {
        Group("Sans sous-titres français")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.noFrench.forEach { w -> key(w.key) { WatchRow(w, onWatched) } }
        }
    }
}

/** Élément à regarder : glisser → « vu » ; le même geste en bouton. */
@Composable
private fun WatchRow(w: Watch, onWatched: (Watch) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SwipeRow(enabled = true, onRight = { onWatched(w) }, onLeft = {}, rightLabel = "Vu ✓", leftLabel = "", leftStays = true) {
        Surface(
            shape = RoundedCornerShape(RowCorner),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(w.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(w.note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = { onWatched(w) }, modifier = Modifier.semantics { contentDescription = "Marquer vu : ${w.title}" }) { Text("Vu") }
            }
        }
    }
}

@Composable
private fun DownloadsSection(m: Medias, onToggle: suspend (Downloads) -> Boolean) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    val d = m.downloads
    SectionHeader("Téléchargements") {
        if (d != null) {
            Pill(if (sending) "Envoi…" else pauseButton(d)) {
                if (!sending) {
                    sending = true
                    scope.launch {
                        onToggle(d)
                        sending = false
                    }
                }
            }
        }
    }
    sectionIssue(m, DOWNLOADS)?.let { SectionDown(it) }
    d ?: return
    Caption(downloadsHeadline(d), Modifier.padding(bottom = 6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        d.items.forEach { item -> DownloadRow(item) }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    val colors = MaterialTheme.colorScheme
    val problem = isStuck(item)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!problem) {
            LinearProgressIndicator(
                progress = { item.progress.toFloat().takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            downloadLine(item),
            style = MaterialTheme.typography.labelMedium,
            color = if (problem) colors.error else colors.onSurfaceVariant,
            fontWeight = if (problem) FontWeight.SemiBold else null,
        )
    }
}

@Composable
private fun Upcoming(m: Medias, today: LocalDate) {
    SectionHeader("À venir")
    sectionIssue(m, UPCOMING)?.let { SectionDown(it) }
    val list = m.upcoming ?: return
    if (list.isEmpty()) Caption("Aucune sortie suivie dans les 7 prochains jours.")
    upcomingDays(list, today).forEach { g ->
        Group(g.label)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.releases.forEach { r -> ReleaseRow(r) }
        }
    }
}

@Composable
private fun ReleaseRow(r: Release) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            releaseNote(r),
            style = MaterialTheme.typography.labelMedium,
            color = if (r.have) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Requests(
    m: Medias,
    search: suspend (String) -> List<MediaResult>,
    onRequest: suspend (MediaResult) -> Boolean,
    onNote: (String) -> Unit,
    onUnauthorized: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(emptySet<String>()) }
    var sending by remember { mutableStateOf(emptySet<String>()) }
    fun run() {
        if (!canSearch(query) || searching) return
        searching = true
        scope.launch {
            try {
                results = search(query.trim())
            } catch (_: ApiError.Unauthorized) {
                onUnauthorized()
            } catch (e: ApiError) {
                onNote(refusal(e))
            } finally {
                searching = false
            }
        }
    }

    SectionHeader("Demandes")
    sectionIssue(m, REQUESTS)?.let { SectionDown(it) }
    m.requests?.let { list ->
        if (list.isEmpty()) Caption("Aucune demande suivie.")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { list.forEach { RequestRow(it) } }
    }
    Group("Ajouter")
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) results = null
            },
            label = { Text("Série ou film") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = ::run, enabled = canSearch(query) && !searching) { Text(if (searching) "…" else "Chercher") }
    }
    val shown = results ?: return
    if (shown.isEmpty()) Caption("Aucun résultat.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { r ->
            val k = askKey(r)
            val state = askState(r, asked)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Caption(resultNote(r))
                }
                OutlinedButton(
                    onClick = {
                        sending = sending + k
                        scope.launch {
                            if (onRequest(r)) asked = asked + k
                            sending = sending - k
                        }
                    },
                    enabled = state.enabled && k !in sending,
                    modifier = Modifier.semantics { contentDescription = "${state.label} : ${r.title}" },
                ) { Text(if (k in sending) "Envoi…" else state.label) }
            }
        }
    }
}

@Composable
private fun RequestRow(r: MediaRequest) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(r.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Caption(r.state)
    }
}
