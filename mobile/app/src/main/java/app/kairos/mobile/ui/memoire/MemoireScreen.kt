package app.kairos.mobile.ui.memoire

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Card
import app.kairos.mobile.data.ConversationSummary
import app.kairos.mobile.data.Fact
import app.kairos.mobile.data.FactValue
import app.kairos.mobile.data.Intention
import app.kairos.mobile.data.JournalEntry
import app.kairos.mobile.data.Mark
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.finances.syncLabel
import app.kairos.mobile.ui.kit.LocalAmountsMasked
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.overlay.Origin
import app.kairos.mobile.ui.overlay.Overlay
import app.kairos.mobile.ui.overlay.anchorTo
import app.kairos.mobile.ui.overlay.rememberOverlayState
import app.kairos.mobile.ui.overlay.underOverlays
import app.kairos.mobile.ui.today.DoneOutcome
import app.kairos.mobile.ui.today.InFlight
import app.kairos.mobile.ui.kit.SwipeRow
import app.kairos.mobile.ui.today.completeCard
import app.kairos.mobile.ui.today.refusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import app.kairos.mobile.ui.finances.issueBanner
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.LocalNotifier
import app.kairos.mobile.ui.kit.MaskToggle
import app.kairos.mobile.ui.kit.PrimaryAction
import app.kairos.mobile.ui.kit.SpaceHeader
import app.kairos.mobile.ui.kit.SpaceScaffold
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime

private val RowCorner = 16.dp
private val JOURNAL = ListSerializer(JournalEntry.serializer())
private val CONVERSATIONS = ListSerializer(ConversationSummary.serializer())
private val HISTORY = ListSerializer(FactValue.serializer())

