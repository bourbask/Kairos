package app.kairos.mobile.ui.politique.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.kit.BalanceBlock
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.EvaluatorTag
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.opensBalance
import app.kairos.mobile.ui.politique.kit.LevelTag
import app.kairos.mobile.ui.politique.kit.PillOption
import app.kairos.mobile.ui.politique.kit.Pills
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.RichText
import app.kairos.mobile.ui.politique.kit.SourceButton
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.VerdictMeter
import app.kairos.mobile.ui.politique.kit.indLabel
import app.kairos.mobile.ui.politique.kit.indLevel
import app.kairos.mobile.ui.politique.kit.verdictLabel
import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.coroutines.launch

private val Mono = FontFamily.Monospace
private val Muted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Ink = Color(0xFF1F2620)
private val Attn = Color(0xFFD9952B)

// ---------- Rhétorique ----------

@Composable
private fun WhoTag(data: PolData, who: String, nav: PolNavigator, onDark: Boolean) {
    val id = data.idOf(who)
    when {
        id != null -> IdentityChip(data, id, text = who, onClick = { nav.sheet(PolSheetState.Identity(id)) })
        who.isNotBlank() -> Text(
            who.uppercase(), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (onDark) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** Exemples sourcés d'un procédé ; [onDark] : sur le verso sombre d'une carte. */
@Composable
private fun Examples(data: PolData, r: Rhet, nav: PolNavigator, onDark: Boolean) {
    val ex = remember(r) { rhetExamples(r) }
    val line = if (onDark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant
    if (ex.isEmpty()) Text("Aucun exemple sourcé pour l'instant.", Modifier.padding(top = 8.dp), fontSize = 13.5.sp, fontStyle = FontStyle.Italic)
    ex.forEachIndexed { i, e ->
        if (i > 0) Box(Modifier.fillMaxWidth().padding(top = 9.dp).height(1.dp).background(line))
        Column(Modifier.padding(top = 9.dp)) {
            WhoTag(data, e.who, nav, onDark)
            Text(frTypo(e.text), Modifier.padding(top = 4.dp), fontSize = 13.5.sp, lineHeight = 19.sp)
            e.url?.let { SourceButton(SourceRef("", it), data, {}, Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun AskBox(ask: String) {
    if (ask.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Attn).padding(14.dp)) {
        Kicker("La question à poser", color = Ink.copy(alpha = 0.75f))
        Text(frTypo(ask), Modifier.padding(top = 3.dp), color = Ink, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpotBox(spot: String) {
    val body = remember(spot) { spotBody(spot) }
    if (body.isBlank()) return
    val pc = PolColors.current
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(pc.partielBg).padding(12.dp)) {
        Kicker("À repérer", color = pc.partiel)
        RichText(body, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AussiLine(r: Rhet, modifier: Modifier = Modifier) {
    val a = aussiOf(r)
    if (a.isNotBlank()) Text("Aussi appelé : $a", modifier, fontSize = 12.5.sp, color = Muted, fontStyle = FontStyle.Italic)
}

private val CARD_HEIGHT = 440.dp

@Composable
private fun RhetCard(data: PolData, r: Rhet, rank: Int, total: Int, nav: PolNavigator, onTable: () -> Unit) {
    var flipped by rememberSaveable(r.t) { mutableStateOf(false) }
    val still = reducedMotion()
    val angle by animateFloatAsState(if (flipped) 180f else 0f, tween(if (still) 0 else 600), label = "retourner")
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    Box(Modifier.fillMaxWidth().height(CARD_HEIGHT).graphicsLayer { rotationY = angle; cameraDistance = 16f * density }) {
        if (angle <= 90f) {
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(22.dp), color = scheme.surface, tonalElevation = 1.dp, shadowElevation = 2.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text("%02d / %02d".format(rank + 1, total), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Muted)
                    Text(r.t, Modifier.padding(top = 10.dp, bottom = 6.dp).semantics { heading() }, fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold)
                    AussiLine(r, Modifier.padding(bottom = 8.dp))
                    if (r.d.isNotBlank()) Text(frTypo(r.d), fontSize = 15.sp, lineHeight = 21.sp)
                    SpotBox(r.spot)
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton({ flipped = true }, Modifier.heightIn(min = 48.dp)) { Text("Les exemples") }
                        TableLink(onTable)
                    }
                }
            }
        } else {
            val bg = if (dark) Color(0xFF1F2A36) else Color(0xFF23384A)
            val fg = if (dark) Color(0xFFF2F5EF) else Color(0xFFF7F5EF)
            Surface(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }, shape = RoundedCornerShape(22.dp), color = bg, contentColor = fg, shadowElevation = 2.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(r.t, Modifier.weight(1f), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fg.copy(alpha = 0.75f))
                        TextButton({ flipped = false }, Modifier.heightIn(min = 48.dp)) { Text("Retourner", color = fg) }
                    }
                    Examples(data, r, nav, onDark = true)
                    AskBox(r.ask)
                }
            }
        }
    }
}

@Composable
fun RhetoriqueScreen(data: PolData, nav: PolNavigator) {
    val list = remember(data) { rhetOf(data) }
    val fams = remember(list) { rhetFamilies(list) }
    val cards = remember(list) { rhetTableCards(list) }
    if (list.isEmpty()) {
        EmptyState("Aucun procédé pour l'instant", "Les techniques de rhétorique, avec leurs exemples sourcés, apparaîtront ici.", Modifier.padding(16.dp))
        return
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = ScreenPad) {
        item {
            Column {
                Lead("Les mêmes techniques reviennent d'un camp à l'autre.", "Les repérer ne dit pas qui a raison, mais aide à écouter sans se faire emporter. Retournez une carte pour ses exemples, sourcés.")
                if (fams.size > 1) Pills(
                    fams.map { PillOption(it.label, it.label, it.cards.size) }, selected = "",
                    onSelect = { k -> val j = fams.indexOfFirst { it.label == k }; if (j >= 0) scope.launch { if (still) state.scrollToItem(1 + j) else state.animateScrollToItem(1 + j) } },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        itemsIndexed(fams) { _, f ->
            Column(Modifier.fillMaxWidth().padding(top = if (fams.size > 1) 22.dp else 16.dp)) {
                if (fams.size > 1) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(f.label, Modifier.weight(1f).semantics { heading() }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Kicker(frCount(f.cards.size, "procédé", "procédés"))
                }
                Rail(f.cards, 300.dp) { _, rank -> RhetCard(data, list[rank], rank, list.size, nav) { nav.table(cards, rank) } }
            }
        }
    }
}

/** Procédé désigné par son nom (depuis un dossier) ; sans carte : feuille explicative. */
@Composable
fun RhetSheetAt(data: PolData, t: String, nav: PolNavigator, onDismiss: () -> Unit) {
    val r = remember(data, t) { rhetFind(rhetOf(data), t) }
    PolSheet(full = false, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (r == null) EmptyState("Procédé pas encore décrit", "« $t » n'a pas encore sa carte dans la rubrique Rhétorique.")
            else {
                Kicker(if (r.famille.isBlank()) "Procédé rhétorique" else r.famille)
                Text(r.t, Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 24.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold)
                AussiLine(r, Modifier.padding(top = 4.dp))
                if (r.d.isNotBlank()) Text(frTypo(r.d), Modifier.padding(top = 8.dp), fontSize = 15.sp, lineHeight = 21.sp)
                SpotBox(r.spot)
                Kicker("Exemples", Modifier.padding(top = 16.dp))
                Examples(data, r, nav, onDark = false)
                AskBox(r.ask)
            }
            FilledTonalButton({ nav.go(app.kairos.mobile.ui.politique.PolRoute.Rubric("rhetorique")) }, Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("Tous les procédés") }
        }
    }
}

// ---------- Dossiers ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DossierBlock(data: PolData, d: ContentDossier, startOpen: Boolean, nav: PolNavigator) {
    var open by rememberSaveable(d.id, d.titre) { mutableStateOf(startOpen) }
    val s = d.source
    val items = d.items
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    val relay = s.relaye_par?.takeIf { data.byId(it) != null }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = scheme.surface, tonalElevation = 1.dp) {
            Column(
                Modifier.clickable(role = Role.Button) { open = !open }.semantics { stateDescription = if (open) "Déplié" else "Replié" }.padding(14.dp),
            ) {
                Kicker(listOf(sourceTypeLabel(s.type), s.date.takeIf { it.isNotBlank() }?.let(::fmtDate)).filterNotNull().joinToString(" · "))
                Text(frTypo(d.titre), Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                val note = listOfNotNull(s.nom.takeIf { it.isNotBlank() }, relay?.let { "relayé par ${data.byId(it)?.nom}" }).joinToString(" · ")
                if (note.isNotBlank()) Text(note, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Muted)
                if (items.isNotEmpty()) {
                    StackBar(dossierBar(items).toMap(), Modifier.padding(top = 12.dp))
                    val parts = dossierTally(items).joinToString(" · ") { (v, n) -> "$n ${verdictLabel(v).lowercase()}" }
                    Text(listOf(frCount(items.size, "affirmation examinée", "affirmations examinées"), parts).filter { it.isNotBlank() }.joinToString(" · "), Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Muted)
                }
                Kicker(if (open) "Replier" else "Voir les affirmations", Modifier.padding(top = 10.dp))
            }
        }
        if (open) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Kicker("Source")
                val ev = s.ev?.let { data.evaluators[it] }
                if (ev != null) EvaluatorTag(ev, onEv)
                else Text(s.nom.ifBlank { "non identifiée" }, Modifier.clip(CircleShape).background(scheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                if (relay != null) {
                    Kicker("Relayé par")
                    IdentityChip(data, relay, onClick = { nav.sheet(PolSheetState.Identity(relay)) })
                }
            }
            if (d.contexte.isNotBlank()) Text(frTypo(d.contexte), Modifier.padding(top = 12.dp), fontSize = 14.5.sp, lineHeight = 21.sp)
            if (items.isEmpty()) EmptyState("Aucune affirmation détaillée", "Ce dossier n'a pas encore ses affirmations examinées.", Modifier.padding(top = 12.dp))
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEachIndexed { j, x -> ItemCard(data, x, j, items.size, nav) }
            }
        }
    }
}

@Composable
private fun ItemCard(data: PolData, x: ContentItem, j: Int, n: Int, nav: PolNavigator) {
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    FigCard {
        // Ouverture de la balance sur le corps seul : procédé et sources restent des boutons distincts.
        Column(Modifier.fillMaxWidth().let { m -> x.lecture?.let { l -> m.opensBalance(x.dit, nav.balance(l, x.dit, null)) } ?: m }) {
            Text("Affirmation ${j + 1} sur $n", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Muted)
            Text(frTypo(x.dit), Modifier.padding(top = 8.dp), fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                VerdictMeter(x.verdict)
                VerdictChip(x.verdict, large = true)
            }
            x.faits.filter { it.isNotBlank() }.forEach { f ->
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Muted))
                    Text(frTypo(f), fontSize = 14.5.sp, lineHeight = 21.sp)
                }
            }
            CacheBox(x.cache)
            x.lecture?.let { l -> BalanceBlock(l, x.dit, onOpen = null) }
        }
        x.procede?.takeIf { it.isNotBlank() }?.let { p ->
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Text(
                "Procédé : $p",
                Modifier.padding(top = 12.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(if (dark) Color(0xFF2A3A4C) else Color(0xFF23384A))
                    .clickable(role = Role.Button) { nav.sheet(PolSheetState.Rhet(p)) }.padding(horizontal = 14.dp, vertical = 12.dp),
                color = if (dark) Color(0xFFF2F5EF) else Color(0xFFF7F5EF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        SourceList(sourcesOf(x.src), data, onEv)
    }
}

@Composable
fun DossiersScreen(data: PolData, nav: PolNavigator) {
    val list = remember(data) { contentDossiersOf(data) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part { Lead("Ce qui circule, repris affirmation par affirmation.", "Une vidéo, une tribune ou un tract qui tourne : chaque affirmation est reprise une à une, avec son verdict, les faits et le procédé rhétorique employé. Une affirmation chiffrée porte sa balance : touchez la carte pour le détail.") }
        if (list.isEmpty()) part { EmptyState("Aucun dossier pour l'instant", "Les analyses de contenus qui circulent apparaîtront ici, affirmation par affirmation.", Modifier.padding(top = 16.dp)) }
        itemsIndexed(list) { i, d -> DossierBlock(data, d, startOpen = i == 0, nav) }
    }
}

// ---------- Évaluateurs ----------

@Composable
fun EvaluatorsScreen(data: PolData, nav: PolNavigator) {
    val groups = remember(data) { evaluatorsByLevel(data.evaluators.values) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part {
            Column {
                Lead("Chaque chiffre porte l'étiquette de son producteur.", "Un chiffre est toujours produit par quelqu'un. Touchez une étiquette pour la fiche de son producteur : statut, financement, dirigeants, historique.")
                FigCard(Modifier.padding(top = 12.dp)) {
                    Kicker("Lire l'étiquette")
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { n ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LevelTag(n, Modifier.width(104.dp))
                                Text(indLabel(n), fontSize = 13.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                    Text("Le niveau décrit les liens (statut, financement, dirigeants), pas la qualité d'un chiffrage. Chaque fiche justifie le sien.", Modifier.padding(top = 10.dp), fontSize = 12.5.sp, color = Muted)
                }
            }
        }
        if (groups.isEmpty()) part { EmptyState("Aucun évaluateur", "La liste des évaluateurs n'est pas encore disponible.", Modifier.padding(top = 16.dp)) }
        groups.forEach { (n, evs) ->
            part {
                Section(if (n > 0) "Niveau $n" else "Non qualifié", if (n > 0) indLabel(n) else "Indépendance non qualifiée", null) {
                    FigCard {
                        evs.forEachIndexed { i, e ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { nav.sheet(PolSheetState.Evaluator(e.id)) }.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.nom, fontSize = 15.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
                                    evaluatorSub(e).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.5.sp, color = Muted) }
                                }
                                Text("›", Modifier.clearAndSetSemantics { }, fontSize = 22.sp, color = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Kicker(title)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** Fiche d'un évaluateur : indépendance, statut, financement, dirigeants, orientation, tendance, historique, controverses, sources. */
@Composable
fun EvaluatorSheet(data: PolData, e: Evaluator, nav: PolNavigator, onDismiss: () -> Unit) {
    val n = indLevel(e)
    val scheme = MaterialTheme.colorScheme
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    PolSheet(full = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Kicker(sourceTypeLabel(e.type, "Évaluateur"))
            Text(e.nom, Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold)
            FigCard(Modifier.padding(top = 14.dp)) {
                Kicker("Indépendance")
                Text(if (n > 0) "Niveau $n · ${indLabel(n)}" else "Non qualifiée", Modifier.padding(top = 4.dp), fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { contentDescription = "Niveau ${if (n > 0) n.toString() else "inconnu"} sur 4" },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (1..4).forEach { i -> Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(if (i == n) scheme.onSurface else scheme.outlineVariant)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.SpaceBetween) {
                    Kicker("1 · statutaire"); Kicker("4 · militant")
                }
                if (e.justification.isNotBlank()) Text(frTypo(e.justification), Modifier.padding(top = 10.dp), fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (e.statut.isNotBlank()) Block("Statut") { Text(frTypo(e.statut), fontSize = 14.5.sp, lineHeight = 21.sp) }
            val fin = e.financement.filter { it.isNotBlank() }
            if (fin.isNotEmpty()) Block("Financement") { fin.forEach { Text("• ${frTypo(it)}", Modifier.padding(top = 2.dp), fontSize = 14.5.sp, lineHeight = 21.sp) } }
            val dirs = e.dirigeants.filter { it.nom.isNotBlank() }
            if (dirs.isNotEmpty()) Block("Dirigeants") {
                dirs.forEachIndexed { i, d ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(scheme.outlineVariant))
                    Text(d.nom, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (d.role.isNotBlank()) Text(frTypo(d.role), fontSize = 12.5.sp, color = Muted)
                    if (d.parcours.isNotBlank()) Text(frTypo(d.parcours), Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            if (e.orientation.isNotBlank()) Block("Orientation") { Text(frTypo(e.orientation), fontSize = 14.5.sp, lineHeight = 21.sp) }
            Block("Tendance observée selon les camps") {
                Text(
                    frTypo(if (hasTendency(e)) e.tendance else "Non mesurée : aucune étude ne documente une sévérité différente selon les camps dans ses chiffrages."),
                    fontSize = 14.5.sp, lineHeight = 21.sp,
                )
            }
            if (e.historique.isNotEmpty()) Block("Historique") {
                e.historique.forEachIndexed { i, h ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(scheme.outlineVariant))
                    Text(listOfNotNull(h.annee?.toString(), h.sujet.takeIf { it.isNotBlank() }).joinToString(" · "), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (h.constat.isNotBlank()) Text(frTypo(h.constat), Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp)
                    SourceList(sourcesOf(h.src), data, onEv, Modifier.padding(top = 8.dp))
                }
            }
            val ctr = e.controverses.filter { it.t.isNotBlank() }
            if (ctr.isNotEmpty()) Block("Controverses") {
                ctr.forEachIndexed { i, c ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(scheme.outlineVariant))
                    Text(frTypo(c.t), fontSize = 14.sp, lineHeight = 20.sp)
                    SourceList(sourcesOf(c.src), data, onEv, Modifier.padding(top = 8.dp))
                }
            }
            Block("Sources de la fiche") {
                val srcs = sourcesOf(e.src)
                if (srcs.isEmpty()) Text("Aucune source listée.", fontSize = 13.sp, color = Muted) else SourceList(srcs, data, onEv, Modifier.padding(top = 0.dp))
            }
            FilledTonalButton({ nav.go(app.kairos.mobile.ui.politique.PolRoute.Rubric("evaluateurs")) }, Modifier.padding(top = 20.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("Tous les évaluateurs") }
        }
    }
}

/** Évaluateur désigné par son id ; introuvable (données rechargées) : feuille explicative. */
@Composable
fun EvaluatorSheetAt(data: PolData, id: String, nav: PolNavigator, onDismiss: () -> Unit) {
    val e = data.evaluators[id]
    if (e != null) EvaluatorSheet(data, e, nav, onDismiss)
    else PolSheet(full = false, onDismiss = onDismiss) { EmptyState("Évaluateur introuvable", "Cette fiche n'est plus dans les données chargées. Fermez la feuille et rouvrez-la depuis la liste.", Modifier.padding(16.dp)) }
}
