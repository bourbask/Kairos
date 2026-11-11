package app.kairos.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import app.kairos.mobile.data.AppRelease
import app.kairos.mobile.ui.theme.ThemeMode
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.MascotKind
import app.kairos.mobile.ui.mascot.MascotState
import app.kairos.mobile.ui.theme.KairosColors
import app.kairos.mobile.ui.theme.ThemePalette
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import app.kairos.mobile.ui.kit.KairosIcons
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.SpaceHeader

/** Adresse du serveur montrée en partie seulement : de quoi reconnaître l'appareil, pas de quoi la relever. */
internal fun maskAddress(baseUrl: String): String {
    val uri = runCatching { java.net.URI(baseUrl) }.getOrNull()
    val host = uri?.host ?: return "•••"
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return host.take(3) + "•••" + port
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    theme: ThemeMode,
    dark: Boolean,
    onTheme: (ThemeMode) -> Unit,
    palette: ThemePalette,
    onPalette: (ThemePalette) -> Unit,
    mascot: MascotKind,
    onMascot: (MascotKind) -> Unit,
    speakReplies: Boolean,
    onSpeakReplies: (Boolean) -> Unit,
    hideFromCaptures: Boolean,
    onHideFromCaptures: (Boolean) -> Unit,
    amountsMasked: Boolean,
    onToggleMasked: () -> Unit,
    address: String,
    version: String,
    update: AppRelease?,
    updateProgress: Float?,
    onUpdate: () -> Unit,
    onCheck: (report: Boolean) -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    // Vérification discrète à l'ouverture ; le bouton « Vérifier » dit le résultat.
    LaunchedEffect(Unit) { onCheck(false) }
    val applied = if (dark) "sombre" else "clair"
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SpaceHeader("Réglages", onClose, backLabel = "Fermer les réglages", backIcon = KairosIcons.ChevronDown)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Thème")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ThemeMode.entries) {
                    FilterChip(selected = theme == mode, onClick = { onTheme(mode) }, label = { Text(mode.name) })
                }
            }
            Text(
                "Auto suit la luminosité ambiante (ou le thème du téléphone sans capteur)." +
                    if (theme == ThemeMode.Auto) " Actuellement : $applied." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Personnalisation")
            Text("Couleurs", style = MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in ThemePalette.entries) PaletteCard(p, p == palette) { onPalette(p) }
            }
            Text(palette.mood, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Personnage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.selectableGroup()) {
                for (m in MascotKind.entries) MascotRow(m, m == mascot) { onMascot(m) }
            }

            SectionHeader("Confidentialité")
            SwitchRow(
                "Masquer les montants",
                "Dans tous les espaces, et le journal sensible de la mémoire. L'œil de Finances fait de même.",
                amountsMasked,
            ) { onToggleMasked() }
            SwitchRow(
                "Masquer l'app dans les captures et les apps récentes",
                "Captures d'écran bloquées, aperçu masqué dans les applications récentes.",
                hideFromCaptures,
                onHideFromCaptures,
            )

            SectionHeader("Voix")
            SwitchRow("Lire les réponses à voix haute", "Les réponses sont lues dès qu'elles arrivent.", speakReplies, onSpeakReplies)

            SectionHeader("Application")
            Text("Version installée : $version", style = MaterialTheme.typography.bodyLarge)
            when {
                updateProgress != null -> {
                    Text(updateLabel(updateProgress), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                    if (updateProgress >= 1f) TextButton(onClick = onUpdate) { Text("Reprendre l'installation") }
                }
                update != null -> Button(onClick = onUpdate) { Text("Mettre à jour vers ${update.version}") }
                else -> OutlinedButton(onClick = { onCheck(true) }) { Text("Vérifier les mises à jour") }
            }

            SectionHeader("Appareil appairé")
            Text(address, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            // Action destructive, isolée en bas et en brique ; confirmée par un dialogue.
            TextButton(onClick = { confirm = true }) { Text("Se déconnecter", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Se déconnecter ?") },
            text = { Text("Le jeton de cet appareil est effacé ; il faudra l'appairer à nouveau.") },
            confirmButton = { TextButton(onClick = { confirm = false; onLogout() }) { Text("Se déconnecter", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}

/** Palette en vignette : moitié claire, moitié sombre, avec le bouton Kairos de chacune. */
@Composable
private fun PaletteCard(palette: ThemePalette, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .widthIn(min = 72.dp)
            .clip(shape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.size(width = 56.dp, height = 36.dp).clip(RoundedCornerShape(8.dp))) {
            for (roles in listOf(palette.light, palette.dark)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(roles.background), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(16.dp).background(roles.btn, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(7.dp).background(roles.body, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        Text(palette.label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Personnage au choix, animé au repos sur son bouton, avec l'origine de son nom. */
@Composable
private fun MascotRow(kind: MascotKind, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(KairosColors.current.btn, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Mascot(MascotState.Repos, size = 28.dp, kind = kind) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(kind.label, style = MaterialTheme.typography.titleMedium)
            Text(kind.origin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

/** Interrupteur libellé : toute la rangée bascule, un seul arrêt pour le lecteur d'écran. */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** « Téléchargement… 42 % » puis « Installation… » une fois le fichier complet. */
internal fun updateLabel(progress: Float): String =
    if (progress < 1f) "Téléchargement… ${(progress * 100).toInt()} %" else "Installation…"
