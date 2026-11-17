package app.kairos.mobile.ui.politique.jeux

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.LocalPlayMoment
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.mascot.SceneKind
import app.kairos.mobile.ui.politique.PolNavigator
import app.kairos.mobile.ui.politique.PolSheetState
import app.kairos.mobile.ui.politique.jeux.kit.BoutonPixel
import app.kairos.mobile.ui.politique.jeux.kit.Glyphes
import app.kairos.mobile.ui.politique.jeux.kit.PixelGrid
import app.kairos.mobile.ui.politique.jeux.kit.PixelIcon
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.SourceButton
import app.kairos.mobile.ui.politique.kit.VerdictChip
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.quoted
import app.kairos.mobile.ui.politique.kit.verdictCode
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.reducedMotion
import app.kairos.mobile.ui.theme.KairosColors
import app.kairos.mobile.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

private val Mono = FontFamily.Monospace
private val Overshoot = CubicBezierEasing(0.34f, 1.45f, 0.64f, 1f)

/** Sur-titre et question de la manche ; [citation] : guillemets et grand pictogramme translucide. */
@Composable
fun FenetreQuestion(m: Manche, citation: Boolean = false) {
    val pri = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        if (citation) PixelIcon("citation", pri.copy(alpha = 0.16f), Modifier.padding(12.dp).size(112.dp))
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, if (citation) Alignment.Bottom else Alignment.CenterVertically),
        ) {
            if (m.kicker.isNotBlank()) Kicker(m.kicker)
            Text(
                frTypo(if (citation) quoted(m.q) else m.q), Modifier.semantics { heading() },
                fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Étoile pixel « du premier coup ». */
@Composable
fun Etoile(modifier: Modifier = Modifier.size(12.dp), color: Color = MaterialTheme.colorScheme.tertiary) = PixelGrid(Glyphes.star, color, modifier)

private fun Palier.scene(): SceneKind = when (this) {
    Palier.PremierCoup -> SceneKind.PremierCoup
    Palier.Moitie -> SceneKind.Moitie
    Palier.Complet -> SceneKind.Complet
}

/** Carte du jeu [slug] : titre, pager, fenêtre et manette ; fin de partie ; « Bientôt » si le jeu n'est pas jouable. */
@Composable
fun CarteJeu(session: JeuxSession, slug: String, data: PolData, nav: PolNavigator, modifier: Modifier = Modifier) {
    val jeu = session.jeux[slug]
    val pret = session.prets[slug]
    val nom = jeu?.nom?.takeIf { it.isNotBlank() } ?: slug
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
    ) {
        if (pret == null) {
            Text(frTypo(nom), Modifier.padding(start = 2.dp, bottom = 10.dp).semantics { heading() }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                PixelIcon(slug, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), Modifier.size(64.dp))
                Text("Bientôt disponible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LaunchedEffect(slug, session.loaded, session.fins[slug], session.etats[slug]) { session.etat(slug) }
        val fin = session.fins[slug]
        val st = session.etats[slug]
        when {
            fin != null -> FinCarte(session, slug, nom, fin)
            st != null -> MancheCarte(session, pret, nom, st, data, nav)
        }
    }
}

@Composable
private fun MancheCarte(session: JeuxSession, g: JeuPret, nom: String, st: MancheEtat, data: PolData, nav: PolNavigator) {
    val view = LocalView.current
    val reduit = reducedMotion()
    val play = LocalPlayMoment.current
    var annonce by remember { mutableStateOf("") }
    val d = remember(st.manche) { g.impl.decoder(st.manche.d, g.jeu.params) } ?: return
    val coque = remember(st, g) {
        object : Coque {
            override fun juste() {
                if (st.solved) return
                val palier = session.juste(g.slug, st)
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                palier?.let { play(it.scene()) }
                annonce = when (palier) {
                    Palier.Complet -> "Juste. Thème complet."
                    Palier.Moitie -> "Juste. Moitié du thème."
                    Palier.PremierCoup -> "Juste, du premier coup."
                    null -> "Juste."
                }
            }
            override fun faux(cle: String, msg: String) {
                session.faux(st, cle)
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                annonce = "$msg Réessayez."
            }
            override fun annoncer(msg: String) { annonce = msg }
            override fun cran() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            override val reduit = reduit
            override val params: JsonObject = g.jeu.params
            override val data = data
        }
    }
    val (n, total) = session.position(g.slug)
    // Carte déjà faite dans un cycle précédent : signalée tant qu'elle n'est pas résolue.
    val deja = remember(st) { carte(g.slug, st.manche.id) in session.prog.faites }
    val piste = rememberPiste(session, g.slug, st, reduit)
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(frTypo(nom), Modifier.weight(1f).semantics { heading() }, fontSize = 18.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Pager(n, total, st, onPage = { piste.go(it) })
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f, fill = true).heightIn(min = 150.dp)) {
            Fenetre(piste, st, session, g.slug, reduit,
                question = {
                    Box(Modifier.fillMaxSize()) {
                        g.impl.FenetreBrute(st.manche, d, st, coque)
                        if (deja && !st.solved) DejaVu(Modifier.align(Alignment.TopEnd).padding(10.dp))
                    }
                },
                reponse = { Reponse(g, st, d, carte(g.slug, st.manche.id) in session.prog.premier, data, nav, onNext = { piste.go(Page.Suivant) }, reduit) },
            )
        }
        g.impl.ManetteBrute(st.manche, d, st, coque)
    }
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = annonce })
}

