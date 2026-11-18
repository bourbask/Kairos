package app.kairos.mobile.ui.politique.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.jeux.kit.PixelGrid
import app.kairos.mobile.ui.politique.kit.EmptyState
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.PixelKey
import app.kairos.mobile.ui.politique.kit.PolColors
import app.kairos.mobile.ui.politique.kit.PolSheet
import app.kairos.mobile.ui.politique.kit.StepCut
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.quoted
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.theme.mix
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Drapeau de la touche « Signaler une erreur », grille du site. */
val FLAG_GRID = listOf("#.......", "######..", "#.....#.", "#......#", "#.....#.", "######..", "#.......", "#.......", "#.......")

private val CHECK_GRID = listOf("........##", ".......###", "......###.", "##...###..", "###.###...", ".#####....", "..###.....", "..........")

private const val LIMIT_TEXT = "Chaque signalement est relu à la main, source par source. Au-delà de 10 par heure, personne ne pourrait les vérifier dans un délai raisonnable, et la limite protège aussi le formulaire des envois automatiques."

/** Sauvegarde du formulaire : un renvoi, même après recréation de l'écran, garde sa clé `idem`. */
private val FormSaver = listSaver<ReportForm, Any>(
    save = { listOf(it.kind, it.target, it.what, it.basis, it.url, it.refWho, it.refWhen, it.refWhat, it.refIn, it.fix, it.oath, it.idem) },
    restore = {
        ReportForm(
            it[0] as String, it[1] as String, it[2] as String, it[3] as String, it[4] as String, it[5] as String,
            it[6] as String, it[7] as String, it[8] as String, it[9] as String, it[10] as Boolean, it[11] as String,
        )
    },
)

/**
 * Signalement d'une erreur sur l'affirmation [claimId] : formulaire, puis reçu, limite atteinte ou fermeture.
 * Introuvable dans les données chargées : feuille explicative.
 */
