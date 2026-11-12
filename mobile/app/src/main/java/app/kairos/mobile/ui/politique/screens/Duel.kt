package app.kairos.mobile.ui.politique.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.Attn
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolRoute
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.duelNight
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.BalanceBlock
import app.kairos.mobile.ui.politique.kit.BalanceHeader
import app.kairos.mobile.ui.politique.kit.LocalPolBottom
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.reducedMotion
import app.kairos.mobile.ui.politique.rubricTitle
import app.kairos.mobile.ui.theme.mix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace
private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val Neutral = Color(0xFF8A938A)
private val HudInk = Color(0xFFF4F6F2)

private fun PolData.colorOf(id: String): Color = identityColor(byId(id)?.couleur) ?: Neutral

/** Sous-titre d'un adversaire : le parti d'une personne, le nom complet d'un parti. */
private fun PolData.subOf(id: String): String = byId(id)?.let { if (kindOf(id) == IdKind.Personne) it.parti.orEmpty() else it.nom }.orEmpty()

/** Duel : choix des deux adversaires, puis rounds face à face. */
@Composable
fun DuelScreen(data: PolData, nav: PolNavigator, a: String?, b: String?) {
    val ready = remember(data, a, b) { duelReady(data, a, b) }
    if (ready) DuelHud(data, nav, a!!, b!!) else DuelPick(data, nav)
}

// ---------- Choix ----------

private class Rosters(val people: List<String>, val parties: List<String>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DuelPick(data: PolData, nav: PolNavigator) {
    val rosters by produceState<Rosters?>(null, data) {
        value = withContext(Dispatchers.Default) { Rosters(duelRoster(data, IdKind.Personne), duelRoster(data, IdKind.Parti)) }
    }
    val r = rosters ?: return Loading("Chargement des adversaires")
    if (r.people.isEmpty() && r.parties.isEmpty()) {
        EmptyState("Aucun duel possible", "Il faut deux candidats ou deux partis qui ont des données comparables. Les rubriques restent ouvertes depuis l'accueil.", Modifier.padding(16.dp))
        return
    }
    var sel by rememberSaveable { mutableStateOf(listOf<String>()) }
    val kind = sel.firstOrNull()?.let { if (it in r.people) IdKind.Personne else IdKind.Parti }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = ScreenPad) {
            item {
                Text("Choisissez deux adversaires", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slot(data, sel.getOrNull(0), "Premier choix", next = sel.isEmpty(), Modifier.weight(1f))
                    Text("VS", Modifier.width(44.dp).clearAndSetSemantics { }, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 24.sp, letterSpacing = (-0.04).em)
                    Slot(data, sel.getOrNull(1), "Adversaire", next = sel.size == 1, Modifier.weight(1f))
                }
                Text("Deux candidats, ou deux partis : chaque duel compare ce qui est comparable.", Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOf(IdKind.Personne to r.people, IdKind.Parti to r.parties).forEach { (k, ids) ->
                if (ids.isEmpty()) return@forEach
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.Bottom) {
                        Text(if (k == IdKind.Personne) "Candidats" else "Partis", Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Kicker(ids.size.toString())
                    }
                }
                items(ids.chunked(4)) { chunk ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chunk.forEach { id ->
                            val i = sel.indexOf(id)
                            RosterButton(data, id, tag = if (i < 0) null else if (i == 0) "A" else "B", enabled = kind == null || kind == k || i >= 0, Modifier.weight(1f)) { sel = pickToggle(sel, id) }
                        }
                        repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = LocalPolBottom.current).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                val k = kind ?: if (r.people.size >= 2) IdKind.Personne else IdKind.Parti
                val pool = (if (k == IdKind.Personne) r.people else r.parties).shuffled()
                val first = sel.firstOrNull() ?: pool.firstOrNull()
                val second = pool.firstOrNull { it != first }
                sel = listOfNotNull(first, second)
            }) { Text("Au hasard") }
            Button({ nav.go(PolRoute.Duel(sel[0], sel[1])) }, Modifier.weight(1f), enabled = sel.size == 2) { Text("Lancer le duel") }
        }
    }
}

