package app.kairos.mobile.ui.politique

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.kit.BalanceBlock
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.KeyRow
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.RichText
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.Claim
import app.kairos.mobile.ui.politique.screens.CHAMBERS
import app.kairos.mobile.ui.politique.screens.DV_LABELS
import app.kairos.mobile.ui.politique.screens.MARK_LABEL
import app.kairos.mobile.ui.politique.screens.TURN_KINDS
import app.kairos.mobile.ui.politique.screens.VOTE_WORDS
import app.kairos.mobile.ui.politique.screens.statusLabel
import app.kairos.mobile.ui.politique.screens.CityFact
import app.kairos.mobile.ui.politique.screens.CohGroup
import app.kairos.mobile.ui.politique.screens.Commune
import app.kairos.mobile.ui.politique.screens.Gap
import app.kairos.mobile.ui.politique.screens.MatrixRow
import app.kairos.mobile.ui.politique.screens.Measure
import app.kairos.mobile.ui.politique.screens.Program
import app.kairos.mobile.ui.politique.screens.Turn
import app.kairos.mobile.ui.politique.screens.VoteRow
import app.kairos.mobile.ui.politique.screens.cityFactsOf
import app.kairos.mobile.ui.politique.screens.muniOf
import app.kairos.mobile.ui.politique.screens.rhetExamples
import app.kairos.mobile.ui.politique.screens.rhetOf
import app.kairos.mobile.ui.politique.screens.shortLabel
import app.kairos.mobile.ui.politique.screens.groupIndex
import app.kairos.mobile.ui.politique.screens.turnsOf
import app.kairos.mobile.ui.politique.screens.factKind
import app.kairos.mobile.ui.politique.screens.AideRow
import app.kairos.mobile.ui.politique.screens.Affaire
import app.kairos.mobile.ui.politique.screens.Campagne
import app.kairos.mobile.ui.politique.screens.Dossier
import app.kairos.mobile.ui.politique.screens.PartiComptes
import app.kairos.mobile.ui.politique.screens.affairesOf
import app.kairos.mobile.ui.politique.screens.aideOf
import app.kairos.mobile.ui.politique.screens.campagnesOf
import app.kairos.mobile.ui.politique.screens.financingOf
import app.kairos.mobile.ui.politique.screens.lobbyOf
import app.kairos.mobile.ui.politique.screens.moneyLabel
import app.kairos.mobile.ui.politique.screens.netLabel
import app.kairos.mobile.ui.politique.screens.partisOf
import app.kairos.mobile.ui.politique.screens.procedureStage
import app.kairos.mobile.ui.politique.screens.stageTone
import app.kairos.mobile.ui.politique.screens.DvItem
import app.kairos.mobile.ui.politique.screens.claimsOf
import app.kairos.mobile.ui.politique.screens.NO_VALUE
import app.kairos.mobile.ui.politique.screens.fmtNum
import app.kairos.mobile.ui.politique.screens.fmtSigned
import app.kairos.mobile.ui.politique.screens.refDebt
import app.kairos.mobile.ui.politique.screens.sideOf
import app.kairos.mobile.ui.politique.screens.dvCount
import app.kairos.mobile.ui.politique.screens.dvFor
import app.kairos.mobile.ui.politique.screens.NO_PUBLIC_STATEMENT
import app.kairos.mobile.ui.politique.screens.noGapsMessage
import app.kairos.mobile.ui.politique.screens.publicRateLabel
import app.kairos.mobile.ui.politique.model.IdKind
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.sourcesOf
import kotlinx.coroutines.launch

data class Stance(val pos: String, val text: String)

/** Tout ce que les données rattachent à une identité, par correspondance d'alias. */
class IdentityLinks(
    val programs: List<Program>, val gaps: List<Gap>, val measures: List<Measure>,
    val matrix: List<Pair<MatrixRow, Stance>>, val claims: List<Claim>, val coh: List<CohGroup>, val turns: List<Turn>,
    val cases: List<Affaire>, val money: List<Dossier>, val aide: List<AideRow>, val partis: List<PartiComptes>, val c22: List<Campagne>,
    val muni: List<Dossier>, val villes: List<CityFact>, val rhet: List<Pair<String, String>>, val communes: List<Commune>,
    /** Chambre (« AN », « PE ») → nom du groupe et votes de ce groupe. */
    val votes: List<Triple<String, String, List<Pair<VoteRow, List<String>>>>>,
    /** Scrutins croisés : ceux du parti pour une personne. */
    val dv: List<DvItem>, val dvOf: String?, private val id: String,
) {
    val size: Int get() = programs.size + gaps.size + measures.size + matrix.size + claims.size + coh.size + turns.size + cases.size +
        money.size + aide.size + partis.size + c22.size + muni.size + villes.size + rhet.size + communes.size + votes.sumOf { it.third.size } +
        if (dvOf == id) dv.size else 0
}

