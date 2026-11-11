package app.kairos.mobile.ui.mimir

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.Repository
import app.kairos.mobile.ui.kit.LocalNotifier
import app.kairos.mobile.ui.kit.SectionHeader
import app.kairos.mobile.ui.kit.StatusBanner
import app.kairos.mobile.ui.kit.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** Demande un nouveau lien à usage unique ; rend le lien vérifié (https, jeton dans le fragment). */
suspend fun createShareLink(repository: Repository, label: String): String {
    val r = repository.lookup(PARTAGE, "app_partage_creer", ShareLink.serializer(), buildJsonObject { put("libelle", label) })
    return normalizeMimirLink(r.lien).getOrElse { throw ApiError.Server(200, ApiError.Server.UNREADABLE) }
}

/** Feuille de partage du système : le lien n'est ni gardé ni journalisé par l'application. */
private fun sendLink(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    try {
        context.startActivity(Intent.createChooser(send, "Partager le lien"))
    } catch (_: ActivityNotFoundException) {
        // Aucune application de partage : rien à faire.
    }
}

/**
 * « Partager » : libellé prérempli, modifiable, puis un lien neuf, valable une fois,
 * remis aussitôt à la feuille de partage.
 */
@Composable
fun ShareDialog(repository: Repository, onDismiss: () -> Unit, onUnauthorized: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(defaultShareLabel(LocalDate.now())) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun submit() {
        if (busy) return
        val clean = cleanShareLabel(label).getOrElse {
            error = it.message
            return
        }
        busy = true
        scope.launch {
            try {
                sendLink(context, createShareLink(repository, clean))
                onDismiss()
            } catch (_: ApiError.Unauthorized) {
                onUnauthorized()
            } catch (e: ApiError) {
                error = shareIssue(e)
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Partager le site") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Un lien neuf, à ouvrir une seule fois dans les 3 jours. Révocable à tout moment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        error = null
                    },
                    label = { Text("Nom du partage") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = !busy && label.isNotBlank()) { Text(if (busy) "Création…" else "Partager") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * « Accès partagés » : chaque partage (nom, état), révocation en un geste avec fenêtre
 * d'annulation. Rien n'est gardé sur l'appareil : la liste est relue à chaque ouverture.
 */
@Composable
fun SharesPanel(repository: Repository, appScope: CoroutineScope, bottom: Dp, onUnauthorized: () -> Unit) {
    val notifier = LocalNotifier.current
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val pending = repository.sharesPending
    val hidden by pending.hidden.collectAsState()
    var shares by remember { mutableStateOf<List<Share>?>(null) }
    var issue by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        try {
            shares = sortedShares(repository.lookup(PARTAGE, "app_partage_liste", ListSerializer(Share.serializer()), buildJsonObject {}))
            issue = null
        } catch (_: ApiError.Unauthorized) {
            unauthorized()
        } catch (e: ApiError) {
            issue = shareIssue(e)
        }
    }

    // Depuis appScope : quitter l'espace pendant la fenêtre n'abandonne pas le geste.
    fun revoke(s: Share) {
        if (!pending.busy.claim(s.id.toString())) return
        appScope.launch {
            pending.hide(s.id, true)
            try {
                if (notifier.undo("Accès révoqué · ${s.libelle}")) {
                    pending.hide(s.id, false)
                    return@launch
                }
                notifier.show(repository.tool(PARTAGE, "app_partage_revoquer", buildJsonObject { put("id", s.id) }))
                reload++
            } catch (_: ApiError.Unauthorized) {
                pending.hide(s.id, false)
                unauthorized()
            } catch (e: ApiError) {
                pending.hide(s.id, false)
                notifier.show("Révocation non faite : ${shareIssue(e)}")
            } finally {
                pending.busy.release(s.id.toString())
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Accès partagés")
        issue?.let { StatusBanner("Partages indisponibles", tone = Tone.Urgent, detail = it, actionLabel = "Réessayer", onAction = { reload++ }) }
        val list = shares
        when {
            list == null -> if (issue == null) Caption("Chargement…")
            list.isEmpty() -> Caption("Aucun partage pour l'instant.")
            else -> list.forEach { s ->
                // Révoqué pendant la fenêtre : montré inactif jusqu'à la relecture.
                val shown = if (s.id in hidden) s.copy(actif = false) else s
                ShareRow(shown, onRevoke = { revoke(s) })
            }
        }
    }
}

@Composable
private fun ShareRow(s: Share, onRevoke: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.libelle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (s.actif) colors.onBackground else colors.onSurfaceVariant)
            Text(shareStatus(s), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (s.actif) TextButton(onClick = onRevoke) { Text("Révoquer") }
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
