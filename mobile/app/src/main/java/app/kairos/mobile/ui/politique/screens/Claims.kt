package app.kairos.mobile.ui.politique.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.BalanceBlock
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.EvaluatorTag
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PillOption
import app.kairos.mobile.ui.politique.kit.Pills
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.RichText
import app.kairos.mobile.ui.politique.kit.Segmented
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.VerdictMeter
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.verdictLabel
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.theme.mix


internal fun PolData.campColor(camp: String): Color? = idOf(camp)?.let { byId(it)?.couleur }?.let { identityColor(it) }

// ---------- Briques ----------

/** « Ce que la phrase cache » : visible sans effort ; [small] pour les cartes. */
@Composable
fun CacheBox(text: String?, small: Boolean = false, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    val pc = PolColors.current
    Row(modifier.fillMaxWidth().padding(top = if (small) 10.dp else 14.dp).clip(RoundedCornerShape(if (small) 10.dp else 14.dp)).background(pc.partielBg).height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(pc.partiel))
        Column(Modifier.padding(horizontal = if (small) 10.dp else 14.dp, vertical = if (small) 8.dp else 12.dp)) {
            Kicker("Ce que la phrase cache", color = pc.partiel)
            RichText(text, Modifier.padding(top = 3.dp), style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = if (small) 6 else Int.MAX_VALUE)
        }
    }
}

