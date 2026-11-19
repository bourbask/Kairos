package app.kairos.mobile.ui.politique.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.duelNight
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.LocalPolBottom
import app.kairos.mobile.ui.politique.kit.SourceButton
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.quoted
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.reducedMotion
import kotlin.random.Random

/** « Ça vient d'où ? » : une phrase au recto, son origine au verso ; glisser pour la suivante. */
@Composable
fun OriginesScreen(data: PolData, nav: PolNavigator) {
    // Ordre aléatoire, le même toute la session (rotation comprise).
    val seed = rememberSaveable { Random.nextLong() }
    val deck = remember(data, seed) { originDeck(data, seed) }
    if (deck.isEmpty()) {
        EmptyState("Aucune carte pour l'instant", "Les phrases dont l'origine est documentée apparaîtront ici, une carte chacune.", Modifier.padding(16.dp))
        return
    }
    val pager = rememberPagerState { deck.size }
    Column(Modifier.fillMaxSize().padding(bottom = LocalPolBottom.current)) {
        Text(
            "Trente secondes par carte : la phrase, puis d'où vient l'idée. Glissez pour la suivante.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 12.dp, key = { it }) { i ->
            OriginCard(data, deck[i], i, deck.size, nav)
        }
    }
}

@Composable
private fun OriginCard(data: PolData, c: Claim, rank: Int, total: Int, nav: PolNavigator) {
    var flipped by rememberSaveable(rank) { mutableStateOf(false) }
    val still = reducedMotion()
    val angle by animateFloatAsState(if (flipped) 180f else 0f, tween(if (still) 0 else 500), label = "retourner")
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val count = "%02d / %02d".format(rank + 1, total)
    Box(Modifier.fillMaxSize().padding(vertical = 8.dp).graphicsLayer { rotationY = angle; cameraDistance = 16f * density }) {
        if (angle <= 90f) {
            Surface(
                { flipped = true }, Modifier.fillMaxSize().semantics { onClick(label = "Retourner la carte") { flipped = true; true } },
                shape = RoundedCornerShape(22.dp), color = scheme.surface, tonalElevation = 1.dp, shadowElevation = 2.dp,
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(count, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                        if (c.theme.isNotBlank()) Kicker(c.theme)
                    }
                    Spacer(Modifier.weight(1f))
                    Kicker("Phrase entendue")
                    Text(frTypo(quoted(c.q)), Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton({ flipped = true }, Modifier.heightIn(min = 48.dp)) { Text("Ça vient d'où ?") }
                }
            }
        } else {
            val bg = duelNight()
            val fg = if (scheme.background.luminance() < 0.5f) Color(0xFFF2F5EF) else Color(0xFFF7F5EF)
            Surface(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }, shape = RoundedCornerShape(22.dp), color = bg, contentColor = fg, shadowElevation = 2.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(count, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fg.copy(alpha = 0.75f))
                        if (validClaimId(c.id)) SignalKey(nav, ReportTarget.Affirmation(c.id), ink = fg)
                        TextButton({ flipped = false }, Modifier.heightIn(min = 48.dp)) { Text("Retourner", color = fg) }
                    }
                    Text(frTypo(quoted(c.q)), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.85f))
                    Kicker("D'où vient l'idée", Modifier.padding(top = 12.dp), color = fg.copy(alpha = 0.75f))
                    Text(frTypo(c.origine.trim()), fontSize = 17.sp, lineHeight = 23.sp)
                    c.cache?.takeIf { it.isNotBlank() }?.let {
                        Kicker("Ce qu'elle cache", Modifier.padding(top = 12.dp), color = fg.copy(alpha = 0.75f))
                        Text(frTypo(it.trim()), fontSize = 17.sp, lineHeight = 23.sp)
                    }
                    VerdictChip(c.v, Modifier.padding(top = 14.dp))
                    // Sur fond clair : l'étiquette de l'évaluateur garde son contraste.
                    sourcesOf(c.src).firstOrNull()?.let {
                        Box(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(4.dp)) {
                            SourceButton(it, data, { ev -> nav.sheet(PolSheetState.Evaluator(ev)) })
                        }
                    }
                }
            }
        }
    }
}
