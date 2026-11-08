package app.kairos.mobile.ui.maison

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.ApiError
import app.kairos.mobile.data.KairosApi
import app.kairos.mobile.data.Product
import app.kairos.mobile.data.Repository
import app.kairos.mobile.scan.BarcodeCamera
import app.kairos.mobile.ui.theme.Motion
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class AddStep { Scan, Name, Zone, Date }

/** Nom saisi ou dicté : borné, une seule ligne. */
private const val NAME_MAX = 80
private const val RETRY_MS = 3_000L

/**
 * Ajouter hors commande (marché, boulangerie…) : scan continu du code-barres,
 * produit retrouvé (`app_product`), zone, date si frais, puis `app_add` ; un
 * produit inconnu ou sans code-barres se nomme au clavier ou à la voix.
 * Après chaque ajout, retour au scan pour le suivant. Quantité : 1.
 */
@Composable
fun AddScreen(
    api: KairosApi,
    repository: Repository,
    offline: Boolean,
    appScope: CoroutineScope,
    onUnauthorized: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val unauthorized by rememberUpdatedState(onUnauthorized)
    val today = remember { LocalDate.now() }
    var step by remember { mutableStateOf(AddStep.Scan) }
    var barcode by remember { mutableStateOf<String?>(null) }
    var product by remember { mutableStateOf<Product?>(null) }
    var name by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableIntStateOf(0) }
    // Un code dont la recherche vient d'échouer n'est pas relancé à chaque image.
    var retryAt by remember { mutableLongStateOf(0L) }
    var lastCode by remember { mutableStateOf<String?>(null) }

    fun restart() {
        barcode = null
        product = null
        name = ""
        zone = null
        step = AddStep.Scan
    }

    fun failure(e: ApiError): String? = when (e) {
        ApiError.Unauthorized -> {
            unauthorized()
            null
        }
        ApiError.Offline -> "Serveur injoignable : rien n'a été enregistré."
        is ApiError.Server -> if (e.code == 422) e.message else "Module indisponible, réessaie plus tard."
    }

    fun lookup(code: String) {
        // Lecture caméra = donnée non fiable : un code produit, rien d'autre.
        if (busy || offline || code.length !in 8..14 || !code.all(Char::isDigit)) return
        // Le produit reste devant la caméra : une seule lecture par code, quelle qu'en soit l'issue.
        if (step != AddStep.Scan || (code == lastCode && SystemClock.uptimeMillis() < retryAt)) return
        busy = true
        barcode = code
        lastCode = code
        retryAt = SystemClock.uptimeMillis() + RETRY_MS
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            try {
                val p = api.tool("alimentation", "app_product", Product.serializer(), buildJsonObject { put("barcode", code) })
                product = p
                name = p.name.take(NAME_MAX)
                note = null
                step = AddStep.Zone
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e is ApiError.Server && e.code == 422) {
                    note = "Code-barres inconnu : donne son nom."
                    step = AddStep.Name
                } else {
                    note = failure(e)
                }
            } finally {
                busy = false
                retryAt = SystemClock.uptimeMillis() + RETRY_MS
            }
        }
    }

    fun add(expiry: LocalDate?) {
        val at = zone ?: return
        if (busy || offline) return
        busy = true
        val args = buildJsonObject {
            name.trim().takeIf { it.isNotEmpty() }?.let { put("name", it) }
            barcode?.let { put("barcode", it) }
            put("qty", 1)
            put("location", at)
            expiry?.let { put("expiry", it.toString()) }
        }
        // Depuis appScope : un ajout parti n'est pas abandonné si le panneau se ferme.
        appScope.launch {
            try {
                note = repository.maisonTool("app_add", args).ifBlank { "Ajouté." }
                added++
                retryAt = SystemClock.uptimeMillis() + RETRY_MS
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                restart()
                try {
                    repository.refreshMaison()
                } catch (_: ApiError.Unauthorized) {
                    unauthorized()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                note = failure(e)
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
            } finally {
                busy = false
            }
        }
    }

    fun choose(at: String) {
        zone = at
        // Frais selon le module ; produit inconnu : ce qui va au frigo a une date.
        if (product?.fresh ?: (at == "frigo")) step = AddStep.Date else add(null)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        TopBar("Ajouter", if (added > 0) "$added ajouté${if (added > 1) "s" else ""}" else "hors commande", 0, 0, onClose)
        if (offline) Note("Hors ligne : ajout impossible pour l'instant.", error = true)
        note?.let { Note(it) }
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = { Motion.fadeThrough() }, label = "étape") { s ->
            when (s) {
                AddStep.Scan -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BarcodeCamera(
                            why = "Scanne le code-barres : il est lu sur le téléphone, seul le code est envoyé à Kairos.",
                            onCode = ::lookup,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (busy) CircularProgressIndicator()
                    }
                    BigTile("Sans code-barres", Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = !offline && !busy) {
                        note = null
                        barcode = null
                        step = AddStep.Name
                    }
                }
                AddStep.Name -> NameStep(
                    name = name,
                    onName = { name = it.take(NAME_MAX) },
                    onNext = { if (name.isNotBlank()) step = AddStep.Zone },
                    onBack = ::restart,
                    onNote = { note = it },
                )
                AddStep.Zone -> Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.Center) {
                        Text("Où le ranges-tu ?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    ZoneGrid(proposed = product?.location, enabled = !offline && !busy, onZone = ::choose) { m ->
                        BigTile("← Autre", m, enabled = !busy, color = MaterialTheme.colorScheme.onSurfaceVariant, onClick = ::restart)
                    }
                    Spacer(Modifier.padding(4.dp))
                }
                AddStep.Date -> DateStep(
                    name = name,
                    zone = zone,
                    today = today,
                    enabled = !offline && !busy,
                    onDate = ::add,
                    onBack = { step = AddStep.Zone },
                    onMissing = null,
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** Nom du produit au clavier, ou dicté par la reconnaissance vocale du téléphone. */
@Composable
private fun NameStep(name: String, onName: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit, onNote: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val next by rememberUpdatedState(onNext)
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            onName(it)
            next()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Scanner") }
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("Nom du produit") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigTile("Dicter", Modifier.weight(1f), enabled = true) {
                try {
                    dictate.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nom du produit"),
                    )
                } catch (_: ActivityNotFoundException) {
                    onNote("Pas de dictée sur ce téléphone : écris le nom.")
                }
            }
            BigTile("Suivant →", Modifier.weight(1f), enabled = name.isNotBlank(), highlighted = true, onClick = onNext)
        }
    }
}
