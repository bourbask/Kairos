package app.kairos.mobile.ui.finances

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.kairos.mobile.data.Affordability
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.FinanceCycle
import app.kairos.mobile.data.FinanceWealth
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.kit.Amount
import app.kairos.mobile.ui.kit.Bar
import app.kairos.mobile.ui.kit.Donut
import app.kairos.mobile.ui.kit.Gauge
import app.kairos.mobile.ui.kit.LineChart
import app.kairos.mobile.ui.kit.Metric
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.Tone
import app.kairos.mobile.ui.kit.amountText
import app.kairos.mobile.ui.kit.color
import app.kairos.mobile.ui.kit.donutColors
import app.kairos.mobile.ui.maison.ChevronBackIcon
import app.kairos.mobile.ui.talk.IconButton
import app.kairos.mobile.ui.theme.Motion
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Page(val label: String) { Cycle("Cycle"), Wealth("Patrimoine") }

/**
 * Espace Finances : onglets Cycle (ouvert par défaut) et Patrimoine, tirer pour
 * actualiser, « Puis-je me permettre ? » en panneau du bas. Dernier état gardé hors
 * ligne. [masked] (l'œil de l'en-tête) masque tous les montants, réglage partagé.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinancesScreen(
    repository: Repository,
    padding: PaddingValues,
    masked: Boolean,
    onToggleMasked: () -> Unit,
    onClose: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val snapshot by repository.finances.collectAsState()
    val scope = rememberCoroutineScope()
    val unauthorized by rememberUpdatedState(onUnauthorized)
    var refreshing by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Page.Cycle) }
    var affordOpen by rememberSaveable { mutableStateOf(false) }

    fun refresh(force: Boolean = true) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refreshFinances(force)
            } catch (_: ApiError.Unauthorized) {
                unauthorized()
            } finally {
                refreshing = false
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        // Retour rapide (autre application, écran éteint) : pas de nouvel appel.
        refresh(force = false)
        onPauseOrDispose {}
    }

    // Montants visibles : ni capture d'écran ni aperçu dans les applications récentes.
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity, masked) {
        if (!masked) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val cycle = snapshot.cycle
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Header(masked, onToggleMasked, onClose)
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                Page.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxWidth().weight(1f)) {
                AnimatedContent(tab, transitionSpec = { Motion.fadeThrough() }, label = "tab") { t ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(top = 8.dp, bottom = padding.calculateBottomPadding() + 88.dp),
                    ) {
                        // Bandeaux de la source de cet onglet seulement.
                        val source = if (t == Page.Cycle) {
                            sourceBanner(snapshot.cycleIssue, snapshot.cycleAt)
                        } else {
                            sourceBanner(snapshot.wealthIssue, snapshot.wealthAt)
                        }
                        val fresh = freshnessBanner(if (t == Page.Cycle) cycle?.freshness else snapshot.wealth?.freshness)
                        listOfNotNull(source, fresh).forEach { (title, detail) ->
                            StatusBanner(title, detail = detail)
                            Spacer(Modifier.height(8.dp))
                        }
                        when (t) {
                            Page.Cycle -> cycle?.let { CycleContent(it, masked) } ?: Hint(snapshot.cycleIssue)
                            Page.Wealth -> snapshot.wealth?.let { WealthContent(it, masked) } ?: Hint(snapshot.wealthIssue)
                        }
                    }
                }
            }
        }
        if (tab == Page.Cycle && cycle != null) {
            ExtendedFloatingActionButton(
                onClick = { affordOpen = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 8.dp),
            ) { Text("Puis-je me permettre ?") }
        }
        if (affordOpen) {
            ModalBottomSheet(onDismissRequest = { affordOpen = false }) {
                AffordPanel(repository, cycle?.postes.orEmpty(), masked, unauthorized)
            }
        }
    }
}

@Composable
private fun Hint(issue: String?) {
    Text(
        if (issue != null) "$issue, et rien en mémoire pour l'instant." else "Chargement…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun Header(masked: Boolean, onToggleMasked: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(label = "Retour aux espaces", onClick = onClose) { ChevronBackIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(4.dp))
        Text(
            "Finances",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            label = if (masked) "Afficher les montants" else "Masquer les montants",
            state = if (masked) "montants masqués" else "montants visibles",
            onClick = onToggleMasked,
        ) { EyeIcon(MaterialTheme.colorScheme.onSurfaceVariant, crossed = masked) }
    }
}

/** Œil ; barré quand les montants sont masqués. */
@Composable
private fun EyeIcon(color: Color, crossed: Boolean) = Canvas(Modifier.size(24.dp)) {
    val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
    val w = size.width
    val h = size.height
    val eye = Path().apply {
        moveTo(w * 0.06f, h * 0.5f)
        quadraticTo(w * 0.5f, h * 0.05f, w * 0.94f, h * 0.5f)
        quadraticTo(w * 0.5f, h * 0.95f, w * 0.06f, h * 0.5f)
        close()
    }
    drawPath(eye, color, style = s)
    drawCircle(color, radius = w * 0.13f, center = Offset(w / 2, h / 2))
    if (crossed) drawLine(color, Offset(w * 0.15f, h * 0.12f), Offset(w * 0.85f, h * 0.88f), s.width, StrokeCap.Round)
}