fun identityLinks(data: PolData, id: String): IdentityLinks {
    fun hit(label: String?) = id in data.idsIn(label)
    val votes = CHAMBERS.mapNotNull { (ch, name) ->
        val groups = data.decode<String>("votes", "${ch}_G")
        val g = groupIndex(data, groups, id).takeIf { it >= 0 } ?: return@mapNotNull null
        Triple(name, groups[g], data.decode<VoteRow>("votes", "${ch}_V").mapNotNull { v -> v.c.getOrNull(g)?.let { v to it } })
    }
    val dvOf = if (data.kindOf(id) == IdKind.Personne) data.partyOf(id) else id
    val dv = if (dvOf == null) emptyList() else dvFor(data, dvOf)
    return IdentityLinks(
        programs = data.decode<Program>("budgets", "PROGRAMS").filter { !it.ref && hit(it.who) },
        gaps = data.decode<Gap>("budgets", "GAPS").filter { hit(it.who) },
        measures = data.decode<Measure>("niveau-de-vie", "MEASURES").filter { hit(it.who) },
        matrix = data.decode<MatrixRow>("bilan", "MATRIX").flatMap { r ->
            r.c.filterKeys { hit(it) }.values.map { p -> r to Stance(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }) }
        },
        claims = data.decode<Claim>("affirmations", "CLAIMS").filter { hit(it.camp) },
        coh = data.decode<CohGroup>("votes", "COH").filter { hit(it.camp) },
        turns = turnsOf(data, id),
        cases = affairesOf(data).filter { a -> if (data.idsIn(a.who).isNotEmpty()) hit(a.who) else hit(a.party) },
        money = (financingOf(data) + lobbyOf(data)).filter { hit(it.t) },
        aide = aideOf(data).rows.filter { hit(it.l) },
        partis = partisOf(data).filter { hit(it.parti) },
        c22 = campagnesOf(data).filter { hit(it.cand) },
        muni = muniOf(data).filter { hit(it.t) },
        villes = cityFactsOf(data).filter { hit(it.maire) },
        rhet = rhetOf(data).flatMap { r -> rhetExamples(r).filter { hit(it.who) }.map { r.t to it.text } },
        communes = data.decode<Commune>("villes", "COMMUNES").filter { it.qui == id },
        votes = votes,
        dv = dv,
        dvOf = dvOf,
        id = id,
    )
}

/** Ligne propre à l'identité parmi celles qui la citent : celle à son nom ou son parti, sinon la seule ; plusieurs sans nom : aucune. */
internal fun <T> List<T>.own(data: PolData, id: String, label: (T) -> String): T? {
    val x = data.byId(id) ?: return singleOrNull()
    return firstOrNull { label(it) == x.parti || label(it) == x.nom } ?: singleOrNull()
}

fun ownProgram(l: IdentityLinks, data: PolData, id: String) = l.programs.own(data, id) { it.who }
fun ownAide(l: IdentityLinks, data: PolData, id: String) = l.aide.own(data, id) { it.l }
fun ownPartis(l: IdentityLinks, data: PolData, id: String) = l.partis.own(data, id) { it.parti }

// ---------------------------------------------------------------- chiffres clés

data class Stat(val label: String, val value: String, val sub: String)

private val FRAGILE = setOf("fragile", "faux", "non-documente")