/**
 * Espace Mémoire : intentions à traiter (glisser → accepter, ← refuser, bouton « plus tard »,
 * ajout rapide), journal (entrées sensibles masquées, recherche, note), faits par thème
 * (tap = historique, correction, oubli), conversations résumées (recherche). Journal, faits
 * et conversations s'effacent définitivement, après une fenêtre d'annulation. Dernier état
 * gardé hors ligne, sans les entrées sensibles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoireScreen(
    repository: Repository,
    padding: PaddingValues,
    appScope: CoroutineScope,
    onToggleMasked: () -> Unit,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val snapshot by repository.memoire.collectAsState()
    val marks by repository.marks.collectAsState()
    val masked = LocalAmountsMasked.current
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val notifier = LocalNotifier.current
    val scroll = rememberScrollState()
    var noting by rememberSaveable { mutableStateOf(false) }
    // ponytail: résultats en mémoire vive seulement, jamais écrits sur l'appareil.
    var found by remember { mutableStateOf<Found?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(emptySet<Long>()) }
    var revealed by remember { mutableStateOf(emptySet<Long>()) }
    // Éléments en cours d'effacement ou effacés : jamais réaffichés, recherche comprise.
    var erased by remember { mutableStateOf(emptySet<String>()) }
    var factKey by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = remember { InFlight() }
    val detail = rememberOverlayState(factKey != null, Origin.Anchor)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val today = LocalDate.now().toString()
    val later = laterIds(marks[today].orEmpty().keys)

    fun refresh(force: Boolean = true) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refreshMemoire(force)
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

    suspend fun undoWindow(text: String): Boolean = notifier.undo(text)

    fun notice(text: String) {
        scope.launch { notifier.show(text) }
    }

    /** Écriture ponctuelle (ajout, correction) ; rend vrai si le module l'a prise. */
    suspend fun write(tool: String, args: kotlinx.serialization.json.JsonObject): Boolean = try {
        notice(repository.tool(MEMOIRE, tool, args))
        repository.refreshMemoire()
        true
    } catch (_: ApiError.Unauthorized) {
        unauthorized()
        false
    } catch (e: ApiError) {
        notice(refusal(e))
        false
    }

    // Depuis appScope : quitter l'espace pendant la fenêtre n'abandonne pas le geste.
    fun decide(i: Intention, state: String) {
        appScope.launch {
            val out = try {
                completeCard(
                    intentionCard(i, state),
                    null,
                    mark = { m -> hidden = if (m == null) hidden - i.id else hidden + i.id },
                    undoWindow = { undoWindow("${if (state == ACCEPT) "Acceptée" else "Refusée"} · ${i.what}") },
                    call = repository::tool,
                    busy = busy,
                )
            } catch (_: ApiError.Unauthorized) {
                return@launch unauthorized()
            }
            when (out) {
                is DoneOutcome.Done -> {
                    // Traitée (acceptée, refusée, échue) : le module ne la renvoie plus, elle quitte la liste.
                    repository.dropFromMemoire { withoutIntention(it, i.id) }
                    try {
                        repository.refreshMemoire()
                    } catch (_: ApiError.Unauthorized) {
                        return@launch unauthorized()
                    }
                    if (repository.memoire.value.issue == null) hidden = hidden - i.id
                    // Seul l'imprévu s'annonce : un message de confirmation retarderait l'annulation du geste suivant.
                    if (out.message == STALE) notifier.show(out.message)
                }
                is DoneOutcome.Refused -> notifier.show(out.message)
                else -> Unit
            }
        }
    }

    /** Effacement définitif après la fenêtre d'annulation ; refus du module : l'élément revient. */
    fun erase(c: Card, label: String) {
        appScope.launch {
            val out = try {
                completeCard(
                    c,
                    null,
                    mark = { m -> erased = if (m == null) erased - c.id else erased + c.id },
                    undoWindow = { undoWindow(label) },
                    call = repository::tool,
                    busy = busy,
                )
            } catch (_: ApiError.Unauthorized) {
                return@launch unauthorized()
            }
            when (out) {
                is DoneOutcome.Done -> {
                    repository.dropFromMemoire { withoutErased(it, c) }
                    try {
                        repository.refreshMemoire()
                    } catch (_: ApiError.Unauthorized) {
                        unauthorized()
                    }
                }
                is DoneOutcome.Refused -> notifier.show(out.message)
                else -> Unit
            }
        }
    }

    /** « Plus tard » : cachée jusqu'à demain sur cet appareil, rien n'est envoyé. */
    fun postpone(i: Intention) {
        appScope.launch {
            completeCard(
                intentionCard(i, "").copy(done = null),
                null,
                mark = { m -> repository.mark(today, laterKey(i.id), m?.let { Mark.PlusTard }) },
                undoWindow = { undoWindow("Plus tard · ${i.what}") },
                call = { _, _, _ -> "" },
                busy = busy,
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant)) {
        SpaceScaffold(
            "Mémoire",
            onClose,
            padding,
            modifier = Modifier.underOverlays(rtl, detail),
            scrolled = scroll.canScrollBackward,
            actions = { MaskToggle(masked, onToggleMasked) },
            banner = snapshot.issue?.let { issue ->
                {
                    val (title, detail) = issueBanner("Mémoire", issue, snapshot.fetchedAt?.let { "Dernier état : ${syncLabel(it)}" })
                    StatusBanner(title, detail = detail, actionLabel = "Réessayer", onAction = { refresh() })
                }
            },
            primaryAction = if (snapshot.memory != null && !noting) {
                {
                    PrimaryAction("Noter", {
                        noting = true
                        // La note s'écrit en haut de page.
                        scope.launch { scroll.animateScrollTo(0) }
                    })
                }
            } else {
                null
            },
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
                    val memory = snapshot.memory
                    if (memory == null) {
                        // L'indisponibilité est dite par le bandeau : ici, seulement l'absence de données connues.
                        Caption(if (snapshot.issue != null) "Rien en mémoire pour l'instant." else "Chargement…", Modifier.padding(vertical = 24.dp))
                    } else {
                        if (noting) {
                            SectionHeader("Nouvelle note") { TextButton(onClick = { noting = false }) { Text("Fermer") } }
                            Composer("Note", "Enregistrer", ::notice) { text ->
                                write("app_journal_add", buildJsonObject { put("text", text.trim()) }).also { if (it) noting = false }
                            }
                        }
                        SearchField(
                            "Chercher dans le journal et les conversations",
                            search = { q ->
                                coroutineScope {
                                    val j = async { repository.lookup(MEMOIRE, "app_journal_search", JOURNAL, buildJsonObject { put("query", q) }) }
                                    val c = async { repository.lookup(MEMOIRE, "app_conversation_search", CONVERSATIONS, buildJsonObject { put("query", q) }) }
                                    Found(j.await(), c.await())
                                }
                            },
                            onNote = ::notice,
                            onUnauthorized = unauthorized,
                        ) { found = it }
                        val onReveal = { id: Long -> revealed = if (id in revealed) revealed - id else revealed + id }
                        val onDeleteEntry = { id: Long -> erase(journalErasure(id), "Entrée supprimée") }
                        val onDeleteConversation = { id: Long -> erase(conversationErasure(id), "Conversation supprimée") }
                        val hits = found
                        if (hits != null) {
                            Results(hits, erased, revealed, masked, onReveal, onDeleteEntry, onDeleteConversation)
                        }
                        val now = LocalDateTime.now()
                        Intentions(
                            openIntentions(memory.intentions, now, later).filter { it.id !in hidden },
                            now,
                            onAccept = { decide(it, ACCEPT) },
                            onRefuse = { decide(it, REFUSE) },
                            onLater = ::postpone,
                            onAdd = { what, days ->
                                write("app_intention_add", buildJsonObject {
                                    put("what", what.trim())
                                    put("days", days)
                                })
                            },
                            onNote = ::notice,
                        )
                        Journal(memory.journal, erased, revealed, masked, onReveal, onDeleteEntry)
                        Facts(memory.facts.filterNot { factErasure(it.key).id in erased }) { fact, at ->
                            at?.let { detail.anchorTo(it, RowCorner) }
                            factKey = fact.key
                        }
                        Conversations(memory.conversations, erased, onDeleteConversation)
                    }
                }
            }
        }
        Overlay(detail, factKey != null, onClose = { factKey = null }, anchorColor = MaterialTheme.colorScheme.surface) {
            // Gardé pendant la fermeture : le panneau ne se vide pas en se refermant.
            val last = remember { arrayOfNulls<String>(1) }
            factKey?.let { last[0] = it }
            last[0]?.let { key ->
                FactPanel(
                    key,
                    current = snapshot.memory?.facts?.firstOrNull { it.key == key }?.value.orEmpty(),
                    padding = padding,
                    history = { repository.lookup(MEMOIRE, "app_fact_history", HISTORY, buildJsonObject { put("key", key) }) },
                    onSave = { value -> write("app_fact_set", factSetArgs(key, value)) },
                    onForget = {
                        factKey = null
                        erase(factErasure(key), "Fait oublié · ${factLabel(key)}")
                    },
                    onUnauthorized = unauthorized,
                    onClose = { factKey = null },
                )
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/** Dictée par la reconnaissance vocale du téléphone ; sans elle, le clavier reste. */
@Composable
private fun rememberDictation(prompt: String, onText: (String) -> Unit, onNote: (String) -> Unit): () -> Unit {
    val text by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(text)
    }
    return {
        try {
            launcher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                    // Notes personnelles : reconnaissance sur l'appareil quand elle est disponible.
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
            )
        } catch (_: ActivityNotFoundException) {
            onNote("Pas de dictée sur ce téléphone : écris plutôt.")
        }
    }
}

