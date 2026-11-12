package app.kairos.mobile.ui.politique.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.IdCoh
import app.kairos.mobile.ui.politique.IdTurn
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolRoute
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PillOption
import app.kairos.mobile.ui.politique.kit.Pills
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.RichText
import app.kairos.mobile.ui.politique.kit.Segmented
import app.kairos.mobile.ui.politique.kit.SectionHeader
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.safeHttps
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.coroutines.launch

private val ScreenPad = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
private val Mono = FontFamily.Monospace

private fun LazyListScope.part(content: @Composable () -> Unit) = item { content() }

// ---------- Briques ----------

@Composable
private fun Chip(text: String, fg: Color, bg: Color, dashed: Boolean = false, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outline
    Text(
        text.uppercase(), modifier.clip(CircleShape).background(bg).let { if (dashed) it.border(1.dp, line, CircleShape) else it }.padding(horizontal = 9.dp, vertical = 5.dp),
        color = fg, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.5.sp, maxLines = 1,
    )
}

/** Verdict dit / voté : toujours le mot, la couleur l'appuie. */
@Composable
internal fun DvChip(v: String) {
    val pc = PolColors.current
    val label = DV_LABELS[v] ?: v
    when (v) {
        "coherent" -> Chip(label, pc.solide, pc.solideBg)
        "ambigu" -> Chip(label, pc.partiel, pc.partielBg)
        "ecart" -> Chip(label, pc.fragile, pc.fragileBg)
        else -> Chip(label, MaterialTheme.colorScheme.onSurfaceVariant, Color.Transparent, dashed = true)
    }
}

@Composable
private fun NeutralChip(text: String) = Chip(text, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)

@Composable
private fun voteFg(code: String): Color {
    val pc = PolColors.current
    return when (code) { "P" -> pc.pour; "C" -> pc.contre; "A" -> pc.partiel; else -> MaterialTheme.colorScheme.onSurfaceVariant }
}

@Composable
private fun VoteTag(code: String) {
    val pc = PolColors.current
    val label = VOTE_WORDS[code] ?: code
    when (code) {
        "P" -> Chip(label, pc.pour, pc.pourBg)
        "C" -> Chip(label, pc.contre, pc.contreBg)
        "A" -> Chip(label, pc.partiel, pc.partielBg)
        "S" -> Chip(label, MaterialTheme.colorScheme.onSurface, pc.pourBg)
        else -> Chip(label, MaterialTheme.colorScheme.onSurfaceVariant, Color.Transparent, dashed = true)
    }
}

/** Fond d'une case de vote ; « divisé » : rayures pour / contre. */
@Composable
private fun voteBg(code: String): Brush {
    val pc = PolColors.current
    val c = when (code) { "P" -> pc.pourBg; "C" -> pc.contreBg; "A" -> pc.partielBg; else -> Color.Transparent }
    return if (code == "S") Brush.linearGradient(0f to pc.pourBg, 0.5f to pc.pourBg, 0.5f to pc.contreBg, 1f to pc.contreBg, start = Offset.Zero, end = Offset(28f, 28f), tileMode = TileMode.Repeated)
    else Brush.linearGradient(listOf(c, c))
}

@Composable
private fun VoteCell(data: PolData, group: String, code: String, detail: String, modifier: Modifier = Modifier) {
    val color = data.idOf(group)?.let { data.byId(it)?.couleur }?.let { identityColor(it) }
    val fg = if (code == "S") MaterialTheme.colorScheme.onSurface else voteFg(code)
    val word = VOTE_WORDS[code] ?: code
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(voteBg(code)).let { if (code == "N") it.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)) else it }
            .semantics(mergeDescendants = true) { contentDescription = "$group : $word. $detail" },
    ) {
        if (color != null) Box(Modifier.fillMaxWidth().height(4.dp).background(color))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(group, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(word, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) Text(detail, color = fg, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2)
        }
    }
}

/** Cases par trois ; [cells] : (groupe, code, détail). */
@Composable
private fun VoteGrid(data: PolData, cells: List<Triple<String, String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (g, c, d) -> VoteCell(data, g, c, d, Modifier.weight(1f).fillMaxHeight()) }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TallyBar(codes: List<String>, modifier: Modifier = Modifier) {
    val pc = PolColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        codes.forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().background(when (c) { "P" -> pc.pour; "C" -> pc.contre; "A" -> pc.partiel; "S" -> muted; else -> line })) }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp)) { content() }
    }
}