@Composable
private fun CycleContent(c: FinanceCycle, masked: Boolean) {
    for (section in cycleSections(c)) when (section) {
        CycleSection.Summary -> {
            val available = c.available ?: 0.0
            Metric(
                amountText(available, masked, cents = false),
                "Reste à vivre",
                tone = if (available < 0) Tone.Urgent else null,
                caption = c.daysLeft?.let { "$it jour${if (it > 1) "s" else ""} restant${if (it > 1) "s" else ""}" },
            )
            val today = c.today?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: LocalDate.now()
            cycleProgress(c.start, c.end, today)?.let { p ->
                Spacer(Modifier.height(12.dp))
                Gauge(p, "Avancement du cycle", marker = p)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Caption(dayLabel(c.start).orEmpty())
                    Caption(dayLabel(c.end).orEmpty())
                }
            }
            c.balance?.let { b ->
                Spacer(Modifier.height(8.dp))
                LabeledAmount("Solde du compte", b)
            }
        }
        CycleSection.Envelopes -> {
            SectionHeader("Enveloppes")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                envelopesByPressure(c.envelopes).forEach { Bar(it.poste, it.spent, it.planned) }
            }
        }
        CycleSection.Charges -> {
            SectionHeader("Charges à venir")
            c.charges.forEach { ch -> LabeledAmount(ch.name, ch.amount, caption = dayLabel(ch.date)) }
        }
        CycleSection.Cushion -> {
            val cushion = c.cushion!!
            val below = cushion.balance < cushion.floor
            SectionHeader("Coussin")
            LabeledAmount("Solde", cushion.balance)
            LabeledAmount("Plancher", cushion.floor)
            Text(
                if (below) "Sous le plancher" else "Au-dessus du plancher",
                style = MaterialTheme.typography.bodySmall,
                color = (if (below) Tone.Urgent else Tone.Neutral).color(),
                fontWeight = FontWeight.SemiBold,
            )
        }
        CycleSection.Alerts -> {
            SectionHeader("Alertes")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.alerts.orEmpty().forEach { a ->
                    // Texte libre du module : peut contenir des montants.
                    val detail = listOfNotNull(a.details.ifBlank { null }, a.remedy).joinToString("\n").ifBlank { null }
                    StatusBanner(maskDigits(a.title, masked), detail = detail?.let { maskDigits(it, masked) })
                }
            }
        }
    }
}

