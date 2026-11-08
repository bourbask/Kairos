package app.kairos.mobile.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Lecture à voix haute par la synthèse du système, en français ; moteur démarré au premier usage. */
class Tts(private val context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = false
    private val queue = mutableListOf<String>()

    fun speak(text: String) {
        val clean = text.replace(MARKUP, "").trim()
        if (clean.isEmpty()) return
        if (ready) {
            say(clean)
            return
        }
        queue += clean
        if (engine == null) {
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    queue.clear()
                    return@TextToSpeech
                }
                engine?.language = Locale.FRANCE
                ready = true
                queue.forEach(::say)
                queue.clear()
            }
        }
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun say(text: String) {
        engine?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    private companion object {
        /** Mise en forme Markdown, qui ne se prononce pas. */
        val MARKUP = Regex("[*_#`>|]")
    }
}
