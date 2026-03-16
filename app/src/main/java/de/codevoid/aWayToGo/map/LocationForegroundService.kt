package de.codevoid.aWayToGo.map

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import de.codevoid.aWayToGo.R

/**
 * Minimal foreground service that satisfies Android 14's requirement for
 * FOREGROUND_SERVICE_LOCATION permission when accessing GPS updates.
 *
 * Without a foreground service with foregroundServiceType="location", future
 * OS power-manager policy changes could throttle GPS even when the app is the
 * device launcher — because launcher foreground state is tracked by the window
 * manager, not the service manager.
 *
 * MapActivity.onResume() starts this service; onDestroy() stops it.
 * GPS registration itself remains on the Activity (via FusedLocationProviderClient)
 * because the Activity lifecycle already matches the GPS-needed lifecycle.
 */
class LocationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "location_fg"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Navigation",
                    NotificationManager.IMPORTANCE_LOW,   // silent; no badge or sound
                ).apply { setShowBadge(false) },
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Navigation active")
            .setSmallIcon(R.drawable.ic_my_location)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}
