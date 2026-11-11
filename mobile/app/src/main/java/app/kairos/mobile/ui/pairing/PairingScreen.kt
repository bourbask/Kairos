package app.kairos.mobile.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kairos.mobile.data.Credentials
import app.kairos.mobile.data.Pairing
import app.kairos.mobile.ui.mascot.Mascot
import app.kairos.mobile.ui.mascot.onPageColors
import app.kairos.mobile.ui.mascot.MascotState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.launch

/**
 * Premier lancement, ou appareil révoqué : scanner le QR code affiché par
 * `tete pair`, ou coller l'adresse `kairos://pair?…`.
 * [onSubmit] vérifie auprès du serveur et rend un message d'erreur, ou null si c'est bon.
 */
@Composable
fun PairingScreen(message: String?, onSubmit: suspend (Credentials) -> String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(message) }
    var busy by remember { mutableStateOf(false) }
    var lastScanned by remember { mutableStateOf<String?>(null) }
    var camera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        camera = granted
        if (!granted) error = "Sans caméra, colle l'adresse d'appairage ci-dessous."
    }

    fun submit(raw: String) {
        if (busy) return
        val creds = Pairing.parse(raw).getOrElse {
            error = it.message ?: "Code d'appairage illisible."
            return
        }
        busy = true
        error = null
        scope.launch {
            error = onSubmit(creds)
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Mascot(if (busy) MascotState.Reflechit else MascotState.Attend, size = 88.dp, colors = onPageColors())
        Text("Appairer Kairos", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sur le serveur, lance « tete pair <nom> » puis scanne le QR code affiché.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (camera) {
                QrScanner { code ->
                    if (code != lastScanned) {
                        lastScanned = code
                        text = code // « Appairer » permet de réessayer le même code
                        submit(code)
                    }
                }
            } else {
                OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                    Text("Scanner le QR code")
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(48.dp))
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("ou colle l'adresse kairos://pair…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { submit(text) }, enabled = !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Appairer")
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

/** Aperçu caméra ; chaque QR code lu est remis à [onCode] (lecture sur l'appareil, rien ne sort). */
@Composable
private fun QrScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onCode)
    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(owner) {
        // Créé ici : chaque passage de l'effet a son propre lecteur, fermé à sa sortie.
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        val executor = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                result.getValue(scanner)?.firstNotNullOfOrNull { it.rawValue }?.let { callback(it) }
            },
        )
        controller.bindToLifecycle(owner)
        onDispose {
            controller.unbind()
            scanner.close()
        }
    }
    AndroidView(
        factory = { PreviewView(it).apply { this.controller = controller } },
        modifier = Modifier.fillMaxSize(),
    )
}
