package app.kairos.mobile.ui.memoire

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.Habitude
import app.kairos.mobile.data.Souvenir
import app.kairos.mobile.ui.kit.SectionHeader

/**
 * « Ce que je retiens » : ancres, habitudes apprises et souvenirs qui s'estompent, chacun
 * replié par défaut, avec un bouton par geste. [hidden] : éléments dont un geste est en cours.
 */
@Composable
fun ControleSection(
    view: ControleView,
    hidden: Set<String>,
    revealed: Set<String>,
    masked: Boolean,
    onReveal: (String) -> Unit,
    onAnchor: (Souvenir, Boolean) -> Unit,
    onRevive: (Souvenir) -> Unit,
    onHabit: (Habitude, Boolean) -> Unit,
    onForgetHabit: (Habitude) -> Unit,
    onRetry: () -> Unit,
) {
    SectionHeader("Ce que je retiens")
    val c = view.data
    if (c == null) {
        // Rien encore lu dans cette session : rien n'est gardé sur l'appareil.
        if (view.issue == null) {
            Caption("Chargement…")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(view.issue, Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Réessayer") }
            }
        }
        return
    }
    view.issue?.let { Caption("$it · dernière liste lue") }

    fun anchorId(s: Souvenir) = anchorCard(s.nature, s.ref, true).id
    val ancres = c.ancres.filterNot { anchorId(it) in hidden }
    val habitudes = c.habitudes.filterNot { habitErasure(it.id).id in hidden }
    val estompes = c.estompes.filterNot { anchorId(it) in hidden || reviveCard(it).id in hidden }

    Fold(anchorsTitle(c), null) {
        if (ancres.isEmpty()) Caption("Aucun souvenir ancré.")
        ancres.forEach { s ->
            SouvenirRow(s, null, revealed, masked, onReveal) {
                if (!s.protege) TextButton(onClick = { onAnchor(s, false) }) { Text("Ne plus retenir") }
            }
        }
    }
    Fold("Habitudes apprises", habitudes.size) {
        if (habitudes.isEmpty()) Caption("Aucune habitude apprise.")
        habitudes.forEach { h ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(h.libelle, style = MaterialTheme.typography.bodyMedium)
                Caption(habitStatus(h))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!h.confirmee || h.rejetee) TextButton(onClick = { onHabit(h, true) }) { Text("Confirmer") }
                if (!h.rejetee) TextButton(onClick = { onHabit(h, false) }) { Text("Rejeter", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onForgetHabit(h) }) { Text("Oublier") }
            }
        }
    }
    Fold("S'estompe", c.estompesTotal) {
        if (estompes.isEmpty()) Caption("Rien ne s'estompe pour l'instant.")
        if (c.estompesTotal > c.estompes.size) Caption("${c.estompes.size} sur ${c.estompesTotal}, les plus récents")
        estompes.forEach { s ->
            SouvenirRow(s, fadedHint(s), revealed, masked, onReveal) {
                TextButton(onClick = { onRevive(s) }) { Text("Raviver") }
                if (s.nature != "deduction") TextButton(onClick = { onAnchor(s, true) }) { Text("Retenir toujours") }
            }
        }
    }
}

@Composable
private fun Fold(title: String, count: Int?, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    FoldHeader(title, count, open) { open = !open }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** Souvenir : nature, texte (sensible masqué comme le journal), indication, gestes. */
@Composable
private fun SouvenirRow(
    s: Souvenir,
    hint: String?,
    revealed: Set<String>,
    masked: Boolean,
    onReveal: (String) -> Unit,
    actions: @Composable () -> Unit,
) {
    val text = controlText(s, revealed, masked)
    val tap = if (s.sensitive && !masked) {
        Modifier.clickable(onClickLabel = if (text == null) "Afficher le souvenir" else "Masquer le souvenir") { onReveal(controlKey(s)) }
    } else {
        Modifier
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.fillMaxWidth().then(tap).semantics(mergeDescendants = true) {}) {
            Caption(natureLabel(s.nature))
            if (text == null) {
                Text(
                    if (masked) "Souvenir sensible · masqué" else "Souvenir sensible · touche pour afficher",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            hint?.let { Caption(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}
