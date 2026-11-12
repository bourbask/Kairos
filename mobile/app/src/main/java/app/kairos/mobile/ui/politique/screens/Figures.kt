package app.kairos.mobile.ui.politique.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.BalanceBlock
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.IdentityDot
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.RichText
import app.kairos.mobile.ui.politique.kit.SectionHeader
import app.kairos.mobile.ui.politique.kit.SourceButton
import app.kairos.mobile.ui.politique.kit.TableCard
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.textOn
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.PolJson
import app.kairos.mobile.ui.politique.model.SourceRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val Mono = FontFamily.Monospace
private val ScreenPad = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)

/** Un élément (objet ou nombre) d'un fichier, à un chemin pointé ; absent ou illisible : null. */
private fun <T> PolData.one(file: String, path: String, s: KSerializer<T>): T? {
    var cur: JsonElement? = file(file)
    for (k in path.split('.')) cur = (cur as? JsonObject)?.get(k)
    return cur?.let { try { PolJson.decodeFromJsonElement(s, it) } catch (_: Exception) { null } }
}

/** Identité d'un libellé nominatif (« Nom · Parti » ou « Nom »). */
private fun PolData.idFor(who: String): String? = idOf(who) ?: idOf(who.substringBefore(" · "))

private fun nameOf(who: String) = who.substringBefore(" · ")

private fun PolData.partyLabel(who: String): String =
    who.substringAfter(" · ", "").ifEmpty { idFor(who)?.let { partyOf(it) }.orEmpty() }

private fun PolData.identityColorOf(who: String): Color? = idFor(who)?.let { byId(it)?.couleur }?.let { identityColor(it) }

// ---------- Briques communes ----------

@Composable
private fun FigCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun Section(kicker: String, title: String, lead: String?, foot: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        SectionHeader(kicker, title, lead)
        Spacer(Modifier.height(10.dp))
        content()
        if (foot != null) RichText(foot, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stat(label: String, value: String, note: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(10.dp)) {
        Kicker(label)
        Text(value, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
        Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(items: List<Pair<Color, String>>) {
    FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (c, t) -> LegendDot(c, t) }
    }
}

/** Barres horizontales à partir du zéro de l'échelle ; couleur et libellé par ligne. */
@Composable
private fun HBars(rows: List<BilanRow>, scale: Scale, labelW: Dp, color: (BilanRow) -> Color) {
    val scheme = MaterialTheme.colorScheme
    rows.forEachIndexed { i, r ->
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant.copy(alpha = 0.6f)))
        val bar = signedBar(r.v, scale.min, scale.max)
        val label = (if (r.v > 0 && scale.min < 0) "+" else "") + fmtNum(r.v, 1) + scale.unit
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).semantics(mergeDescendants = true) { contentDescription = "${r.l} : $label" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.width(labelW)) {
                Text(r.l, fontSize = 13.5.sp, lineHeight = 17.sp, fontWeight = if (r.total) FontWeight.ExtraBold else FontWeight.SemiBold)
                if (!r.s.isNullOrBlank()) Text(r.s, fontSize = 12.sp, lineHeight = 15.sp, color = scheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.weight(1f).height(26.dp)) {
                val w = maxWidth
                if (scale.min < 0) Box(Modifier.offset(x = w * axisPos(0.0, scale.min, scale.max).toFloat()).fillMaxHeight().width(1.5.dp).background(scheme.onSurface.copy(alpha = 0.45f)))
                Box(Modifier.offset(x = w * bar.start.toFloat(), y = 4.dp).width(w * bar.width.toFloat()).height(18.dp).clip(RoundedCornerShape(4.dp)).background(color(r)))
                val txt = @Composable { Text(label, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, softWrap = false, textAlign = if (r.v >= 0) TextAlign.Start else TextAlign.End) }
                if (r.v >= 0) Box(Modifier.fillMaxHeight().padding(start = w * (bar.start + bar.width).toFloat() + 6.dp), contentAlignment = Alignment.CenterStart) { txt() }
                else Box(Modifier.fillMaxSize().padding(end = (w * (1 - bar.start).toFloat() + 6.dp).coerceAtMost(w)), contentAlignment = Alignment.CenterEnd) { txt() }
            }
        }
    }
}