/** Jusqu'à quatre chiffres clés, chacun seulement si la donnée existe. */
fun identityStats(data: PolData, id: String, l: IdentityLinks = identityLinks(data, id)): List<Stat> {
    val x = data.byId(id) ?: return emptyList()
    val out = mutableListOf<Stat>()
    ownProgram(l, data, id)?.let { p ->
        // Même référence et même comparaison que l'écran Budgets.
        val ref = data.refDebt(data.decode<Program>("budgets", "PROGRAMS"))
        p.debt?.let {
            val side = ref?.let { r -> sideOf(it, r) }
            out += Stat("Dette à l'horizon", fmtNum(it, 1) + " %", when {
                side == null -> "part du PIB"
                side < 0 -> "sous la référence"
                side > 0 -> "au-dessus de la référence"
                else -> "au niveau de la référence"
            })
        }
        p.hh?.let { out += Stat("Par ménage", fmtSigned(it) + " €", "par an, en moyenne") }
    }
    ownAide(l, data, id)?.let { a ->
        a.v?.let { out += Stat("Aide publique", moneyLabel(it), if (data.idsIn(a.l).any { o -> o != id }) "bloc « ${shortLabel(a.l)} »" else "aide annuelle") }
    }
    ownPartis(l, data, id)?.net?.let { out += Stat("Situation nette", netLabel(it), "derniers comptes publiés") }
    if (l.claims.isNotEmpty()) {
        out += Stat("Phrases vérifiées", l.claims.size.toString(), plural(l.claims.count { it.v == "solide" }, "solide", "solides") + " · " + plural(l.claims.count { it.v in FRAGILE }, "fragile ou faux", "fragiles ou faux"))
    }
    val k = dvCount(l.dv)
    if (k.t > 0) out += Stat("Dit / voté", "${k.c} / ${k.t}", "votes conformes à la parole publique")
    if (l.coh.isNotEmpty()) out += Stat("Votes à rebours", l.coh.sumOf { g -> g.items.count { it.firstOrNull() != "coherent" } }.toString(), "écarts relevés")
    if (l.turns.isNotEmpty()) {
        val r = l.turns.count { it.k == "R" }
        out += Stat("Positions suivies", l.turns.size.toString(), plural(r, "revirement", "revirements"))
    }
    if (l.cases.isNotEmpty()) out += Stat("Procédures", l.cases.size.toString(), "voir l'onglet Justice")
    if (l.matrix.isNotEmpty() && out.size < 4) out += Stat("Bilan sortant", "${l.matrix.count { it.second.pos == "keep" }} / ${l.matrix.size}", "orientations poursuivies")
    return out.take(4)
}

// ---------------------------------------------------------------- feuille

private val KIND_ONE = mapOf(IdKind.Personne to "Candidat", IdKind.Parti to "Parti", IdKind.Camp to "Camp")
private val COH_LABEL = mapOf("ecart" to "Écart", "coherent" to "Cohérent", "explique" to "Écart expliqué")

/** Section de la fiche : titre d'onglet et contenu. */
private class Section(val title: String, val content: @Composable () -> Unit)

/** Fiche identité plein écran : en-tête, chiffres clés, puis sections balayables avec onglets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentitySheet(data: PolData, id: String, nav: PolNavigator, onDismiss: () -> Unit) {
    val x = data.byId(id)
    PolSheet(full = true, onDismiss = onDismiss) {
        if (x == null) {
            EmptyState("Fiche introuvable", "Cette identité n'existe plus dans les données. Revenez à l'accueil.", Modifier.padding(16.dp), "Fermer" to onDismiss)
            return@PolSheet
        }
        // Rattachements : parcours de tous les fichiers, hors du fil d'affichage.
        val computed by produceState<Pair<IdentityLinks, List<Stat>>?>(null, data, id) {
            value = withContext(Dispatchers.Default) { identityLinks(data, id).let { it to identityStats(data, id, it) } }
        }
        val (links, stats) = computed ?: run {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Chargement de la fiche" })
            }
            return@PolSheet
        }
        val sections = sections(data, id, links, nav)
        val kind = data.kindOf(id)
        val members = data.identities.filter { it.id != id && data.kindOf(it.id) == IdKind.Personne && data.partyOf(it.id) == id }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IdentityBadge(x, 92.dp)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Kicker(KIND_ONE.getValue(kind))
                        Text(x.nom, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        val party = data.partyOf(id)
                        Box(Modifier.padding(top = 6.dp)) {
                            when {
                                kind == IdKind.Personne && party != null ->
                                    IdentityChip(data, party, text = "${x.parti} · voir la fiche", onClick = { nav.sheet(PolSheetState.Identity(party)) })
                                else -> IdentityChip(data, id, text = if (kind == IdKind.Camp) "Camp politique" else x.parti?.takeIf { it != x.nom } ?: "Parti")
                            }
                        }
                    }
                }
                if (members.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Kicker(if (members.size > 1) "Personnalités" else "Candidat", Modifier.align(Alignment.CenterVertically))
                        members.forEach { m -> IdentityChip(data, m.id, text = "${m.nom} · fiche", onClick = { nav.sheet(PolSheetState.Identity(m.id)) }) }
                    }
                }
                if (stats.isNotEmpty()) StatsGrid(stats)
            }
            if (sections.isEmpty()) {
                EmptyState(
                    "Peu de données pour cette fiche",
                    "Aucune rubrique ne cite encore ${x.nom} de façon identifiable" +
                        (if (members.isNotEmpty()) " ; les données sont rattachées à la fiche de sa personnalité ci-dessus" else "") +
                        ". La fiche se remplira dès qu'un programme, un vote ou une affaire y sera rattaché.",
                    Modifier.padding(16.dp),
                    "Fermer" to onDismiss,
                )
                return@Column
            }
            SectionPager(sections)
            Text(
                "Faites glisser horizontalement pour passer d'une section à l'autre.",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun StatsGrid(stats: List<Stat>) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { s ->
                    Card(Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) {}) {
                        Kicker(s.label)
                        Text(s.value, Modifier.padding(top = 4.dp), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text(s.sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionPager(sections: List<Section>) {
    val pager = rememberPagerState { sections.size }
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    PrimaryScrollableTabRow(
        selectedTabIndex = pager.currentPage.coerceIn(0, sections.size - 1),
        modifier = Modifier.padding(top = 12.dp),
        edgePadding = 16.dp,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        sections.forEachIndexed { i, s ->
            Tab(
                selected = pager.currentPage == i,
                onClick = { scope.launch { if (still) pager.scrollToPage(i) else pager.animateScrollToPage(i) } },
                text = { Text(s.title, fontWeight = FontWeight.SemiBold) },
            )
        }
    }
    // Hauteur du contenu : la page active fixe la hauteur, la feuille défile.
    HorizontalPager(pager, Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { i ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = sections[i].title }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sections[i].content()
        }
    }
}

/** Animations système coupées : les défilements sautent directement. */
@Composable
internal fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp),
        content = content,
    )
}

