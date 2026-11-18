package app.kairos.mobile.ui.politique.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.kairos.mobile.ui.theme.mix

/** Coins en marche de 3 dp, comme `--st-cut`. */
val StepCut: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { 3.dp.toPx() }
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(0f, c); lineTo(c, c); lineTo(c, 0f); lineTo(w - c, 0f); lineTo(w - c, c); lineTo(w, c)
            lineTo(w, h - c); lineTo(w - c, h - c); lineTo(w - c, h); lineTo(c, h); lineTo(c, h - c); lineTo(0f, h - c); close()
        })
    }
}

/**
 * Touche pixel : 30 dp visibles, cible de 48 dp, relief bas qui s'écrase au survol puis à l'appui ; jamais d'élévation.
 * [label] est le nom accessible et remplace le contenu. Désactivée : inerte, même teinte.
 * [selected] non null : touche de choix, enfoncée de 2 dp tant qu'elle est choisie.
 */
@Composable
fun PixelKey(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fg: Color = MaterialTheme.colorScheme.onSurface,
    bg: Color = MaterialTheme.colorScheme.let { mix(it.surface, it.onSurface, .08f) },
    /** Fond au survol ; neutre par défaut, une touche teintée passe le sien. */
    hoverBg: Color = MaterialTheme.colorScheme.let { mix(it.surface, it.onSurface, .15f) },
    focusRequester: FocusRequester? = null,
    padding: PaddingValues = PaddingValues(horizontal = 11.dp),
    selected: Boolean? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    val down = selected == true
    val drop = if (down) 2 else if (!enabled) 0 else if (pressed) 2 else if (hovered) 1 else 0
    val relief = if (down) 0 else if (!enabled) 2 else if (pressed) 0 else if (hovered) 1 else 2
    val face = if (enabled && hovered && !pressed && !down) hoverBg else bg
    val reliefColor = mix(bg, scheme.onSurface, .22f)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            // Focalisable aussi en mode tactile (le focus y est rendu après un retournement) : la cible de focus du clic,
            // limitée au mode clavier, est coupée au profit d'un `focusable` placé dessous, dont le clic reçoit les touches.
            .focusProperties { canFocus = false }
            .clickable(src, indication = null, enabled = enabled, role = if (selected != null) Role.RadioButton else Role.Button, onClick = onClick)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(enabled, src)
            .clearAndSetSemantics {
                contentDescription = label
                selected?.let { role = Role.RadioButton; this.selected = it }
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .offset(y = drop.dp)
                .heightIn(min = 30.dp)
                .widthIn(min = 30.dp)
                .clip(StepCut)
                .drawBehind {
                    drawRect(face)
                    if (relief > 0) { val r = relief.dp.toPx(); drawRect(reliefColor, Offset(0f, size.height - r), Size(size.width, r)) }
                }
                .then(if (focused) Modifier.border(2.dp, scheme.primary, StepCut) else Modifier)
                .padding(padding)
                .padding(bottom = relief.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Jauge pixel (verdict, progression) : creux en relief, remplissage gradué. [label] null : décorative. */
@Composable
fun PixelGauge(fraction: Float, color: Color, label: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val f = fraction.coerceIn(0f, 1f)
    Canvas(
        modifier.fillMaxWidth().height(16.dp).clearAndSetSemantics {
            if (label != null) { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) }
        },
    ) {
        val d = 1.dp.toPx()
        drawRect(mix(scheme.surface, scheme.onSurface, .07f))
        drawRect(mix(scheme.surface, scheme.onSurface, .13f), size = Size(size.width, 4 * d))
        ring(Offset.Zero, size, 2 * d, mix(scheme.surface, scheme.onSurface, .20f))
        val fw = (size.width - 6 * d) * f
        if (fw <= 0f) return@Canvas
        val o = Offset(3 * d, 3 * d)
        val fs = Size(fw, size.height - 6 * d)
        drawRect(color, o, fs)
        drawRect(Color.Black.copy(alpha = .18f), Offset(o.x, o.y + fs.height - 3 * d), Size(fs.width, 3 * d))
        ring(o, fs, 2 * d, mix(color, Color.Black, .45f))
        // Graduations : 2 dp tous les 6 dp, dans le liseré.
        val gTop = o.y + 2 * d
        val gH = fs.height - 4 * d
        var x = o.x + 2 * d + 4 * d
        while (x < o.x + fs.width - 2 * d) {
            drawRect(Color.Black.copy(alpha = .14f), Offset(x, gTop), Size(minOf(2 * d, o.x + fs.width - 2 * d - x), gH))
            x += 6 * d
        }
    }
}

/** Contour intérieur d'épaisseur [t]. */
private fun DrawScope.ring(o: Offset, s: Size, t: Float, c: Color) {
    drawRect(c, o, Size(s.width, t))
    drawRect(c, Offset(o.x, o.y + s.height - t), Size(s.width, t))
    drawRect(c, Offset(o.x, o.y + t), Size(t, s.height - 2 * t))
    drawRect(c, Offset(o.x + s.width - t, o.y + t), Size(t, s.height - 2 * t))
}

/** Indice de confiance : trois blocs qui montent, pleins jusqu'à [level] ; décoratif, le libellé est lu à côté. */
@Composable
fun ConfidenceBars(level: Int, small: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val off = mix(scheme.surface, scheme.onSurface, .18f)
    Row(
        Modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(if (small) 2.dp else 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(3) { i ->
            val h = (if (small) 3 else 6) * (i + 1)
            Box(Modifier.size(if (small) 4.dp else 9.dp, h.dp).background(if (i < level) scheme.onSurface else off))
        }
    }
}
