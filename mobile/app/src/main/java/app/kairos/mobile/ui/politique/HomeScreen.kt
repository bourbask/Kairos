package app.kairos.mobile.ui.politique

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PillOption
import app.kairos.mobile.ui.politique.kit.Pills
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.PolData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val KIND_LABEL = linkedMapOf(IdKind.Personne to "Candidats", IdKind.Parti to "Partis", IdKind.Camp to "Camps")
private val Mono = FontFamily.Monospace

/** « vérifié le 9 novembre 2026 » ; date illisible : telle quelle ; absente : null. */
fun verifiedLabel(raw: String?): String? {
    val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val day = runCatching { LocalDate.parse(v.take(10)).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)) }.getOrDefault(v)
    return "Vérifié le $day"
}

/** Phrase courte pour la carte du quiz : la première qui tient sur quelques lignes. */
private fun quizQuote(claims: List<Claim>): String? = claims.firstOrNull { it.q.length in 1..60 }?.q

/** Accueil : promesse, deux héros (Duel, Vrai ou faux ?), les visages, les rubriques, puis les lectures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(data: PolData, nav: PolNavigator, bottom: Dp) {
    var kind by rememberSaveable { mutableStateOf(IdKind.Personne) }
    // Comptes par identité : calcul complet des rattachements, hors du fil d'affichage.
    val linked by produceState(emptyMap<String, Int>(), data) {
        value = withContext(Dispatchers.Default) { data.identities.associate { it.id to identityLinks(data, it.id).size } }
    }
    val claims = remember(data) { data.decode<Claim>("affirmations", "CLAIMS") }
    val bubble = remember(data) { bubbleLine(data) }
    val counts = remember(data) { RUBRICS.associate { it.id to it.count(data) } }
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        full {
            Column {
                Text(
                    "Programmes, votes et phrases entendues partout, passés au crible des données publiques.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp,
                )
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Même méthode pour tous", "Chaque chiffre sourcé", "Aucun classement").forEach {
                        Text(
                            it, Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 5.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        full { DuelHero(data) { nav.go(PolRoute.Duel()) } }
        full { QuizHero(claims, bubble) { nav.go(PolRoute.Quiz) } }

        full { HeaderRow("Les visages", plural(data.identities.size, "fiche", "fiches")) }
        full {
            Pills(
                KIND_LABEL.map { (k, l) -> PillOption(k.name, l, data.identities.count { data.kindOf(it.id) == k }) },
                kind.name, { kind = IdKind.valueOf(it) },
            )
        }
        full {
            val list = data.identities.filter { data.kindOf(it.id) == kind }
            if (list.isEmpty()) {
                EmptyState("Aucune fiche", "Aucune identité de ce type dans les données.")
            } else {
                val state = rememberLazyListState()
                LazyRow(state = state, flingBehavior = rememberSnapFlingBehavior(state), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { x -> IdCard(data, x.id, linked[x.id]) { nav.sheet(PolSheetState.Identity(x.id)) } }
                }
            }
        }

        val tiles = RUBRICS.filter { it.kind != TileKind.Link }
        full { HeaderRow("Rubriques", plural(tiles.size, "entrée", "entrées")) }
        tiles.forEach { t ->
            item(span = { GridItemSpan(if (t.kind == TileKind.Wide) maxLineSpan else 1) }) { Tile(t, counts[t.id].orEmpty()) { nav.go(PolRoute.Rubric(t.id)) } }
        }
        val links = RUBRICS.filter { it.kind == TileKind.Link }
        full { HeaderRow("Lire entre les lignes", plural(links.size, "entrée", "entrées")) }
        items(links, span = { GridItemSpan(maxLineSpan) }) { t -> LinkRow(t, counts[t.id].orEmpty()) { nav.go(PolRoute.Rubric(t.id)) } }
        full {
            Text(
                "Chaque verdict juge l'état des preuves, pas les personnes. Les chiffres provisoires peuvent être révisés.",
                Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            )
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun HeaderRow(title: String, kicker: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Kicker(kicker)
    }
}

internal val Attn = Color(0xFFE8A33A)
internal val AttnInk = Color(0xFF1F2620)

/** Fond « nuit » des bandeaux de duel. */
@Composable
internal fun duelNight(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF1F2A36) else Color(0xFF23384A)

@Composable
private fun DuelHero(data: PolData, onClick: () -> Unit) {
    val bg = duelNight()
    val ink = Color(0xFFF7F5EF)
    // Deux visages publiés : les premiers candidats qui en ont un.
    val duo = data.identities.filter { data.kindOf(it.id) == IdKind.Personne && it.avatar != null }.take(2)
    Surface(onClick, Modifier.fillMaxWidth().heightIn(min = 184.dp).semantics { contentDescription = "Duel : deux programmes face à face" }, shape = RoundedCornerShape(24.dp), color = bg, contentColor = ink) {
        Box(Modifier.padding(18.dp)) {
            Column {
                Kicker("Comparer", color = ink.copy(alpha = 0.75f))
                Text("Duel", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em)
                Text("Deux programmes face à face, chiffre par chiffre.", Modifier.width(200.dp).padding(top = 4.dp), fontSize = 14.sp, color = ink.copy(alpha = 0.85f))
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, end = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Attn))
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF9FC4E8)))
                }
            }
            if (duo.size == 2) {
                Row(Modifier.align(Alignment.TopEnd).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IdentityBadge(duo[0], 58.dp)
                    Text("VS", color = Attn, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    IdentityBadge(duo[1], 58.dp, Modifier.graphicsLayer { scaleX = -1f })
                }
            }
            Box(Modifier.align(Alignment.BottomEnd).size(44.dp).clip(CircleShape).background(Attn), contentAlignment = Alignment.Center) {
                Icon(KairosIcons.ChevronForward, contentDescription = null, tint = AttnInk)
            }
        }
    }
}

