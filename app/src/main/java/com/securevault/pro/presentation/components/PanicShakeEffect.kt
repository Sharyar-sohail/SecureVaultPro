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

        var firstPeakAt = 0L
        var lastTriggerAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0] / SensorManager.GRAVITY_EARTH
                val gy = event.values[1] / SensorManager.GRAVITY_EARTH
                val gz = event.values[2] / SensorManager.GRAVITY_EARTH
                val force = sqrt(gx * gx + gy * gy + gz * gz)
                if (force < 2.6f) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastTriggerAt < 2_500L) return
                if (firstPeakAt > 0L && now - firstPeakAt in 90L..750L) {
                    firstPeakAt = 0L
                    lastTriggerAt = now
                    currentOnShake()
                } else {
                    firstPeakAt = now
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }
}
