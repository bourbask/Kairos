package app.kairos.mobile.ui.politique.kit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.politique.avatarOf
import app.kairos.mobile.ui.politique.IdentityBadge
import app.kairos.mobile.ui.politique.identityColor
import app.kairos.mobile.ui.politique.model.Evaluator
import app.kairos.mobile.ui.politique.model.Lecture
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.model.Poste
import app.kairos.mobile.ui.politique.model.SourceRef
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- logique pure (testée)

const val NEUTRAL_LABEL = "Non précisé"

private val VERDICT_LABELS = mapOf(
    "solide" to "Solide", "partiel" to "En partie vrai", "trompeur" to "Vrai mais trompeur",
    "fragile" to "Fragile", "faux" to "Faux", "non-documente" to "Non documenté",
)

/** Les sept verdicts de promesse (balance d'un chiffre) : libellé, famille de couleur, définition. */
enum class PromiseTone { Ok, Mid, Bad, Info, None }

private class PromiseInfo(val label: String, val tone: PromiseTone, val def: String)

private val PROMISES = linkedMapOf(
    "financable" to PromiseInfo("Finançable", PromiseTone.Ok, "Chiffrage crédible selon au moins un évaluateur."),
    "conteste" to PromiseInfo("Financement contesté", PromiseTone.Mid, "Évaluateurs en désaccord, ou recettes surestimées."),
    "irrealiste" to PromiseInfo("Irréaliste", PromiseTone.Bad, "Contredit par les ordres de grandeur."),
    "deja-tente" to PromiseInfo("Déjà tenté", PromiseTone.Info, "Résultat connu, en France ou ailleurs."),
    "nefaste" to PromiseInfo("Effets néfastes documentés", PromiseTone.Bad, "Des effets négatifs sont mesurés."),
    "non-documente" to PromiseInfo("Non documenté", PromiseTone.None, "Montants annoncés sans paramètres publics."),
    "non-evalue" to PromiseInfo("Non évalué", PromiseTone.None, "Aucune évaluation trouvée."),
)

val PROMISE_VERDICTS: List<String> = PROMISES.keys.toList()

/** Valeur inconnue : libellé neutre, jamais d'erreur. */
fun verdictLabel(v: String): String = VERDICT_LABELS[v] ?: NEUTRAL_LABEL
fun promiseLabel(v: String): String = PROMISES[v]?.label ?: NEUTRAL_LABEL
fun promiseDefinition(v: String): String? = PROMISES[v]?.def

/** Contrepartie d'un chiffre : forme courte + postes si publiée, sinon texte long (puis angle mort à défaut de contrepartie). */
class BalanceSummary(val short: String?, val postes: List<Poste>, val long: String, val angleMort: Boolean)

fun balanceSummary(l: Lecture): BalanceSummary {
    val short = l.contrepartie_courte.trim().takeIf { it.isNotEmpty() }
    val postes = if (short != null) l.postes.filter { it.quoi.isNotBlank() } else emptyList()
    val cp = l.contrepartie.trim().ifEmpty { short.orEmpty() }
    val am = l.angle_mort_court.trim().ifEmpty { l.angle_mort.trim() }
    return if (cp.isEmpty() && short == null) BalanceSummary(null, emptyList(), am, true) else BalanceSummary(short, postes, cp, false)
}

private val ZERO_AMOUNT = Regex("""^[-−]?\s*0+(?:[.,]0+)?(?![\d.,])""")
/** Un nombre, séparateurs de milliers (espaces) et décimales compris. */
private val NUMBER = Regex("""\d+(?:[\s\u00A0\u202F]\d{3})*(?:[.,]\d+)?""")

/** Fourchette en tête (« 2 à 4 », « 2-4 ») : son signe serait ambigu. */
private val RANGE = Regex("""^${NUMBER.pattern}\s*(?:à|-|–)\s*\d""")

/**
 * Montant d'une perte : signe moins devant un chiffre, précision entre parenthèses comprise ; montant nul,
 * fourchette (« 2 à 4 »), déjà signé ou rédigé (« plus de… ») laissé tel quel.
 */
