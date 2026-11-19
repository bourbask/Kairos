package app.kairos.mobile.ui.politique.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolRoute
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.LevelTag
import app.kairos.mobile.ui.politique.kit.NO_FICHE_TEXT
import app.kairos.mobile.ui.politique.kit.PixelKey
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.indLevel
import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.sourcesOf
import app.kairos.mobile.ui.politique.reducedMotion
import app.kairos.mobile.ui.theme.mix
import kotlinx.coroutines.launch

/** Feuille d'un producteur, en deux paliers : sa fiche, puis l'explication des niveaux, dans la même feuille. */

/** Évaluateur désigné par son id ; introuvable (données rechargées) : feuille explicative. */
@Composable
fun EvaluatorSheetAt(data: PolData, id: String, nav: PolNavigator, onDismiss: () -> Unit) {
    val e = data.evaluators[id]
    if (e != null) ProducerSheet(data, e, e.nom, nav, onDismiss)
    else PolSheet(full = false, onDismiss = onDismiss) { EmptyState("Évaluateur introuvable", "Cette fiche n'est plus dans les données chargées. Fermez la feuille et rouvrez-la depuis la liste.", Modifier.padding(16.dp)) }
}

/** Producteur [e], ou source signée [name] sans fiche (`e` null) : même feuille, mêmes paliers. */
@Composable
fun ProducerSheet(data: PolData, e: Evaluator?, name: String, nav: PolNavigator, onDismiss: () -> Unit) {
    var tier by rememberSaveable { mutableStateOf(Tier.Fiche) }
    // Hors du palier : la fiche retrouve sa position au retour.
    val ficheScroll = rememberScrollState()
    val toLevels = remember { FocusRequester() }
    var cameBack by remember { mutableStateOf(false) }
    PolSheet(full = true, onDismiss = onDismiss) {
        // Déclaré dans la feuille, après le sien : le retour remonte d'abord à la fiche.
        BackHandler(enabled = tier == Tier.Niveaux) { tier = tier.back() ?: Tier.Fiche; cameBack = true }
        when (tier) {
            Tier.Fiche -> {
                ProducerFiche(data, e, name, nav, ficheScroll, toLevels) { tier = Tier.Niveaux }
                LaunchedEffect(cameBack) { if (cameBack) { runCatching { toLevels.requestFocus() }; cameBack = false } }
            }
            Tier.Niveaux -> LevelsExplained(data, hl = e?.let(::indLevel) ?: 0, nav) { tier = Tier.Fiche; cameBack = true }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Kicker(title)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** Palier 2 : type, nom, indépendance (libellé, jauge, justification, accès aux niveaux), puis le détail de la fiche. */
@Composable
private fun ProducerFiche(data: PolData, e: Evaluator?, name: String, nav: PolNavigator, scroll: ScrollState, toLevels: FocusRequester, onLevels: () -> Unit) {
    val n = e?.let(::indLevel) ?: 0
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Kicker(sourceTypeLabel(e?.type.orEmpty(), if (e != null) "Évaluateur" else "Non précisé"))
        Text(name, Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold)
        FigCard(Modifier.padding(top = 14.dp)) {
            Kicker("Indépendance", Modifier.semantics { heading() })
            Text(if (n > 0) "Niveau $n · ${data.levelLabel(n)}" else "Indépendance non qualifiée", Modifier.padding(top = 4.dp), fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
            LevelGauge(n, Modifier.padding(top = 8.dp))
            val just = if (e == null) NO_FICHE_TEXT else e.justification
            if (just.isNotBlank()) Text(frTypo(just), Modifier.padding(top = 10.dp), fontSize = 14.sp, lineHeight = 20.sp)
            PixelKey(
                onLevels, "Comprendre les niveaux", Modifier.padding(top = 6.dp),
                fg = scheme.onPrimaryContainer, bg = scheme.primaryContainer, hoverBg = mix(scheme.primaryContainer, scheme.primary, .14f), focusRequester = toLevels,
            ) { Text("Comprendre les niveaux", fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = scheme.onPrimaryContainer) }
        }
        if (e == null) { Spacer(Modifier.height(16.dp)); return@Column }
        if (e.statut.isNotBlank()) Block("Statut") { Text(frTypo(e.statut), fontSize = 14.5.sp, lineHeight = 21.sp) }
        val fin = e.financement.filter { it.isNotBlank() }
        if (fin.isNotEmpty()) Block("Financement") { fin.forEach { Text("• ${frTypo(it)}", Modifier.padding(top = 2.dp), fontSize = 14.5.sp, lineHeight = 21.sp) } }
        val dirs = e.dirigeants.filter { it.nom.isNotBlank() }
        if (dirs.isNotEmpty()) Block("Dirigeants") {
            dirs.forEachIndexed { i, d ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(scheme.outlineVariant))
                Text(d.nom, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (d.role.isNotBlank()) Text(frTypo(d.role), fontSize = 12.5.sp, color = muted)
                if (d.parcours.isNotBlank()) Text(frTypo(d.parcours), Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        if (e.orientation.isNotBlank()) Block("Orientation") { Text(frTypo(e.orientation), fontSize = 14.5.sp, lineHeight = 21.sp) }
        Block("Tendance observée selon les camps") {
            Text(
                frTypo(if (hasTendency(e)) e.tendance else "Non mesurée : aucune étude ne documente une sévérité différente selon les camps dans ses chiffrages."),
                fontSize = 14.5.sp, lineHeight = 21.sp,
            )
        }
        if (e.historique.isNotEmpty()) Block("Historique") {
            e.historique.forEachIndexed { i, h ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(scheme.outlineVariant))
                Text(listOfNotNull(h.annee?.toString(), h.sujet.takeIf { it.isNotBlank() }).joinToString(" · "), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (h.constat.isNotBlank()) Text(frTypo(h.constat), Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp)
                SourceList(sourcesOf(h.src), data, onEv, Modifier.padding(top = 8.dp))
            }
        }
        val ctr = e.controverses.filter { it.t.isNotBlank() }
        if (ctr.isNotEmpty()) Block("Controverses") {
            ctr.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(scheme.outlineVariant))
                Text(frTypo(c.t), fontSize = 14.sp, lineHeight = 20.sp)
                SourceList(sourcesOf(c.src), data, onEv, Modifier.padding(top = 8.dp))
            }
        }
        Block("Sources de la fiche") {
            val srcs = sourcesOf(e.src)
            if (srcs.isEmpty()) Text("Aucune source listée.", fontSize = 13.sp, color = muted) else SourceList(srcs, data, onEv, Modifier.padding(top = 0.dp))
        }
        FilledTonalButton({ nav.go(PolRoute.Rubric("evaluateurs")) }, Modifier.padding(top = 20.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("Tous les évaluateurs") }
    }
}

/** Jauge pixel de 4 cases : seule celle du niveau est pleine, à l'encre ; lue en texte, jamais par la couleur seule. */
@Composable
private fun LevelGauge(n: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = if (n > 0) "Niveau $n sur 4" else "Indépendance non qualifiée" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..4).forEach { i ->
                val on = i == n
                Box(
                    Modifier.weight(1f).height(18.dp)
                        .background(if (on) scheme.onSurface else mix(scheme.surface, scheme.onSurface, .07f))
                        .border(2.dp, if (on) mix(scheme.onSurface, Color.Black, .45f) else mix(scheme.surface, scheme.onSurface, .20f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Kicker("1 · statutaire"); Kicker("4 · militant")
        }
    }
}

/** Palier 3 : principe, puis chaque niveau (libellé, définition, critères, producteurs repliés) ; [hl] mis en évidence. */
@Composable
private fun LevelsExplained(data: PolData, hl: Int, nav: PolNavigator, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val levels = remember(data, hl) { levelViews(data, hl) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    var hlY by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)) {
        PixelKey(onBack, "Retour à la fiche") { Text("‹ Retour à la fiche", fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface) }
        Kicker("Comprendre les niveaux", Modifier.padding(top = 10.dp))
        Text("Les niveaux d'indépendance", Modifier.padding(top = 6.dp).semantics { heading() }, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(frTypo(data.principe), Modifier.padding(top = 8.dp), fontSize = 14.5.sp, lineHeight = 21.sp)
        if (data.levelsProvisional) Text("Provisoire : définitions en cours de relecture.", Modifier.padding(top = 6.dp), fontSize = 12.5.sp, color = muted)
        levels.firstOrNull { it.highlighted }?.let {
            PixelKey(
                { scope.launch { if (still) scroll.scrollTo(hlY.toInt()) else scroll.animateScrollTo(hlY.toInt()) } },
                if (hl > 0) "Voir le niveau $hl" else "Voir « Non qualifié »", Modifier.padding(top = 8.dp),
            ) { Text(if (hl > 0) "↓ Voir le niveau $hl" else "↓ Voir « Non qualifié »", fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface) }
        }
        levels.forEach { v ->
            val L = v.niveau
            FigCard(
                Modifier.padding(top = 12.dp).let { m -> if (v.highlighted) m.onPlaced { hlY = it.positionInParent().y } else m },
                inner = if (v.highlighted) Modifier.background(mix(scheme.surface, scheme.primaryContainer, .6f)) else Modifier,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LevelTag(L.n)
                    if (v.highlighted) Text("← niveau de ce producteur", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = "Niveau de ce producteur" })
                }
                Text(frTypo(data.levelLabel(L.n)), Modifier.padding(top = 8.dp).semantics { heading() }, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                if (L.definition.isNotBlank()) Text(frTypo(L.definition), Modifier.padding(top = 4.dp), fontSize = 14.sp, lineHeight = 20.sp)
                val crit = L.criteres.filter { it.isNotBlank() }
                if (crit.isNotEmpty()) {
                    Kicker("Critères observés", Modifier.padding(top = 10.dp))
                    crit.forEach { Text("• ${frTypo(it)}", Modifier.padding(top = 2.dp), fontSize = 13.5.sp, lineHeight = 19.sp) }
                }
                if (v.producers.isNotEmpty()) ProducerList(v.producers) { nav.sheet(PolSheetState.Evaluator(it)) }
                if (L.n == 0) Text("Les sources marquées « ? » sans fiche relèvent aussi de ce niveau.", Modifier.padding(top = 8.dp), fontSize = 12.5.sp, color = muted)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Producteurs d'un niveau, repliés : chacun ouvre sa fiche dans une nouvelle feuille. */
@Composable
private fun ProducerList(producers: List<Evaluator>, onOpen: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val count = "${producers.size} producteur${if (producers.size > 1) "s" else ""} à ce niveau"
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Liste dépliée" else "Liste repliée" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (open) "▾" else "▸", Modifier.size(16.dp).clearAndSetSemantics { }, fontSize = 14.sp)
        Text(count, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    if (open) producers.forEach { p ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(p.id) }
                .semantics(mergeDescendants = true) { contentDescription = "${p.nom}. Ouvrir sa fiche" }.padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.nom, Modifier.weight(1f), fontSize = 14.sp)
            Text("›", Modifier.clearAndSetSemantics { }, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