@Composable
private fun PhraseCard(data: PolData, c: Claim, showCamp: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.clickable(role = Role.Button, onClick = onClick).padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Kicker(claimKicker(c), Modifier.weight(1f))
                if (showCamp) CampTag(data, c.camp)
            }
            Text("« ${frTypo(c.q)} »", Modifier.padding(top = 8.dp), fontSize = 16.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            CacheBox(c.cache, small = true)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                VerdictChip(c.v)
                Text("Les faits ›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CampTag(data: PolData, camp: String) {
    val id = data.idOf(camp)
    if (id != null) IdentityChip(data, id, text = camp) else Text(camp, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun StackBar(tally: Map<String, Int>, modifier: Modifier = Modifier) {
    val pc = PolColors.current
    val line = MaterialTheme.colorScheme.outlineVariant
    val parts = tally.filterValues { it > 0 }
    if (parts.isEmpty()) return
    val color = mapOf("solide" to pc.solide, "partiel" to pc.partiel, "trompeur" to pc.trompeur, "fragile" to pc.fragile, "faux" to pc.faux)
    val desc = parts.entries.joinToString(", ") { "${it.value} ${verdictLabel(it.key).lowercase()}" }
    Row(modifier.height(8.dp).clip(CircleShape).semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (v, n) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(color[v] ?: line)) }
    }
}

@Composable
private fun CampHeader(data: PolData, camp: String, count: Int, tally: Map<String, Int>? = null, modifier: Modifier = Modifier) {
    val id = data.idOf(camp)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            id?.let { data.byId(it) }?.let { IdentityBadge(it, 36.dp) }
            Column(Modifier.weight(1f)) {
                Text(camp, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 20.sp)
                Text(frCount(count, "phrase vérifiée", "phrases vérifiées"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tally != null) StackBar(tally, Modifier.width(60.dp))
        }
    }
}

@Composable
internal fun Lead(text: String) = Text(text, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Label(text: String, top: Int = 12) = Kicker(text, Modifier.padding(top = top.dp, bottom = 6.dp))


internal fun frCount(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

// ---------- Phrases ----------

@Composable
fun PhrasesScreen(data: PolData, nav: PolNavigator) {
    val st = remember(data) { phrasesState(data) }
    var v by rememberSaveable { mutableStateOf("") }
    var camp by rememberSaveable { mutableStateOf("") }
    var theme by rememberSaveable { mutableStateOf("") }
    var n by rememberSaveable { mutableIntStateOf(PHRASES_PAGE) }
    var defs by rememberSaveable { mutableStateOf(false) }
    val view = remember(st, v, camp, theme, n) { phrasesView(st, PhraseFilter(v, camp, theme, n)) }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        part {
            Column {
                Text("Entendues partout, vérifiées une à une", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                RichText("Des phrases qu'on entend à la télé, en meeting ou à table, de tous les camps. Pour chacune : le verdict des données, et surtout <b>ce qu'elle cache</b>.", Modifier.padding(top = 6.dp), color = scheme.onSurfaceVariant)
            }
        }
        part {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = scheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.clickable(role = Role.Button) { defs = !defs }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Que veulent dire les verdicts ?", fontWeight = FontWeight.SemiBold, modifier = Modifier.heightIn(min = 24.dp))
                    if (defs) VERDICT_ORDER.forEach { k ->
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            VerdictChip(k)
                            Text(verdictDefinition(k).orEmpty(), Modifier.weight(1f), fontSize = 13.sp, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (st.claims.isEmpty()) {
            part { EmptyState("Aucune phrase vérifiée", "Les phrases entendues et vérifiées n'ont pas encore été publiées. Elles apparaîtront ici avec leur verdict et ce qu'elles cachent.") }
            return@LazyColumn
        }
        part {
            Column {
                Label("Verdict", 4)
                Pills(listOf(PillOption("", "Tous", st.claims.size)) + st.verdicts.map { k -> PillOption(k, verdictLabel(k), st.claims.count { it.v == k }) }, v, onSelect = { v = it; n = PHRASES_PAGE })
                Label("Camp")
                Pills(listOf(PillOption("", "Tous")) + st.camps.map { k -> PillOption(k, k, st.claims.count { it.camp == k }) }, camp, onSelect = { camp = it; n = PHRASES_PAGE })
                Label("Thème")
                Pills(listOf(PillOption("", "Tous")) + st.themes.map { k -> PillOption(k, k, st.claims.count { it.theme == k }) }, theme, onSelect = { theme = it; n = PHRASES_PAGE })
                Text(
                    frCount(view.total, "phrase", "phrases") + if (view.total > view.items.size) ", ${view.items.size} affichées" else "",
                    Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp, color = scheme.onSurfaceVariant,
                )
            }
        }
        if (view.items.isEmpty()) part { EmptyState("Aucune phrase pour ce choix", "Aucune phrase vérifiée ne combine ce verdict, ce camp et ce thème. Élargissez un des filtres (« Tous »).") }
        items(view.items, key = { it }) { i -> PhraseCard(data, st.claims[i], showCamp = true) { nav.sheet(PolSheetState.Claim(i)) } }
        if (view.more > 0) part {
            TextButton({ n += PHRASES_PAGE }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Afficher ${frCount(view.more, "phrase", "phrases")} de plus") }
        }
    }
}


// ---------- Hors de ma bulle ----------

@Composable
fun BulleScreen(data: PolData, nav: PolNavigator) {
    val claims = remember(data) { claimsOf(data) }
    val camps = remember(data, claims) { campsOf(data, claims) }
    val themes = remember(claims) { bubbleThemes(claims) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var camp by rememberSaveable { mutableStateOf("") }
    var pick by rememberSaveable { mutableIntStateOf(-1) }
    var n by rememberSaveable { mutableIntStateOf(BULLE_PAGE) }
    var theme by rememberSaveable { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(pick) { state.scrollToItem(0) }
    val scheme = MaterialTheme.colorScheme
    val picked = claims.getOrNull(pick)
    // Déclaré après celui de la coque : passe en premier.
    BackHandler(enabled = mode == 0 && picked != null) { pick = -1 }

    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        part {
            Column {
                Text("Les mêmes thèmes, vus de chaque camp", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Lead("Choisissez une phrase que vous entendez souvent, puis lisez ce qu'on dit en face sur le même sujet. Même format pour tous, même ordre.")
                Segmented(listOf("Parcours", "Par thème"), mode, { mode = it }, Modifier.padding(top = 12.dp))
            }
        }
        if (claims.isEmpty()) {
            part { EmptyState("Rien à comparer pour l'instant", "Les phrases vérifiées de chaque camp n'ont pas encore été publiées. Dès qu'il y en aura, elles seront rangées ici par thème.") }
            return@LazyColumn
        }
        if (mode == 1) {
            val cur = theme.takeIf { it in themes } ?: themes.firstOrNull().orEmpty()
            part { Pills(themes.map { t -> PillOption(t, t, claims.count { it.theme == t }) }, cur, onSelect = { theme = it }) }
            val cols = themeColumns(claims, camps, cur)
            part {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    items(cols, key = { it.first }) { (k, idx) ->
                        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CampHeader(data, k, idx.size, verdictTally(idx.map { claims[it] }))
                            idx.forEach { i -> PhraseCard(data, claims[i], showCamp = false) { nav.sheet(PolSheetState.Claim(i)) } }
                            if (idx.isEmpty()) EmptyState("Rien de ce côté", "Aucune phrase entendue côté $k n'a encore été vérifiée sur ce thème.")
                        }
                    }
                }
            }
            return@LazyColumn
        }
        if (picked == null) {
            val pool = bubbleOffers(claims, camp)
            part { Steps(1, "Étape 1 sur 2 · une phrase") }
            part {
                Column {
                    Label("Je l'entends plutôt", 0)
                    Pills(listOf(PillOption("", "Partout")) + camps.map { PillOption(it, it) }, camp, onSelect = { camp = it; n = BULLE_PAGE })
                }
            }
            if (pool.isEmpty()) part { EmptyState("Aucune phrase à comparer", "Aucune phrase de ce camp n'a encore de réponse vérifiée d'un autre camp sur le même thème.") }
            items(pool.take(n), key = { it }) { i ->
                val c = claims[i]
                val fc = facing(claims, i)
                val color = data.campColor(c.camp) ?: scheme.outlineVariant
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = scheme.surface, tonalElevation = 1.dp) {
                    Row(Modifier.height(IntrinsicSize.Min).clickable(role = Role.Button) { pick = i }) {
                        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
                        Column(Modifier.padding(14.dp)) {
                            Kicker("${c.theme} · côté ${c.camp}")
                            Text("« ${frTypo(c.q)} »", Modifier.padding(top = 6.dp), fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                            Text("En face : ${frCount(fc.size, "phrase", "phrases")}, ${frCount(fc.map { claims[it].camp }.distinct().size, "camp", "camps")} ›", Modifier.padding(top = 8.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (pool.size > n) part { TextButton({ n += BULLE_PAGE }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Afficher ${frCount(minOf(BULLE_PAGE, pool.size - n), "phrase", "phrases")} de plus") } }
            return@LazyColumn
        }
        part { Steps(2, "Étape 2 sur 2 · en face") }
        part { TextButton({ pick = -1 }, Modifier.heightIn(min = 48.dp)) { Text("‹ Choisir une autre phrase") } }
        part {
            val color = data.campColor(picked.camp) ?: scheme.outlineVariant
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = scheme.surface, tonalElevation = 1.dp) {
                Column {
                    Box(Modifier.fillMaxWidth().height(5.dp).background(color))
                    Column(Modifier.padding(16.dp)) {
                        Kicker("${picked.theme} · votre phrase, côté ${picked.camp}")
                        Text("« ${frTypo(picked.q)} »", Modifier.padding(top = 8.dp), fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            VerdictMeter(picked.v)
                            Text(verdictLabel(picked.v), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        CacheBox(picked.cache)
                        TextButton({ nav.sheet(PolSheetState.Claim(pick)) }, Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) { Text("Les faits et les sources ›") }
                    }
                }
            }
        }
        part {
            Column(Modifier.padding(top = 14.dp)) {
                Text("Sur « ${picked.theme} », en face", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Les autres camps, dans le même ordre. Lisez d'abord celui qui n'est pas le vôtre.", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = scheme.onSurfaceVariant)
            }
        }
        facingColumns(claims, pick, camps).forEach { (k, idx) ->
            part {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CampHeader(data, k, idx.size)
                    idx.forEach { i -> PhraseCard(data, claims[i], showCamp = false) { nav.sheet(PolSheetState.Claim(i)) } }
                    if (idx.isEmpty()) Text("Rien de vérifié côté $k sur ce thème pour l'instant : l'absence ne dit rien de ce camp.", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(10.dp), fontSize = 13.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Steps(on: Int, label: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(2) { i -> Box(Modifier.width(22.dp).height(4.dp).clip(CircleShape).background(if (i < on) scheme.onSurface else scheme.outlineVariant)) }
        Kicker(label)
    }
}

// ---------- Détail d'une phrase ----------

/** Feuille de détail : réutilisable par toute rubrique qui montre une phrase. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClaimSheet(data: PolData, claim: Claim, nav: PolNavigator, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    val evs = claim.par.mapNotNull { data.evaluators[it] }
    PolSheet(full = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Kicker(claimKicker(claim))
            Box(Modifier.padding(vertical = 8.dp)) {
                val id = data.idOf(claim.camp)
                if (id != null) IdentityChip(data, id, text = claim.camp, onClick = { nav.sheet(PolSheetState.Identity(id)) }) else Text(claim.camp, fontWeight = FontWeight.SemiBold)
            }
            Text("« ${frTypo(claim.q)} »", Modifier.semantics { heading() }, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                VerdictMeter(claim.v)
                VerdictChip(claim.v, large = true)
            }
            verdictDefinition(claim.v)?.let { Text("Verdict : $it.", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = scheme.onSurfaceVariant) }
            CacheBox(claim.cache)
            claim.facts.forEach { Text(frTypo(it), Modifier.padding(top = 10.dp), fontSize = 14.5.sp, lineHeight = 21.sp) }
            if (claim.origine.isNotBlank()) Text("Où on l'entend. ${claim.origine}", Modifier.padding(top = 12.dp), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
            if (evs.isNotEmpty()) {
                Kicker("Vérifié avec les chiffres de", Modifier.padding(top = 14.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { evs.forEach { EvaluatorTag(it, onEv) } }
            }
            claim.lecture?.let { BalanceBlock(it, claim.q, claim.camp, onOpen = { nav.sheet(PolSheetState.Balance(it, claim.q, claim.camp)) }) }
            SourceList(sourcesOf(claim.src), data, onEv)
            FilledTonalButton({ nav.table(listOf(claimTableCard(claim)), 0) }, Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("Afficher en grand, à table") }
        }
    }
}

/** Phrase désignée par son rang ; introuvable (données rechargées) : feuille explicative. */
@Composable
fun ClaimSheetAt(data: PolData, index: Int, nav: PolNavigator, onDismiss: () -> Unit) {
    val claim = remember(data, index) { claimsOf(data).getOrNull(index) }
    if (claim != null) ClaimSheet(data, claim, nav, onDismiss)
    else PolSheet(full = false, onDismiss = onDismiss) { EmptyState("Phrase introuvable", "Cette phrase n'est plus dans les données chargées. Fermez la feuille et rouvrez-la depuis la liste.", Modifier.padding(16.dp)) }
}