@Composable
private fun Title(text: String) = Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

@Composable
private fun Lead(text: String) = Text(text, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(content: @Composable () -> Unit) = FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun officialSource(chambre: String, url: String?) = listOf(SourceRef(if (chambre == "AN") "Scrutin officiel" else "Source du scrutin", url))

// ---------- Votes ----------

@Composable
fun VotesScreen(data: PolData, nav: PolNavigator) {
    var ch by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var g by rememberSaveable { mutableIntStateOf(0) }
    val key = CHAMBERS[ch].first
    val groups = remember(data, key) { data.decode<String>("votes", "${key}_G") }
    val rows = remember(data, key) { data.decode<VoteRow>("votes", "${key}_V") }
    val coh = remember(data) { data.decode<IdCoh>("votes", "COH") }
    val pc = PolColors.current
    val gi = g.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        part {
            Column {
                Title("Ce qu'ils votent, pas ce qu'ils disent")
                Segmented(CHAMBERS.map { it.second }, ch, { ch = it; g = 0 }, Modifier.padding(top = 12.dp))
                Segmented(listOf("Par scrutin", "Par groupe"), mode, { mode = it }, Modifier.padding(top = 8.dp))
                Legend {
                    LegendDot(pc.pour, "Pour"); LegendDot(pc.contre, "Contre"); LegendDot(pc.partiel, "Abstention"); LegendDot(MaterialTheme.colorScheme.onSurfaceVariant, "Divisé")
                }
            }
        }
        if (rows.isEmpty() || groups.isEmpty()) {
            part { EmptyState("Aucun scrutin", "Aucun vote retenu pour cette assemblée. Essayez l'autre assemblée.") }
        } else if (mode == 0) {
            items(rows, key = { it.t + it.d }) { v ->
                Card {
                    Text(frTypo(v.t), fontSize = 17.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                    Text(v.d, Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    v.n?.takeIf { it.isNotBlank() }?.let { Text(frTypo(it), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TallyBar(v.c.map { it.getOrElse(0) { "N" } }, Modifier.padding(top = 10.dp, bottom = 2.dp))
                    VoteGrid(data, v.c.mapIndexed { i, c -> Triple(groups.getOrElse(i) { "" }, c.getOrElse(0) { "N" }, c.getOrElse(1) { "" }) })
                    SourceList(officialSource(key, v.u), data, { nav.sheet(PolSheetState.Evaluator(it)) })
                }
            }
        } else {
            part {
                Pills(groups.mapIndexed { i, n -> PillOption(i.toString(), n) }, gi.toString(), onSelect = { g = it.toInt() })
            }
            part {
                val gid = data.idOf(groups[gi])
                val counts = voteCounts(rows, gi)
                Card {
                    if (gid != null) IdentityChip(data, gid, large = true, text = groups[gi]) else Text(groups[gi], fontWeight = FontWeight.Bold)
                    if (counts.isNotEmpty()) Text(counts.joinToString(" · ") { (c, n) -> "$n ${VOTE_WORDS[c].orEmpty().lowercase()}" }, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rows.forEach { v ->
                        val c = v.c.getOrNull(gi) ?: return@forEach
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).background(voteBg(c.getOrElse(0) { "N" })).padding(vertical = 10.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                val code = c.getOrElse(0) { "N" }
                                Text(VOTE_WORDS[code] ?: code, color = if (code == "S") MaterialTheme.colorScheme.onSurface else voteFg(code), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(frTypo(v.t), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp)
                                Text(listOf(c.getOrElse(1) { "" }, v.d.substringBefore(" · ")).filter { it.isNotBlank() }.joinToString(" · "), Modifier.padding(top = 3.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (coh.isNotEmpty()) {
            part {
                SectionHeader("Cohérence", "Ce que ces votes disent du discours", "Chaque camp a voté au moins une fois à rebours de son affichage. Certains écarts sont expliqués par le parti, d'autres non.", Modifier.padding(top = 12.dp))
            }
            part {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    items(coh, key = { it.camp }) { c ->
                        Surface(Modifier.width(300.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val id = data.idOf(c.camp)
                                if (id != null) IdentityChip(data, id, large = true, text = c.camp) else Text(c.camp, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                c.items.forEach { it ->
                                    val k = it.getOrElse(0) { "" }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        when (k) {
                                            "coherent" -> Chip("Cohérent", pc.solide, pc.solideBg)
                                            "explique" -> Chip("Écart expliqué", pc.partiel, pc.partielBg)
                                            "ecart" -> Chip("Écart", pc.fragile, pc.fragileBg)
                                            else -> NeutralChip(k)
                                        }
                                        Text(frTypo(it.getOrElse(1) { "" }), fontSize = 14.sp, lineHeight = 19.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            part { Text("Totaux par groupe souvent inférieurs à l'effectif : une partie des élus ne vote pas.", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            part { TextButton({ nav.go(PolRoute.Rubric("dit-vote")) }, Modifier.heightIn(min = 48.dp)) { Text("Tous les scrutins croisés avec la parole : Dit / voté ›") } }
        }
    }
}

// ---------- Revirements ----------

@Composable
private fun turnColors(k: String): Pair<Color, Color> {
    val pc = PolColors.current
    return when (k) { "R" -> pc.contre to pc.contreBg; "I" -> pc.partiel to pc.partielBg; else -> pc.pour to pc.pourBg }
}

@Composable
fun RevirementsScreen(data: PolData, nav: PolNavigator) {
    val rv = remember(data) { data.decode<IdTurn>("revirements", "RV") }
    val camps = remember(rv) { revirementCamps(rv) }
    var camp by rememberSaveable { mutableStateOf("") }
    val cur = camp.takeIf { it in camps } ?: camps.firstOrNull().orEmpty()
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        part {
            Column {
                Title("Qui a changé d'avis")
                Lead("Changer d'avis n'est pas un défaut en soi. Ce qui compte : le parti l'assume-t-il, et le changement touche-t-il le fond ou le calendrier ?")
                Legend { TURN_KINDS.forEach { (k, l) -> val (fg, bg) = turnColors(k); Chip(l, fg, bg) } }
            }
        }
        if (rv.isEmpty()) {
            part { EmptyState("Aucune position suivie", "Aucun changement de position n'est encore documenté. Les positions suivies apparaîtront ici, camp par camp.") }
            return@LazyColumn
        }
        part {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(camps, key = { it }) { c ->
                    val on = c == cur
                    val color = data.idOf(c)?.let { data.byId(it)?.couleur }?.let { identityColor(it) } ?: scheme.outline
                    Surface(
                        Modifier.width(180.dp).clip(RoundedCornerShape(16.dp)).clickable { camp = c }.let { if (on) it.border(2.dp, scheme.onSurface, RoundedCornerShape(16.dp)) else it },
                        shape = RoundedCornerShape(16.dp), color = scheme.surface, tonalElevation = 1.dp,
                    ) {
                        Column {
                            Box(Modifier.fillMaxWidth().height(4.dp).background(color))
                            Column(Modifier.padding(12.dp)) {
                                Text(c, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    rv.filter { it.camp == c }.forEach { Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(turnColors(it.k).first)) }
                                }
                                Text(revirementLine(revirementCounts(rv, c)), Modifier.padding(top = 6.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        val list = rv.filter { it.camp == cur }
        if (list.isEmpty()) part { EmptyState("Aucune position suivie", "Aucun changement de position documenté pour ce camp.") }
        items(list, key = { it.camp + it.p + it.s + it.a }) { r -> TurnCard(data, r) }
    }
}

@Composable
private fun TurnCard(data: PolData, r: IdTurn) {
    val scheme = MaterialTheme.colorScheme
    val (fg, bg) = turnColors(r.k)
    val label = TURN_KINDS.firstOrNull { it.first == r.k }?.second ?: "Position"
    val identity = (data.idOf(r.p) ?: data.idOf(r.camp))?.let { data.byId(it) }
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    val (w1, t1) = splitWhen(r.a)
    val (w2, t2) = splitWhen(r.b)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = scheme.surface, tonalElevation = 1.dp) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                identity?.let { IdentityBadge(it, 34.dp) }
                Column(Modifier.weight(1f)) {
                    Text(r.s, Modifier.semantics { heading() }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.p, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        Chip(label, fg, bg)
                    }
                }
            }
            HorizontalPager(pager, Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) { page ->
                val after = page == 1
                val (w, t) = if (after) w2 to t2 else w1 to t1
                Column(Modifier.fillMaxWidth().background(if (after) bg.copy(alpha = 0.5f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Kicker(if (after) "Après ›" else "Avant", color = if (after) fg else scheme.onSurfaceVariant)
                    if (w.isNotEmpty()) Text(w, Modifier.padding(top = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(frTypo(t), Modifier.padding(top = 4.dp), fontSize = 14.5.sp, lineHeight = 20.sp)
                }
            }
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) { SourceList(sourcesOf(r.src), data, {}, Modifier.padding(top = 0.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(2) { i -> Box(Modifier.width(22.dp).height(4.dp).clip(CircleShape).background(if (i == pager.currentPage) scheme.onSurface else scheme.outlineVariant)) }
                    Text(if (pager.currentPage == 0) "Avant" else "Après", fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
                }
                TextButton({
                    val to = 1 - pager.currentPage
                    scope.launch { if (still) pager.scrollToPage(to) else pager.animateScrollToPage(to) }
                }, Modifier.heightIn(min = 48.dp)) { Text(if (pager.currentPage == 0) "Après ›" else "‹ Avant") }
            }
        }
    }
}

// ---------- Dit / voté ----------

private fun fmtChamber(c: String) = CHAMBERS.firstOrNull { it.first == c }?.second.orEmpty()

@Composable
private fun DvRate(data: PolData, items: List<DvItem>) {
    val pc = PolColors.current
    val scheme = MaterialTheme.colorScheme
    val k = dvCount(items)
    val s = dvCount(items, "seance")
    Column {
        Kicker("Parole publique, hors séance", Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Text("${k.c} / ${k.t}", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Text("scrutins où le vote suit ce que le parti dit en public" + if (k.t == 0) " (aucune déclaration publique trouvée)" else "", Modifier.weight(1f).padding(bottom = 4.dp), fontSize = 13.5.sp, color = scheme.onSurfaceVariant)
        }
        if (k.t > 0) Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(scheme.surfaceVariant)
                .semantics { contentDescription = "${k.c} cohérents, ${k.a} ambigus, ${k.e} écarts" },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (k.c > 0) Box(Modifier.weight(k.c.toFloat()).fillMaxHeight().background(pc.solide))
            if (k.a > 0) Box(Modifier.weight(k.a.toFloat()).fillMaxHeight().background(pc.partiel))
            if (k.e > 0) Box(Modifier.weight(k.e.toFloat()).fillMaxHeight().background(pc.fragile))
        }
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (k.e > 0) Chip(plural(k.e, "écart", "écarts"), pc.fragile, pc.fragileBg)
            if (k.a > 0) Chip(plural(k.a, "ambigu", "ambigus"), pc.partiel, pc.partielBg)
            if (k.c > 0) Chip(plural(k.c, "cohérent", "cohérents"), pc.solide, pc.solideBg)
        }
        if (s.t > 0) Column(Modifier.padding(top = 12.dp)) {
            Kicker("Ligne annoncée en séance")
            Text(
                "${s.c} / ${s.t} votes conformes à l'explication donnée dans l'hémicycle juste avant" +
                    (if (s.e > 0) ", ${plural(s.e, "écart", "écarts")}" else "") + (if (s.a > 0) ", ${plural(s.a, "vote divisé ou ambigu", "votes divisés ou ambigus")}" else "") + ".",
                Modifier.padding(top = 4.dp), fontSize = 14.sp,
            )
            Text("Moins probant : annoncer son vote puis le suivre est attendu. Les écarts y restent parlants (élus qui votent contre la ligne de leur groupe).", Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
        }
        if (k.sp > 0) Honest("${plural(k.sp, "scrutin", "scrutins")} sans déclaration trouvée", " dans les six mois autour du vote : ni cohérents ni incohérents, donc hors des deux taux.")
    }
}

@Composable
private fun Honest(bold: String, rest: String) {
    Text(
        buildAnnotated(bold, rest), Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun buildAnnotated(bold: String, rest: String) = androidx.compose.ui.text.buildAnnotatedString {
    append(bold); addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), 0, bold.length); append(rest)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DvRow(data: PolData, d: DvItem, onClick: () -> Unit) {
    val p = positionOf(data, d.scrutin, d.verdict.qui)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            DvChip(d.verdict.v)
            p?.let { VoteTag(it.second) }
            NeutralChip(BASE_LABELS[d.verdict.baseOf()].orEmpty())
            Kicker("${fmtChamber(d.scrutin.chambre)} · ${fmtDate(d.scrutin.date)}")
        }
        Text(frTypo(d.scrutin.titre), Modifier.padding(top = 6.dp), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
        Text(frTypo(d.verdict.explication), Modifier.padding(top = 6.dp), fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DitVoteScreen(data: PolData, nav: PolNavigator) {
    val all = remember(data) { scrutinsOf(data) }
    var ch by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var f by rememberSaveable { mutableStateOf("") }
    var party by rememberSaveable { mutableStateOf("") }
    val key = CHAMBERS[ch].first
    val scheme = MaterialTheme.colorScheme
    val inCh = remember(all, key) { scrutinsFor(all, key, "") }
    val partiesIn = remember(inCh) { dvParties(inCh) }
    val curParty = party.takeIf { it in partiesIn } ?: partiesIn.firstOrNull()
    val partyItems = remember(all, key, curParty) { dvFor(data, curParty).filter { it.scrutin.chambre == key } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        part {
            Column {
                Title("Ce qu'ils disent, ce qu'ils votent")
                Lead("Chaque scrutin croisé avec ce que les partis en ont dit publiquement dans les six mois autour du vote. Un écart n'est pas forcément une trahison : lisez l'explication.")
                Segmented(CHAMBERS.map { it.second }, ch, { ch = it; f = "" }, Modifier.padding(top = 12.dp))
                Segmented(listOf("Par scrutin", "Par parti"), mode, { mode = it }, Modifier.padding(top = 8.dp))
                Legend { DV_ORDER.forEach { DvChip(it) } }
            }
        }
        if (mode == 0) {
            part {
                Pills(
                    listOf("" to "Tous", "ecart" to "Avec un écart", "ambigu" to "Ambigus").map { (k, l) -> PillOption(k, l, scrutinsFor(all, key, k).size) },
                    f, onSelect = { f = it },
                )
            }
            val list = scrutinsFor(all, key, f)
            if (list.isEmpty()) part { EmptyState("Aucun scrutin pour ce filtre", if (f.isNotEmpty()) "Aucun scrutin de cette assemblée ne présente ce cas. Revenez à « Tous »." else "Aucun scrutin croisé pour cette assemblée.") }
            items(list, key = { it.id }) { s -> ScrutinCard(data, s) { nav.sheet(PolSheetState.Scrutin(s.id)) } }
        } else {
            val p = curParty
            if (p == null) {
                part { EmptyState("Aucun parti", "Aucun verdict pour cette assemblée. Essayez l'autre assemblée.") }
                return@LazyColumn
            }
            part { Pills(partiesIn.map { PillOption(it, data.byId(it)?.nom ?: it) }, p, onSelect = { party = it }) }
            val items = partyItems
            part {
                Card {
                    if (data.byId(p) != null) IdentityChip(data, p, large = true) else Text(p, fontWeight = FontWeight.Bold)
                    DvRate(data, items)
                }
            }
            part { SectionHeader("Les écarts", "Là où le vote ne suit pas la parole", "Écarts d'abord, puis votes ambigus ; chaque ligne ouvre le scrutin et les déclarations.", Modifier.padding(top = 8.dp)) }
            val gaps = dvGaps(items)
            if (gaps.isEmpty()) {
                part { EmptyState("Aucun écart relevé", noGapsMessage(dvCount(items), dvCount(items, "seance"))) }
            } else part {
                Card(Modifier) { gaps.forEachIndexed { i, d -> if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant)); DvRow(data, d) { nav.sheet(PolSheetState.Scrutin(d.scrutin.id)) } } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrutinCard(data: PolData, s: Scrutin, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    fun n(k: String) = s.verdicts.count { it.v == k }
    Card(onClick = onClick) {
        Kicker(fmtDate(s.date))
        Text(frTypo(s.titre), Modifier.padding(top = 4.dp), fontSize = 16.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
        if (s.resultat.isNotBlank()) Text(s.resultat, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = scheme.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.positions.forEach { (g, p) ->
                val c = p.getOrElse(0) { "N" }
                val color = data.idOf(g)?.let { data.byId(it)?.couleur }?.let { identityColor(it) } ?: scheme.outline
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(scheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(g, fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(VOTE_WORDS[c] ?: c, color = voteFg(c), fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val pc = PolColors.current
            if (n("ecart") > 0) Chip(plural(n("ecart"), "écart", "écarts"), pc.fragile, pc.fragileBg)
            if (n("ambigu") > 0) Chip(plural(n("ambigu"), "ambigu", "ambigus"), pc.partiel, pc.partielBg)
            if (n("coherent") > 0) Chip(plural(n("coherent"), "cohérent", "cohérents"), pc.solide, pc.solideBg)
            if (n("sans-parole") > 0) Chip("${n("sans-parole")} sans déclaration", scheme.onSurfaceVariant, Color.Transparent, dashed = true)
        }
    }
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

// ---------- Feuille de scrutin ----------

@Composable
fun ScrutinSheetAt(data: PolData, id: String, nav: PolNavigator, onDismiss: () -> Unit) {
    val s = remember(data, id) { scrutinsOf(data).firstOrNull { it.id == id } }
    if (s != null) ScrutinSheet(data, s, nav, onDismiss)
    else PolSheet(full = false, onDismiss = onDismiss) { EmptyState("Scrutin introuvable", "Ce scrutin n'est plus dans les données chargées. Fermez la feuille et rouvrez-le depuis la liste.", Modifier.padding(16.dp)) }
}

@Composable
fun ScrutinSheet(data: PolData, s: Scrutin, nav: PolNavigator, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    val vs = remember(s) { s.verdicts.sortedBy { DV_ORDER.indexOf(it.v) } }
    PolSheet(full = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Kicker("${fmtChamber(s.chambre)} · ${fmtDate(s.date)}")
            Text(frTypo(s.titre), Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
            if (s.objet.isNotBlank()) Text(frTypo(s.objet), Modifier.padding(top = 8.dp), fontSize = 14.5.sp)
            if (s.resultat.isNotBlank()) Text(s.resultat, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = scheme.onSurfaceVariant)
            SourceList(officialSource(s.chambre, s.url), data, onEv)

            SectionHeader("Le vote", "Comment chaque groupe a voté", modifier = Modifier.padding(top = 20.dp))
            if (s.positions.isEmpty()) EmptyState("Positions non publiées", "Aucune position de groupe n'est publiée pour ce scrutin.", Modifier.padding(top = 10.dp))
            else VoteGrid(data, s.positions.map { (g, p) -> Triple(g, p.getOrElse(0) { "N" }, p.getOrElse(1) { "" }) })
            s.note?.takeIf { it.isNotBlank() }?.let { Text(frTypo(it), Modifier.padding(top = 8.dp), fontSize = 12.5.sp, color = scheme.onSurfaceVariant) }

            SectionHeader("La parole", "Ce qu'ils ont dit, six mois autour du vote", modifier = Modifier.padding(top = 20.dp))
            if (s.paroles.isEmpty()) EmptyState("Aucune prise de parole retenue", "Aucune déclaration publique n'a été trouvée dans les sources consultées, pour aucun groupe, dans la fenêtre de six mois.", Modifier.padding(top = 10.dp))
            else Card(Modifier.padding(top = 10.dp)) {
                s.paroles.forEachIndexed { i, p ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(scheme.outlineVariant))
                    ParoleBlock(data, p, onEv)
                }
            }

            SectionHeader("Dit / voté", "Parti par parti", modifier = Modifier.padding(top = 20.dp))
            Honest("« Aucune déclaration trouvée »", " n'est pas un bon point : on ne peut rien comparer. C'est une absence de source, pas une cohérence.")
            if (vs.isEmpty()) EmptyState("Aucun verdict", "Aucun verdict n'est publié pour ce scrutin.", Modifier.padding(top = 10.dp))
            vs.forEach { v ->
                val p = positionOf(data, s, v.qui)
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (data.byId(v.qui) != null) IdentityChip(data, v.qui) else NeutralChip(v.qui)
                        DvChip(v.v)
                        p?.let { VoteTag(it.second) }
                    }
                    Text(frTypo(v.explication), Modifier.padding(top = 6.dp), fontSize = 14.sp, color = if (v.v == "sans-parole") scheme.onSurfaceVariant else Color.Unspecified)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParoleBlock(data: PolData, p: Parole, onEv: (String) -> Unit) {
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (p.parti != null && data.byId(p.parti) != null) IdentityChip(data, p.parti)
            Text(p.qui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            NeutralChip(if (p.cadre == "seance") "En séance" else "Hors séance")
            Kicker(fmtDate(p.date))
        }
        Text(frTypo(p.dit), Modifier.padding(top = 6.dp), fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
        if (safeHttps(p.url) != null) SourceList(listOf(SourceRef("", p.url)), data, onEv)
    }
}
