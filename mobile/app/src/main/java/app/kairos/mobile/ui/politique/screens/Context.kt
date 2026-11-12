package app.kairos.mobile.ui.politique.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.StageLadder
import app.kairos.mobile.ui.politique.StageTag
import app.kairos.mobile.ui.politique.StateTag
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.kit.Bar
import app.kairos.mobile.ui.politique.kit.Divider
import app.kairos.mobile.ui.politique.kit.KeyRow
import app.kairos.mobile.ui.politique.kit.LegendDot
import app.kairos.mobile.ui.politique.kit.Muted
import app.kairos.mobile.ui.politique.kit.Note
import app.kairos.mobile.ui.politique.kit.Tags
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.IdentityChip
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.InfoIcon
import app.kairos.mobile.ui.politique.kit.SourceList
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.alphabetical
import app.kairos.mobile.ui.politique.model.SourceRef
import app.kairos.mobile.ui.politique.model.sourcesOf
import java.time.LocalDate

private val Mono = FontFamily.Monospace

@Composable
private fun Empty(title: String, body: String) = EmptyState(title, body)

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) = Text(frTypo(text), modifier, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp)

/** Dossier sourcé (lobbying, financement) : titre, étape, texte, sources. */
@Composable
private fun DossierCard(data: PolData, d: Dossier, onTable: () -> Unit, nav: PolNavigator) {
    val id = data.idOf(d.t)
    FigCard {
        Row(verticalAlignment = Alignment.Top) {
            CardTitle(d.t, Modifier.weight(1f))
            TableLink(onTable)
        }
        if (id != null || d.stg.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (id != null) IdentityChip(data, id)
                StageTag(d.stg)
            }
        }
        Note(d.p)
        SourceList(sourcesOf(d.s), data, { nav.sheet(app.kairos.mobile.ui.politique.PolSheetState.Evaluator(it)) })
    }
}

// ---------- Probité ----------

@Composable
fun ProbiteScreen(data: PolData, nav: PolNavigator) {
    val agenda = remember(data) { agendaOf(data, LocalDate.now()) }
    val affaires = remember(data) { affairesOf(data).alphabetical { it.who } }
    val lobby = remember(data) { lobbyOf(data) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Présomption d'innocence : une enquête ne vaut pas culpabilité.", Modifier.weight(1f, fill = false), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoIcon("Une enquête ou une mise en examen ne vaut pas culpabilité. Les étapes ne se valent pas : comparez-les, pas les noms.", "À propos de la présomption d'innocence")
            }
        }
        part {
            Section("Agenda", "Décisions attendues avant l'élection", null) {
                if (agenda.isEmpty()) Empty("Aucune échéance publiée", "Les prochaines décisions judiciaires attendues apparaîtront ici dès qu'elles seront datées et sourcées.")
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { agenda.forEach { AgendaRow(it) } }
            }
        }
        part {
            Section("Procédures", "Où en est chaque affaire", "La barre suit la procédure : enquête, mise en examen, procès, jugement, décision définitive.") {
                if (affaires.isEmpty()) Empty("Aucune procédure suivie", "Aucune affaire n'est publiée pour l'instant. Une section vide ne vaut pas absence d'affaires : seules celles qui sont établies et sourcées figurent ici.")
                else {
                    val cards = remember(affaires) { affaires.map(::affaireTableCard) }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        affaires.forEachIndexed { i, a -> AffaireCard(data, a, onTable = { nav.table(cards, i) }, nav) }
                    }
                }
            }
        }
        part {
            Section("Lobbying", "Des lois sous influence : cas documentés", "Établis par des enquêtes parlementaires ou journalistiques, avec la réponse des personnes mises en cause.") {
                if (lobby.isEmpty()) Empty("Aucun cas documenté", "Aucun cas de lobbying n'est publié pour l'instant.")
                else {
                    val cards = remember(lobby) { lobby.map { dossierTableCard("Lobbying", it) } }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { lobby.forEachIndexed { i, d -> DossierCard(data, d, { nav.table(cards, i) }, nav) } }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(a: AgendaItem) {
    val pc = PolColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 4.dp).size(14.dp).clip(CircleShape).background(if (a.key) pc.partiel else MaterialTheme.colorScheme.onSurface).clearAndSetSemantics { })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a.date, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                a.inDays?.let { Muted(agendaWhen(it)) }
            }
            Text(frTypo(a.text), Modifier.padding(top = 2.dp), fontSize = 14.5.sp)
        }
    }
}