// ---------- Piste ----------

/** État de la piste : position (px), largeur mesurée, page visée. */
private class Piste(val st: MancheEtat, private val go: (Page) -> Unit) {
    var x by mutableFloatStateOf(0f)
    var width by mutableFloatStateOf(0f)
    var gap = 0f
    var job: Job? = null
    /** Dernier geste ou dernier affichage de la réponse : relance l'attente de l'invitation. */
    var touched by mutableStateOf(0)
    fun go(p: Page) = go.invoke(p)
}

@Composable
private fun rememberPiste(session: JeuxSession, slug: String, st: MancheEtat, reduit: Boolean): Piste {
    val scope = rememberCoroutineScope()
    val piste = remember(st) {
        lateinit var p: Piste
        p = Piste(st) { target ->
            if (!st.solved && target != Page.Question) return@Piste
            p.job?.cancel()
            if (target != Page.Suivant) st.page = target
            p.job = scope.launch {
                val to = restX(target, p.width, p.gap)
                if (reduit) p.x = to else animate(p.x, to, animationSpec = tween(300, easing = Motion.EmphasizedDecelerate)) { v, _ -> p.x = v }
                if (target == Page.Suivant) session.suivant(slug)
                else if (target == Page.Reponse) p.touched++
            }
        }
        p
    }
    // Bonne réponse : glissement vers la réponse après un temps.
    LaunchedEffect(st) {
        if (st.solved) return@LaunchedEffect
        snapshotFlow { st.solved }.first { it }
        delay(if (reduit) 0 else AUTO_SLIDE_MS)
        piste.go(Page.Reponse)
    }
    return piste
}