@Composable
fun ReportSheet(data: PolData, claimId: String, nav: PolNavigator, onDismiss: () -> Unit) {
    val claim = remember(data, claimId) { claimsOf(data).firstOrNull { it.id == claimId && validClaimId(it.id) } }
    if (claim == null) {
        PolSheet(full = false, onDismiss = onDismiss) { EmptyState("Phrase introuvable", "Cette phrase n'est plus dans les données chargées. Fermez la feuille et rouvrez-la depuis la liste.", Modifier.padding(16.dp)) }
        return
    }
    var form by rememberSaveable(claimId, stateSaver = FormSaver) { mutableStateOf(ReportForm()) }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var received by remember { mutableStateOf<ReportOutcome.Received?>(null) }
    var closed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val wait = nav.reportQuota.waitMinutes(System.currentTimeMillis())

    fun send() {
        if (sending || !reportReady(form, claim, data)) return
        sending = true
        problem = null
        val sent = form
        scope.launch {
            val outcome = try {
                nav.report(reportArgs(sent, claim.id)).let { ReportOutcome.Received(it.reference, it.recu_a, it.restant) }
            } catch (_: ApiError.Unauthorized) {
                // L'espace demande déjà une nouvelle connexion ; le formulaire reste tel quel.
                sending = false
                return@launch
            } catch (e: ApiError) {
                reportOutcome(e)
            }
            nav.reportQuota = nav.reportQuota.after(outcome, System.currentTimeMillis())
            when (outcome) {
                is ReportOutcome.Received -> received = outcome
                is ReportOutcome.Refused -> problem = outcome.message
                is ReportOutcome.Failed -> problem = outcome.message
                ReportOutcome.Closed -> closed = true
                // La limite passe par le quota : l'écran de limite s'affiche de lui-même.
                is ReportOutcome.Limited -> Unit
            }
            sending = false
        }
    }

    PolSheet(full = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val r = received
            Column {
                Kicker(if (r != null) "Signalement reçu" else "Signaler une erreur")
                Text(if (r != null) "Merci, c'est noté" else frTypo(quoted(claim.q)), Modifier.padding(top = 4.dp).semantics { heading() }, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
            }
            when {
                r != null -> {
                    Notice(ok = true, receiptLine(r.ref, r.at, ZoneId.systemDefault()), "Il sera relu avec ses sources.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Key("Fermer", onDismiss) }
                }
                wait != null -> Notice(ok = false, "$LIMIT_TEXT Vous pourrez en envoyer un nouveau dans $wait min.")
                closed -> Notice(ok = false, "Les signalements sont momentanément fermés.")
                else -> ReportFields(form, { form = it }, claim, data, nav.reportQuota.left, sending, problem, ::send, onDismiss)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ReportFields(
    form: ReportForm, set: (ReportForm) -> Unit, claim: app.kairos.mobile.ui.politique.model.Claim, data: PolData,
    left: Int?, sending: Boolean, problem: String?, onSend: () -> Unit, onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Group("Qu'est-ce qui pose problème ?") {
        // La clé `idem` suit le formulaire : changer de type ne la renouvelle pas.
        Choices(REPORT_KINDS, form.kind) { if (it != form.kind) set(form.copy(kind = it, target = "")) }
    }
    if (form.kind == "fait" || form.kind == "source") Group("Lequel ?") {
        Choices(reportTargets(claim, form.kind, data), form.target) { set(form.copy(target = it)) }
    }
    Group("Qu'est-ce qui est inexact ?", "Citez le passage concerné, puis dites précisément ce qui ne va pas. Pas votre avis sur le sujet : l'erreur à corriger.") {
        Field(form.what, { set(form.copy(what = it)) }, "Qu'est-ce qui est inexact ?", 400, multiline = true)
        Counter(form.what, 30, 400)
    }
    Group("Sur quoi vous appuyez-vous ?") {
        Choices(REPORT_BASES, form.basis) { set(form.copy(basis = it)) }
        when (form.basis) {
            "lien" -> Sub {
                Group("Lien vers la source", "Adresse en https. Elle n'est pas ouverte automatiquement : elle sera consultée à la relecture.", main = false) {
                    Field(form.url, { set(form.copy(url = it)) }, "Lien vers la source", 300, placeholder = "https://…", keyboard = KeyboardType.Uri)
                    if (form.url.isNotEmpty()) urlProblem(form.url)?.let { Text(it, fontSize = 13.sp, color = PolColors.current.fragile) }
                }
            }
            "ref" -> Sub {
                Group("Média ou auteur", main = false) { Field(form.refWho, { set(form.copy(refWho = it)) }, "Média ou auteur", 80) }
                Group("Date, même approximative", main = false) { Field(form.refWhen, { set(form.copy(refWhen = it)) }, "Date, même approximative", 30, placeholder = "ex. mars 2025") }
                Group("Titre ou mots-clés", "Assez pour retrouver l'article par une recherche.", main = false) { Field(form.refWhat, { set(form.copy(refWhat = it)) }, "Titre ou mots-clés", 160) }
            }
            "carte" -> Sub {
                Group("Quels éléments de la carte se contredisent ?", main = false) {
                    Field(form.refIn, { set(form.copy(refIn = it)) }, "Quels éléments de la carte se contredisent ?", 160, placeholder = "ex. le fait 2 et le verdict")
                }
            }
        }
    }
    Group("Que faudrait-il écrire à la place, d'après vous ?", "Votre proposition sera relue et vérifiée ; elle ne remplace pas le texte automatiquement.") {
        Field(form.fix, { set(form.copy(fix = it)) }, "Que faudrait-il écrire à la place, d'après vous ?", 300, multiline = true)
        Counter(form.fix, 10, 300)
    }
    Oath(form.oath) { set(form.copy(oath = it)) }
    Text(anonymityNote(left), fontSize = 12.5.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Key("Annuler", onCancel)
            val active = !sending && reportReady(form, claim, data)
            val label = if (sending) "Envoi…" else "Envoyer le signalement"
            PixelKey(onSend, label, Modifier.alpha(if (active) 1f else .45f), enabled = active, fg = scheme.surface, bg = scheme.primary, hoverBg = scheme.primary) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.surface)
            }
        }
        if (problem != null) Text(problem, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, fontSize = 13.sp, color = PolColors.current.fragile)
    }
}

/** Intitulé (et son aide), puis le contenu ; [main] : question du formulaire, sinon sous-champ. */
@Composable
private fun Group(label: String, hint: String? = null, main: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(label, fontWeight = FontWeight.Bold, fontSize = if (main) 15.sp else 14.sp)
            if (hint != null) Text(hint, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

/** Sous-champs d'une base, en retrait derrière un filet. */
@Composable
private fun Sub(content: @Composable ColumnScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.fillMaxWidth().drawBehind { drawRect(line, size = Size(3.dp.toPx(), size.height)) }.padding(start = 15.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

/** Choix exclusif en touches : la choisie en encre pleine, enfoncée. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            PixelKey(
                { onSelect(id) }, label, selected = on,
                fg = if (on) scheme.surface else scheme.onSurface,
                bg = if (on) scheme.onSurface else mix(scheme.surface, scheme.onSurface, .08f),
                hoverBg = if (on) scheme.onSurface else mix(scheme.surface, scheme.onSurface, .15f),
            ) {
                Text(label, fontSize = 14.sp, color = if (on) scheme.surface else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Champ creusé : coins en marche, ombre intérieure en haut. [max] borne la saisie brute (points de code). */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, max: Int, multiline: Boolean = false, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text) {
    val scheme = MaterialTheme.colorScheme
    val shade = mix(scheme.surface, scheme.onSurface, .18f)
    BasicTextField(
        value,
        { if (it.codePointCount(0, it.length) <= max) onChange(it) },
        Modifier.fillMaxWidth().heightIn(min = if (multiline) 84.dp else 0.dp).clip(StepCut)
            .background(mix(scheme.surface, scheme.onSurface, .05f))
            .drawBehind { drawRect(shade, size = Size(size.width, 2.dp.toPx())) }
            .semantics { contentDescription = label },
        textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp, lineHeight = 21.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = keyboard != KeyboardType.Uri),
        singleLine = !multiline,
        cursorBrush = SolidColor(scheme.onSurface),
        decorationBox = { inner ->
            Box(Modifier.padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 9.dp)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 15.sp, color = scheme.onSurfaceVariant)
                inner()
            }
        },
    )
}

@Composable
private fun ColumnScope.Counter(text: String, min: Int, max: Int) {
    val n = reportLength(text)
    Text(
        counterText(n, min, max), Modifier.align(Alignment.End), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
        color = if (n in 1 until min) PolColors.current.fragile else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Case pixel de 18 dp et phrase d'engagement ; toute la ligne coche. */
@Composable
private fun Oath(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rim = mix(scheme.surface, scheme.onSurface, .22f)
    val under = mix(scheme.surface, scheme.onSurface, .14f)
    val sunk = mix(scheme.onSurface, scheme.surface, .30f)
    Row(
        Modifier.fillMaxWidth().clip(StepCut).background(mix(scheme.surface, scheme.onSurface, .06f))
            .drawBehind { bottomBand(under, 2) }
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(18.dp).clip(StepCut).background(if (checked) scheme.onSurface else mix(scheme.surface, scheme.onSurface, .05f))
                .drawBehind {
                    if (checked) bottomBand(sunk, 2)
                    else {
                        val t = 1.dp.toPx()
                        drawRect(rim, size = Size(size.width, 2.dp.toPx()))
                        drawRect(rim, Offset(0f, size.height - t), Size(size.width, t))
                        drawRect(rim, size = Size(t, size.height))
                        drawRect(rim, Offset(size.width - t, 0f), Size(t, size.height))
                    }
                },
            contentAlignment = Alignment.Center,
        ) { if (checked) PixelGrid(CHECK_GRID, scheme.surface, Modifier.size(10.dp, 8.dp)) }
        Text("Je signale une erreur vérifiable, pas un désaccord d'opinion.", Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp)
    }
}

private fun DrawScope.bottomBand(c: Color, dp: Int) {
    val h = dp.dp.toPx()
    drawRect(c, Offset(0f, size.height - h), Size(size.width, h))
}

/** Encadré d'état : reçu ([ok]) ou limite et fermeture ; annoncé à son apparition. */
@Composable
private fun Notice(ok: Boolean, vararg lines: String) {
    val pc = PolColors.current
    val accent = if (ok) pc.solide else pc.partiel
    Column(
        Modifier.fillMaxWidth().clip(StepCut).background(if (ok) pc.solideBg else pc.partielBg)
            .drawBehind { bottomBand(accent.copy(alpha = .35f), 2) }
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { lines.forEach { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface) } }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) =
    PixelKey(onClick, label) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