@Composable
private fun AffaireCard(data: PolData, a: Affaire, onTable: () -> Unit, nav: PolNavigator) {
    val whoId = data.idOf(a.who)
    val partyId = data.idOf(a.party)
    FigCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val identity = whoId?.let { data.byId(it) }
            if (identity != null) IdentityBadge(identity, 36.dp)
            else Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clearAndSetSemantics { }, contentAlignment = Alignment.Center) { Text(initials(a.who), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Column(Modifier.weight(1f)) {
                if (a.who.isNotBlank()) Text(a.who, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (partyId != null) IdentityChip(data, partyId, modifier = Modifier.padding(top = 2.dp)) else if (a.party.isNotBlank()) Muted(a.party)
            }
            TableLink(onTable)
        }
        Text(frTypo(a.factsText), Modifier.padding(top = 12.dp), fontSize = 14.5.sp)
        if (a.stage != null) {
            StageLadder(a.stg)
            if (a.date.isNotBlank()) Kicker(a.date, Modifier.padding(top = 2.dp))
        } else if (a.date.isNotBlank()) Kicker(a.date, Modifier.padding(top = 6.dp))
        Note(a.etat)
        if (a.url.isNotBlank()) SourceList(listOf(SourceRef("Source", a.url)), data, { nav.sheet(app.kairos.mobile.ui.politique.PolSheetState.Evaluator(it)) })
    }
}

// ---------- Argent ----------

@Composable
fun ArgentScreen(data: PolData, nav: PolNavigator) {
    val aide = remember(data) { aideOf(data) }
    val partis = remember(data) { partisOf(data).alphabetical { it.parti } }
    val camps = remember(data) { campagnesOf(data).alphabetical { it.cand } }
    val fin = remember(data) { financingOf(data) }
    val pc = PolColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part { Text("Les partis vivent surtout d'argent public, calculé sur leurs résultats, puis de dons plafonnés et d'emprunts.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        part {
            Section("Aide publique", "Combien l'État verse à chaque parti", "En millions d'euros, selon les voix aux dernières législatives et le nombre de parlementaires.") {
                if (aide.rows.isEmpty()) Empty("Aide publique pas encore publiée", "Les montants versés à chaque parti apparaîtront ici dès qu'ils seront sourcés.")
                else FigCard {
                    aide.rows.alphabetical { shortLabel(it.l) }.forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        val id = data.idOf(r.l)
                        val label = moneyLabel(r.v)
                        Column(Modifier.padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "${shortLabel(r.l)} : $label" }) {
                            Text(shortLabel(r.l), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            Bar(barFraction(r.v, aide.max), identityColor(id?.let { data.byId(it)?.couleur }) ?: MaterialTheme.colorScheme.primary, label)
                        }
                    }
                }
            }
        }
        part {
            Section("Derniers comptes", "Dons, dettes et santé financière", "Ce que les partis tirent de leurs adhérents et donateurs, et ce qu'ils doivent.") {
                if (partis.isEmpty()) Empty("Comptes pas encore publiés", "Les comptes des partis (dons, dette, situation nette) apparaîtront ici dès qu'ils seront publiés.")
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    partis.forEach { p ->
                        val id = data.idOf(p.parti)
                        FigCard {
                            if (id != null) IdentityChip(data, id) else Text(p.parti, fontWeight = FontWeight.Bold)
                            Column(Modifier.padding(top = 8.dp)) {
                                KeyRow("Dons et cotisations", moneyLabel(p.dons), null)
                                KeyRow("Dette", moneyLabel(p.dette), null)
                                KeyRow("Situation nette", netLabel(p.net), p.net?.let { if (it >= 0) pc.solide else pc.fragile })
                            }
                        }
                    }
                }
            }
        }
        part {
            Section("Présidentielle précédente", "Ce qu'a coûté chaque campagne", "Sous 5 % des voix, l'État ne rembourse presque rien.") {
                if (camps.isEmpty()) Empty("Campagnes pas encore publiées", "Les dépenses et remboursements des campagnes présidentielles apparaîtront ici dès qu'ils seront sourcés.")
                else FigCard {
                    val max = campagnesMax(camps)
                    camps.forEachIndexed { i, c ->
                        if (i > 0) Divider()
                        val id = data.idOf(c.cand)
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (id != null) IdentityChip(data, id, modifier = Modifier.weight(1f, fill = false)) else Text(c.cand, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                if (c.score.isNotBlank()) Text("${c.score} des voix", fontFamily = Mono, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Muted("Dépensé", Modifier.padding(top = 6.dp))
                            Bar(barFraction(c.depense, max), MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), moneyLabel(c.depense))
                            Muted("Remboursé", Modifier.padding(top = 4.dp))
                            Bar(barFraction(c.rembourse, max), pc.low, moneyLabel(c.rembourse))
                            Note(c.note)
                        }
                    }
                }
            }
        }
        part {
            Section("Prêts et financements", "Ceux qui ont fait débat", null) {
                if (fin.isEmpty()) Empty("Aucun dossier publié", "Aucun prêt ni financement controversé n'est publié pour l'instant.")
                else {
                    val cards = remember(fin) { fin.map { dossierTableCard("Financement", it) } }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fin.forEachIndexed { i, d -> DossierCard(data, d, { nav.table(cards, i) }, nav) } }
                }
            }
        }
    }
}

// ---------- Médias ----------