@Composable
private fun Slot(data: PolData, id: String?, empty: String, next: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val x = id?.let { data.byId(it) }
    val shape = RoundedCornerShape(20.dp)
    if (x == null) {
        Column(
            modifier.height(150.dp).clip(shape).background(scheme.surfaceVariant).border(if (next) 2.5.dp else 2.dp, if (next) Attn else scheme.outlineVariant, shape),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(22)).border(2.dp, scheme.outlineVariant, RoundedCornerShape(22)), contentAlignment = Alignment.Center) {
                Text("?", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurfaceVariant)
            }
            Text(empty, Modifier.padding(top = 8.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        return
    }
    val c = data.colorOf(x.id)
    Column(
        modifier.height(150.dp).clip(shape).background(mix(scheme.surface, c, 0.14f)).border(3.dp, c, shape).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        IdentityBadge(x, 72.dp)
        Text(duelName(data, x.id), fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (data.kindOf(x.id) == IdKind.Personne) x.parti.orEmpty() else "Parti", fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RosterButton(data: PolData, id: String, tag: String?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val x = data.byId(id) ?: return
    val name = duelName(data, id)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (tag != null) scheme.surface else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                selected = tag != null
                contentDescription = name + (tag?.let { ", adversaire $it" } ?: "")
                if (!enabled) disabled()
            }
            .alpha(if (enabled) 1f else 0.35f).padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            IdentityBadge(x, 56.dp)
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (tag != null) Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp).size(20.dp).clip(CircleShape).background(scheme.onSurface), contentAlignment = Alignment.Center) {
            Text(tag, color = scheme.surface, fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Loading(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}

// ---------- Face à face ----------

/** Les deux côtés d'un duel : nom court, couleur, hachures du second si les couleurs se confondent. */
private class Sides(val a: String, val b: String, val nameA: String, val nameB: String, val colorA: Color, val colorB: Color, val hatch: Boolean) {
    fun id(i: Int) = if (i == 0) a else b
    fun name(i: Int) = if (i == 0) nameA else nameB
    fun color(i: Int) = if (i == 0) colorA else colorB
    fun hatched(i: Int) = i == 1 && hatch
}

@Composable
private fun DuelHud(data: PolData, nav: PolNavigator, a: String, b: String) {
    val rounds by produceState<List<DuelRound>?>(null, data, a, b) { value = withContext(Dispatchers.Default) { duelRounds(data, a, b) } }
    val r = rounds ?: return Loading("Préparation du duel")
    val s = remember(data, a, b) { Sides(a, b, duelName(data, a), duelName(data, b), data.colorOf(a), data.colorOf(b), hatchSecond(data.byId(a)?.couleur, data.byId(b)?.couleur)) }
    val still = reducedMotion()
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!still) { flash = true; delay(1100); flash = false } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Hud(data, s, r, still)
            if (r.isEmpty()) {
                EmptyState("Rien à comparer", "Ces deux adversaires n'ont aucune donnée commune pour l'instant.", Modifier.padding(16.dp), "Changer d'adversaires" to { nav.replace(PolRoute.Duel()) })
                return@Column
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, LocalPolBottom.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.size} rounds · aucun vainqueur désigné", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ nav.replace(PolRoute.Duel()) }) { Text("Changer d'adversaires") }
                    }
                    Legend(s)
                }
                itemsIndexed(r) { i, round -> RoundCard(data, nav, s, i, round, still) }
                item { NeutralNote() }
            }
        }
        AnimatedVisibility(flash, Modifier.align(Alignment.Center), enter = scaleIn(initialScale = 2.2f), exit = fadeOut()) {
            Text("DUEL !", Modifier.clearAndSetSemantics { }, color = Attn, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 54.sp, letterSpacing = (-0.04).em)
        }
    }
}