fun lossAmount(m: String): String {
    val t = m.trim()
    return when {
        ZERO_AMOUNT.containsMatchIn(t) -> t.trimStart('-', '−').trimStart()
        RANGE.containsMatchIn(t) -> t
        t.startsWith("-") -> "−" + t.drop(1).trimStart()
        t.firstOrNull()?.isDigit() == true -> "−$t"
        else -> t
    }
}

/** Ligne d'un poste de balance : ce qui est retiré, qui y perd, le montant signé comme une perte. */
data class BalanceRow(val quoi: String, val qui: String?, val montant: String?)

fun balanceRows(postes: List<Poste>): List<BalanceRow> = postes.map { p ->
    BalanceRow(p.quoi.trim(), p.qui?.trim()?.takeIf { it.isNotEmpty() }, p.montant?.takeIf { it.isNotBlank() }?.let(::lossAmount))
}

private val IND_LABELS = mapOf(
    1 to "Indépendance statutaire et contrôlée", 2 to "Indépendant, orientation connue",
    3 to "Lié à un intérêt économique ou à un camp", 4 to "Partisan ou militant",
)

/** Niveau d'indépendance 1 à 4 ; 0 = non qualifié. */
fun indLevel(ev: Evaluator): Int = ev.independance?.takeIf { it in 1..4 } ?: 0
fun indLabel(level: Int): String = IND_LABELS[level] ?: "Non qualifiée"

/** Nom court : le nom s'il tient en 26 caractères, sinon le plus court alias sans chiffre. */
fun evShortName(ev: Evaluator): String =
    if (ev.nom.length <= 26) ev.nom else ev.alias.filter { a -> a.none { it.isDigit() } }.minByOrNull { it.length } ?: ev.nom

fun evA11yLabel(ev: Evaluator): String {
    val n = indLevel(ev)
    return "${ev.nom}, évaluateur. Indépendance " + (if (n > 0) "niveau $n sur 4 : ${indLabel(n)}" else "non qualifiée") + ". Ouvrir sa fiche"
}

/** Libellé d'une source : son nom, à défaut le domaine de l'adresse. */
fun sourceName(src: SourceRef): String =
    src.label.trim().ifEmpty {
        safeHttps(src.url)?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.removePrefix("www.") ?: "Source"
    }

/** Texte découpé sur la seule balise admise, `<b>` ; tout le reste reste du texte brut. */
fun splitBold(text: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var last = 0
    var bold = false
    for (m in Regex("(?i)<(/?)b>").findAll(text)) {
        if (m.range.first > last) out += text.substring(last, m.range.first) to bold
        bold = m.groupValues[1].isEmpty()
        last = m.range.last + 1
    }
    if (last < text.length) out += text.substring(last) to bold
    return out
}

/** Espaces insécables autour des guillemets français et avant `: ; ? !`. */
fun frTypo(s: String): String =
    s.replace("« ", "« ").replace(" »", " »").replace(Regex(" ([:;?!])"), " $1").replace(' ', ' ')

/** Ouvre une adresse https hors de l'application ; toute autre forme est ignorée. */
fun openUrl(context: Context, url: String?) {
    val safe = safeHttps(url) ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Aucun navigateur : rien.
    }
}

// ---------------------------------------------------------------- composants

private val Mono = FontFamily.Monospace

/**
 * Pastille ronde portant un repère court (chiffre, lettre), centré sur l'encre du glyphe : les métriques
 * de police (ascendante, descendante, marges) changent d'un appareil à l'autre et décalent un `Text`.
 * Décorative : le libellé voisin porte le sens.
 */
@Composable
fun NumberDot(text: String, bg: Color, fg: Color, size: Dp, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) } }
    val path = remember { Path() }
    val ink = remember { RectF() }
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        drawCircle(bg)
        paint.color = fg.toArgb()
        paint.textSize = fontSize.toPx()
        // Contour exact du glyphe (au sous-pixel), là où getTextBounds arrondit au pixel.
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        path.computeBounds(ink, true)
        drawContext.canvas.nativeCanvas.drawText(text, center.x - ink.centerX(), center.y - ink.centerY(), paint)
    }
}
private fun Modifier.outline(color: Color, width: Float = 1f, shape: Shape = CircleShape) = border(BorderStroke(width.dp, color), shape)

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier, color = color, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.09.em)
}

