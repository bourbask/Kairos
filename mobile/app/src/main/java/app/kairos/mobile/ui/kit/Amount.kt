package app.kairos.mobile.ui.kit

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle

/** Masquage des montants, fourni une fois par l'écran d'espace et lu par tous les composants. */
val LocalAmountsMasked = compositionLocalOf { false }

/** Montant en euros, masqué selon [masked] : ni à l'écran ni dans la sémantique, aucun chiffre. */
@Composable
fun Amount(
    value: Double,
    modifier: Modifier = Modifier,
    masked: Boolean = LocalAmountsMasked.current,
    cents: Boolean = true,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val m = if (masked) modifier.clearAndSetSemantics { contentDescription = "montant masqué" } else modifier
    Text(amountText(value, masked, cents), m, color = color, style = style, maxLines = 1)
}
