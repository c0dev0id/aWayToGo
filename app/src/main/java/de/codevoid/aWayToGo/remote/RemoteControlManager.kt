package de.codevoid.aWayToGo.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Manages the DMD remote control BroadcastReceiver.
 *
 * Register in Activity.onResume, unregister in Activity.onPause.
 * Observe [events] to react to remote input.
 *
 * Long press is detected for [LONG_PRESS_KEYS] by measuring the duration
 * between key_press and key_release. All other keys always emit ShortPress.
 */
class RemoteControlManager(private val context: Context) {

    companion object {
        private const val ACTION = "com.thorkracing.wireddevices.keypress"
        private const val LONG_PRESS_THRESHOLD_MS = 500L
        private val LONG_PRESS_KEYS = setOf(RemoteKey.CONFIRM, RemoteKey.BACK)
    }

    private val _events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RemoteEvent> = _events.asSharedFlow()

    // Tracks the timestamp of key_press for long-press candidates
    private val pressTimestamps = mutableMapOf<RemoteKey, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return

            when {
                intent.hasExtra("key_press") -> onKeyPress(
                    keyCode = intent.getIntExtra("key_press", 0),
                )
                intent.hasExtra("key_release") -> onKeyRelease(
                    keyCode = intent.getIntExtra("key_release", 0),
                )
            }
        }
    }

    private fun onKeyPress(keyCode: Int) {
        val key = RemoteKey.fromKeyCode(keyCode) ?: return
        if (key in LONG_PRESS_KEYS) {
            pressTimestamps[key] = System.currentTimeMillis()
        }
        _events.tryEmit(RemoteEvent.KeyDown(key))
    }

    private fun onKeyRelease(keyCode: Int) {
        val key = RemoteKey.fromKeyCode(keyCode) ?: return
        _events.tryEmit(RemoteEvent.KeyUp(key))

        val pressTime = pressTimestamps.remove(key)
        val isLongPress = pressTime != null &&
            key in LONG_PRESS_KEYS &&
            (System.currentTimeMillis() - pressTime) >= LONG_PRESS_THRESHOLD_MS

        _events.tryEmit(
            if (isLongPress) RemoteEvent.LongPress(key)
            else RemoteEvent.ShortPress(key)
        )
    }

    fun register() {
        val filter = IntentFilter(ACTION)
        // RECEIVER_EXPORTED is required because the DMD device sends broadcasts from a
        // separate app. ContextCompat handles the flag correctly across all API levels.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }
}