@Composable
private fun Hud(data: PolData, s: Sides, r: List<DuelRound>, still: Boolean) {
    val (ca, cb) = coverage(r)
    val n = r.size.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().background(duelNight()).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Fighter(data, s.a, s.nameA, s.colorA, mirrored = false, Modifier.weight(1f))
            Text("VS", Modifier.padding(horizontal = 8.dp).clearAndSetSemantics { }, color = Attn, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, fontSize = 28.sp)
            Fighter(data, s.b, s.nameB, s.colorB, mirrored = true, Modifier.weight(1f))
        }
        val caption = coverageCaption(r) ?: return@Column
        var go by remember { mutableStateOf(still) }
        LaunchedEffect(Unit) { if (!still) { delay(500); go = true } }
        val pa by animateFloatAsState(if (go) ca.toFloat() / n else 0f, tween(if (still) 0 else 700, easing = Ease), label = "couverture A")
        val pb by animateFloatAsState(if (go) cb.toFloat() / n else 0f, tween(if (still) 0 else 700, easing = Ease), label = "couverture B")
        Row(Modifier.padding(top = 10.dp).clearAndSetSemantics { contentDescription = "Données trouvées : ${s.nameA} $ca sur ${r.size}, ${s.nameB} $cb sur ${r.size}. Couverture, pas un score." }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CovBar(pa, s.colorA, fromEnd = false, Modifier.weight(1f))
            CovBar(pb, s.colorB, fromEnd = true, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp).clearAndSetSemantics { }) {
            Text(caption.first.uppercase(), Modifier.weight(1f), color = HudInk.copy(alpha = 0.7f), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.06.em)
            Text(caption.second.uppercase(), color = HudInk.copy(alpha = 0.7f), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 0.06.em)
        }
    }
}

