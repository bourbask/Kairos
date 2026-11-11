package app.kairos.mobile.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * Bascule clair/sombre selon la luminosité, sans clignoter : deux seuils
 * (hystérésis) et un délai pendant lequel les mesures doivent rester du même
 * côté. Sans état connu ([initialDark] nul), la première mesure tranche
 * aussitôt, au milieu des deux seuils : pas de passage transitoire par le clair.
 */
class LuxHysteresis(
    initialDark: Boolean? = null,
    private val darkBelow: Float = 15f,
    private val lightAbove: Float = 40f,
    // Assez long pour ignorer une ombre ou un passage devant une lampe, assez court pour suivre un vrai changement de pièce.
    private val holdMillis: Long = 8000,
) {
    private var dark: Boolean? = initialDark
    private var since: Long? = null

    /** Rend vrai si le thème doit être sombre. */
    fun update(lux: Float, nowMillis: Long): Boolean {
        val dark = this.dark ?: (lux < (darkBelow + lightAbove) / 2).also { this.dark = it }
        val wantsSwitch = if (dark) lux > lightAbove else lux < darkBelow
        if (!wantsSwitch) {
            since = null
        } else {
            val start = since ?: nowMillis.also { since = it }
            if (nowMillis - start >= holdMillis) {
                this.dark = !dark
                since = null
            }
        }
        return this.dark!!
    }

    /** Oublie l'attente en cours ; le thème courant reste. */
    fun reset() {
        since = null
    }
}

/**
 * Le thème effectif. En Auto, le capteur de lumière n'est écouté qu'au premier
 * plan ; sans capteur, on suit le thème du système. [mode] nul (réglages pas
 * encore lus) : thème du système, comme l'écran de démarrage.
 */
@Composable
fun rememberDark(mode: ThemeMode?): Boolean {
    val system = isSystemInDarkTheme()
    if (mode == null) return system
    if (mode != ThemeMode.Auto) return mode == ThemeMode.Sombre
    val context = LocalContext.current
    val sensors = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val light = remember { sensors.getDefaultSensor(Sensor.TYPE_LIGHT) } ?: return system

    // Conservé à la recréation de l'écran (rotation…) : le thème ne repart pas de zéro.
    var dark by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val filter = remember { LuxHysteresis(initialDark = dark) }
    val owner = LocalLifecycleOwner.current

    // Seul un changement de thème écrit un état lu par l'appelant : une mesure ne recompose rien.
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Au retour au premier plan, l'attente commencée avant n'a plus de sens.
            filter.reset()
            var lux: Float? = null
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    lux = event.values[0]
                    dark = filter.update(event.values[0], SystemClock.elapsedRealtime())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensors.registerListener(listener, light, SensorManager.SENSOR_DELAY_NORMAL)
            try {
                // Le capteur n'émet qu'au changement : sans cela, une mesure stable ne franchirait jamais le délai.
                while (true) {
                    delay(2_000)
                    lux?.let { dark = filter.update(it, SystemClock.elapsedRealtime()) }
                }
            } finally {
                sensors.unregisterListener(listener)
            }
        }
    }
    return dark ?: system
}