/** Champ de saisie avec dictée et bouton d'envoi ; vidé quand l'envoi a abouti. */
@Composable
private fun Composer(
    label: String,
    action: String,
    onNote: (String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
    onSend: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val dictate = rememberDictation(label, onText = { text = if (text.isBlank()) it else "$text $it" }, onNote = onNote)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        extra?.invoke()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = dictate) { Text("Dicter") }
            Button(
                onClick = {
                    sending = true
                    scope.launch {
                        if (onSend(text)) text = ""
                        sending = false
                    }
                },
                enabled = text.isNotBlank() && !sending,
            ) { Text(if (sending) "Envoi…" else action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intentions(
    list: List<Intention>,
    now: LocalDateTime,
    onAccept: (Intention) -> Unit,
    onRefuse: (Intention) -> Unit,
    onLater: (Intention) -> Unit,
    onAdd: suspend (String, Int) -> Boolean,
    onNote: (String) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableStateOf(DEADLINES[1].second) }
    SectionHeader("Intentions") { TextButton(onClick = { adding = !adding }) { Text(if (adding) "Fermer" else "Ajouter") } }
    if (adding) {
        Composer(
            "Intention",
            "Ajouter",
            onNote,
            extra = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DEADLINES.forEach { (label, d) -> FilterChip(selected = d == days, onClick = { days = d }, label = { Text(label) }) }
                }
            },
        ) { what -> onAdd(what, days).also { if (it) adding = false } }
    }
    if (list.isEmpty()) Caption("Rien à traiter pour l'instant.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { i -> key(i.id) { IntentionRow(i, now, onAccept, onRefuse, onLater) } }
    }
}