@Composable
private fun Fenetre(
    piste: Piste, st: MancheEtat, session: JeuxSession, slug: String, reduit: Boolean,
    question: @Composable () -> Unit, reponse: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val kc = KairosColors.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var dx by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val drag = rememberDraggableState { delta ->
        dx += delta
        val d = dragOffset(st.page, dx, piste.width, piste.gap)
        if (d.armed && !armed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        armed = d.armed
        if (st.page == Page.Reponse) session.invitation = session.invitation.pulled(d.pull)
        piste.x = restX(st.page, piste.width, piste.gap) + d.offset
    }
    // Invitation à glisser : sur la réponse, sans geste, 10 s puis toutes les 3 s, 3 fois au plus.
    LaunchedEffect(st.page, piste.touched, reduit) {
        if (reduit || st.page != Page.Reponse) return@LaunchedEffect
        var encore = false
        while (true) {
            delay(session.invitation.delay(encore) ?: break)
            val rest = restX(Page.Reponse, piste.width, piste.gap)
            val a = CAP * piste.width * Invitation.AMP
            animate(0f, 0f, animationSpec = keyframes {
                durationMillis = Invitation.DUR.toInt()
                0f at 0 using Motion.EmphasizedDecelerate
                -a at (Invitation.DUR * 0.28f).toInt()
                -a at (Invitation.DUR * 0.42f).toInt() using Overshoot
            }) { v, _ -> piste.x = rest + v }
            session.invitation = session.invitation.played()
            encore = true
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(scheme.surface).border(2.dp, scheme.outlineVariant, RoundedCornerShape(20.dp))
            .draggable(
                drag, Orientation.Horizontal, enabled = st.solved,
                onDragStarted = { piste.job?.cancel(); dx = 0f; armed = false },
                onDragStopped = { v ->
                    val target = release(st.page, dx, v, armed, density.density)
                    dx = 0f
                    armed = false
                    piste.touched++
                    piste.go(target)
                },
            ),
    ) {
        val w = constraints.maxWidth.toFloat()
        val gap = with(density) { GAP_DP.dp.toPx() }
        // Clé sur la piste aussi : une piste neuve (retour à un jeu entamé) reçoit sa largeur.
        LaunchedEffect(w, piste) {
            piste.width = w
            piste.gap = gap
            piste.x = restX(st.page, w, gap)
        }
        val wDp = maxWidth
        val at = { base: Float -> Modifier.offset { IntOffset((base + piste.x).roundToInt(), 0) }.width(wDp).fillMaxHeight() }
        Box(at(0f).let { if (st.page != Page.Question) it.clearAndSetSemantics { } else it }) { question() }
        if (st.solved) {
            Box(Modifier.offset { IntOffset((w + piste.x).roundToInt(), 0) }.width(GAP_DP.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(scheme.surface, scheme.primaryContainer))))
            Box(at(w + gap).background(scheme.primaryContainer).let { if (st.page != Page.Reponse) it.clearAndSetSemantics { } else it }) { reponse() }
            // Panneau « suivant » : flèche fixe au centre du quart révélé à la butée.
            Box(at(2 * w + gap).background(kc.ciel).clearAndSetSemantics { }) {
                PixelGrid(Glyphes.fleche, kc.nuit, Modifier.align(Alignment.CenterStart).offset(x = with(density) { (w * CAP / 2).toDp() } - 24.dp + if (armed) 1.dp else 0.dp).size(48.dp))
            }
        }
    }
}

// ---------- Réponse ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reponse(g: JeuPret, st: MancheEtat, d: Any, first: Boolean, data: PolData, nav: PolNavigator, onNext: () -> Unit, reduit: Boolean) {
    val r = st.manche.reponse
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onPrimaryContainer
    Box(Modifier.fillMaxSize()) {
        PixelIcon("coche", scheme.primary.copy(alpha = 0.22f), Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 64.dp).size(112.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (first) Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Etoile(Modifier.size(14.dp))
                Text("DU PREMIER COUP", color = ink.copy(alpha = 0.85f), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.04.em)
            }
            Text(frTypo(r.titre), Modifier.semantics { heading() }, color = ink, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold)
            val code = r.verdict?.let(::verdictCode)
            if (code != null) VerdictChip(code)
            else g.impl.etiquetteBrute(st.manche, d, st, g.jeu.params)?.let { NeutralChip(it) }
            r.texte.split("\n\n").filter { it.isNotBlank() }.forEach { Text(frTypo(it.trim()), color = ink, fontSize = 14.sp, lineHeight = 20.sp) }
            r.cache?.takeIf { it.isNotBlank() }?.let {
                Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Ce qu'elle cache : ") }; append(frTypo(it.trim())) }, color = ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
            g.impl.ReponseBrute(this, st.manche, d, st)
            Spacer(Modifier.weight(1f))
            val refs = r.refs
            if (refs.isNotEmpty()) FlowRow(Modifier.semantics { contentDescription = r.libelle ?: "Source" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                refs.forEach { SourceButton(it, data, { id -> nav.sheet(PolSheetState.Evaluator(id)) }) }
            }
        }
        // Mouvements réduits : pas d'invitation, une flèche compacte mène à la suivante.
        if (reduit) IconButton(onNext, Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(CircleShape).background(KairosColors.current.ciel)) {
            Icon(KairosIcons.ChevronForward, contentDescription = "Question suivante", tint = KairosColors.current.nuit)
        }
    }
}

@Composable
private fun DejaVu(modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        "DÉJÀ VU", modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 3.dp),
        color = scheme.onSurfaceVariant, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.06.em,
    )
}

@Composable
private fun NeutralChip(text: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text.uppercase(), Modifier.clip(CircleShape).border(1.5.dp, scheme.outlineVariant, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
        color = scheme.onSurfaceVariant, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.06.em, maxLines = 2,
    )
}

// ---------- Pager ----------

