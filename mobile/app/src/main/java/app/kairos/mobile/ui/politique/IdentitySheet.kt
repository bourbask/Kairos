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
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.RichText
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.Claim
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

// Lignes des fichiers lues par la fiche identité : tout est facultatif, une ligne mal formée est ignorée.

@Serializable
data class IdProgram(val who: String = "", val ref: Boolean = false, val debt: Double? = null, val deficit: Double? = null, val net: Double? = null, val hh: Double? = null, val lecture: Lecture? = null)

@Serializable
data class IdGap(val who: String = "", val what: String = "", val claimed: Double? = null, val credited: Double? = null, val unit: String = "", val inverse: Boolean = false, val src: JsonElement? = null, val lecture: Lecture? = null)

@Serializable
data class IdMeasure(val who: String = "", val hh: Double? = null, val m: String = "", val b: String = "", val f: String = "", val lecture: Lecture? = null)

@Serializable
data class IdMatrixRow(val o: String = "", val c: Map<String, List<String>> = emptyMap())

@Serializable
data class IdVote(val t: String = "", val c: List<List<String>> = emptyList())

@Serializable
data class IdCoh(val camp: String = "", val items: List<List<String>> = emptyList())

@Serializable
data class IdTurn(val camp: String = "", val p: String = "", val s: String = "", val a: String = "", val b: String = "", val k: String = "", val src: JsonElement? = null)

@Serializable
data class IdRhet(val t: String = "", val ex: List<List<String>> = emptyList())

@Serializable
data class IdCommune(val ville: String = "", val qui: String = "", val statut: String = "")

