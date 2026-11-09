package app.kairos.mobile.voice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Dictée par la reconnaissance vocale du téléphone, en français, sur l'appareil quand
 * elle est disponible. Rend l'action qui lance la dictée ; le texte reconnu arrive par
 * [onText]. Sans reconnaissance, [missing] est passé à [onNote] : le clavier reste.
 */
@Composable
fun rememberDictation(prompt: String, missing: String, onText: (String) -> Unit, onNote: (String) -> Unit): () -> Unit {
    val text by rememberUpdatedState(onText)
    val note by rememberUpdatedState(onNote)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(text)
    }
    return {
        try {
            launcher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Speech.LANGUAGE)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
            )
        } catch (_: ActivityNotFoundException) {
            note(missing)
        }
    }
}
