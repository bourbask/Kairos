package app.kairos.mobile.ui.politique.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.FullscreenButton
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.Muted
import app.kairos.mobile.ui.politique.kit.PillOption
import app.kairos.mobile.ui.politique.kit.Pills
import app.kairos.mobile.ui.politique.kit.SourceButton
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.quoted
import app.kairos.mobile.ui.politique.kit.verdictLabel
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.fold

private val POUVOIRS = listOf("gouvernement" to "Gouvernement", "opposition" to "Opposition")

@Composable
fun DeclarationsScreen(data: PolData, nav: PolNavigator) {
    val st = remember(data) { declState(data) }
    var pouvoir by rememberSaveable { mutableStateOf("") }
    var verdict by rememberSaveable { mutableStateOf("") }
    var camp by rememberSaveable { mutableStateOf("") }
    val view = remember(st, pouvoir, verdict, camp) { declView(st, DeclFilter(pouvoir, verdict, camp)) }
    val cards = remember(view) { view.map { declTableCard(data, st.list[it]) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        part {
            Column {
                Lead(
                    "Ce qu'ils ont dit en public, face aux faits.",
                    "Verdicts du plus objectif au moins : la phrase contredite par son auteur, par la justice, par un document officiel, par une preuve publiée ; " +
                        "puis « exact » (phrase contestée qui se révèle vraie) et « non vérifiable ». L'icône plein écran montre la carte en grand, à table.",
                )
                Muted("La parole du gouvernement engage l'État : signalée, même grille. Le verdict juge la phrase, pas la personne.", Modifier.padding(top = 4.dp))
            }
        }
        if (st.list.isEmpty()) {
            part { EmptyState("Aucune déclaration pour l'instant", "Les déclarations publiques confrontées aux faits apparaîtront ici, avec leurs preuves.") }
            return@LazyColumn
        }
        part {
            Column {
                Kicker("Pouvoir", Modifier.padding(top = 4.dp, bottom = 6.dp))
                Pills(
                    listOf(PillOption("", "Tous", st.list.size)) + POUVOIRS.map { (k, l) -> PillOption(k, l, st.list.count { fold(it.pouvoir) == k }) },
                    pouvoir, onSelect = { pouvoir = it },
                )
                Kicker("Verdict", Modifier.padding(top = 12.dp, bottom = 6.dp))
                Pills(listOf(PillOption("", "Tous")) + st.verdicts.map { k -> PillOption(k, verdictLabel(k), st.list.count { it.verdict == k }) }, verdict, onSelect = { verdict = it })
                if (st.camps.isNotEmpty()) {
                    Kicker("Camp", Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Pills(
                        listOf(PillOption("", "Tous")) + st.camps.map { k -> PillOption(k, data.byId(k)?.nom ?: k, st.campIds.count { it == k }, identityColor(data.byId(k)?.couleur)) },
                        camp, onSelect = { camp = it },
                    )
                }
                Text(
                    frCount(view.size, "déclaration", "déclarations"),
                    Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (view.isEmpty()) part { EmptyState("Aucune déclaration pour ce choix", "Aucune déclaration ne combine ce pouvoir, ce verdict et ce camp. Élargissez un des filtres (« Tous »).") }
        items(view.size, key = { declKey(st.list[view[it]], view[it]) }) { j ->
            DeclarationCard(data, st.list[view[j]], nav) { nav.table(cards, j) }
        }
    }
}

/** Carte d'une déclaration : tout est visible, réponse de l'intéressé comprise ; [onTable] : plein écran ; [self] : fiche ouverte (auteur non répété). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeclarationCard(data: PolData, d: Declaration, nav: PolNavigator, self: String? = null, onTable: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    val who = whoOf(data, d)
    FigCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (who != null) { if (who != self) IdentityChip(data, who, onClick = { nav.sheet(PolSheetState.Identity(who)) }) }
                else Text(d.qui, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (isGovernment(d)) Text(
                    "Gouvernement", Modifier.clip(RoundedCornerShape(8.dp)).background(scheme.surfaceVariant).padding(horizontal = 9.dp, vertical = 4.dp),
                    fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            FullscreenButton(onTable)
        }
        declKicker(d, withGov = false).takeIf { it.isNotEmpty() }?.let { Kicker(it) }
        if (d.contexte.isNotBlank()) Muted(d.contexte, Modifier.padding(top = 2.dp))
        Text(frTypo(quoted(d.citation)), Modifier.padding(top = 8.dp), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        citationSource(d)?.let { SourceButton(it, data, onEv, Modifier.padding(top = 4.dp)) }
        VerdictChip(d.verdict, Modifier.padding(top = 8.dp))
        d.faits.filter { it.isNotBlank() }.forEach { f ->
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(scheme.onSurfaceVariant))
                Text(frTypo(f), fontSize = 14.5.sp, lineHeight = 21.sp)
            }
        }
        d.reponse?.takeIf { it.isNotBlank() }?.let { r ->
            Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.outline))
                Column(Modifier.padding(start = 10.dp)) {
                    Kicker("Sa réponse")
                    Text(frTypo(r), Modifier.padding(top = 2.dp), fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        ProcedeLink(d.procede, nav)
        SourceList(preuvesOf(d), data, onEv)
    }
}
