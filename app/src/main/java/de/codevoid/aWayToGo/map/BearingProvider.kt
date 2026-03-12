package de.codevoid.aWayToGo.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Provides a smoothed compass bearing from device sensors.
 *
 * Follows the same pattern used by OsmAnd and Organic Maps: read
 * orientation from [SensorManager], apply geomagnetic declination
 * correction, and smooth the result with a low-pass filter.
 *
 * **Sensor stack:**
 * - Primary: [Sensor.TYPE_ROTATION_VECTOR] (fused sensor, best quality).
 * - Fallback: [Sensor.TYPE_ACCELEROMETER] + [Sensor.TYPE_MAGNETIC_FIELD]
 *   combined via [SensorManager.getRotationMatrix].
 *
 * **Thread safety:** sensor callbacks run on the main thread
 * ([SensorManager.SENSOR_DELAY_UI]). [bearing] is read from the
 * Choreographer callback, also on the main thread — no synchronisation
 * needed.
 */
class BearingProvider(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Current smoothed compass bearing in degrees (0 = north, clockwise). */
    var bearing = 0f
        private set

    /** `true` once the first valid sensor reading has arrived. */
    var hasBearing = false
        private set

    // Geomagnetic declination (degrees) at the user's position.
    // Updated via [updateLocation]; 0 until first GPS fix.
    private var declination = 0f

    // Low-pass filter coefficient.  Smaller = smoother / more lag.
    // 0.06 is comparable to OsmAnd's Kalman α = 0.04 in practice.
    private val alpha = 0.06f

    // ── Sensor state ────────────────────────────────────────────────────────
    private var useRotationVector = false

    private val rotationMatrix = FloatArray(9)
    private val orientation    = FloatArray(3)

    // Fallback: raw accelerometer + magnetometer buffers.
    private val accelValues = FloatArray(3)
    private val magValues   = FloatArray(3)
    private var hasAccel = false
    private var hasMag   = false

    // ── Sensor listener ─────────────────────────────────────────────────────
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> onRotationVector(event)
                Sensor.TYPE_ACCELEROMETER   -> { System.arraycopy(event.values, 0, accelValues, 0, 3); hasAccel = true; onAccelMag() }
                Sensor.TYPE_MAGNETIC_FIELD  -> { System.arraycopy(event.values, 0, magValues, 0, 3); hasMag = true; onAccelMag() }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Register sensors.  Call from [android.app.Activity.onResume]. */
    fun start() {
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            useRotationVector = true
            sensorManager.registerListener(listener, rotVec, SensorManager.SENSOR_DELAY_UI)
        } else {
            useRotationVector = false
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    /** Unregister sensors.  Call from [android.app.Activity.onPause]. */
    fun stop() {
        sensorManager.unregisterListener(listener)
        hasAccel = false
        hasMag   = false
    }

    /**
     * Update the geomagnetic declination correction for the user's current
     * position.  Call whenever a new GPS fix arrives.
     */
    fun updateLocation(lat: Double, lng: Double, alt: Double) {
        val field = GeomagneticField(
            lat.toFloat(), lng.toFloat(), alt.toFloat(), System.currentTimeMillis(),
        )
        declination = field.declination
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private fun onRotationVector(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        applyBearing(Math.toDegrees(orientation[0].toDouble()).toFloat())
    }

    private fun onAccelMag() {
        if (!hasAccel || !hasMag) return
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magValues)) return
        SensorManager.getOrientation(rotationMatrix, orientation)
        applyBearing(Math.toDegrees(orientation[0].toDouble()).toFloat())
    }

    private fun applyBearing(rawDegrees: Float) {
        // Apply geomagnetic declination → true north.
        var corrected = rawDegrees + declination
        // Normalise to [0, 360).
        corrected = ((corrected % 360f) + 360f) % 360f

        if (!hasBearing) {
            // First reading — snap immediately.
            bearing = corrected
            hasBearing = true
            return
        }

        // Low-pass filter with shortest-arc interpolation.
        var diff = corrected - bearing
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        bearing = ((bearing + alpha * diff) % 360f + 360f) % 360f
    }
}
