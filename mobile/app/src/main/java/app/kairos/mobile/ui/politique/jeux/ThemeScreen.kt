package app.kairos.mobile.ui.politique.jeux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolRoute
import app.kairos.mobile.ui.politique.RUBRICS
import app.kairos.mobile.ui.politique.jeux.kit.PixelIcon
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.theme.KairosColors

/**
 * Écran d'un thème : le jeu en tête occupe l'écran visible (la suite dépasse de moins de 10 %), carrousel des jeux
 * du thème, puis ses rubriques. [bottom] : marge du dock, le contenu défile dessous.
 */
@Composable
fun ThemeScreen(session: JeuxSession, id: String, data: PolData, nav: PolNavigator, bottom: Dp) {
    val t = session.theme(id) ?: return
    var game by rememberSaveable(id) { mutableIntStateOf(0) }
    val slug = t.jeux.getOrNull(game) ?: t.jeux.firstOrNull()
    val counts = remember(data, t) { t.rubrics.associateWith { r -> RUBRICS.firstOrNull { it.id == r }?.count?.invoke(data).orEmpty() } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val visible = maxHeight - bottom
        LazyColumn(
            Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (slug != null) item(key = "hero") {
                CarteJeu(session, slug, data, nav, Modifier.fillMaxWidth().height((visible * 0.92f - 20.dp).coerceAtLeast(420.dp)))
            }
            if (t.jeux.size > 1) item(key = "jeux") {
                Carrousel(session, t, game) { game = it; scope.launch { list.animateScrollToItem(0) } }
            }
            item(key = "rubriques-titre") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Rubriques", Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Kicker("${t.rubrics.size}")
                }
            }
            t.rubrics.mapNotNull { r -> RUBRICS.firstOrNull { it.id == r } }.forEach { r ->
                item(key = "rb-${r.id}") { RubriqueCarte(r.title, r.hook, counts[r.id].orEmpty()) { nav.go(PolRoute.Rubric(r.id)) } }
            }
        }
    }
}

@Composable
private fun Carrousel(session: JeuxSession, t: Theme, current: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    val kc = KairosColors.current
    val scheme = MaterialTheme.colorScheme
    val tints = listOf(scheme.primaryContainer to scheme.onPrimaryContainer, scheme.tertiaryContainer to scheme.onSurface, kc.ciel to kc.nuit)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = (maxWidth + 32.dp) * 0.58f
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 0.dp), state = state, flingBehavior = rememberSnapFlingBehavior(state),
            horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp),
        ) {
            itemsIndexed(t.jeux, key = { _, s -> s }) { i, slug ->
                val j = session.jeux[slug]
                val (bg, ink) = tints[i % 3]
                val a = session.avance(slug)
                val on = i == current
                val nom = j?.nom?.takeIf { it.isNotBlank() } ?: slug
                val label = nom + (if (a.m > 0) ", ${a.n} sur ${a.m} cartes" + (if (a.etoile) ", toutes trouvées du premier coup" else "") else ", bientôt")
                Box(
                    Modifier.width(w).heightIn(min = 128.dp).offset(y = if (on) (-3).dp else 0.dp)
                        .let { if (on) it.shadow(6.dp, RoundedCornerShape(20.dp)) else it }
                        .clip(RoundedCornerShape(20.dp)).background(bg)
                        .border(2.dp, if (on) scheme.primary else Color.Transparent, RoundedCornerShape(20.dp))
                        .clickable(role = Role.Tab) { onSelect(i) }
                        .semantics(mergeDescendants = true) { contentDescription = label; selected = on },
                ) {
                    PixelIcon(slug, ink.copy(alpha = 0.4f), Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).size(64.dp))
                    Column(Modifier.padding(12.dp).heightIn(min = 100.dp).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(frTypo(nom), Modifier.fillMaxWidth(0.8f), color = ink, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
                        j?.sousTitre?.takeIf { it.isNotBlank() }?.let {
                            Text(frTypo(it), color = ink.copy(alpha = 0.85f), fontSize = 12.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.weight(1f, fill = false).heightIn(min = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (a.m > 0) "${a.n}/${a.m}" else "Bientôt", color = ink.copy(alpha = 0.75f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            if (a.etoile) Etoile()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RubriqueCarte(title: String, hook: String, count: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).shadow(1.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(scheme.surface)
            .clickable(role = Role.Button, onClickLabel = "Ouvrir la rubrique", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $hook. $count" }
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(frTypo(title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(frTypo(hook), color = scheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 17.sp)
            Text(count.uppercase(), Modifier.padding(top = 4.dp), color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.04.em)
        }
        Icon(KairosIcons.ChevronForward, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}

/** Pictogramme au trait d'un thème (24×24), pour le dock. */
@Composable
fun ThemeIcon(id: String, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    val path = remember(id) { THEME_PATHS[id]?.let { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.clearAndSetSemantics { }) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (id) {
                "verifier" -> drawCircle(color, 6.5f, Offset(11f, 11f), style = stroke)
                "chiffrer" -> drawRoundRect(color, Offset(5f, 3f), Size(14f, 18f), CornerRadius(2.5f), style = stroke)
                "bilan" -> drawRoundRect(color, Offset(5f, 4.5f), Size(14f, 16.5f), CornerRadius(2.5f), style = stroke)
                "pouvoirs" -> listOf(12f to 5f, 5f to 19f, 12f to 19f, 19f to 19f).forEach { (x, y) -> drawCircle(color, 2f, Offset(x, y), style = stroke) }
            }
            path?.let { drawPath(it, color, style = stroke) }
        }
    }
}

private val THEME_PATHS = mapOf(
    "verifier" to "M20 20l-4.4-4.4M8.3 11l2 2 3.6-3.6",
    "chiffrer" to "M8.5 7.5h7M8.5 12h1.5M14 12h1.5M8.5 16.5h1.5M14 16.5h1.5",
    "bilan" to "M9 4.5V3h6v1.5M9 13l2 2 4-4",
    "pouvoirs" to "M12 7v10M11 6.8 6 17.2M13 6.8l5 10.4",
)
