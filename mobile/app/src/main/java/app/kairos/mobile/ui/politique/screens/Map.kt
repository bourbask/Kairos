package app.kairos.mobile.ui.politique.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.StageTag
import app.kairos.mobile.ui.politique.StateTag
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PillOption
import app.kairos.mobile.ui.politique.kit.Pills
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.RichText
import app.kairos.mobile.ui.politique.kit.SectionHeader
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.plural
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.hypot
import kotlin.math.min

private val Mono = FontFamily.Monospace
private val NO_PARTY = Color(0xFF8A938A)
private val CubicOut = Easing { 1 - (1 - it) * (1 - it) * (1 - it) }

private fun PolData.quiColor(qui: String): Color = identityColor(byId(qui)?.couleur) ?: NO_PARTY

/** Fenêtre de carte posée dans le cadre, centrée, proportions gardées : échelle et décalage en pixels. */
private class Fit(val scale: Float, val dx: Float, val dy: Float) {
    fun screen(p: Offset) = Offset(p.x * scale + dx, p.y * scale + dy)
    fun map(p: Offset) = Offset((p.x - dx) / scale, (p.y - dy) / scale)
}

private fun fit(win: Rect, size: IntSize): Fit {
    if (win.width <= 0 || win.height <= 0 || size.width == 0) return Fit(1f, 0f, 0f)
    val s = min(size.width / win.width, size.height / win.height)
    return Fit(s, (size.width - win.width * s) / 2 - win.left * s, (size.height - win.height * s) / 2 - win.top * s)
}

// ---------- Repères (dessinés en dp, taille constante quel que soit le zoom) ----------

/**
 * Couleur = parti ; statut = un détail lisible sans la couleur : pleine = conservée, point clair = gagnée,
 * contour pointillé = maire rallié, vidée et barrée = perdue, petit anneau = échec.
 */
private fun DrawScope.marker(st: String, c: Color, at: Offset, ink: Color, surface: Color, rDp: Float = 5.5f) {
    val u = density
    val r = rDp * u
    val hair = ink.copy(alpha = 0.7f)
    fun dot() { drawCircle(c, r, at); drawCircle(hair, r, at, style = Stroke(u)) }
    when (st) {
        "conservee" -> dot()
        "gagnee" -> {
            dot()
            val p = at + Offset(r * 0.74f, -r * 0.74f)
            drawCircle(Color.White, r * 0.44f, p); drawCircle(Color(0xFF1F2620), r * 0.44f, p, style = Stroke(0.9f * u))
        }
        "ralliee" -> {
            dot()
            drawCircle(ink.copy(alpha = 0.8f), (rDp + 2.3f) * u, at, style = Stroke(1.2f * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.2f * u, 1.9f * u))))
        }
        "perdue" -> {
            drawCircle(surface, (rDp - 1.15f) * u, at); drawCircle(c, (rDp - 1.15f) * u, at, style = Stroke(2.3f * u))
            drawCircle(hair, r, at, style = Stroke(u)); drawCircle(hair, (rDp - 2.3f) * u, at, style = Stroke(u))
            drawLine(ink, at + Offset(-r * 0.5f, r * 0.5f), at + Offset(r * 0.5f, -r * 0.5f), 1.1f * u, StrokeCap.Round)
        }
        "echec" -> {
            val e = rDp * 0.62f * u
            drawCircle(surface, e, at); drawCircle(c, e, at, style = Stroke(1.8f * u)); drawCircle(hair, e + 0.9f * u, at, style = Stroke(0.8f * u))
        }
        else -> drawCircle(ink, rDp * 0.7f * u, at, style = Stroke(1.2f * u))
    }
}

