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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * Bascule clair/sombre selon la luminosité, sans clignoter : deux seuils
 * (hystérésis) et un délai pendant lequel les mesures doivent rester du même
 * côté. Commence en clair.
 */
class LuxHysteresis(
    private val darkBelow: Float = 15f,
    private val lightAbove: Float = 40f,
    private val holdMillis: Long = 8000,
) {
    private var dark = false
    private var since: Long? = null

    /** Rend vrai si le thème doit être sombre. */
    fun update(lux: Float, nowMillis: Long): Boolean {
        val wantsSwitch = if (dark) lux > lightAbove else lux < darkBelow
        if (!wantsSwitch) {
            since = null
        } else {
            val start = since ?: nowMillis.also { since = it }
            if (nowMillis - start >= holdMillis) {
                dark = !dark
                since = null
            }
        }
        return dark
    }
}

/**
 * Le thème effectif. En Auto, le capteur de lumière n'est écouté qu'au premier
 * plan ; sans capteur, on suit le thème du système.
 */
@Composable
fun rememberDark(mode: ThemeMode): Boolean {
    val system = isSystemInDarkTheme()
    if (mode != ThemeMode.Auto) return mode == ThemeMode.Sombre
    val context = LocalContext.current
    val sensors = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val light = remember { sensors.getDefaultSensor(Sensor.TYPE_LIGHT) } ?: return system

    val filter = remember { LuxHysteresis() }
    var dark by remember { mutableStateOf(false) }
    var lux by remember { mutableStateOf<Float?>(null) }

    LifecycleStartEffect(sensors) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                lux = event.values[0]
                dark = filter.update(event.values[0], SystemClock.elapsedRealtime())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, light, SensorManager.SENSOR_DELAY_NORMAL)
        onStopOrDispose { sensors.unregisterListener(listener) }
    }
    // Le capteur n'émet qu'au changement : sans cela, une mesure stable ne franchirait jamais le délai.
    LaunchedEffect(lux) {
        val l = lux ?: return@LaunchedEffect
        while (true) {
            delay(2_000)
            dark = filter.update(l, SystemClock.elapsedRealtime())
        }
    }
    return dark
}