@Composable
private fun WealthContent(w: FinanceWealth, masked: Boolean) {
    val sections = wealthSections(w)
    if (sections.isEmpty()) {
        Text("Patrimoine indisponible pour l'instant.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for (section in sections) when (section) {
        WealthSection.NetWorth -> Metric(
            amountText(w.netWorth ?: 0.0, masked, cents = false),
            "Patrimoine net",
            caption = w.debt?.let { "Dettes ${amountText(it, masked, cents = false)}" },
        )
        WealthSection.History -> {
            val history = w.history.orEmpty()
            SectionHeader("Évolution")
            LineChart(
                "Patrimoine net mensuel",
                history.map { it.netWorth },
                startLabel = monthLabel(history.first().month),
                endLabel = monthLabel(history.last().month),
            )
        }
        WealthSection.Loans -> {
            SectionHeader("Prêts")
            w.loans.orEmpty().forEach { l ->
                val caption = listOfNotNull(
                    l.payment?.let { "mensualité ${amountText(it, masked)}" },
                    l.payoffMonth?.let { "fin ${monthLabel(it)}" },
                ).joinToString(" · ").ifBlank { null }
                LabeledAmount(l.name, l.balance, caption = caption)
            }
        }
        WealthSection.Portfolio -> {
            val p = w.portfolio!!
            SectionHeader("Portefeuille") { p.total?.let { Amount(it, cents = false, style = MaterialTheme.typography.labelLarge) } }
            if (p.buckets.isNotEmpty()) {
                val colors = donutColors()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Donut("Répartition par risque", p.buckets.map { bucketLabel(it.name) to it.amount }, diameter = 120.dp, colors = colors)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        p.buckets.forEachIndexed { i, b ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                                Box(Modifier.size(10.dp).background(colors[i % colors.size], CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(bucketLabel(b.name), style = MaterialTheme.typography.bodyMedium)
                                    Amount(b.amount, cents = false, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (p.lines.isNotEmpty()) Spacer(Modifier.height(12.dp))
            p.lines.forEach { line ->
                LabeledAmount(line.name, line.amount, caption = line.bucket?.let(::bucketLabel))
            }
        }
    }
}

/** Ligne libellé / montant, détail facultatif dessous (sans montant fourni par l'appelant). */
@Composable
private fun LabeledAmount(label: String, value: Double?, caption: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            caption?.let { Caption(it) }
        }
        Spacer(Modifier.width(12.dp))
        if (value == null) Text("—", style = MaterialTheme.typography.bodyMedium) else Amount(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * Saisie d'un montant (refusée sur place si illisible : rien ne part), poste au
 * choix en puces, verdict du module en fondu enchaîné.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AffordPanel(repository: Repository, postes: List<String>, masked: Boolean, onUnauthorized: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var poste by rememberSaveable { mutableStateOf<String?>(null) }
    var verdict by remember { mutableStateOf<Affordability?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val amount = parseAmount(input)
    val invalid = input.isNotBlank() && amount == null

    fun ask() {
        val a = amount ?: return
        busy = true
        error = null
        scope.launch {
            try {
                verdict = repository.afford(a, poste)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                verdict = null
                error = when (e) {
                    ApiError.Unauthorized -> return@launch onUnauthorized()
                    ApiError.Offline -> "Serveur injoignable."
                    ApiError.NoReply -> "Pas de réponse du serveur."
                    is ApiError.Server -> if (e.code == 422) e.message else "${e.unavailable()}."
                }
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Puis-je me permettre ?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it.take(16)
                verdict = null
            },
            label = { Text("Montant") },
            suffix = { Text("€") },
            singleLine = true,
            isError = invalid,
            // Verrouillé pendant la vérification : le verdict reçu correspond toujours à la saisie.
            enabled = !busy,
            supportingText = if (invalid) ({ Text("Un montant positif, par exemple 12,50") }) else null,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        if (postes.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                postes.forEach { p ->
                    FilterChip(
                        selected = poste == p,
                        onClick = {
                            poste = if (poste == p) null else p
                            verdict = null
                        },
                        label = { Text(p) },
                        enabled = !busy,
                    )
                }
            }
        }
        Button(onClick = ::ask, enabled = amount != null && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Vérification…" else "Vérifier")
        }
        error?.let { Text(maskDigits(it, masked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        AnimatedContent(verdict, transitionSpec = { Motion.fadeThrough() }, label = "verdict") { v ->
            if (v != null) Verdict(v, masked)
        }
    }
}

@Composable
private fun Verdict(v: Affordability, masked: Boolean) {
    val tone = if (v.ok) Tone.Neutral else Tone.Urgent
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (v.ok) "Oui, ça passe" else "Non, pas en l'état",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tone.color(),
        )
        LabeledAmount("Reste à vivre après", v.after)
        v.reason?.let { Caption(maskDigits(it, masked)) }
        if (v.cuts.isNotEmpty()) {
            Text("Coupes proposées", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.cuts.forEach { LabeledAmount(it.poste ?: "Autres postes", it.amount) }
        }
    }
}