/** Bulle-compteur : anneau segmenté aux couleurs des partis présents (proportions), nombre au centre. */
private fun DrawScope.bubble(segs: List<Pair<Color, Int>>, at: Offset, rDp: Float, ink: Color, surface: Color, number: (DrawScope.() -> Unit)? = null) {
    val u = density
    val w = 3.4f
    val n = segs.sumOf { it.second }.coerceAtLeast(1)
    val outer = (rDp + w / 2 + 0.6f) * u
    drawCircle(surface, outer, at); drawCircle(ink.copy(alpha = 0.7f), outer, at, style = Stroke(u))
    val gap = if (segs.size > 1) 1.6f else 0f
    val r = rDp * u
    var acc = 0f
    segs.forEach { (col, m) ->
        val len = m * 100f / n
        drawArc(col, -90f + acc * 3.6f, maxOf(0.5f, len - gap) * 3.6f, false, at - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(w * u))
        acc += len
    }
    drawCircle(ink.copy(alpha = 0.3f), (rDp - w / 2 - 0.5f) * u, at, style = Stroke(0.8f * u))
    number?.invoke(this)
}

@Composable
private fun MarkerIcon(st: String, c: Color) {
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(18.dp)) { marker(st, c, center, ink, surface) }
}

// ---------- Rubrique ----------