@Composable
private fun Pager(n: Int, total: Int, st: MancheEtat, onPage: (Page) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$n/$total", Modifier.padding(end = 4.dp).clearAndSetSemantics { }, color = scheme.onSurfaceVariant, fontFamily = Mono, fontSize = 11.sp)
        IconButton({ onPage(Page.Question) }, enabled = st.page != Page.Question) {
            Icon(KairosIcons.ChevronBack, contentDescription = "Question $n", tint = scheme.onSurfaceVariant)
        }
        listOf(Page.Question to "Question $n", Page.Reponse to "Réponse $n").forEach { (p, label) ->
            val on = st.page == p
            val enabled = p == Page.Question || st.solved
            Box(
                Modifier.size(22.dp, 48.dp).clickable(enabled = enabled, onClickLabel = label) { onPage(p) }
                    .semantics { contentDescription = label + if (on) ", affichée" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (on) 16.dp else 7.dp, 7.dp).clip(RoundedCornerShape(4.dp)).background(if (on) scheme.primary else scheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.45f)))
            }
        }
        IconButton({ onPage(if (st.page == Page.Reponse) Page.Suivant else Page.Reponse) }, enabled = st.solved) {
            Icon(KairosIcons.ChevronForward, contentDescription = if (st.page == Page.Reponse) "Question suivante" else "Réponse $n", tint = scheme.onSurfaceVariant)
        }
    }
}

// ---------- Fin de partie ----------

@Composable
private fun FinCarte(session: JeuxSession, slug: String, nom: String, fin: FinPartie) {
    val scheme = MaterialTheme.colorScheme
    val viv = fin.fin == Fin.Vivier
    val pg = session.avance(slug)
    val tp = session.themeOf(slug)?.let { session.avance(it) }
    val complet = tp != null && tp.m > 0 && tp.n == tp.m
    val nCards = if (viv) pg.n else fin.cartes.size
    val f = if (viv) pg.f else fin.cartes.count { carte(slug, it) in session.prog.premier }
    val s = if (nCards > 1) "s" else ""
    Text(frTypo(nom), Modifier.padding(start = 2.dp, bottom = 10.dp).semantics { heading() }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 150.dp).clip(RoundedCornerShape(20.dp)).border(2.dp, scheme.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Etoile(Modifier.padding(bottom = 6.dp).size(72.dp))
            Kicker((if (viv) "Toutes les cartes jouées" else "Partie terminée") + if (complet) " · thème complet" else "")
            Text(
                if (viv) "$nCards carte$s sur ${pg.m}" else "$nCards carte$s faite$s", Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (f > 0) Etoile()
                Text(if (f > 0) "$f du premier coup" else "Aucune du premier coup cette fois", color = scheme.onSurfaceVariant, fontSize = 14.sp)
            }
            if (!viv) {
                val left = pg.m - pg.n
                Text(
                    (if (left > 0) "Encore $left carte${if (left > 1) "s" else ""} jamais vue${if (left > 1) "s" else ""}" else "D'autres cartes à rejouer") + " dans ce jeu",
                    color = scheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoutonPixel(
                if (viv) "Recommencer ce jeu depuis le début" else "Continuer : nouvelle partie", { session.relancer(slug) },
                glyphe = if (viv) Glyphes.redo else Glyphes.play, couleur = Teintes.valid,
                forme = app.kairos.mobile.ui.politique.jeux.kit.Forme.Carre,
            )
            Text(if (viv) "Recommencer" else "Continuer", Modifier.clearAndSetSemantics { }, color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                if (viv) "Les cartes restent acquises dans la progression du thème." else "Nouvelle partie : cartes jamais vues d'abord.",
                color = scheme.onSurfaceVariant, fontSize = 12.5.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Barre de progression pixel : 12 cases. */
@Composable
fun BarrePixel(a: Avance, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val on = progressCells(a)
    val bg = app.kairos.mobile.ui.politique.jeux.kit.mix(scheme.surface, scheme.onSurface, 0.14f)
    val off = app.kairos.mobile.ui.politique.jeux.kit.mix(scheme.surface, scheme.onSurface, 0.04f)
    Canvas(modifier.size((12 * 5 + 1).dp, 7.dp).clearAndSetSemantics { }) {
        val c = 1.dp.toPx().roundToInt().coerceAtLeast(1)
        drawRect(bg)
        for (i in 0 until 12) drawRect(
            if (i < on) scheme.primary else off,
            androidx.compose.ui.geometry.Offset((c + i * 5 * c).toFloat(), c.toFloat()),
            androidx.compose.ui.geometry.Size((4 * c).toFloat(), (5 * c).toFloat()),
        )
    }
}

/** Sous-titre de l'en-tête d'un thème : « N/M cartes · ★ K du premier coup » et barre pixel. */
@Composable
fun ProgressionTheme(a: Avance) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = progressLabel(a) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${a.n}/${a.m} cartes" + if (a.f > 0) " ·" else "", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (a.f > 0) {
                Etoile()
                Text("${a.f} du premier coup", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        BarrePixel(a)
    }
}
