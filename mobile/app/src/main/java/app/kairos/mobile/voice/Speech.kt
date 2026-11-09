package app.kairos.mobile.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Reconnaissance vocale du système, en français, sur l'appareil quand c'est
 * possible. À utiliser depuis le fil principal.
 * [onError] : `retry` = rien entendu (on peut relancer) ; sinon échec durable.
 */
class Speech(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (retry: Boolean) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val available: Boolean
        get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        val r = recognizer ?: create().also { recognizer = it }
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
    }

    /** Termine l'écoute ; le texte définitif arrive par [onFinal]. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.cancel()
        onLevel(0f)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer {
        val r = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(Listener())
        return r
    }

    private inner class Listener : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            first(partialResults)?.let(onPartial)
        }

        override fun onResults(results: Bundle) {
            onLevel(0f)
            onFinal(first(results).orEmpty())
        }

        override fun onError(error: Int) {
            onLevel(0f)
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                -> if (onDevice) {
                    // Pas de modèle français sur l'appareil : moteur du système, hors ligne si possible.
                    onDevice = false
                    destroy()
                    if (SpeechRecognizer.isRecognitionAvailable(context)) start() else onError(false)
                } else {
                    onError(false)
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                -> onError(true)
                else -> onError(false)
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun first(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        const val LANGUAGE = "fr-FR"
    }
}