/** Étiquette d'état : couleur neutre ou d'état, jamais celle d'un parti. */
@Composable
private fun Tag(text: String, fg: Color = MaterialTheme.colorScheme.onSurfaceVariant, bg: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Text(
        text, Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** Couleur d'état d'un code : solide / partiel / fragile / pour / contre ; autre : neutre. */
@Composable
internal fun StateTag(text: String, tone: String?) {
    val pc = PolColors.current
    when (tone) {
        "solide" -> Tag(text, pc.solide, pc.solideBg)
        "partiel" -> Tag(text, pc.partiel, pc.partielBg)
        "fragile" -> Tag(text, pc.fragile, pc.fragileBg)
        "pour" -> Tag(text, pc.pour, pc.pourBg)
        "contre" -> Tag(text, pc.contre, pc.contreBg)
        else -> Tag(text)
    }
}

@Composable
private fun KeyValue(rows: List<Pair<String, String>>) {
    Column(Modifier.padding(top = 6.dp)) { rows.forEach { (k, v) -> KeyRow(k, v) } }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) = RichText(text, modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Title(text: String) = Text(frTypo(text), Modifier.padding(top = 6.dp), fontWeight = FontWeight.Bold, fontSize = 15.5.sp)

private fun Double?.or(d: Int = 1, unit: String): String = this?.let { fmtNum(it, d) + unit } ?: NO_VALUE

/** Sections non vides, dans l'ordre de la fiche. */
@Composable
private fun sections(data: PolData, id: String, l: IdentityLinks, nav: PolNavigator): List<Section> {
    val onEv: (String) -> Unit = { nav.sheet(PolSheetState.Evaluator(it)) }
    fun balance(lecture: Lecture?, title: String, who: String?): (@Composable () -> Unit)? =
        lecture?.let { { BalanceBlock(it, title, who, onOpen = { nav.sheet(PolSheetState.Balance(it, title, who)) }) } }
    val out = mutableListOf<Section>()
    val p = ownProgram(l, data, id)
    val m = l.measures.firstOrNull()
    if (p != null || m != null || l.gaps.isNotEmpty()) out += Section("Programme") {
        p?.let {
            Card {
                Kicker("Budget à l'horizon")
                KeyValue(
                    listOf(
                        "Dette publique" to it.debt.or(unit = " %"), "Déficit public" to it.deficit.or(unit = " %"),
                        "Effet net par an" to (it.net?.let { v -> fmtSigned(v, 1) + " Md€" } ?: NO_VALUE),
                        "Par ménage et par an" to (it.hh?.let { v -> fmtSigned(v) + " €" } ?: NO_VALUE),
                    ),
                )
                balance(it.lecture, "${it.who} : programme", it.who)?.invoke()
            }
        }
        m?.let {
            Card {
                Kicker("Mesures pour le pouvoir d'achat")
                it.m.split(" ; ").filter(String::isNotBlank).forEach { s -> Body("• $s") }
                if (it.b.isNotBlank()) { Kicker("Pour qui", Modifier.padding(top = 10.dp)); Body(it.b) }
                if (it.f.isNotBlank()) { Kicker("Financé par", Modifier.padding(top = 10.dp)); Body(it.f) }
                balance(it.lecture, "${it.who} : pouvoir d'achat", it.who)?.invoke()
            }
        }
        l.gaps.forEach { g ->
            Card {
                Kicker("Annoncé contre crédité")
                Title(g.what)
                Body("Annoncé ${g.claimed.or(unit = " ${g.unit}")} · ${if (g.inverse) "estimé" else "crédité"} ${g.credited.or(unit = " ${g.unit}")}")
                balance(g.lecture, g.what, g.who)?.invoke()
                g.src?.takeIf { it.isNotBlank() }?.let { SourceList(listOf(SourceRef(it, g.url, g.ev)), data, onEv) }
            }
        }
    }
    if (l.matrix.isNotEmpty()) out += Section("Bilan sortant") {
        l.matrix.forEach { (r, st) ->
            Card {
                Row { Tag(MARK_LABEL[st.pos] ?: "Position") }
                Title(r.o)
                Body(st.text)
            }
        }
    }
    if (l.votes.isNotEmpty()) out += Section("Votes") {
        l.votes.forEach { (chamber, group, rows) ->
            Kicker("$chamber · groupe $group", Modifier.padding(top = 6.dp))
            rows.forEach { (v, c) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val k = c.getOrElse(0) { "" }
                    Box(Modifier.width(92.dp)) { StateTag(VOTE_WORDS[k] ?: "—", if (k == "P") "pour" else if (k == "C") "contre" else null) }
                    Column(Modifier.weight(1f)) {
                        Text(frTypo(v.t), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        c.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
    if (l.dv.isNotEmpty()) out += Section("Dit / voté") {
        if (l.dvOf != null && l.dvOf != id) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Votes du parti : ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                IdentityChip(data, l.dvOf, onClick = { nav.sheet(PolSheetState.Identity(l.dvOf)) })
            }
        }
        val k = dvCount(l.dv)
        Kicker("Parole publique, hors séance")
        val rate = publicRateLabel(k)
        if (rate == null) Text(NO_PUBLIC_STATEMENT, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        else {
            Text(rate.figure, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(rate.text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Kicker("Écarts et votes ambigus", Modifier.padding(top = 12.dp))
        val gaps = l.dv.filter { it.verdict.v == "ecart" || it.verdict.v == "ambigu" }
            .sortedWith(compareBy<DvItem> { if (it.verdict.v == "ecart") 0 else 1 }.thenByDescending { it.scrutin.date })
        if (gaps.isEmpty()) Body(noGapsMessage(k, dvCount(l.dv, "seance")))
        gaps.forEach { d ->
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { StateTag(DV_LABELS[d.verdict.v].orEmpty(), if (d.verdict.v == "ecart") "fragile" else "partiel") }
                Title(d.scrutin.titre)
                Body(d.verdict.explication)
            }
        }
        TextButton({ nav.go(PolRoute.Rubric("dit-vote")) }, Modifier.heightIn(min = 48.dp)) { Text("Tous les scrutins croisés") }
    }
    if (l.claims.isNotEmpty()) out += Section("Phrases") {
        l.claims.forEach { c ->
            Card {
                Row { VerdictChip(c.v) }
                Body("« ${c.q} »", Modifier.padding(top = 6.dp))
                SourceList(sourcesOf(c.src), data, onEv)
                claimsOf(data).indexOf(c).takeIf { it >= 0 }?.let { i -> TextButton({ nav.sheet(PolSheetState.Claim(i)) }, Modifier.heightIn(min = 48.dp)) { Text("Les faits et ce qu'elle cache") } }
            }
        }
    }
    if (l.coh.isNotEmpty()) out += Section("Cohérence") {
        l.coh.flatMap { it.items }.forEach { item ->
            val k = item.getOrElse(0) { "" }
            Column(Modifier.padding(vertical = 4.dp)) {
                StateTag(COH_LABEL[k] ?: "—", when (k) { "coherent" -> "solide"; "explique" -> "partiel"; "ecart" -> "fragile"; else -> null })
                Body(item.getOrElse(1) { "" })
            }
        }
    }
    if (l.turns.isNotEmpty()) out += Section("Revirements") {
        l.turns.forEach { r ->
            Card {
                Row { StateTag(TURN_KINDS.firstOrNull { it.first == r.k }?.second ?: "Position", when (r.k) { "R" -> "fragile"; "I" -> "partiel"; "C" -> "solide"; else -> null }) }
                Title("${r.s} · ${r.p}")
                Body("<b>Avant.</b> ${r.a}")
                Body("<b>Après.</b> ${r.b}")
                SourceList(sourcesOf(r.src), data, onEv)
            }
        }
    }
    if (l.cases.isNotEmpty()) out += Section("Justice") {
        Card { Body("Présumé innocent tant qu'aucune condamnation n'est définitive.") }
        l.cases.forEach { a ->
            Card {
                Kicker(listOf(a.who, a.date).filter(String::isNotBlank).joinToString(" · "))
                Title(a.factsText)
                StageLadder(a.stg)
                Body(a.etat)
                a.url.takeIf { it.isNotBlank() }?.let { SourceList(listOf(SourceRef("Source", it)), data, onEv) }
            }
        }
    }
    val aide = ownAide(l, data, id)
    val part = ownPartis(l, data, id)
    if (aide != null || part != null || l.c22.isNotEmpty() || l.money.isNotEmpty()) out += Section("Argent") {
        aide?.v?.let { v -> Card { Kicker("Aide publique"); Text(moneyLabel(v), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) } }
        part?.let {
            Card {
                Kicker("Derniers comptes publiés")
                KeyValue(listOf("Dons et cotisations" to moneyLabel(it.dons), "Dette" to moneyLabel(it.dette), "Situation nette" to netLabel(it.net)))
            }
        }
        l.c22.forEach { c ->
            Card {
                Kicker("Présidentielle précédente · ${c.cand}")
                KeyValue(listOf("Score" to c.score, "Dépenses" to moneyLabel(c.depense), "Remboursé" to moneyLabel(c.rembourse)))
                c.note.takeIf { it.isNotBlank() }?.let { Body(it) }
            }
        }
        l.money.forEach { f ->
            Card {
                StageTag(f.stg)
                Title(f.t)
                Body(f.p)
                SourceList(sourcesOf(f.s), data, onEv)
            }
        }
    }
    if (l.communes.isNotEmpty() || l.muni.isNotEmpty() || l.villes.isNotEmpty()) out += Section("Villes") {
        l.communes.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.ville, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(statusLabel(c.statut), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        l.muni.forEach { mu -> Card { StageTag(mu.stg); Body(mu.p, Modifier.padding(top = 6.dp)) } }
        l.villes.forEach { f ->
            Card {
                Row { Tag(factKind(f.kind).first) }
                Title("${f.ville} · ${f.maire}")
                Body(f.text)
                if (f.srcLabel.isNotBlank() || f.srcUrl.isNotBlank()) SourceList(listOf(SourceRef(f.srcLabel, f.srcUrl)), data, onEv)
            }
        }
    }
    if (l.rhet.isNotEmpty()) out += Section("Procédés") {
        l.rhet.forEach { (t, text) -> Card { Kicker(t); Body(text) } }
    }
    return out
}

/** Étape d'une procédure : cinq crans, et le mot ; la relaxe est en vert. */
@Composable
internal fun StageLadder(code: String, modifier: Modifier = Modifier) {
    val stage = procedureStage(code) ?: return
    val on = if (stage.relaxe) PolColors.current.solide else MaterialTheme.colorScheme.onSurface
    Row(modifier.padding(top = 8.dp).clearAndSetSemantics { contentDescription = "Étape : ${stage.label}, ${stage.step} sur 5" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { s ->
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (s <= stage.step) on else MaterialTheme.colorScheme.outlineVariant))
        }
    }
    Text(stage.label, Modifier.padding(top = 4.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
}

/** `[code, libellé]` d'une étape judiciaire. */
@Composable
internal fun StageTag(stg: List<String>) {
    val label = stg.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
    StateTag(label, stageTone(stg.getOrNull(0)))
}