/** Liste « ce que chaque chiffre sacrifie » : la balance de chaque ligne, ou un état vide. */
@Composable
private fun <T> BalanceRows(rows: List<T>, nameOf: (T) -> String, lecture: (T) -> Lecture?, whoOf: (T) -> String?, data: PolData, nav: PolNavigator, nothing: Boolean = true) {
    val with = rows.filter { lecture(it) != null }
    if (with.isEmpty()) {
        if (nothing) EmptyState("Balance pas encore documentée", "Aucun de ces chiffres n'a encore sa lecture critique : ce qu'il mesure, ce qu'il laisse dehors, qui paie. Elle apparaîtra ici dès qu'elle sera sourcée.")
        return
    }
    FigCard {
        with.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            val who = whoOf(r)
            val id = who?.let { data.idFor(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (id != null) data.byId(id)?.let { IdentityBadge(it, 28.dp) }
                Text(nameOf(r), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Bal(lecture(r)!!, nameOf(r), who, nav)
        }
        val miss = rows.filter { lecture(it) == null }
        if (miss.isNotEmpty()) Text("Balance pas encore documentée : ${miss.joinToString(", ") { nameOf(it) }}.", Modifier.padding(top = 12.dp), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Carrousel à accroche, avec ses points de position. */
@Composable
private fun <T> Rail(items: List<T>, cardWidth: Dp, content: @Composable (Int, T) -> Unit) {
    val state = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme
    Column {
        LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.size.let { n -> items(n) { i -> Box(Modifier.width(cardWidth)) { content(i, items[i]) } } }
        }
        if (items.size > 1) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            items.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == state.firstVisibleItemIndex) 8.dp else 6.dp).clip(CircleShape).background(if (i == state.firstVisibleItemIndex) scheme.primary else scheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun Bal(lecture: Lecture, title: String, who: String?, nav: PolNavigator, modifier: Modifier = Modifier) =
    BalanceBlock(lecture, title, who, onOpen = { nav.sheet(PolSheetState.Balance(lecture, title, who)) }, modifier = modifier)

@Composable
private fun TableLink(onClick: () -> Unit) {
    TextButton(onClick, Modifier.semantics { contentDescription = "Afficher cette carte en grand, à table" }) { Text("À table") }
}

private fun LazyListScope.part(content: @Composable () -> Unit) = item { content() }

// ---------- Budgets ----------

@Composable
fun BudgetsScreen(data: PolData, nav: PolNavigator) {
    val programs = remember(data) { data.decode<Program>("budgets", "PROGRAMS") }
    val gaps = remember(data) { data.decode<Gap>("budgets", "GAPS") }
    val ref = remember(data) { data.one("budgets", "REF", Double.serializer()) ?: programs.firstOrNull { it.ref }?.debt }
    val axis = remember(data) { data.one("budgets", "AXIS", Scale.serializer()) }
    val cands = remember(programs) { programs.filter { !it.ref && it.debt != null } }
    if (cands.isEmpty() || ref == null) {
        EmptyState("Budgets pas encore publiés", "Les projections de dette par programme ne sont pas disponibles pour l'instant. Les autres rubriques restent ouvertes depuis l'accueil.", Modifier.padding(16.dp))
        return
    }
    val pc = PolColors.current
    val (lo, hi) = remember(axis, cands, ref) { axisBounds(axis, cands.mapNotNull { it.debt } + ref) }
    val ticks = remember(lo, hi) { axisTicks(lo, hi) }
    val below = cands.count { it.debt!! < ref }
    val gapCards = remember(gaps) {
        gaps.map { g ->
            val (a, b) = gapLabels(g)
            TableCard("Annoncé contre crédité", g.what, lines = listOf("${nameOf(g.who)} : ${fmtNum(g.claimed, 1)} → ${fmtNum(g.credited, 1)} ${g.unit}".trim(), "$a / $b"), source = g.src)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part {
            Column {
                RichText("Dette publique projetée à l'horizon du programme, comparée à ce qui se passerait <b>sans aucune mesure nouvelle : ${fmtNum(ref, 1)} % du PIB</b>. Mêmes hypothèses de croissance, d'inflation et de taux pour tous.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Référence", "${fmtNum(ref, 1)} %", "sans mesure", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f).fillMaxHeight())
                    Stat("En dessous", "$below sur ${cands.size}", "moins de dette", pc.low, Modifier.weight(1f).fillMaxHeight())
                    Stat("Au-dessus", "${cands.size - below} sur ${cands.size}", "plus de dette", pc.high, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        part {
            Section("Dette publique à l'horizon", "Chaque programme face à la référence", null, "Touchez une ligne pour la fiche du candidat. Une économie annoncée sans mesure détaillée n'est comptée qu'à 20 %.") {
                FigCard {
                    Legend(listOf(pc.low to "Moins de dette que sans mesure", pc.high to "Plus de dette"))
                    programs.filter { it.debt != null }.forEachIndexed { i, p -> DebtRow(data, p, ref, lo, hi, ticks, nav, divider = i > 0) }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("% du PIB", Modifier.width(134.dp), fontFamily = Mono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BoxWithConstraints(Modifier.weight(1f).height(18.dp)) {
                            ticks.forEach { t -> Text(fmtNum(t), Modifier.offset(x = maxWidth * axisPos(t, lo, hi).toFloat() - 14.dp).width(28.dp), textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = false) }
                        }
                    }
                }
            }
        }
        part {
            Section("La balance", "Ce que chaque programme sacrifie", "Pour chaque chiffre : ce qu'il sacrifie, ce qu'il ne montre pas, et l'avis signé des évaluateurs.") {
                BalanceRows(cands, { it.who }, { it.lecture }, { it.who }, data, nav)
            }
        }
        part {
            Section("Le détail", "Dette, déficit et effet net", "Effet net : ce que les mesures rapportent (+) ou coûtent (−) aux finances publiques chaque année.") {
                FigCard { DetailTable(data, programs.filter { it.debt != null }) }
            }
        }
        part {
            Section("Annoncé contre crédité", "La promesse face au chiffrage", "Ce que le candidat met en avant, face à ce qu'un chiffrage extérieur retient. Barre sombre : l'annonce. Barre bleue : ce qui tient.") {
                if (gaps.isEmpty()) EmptyState("Aucun écart chiffré", "Aucun chiffrage extérieur ne contredit encore une annonce.")
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { gaps.forEachIndexed { i, g -> GapCard(data, g, nav) { nav.table(gapCards, i) } } }
            }
        }
    }
}

@Composable
private fun DebtRow(data: PolData, p: Program, ref: Double, lo: Double, hi: Double, ticks: List<Double>, nav: PolNavigator, divider: Boolean) {
    val pc = PolColors.current
    val scheme = MaterialTheme.colorScheme
    val debt = p.debt!!
    val id = if (p.ref) null else data.idFor(p.who)
    val side = if (debt < ref) pc.low else pc.high
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
    val dotColor = data.identityColorOf(p.who) ?: scheme.primary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).let { m ->
            if (p.ref || id == null) m.semantics(mergeDescendants = true) { contentDescription = "${p.who} : ${fmtNum(debt, 1)} % du PIB" }
            else m.clickable(role = Role.Button, onClickLabel = "Ouvrir la fiche") { nav.sheet(PolSheetState.Identity(id)) }
                .semantics(mergeDescendants = true) { contentDescription = "${p.who} : dette ${fmtNum(debt, 1)} % du PIB" }
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.width(134.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.ref) Box(Modifier.size(32.dp).clip(RoundedCornerShape(22)).background(scheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("=", fontFamily = Mono, fontWeight = FontWeight.Bold, color = scheme.onSurfaceVariant) }
            else id?.let { data.byId(it) }?.let { IdentityBadge(it, 32.dp) } ?: Spacer(Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(p.who, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text("${fmtNum(debt, 1)} %", fontFamily = Mono, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = if (p.ref) scheme.onSurfaceVariant else side)
                    Text(" · ${p.party}", fontSize = 11.5.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Canvas(Modifier.weight(1f).height(34.dp)) {
            val w = size.width
            fun x(v: Double) = (axisPos(v, lo, hi) * w).toFloat()
            ticks.forEach { drawLine(scheme.outlineVariant, Offset(x(it), 0f), Offset(x(it), size.height), 1.dp.toPx()) }
            drawLine(scheme.onSurfaceVariant, Offset(x(ref), 0f), Offset(x(ref), size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            if (!p.ref) {
                val a = minOf(x(debt), x(ref))
                val b = maxOf(x(debt), x(ref))
                drawLine(side, Offset(a, size.height / 2), Offset(b, size.height / 2), 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(scheme.surface, 9.dp.toPx(), Offset(x(debt), size.height / 2))
                drawCircle(dotColor, 6.5.dp.toPx(), Offset(x(debt), size.height / 2))
            }
        }
    }
}

@Composable
private fun DetailTable(data: PolData, programs: List<Program>) {
    val scheme = MaterialTheme.colorScheme
    val pc = PolColors.current
    @Composable fun Cell(t: String, w: Dp, head: Boolean = false, color: Color = Color.Unspecified) =
        Text(t, Modifier.width(w), textAlign = TextAlign.End, fontSize = if (head) 10.5.sp else 13.5.sp, fontFamily = if (head) Mono else FontFamily.Default, color = if (head) scheme.onSurfaceVariant else color, lineHeight = 14.sp)
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text("PROGRAMME", Modifier.weight(1f), fontFamily = Mono, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
        Cell("DETTE\nFIN", 54.dp, true); Cell("DÉFICIT\nFIN", 58.dp, true); Cell("EFFET NET\nPAR AN", 74.dp, true)
    }
    programs.forEach { p ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!p.ref) data.idFor(p.who)?.let { IdentityDot(data, it) }
                    Text(p.who, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, fontStyle = if (p.ref) androidx.compose.ui.text.font.FontStyle.Italic else null)
                }
                Text(p.party, fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
            }
            Cell("${fmtNum(p.debt ?: 0.0, 1)} %", 54.dp); Cell(p.deficit?.let { "${fmtNum(it, 1)} %" } ?: "—", 58.dp)
            Cell(p.net?.let { "${fmtSigned(it, 1)} Md€" } ?: "—", 74.dp, color = p.net?.let { if (it >= 0) pc.low else pc.high } ?: Color.Unspecified)
        }
    }
}

@Composable
private fun GapCard(data: PolData, g: Gap, nav: PolNavigator, onTable: () -> Unit) {
    val pc = PolColors.current
    val scheme = MaterialTheme.colorScheme
    val max = maxOf(g.claimed, g.credited)
    val (la, lb) = gapLabels(g)
    val id = data.idFor(g.who)
    FigCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (id != null) IdentityChip(data, id, text = nameOf(g.who)) else Text(nameOf(g.who), fontWeight = FontWeight.Bold)
            Text(gapRatioLabel(g), Modifier.semantics { contentDescription = "Écart : ${if (g.inverse) "multiplié" else "divisé"} par ${gapRatio(g)?.let { fmtNum(it, if (it < 3) 1 else 0) } ?: "une valeur indéfinie"}" }, color = pc.fragile, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, fontFamily = Mono)
        }
        Text(g.what, Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
        @Composable fun PBar(label: String, v: Double, bg: Color) {
            Kicker(label, Modifier.padding(top = 8.dp, bottom = 3.dp))
            Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(if (max > 0) (v / max).toFloat().coerceIn(0f, 1f) else 0f).defaultMinSize(minWidth = 96.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    Text("${fmtNum(v, 1)} ${g.unit}".trim(), color = textOn(bg), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, softWrap = false)
                }
            }
        }
        PBar(la, g.claimed, scheme.onSurface)
        PBar(lb, g.credited, pc.pour)
        g.lecture?.let { Bal(it, g.what, g.who, nav) }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!g.src.isNullOrBlank()) SourceButton(SourceRef(g.src, g.url, g.ev), data, { nav.sheet(PolSheetState.Evaluator(it)) }, Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f)); TableLink(onTable)
        }
    }
}

// ---------- Niveau de vie ----------

@Composable
fun LivingScreen(data: PolData, nav: PolNavigator) {
    val pc = PolColors.current
    val scheme = MaterialTheme.colorScheme
    val salaires = remember(data) { data.decode<BilanRow>("niveau-de-vie", "SALAIRES.rows") }
    val sScale = remember(data) { data.one("niveau-de-vie", "SALAIRES.scale", Scale.serializer()) ?: Scale(-2.0, 2.0, " %") }
    val programs = remember(data) { data.decode<Program>("budgets", "PROGRAMS").filter { !it.ref && it.hh != null }.sortedByDescending { it.hh } }
    val ref = remember(data) { data.one("budgets", "REF", Double.serializer()) }
    val measures = remember(data) { data.decode<Measure>("niveau-de-vie", "MEASURES") }
    val maxAbs = remember(programs) { programs.maxOfOrNull { kotlin.math.abs(it.hh!!) } ?: 0.0 }
    val cards = remember(measures) {
        measures.map { m ->
            TableCard("Pouvoir d'achat", nameOf(m.who), lines = listOf("${fmtSigned(m.hh)} € par an") + m.m.split(" ; ").filter { it.isNotBlank() } + listOf("Pour qui : ${m.b}", "Financé par : ${m.f}"))
        }
    }
    if (salaires.isEmpty() && programs.isEmpty() && measures.isEmpty()) {
        EmptyState("Niveau de vie pas encore publié", "Ni les salaires réels, ni les gains par ménage ne sont disponibles pour l'instant.", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part {
            Section("Salaires", "Les salaires face à l'inflation", "Évolution du salaire net moyen réel dans le privé, après inflation.") {
                if (salaires.isEmpty()) EmptyState("Aucune série de salaires", "La série des salaires réels n'est pas disponible pour l'instant.")
                else FigCard { HBars(salaires, sScale, 60.dp) { if (it.v < 0) pc.high else pc.low } }
            }
        }
        if (salaires.any { it.lecture != null }) part {
            Section("La balance", "Ce que ces moyennes laissent dehors", "Pour chaque chiffre : ce qu'il sacrifie, ce qu'il ne montre pas, et l'avis signé des évaluateurs.") {
                BalanceRows(salaires, { it.l }, { it.lecture }, { null }, data, nav)
            }
        }
        part {
            Section("Programmes", "Gain par ménage, et dette à l'horizon", "Gain ou perte <b>moyenne</b> par ménage et par an, puis la dette que le programme laisse à l'horizon. Une moyenne peut cacher des gagnants et des perdants très différents.") {
                if (programs.isEmpty()) EmptyState("Aucun gain chiffré", "Aucun programme n'a encore de gain par ménage publié.")
                else FigCard {
                    programs.forEachIndexed { i, p ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                        HouseholdRow(data, p, ref, maxAbs, nav)
                    }
                }
            }
        }
        part {
            Section("Mesure par mesure", "Ce que chacun promet", "Faites défiler. Mêmes rubriques pour tous : mesures, bénéficiaires, financement.") {
                if (measures.isEmpty()) EmptyState("Aucune mesure détaillée", "Aucun programme n'a encore ses mesures de pouvoir d'achat publiées.")
                else Rail(measures, 300.dp) { i, m -> MeasureCard(data, m, nav) { nav.table(cards, i) } }
            }
        }
    }
}

@Composable
private fun HouseholdRow(data: PolData, p: Program, ref: Double?, maxAbs: Double, nav: PolNavigator) {
    val pc = PolColors.current
    val scheme = MaterialTheme.colorScheme
    val hh = p.hh!!
    val bar = householdBar(hh, maxAbs)
    val id = data.idFor(p.who)
    val color = data.identityColorOf(p.who) ?: scheme.primary
    val below = p.debt?.let { d -> ref?.let { d < it } }
    val debtLine = p.debt?.let { d -> ref?.let { "dette : ${fmtNum(d, 1)} % · ${if (d < it) "sous" else "au-dessus de"} la référence" } }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).let { m -> if (id != null) m.clickable(role = Role.Button, onClickLabel = "Ouvrir la fiche") { nav.sheet(PolSheetState.Identity(id)) } else m }
                .semantics(mergeDescendants = true) { contentDescription = "${p.who} : ${fmtSigned(hh)} euros par an et par ménage" }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (id != null) data.byId(id)?.let { IdentityBadge(it, 34.dp) } else Spacer(Modifier.size(34.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(p.who, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, modifier = Modifier.weight(1f, fill = false))
                    Text("${fmtSigned(hh)} €/an", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Canvas(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(10.dp)) {
                    drawRoundRect(scheme.surfaceVariant, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    drawRoundRect(color, Offset((bar.start * size.width).toFloat(), 0f), Size((bar.width * size.width).toFloat(), size.height), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    drawLine(scheme.onSurface.copy(alpha = 0.5f), Offset(size.width / 2, -3.dp.toPx()), Offset(size.width / 2, size.height + 3.dp.toPx()), 2.dp.toPx())
                }
                if (debtLine != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (below == true) pc.low else pc.high))
                    Text(debtLine, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
        p.lecture?.let { lec -> Bal(lec, "${p.who} : ${fmtSigned(hh)} € par ménage et par an", p.who, nav, Modifier.padding(start = 44.dp)) }
    }
}

@Composable
private fun MeasureCard(data: PolData, m: Measure, nav: PolNavigator, onTable: () -> Unit) {
    val pc = PolColors.current
    val scheme = MaterialTheme.colorScheme
    val id = data.idFor(m.who)
    val accent = data.identityColorOf(m.who) ?: scheme.primary
    val pill = if (m.hh >= 0) pc.low else pc.high
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = scheme.surface, tonalElevation = 1.dp, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (id != null) data.byId(id)?.let { IdentityBadge(it, 40.dp) }
                    Column {
                        Text(nameOf(m.who), fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 19.sp)
                        data.partyLabel(m.who).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.5.sp, color = scheme.onSurfaceVariant) }
                    }
                }
                Text("${fmtSigned(m.hh)} €/an par ménage", Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).background(pill.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 6.dp), color = pill, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Column(Modifier.padding(top = 14.dp)) {
                    Kicker("Mesures")
                    m.m.split(" ; ").filter { it.isNotBlank() }.forEach { Text("• ${frTypo(it)}", Modifier.padding(top = 3.dp), fontSize = 14.sp, lineHeight = 19.sp) }
                }
                Column(Modifier.padding(top = 14.dp)) { Kicker("Pour qui"); Text(frTypo(m.b), Modifier.padding(top = 3.dp), fontSize = 14.sp, lineHeight = 19.sp) }
                Column(Modifier.padding(top = 14.dp)) { Kicker("Financé par"); Text(frTypo(m.f), Modifier.padding(top = 3.dp), fontSize = 14.sp, lineHeight = 19.sp) }
                m.lecture?.let { lec -> Bal(lec, "${nameOf(m.who)} : mesures de pouvoir d'achat", m.who, nav) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TableLink(onTable) }
            }
        }
    }
}

// ---------- Bilan sortant ----------

private class Mark(val label: String, val fg: Color, val bg: Color)

@Composable
private fun mark(code: String?): Mark {
    val pc = PolColors.current
    return when (code) {
        "keep" -> Mark("Poursuit", pc.pour, pc.pourBg)
        "flip" -> Mark("Inverse", pc.contre, pc.contreBg)
        "mix" -> Mark("Modifie", pc.partiel, pc.partielBg)
        else -> Mark("Non trouvé", MaterialTheme.colorScheme.onSurfaceVariant, Color.Transparent)
    }
}

@Composable
private fun MarkPill(code: String?) {
    val m = mark(code)
    Text(m.label.uppercase(), Modifier.clip(CircleShape).background(m.bg).let { if (m.bg == Color.Transparent) it.border(BorderStroke(1.dp, m.fg.copy(alpha = 0.4f)), CircleShape) else it }.padding(horizontal = 8.dp, vertical = 5.dp), color = m.fg, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, softWrap = false)
}

private val MARK_LABEL = mapOf("keep" to "Poursuit", "flip" to "Inverse", "mix" to "Modifie")

@Composable
fun BilanScreen(data: PolData, nav: PolNavigator) {
    val pc = PolColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val ofce = remember(data) { data.decode<BilanRow>("bilan", "OFCE.rows") }
    val ipp = remember(data) { data.decode<BilanRow>("bilan", "IPP.rows") }
    val ofceScale = remember(data) { data.one("bilan", "OFCE.scale", Scale.serializer()) ?: Scale(-3.0, 14.0, " pt") }
    val ippScale = remember(data) { data.one("bilan", "IPP.scale", Scale.serializer()) ?: Scale(0.0, 4.0, " %") }
    val cands = remember(data) { data.decode("bilan", "CANDS", String.serializer()) }
    val matrix = remember(data) { data.decode<MatrixRow>("bilan", "MATRIX") }
    val cards = remember(matrix, cands) {
        matrix.map { r ->
            TableCard("Face au bilan sortant · ${r.s}", r.o, lines = cands.map { c -> r.c[c]?.let { x -> "$c — ${MARK_LABEL[x.firstOrNull()] ?: "Position"} : ${x.getOrNull(1).orEmpty()}" } ?: "$c : non trouvé" })
        }
    }
    if (ofce.isEmpty() && ipp.isEmpty() && matrix.isEmpty()) {
        EmptyState("Bilan pas encore publié", "Ni la décomposition de la dette, ni les positions face au bilan sortant ne sont disponibles pour l'instant.", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part {
            Section("Dette", "D'où vient la hausse de la dette ?", "Décomposition de la hausse de la dette du mandat sortant, par grande cause, selon un évaluateur extérieur.") {
                if (ofce.isEmpty()) EmptyState("Aucune décomposition", "La décomposition de la dette n'est pas disponible pour l'instant.")
                else FigCard {
                    Legend(listOf(pc.high to "Fait monter la dette", pc.low to "La fait baisser"))
                    HBars(ofce, ofceScale, 118.dp) { if (it.total) ink else if (it.v >= 0) pc.high else pc.low }
                }
            }
        }
        part {
            Section("Niveau de vie", "Qui a gagné quoi ?", "Effet des mesures fiscales et sociales du mandat sortant, selon un institut de recherche indépendant.", "Ces études ne couvrent pas les années suivantes.") {
                if (ipp.isEmpty()) EmptyState("Aucune étude de répartition", "Aucun chiffrage de l'effet sur le niveau de vie n'est disponible pour l'instant.")
                else FigCard { HBars(ipp, ippScale, 118.dp) { pc.pour } }
            }
        }
        if ((ofce + ipp).any { it.lecture != null }) part {
            Section("La balance", "Ce que ces chiffres laissent dehors", "Pour chaque chiffre : ce qu'il sacrifie, ce qu'il ne montre pas, et l'avis signé des évaluateurs.") {
                BalanceRows(ofce + ipp, { it.l }, { it.lecture }, { null }, data, nav)
            }
        }
        part {
            Section("Et après ?", "Qui continue la ligne, qui la renverse", "Une carte par grande orientation du mandat sortant. Mêmes candidats, même ordre, à chaque carte.", "« Non trouvé » : aucune position dans une source fiable, ce qui ne veut pas dire aucune position.") {
                if (matrix.isEmpty()) EmptyState("Aucune orientation suivie", "Les positions des candidats face au bilan sortant ne sont pas encore publiées.")
                else Column {
                    Legend(listOf("keep", "flip", "mix", "none").map { c -> mark(c).let { it.fg to it.label } })
                    Rail(matrix, 318.dp) { i, r -> MatrixCard(data, r, cands, i, matrix.size, nav) { nav.table(cards, i) } }
                }
            }
        }
    }
}

@Composable
private fun MatrixCard(data: PolData, r: MatrixRow, cands: List<String>, i: Int, n: Int, nav: PolNavigator, onTable: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FigCard {
        Kicker("${i + 1} / $n")
        Text(frTypo(r.o), Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp)
        if (r.s.isNotBlank()) Text(r.s, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
        cands.forEach { c ->
            val x = r.c[c]
            val id = data.idFor(c)
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
            Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (id != null) data.byId(id)?.let { IdentityBadge(it, 30.dp) }
                Text(c, Modifier.weight(1f), fontWeight = if (x != null) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, color = if (x != null) Color.Unspecified else scheme.onSurfaceVariant)
                MarkPill(x?.firstOrNull())
            }
            if (x != null && x.getOrNull(1)?.isNotBlank() == true) Text(frTypo(x[1]), Modifier.padding(top = 4.dp), fontSize = 13.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant)
        }
        r.lecture?.let { lec -> Bal(lec, r.o, null, nav) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TableLink(onTable) }
    }
}