@Composable
fun MediasScreen(data: PolData, nav: PolNavigator) {
    val st = remember(data) { mediasState(data) }
    val pc = PolColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPad) {
        part {
            Section("Propriété", "Qui possède quoi", "Une large part des grands médias appartient à quelques fortunes.") {
                if (st.owners.isEmpty()) Empty("Propriétaires pas encore publiés", "La carte de la propriété des médias apparaîtra ici dès qu'elle sera sourcée.")
                else Rail(st.owners, 280.dp) { _, x ->
                    FigCard(Modifier.fillMaxSize()) {
                        CardTitle(x.o)
                        if (x.g.isNotBlank()) Muted(x.g, Modifier.padding(top = 2.dp))
                        if (x.m.isNotEmpty()) Tags(x.m)
                        Note(x.n)
                    }
                }
            }
        }
        part {
            Section("Propriété", "Ceux qui ne peuvent pas être vendus", null) {
                if (st.unsellable.isEmpty()) Empty("Aucun titre protégé publié", "Les titres dont le capital est protégé de la vente apparaîtront ici dès qu'ils seront sourcés.")
                else Rail(st.unsellable, 260.dp) { _, x ->
                    FigCard(Modifier.fillMaxSize()) {
                        StateTag("Protégé de la vente", "solide")
                        CardTitle(x.o, Modifier.padding(top = 8.dp))
                        Note(x.n)
                    }
                }
            }
        }
        part {
            Section("Audience", "Les chaînes d'info les plus regardées", "Part d'audience nationale, moyenne précédente puis dernière mesure.") {
                if (st.audience.rows.isEmpty()) Empty("Audiences pas encore publiées", "Les parts d'audience des chaînes d'information apparaîtront ici dès qu'elles seront sourcées.")
                else FigCard {
                    st.audience.rows.forEachIndexed { i, r ->
                        if (i > 0) Divider()
                        val a = r.before?.let { fmtNum(it, 1) + " %" } ?: NO_VALUE
                        val b = r.now?.let { fmtNum(it, 1) + " %" } ?: NO_VALUE
                        Column(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "${r.name} : moyenne précédente $a, dernière mesure $b" }) {
                            Text(r.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                            Bar(barFraction(r.before, st.audience.max), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), a)
                            Bar(barFraction(r.now, st.audience.max), pc.low, b)
                        }
                    }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LegendDot(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), "Moyenne précédente")
                        LegendDot(pc.low, "Dernière mesure")
                    }
                }
            }
        }
        part {
            Section("Régulateur", "Ce que le régulateur et la justice ont fait", "Décisions, sanctions et mises en demeure, dans tous les sens.") {
                if (st.reg.isEmpty()) Empty("Aucune décision publiée", "La frise des décisions du régulateur et de la justice apparaîtra ici dès qu'elle sera sourcée.")
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    st.reg.forEach { r ->
                        Column {
                            if (r.date.isNotBlank()) Text(r.date, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(frTypo(r.text), Modifier.padding(top = 2.dp), fontSize = 14.5.sp)
                            if (r.note.isNotBlank()) Muted(r.note, Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
        part {
            Section("Recherche", "L'influence des médias : ce qui est prouvé", "Les effets causaux sur le vote sont mesurés aux États-Unis. En France : le contenu et l'audience, pas encore l'effet sur le vote.") {
                if (st.studies.isEmpty()) Empty("Aucune étude publiée", "Les études sur l'influence des médias apparaîtront ici dès qu'elles seront sourcées.")
                else {
                    val cards = remember(st.studies) { st.studies.map(::studyTableCard) }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        st.studies.forEachIndexed { i, s ->
                            FigCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StateTag(s.kindLabel, when (s.kind) { "causal" -> "solide"; "cont" -> "pour"; "desc" -> "partiel"; else -> null })
                                    Spacer(Modifier.weight(1f))
                                    TableLink { nav.table(cards, i) }
                                }
                                CardTitle(s.author, Modifier.padding(top = 8.dp))
                                if (s.country.isNotBlank()) Kicker(s.country)
                                Note(s.text)
                            }
                        }
                    }
                }
            }
        }
        part {
            Section("Sondages", "Les instituts et leurs propriétaires", "Une intention de vote loin de l'élection reste une photographie, pas une prévision.") {
                if (st.polls.isEmpty()) Empty("Instituts pas encore publiés", "Les instituts de sondage et leurs propriétaires apparaîtront ici dès qu'ils seront sourcés.")
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    st.polls.forEach { p ->
                        FigCard {
                            CardTitle(p.institute)
                            if (p.owner.isNotBlank()) { Kicker("Propriétaire", Modifier.padding(top = 8.dp)); Text(frTypo(p.owner), fontSize = 14.sp) }
                            if (p.clients.isNotBlank()) { Kicker("Commanditaires récents", Modifier.padding(top = 8.dp)); Text(frTypo(p.clients), fontSize = 14.sp) }
                        }
                    }
                }
            }
        }
    }
}