/** Intention : glisser → accepter, ← refuser ; les mêmes gestes en boutons, plus « plus tard ». */
@Composable
private fun IntentionRow(i: Intention, now: LocalDateTime, onAccept: (Intention) -> Unit, onRefuse: (Intention) -> Unit, onLater: (Intention) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SwipeRow(
        enabled = true,
        onRight = { onAccept(i) },
        onLeft = { onRefuse(i) },
        rightLabel = "Accepter ✓",
        leftLabel = "Refuser",
        leftColor = colors.error,
    ) {
        Surface(
            shape = RoundedCornerShape(RowCorner),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(i.what, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    i.constraints?.let { Caption(it) }
                    Text(
                        listOfNotNull(timeLeft(i.expires, now), stateLabel(i.state).ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAccept(i) }, Modifier.semantics { contentDescription = "Accepter : ${i.what}" }) { Text("Accepter") }
                    TextButton(onClick = { onRefuse(i) }, Modifier.semantics { contentDescription = "Refuser : ${i.what}" }) { Text("Refuser", color = colors.error) }
                    TextButton(onClick = { onLater(i) }, Modifier.semantics { contentDescription = "Plus tard : ${i.what}" }) { Text("Plus tard") }
                }
            }
        }
    }
}

/** Recherche à la demande ; null = pas de recherche en cours, la liste récente s'affiche. */
/** Résultats de la recherche unique, par type ; null = pas de recherche en cours. */
private data class Found(val journal: List<JournalEntry>, val conversations: List<ConversationSummary>)

/** Recherche à la demande ; null = pas de recherche en cours. */
@Composable
private fun <T> SearchField(
    label: String,
    search: suspend (String) -> T,
    onNote: (String) -> Unit,
    onUnauthorized: () -> Unit,
    onResults: (T?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    fun run() {
        val q = query.trim()
        if (q.isEmpty()) return onResults(null)
        scope.launch {
            try {
                onResults(search(q))
            } catch (_: ApiError.Unauthorized) {
                onUnauthorized()
            } catch (e: ApiError) {
                onNote(refusal(e))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) onResults(null)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = ::run, enabled = query.isNotBlank()) { Text("Chercher") }
        if (query.isNotEmpty()) {
            TextButton(onClick = {
                query = ""
                onResults(null)
            }) { Text("Effacer") }
        }
    }
}

@Composable
private fun Results(
    found: Found,
    erased: Set<String>,
    revealed: Set<Long>,
    masked: Boolean,
    onReveal: (Long) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
) {
    val entries = found.journal.filterNot { journalErasure(it.id).id in erased }
    val talks = found.conversations.filterNot { conversationErasure(it.id).id in erased }
    SectionHeader("Journal · ${entries.size} trouvée${if (entries.size > 1) "s" else ""}")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { e -> EntryRow(e, revealed, masked, onReveal, onDeleteEntry) }
    }
    SectionHeader("Conversations · ${talks.size} trouvée${if (talks.size > 1) "s" else ""}")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        talks.forEach { c -> ConversationRow(c, onDeleteConversation) }
    }
}

@Composable
private fun Journal(
    recent: List<JournalEntry>,
    erased: Set<String>,
    revealed: Set<Long>,
    masked: Boolean,
    onReveal: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    SectionHeader("Journal")
    val shown = recent.filterNot { journalErasure(it.id).id in erased }
    if (shown.isEmpty()) Caption("Journal vide pour l'instant.")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { e -> EntryRow(e, revealed, masked, onReveal, onDelete) }
    }
}

