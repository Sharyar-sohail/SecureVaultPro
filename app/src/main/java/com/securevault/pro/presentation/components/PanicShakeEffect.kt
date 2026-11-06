package com.securevault.pro.presentation.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * Locks the vault after a deliberate double-shake gesture. Two acceleration peaks above
 * 2.6 g within a short window are required so walking or bumping the phone cannot lock
 * the vault by accident.
 *
 * The two peaks are paired on `SensorEvent.timestamp` — the hardware stamp taken when the
 * accelerometer actually sampled — and not on `SystemClock.elapsedRealtime()` read at
 * delivery time. Delivery jitters by tens of milliseconds at `SENSOR_DELAY_GAME`, so the
 * old comparison could turn a real shake's 100 ms second impact into "too soon" (no lock)
 * or smear one long rumble's irregular train into a false double-peak (a phantom lock in
 * a pocket). The elapsed-realtime clock still drives `RE_ARM_MS`, which is about the app's
 * own state between bursts rather than about a sample, and it is never mixed with the
 * hardware clock because the two do not share an epoch.
 */
@Composable
fun PanicShakeEffect(
    enabled: Boolean,
    onShake: () -> Unit
) {
    val context = LocalContext.current
    val currentOnShake by rememberUpdatedState(onShake)

    DisposableEffect(enabled, context) {
        if (!enabled) return@DisposableEffect onDispose { }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || accelerometer == null) return@DisposableEffect onDispose { }

        var firstPeakAtNs = 0L
        var firstPeakFromSensor = false
        var lastTriggerAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0] / SensorManager.GRAVITY_EARTH
                val gy = event.values[1] / SensorManager.GRAVITY_EARTH
                val gz = event.values[2] / SensorManager.GRAVITY_EARTH
                val force = sqrt(gx * gx + gy * gy + gz * gz)
                if (force < SHAKE_THRESHOLD_G) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastTriggerAt < RE_ARM_MS) return
                // Some virtual/older HALs report 0; pairing across clock epochs is meaningless,
                // so such a sample only re-arms the gesture instead of completing it.
                val fromSensor = event.timestamp > 0L
                val sampleAtNs = if (fromSensor) event.timestamp else now * 1_000_000L
                val gapMs =
                    if (firstPeakAtNs > 0L && firstPeakFromSensor == fromSensor)
                        (sampleAtNs - firstPeakAtNs) / 1_000_000L
                    else -1L
                if (gapMs in MIN_BURST_GAP_MS..MAX_BURST_GAP_MS) {
                    firstPeakAtNs = 0L
                    lastTriggerAt = now
                    currentOnShake()
                } else {
                    firstPeakAtNs = sampleAtNs
                    firstPeakFromSensor = fromSensor
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // Batching is safe here only because the burst window is measured on hardware stamps:
        // the HAL may hold samples for MAX_BATCH_LATENCY_MS, but their timestamps are exact, so
        // the gesture is unchanged while the CPU wakes far less often with the screen off.
        sensorManager.registerListener(
            listener,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME,
            MAX_BATCH_LATENCY_MS
        )
        onDispose { sensorManager.unregisterListener(listener) }
    }
}

private const val SHAKE_THRESHOLD_G = 2.6f

/** The second impact must land inside one arm swing of the first: neither a tap nor a rumble. */
private const val MIN_BURST_GAP_MS = 90L
private const val MAX_BURST_GAP_MS = 750L

/** After a lock, ignore further peaks for this long so a single shake cannot retrigger it. */
private const val RE_ARM_MS = 2_500L

/** Delivery slack allowed for hardware batching, in milliseconds; well under the 90 ms floor. */
private const val MAX_BATCH_LATENCY_MS = 20