@Composable
fun VillesScreen(data: PolData, nav: PolNavigator) {
    val model by produceState<VillesModel?>(null, data) { value = withContext(Dispatchers.Default) { villesModel(data) } }
    val facts = remember(data) { cityFactsOf(data) }
    val sections = remember(data) { factSections(data) }
    val muni = remember(data) { muniOf(data) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part {
            Text(
                frTypo("Un indice imparfait (un maire ne gère ni l'armée ni la protection sociale) mais concret. Faits vérifiables séparés des avis."),
                Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp,
            )
        }
        part {
            Section("Municipales", "La carte des villes suivies", "Filtrez par parti, touchez une région pour zoomer, puis une ville pour sa fiche. La couleur dit le parti, le repère dit le résultat ; un nombre regroupe les villes trop proches.") {
                val m = model
                when {
                    m == null -> Box(Modifier.fillMaxWidth().height(240.dp))
                    m.communes.isEmpty() -> EmptyState("Aucune ville suivie", "Aucun résultat municipal sourcé n'est publié pour l'instant. La carte se remplira dès que les premières villes seront documentées.")
                    else -> VillesMap(data, m, facts, nav)
                }
            }
        }
        part {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FACT_KINDS.forEach { k -> factKind(k).let { (l, tone) -> StateTag(l, tone) } }
            }
        }
        part {
            Section("Municipales", "Ce qui a changé de mains", null) {
                if (muni.isEmpty()) EmptyState("Pas encore de bilan des municipales", "Aucune synthèse par camp n'est publiée. Elle apparaîtra ici dès qu'elle sera sourcée.")
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { muni.forEach { MuniCard(data, it) } }
            }
        }
        if (sections.isEmpty()) part {
            Section("Bilan local", "Ce qu'ils font quand ils dirigent", null) {
                EmptyState("Aucun fait de gestion locale", "Aucun fait vérifiable ni décision de justice sur la gestion d'une ville n'est publié pour l'instant.")
            }
        }
        sections.forEach { s ->
            part {
                Section("Bilan local", s.id?.let { data.byId(it)?.nom } ?: "Autres villes", null) {
                    if (s.cities.isEmpty()) EmptyState("Pas encore de bilan", "Aucun fait de gestion n'est encore documenté pour ces villes. Cette section se remplira quand les premiers comptes ou décisions seront publiés.")
                    else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        s.cities.forEach { (ville, l) -> CityFactsCard(data, ville, l, nav) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MuniCard(data: PolData, d: Dossier) {
    val id = data.uniqueId(d.t)
    val bar = id?.let { identityColor(data.byId(it)?.couleur) }
    FigCard(Modifier.clip(RoundedCornerShape(16.dp)).drawWithContent { drawContent(); if (bar != null) drawRect(bar, size = Size(size.width, 4.dp.toPx())) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (id != null) IdentityChip(data, id, large = true, text = d.t, modifier = Modifier.weight(1f, fill = false))
            else Text(d.t, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            StageTag(d.stg)
        }
        if (d.p.isNotBlank()) RichText(d.p, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 21.sp))
    }
}

/** Fait documenté : nature, maire (puce d'identité si rattaché), texte, source. */
@Composable
private fun FactBlock(data: PolData, f: CityFact, nav: PolNavigator, withMaire: Boolean) {
    val (label, tone) = factKind(f.kind)
    StateTag(label, tone)
    if (withMaire && f.maire.isNotBlank()) {
        val id = data.uniqueId(f.maire)
        if (id != null) IdentityChip(data, id, text = f.maire, onClick = { nav.sheet(PolSheetState.Identity(id)) }, modifier = Modifier.padding(top = 6.dp))
        else Text(f.maire, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(frTypo(f.text), Modifier.padding(top = 6.dp), fontSize = 14.sp, lineHeight = 20.sp)
    SourceList(listOf(SourceRef(f.srcLabel, f.srcUrl)).filter { it.label.isNotBlank() }, data, { nav.sheet(PolSheetState.Evaluator(it)) })
}

@Composable
private fun RowDivider(top: Dp = 12.dp) = Box(Modifier.padding(top = top).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

@Composable
private fun CityFactsCard(data: PolData, ville: String, l: List<CityFact>, nav: PolNavigator) {
    FigCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ville, Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            TextButton({ nav.sheet(PolSheetState.City(ville)) }, Modifier.semantics { contentDescription = "Fiche de $ville" }) { Text("Fiche") }
        }
        l.firstOrNull()?.maire?.takeIf { it.isNotBlank() }?.let { m ->
            val id = data.uniqueId(m)
            if (id != null) IdentityChip(data, id, text = m, onClick = { nav.sheet(PolSheetState.Identity(id)) })
            else Text(m, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        l.forEach { f ->
            RowDivider()
            Column(Modifier.padding(top = 12.dp)) { FactBlock(data, f, nav, withMaire = false) }
        }
    }
}

// ---------- Carte ----------

@Composable
private fun VillesMap(data: PolData, m: VillesModel, facts: List<CityFact>, nav: PolNavigator) {
    var qui by rememberSaveable { mutableStateOf("") }
    var reg by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = reg != null) { reg = null }
    val shown = remember(m, qui) { m.communes.filter { qui.isEmpty() || it.qui == qui } }
    val options = remember(m) {
        listOf(PillOption("", "Tous les partis", m.communes.size)) +
            m.communes.groupingBy { it.qui }.eachCount().toList().sortedByDescending { it.second }
                .map { (q, n) -> PillOption(q, quiLabel(data, q), n, data.quiColor(q)) }
    }
    val openCity = { name: String -> nav.sheet(PolSheetState.City(name)) }
    Column {
        Pills(options, qui, { qui = it }, Modifier.padding(top = 4.dp))
        if (m.hasMap) FigCard(Modifier.padding(top = 10.dp)) {
            MapCanvas(data, m, shown, reg, onRegion = { reg = it }, onCity = openCity, onCluster = { nav.sheet(PolSheetState.Cluster(it)) })
            MapLegend()
        }
        MapList(data, m, shown, reg, qui.isNotEmpty(), facts, onRegion = { reg = it }, onCity = openCity)
        val src = remember(m) { sourcesOf(m.source) }
        if (src.isNotEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fond de carte", Modifier.padding(top = 12.dp, end = 8.dp), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceList(src, data, { nav.sheet(PolSheetState.Evaluator(it)) })
        }
    }
}

@Composable
private fun MapCanvas(data: PolData, m: VillesModel, shown: List<Commune>, reg: String?, onRegion: (String?) -> Unit, onCity: (String) -> Unit, onCluster: (List<String>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val paths = remember(m) {
        m.regions.associate { r ->
            r.code to Path().apply { r.rings.forEach { ring -> moveTo(ring[0].x, ring[0].y); ring.drop(1).forEach { lineTo(it.x, it.y) }; close() } }
        }
    }
    val target = reg?.let { c -> m.regions.firstOrNull { it.code == c }?.bounds?.let(::regionWindow) } ?: m.viewBox
    val reduced = reducedMotion()
    val win = animateRectAsState(target, if (reduced) snap() else tween(380, easing = CubicOut), label = "fenêtre")
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shownSet = remember(shown) { shown.toSet() }
    val placed = remember(m, shownSet) { m.placed.filter { it.c in shownSet } }
    // Regroupement calculé pour la fenêtre d'arrivée : les repères glissent pendant le zoom, puis restent stables.
    val groups = remember(placed, reg, size, density, m) {
        val u = density / fit(target, size).scale
        when {
            size == IntSize.Zero -> emptyList()
            reg == null -> mergeOverlapping(regionBubbles(m.regions.map { it.code }, placed), u)
            else -> clusterMarkers(placed.filter { it.c.region == reg }, u)
        }
    }
    val counts = remember(shown) { shown.groupingBy { it.region }.eachCount() }
    val measurer = rememberTextMeasurer()
    val numStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, color = scheme.onSurface)
    val regName = reg?.let { c -> m.regions.firstOrNull { it.code == c }?.nom }
    val summary = if (reg == null) "Carte de France : ${plural(placed.size, "ville", "villes")} en métropole, dans ${plural(groups.size, "région", "régions")}. Chaque ville est reprise dans la liste sous la carte."
    else "Carte zoomée sur ${regName.orEmpty()} : ${plural(groups.sumOf { it.cities.size }, "ville", "villes")}. La liste sous la carte reprend chaque ville."

    val base = lerp(scheme.surface, scheme.background, 0.5f)
    val has = lerp(base, scheme.primary, 0.10f)
    val on = lerp(scheme.surface, scheme.tertiary, 0.26f)
    val ar = (m.viewBox.width / m.viewBox.height).coerceIn(0.5f, 2f)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(ar).clipToBounds().onSizeChanged { size = it }
            .pointerInput(groups, reg, m) {
                detectTapGestures { pos ->
                    if (size == IntSize.Zero) return@detectTapGestures
                    val f = fit(win.value, size)
                    // Rayon de toucher : repère seul 15 dp, bulle 20 dp ; le plus proche l'emporte.
                    val hit = groups.filter { (f.screen(it.at) - pos).getDistance() <= (if (it.cities.size == 1 && it.regions.isEmpty()) 15 else 20).dp.toPx() }
                        .minByOrNull { (f.screen(it.at) - pos).getDistance() }
                    when {
                        hit != null && hit.regions.size == 1 -> onRegion(hit.regions[0])
                        hit != null && hit.cities.size > 1 -> onCluster(hit.cities.map { it.key })
                        hit != null -> onCity(hit.cities[0].key)
                        else -> m.regions.firstOrNull { contains(it.rings, f.map(pos)) }?.let { onRegion(if (it.code == reg) null else it.code) }
                    }
                }
            }
            .clearAndSetSemantics { contentDescription = summary },
    ) {
        val f = fit(win.value, IntSize(this.size.width.toInt(), this.size.height.toInt()))
        withTransform({ translate(f.dx, f.dy); scale(f.scale, f.scale, Offset.Zero) }) {
            // Région ouverte en dernier : son contour passe au-dessus des voisines.
            m.regions.sortedBy { it.code == reg }.forEach { r ->
                val p = paths[r.code] ?: return@forEach
                val isOn = r.code == reg
                val fill = when { isOn -> on; (counts[r.code] ?: 0) > 0 -> has; else -> base }
                drawPath(p, if (reg != null && !isOn) fill.copy(alpha = 0.45f) else fill)
                drawPath(
                    p, if (isOn) scheme.onSurface.copy(alpha = 0.9f) else scheme.onSurfaceVariant.copy(alpha = 0.55f),
                    style = Stroke((if (isOn) 2f else 1.2f) * density / f.scale),
                )
            }
        }
        groups.forEach { g ->
            val at = f.screen(g.at)
            if (g.cities.size == 1 && g.regions.isEmpty()) {
                val c = g.cities[0]
                marker(c.statut, data.quiColor(c.qui), at, scheme.onSurface, scheme.surface)
            } else {
                val r = bubbleRadiusDp(g)
                val label = measurer.measure(g.cities.size.toString(), numStyle)
                bubble(partyCounts(g.cities).map { (q, n) -> data.quiColor(q) to n }, at, r, scheme.onSurface, scheme.surface) {
                    drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapLegend() {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) { contentDescription = "Légende : la couleur dit le parti, le repère dit le résultat" },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        STATUSES.forEach { (k, l) -> LegendChip(l) { MarkerIcon(k, scheme.onSurfaceVariant) } }
        LegendChip("Plusieurs villes") {
            val ink = scheme.onSurface; val surface = scheme.surface; val muted = scheme.onSurfaceVariant
            Canvas(Modifier.size(18.dp)) { bubble(listOf(muted to 2, NO_PARTY to 1), center, 5.4f, ink, surface) }
        }
    }
}

@Composable
private fun LegendChip(label: String, icon: @Composable () -> Unit) {
    Row(
        Modifier.clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(start = 4.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(label.uppercase(), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
    }
}

/** Liste sous la carte : la voie accessible complète (chaque ville est un bouton), outre-mer compris. */
@Composable
private fun MapList(data: PolData, m: VillesModel, shown: List<Commune>, reg: String?, filtered: Boolean, facts: List<CityFact>, onRegion: (String?) -> Unit, onCity: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val mapped = remember(m) { m.placed.map { it.c }.toSet() }
    val off = remember(m) { m.unmapped.toSet() }
    val metro = shown.filter { it in mapped }
    val others = shown.filter { it in off }
    val dom = outreMer(shown)
    val row: @Composable (Int, Commune) -> Unit = { i, c -> if (i > 0) RowDivider(0.dp); CityRow(data, c, factsFor(facts, c.ville).size) { onCity(c.key) } }
    if (m.hasMap && reg != null) {
        val nom = m.regions.firstOrNull { it.code == reg }?.nom.orEmpty()
        val l = metro.filter { it.region == reg }.sortedWith(compareBy(FR) { it.ville })
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(nom, Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            TextButton({ onRegion(null) }, Modifier.heightIn(min = 48.dp)) { Text("← Toute la France") }
        }
        FigCard(Modifier.padding(top = 8.dp)) {
            if (l.isEmpty()) Note("Aucune ville suivie dans cette région${if (filtered) " pour ce parti" else ""}.")
            l.forEachIndexed { i, c -> row(i, c) }
        }
    } else if (m.hasMap) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("France métropolitaine", Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Kicker(plural(metro.size, "ville", "villes"))
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            STATUSES.forEach { (k, l) ->
                val n = shown.count { it.statut == k }
                if (n > 0) LegendChip("$n ${l.lowercase()}") { MarkerIcon(k, scheme.onSurfaceVariant) }
            }
        }
        val regs = m.regions.map { r -> r to metro.count { it.region == r.code } }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (regs.isEmpty() && shown.isEmpty()) Note(if (filtered) "Aucune ville pour ce parti." else "Aucune ville suivie.")
        if (regs.isNotEmpty()) FigCard(Modifier.padding(top = 10.dp)) {
            regs.forEachIndexed { i, (r, n) ->
                if (i > 0) RowDivider(0.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Zoomer sur la région") { onRegion(r.code) }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.nom, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                    Text("${plural(n, "ville", "villes")} ›".uppercase(), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
    if (others.isNotEmpty()) {
        Kicker("Autres villes", Modifier.padding(top = 18.dp))
        Note(if (m.hasMap) "Sans place sur la carte : région inconnue du fond de carte ou coordonnées manquantes." else "Fond de carte indisponible : les villes sont listées ici.")
        FigCard(Modifier.padding(top = 6.dp)) { others.sortedWith(compareBy(FR) { it.ville }).forEachIndexed { i, c -> row(i, c) } }
    }
    Kicker("Outre-mer", Modifier.padding(top = 18.dp))
    if (dom.isEmpty()) Note("Aucune ville d'outre-mer suivie${if (filtered) " pour ce parti" else ""}.")
    else FigCard(Modifier.padding(top = 6.dp)) { dom.forEachIndexed { i, c -> row(i, c) } }
}

@Composable
private fun Note(text: String) = Text(text, Modifier.padding(vertical = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun CityRow(data: PolData, c: Commune, nFacts: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sub = "${quiLabel(data, c.qui)} · ${statusLabel(c.statut)}"
    val factsLabel = if (nFacts > 0) plural(nFacts, "fait", "faits") else ""
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Ouvrir la fiche", onClick = onClick)
            .clearAndSetSemantics { contentDescription = listOf(c.ville, sub, factsLabel).filter { it.isNotBlank() }.joinToString(", ") }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MarkerIcon(c.statut, data.quiColor(c.qui))
        Column(Modifier.weight(1f)) {
            Text(c.ville, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
            Text(sub, fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        if (factsLabel.isNotEmpty()) Text(factsLabel.uppercase(), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
    }
}

// ---------- Feuilles ----------

private fun PolData.communes(): List<Commune> = decode<Commune>("villes", "COMMUNES").filter { it.ville.isNotBlank() }

private fun PolData.regionName(code: String?): String =
    decode<RegionShape>("carte", "regions").firstOrNull { it.code == code }?.nom ?: if (code != null && isDom(code)) "Outre-mer" else ""

/** Source d'une commune : `{ev, url}` sans libellé devient « Source », signée par l'évaluateur. */
private fun communeSources(c: Commune): List<SourceRef> {
    val o = c.src as? JsonObject
    return sourcesOf(c.src).map { if (o != null && o["label"] == null && o["nom"] == null) it.copy(label = "Source") else it }
}

@Composable
fun CitySheet(data: PolData, key: String, nav: PolNavigator, onDismiss: () -> Unit) {
    val c = remember(data, key) { data.communes().let { l -> l.firstOrNull { it.key == key } ?: l.firstOrNull { it.ville == key } } }
    val name = c?.ville ?: key
    val facts = remember(data, name) { factsFor(cityFactsOf(data), name) }
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    PolSheet(full = false, onDismiss = onDismiss) {
        if (c == null && facts.isEmpty()) {
            EmptyState("Ville introuvable", "Cette ville n'est plus dans les données chargées. Fermez la feuille et rouvrez-la depuis la liste.", Modifier.padding(16.dp))
            return@PolSheet
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val reg = data.regionName(c?.region)
            Kicker(listOf(reg, "Municipales").filter { it.isNotBlank() }.joinToString(" · "))
            Text(name, Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
            if (c != null) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (data.byId(c.qui) != null) IdentityChip(data, c.qui, onClick = { nav.sheet(PolSheetState.Identity(c.qui)) })
                    else StateTag(quiLabel(data, c.qui), null)
                    LegendChip(statusLabel(c.statut)) { MarkerIcon(c.statut, data.quiColor(c.qui)) }
                }
                if (c.detail.isNotBlank()) Text(frTypo(c.detail), Modifier.padding(top = 12.dp), fontSize = 15.sp, lineHeight = 21.sp)
                SourceList(communeSources(c), data, onEv)
            }
            if (facts.isEmpty()) Note("Aucun fait de gestion locale documenté pour cette ville.")
            else {
                SectionHeader("Bilan local", "Faits documentés", modifier = Modifier.padding(top = 20.dp))
                FigCard(Modifier.padding(top = 10.dp)) {
                    facts.forEachIndexed { i, f ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(top = if (i > 0) 12.dp else 0.dp)) { FactBlock(data, f, nav, withMaire = true) }
                    }
                }
            }
        }
    }
}

@Composable
fun ClusterSheet(data: PolData, keys: List<String>, nav: PolNavigator, onDismiss: () -> Unit) {
    val l = remember(data, keys) { data.communes().filter { it.key in keys }.sortedWith(compareBy(FR) { it.ville }) }
    val facts = remember(data) { cityFactsOf(data) }
    PolSheet(full = false, onDismiss = onDismiss) {
        if (l.isEmpty()) {
            EmptyState("Villes introuvables", "Ces villes ne sont plus dans les données chargées. Fermez la feuille et rouvrez la carte.", Modifier.padding(16.dp))
            return@PolSheet
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val regs = l.map { data.regionName(it.region) }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            Kicker(listOf(regs, "même point de la carte").filter { it.isNotBlank() }.joinToString(" · "))
            Text("${plural(l.size, "ville", "villes")} à cet endroit", Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            FigCard(Modifier.padding(top = 12.dp)) {
                l.forEachIndexed { i, c -> if (i > 0) RowDivider(0.dp); CityRow(data, c, factsFor(facts, c.ville).size) { nav.sheet(PolSheetState.City(c.key)) } }
            }
        }
    }
}