@Composable
private fun Fighter(data: PolData, id: String, name: String, color: Color, mirrored: Boolean, modifier: Modifier) {
    val x = data.byId(id) ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, if (mirrored) Alignment.End else Alignment.Start)) {
        val badge = @Composable {
            Box(Modifier.border(3.dp, color, RoundedCornerShape(12.dp)).padding(3.dp)) {
                IdentityBadge(x, 40.dp, if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
            }
        }
        if (!mirrored) badge()
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start) {
            Text(name, color = HudInk, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(data.subOf(id), color = HudInk.copy(alpha = 0.75f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (mirrored) badge()
    }
}

@Composable
private fun CovBar(p: Float, color: Color, fromEnd: Boolean, modifier: Modifier) {
    Canvas(modifier.height(8.dp)) {
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(Color.White.copy(alpha = 0.15f), cornerRadius = r)
        val w = size.width * p.coerceIn(0f, 1f)
        drawRoundRect(color, topLeft = Offset(if (fromEnd) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = r)
    }
}

/** Remplissage d'identité ; [hatch] : rayures claires par-dessus, pour distinguer deux couleurs proches. */
private fun DrawScope.idFill(color: Color, hatch: Boolean, topLeft: Offset, size: Size, radius: Float) {
    drawRoundRect(color, topLeft, size, CornerRadius(radius))
    if (!hatch || size.width <= 0f) return
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        val step = 6.dp.toPx()
        var x = topLeft.x - size.height
        while (x < topLeft.x + size.width) {
            drawLine(Color.White.copy(alpha = 0.55f), Offset(x, topLeft.y + size.height), Offset(x + size.height, topLeft.y), strokeWidth = 2.dp.toPx())
            x += step
        }
    }
}

@Composable
private fun Swatch(color: Color, hatch: Boolean) {
    Canvas(Modifier.size(12.dp)) { idFill(color, hatch, Offset.Zero, size, 3.dp.toPx()) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(s: Sides) {
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..1).forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Swatch(s.color(i), s.hatched(i))
                Text(s.name(i) + if (s.hatched(i)) " (hachuré : couleurs proches)" else "", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RoundCard(data: PolData, nav: PolNavigator, s: Sides, i: Int, round: DuelRound, still: Boolean) {
    // Entrée au défilement : l'élément n'est composé qu'une fois visible.
    val enter = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) enter.animateTo(1f, tween(450, easing = Ease)) }
    Surface(
        Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationY = (1 - enter.value) * 16.dp.toPx() }
            .semantics(mergeDescendants = false) { contentDescription = "Round ${i + 1} : ${round.title}" },
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Round ${i + 1}", Modifier.weight(1f))
                if (round is BarRound) TableLink { nav.table(listOf(round.tableCard(s.nameA, s.nameB)), 0) }
            }
            Text(frTypo(round.title), Modifier.semantics { heading() }, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp)
            if (round.help.isNotBlank()) Text(frTypo(round.help), Modifier.padding(top = 3.dp), fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (round) {
                is BarRound -> BarBody(data, nav, s, round, still)
                is BilanRound -> BilanBody(data, s, round)
                is GapRound -> GapBody(data, nav, s, round)
                is VotesRound -> VotesBody(s, round)
                is DvRound -> DvBody(data, nav, s, round)
                is TurnRound -> TurnBody(data, s, round)
            }
            round.rubric?.let { id ->
                TextButton({ nav.go(PolRoute.Rubric(id)) }, Modifier.padding(top = 4.dp)) { Text("Rubrique ${rubricTitle(id)} ›") }
            }
        }
    }
}

@Composable
private fun BarBody(data: PolData, nav: PolNavigator, s: Sides, r: BarRound, still: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var go by remember { mutableStateOf(still) }
    LaunchedEffect(Unit) { if (!still) { delay(150); go = true } }
    listOf(r.a, r.b).forEachIndexed { i, v ->
        val p by animateFloatAsState(if (go) 1f else 0f, tween(if (still) 0 else 800, delayMillis = if (still) 0 else i * 140, easing = Ease), label = "barre")
        Column(Modifier.padding(top = 12.dp).semantics(mergeDescendants = true) { contentDescription = "${s.name(i)} : ${r.label(v)}" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(s.color(i), s.hatched(i))
                Text(s.name(i), Modifier.weight(1f).padding(start = 7.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (v == null) Text(NO_VALUE, fontSize = 13.sp, color = scheme.onSurfaceVariant)
                else Text(r.label(v), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            val track = scheme.surfaceVariant
            val line = scheme.outlineVariant
            val ink = scheme.onSurface
            val ghost = scheme.onSurfaceVariant.copy(alpha = 0.45f)
            Canvas(Modifier.padding(top = 4.dp).fillMaxWidth().height(18.dp)) {
                val rad = 6.dp.toPx()
                drawRoundRect(track, cornerRadius = CornerRadius(rad))
                drawRoundRect(line, cornerRadius = CornerRadius(rad), style = Stroke(1.dp.toPx()))
                r.others.forEach { o -> drawCircle(ghost, 3.dp.toPx(), Offset(size.width * r.pos(o).toFloat(), size.height / 2)) }
                val z = size.width * r.pos(r.base).toFloat()
                if (v != null) {
                    val x = size.width * r.pos(v).toFloat()
                    val end = z + (x - z) * p
                    val inset = 3.dp.toPx()
                    idFill(s.color(i), s.hatched(i), Offset(minOf(z, end), inset), Size(kotlin.math.abs(end - z), size.height - 2 * inset), 4.dp.toPx())
                }
                drawLine(ink.copy(alpha = 0.55f), Offset(z, -4.dp.toPx()), Offset(z, size.height + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics { }) {
        val st = scheme.onSurfaceVariant
        Text(r.format(r.min), fontFamily = Mono, fontSize = 10.5.sp, color = st)
        Text("trait : ${if (r.base != 0.0) r.format(r.base) else "zéro"}", Modifier.weight(1f), textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 10.5.sp, color = st)
        Text(r.format(r.max), fontFamily = Mono, fontSize = 10.5.sp, color = st)
    }
    if (r.others.isNotEmpty()) Text("Points gris : les autres (${r.others.size}), pour situer sans classer.", Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
    SourceList(r.sources, data, { nav.sheet(PolSheetState.Evaluator(it)) })
    r.sacrifice?.let { sac ->
        BalanceHeader("Ce que chaque programme sacrifie", modifier = Modifier.padding(top = 8.dp))
        Sided(data, s, layoutFor(r)) { i ->
            val (lec, who) = if (i == 0) sac.a to sac.whoA else sac.b to sac.whoB
            if (lec != null) Bal(nav, lec, who ?: s.name(i), who) else Na("Balance non documentée.")
        }
    }
}

@Composable
private fun Bal(nav: PolNavigator, lecture: Lecture, title: String, who: String?) =
    BalanceBlock(lecture, title, onOpen = nav.balance(lecture, title, who), modifier = Modifier.padding(top = 0.dp))

@Composable
private fun Na(text: String) = Text(text, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Côtés d'un round selon [layout] : deux cases côte à côte, ou onglets glissables. */
@Composable
private fun Sided(data: PolData, s: Sides, layout: RoundLayout, content: @Composable (Int) -> Unit) =
    if (layout == RoundLayout.Switcher) SideSwitcher(data, s, content) else HitRow(s, content)

/** Case teintée à l'identité d'un côté, liseré à gauche. */
@Composable
private fun SideCell(color: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(mix(MaterialTheme.colorScheme.surfaceVariant, color, 0.12f))
            .drawBehind { drawRect(color, size = Size(4.dp.toPx(), size.height)) }
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
    ) { content() }
}

/**
 * Cases [shown] posées côte à côte, à la hauteur de la plus haute des deux côtés.
 * Pas d'intrinsèque (une `Row` y compte une seule ligne et rognait la case) : chaque côté est
 * d'abord mesuré à blanc (ni posé, ni dessiné, ni lu), puis la case réelle reçoit la hauteur commune.
 */
@Composable
private fun SameHeight(s: Sides, shown: List<Int>, gap: Dp, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    val cell = @Composable { i: Int, m: Modifier -> SideCell(s.color(i), m) { content(i) } }
    SubcomposeLayout(modifier.fillMaxWidth()) { c ->
        val g = gap.roundToPx()
        val w = ((c.maxWidth - g * (shown.size - 1)) / shown.size).coerceAtLeast(0)
        val probe = Constraints(minWidth = w, maxWidth = w)
        val h = (0..1).maxOf { i -> subcompose("probe$i") { cell(i, Modifier.clearAndSetSemantics { }) }.maxOfOrNull { it.measure(probe).height } ?: 0 }
        val placed = shown.map { i -> subcompose("side$i") { cell(i, Modifier) }.map { it.measure(Constraints.fixed(w, h)) } }
        layout(c.maxWidth, h) { placed.forEachIndexed { k, ps -> ps.forEach { it.place(k * (w + g), 0) } } }
    }
}

/** Deux cases côte à côte, de même hauteur. */
@Composable
private fun HitRow(s: Sides, content: @Composable (Int) -> Unit) =
    SameHeight(s, listOf(0, 1), 8.dp, Modifier.padding(top = 8.dp), content)

/**
 * Un côté à la fois, pour le texte long : onglets aux couleurs des adversaires, contenu glissable.
 * L'onglet de l'autre côté et le bord de sa case, visible à droite, disent qu'il y a une suite.
 */
@Composable
private fun SideSwitcher(data: PolData, s: Sides, content: @Composable (Int) -> Unit) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    val cur = pager.currentPage
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        PrimaryTabRow(
            selectedTabIndex = cur, containerColor = Color.Transparent,
            indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(cur, matchContentSize = true), width = Dp.Unspecified, color = s.color(cur)) },
        ) {
            (0..1).forEach { i ->
                Tab(
                    selected = cur == i,
                    onClick = { scope.launch { if (still) pager.scrollToPage(i) else pager.animateScrollToPage(i) } },
                    modifier = Modifier.semantics { contentDescription = "Côté ${if (i == 0) "A" else "B"} : ${s.name(i)}" },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.byId(s.id(i))?.let { x -> Box(Modifier.border(2.dp, s.color(i), RoundedCornerShape(8.dp)).padding(2.dp)) { IdentityBadge(x, 24.dp) } }
                        Text(s.name(i), fontWeight = if (cur == i) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Le pager ne prend que le glissement horizontal : le défilement vertical reste à la liste.
        HorizontalPager(pager, Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(end = 28.dp), pageSpacing = 8.dp, verticalAlignment = Alignment.Top) { i ->
            // Même hauteur pour les deux pages : pas de saut de la liste au changement de côté.
            SameHeight(s, listOf(i), 0.dp, content = content)
        }
    }
}

@Composable
private fun BilanBody(data: PolData, s: Sides, r: BilanRound) {
    Sided(data, s, layoutFor(r)) { i ->
        r.lines.forEachIndexed { j, l ->
            val x = if (i == 0) l.a else l.b
            Text(frTypo(l.o), Modifier.padding(top = if (j > 0) 14.dp else 0.dp, bottom = 5.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            MarkPill(x?.firstOrNull())
            if (x != null) Text(frTypo(x.getOrNull(1).orEmpty()), Modifier.padding(top = 5.dp), fontSize = 13.sp, lineHeight = 18.sp)
            else Box(Modifier.padding(top = 5.dp)) { Na("Aucune position dans une source fiable.") }
        }
    }
}

@Composable
private fun GapBody(data: PolData, nav: PolNavigator, s: Sides, r: GapRound) {
    Sided(data, s, layoutFor(r)) { i ->
        val gaps = if (i == 0) r.a else r.b
        if (gaps.isEmpty()) Na("Aucun chiffrage extérieur relevé.")
        gaps.forEachIndexed { j, g ->
            val (la, lb) = gapLabels(g)
            Text(frTypo(g.what), Modifier.padding(top = if (j > 0) 12.dp else 0.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("$la ${g.claimed?.let { fmtNum(it, 1) } ?: NO_VALUE} · ${lb.lowercase()} ${g.credited?.let { fmtNum(it, 1) } ?: NO_VALUE} ${g.unit}".trim(), Modifier.padding(top = 4.dp), fontFamily = Mono, fontSize = 12.5.sp)
            g.lecture?.let { Bal(nav, it, g.what, g.who) }
            g.src?.takeIf { it.isNotBlank() }?.let { SourceList(listOf(SourceRef(it, g.url, g.ev)), data, { id -> nav.sheet(PolSheetState.Evaluator(id)) }, Modifier.padding(top = 0.dp)) }
        }
    }
}

@Composable
private fun VotesBody(s: Sides, r: VotesRound) {
    val scheme = MaterialTheme.colorScheme
    if (r.groupA == null || r.groupB == null) {
        EmptyState("Pas de comparaison possible", "${if (r.groupA == null) s.nameA else s.nameB} n'a pas de groupe dans ces scrutins.", Modifier.padding(top = 10.dp))
        return
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.both > 0) {
            Text("${r.same} / ${r.both}", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, softWrap = false)
            Text("scrutins où les deux groupes ont voté pareil", fontSize = 14.sp)
        } else Na("Aucun scrutin où les deux groupes ont voté : impossible de comparer.")
    }
    r.lines.forEach { l ->
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
        Text(
            frTypo(l.t) + if (l.same) " · même vote" else "", Modifier.padding(top = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { VoteTag(l.a) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { VoteTag(l.b) }
        }
    }
}

@Composable
private fun DvBody(data: PolData, nav: PolNavigator, s: Sides, r: DvRound) {
    val scheme = MaterialTheme.colorScheme
    Sided(data, s, layoutFor(r)) { i ->
        val items = if (i == 0) r.a else r.b
        val party = if (i == 0) r.partyA else r.partyB
        if (party == null || items.isEmpty()) { Na("Aucun scrutin croisé pour ce parti."); return@Sided }
        Kicker(duelName(data, party))
        val k = dvCount(items)
        val rate = publicRateLabel(k)
        if (rate == null) Text(NO_PUBLIC_STATEMENT, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = scheme.onSurfaceVariant)
        else {
            Text(rate.figure, Modifier.padding(top = 4.dp), fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 29.sp)
            Text("votes conformes à la parole publique", fontSize = 13.sp)
            Text("${frCount(k.e, "écart", "écarts")} · ${frCount(k.a, "ambigu", "ambigus")}", Modifier.padding(top = 6.dp), fontSize = 13.sp)
        }
        if (k.sp > 0) Box(Modifier.padding(top = 4.dp)) { Na("${frCount(k.sp, "scrutin", "scrutins")} sans déclaration trouvée, non comptés") }
        items.filter { it.verdict.v == "ecart" }.forEach { d ->
            // Pas de TextButton : sa forme en pilule rognait un titre sur plusieurs lignes.
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Ouvrir le scrutin") { nav.sheet(PolSheetState.Scrutin(d.scrutin.id)) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Écart : ${frTypo(d.scrutin.titre)}", color = scheme.primary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnBody(data: PolData, s: Sides, r: TurnRound) {
    Sided(data, s, layoutFor(r)) { i ->
        val l = if (i == 0) r.a else r.b
        if (l.isEmpty()) { Na("Aucune position suivie."); return@Sided }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TURN_KINDS.forEach { (k, label) ->
                val n = l.count { it.k == k }
                val (fg, bg) = turnColors(k)
                if (n > 0) Chip("$n ${label.lowercase()}${if (n > 1) "s" else ""}", fg, bg)
            }
        }
        Text(l.joinToString(" · ") { it.s }, Modifier.padding(top = 6.dp), fontSize = 13.sp)
    }
}

@Composable
private fun NeutralNote() {
    val scheme = MaterialTheme.colorScheme
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface)); append("Pas de vainqueur."); pop()
            append(" Une barre plus longue n'est ni meilleure ni pire : elle montre un chiffre. À chacun de peser ce qui compte pour lui.")
        },
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceVariant).border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp),
        fontSize = 13.5.sp, color = scheme.onSurfaceVariant,
    )
}