@Composable
private fun EntryRow(e: JournalEntry, revealed: Set<Long>, masked: Boolean, onReveal: (Long) -> Unit, onDelete: (Long) -> Unit) {
    val text = shownText(e, revealed, masked)
    val at = syncLabel(e.at)
    // Sensible : un tap l'affiche ou la remasque ; jamais quand les montants sont masqués.
    val tap = if (e.sensitive && !masked) {
        Modifier.clickable(onClickLabel = if (text == null) "Afficher l'entrée" else "Masquer l'entrée") { onReveal(e.id) }
    } else {
        Modifier
    }
    Column(Modifier.fillMaxWidth().then(tap).semantics(mergeDescendants = true) {}) {
        Caption(listOfNotNull(at, e.kind.takeIf { it.isNotBlank() && it != "note" }).joinToString(" · "))
        if (text == null) {
            Text(
                if (masked) "Entrée sensible · masquée" else "Entrée sensible · touche pour afficher",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
    // Visible seulement quand le texte l'est : une entrée sensible se révèle d'abord.
    if (text != null) {
        TextButton(onClick = { onDelete(e.id) }, Modifier.semantics { contentDescription = "Supprimer l'entrée du $at" }) {
            Text("Supprimer", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** En-tête de section repliable, avec son compte ; annoncé comme titre, état dit. */
@Composable
private fun FoldHeader(title: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = if (open) "Replier" else "Déplier", onClick = onToggle)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = if (open) "déplié" else "replié"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${title.uppercase()} · $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            KairosIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (open) 180f else 0f),
        )
    }
}

@Composable
private fun Facts(facts: List<Fact>, onOpen: (Fact, LayoutCoordinates?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    FoldHeader("Ce que Kairos sait de toi", facts.size, open) { open = !open }
    if (!open) return
    if (facts.isEmpty()) Caption("Aucun fait connu pour l'instant.")
    factGroups(facts).forEach { g ->
        Text(g.theme, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.facts.forEach { f ->
                var at by remember(f.key) { mutableStateOf<LayoutCoordinates?>(null) }
                Surface(
                    onClick = { onOpen(f, at) },
                    shape = RoundedCornerShape(RowCorner),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { at = it },
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(factLabel(f.key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                        Text(f.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(KairosIcons.ChevronForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Conversations(recent: List<ConversationSummary>, erased: Set<String>, onDelete: (Long) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = recent.filterNot { conversationErasure(it.id).id in erased }
    FoldHeader("Conversations", shown.size, open) { open = !open }
    if (!open) return
    if (shown.isEmpty()) Caption("Aucune conversation pour l'instant.")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.forEach { c -> ConversationRow(c, onDelete) }
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, onDelete: (Long) -> Unit) {
    val at = syncLabel(c.at)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Caption(at)
        Text(c.summary ?: "Pas encore résumée.", style = MaterialTheme.typography.bodyMedium, fontStyle = if (c.summary == null) FontStyle.Italic else null)
        if (c.topics.isNotEmpty()) Caption("Sujets : ${c.topics.joinToString(", ")}")
        c.decisions.forEach { d -> Text("→ $d", style = MaterialTheme.typography.bodySmall) }
    }
    TextButton(onClick = { onDelete(c.id) }, Modifier.semantics { contentDescription = "Supprimer la conversation du $at" }) {
        Text("Supprimer", color = MaterialTheme.colorScheme.error)
    }
}

/** Fait : valeur courante, historique (relu à l'ouverture) et correction. */
@Composable
private fun FactPanel(
    key: String,
    current: String,
    padding: PaddingValues,
    history: suspend () -> List<FactValue>,
    onSave: suspend (String) -> Boolean,
    onForget: () -> Unit,
    onUnauthorized: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var values by remember(key) { mutableStateOf<List<FactValue>?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    var reload by remember(key) { mutableStateOf(0) }
    var draft by rememberSaveable(key) { mutableStateOf(current) }
    var tried by remember(key) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(key, reload) {
        try {
            values = history()
            failed = false
        } catch (_: ApiError.Unauthorized) {
            onUnauthorized()
        } catch (_: ApiError) {
            failed = true
        }
    }
    val error = factEditError(draft, current)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = padding.calculateTopPadding()),
    ) {
        SpaceHeader(factLabel(key).replaceFirstChar { it.titlecase() }, onClose, backLabel = "Retour à la mémoire")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Caption("Valeur actuelle")
            Text(current, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SectionHeader("Corriger")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Nouvelle valeur") },
                isError = tried && error != null,
                supportingText = (error?.takeIf { tried })?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    tried = true
                    if (error == null) {
                        saving = true
                        scope.launch {
                            if (onSave(draft)) {
                                tried = false
                                reload++
                            }
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "Envoi…" else "Corriger") }
            OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Oublier ce fait", color = MaterialTheme.colorScheme.error)
            }
            Caption("La valeur et tout son historique sont effacés.")
            SectionHeader("Historique")
            val v = values
            when {
                v == null && failed -> StatusBanner("Historique indisponible pour l'instant.")
                v == null -> Caption("Chargement…")
                v.isEmpty() -> Caption("Pas d'historique.")
                else -> v.asReversed().forEach { h ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                        Text(h.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Caption(syncLabel(h.at))
                    }
                }
            }
        }
    }
}
