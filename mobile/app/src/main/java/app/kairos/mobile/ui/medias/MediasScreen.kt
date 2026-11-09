package app.kairos.mobile.ui.medias

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.DownloadItem
import app.kairos.mobile.data.MediaRequest
import app.kairos.mobile.data.MediaResult
import app.kairos.mobile.data.Medias
import app.kairos.mobile.data.Release
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.finances.issueBanner
import app.kairos.mobile.ui.finances.syncLabel
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.LocalNotifier
import app.kairos.mobile.ui.kit.PrimaryAction
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.SpaceScaffold
import app.kairos.mobile.ui.kit.StatePill
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.SwipeRow
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.InFlight
import app.kairos.mobile.ui.today.completeCard
import app.kairos.mobile.ui.today.refusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

private val RowCorner = 16.dp
private val RESULTS = ListSerializer(MediaResult.serializer())

/**
 * Espace Médias : à regarder (glisser → « vu », bouton « Vu », fenêtre d'annulation ; sans
 * sous-titres FR à part), téléchargements (barres, pause / reprise), sorties des 7 prochains
 * jours, demandes suivies. « Demander » (action principale) ouvre la recherche en panneau.
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
    val notifier = LocalNotifier.current
    var refreshing by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var asking by rememberSaveable { mutableStateOf(false) }
    val busy = remember { InFlight() }
    val scroll = rememberScrollState()

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
        scope.launch { notifier.show(text) }
    }

    /** Geste ponctuel (pause, reprise) ; le message du module s'affiche. */
    suspend fun write(tool: String, args: JsonObject) {
        try {
            notice(repository.tool(MEDIAS, tool, args))
            repository.refreshMedias()
        } catch (_: ApiError.Unauthorized) {
            unauthorized()
        } catch (e: ApiError) {
            notice(refusal(e))
        }
    }

    // Depuis appScope : quitter l'espace pendant la fenêtre n'abandonne pas le geste.
    fun watched(w: Watch) {
        appScope.launch {
            val out = try {
                completeCard(
                    watchedCard(w),
                    null,
                    mark = { m -> hidden = if (m == null) hidden - w.key else hidden + w.key },
                    undoWindow = { notifier.undo("Vu · ${w.title}") },
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
                    watchedNotice(out.message)?.let { notifier.show(it) }
                }
                is DoneOutcome.Refused -> notifier.show(out.message)
                else -> Unit
            }
        }
    }

    val m = snapshot.medias
    SpaceScaffold(
        "Médias",
        onClose,
        padding,
        scrolled = scroll.canScrollBackward,
        banner = snapshot.issue?.let { issue ->
            {
                val (title, detail) = issueBanner("Médias", issue, snapshot.fetchedAt?.let { "Dernier état : ${syncLabel(it)}" })
                StatusBanner(title, detail = detail, actionLabel = "Réessayer", onAction = { refresh() })
            }
        },
        primaryAction = { PrimaryAction("Demander", { asking = true }, icon = KairosIcons.Search) },
    ) { inner ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp)
                    .padding(inner)
                    .padding(top = 8.dp),
            ) {
                if (m == null) {
                    // L'indisponibilité est dite par le bandeau : ici, seulement l'absence de données connues.
                    Caption(if (snapshot.issue != null) "Rien en mémoire pour l'instant." else "Chargement…", Modifier.padding(vertical = 24.dp))
                } else {
                    problems(m).takeIf { it.isNotEmpty() }?.let { StatusBanner(it.joinToString(" · "), tone = Tone.Urgent) }
                    ToWatch(m, toWatch(m, hidden), ::watched)
                    DownloadsSection(m) { action -> write("app_downloads_control", pauseArgs(action)) }
                    Upcoming(m, LocalDate.now())
                    Requests(m)
                }
            }
        }
    }
    if (asking) {
        ModalBottomSheet(onDismissRequest = { asking = false }) {
            AskPanel(
                search = { q -> repository.lookup(MEDIAS, "app_media_search", RESULTS, buildJsonObject { put("query", q) }) },
                request = { r -> repository.tool(MEDIAS, "app_media_request", requestArgs(r)) },
                onRequested = {
                    scope.launch {
                        // Relecture de confort : un échec se voit au prochain rafraîchissement, une session expirée tout de suite.
                        try {
                            repository.refreshMedias()
                        } catch (_: ApiError.Unauthorized) {
                            unauthorized()
                        } catch (_: ApiError) {
                        }
                    }
                },
                onUnauthorized = unauthorized,
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
                Column(Modifier.weight(1f)) {
                    Text(w.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(w.note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = { onWatched(w) }, modifier = Modifier.semantics { contentDescription = "Marquer vu : ${w.title}" }) { Text("Vu") }
            }
        }
    }
}

@Composable
private fun DownloadsSection(m: Medias, onControl: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf<String?>(null) }
    val d = m.downloads
    // Section tombée : l'état de la file est inconnu, mais pause et reprise restent possibles.
    SectionHeader("Téléchargements") {
        Row {
            pauseChoices(d).forEach { (label, action) ->
                TextButton(
                    onClick = {
                        sending = action
                        scope.launch {
                            try {
                                onControl(action)
                            } finally {
                                sending = null
                            }
                        }
                    },
                    enabled = sending == null,
                ) { Text(if (sending == action) "Envoi…" else label) }
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
private fun Requests(m: Medias) {
    SectionHeader("Demandes")
    sectionIssue(m, REQUESTS)?.let { SectionDown(it) }
    m.requests?.let { list ->
        if (list.isEmpty()) Caption("Aucune demande suivie.")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { list.forEach { RequestRow(it) } }
    }
}

/**
 * Recherche d'un titre à demander. L'état d'un résultat est une pastille (« déjà là »,
 * « déjà demandé ») ; seul « Demander » est un bouton. L'état après une demande est celui
 * que le module rend. Messages dans le panneau (la file commune est sous lui).
 */
@Composable
private fun AskPanel(
    search: suspend (String) -> List<MediaResult>,
    request: suspend (MediaResult) -> String,
    onRequested: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf(emptyMap<String, Ask>()) }
    var sending by remember { mutableStateOf(emptySet<String>()) }
    fun run() {
        if (!canSearch(query) || searching) return
        searching = true
        note = null
        scope.launch {
            try {
                results = search(query.trim())
            } catch (_: ApiError.Unauthorized) {
                onUnauthorized()
            } catch (e: ApiError) {
                note = refusal(e)
            } finally {
                searching = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("Demander un titre", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
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
        note?.let { Caption(it, Modifier.padding(bottom = 6.dp)) }
        val shown = results ?: return@Column
        if (shown.isEmpty()) Caption("Aucun résultat.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { r ->
                val k = askKey(r)
                val state = askState(r, asked)
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Caption(resultNote(r))
                    }
                    Spacer(Modifier.width(8.dp))
                    if (state.enabled) {
                        OutlinedButton(
                            onClick = {
                                sending = sending + k
                                note = null
                                scope.launch {
                                    try {
                                        asked = asked + (k to askAfter(request(r)))
                                        onRequested()
                                    } catch (_: ApiError.Unauthorized) {
                                        onUnauthorized()
                                    } catch (e: ApiError) {
                                        note = refusal(e)
                                    } finally {
                                        sending = sending - k
                                    }
                                }
                            },
                            enabled = k !in sending,
                            modifier = Modifier.semantics { contentDescription = "Demander : ${r.title}" },
                        ) { Text(if (k in sending) "Envoi…" else state.label) }
                    } else {
                        StatePill(state.label.lowercase())
                    }
                }
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
