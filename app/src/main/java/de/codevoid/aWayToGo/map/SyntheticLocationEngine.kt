package de.codevoid.aWayToGo.map

import android.app.PendingIntent
import android.location.Location
import android.os.Looper
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult

/**
 * A [LocationEngine] whose position is set programmatically via [pushLocation].
 *
 * Used to feed the dead-reckoned predicted position into MapLibre's
 * [org.maplibre.android.location.LocationComponent] so the GPS puck tracks
 * the same smooth position as the camera, instead of snapping on each raw fix.
 *
 * All calls to [pushLocation] must come from the main thread (Choreographer).
 * Registered callbacks are invoked synchronously on the calling thread.
 */
class SyntheticLocationEngine : LocationEngine {

    private val callbacks = mutableListOf<LocationEngineCallback<LocationEngineResult>>()
    private var lastLocation: Location? = null

    /**
     * Push a new predicted location.  All registered callbacks are notified
     * immediately on the calling thread.
     */
    fun pushLocation(location: Location) {
        lastLocation = location
        val result = LocationEngineResult.create(location)
        // Iterate over a snapshot in case a callback removes itself.
        callbacks.toList().forEach { it.onSuccess(result) }
    }

    override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
        val loc = lastLocation
        if (loc != null) callback.onSuccess(LocationEngineResult.create(loc))
        else callback.onFailure(Exception("No synthetic location yet"))
    }

    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        callback: LocationEngineCallback<LocationEngineResult>,
        looper: Looper?,
    ) {
        if (callback !in callbacks) callbacks += callback
    }

    override fun removeLocationUpdates(
        callback: LocationEngineCallback<LocationEngineResult>,
    ) {
        callbacks -= callback
    }

    // PendingIntent overloads are required by the LocationEngine interface but
    // not used by this synthetic engine — background delivery is not needed.
    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        pendingIntent: PendingIntent,
    ) {}

    override fun removeLocationUpdates(pendingIntent: PendingIntent) {}
}
