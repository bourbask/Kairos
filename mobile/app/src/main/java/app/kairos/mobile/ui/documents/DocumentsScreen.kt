package app.kairos.mobile.ui.documents

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.DocHit
import app.kairos.mobile.data.DocPiece
import app.kairos.mobile.data.Documents
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.finances.syncLabel
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.kit.color
import app.kairos.mobile.ui.maison.ChevronBackIcon
import app.kairos.mobile.ui.talk.IconButton
import app.kairos.mobile.ui.today.refusal
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

private val RowCorner = 16.dp
private val HITS = ListSerializer(DocHit.serializer())

/**
 * Espace Documents, lecture seule : échéances (retards d'abord, ambre sous 30 jours, brique
 * en retard ; nature privée réduite à « document privé »), recherche plein texte, pièces
 * attendues (manquantes d'abord), classement et avancement de l'extraction.
 * Dernier état gardé hors ligne.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    repository: Repository,
    padding: PaddingValues,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val snapshot by repository.documents.collectAsState()
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    var refreshing by remember { mutableStateOf(false) }

    fun refresh(force: Boolean = true) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refreshDocuments(force)
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(label = "Retour aux espaces", onClick = onClose) { ChevronBackIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Documents",
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
                    val d = snapshot.documents
                    when {
                        d == null -> Caption(
                            if (snapshot.issue != null) "${snapshot.issue}, et rien en mémoire pour l'instant." else "Chargement…",
                            Modifier.padding(vertical = 24.dp),
                        )
                        isEmptyIndex(d) -> {
                            Caption(EMPTY_INDEX, Modifier.padding(vertical = 24.dp))
                            Classement(d)
                        }
                        else -> {
                            Deadlines(d, LocalDate.now())
                            Search(
                                search = { q -> repository.lookup(DOCUMENTS, "app_documents_search", HITS, buildJsonObject { put("query", q) }) },
                                onUnauthorized = unauthorized,
                            )
                            Expected(d)
                            Classement(d)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
private fun Group(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

@Composable
private fun Deadlines(d: Documents, today: LocalDate) {
    SectionHeader("Échéances")
    val rows = deadlineRows(d, today)
    if (rows.isEmpty()) Caption("Aucune échéance dans les 90 prochains jours.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { r -> DeadlineCard(r) }
    }
}

@Composable
private fun DeadlineCard(r: DeadlineRow) {
    val colors = MaterialTheme.colorScheme
    val accent = if (r.tone == Tone.Neutral) colors.onSurfaceVariant else r.tone.color()
    Surface(
        shape = RoundedCornerShape(RowCorner),
        color = colors.surface,
        border = BorderStroke(1.dp, if (r.tone == Tone.Neutral) colors.outlineVariant else accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.day, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            r.left?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = if (r.tone == Tone.Neutral) null else FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Search(search: suspend (String) -> List<DocHit>, onUnauthorized: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DocHit>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    fun run() {
        if (!canSearch(query) || searching) return
        searching = true
        failed = null
        results = null
        scope.launch {
            try {
                results = search(query.trim())
            } catch (_: ApiError.Unauthorized) {
                onUnauthorized()
            } catch (e: ApiError) {
                failed = refusal(e)
            } finally {
                searching = false
            }
        }
    }

    SectionHeader("Chercher")
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                // Résultats d'une autre recherche : retirés dès que la requête change.
                results = null
            },
            label = { Text("Mot, organisme, référence…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = ::run, enabled = canSearch(query) && !searching) { Text(if (searching) "…" else "Chercher") }
    }
    failed?.let { Caption(it, Modifier.padding(bottom = 6.dp)) }
    val shown = results ?: return
    if (shown.isEmpty()) Caption("Aucun résultat.")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { h -> HitRow(h) }
    }
}

@Composable
private fun HitRow(h: DocHit) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(h.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        hitNote(h).takeIf { it.isNotEmpty() }?.let { Caption(it) }
        if (h.passage.isNotBlank()) {
            Text(
                buildAnnotatedString {
                    passageSpans(h.passage).forEach { (text, bold) ->
                        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) } else append(text)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Expected(d: Documents) {
    val p = pieces(d)
    SectionHeader("Pièces attendues")
    if (p.missing.isEmpty() && p.present.isEmpty()) return Caption("Aucune pièce attendue.")
    Caption(piecesHeadline(p), Modifier.padding(bottom = 4.dp))
    if (p.missing.isNotEmpty()) {
        Group("À fournir")
        p.missing.forEach { PieceRow(it) }
    }
    if (p.present.isNotEmpty()) {
        Group("Présentes")
        p.present.forEach { PieceRow(it) }
    }
}

@Composable
private fun PieceRow(p: DocPiece) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (p.present) "✓" else "•",
            color = if (p.present) colors.primary else colors.tertiary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp),
        )
        Text(p.item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Caption(p.domain)
    }
}

@Composable
private fun Classement(d: Documents) {
    if (d.natures.isEmpty() && d.extraction.total == 0) return
    SectionHeader("Classement")
    if (d.extraction.total > 0) Caption(extractionLabel(d.extraction), Modifier.padding(bottom = 6.dp))
    d.natures.forEach { n ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Text(n.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Caption(natureNote(n))
        }
    }
}