/** Ligne-tableau des données (`["libellé", 1.5, null…]`) : lecture par position, tolérante. */
class Row(private val a: List<JsonElement>) {
    fun s(i: Int): String = (a.getOrNull(i) as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun d(i: Int): Double? = (a.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
}

data class Stance(val pos: String, val text: String)

/** Tout ce que les données rattachent à une identité, par correspondance d'alias. */
class IdentityLinks(
    val programs: List<IdProgram>, val gaps: List<IdGap>, val measures: List<IdMeasure>,
    val matrix: List<Pair<IdMatrixRow, Stance>>, val claims: List<Claim>, val coh: List<IdCoh>, val turns: List<IdTurn>,
    val cases: List<Affaire>, val money: List<Dossier>, val aide: List<AideRow>, val partis: List<PartiComptes>, val c22: List<Campagne>,
    val muni: List<Dossier>, val villes: List<Row>, val rhet: List<Pair<String, String>>, val communes: List<IdCommune>,
    /** Chambre (« AN », « PE ») → nom du groupe et votes de ce groupe. */
    val votes: List<Triple<String, String, List<Pair<IdVote, List<String>>>>>,
    /** Scrutins croisés : ceux du parti pour une personne. */
    val dv: List<DvItem>, val dvOf: String?, private val id: String,
) {
    val size: Int get() = programs.size + gaps.size + measures.size + matrix.size + claims.size + coh.size + turns.size + cases.size +
        money.size + aide.size + partis.size + c22.size + muni.size + villes.size + rhet.size + communes.size + votes.sumOf { it.third.size } +
        if (dvOf == id) dv.size else 0
}

fun identityLinks(data: PolData, id: String): IdentityLinks {
    fun hit(label: String?) = id in data.idsIn(label)
    fun rows(file: String, path: String) = data.decode(file, path, JsonArray.serializer()).map(::Row)
    val votes = listOf("AN" to "Assemblée nationale", "PE" to "Parlement européen").mapNotNull { (ch, name) ->
        val groups = data.decode<String>("votes", "${ch}_G")
        val g = groupIndex(data, groups, id).takeIf { it >= 0 } ?: return@mapNotNull null
        Triple(name, groups[g], data.decode<IdVote>("votes", "${ch}_V").mapNotNull { v -> v.c.getOrNull(g)?.let { v to it } })
    }
    val dvOf = if (data.kindOf(id) == IdKind.Personne) data.partyOf(id) else id
    val dv = if (dvOf == null) emptyList() else dvFor(data, dvOf)
    return IdentityLinks(
        programs = data.decode<IdProgram>("budgets", "PROGRAMS").filter { !it.ref && hit(it.who) },
        gaps = data.decode<IdGap>("budgets", "GAPS").filter { hit(it.who) },
        measures = data.decode<IdMeasure>("niveau-de-vie", "MEASURES").filter { hit(it.who) },
        matrix = data.decode<IdMatrixRow>("bilan", "MATRIX").flatMap { r ->
            r.c.filterKeys { hit(it) }.values.map { p -> r to Stance(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }) }
        },
        claims = data.decode<Claim>("affirmations", "CLAIMS").filter { hit(it.camp) },
        coh = data.decode<IdCoh>("votes", "COH").filter { hit(it.camp) },
        turns = turnsOf(data, id),
        cases = affairesOf(data).filter { a -> if (data.idsIn(a.who).isNotEmpty()) hit(a.who) else hit(a.party) },
        money = (financingOf(data) + lobbyOf(data)).filter { hit(it.t) },
        aide = aideOf(data).rows.filter { hit(it.l) },
        partis = partisOf(data).filter { hit(it.parti) },
        c22 = campagnesOf(data).filter { hit(it.cand) },
        muni = data.decode<Dossier>("villes", "MUNI").filter { hit(it.t) },
        villes = (rows("villes", "VRN") + rows("villes", "VECO")).filter { hit(it.s(1)) },
        rhet = data.decode<IdRhet>("rhetorique", "RHET").flatMap { r -> r.ex.filter { hit(it.getOrNull(0)) }.map { r.t to it.getOrElse(1) { "" } } },
        communes = data.decode<IdCommune>("villes", "COMMUNES").filter { it.qui == id },
        votes = votes,
        dv = dv,
        dvOf = dvOf,
        id = id,
    )
}

/** Ligne propre à l'identité quand plusieurs la citent (bloc commun) : celle qui porte son nom ou son parti. */
internal fun <T> List<T>.own(data: PolData, id: String, label: (T) -> String): T? {
    if (size < 2) return firstOrNull()
    val x = data.byId(id) ?: return firstOrNull()
    return firstOrNull { label(it) == x.parti || label(it) == x.nom } ?: first()
}

fun ownProgram(l: IdentityLinks, data: PolData, id: String) = l.programs.own(data, id) { it.who }
fun ownAide(l: IdentityLinks, data: PolData, id: String) = l.aide.own(data, id) { it.l }
fun ownPartis(l: IdentityLinks, data: PolData, id: String) = l.partis.own(data, id) { it.parti }

// ---------------------------------------------------------------- chiffres clés

data class Stat(val label: String, val value: String, val sub: String)

private const val MINUS = "−"

fun fmt(v: Double, d: Int = 1): String = String.format(Locale.FRENCH, "%.${d}f", v).replace('-', '−')

/** Signe explicite ; [d] null : entier groupé. */
fun signed(v: Double, d: Int? = null): String {
    val sign = if (v > 0) "+" else if (v < 0) MINUS else ""
    val abs = kotlin.math.abs(v)
    return sign + if (d == null) String.format(Locale.FRENCH, "%,d", Math.round(abs)) else fmt(abs, d)
}

private val FRAGILE = setOf("fragile", "faux", "non-documente")

/** Jusqu'à quatre chiffres clés, chacun seulement si la donnée existe. */
fun identityStats(data: PolData, id: String, l: IdentityLinks = identityLinks(data, id)): List<Stat> {
    val x = data.byId(id) ?: return emptyList()
    val out = mutableListOf<Stat>()
    ownProgram(l, data, id)?.let { p ->
        val ref = data.file("budgets")?.get("REF")?.let { (it as? JsonPrimitive)?.doubleOrNull }
        p.debt?.let { out += Stat("Dette en 2032", fmt(it) + " %", if (ref == null) "part du PIB" else if (it < ref) "sous la référence" else "au-dessus de la référence") }
        p.hh?.let { out += Stat("Par ménage", signed(it) + " €", "par an, en moyenne") }
    }
    ownAide(l, data, id)?.let { a ->
        a.v?.let { out += Stat("Aide publique", moneyLabel(it), if (a.l == x.parti || a.l == x.nom) "aide annuelle" else "bloc « ${a.l.substringBefore(" (")} »") }
    }
    ownPartis(l, data, id)?.net?.let { out += Stat("Situation nette", signed(it, 1) + " M€", "derniers comptes publiés") }
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
private val VOTE_WORD = mapOf("P" to "Pour", "C" to "Contre", "A" to "Abstention", "S" to "Divisé", "N" to "Pas de vote")
private val COH_LABEL = mapOf("ecart" to "Écart", "coherent" to "Cohérent", "explique" to "Écart expliqué")
private val TURN_LABEL = mapOf("R" to "Revirement", "I" to "Inflexion", "C" to "Constance")
private val STANCE_LABEL = mapOf("keep" to "Poursuit", "flip" to "Inverse", "mix" to "Modifie")
private val DV_LABEL = mapOf("coherent" to "Cohérent", "ambigu" to "Ambigu", "ecart" to "Écart")
private val COMMUNE_STATUS = mapOf("gagnee" to "Gagnée en 2026", "conservee" to "Conservée", "ralliee" to "Maire rallié", "perdue" to "Perdue", "echec" to "Échec")

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
    Column(Modifier.padding(top = 6.dp)) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Text(v, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) = RichText(text, modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Title(text: String) = Text(frTypo(text), Modifier.padding(top = 6.dp), fontWeight = FontWeight.Bold, fontSize = 15.5.sp)

private fun Double?.or(d: Int = 1, unit: String): String = this?.let { fmt(it, d) + unit } ?: "non trouvé"

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
                Kicker("Budget 2032")
                KeyValue(
                    listOf(
                        "Dette publique" to it.debt.or(unit = " %"), "Déficit public" to it.deficit.or(unit = " %"),
                        "Effet net par an" to (it.net?.let { v -> signed(v, 1) + " Md€" } ?: "non trouvé"),
                        "Par ménage et par an" to (it.hh?.let { v -> signed(v) + " €" } ?: "non trouvé"),
                    ),
                )
                balance(it.lecture, "${it.who} : programme 2032", it.who)?.invoke()
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
                SourceList(sourcesOf(g.src), data, onEv)
            }
        }
    }
    if (l.matrix.isNotEmpty()) out += Section("Bilan sortant") {
        l.matrix.forEach { (r, st) ->
            Card {
                Row { Tag(STANCE_LABEL[st.pos] ?: "Position") }
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
                    Box(Modifier.width(92.dp)) { StateTag(VOTE_WORD[k] ?: "—", if (k == "P") "pour" else if (k == "C") "contre" else null) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { StateTag(DV_LABEL[d.verdict.v].orEmpty(), if (d.verdict.v == "ecart") "fragile" else "partiel") }
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
                Row { StateTag(TURN_LABEL[r.k] ?: "Position", when (r.k) { "R" -> "fragile"; "I" -> "partiel"; "C" -> "solide"; else -> null }) }
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
                Text(COMMUNE_STATUS[c.statut].orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        l.muni.forEach { mu -> Card { StageTag(mu.stg); Body(mu.p, Modifier.padding(top = 6.dp)) } }
        l.villes.forEach { r ->
            Card {
                Row { Tag(factKind(r.s(3)).first) }
                Title("${r.s(0)} · ${r.s(1)}")
                Body(r.s(2))
                if (r.s(4).isNotBlank() || r.s(5).isNotBlank()) SourceList(listOf(SourceRef(r.s(4), r.s(5))), data, onEv)
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
