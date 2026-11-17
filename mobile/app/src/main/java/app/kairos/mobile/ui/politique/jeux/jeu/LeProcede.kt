package app.kairos.mobile.ui.politique.jeux.jeu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kairos.mobile.ui.politique.jeux.Coque
import app.kairos.mobile.ui.politique.jeux.Manche
import app.kairos.mobile.ui.politique.jeux.MancheEtat
import app.kairos.mobile.ui.politique.jeux.PolJeu
import app.kairos.mobile.ui.politique.jeux.kit.PixelIcon
import app.kairos.mobile.ui.politique.jeux.kit.PixelShape
import app.kairos.mobile.ui.politique.jeux.kit.Teintes
import app.kairos.mobile.ui.politique.jeux.kit.cell
import app.kairos.mobile.ui.politique.jeux.kit.cellPx
import app.kairos.mobile.ui.politique.jeux.kit.mix
import app.kairos.mobile.ui.politique.kit.Kicker
import app.kairos.mobile.ui.politique.kit.frTypo
import app.kairos.mobile.ui.politique.kit.quoted
import app.kairos.mobile.ui.politique.reducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `d` de « le-procede » : trois procédés, index du bon, famille (étiquette de la réponse), contexte facultatif. */
@Serializable
data class TamponsD(val choix: List<String> = emptyList(), val bonne: Int = -1, val famille: String = "", val contexte: String? = null)

/** Empreinte visible sur la feuille : procédé frappé et justesse. */
data class Empreinte(val texte: String, val juste: Boolean)

/** Dernier tampon frappé : le bon une fois trouvé, sinon le dernier refusé ; rien avant le premier essai. */
fun empreinte(d: TamponsD, solved: Boolean, out: List<String>): Empreinte? = when {
    solved -> d.choix.getOrNull(d.bonne)?.let { Empreinte(it, true) }
    else -> out.lastOrNull()?.toIntOrNull()?.let(d.choix::getOrNull)?.let { Empreinte(it, false) }
}

/**
 * Poignée du tampon, vue de face, 16×17 : boule en champignon (contour `o`, clair `h`, reflet `w`, aplat `k`,
 * ombre `s`), col étroit puis évasement vers le bloc (bois `n`, ombre `m`).
 */
val POIGNEE_TAMPON: List<String> = (0 until 17).map { y ->
    fun ball(x: Int, y: Int) = x in 0 until 16 && ((x + 0.5 - 8) / 7.5).let { it * it } + ((y + 0.5 - 5.5) / 5.8).let { it * it } <= 1
    buildString {
        for (x in 0 until 16) {
            if (y <= 11 && ball(x, y)) {
                val v = (x + 0.5 - 8) / 7.5 + (y + 0.5 - 5.5) / 5.8
                append(
                    when {
                        !ball(x - 1, y) || !ball(x + 1, y) || !ball(x, y - 1) || !ball(x, y + 1) -> 'o'
                        (x to y) in setOf(4 to 2, 5 to 2, 4 to 3) -> 'w'
                        v < -0.85 -> 'h'
                        v > 0.65 -> 's'
                        else -> 'k'
                    },
                )
                continue
            }
            val half = if (y <= 14) 2 else y - 12
            val dx = x + 0.5 - 8
            append(if (y > 10 && kotlin.math.abs(dx) < half) (if (dx > half - 1.5) 'm' else 'n') else '.')
        }
    }
}

/**
 * Texture d'encre 16×16 (graine fixe) : 0 trou, 1 demi-teinte, 2 plein. Les trous et les demi-teintes donnent
 * l'aspect d'un tampon réel sans flou.
 */
val TEXTURE_ENCRE: IntArray = run {
    var n = 7L
    IntArray(256) {
        n = (n * 1103515245L + 12345L) % 2147483648L
        val r = n / 2147483648.0
        if (r <= 0.09) 0 else if (r < 0.2) 1 else 2
    }
}

private val Claque = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Trois tampons encreurs ; on frappe le procédé, son empreinte claque en travers de la phrase. */
class LeProcede : PolJeu<TamponsD>("le-procede", TamponsD.serializer()) {

    override fun valide(d: TamponsD) = d.choix.size == 3 && d.bonne in d.choix.indices && d.choix.none { it.isBlank() }

    override val glyphe = true