/** Titre (heading) suivi de son aide en infobulle ([info] nul : titre seul). */
@Composable
fun TitleInfo(title: String, info: String?, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false).semantics { heading() }, style = style, fontWeight = FontWeight.Bold)
        if (info != null) InfoIcon(info, "À propos : $title")
    }
}

/**
 * En-tête de section : sur-titre, titre. [info] : mode d'emploi, en infobulle. [note] : ce qui doit rester lu
 * (unité, définition, convention de signe, légende, provenance, limite de portée), une ligne discrète.
 */
@Composable
fun SectionHeader(kicker: String, title: String, info: String? = null, modifier: Modifier = Modifier, note: String? = null) {
    Column(modifier.fillMaxWidth()) {
        Kicker(kicker)
        TitleInfo(title, info, Modifier.padding(top = 4.dp).heightIn(min = 32.dp))
        if (note != null) Text(frTypo(note), Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Texte reçu : seule la balise `<b>` met en gras. */
@Composable
fun RichText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) {
    val s = buildAnnotatedString {
        splitBold(frTypo(text)).forEach { (t, b) -> if (b) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t) } else append(t) }
    }
    Text(s, modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Candidat, parti ou camp : puce à sa couleur, texte lisible dessus, personnage si publié. Inconnu : rien. */
@Composable
fun IdentityChip(data: PolData, id: String, large: Boolean = false, text: String? = null, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val identity = data.byId(id) ?: return
    val bg = identityColor(identity.couleur) ?: MaterialTheme.colorScheme.primary
    val hasAvatar = remember(identity.avatar) { avatarOf(identity.avatar) != null }
    val ink = MaterialTheme.colorScheme.onSurface
    val badge = if (large) 26.dp else 20.dp
    Row(
        modifier.let { if (onClick != null) it.minimumInteractiveComponentSize() else it }.clip(CircleShape).background(bg).outline(ink.copy(alpha = 0.22f))
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .heightIn(min = if (large) 34.dp else 28.dp)
            .padding(start = if (hasAvatar) 4.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasAvatar) IdentityBadge(identity, badge)
        Text(text ?: identity.nom, color = textOn(bg), fontSize = if (large) 14.sp else 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Point de la couleur de l'identité, pour les légendes et les barres. */
@Composable
fun IdentityDot(data: PolData, id: String, modifier: Modifier = Modifier) {
    val bg = identityColor(data.byId(id)?.couleur) ?: MaterialTheme.colorScheme.outline
    Box(modifier.size(10.dp).clip(CircleShape).background(bg).outline(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)).clearAndSetSemantics { })
}

/** Étiquette d'indépendance : pastille du niveau (1 à 4, « ? » sinon) et [text] ; les couleurs ne dépendent que du niveau. */
@Composable
private fun IndTag(n: Int, text: String, modifier: Modifier, shape: Shape) {
    val pc = PolColors.current
    val scheme = MaterialTheme.colorScheme
    // fond, texte, fond de la pastille, texte de la pastille, contour
    val look = when (n) {
        1 -> listOf(pc.ind1, pc.ind1Ink, pc.ind1Ink, pc.ind1, null)
        2 -> listOf(pc.ind2, pc.ind2Ink, pc.ind2Ink, pc.ind2, null)
        3 -> listOf(pc.ind3, pc.ind3Ink, pc.ind3Ink, pc.ind3, null)
        4 -> listOf(Color.Transparent, scheme.onSurface, scheme.onSurface, scheme.background, scheme.onSurface)
        else -> listOf(scheme.surface, scheme.onSurfaceVariant, scheme.onSurfaceVariant, scheme.surface, scheme.outlineVariant)
    }
    Row(
        modifier.clip(shape).background(look[0]!!).let { m -> look[4]?.let { m.outline(it, if (n == 4) 1.5f else 1f, shape) } ?: m }
            .heightIn(min = 28.dp).padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NumberDot(if (n > 0) n.toString() else "?", look[2]!!, look[3]!!, 17.dp, 10.sp)
        Text(text, color = look[1]!!, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Étiquette d'évaluateur : pastille du niveau d'indépendance (1 à 4) et nom court ; ouvre sa fiche. */
@Composable
fun EvaluatorTag(ev: Evaluator, onOpen: (String) -> Unit, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) =
    IndTag(indLevel(ev), evShortName(ev), modifier.minimumInteractiveComponentSize().opensEvaluator(ev, onOpen), shape)

private fun Modifier.opensEvaluator(ev: Evaluator, onOpen: (String) -> Unit) =
    clickable(role = Role.Button) { onOpen(ev.id) }.semantics(mergeDescendants = true) { contentDescription = evA11yLabel(ev) }

/** Légende : l'étiquette d'un niveau, sans action. */
@Composable
fun LevelTag(n: Int, modifier: Modifier = Modifier) = IndTag(n, if (n > 0) "Niveau $n" else "Non qualifié", modifier, RoundedCornerShape(8.dp))

/**
 * Source : étiquette de l'évaluateur reconnu (id explicite, sinon alias en tête du libellé) collée au bouton.
 * Le bouton n'ouvre qu'une adresse https ; sans elle, le nom reste lisible.
 */
@Composable
fun SourceButton(src: SourceRef, data: PolData, onOpenEv: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val ev = data.evFor(src.label, src.ev)
    val url = safeHttps(src.url)
    val name = sourceName(src)
    val shape = if (ev != null) RoundedCornerShape(0.dp, 8.dp, 8.dp, 0.dp) else RoundedCornerShape(8.dp)
    // Les deux parties partagent la hauteur de la plus haute ; la cible tactile de 48 dp est réservée autour de l'ensemble.
    Row(modifier.minimumInteractiveComponentSize().height(IntrinsicSize.Min)) {
        if (ev != null) IndTag(indLevel(ev), evShortName(ev), Modifier.fillMaxHeight().opensEvaluator(ev, onOpenEv), RoundedCornerShape(8.dp, 0.dp, 0.dp, 8.dp))
        Row(
            Modifier.fillMaxHeight().clip(shape).background(scheme.surface).outline(scheme.outlineVariant, 1f, shape)
                .let { if (url != null) it.clickable(role = Role.Button) { openUrl(context, url) } else it }
                .semantics { if (url != null) contentDescription = "Voir la source : $name (s'ouvre dans le navigateur)" }
                .heightIn(min = 28.dp).padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = scheme.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceList(srcs: List<SourceRef>, data: PolData, onOpenEv: (String) -> Unit, modifier: Modifier = Modifier) {
    if (srcs.isEmpty()) return
    FlowRow(modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        srcs.forEach { SourceButton(it, data, onOpenEv) }
    }
}

// Verdicts de phrase : toujours le mot, la couleur l'appuie.
private class VerdictLook(val fg: Color, val bg: Color, val ring: Color?)

@Composable
private fun verdictLook(v: String): VerdictLook {
    val pc = PolColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when (v) {
        "solide" -> VerdictLook(pc.solide, pc.solideBg, null)
        "partiel" -> VerdictLook(pc.partiel, pc.partielBg, null)
        "trompeur" -> VerdictLook(pc.trompeur, pc.trompeurBg, null)
        "fragile" -> VerdictLook(pc.fragile, pc.fragileBg, null)
        "faux" -> VerdictLook(pc.faux, pc.fragileBg, pc.faux)
        else -> VerdictLook(muted, Color.Transparent, MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ChipBox(text: String, fg: Color, bg: Color, ring: Color?, modifier: Modifier, large: Boolean, dot: Boolean = false, hollowDot: Boolean = false) {
    Row(
        modifier.clip(CircleShape).background(bg).let { m -> ring?.let { m.outline(it, 1.5f) } ?: m }
            .padding(horizontal = if (large) 14.dp else 9.dp, vertical = if (large) 9.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).let { if (hollowDot) it.outline(fg, 1.5f) else it.background(fg) })
        Text(text.uppercase(), color = fg, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = if (large) 15.sp else 11.sp, letterSpacing = 0.06.em, maxLines = 1)
    }
}

@Composable
fun VerdictChip(v: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val l = verdictLook(v)
    ChipBox(verdictLabel(v), l.fg, l.bg, l.ring, modifier, large)
}

/** Trois segments : plus ils sont pleins, plus la phrase tient ; le mot reste lu à côté. */
@Composable
fun VerdictMeter(v: String, modifier: Modifier = Modifier) {
    val l = verdictLook(v)
    val line = MaterialTheme.colorScheme.outlineVariant
    val filled = when (v) { "solide" -> 3; "partiel", "trompeur" -> 2; "fragile", "faux" -> 1; else -> 0 }
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val on = i < filled
            val seg = Modifier.size(26.dp, 8.dp).clip(CircleShape)
            Box(
                when {
                    v == "non-documente" || v !in VERDICT_LABELS -> seg.outline(l.fg)
                    v == "trompeur" && on -> seg.background(l.fg.copy(alpha = 0.45f)).outline(l.fg)
                    on -> seg.background(l.fg)
                    else -> seg.background(line)
                },
            )
        }
    }
}

@Composable
fun PromiseChip(v: String, modifier: Modifier = Modifier) {
    val pc = PolColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (PROMISES[v]?.tone) {
        PromiseTone.Ok -> ChipBox(promiseLabel(v), pc.solide, pc.solideBg, null, modifier, false, dot = true)
        PromiseTone.Mid -> ChipBox(promiseLabel(v), pc.partiel, pc.partielBg, null, modifier, false, dot = true)
        PromiseTone.Bad -> ChipBox(promiseLabel(v), pc.fragile, pc.fragileBg, null, modifier, false, dot = true)
        PromiseTone.Info -> ChipBox(promiseLabel(v), pc.pour, pc.pourBg, null, modifier, false, dot = true)
        PromiseTone.None -> ChipBox(promiseLabel(v), muted, Color.Transparent, MaterialTheme.colorScheme.outlineVariant, modifier, false, dot = true, hollowDot = true)
        null -> Unit
    }
}

@Composable
private fun PostesList(postes: List<Poste>) {
    val loss = PolColors.current.fragile
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 4.dp)) {
        balanceRows(postes).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(frTypo(r.quoi), fontSize = 13.5.sp, lineHeight = 18.sp)
                    if (r.qui != null) Text("Y perd : ${r.qui}", fontSize = 12.sp, color = muted, lineHeight = 16.sp)
                }
                if (r.montant != null) Text(r.montant, Modifier.widthIn(max = 150.dp), color = loss, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}

/** Ce que montre une balance, dit une fois par section (infobulle). */
const val BALANCE_INFO = "Pour chaque chiffre : ce qu'il coûte et qui paie, ou à défaut ce qu'il laisse dehors. Touchez la carte pour le détail et l'avis signé des évaluateurs."

/** En-tête de section des balances : dit une seule fois ce qu'elles montrent ; [info] précise en infobulle. */
@Composable
fun BalanceHeader(title: String, info: String = BALANCE_INFO, modifier: Modifier = Modifier) =
    TitleInfo(title, info, modifier, MaterialTheme.typography.titleSmall)

/** Rend tout un bloc (la carte qui porte une balance) ouvrable vers le détail [BalanceSheet]. */
fun Modifier.opensBalance(title: String, onOpen: () -> Unit) =
    clickable(onClickLabel = "Voir toute la balance : $title", role = Role.Button, onClick = onOpen)

/**
 * Balance d'un chiffre, à plat dans sa carte : verdict, phrase courte et postes (sinon texte long coupé
 * à cinq lignes). Le libellé de ce que montre la balance vient une fois, en tête de section ([BalanceHeader]).
 * Le détail s'ouvre en touchant la carte : [opensBalance] sur la carte parente ; [onOpen] rend ce bloc
 * seul touchable, faute de carte (feuille, liste).
 */
@Composable
fun BalanceBlock(lecture: Lecture, title: String, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val s = balanceSummary(lecture)
    Column(modifier.fillMaxWidth().let { if (onOpen != null) it.opensBalance(title, onOpen) else it }.padding(top = 10.dp)) {
        if (lecture.verdict in PROMISES) PromiseChip(lecture.verdict, Modifier.padding(bottom = 6.dp))
        // Sans contrepartie publiée, le texte est l'angle mort : le dire, pour ne pas le lire comme un sacrifice.
        if (s.angleMort && s.long.isNotEmpty()) Kicker("Ce que le chiffre laisse dehors", Modifier.padding(bottom = 2.dp))
        if (s.short != null) {
            Text(frTypo(s.short), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            if (s.postes.isNotEmpty()) PostesList(s.postes)
        } else if (s.long.isNotEmpty()) {
            Text(frTypo(s.long), maxLines = 5, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun Labeled(label: String, text: String) {
    if (text.isBlank()) return
    Column(Modifier.padding(top = 14.dp)) {
        Kicker(label)
        Text(frTypo(text), Modifier.padding(top = 3.dp), fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Feuille complète d'une balance : verdict et sa définition, contrepartie entière, mesure, angle mort, ordre de grandeur, avis signés. */
@Composable
fun BalanceSheet(lecture: Lecture, title: String, who: String?, data: PolData, onOpenEv: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    PolSheet(full = true, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScrollPad()) {
            Kicker("La balance")
            Text(title, Modifier.padding(top = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!who.isNullOrBlank()) Text(who, Modifier.padding(top = 2.dp), color = scheme.onSurfaceVariant, fontSize = 14.sp)
            if (lecture.verdict in PROMISES) {
                PromiseChip(lecture.verdict, Modifier.padding(top = 12.dp))
                Text(promiseDefinition(lecture.verdict).orEmpty(), Modifier.padding(top = 6.dp), color = scheme.onSurfaceVariant, fontSize = 13.sp)
            }
            val s = balanceSummary(lecture)
            if (!s.angleMort) Labeled("Ce que ça coûte, qui paie", s.long.ifEmpty { s.short.orEmpty() })
            if (s.short != null && s.postes.isNotEmpty()) PostesList(s.postes)
            Labeled("Ce que le chiffre mesure", lecture.mesure)
            Labeled("Ce qu'il laisse dehors", lecture.angle_mort.ifBlank { lecture.angle_mort_court })
            Labeled("Ordre de grandeur", lecture.ordre_grandeur)
            if (lecture.avis.isNotEmpty()) {
                Kicker("Avis signés", Modifier.padding(top = 18.dp))
                lecture.avis.forEach { a ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        val ev = data.evFor(a.ev, a.ev.takeIf { data.evaluators.containsKey(it) })
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ev != null) EvaluatorTag(ev, onOpenEv) else Text(a.ev, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (safeHttps(a.url) != null) TextButton({ openUrl(context, a.url) }) { Text("Source") }
                        }
                        Text(frTypo(a.dit), fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

/** Bouton icône « plein écran » : ouvre la carte en mode « À table » ; son libellé en infobulle à l'appui long. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Afficher en grand, à table") {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick) { Icon(KairosIcons.Fullscreen, contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Petite icône « i » : [text] en infobulle, au toucher comme à l'appui long ; cible de 48 dp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoIcon(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(frTypo(text)) } },
        state = state,
        modifier = modifier,
    ) {
        IconButton({ scope.launch { state.show() } }) {
            Icon(KairosIcons.Info, contentDescription, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Modifier.verticalScrollPad() = this.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).outline(scheme.outlineVariant, 1.5f, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(frTypo(body), Modifier.padding(top = 6.dp), color = scheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
        if (action != null) FilledTonalButton(action.second, Modifier.padding(top = 14.dp).heightIn(min = 48.dp)) { Text(action.first) }
    }
}

/** [dot] : couleur d'identité affichée en pastille devant le libellé. */
data class PillOption(val key: String, val label: String, val count: Int? = null, val dot: Color? = null)

/** Rangée de puces de choix : défile, une seule sélectionnée. */
@Composable
fun Pills(options: List<PillOption>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o.key == selected
            Row(
                Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp).clip(CircleShape).background(if (on) scheme.onSurface else scheme.surface)
                    .let { if (on) it else it.outline(scheme.outlineVariant) }
                    .clickable(role = Role.Button) { onSelect(o.key) }.semantics { this.selected = on }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val fg = if (on) scheme.background else scheme.onSurface
                if (o.dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(o.dot).outline(fg.copy(alpha = 0.4f)))
                Text(o.label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                if (o.count != null) Text(o.count.toString(), color = fg.copy(alpha = 0.7f), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            }
        }
    }
}

/** Onglets segmentés : 2 à 4 choix, tous visibles. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(CircleShape).background(scheme.surface).outline(scheme.outlineVariant).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).minimumInteractiveComponentSize().heightIn(min = 40.dp).clip(CircleShape).background(if (on) scheme.onSurface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) scheme.background else scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1) }
        }
    }
}
