package app.kairos.mobile.ui.politique.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Feuille glissante (poignée, fermeture au glisser ou au toucher du fond). [full] : plein écran dès
 * l'ouverture ; sinon hauteur du contenu. Le contenu défile à lui seul s'il le faut.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolSheet(full: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = full),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight() else Modifier).navigationBarsPadding(), content = content)
    }
}