    override fun etiquette(m: Manche, d: TamponsD, st: MancheEtat, params: JsonObject) = d.famille.takeIf { it.isNotBlank() }

    @Composable
    override fun Fenetre(m: Manche, d: TamponsD, st: MancheEtat) {
        val sc = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize()) {
            PixelIcon("citation", sc.primary.copy(alpha = 0.16f), Modifier.padding(12.dp).size(112.dp))
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                if (m.kicker.isNotBlank()) Kicker(m.kicker)
                d.contexte?.takeIf { it.isNotBlank() }?.let { Text(frTypo(it), color = sc.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 17.5.sp) }
                Text(
                    frTypo(quoted(m.q)), Modifier.semantics { heading() },
                    fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em, color = sc.onSurface,
                )
            }
            empreinte(d, st.solved, st.out)?.let { Frappe(it, if (it.juste) "ok" to 0 else "ko" to st.react) }
        }
    }

    @Composable
    override fun Manette(m: Manche, d: TamponsD, st: MancheEtat, c: Coque) {
        val sc = MaterialTheme.colorScheme
        val bleu = Teintes.bleu
        val appui = rememberAppui(m.id, c, 150) { i -> if (i == d.bonne) c.juste() else c.faux(i.toString(), "Pas « ${d.choix[i]} ».") }
        Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                d.choix.forEachIndexed { i, t ->
                    Tampon(t, i.toString(), juste = st.solved && i == d.bonne, enfonce = appui.piece == i, st, c, Modifier.weight(1f)) { appui.jouer(i) }
                }
            }
            // Encreur.
            Box(
                Modifier.padding(top = 9.dp).fillMaxWidth().height(18.dp).clip(PixelShape()).background(mix(sc.surface, sc.onSurface, 0.16f))
                    .drawBehind {
                        val e = ent(3.dp)
                        biseau(mix(sc.surface, Color.White, 0.5f), mix(sc.surface, sc.onSurface, 0.3f), e)
                        aplat(mix(bleu, Color.Black, 0.45f), 3 * e, 2 * e, size.width - 6 * e, size.height - 4 * e)
                        aplat(mix(bleu, Color.Black, 0.65f), 3 * e, 2 * e, size.width - 6 * e, e)
                    }.clearAndSetSemantics { },
            )
        }
    }
}

/** Empreinte inclinée : claque à l'arrivée ; refusée, barrée à 0,5 s puis estompée à 1,5 s. */
@Composable
private fun BoxScope.Frappe(e: Empreinte, cle: Pair<String, Int>) {
    val sc = MaterialTheme.colorScheme
    val reduit = reducedMotion()
    val encre = if (e.juste) sc.primary else sc.error
    val echelle = remember(cle) { Animatable(if (reduit) 1f else 1.7f) }
    val opacite = remember(cle) { Animatable(if (reduit) 1f else 0f) }
    val barre = remember(cle) { Animatable(if (reduit || e.juste) 1f else 0f) }
    var partie by remember(cle) { mutableStateOf(false) }
    LaunchedEffect(cle) {
        if (!reduit) {
            launch { echelle.animateTo(1f, keyframes { durationMillis = 240; 0.93f at 144 using Claque }) }
            launch { opacite.animateTo(1f, keyframes { durationMillis = 240; 1f at 144 using Claque }) }
        }
        if (e.juste) return@LaunchedEffect
        if (!reduit) launch { delay(500); barre.animateTo(1f, tween(320, easing = Claque)) }
        delay(1500)
        // Estompage en trois pas ; net en animations réduites.
        if (!reduit) for (a in listOf(0.67f, 0.33f)) { opacite.snapTo(a); delay(120) }
        partie = true
    }
    if (partie) return
    BoxWithConstraints(Modifier.matchParentSize().clearAndSetSemantics { }) {
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = maxHeight * 0.6f).graphicsLayer {
                translationY = -size.height / 2
                rotationZ = -10f
                scaleX = echelle.value; scaleY = echelle.value
                alpha = opacite.value
            },
        ) {
            Box(Modifier.background(sc.surface.copy(alpha = 0.84f))) {
                // Encre masquée par la texture pixel.
                Box(
                    Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val s = ent(3.dp)
                            var y = 0
                            while (y * s < size.height) {
                                var x = 0
                                while (x * s < size.width) {
                                    val a = when (TEXTURE_ENCRE[(y % 16) * 16 + x % 16]) { 0 -> 1f; 1 -> 0.45f; else -> 0f }
                                    if (a > 0f) drawRect(Color.Black.copy(alpha = a), Offset(x * s, y * s), Size(s, s), blendMode = BlendMode.DstOut)
                                    x++
                                }
                                y++
                            }
                        }
                        .border(5.dp, encre).padding(8.dp), // liseré de 5 dp + marge de 3 dp
                ) {
                    Text(
                        e.texte.uppercase(), Modifier.widthIn(max = 260.dp).border(3.dp, encre).padding(start = 19.dp, end = 19.dp, top = 13.dp, bottom = 11.dp),
                        color = encre, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 29.sp,
                        letterSpacing = 0.05.em, textAlign = TextAlign.Center,
                    )
                }
            }
            // Barre de refus, tracée de gauche à droite.
            if (!e.juste) Box(
                Modifier.matchParentSize().drawBehind {
                    val h = ent(9.dp)
                    val debord = ent(12.dp)
                    val w = (size.width + 2 * debord) * barre.value
                    aplat(mix(encre, Color.Black, 0.25f), -debord, (size.height - h) / 2, w, h)
                },
            )
        }
    }
}

