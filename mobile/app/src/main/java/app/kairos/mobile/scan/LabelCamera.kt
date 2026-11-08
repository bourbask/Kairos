package app.kairos.mobile.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.interfaces.Detector
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Texte lu en continu sur l'étiquette visée (reconnaissance sur l'appareil,
 * rien ne sort). [why] explique l'usage de la caméra avant de la demander.
 */
@Composable
fun LabelCamera(why: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    CameraGate(why, modifier) {
        VisionCamera({ TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }) { onText(it.text) }
    }
}

/** Codes-barres de produits (EAN, UPC) lus en continu ; chaque lecture est remise à [onCode]. */
@Composable
fun BarcodeCamera(why: String, onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    CameraGate(why, modifier) {
        VisionCamera({
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                    .build(),
            )
        }) { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onCode) }
    }
}

/**
 * Caméra demandée au premier usage, avec sa raison. Refusée : le reste de
 * l'écran (saisie de la date, du nom) suffit.
 */
@Composable
private fun CameraGate(why: String, modifier: Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        refused = !it
    }
    // Retour des réglages du système : l'autorisation a pu changer.
    LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose {}
    }
    Box(
        modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            content()
        } else {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (refused) "Caméra refusée : continue sans, plus bas." else why,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                    Text(if (refused) "Réessayer la caméra" else "Autoriser la caméra")
                }
                // Refus définitif : le système ne redemande plus, seuls ses réglages le peuvent.
                if (refused) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("Ouvrir les réglages") }
                }
            }
        }
    }
}

/** Aperçu caméra et analyse ML Kit des images ; un détecteur neuf par passage, fermé à la sortie. */
@Composable
private fun <T> VisionCamera(detector: () -> Detector<T>, onResult: (T) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onResult)
    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(owner) {
        val d = detector()
        val executor = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(
            executor,
            MlKitAnalyzer(listOf(d), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                result.getValue(d)?.let { callback(it) }
            },
        )
        controller.bindToLifecycle(owner)
        onDispose {
            controller.unbind()
            d.close()
        }
    }
    AndroidView(
        factory = { PreviewView(it).apply { this.controller = controller } },
        modifier = Modifier.fillMaxSize(),
    )
}
