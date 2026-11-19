package app.kairos.mobile.ui.politique.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.Muted
import app.kairos.mobile.ui.politique.kit.PillOption
import app.kairos.mobile.ui.politique.kit.Pills
import app.kairos.mobile.ui.politique.kit.SourceButton
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.textOn
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf

/** Fond d'une marche : neutre, de plus en plus foncé (ou clair en sombre) avec la gravité ; jamais une couleur de parti. */
@Composable
private fun graviteBg(n: Int): Color {
    val s = MaterialTheme.colorScheme
    return lerp(s.surfaceVariant, s.onSurface, if (n in 1..4) 0.12f + 0.2f * (n - 1) else 0f)
}

@Composable
fun GraviteChip(n: Int, modifier: Modifier = Modifier) {
    val bg = graviteBg(n)
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (n in 1..4) Text("$n/4 · ", color = textOn(bg), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(graviteLabel(n), color = textOn(bg), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    }
}

/** Échelle en tête : quatre marches montantes (décor), puis chaque marche en clair. */
@Composable
private fun GraviteScale() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            GRAVITES.forEach { g ->
                val bg = graviteBg(g.n)
                Box(Modifier.weight(1f).height((14 + 12 * g.n).dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(bg), contentAlignment = Alignment.TopStart) {
                    Text("${g.n}", Modifier.padding(6.dp), color = textOn(bg), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
        GRAVITES.forEach { g ->
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${g.n}", Modifier.width(14.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Column {
                    Text(g.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Muted(g.sens)
                }
            }
        }
    }
}

@Composable
fun PromessesScreen(data: PolData, nav: PolNavigator) {
    val st = remember(data) { promState(data) }
    var qui by rememberSaveable { mutableStateOf("") }
    var gravite by rememberSaveable { mutableIntStateOf(0) }
    var theme by rememberSaveable { mutableStateOf("") }
    val view = remember(st, qui, gravite, theme) { promView(st, PromFilter(qui, gravite, theme)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        part {
            Column {
                Lead("Chaque promesse classée sur une même échelle, de la divergence ordinaire à l'atteinte aux droits.")
                GraviteScale()
                Muted("La gravité juge la promesse, pas la personne. Le fondement juridique est cité quand il existe.", Modifier.padding(top = 10.dp))
            }
        }
        if (st.list.isEmpty()) {
            part { EmptyState("Aucune promesse classée pour l'instant", "Les promesses, leur gravité et leur fondement juridique apparaîtront ici, avec leurs sources.") }
            return@LazyColumn
        }
        part {
            Column {
                if (st.ids.isNotEmpty()) {
                    Kicker("Qui", Modifier.padding(top = 4.dp, bottom = 6.dp))
                    Pills(
                        listOf(PillOption("", "Tous", st.list.size)) + st.ids.map { k -> PillOption(k, data.byId(k)?.nom ?: k, st.who.count { it == k }, identityColor(data.byId(k)?.couleur)) },
                        qui, onSelect = { qui = it },
                    )
                }
                Kicker("Gravité", Modifier.padding(top = 12.dp, bottom = 6.dp))
                Pills(
                    listOf(PillOption("", "Toutes")) + st.graves.map { g -> PillOption("$g", graviteLabel(g), st.list.count { it.gravite == g }) },
                    if (gravite == 0) "" else "$gravite", onSelect = { gravite = it.toIntOrNull() ?: 0 },
                )
                if (st.themes.size > 1) {
                    Kicker("Thème", Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Pills(listOf(PillOption("", "Tous")) + st.themes.map { t -> PillOption(t, t, st.list.count { it.theme.trim() == t }) }, theme, onSelect = { theme = it })
                }
                Text(
                    frCount(view.size, "promesse", "promesses"),
                    Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (view.isEmpty()) part { EmptyState("Aucune promesse pour ce choix", "Aucune promesse ne combine ces filtres. Élargissez-en un (« Tous »).") }
        items(view, key = { "$it:${st.list[it].id}" }) { i -> PromesseCard(data, st.list[i], nav) }
    }
}

/** Carte d'une promesse : qui, thème, promesse, gravité, pourquoi, fondement juridique, sources ; [self] : fiche ouverte (auteur non répété). */
@Composable
internal fun PromesseCard(data: PolData, p: Promesse, nav: PolNavigator, self: String? = null) {
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    val who = promWho(data, p)
    FigCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (who != null) { if (who != self) IdentityChip(data, who, onClick = { nav.sheet(PolSheetState.Identity(who)) }) }
            else if (p.qui.isNotBlank()) Text(p.qui, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (p.theme.isNotBlank()) Kicker(p.theme, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            if (p.id.isNotBlank()) SignalKey(nav, "promesse", infoRef(p.id), "${p.qui} : ${p.promesse.trim()} · ${graviteLabel(p.gravite)}", p.qui)
        }
        Text(frTypo(p.promesse.trim()), Modifier.padding(top = 8.dp), fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        GraviteChip(p.gravite, Modifier.padding(top = 8.dp))
        if (p.pourquoi.isNotBlank()) Text(frTypo(p.pourquoi.trim()), Modifier.padding(top = 8.dp), fontSize = 14.5.sp, lineHeight = 21.sp)
        fondementOf(p)?.let {
            Kicker("Fondement juridique", Modifier.padding(top = 10.dp))
            SourceButton(it, data, onEv, Modifier.padding(top = 2.dp))
        }
        SourceList(sourcesOf(p.src), data, onEv)
    }
}