@Composable
private fun Tampon(t: String, cle: String, juste: Boolean, enfonce: Boolean, st: MancheEtat, c: Coque, modifier: Modifier, onClick: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    val ecarte = cle in st.out
    val actif = !st.solved && !ecarte
    val src = remember { MutableInteractionSource() }
    val presse by src.collectIsPressedAsState()
    val tremble = rememberTremblement(st, cle, c.reduit)
    val gris = mix(sc.outlineVariant, sc.onSurfaceVariant, 0.3f)
    val rouge = Teintes.rouge
    val kc = when { juste -> sc.primaryContainer; ecarte -> mix(sc.surface, gris, 0.5f); else -> sc.tertiaryContainer }
    val kn = when { juste -> sc.primary; ecarte -> gris; else -> mix(sc.tertiary, Color.Black, 0.32f) }
    val ink = sc.onSurface
    val bas = when { juste || enfonce -> 9.dp; presse && actif -> 3.dp; else -> 0.dp }
    val s = cellPx(3.dp)
    val bois = mapOf(
        'o' to mix(kn, Color.Black, 0.45f), 'k' to kn, 'h' to mix(kn, Color.White, 0.28f), 'w' to Color.White.copy(alpha = 0.8f),
        's' to mix(kn, Color.Black, 0.22f), 'n' to mix(kn, Color.Black, 0.12f), 'm' to mix(kn, Color.Black, 0.4f),
    )
    val etat = if (juste) " (bonne réponse)" else if (ecarte) " (écarté)" else ""
    Column(
        modifier.offset(y = bas).graphicsLayer { translationX = tremble.value.dp.toPx() }.alpha(if (ecarte) 0.42f else 1f)
            .clickable(src, indication = null, enabled = actif && !enfonce, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = t + etat },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val w = with(LocalDensity.current) { (16 * s).toDp() to (17 * s).toDp() }
        Canvas(Modifier.size(w.first, w.second)) {
            POIGNEE_TAMPON.forEachIndexed { y, r -> r.forEachIndexed { x, ch -> bois[ch]?.let { cell(x, y, it, s) } } }
        }
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(PixelShape()).background(mix(kc, ink, 0.32f)),
        ) {
            Box(
                Modifier.fillMaxWidth().height(42.dp).background(kc).drawBehind {
                    val e = ent(3.dp)
                    biseau(mix(kc, Color.White, 0.6f), mix(kc, ink, 0.16f), e)
                    if (!ecarte) aplat(Color.White.copy(alpha = 0.7f), 3 * e, 2 * e, 3 * e, e)
                }.padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    frTypo(t), Modifier.clearAndSetSemantics { }, color = if (juste) sc.onPrimaryContainer else if (ecarte) sc.onSurfaceVariant else ink,
                    fontSize = 12.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Gomme du tampon.
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(9.dp)) {
            val e = ent(3.dp)
            val g = mix(if (ecarte) gris else rouge, Color.Black, 0.38f)
            aplat(g, 0f, 0f, size.width, 2 * e)
            aplat(g, e, 2 * e, size.width - 2 * e, size.height - 2 * e)
        }
    }
}