@Composable
private fun QuizHero(claims: List<Claim>, line: String, onClick: () -> Unit) {
    val n = claims.count { it.v != "non-documente" }
    val bg = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(onClick, Modifier.fillMaxWidth().heightIn(min = 168.dp).semantics { contentDescription = "Vrai ou faux ? Jouer avec les phrases vérifiées" }, shape = RoundedCornerShape(24.dp), color = bg, contentColor = ink) {
        Box(Modifier.padding(18.dp)) {
            Column(Modifier.padding(end = 124.dp)) {
                Kicker("Jouer", color = ink.copy(alpha = 0.75f))
                Text(frTypo("Vrai ou faux ?"), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, lineHeight = 34.sp)
                Text(
                    if (n > 0) "${plural(n, "phrase entendue", "phrases entendues")} à table. Devinez le verdict." else "Devinez le verdict des phrases entendues à table.",
                    Modifier.padding(top = 4.dp), fontSize = 14.sp,
                )
                Text(line, Modifier.padding(top = 12.dp), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Box(Modifier.align(Alignment.TopEnd).size(112.dp, 128.dp).clearAndSetSemantics { }) {
                val card = MaterialTheme.colorScheme.surface
                listOf(-9f to 0.6f, 5f to 0.85f).forEach { (r, a) ->
                    Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = r; alpha = a }.clip(RoundedCornerShape(14.dp)).background(card))
                }
                Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = -2f }.clip(RoundedCornerShape(14.dp)).background(card).padding(10.dp), contentAlignment = Alignment.Center) {
                    quizQuote(claims)?.let {
                        Text(frTypo("« $it »"), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

/** Carte de taille fixe : avatar, nom sur deux lignes réservées, parti, nombre d'éléments rattachés. */
@Composable
private fun IdCard(data: PolData, id: String, count: Int?, onClick: () -> Unit) {
    val x = data.byId(id) ?: return
    val kind = data.kindOf(id)
    val party = when (kind) {
        IdKind.Personne -> x.parti.orEmpty()
        IdKind.Parti -> x.parti?.takeIf { it != x.nom } ?: "Parti"
        IdKind.Camp -> "Camp politique"
    }
    val n = when (count) {
        null -> ""
        0 -> "peu de données"
        else -> plural(count, "élément", "éléments")
    }
    Surface(
        onClick,
        Modifier.size(128.dp, 176.dp).semantics { contentDescription = listOf(x.nom, party, n).filter(String::isNotBlank).joinToString(", ") + ". Ouvrir la fiche" },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            IdentityBadge(x, 64.dp)
            Text(x.nom, Modifier.padding(top = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(party, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Mono, fontSize = 10.5.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Tile(t: RubricTile, count: String, onClick: () -> Unit) {
    val wide = t.kind == TileKind.Wide
    val bubble = t.id == "bulle"
    val bg = if (bubble) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val ink = if (bubble) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val muted = if (bubble) ink.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick,
        Modifier.fillMaxWidth().heightIn(min = if (wide) 0.dp else 132.dp).semantics { contentDescription = "${t.title}. ${t.hook}. $count" },
        shape = RoundedCornerShape(20.dp), color = bg, contentColor = ink, shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(frTypo(t.hook), Modifier.padding(top = 3.dp), color = muted, fontSize = 13.sp, lineHeight = 17.sp)
            if (!wide) Spacer(Modifier.weight(1f, fill = false))
            Text(count.uppercase(), Modifier.padding(top = 8.dp), color = muted, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.04.em)
        }
    }
}

@Composable
private fun LinkRow(t: RubricTile, count: String, onClick: () -> Unit) {
    Surface(
        onClick,
        Modifier.fillMaxWidth().semantics { contentDescription = "${t.title}. ${t.hook}. $count" },
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(frTypo(t.title), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(frTypo(t.hook), Modifier.padding(top = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(count.uppercase(), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            }
            Icon(KairosIcons.ChevronForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Données injoignables : la raison, « Réessayer », et le site qui reste ouvrable. */
@Composable
fun HomeDown(load: PolLoad.Down, onRetry: () -> Unit, onSite: () -> Unit) {
    val actions = homeActions(load)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        EmptyState(
            "Les données n'ont pas pu être chargées",
            "${load.reason} Vérifiez la connexion, puis réessayez. Rien n'est affiché tant que les données ne sont pas vérifiables.",
            action = if (HomeAction.Retry in actions) "Réessayer" to onRetry else null,
        )
        if (HomeAction.OpenSite in actions) {
            Text("Le site reste consultable en entier, et ses accès partageables.", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            OutlinedButton(onSite, Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) { Text("Ouvrir le site") }
        }
    }
}

@Composable
fun HomeLoading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = "Chargement de l'espace Politique" })
    }
}
