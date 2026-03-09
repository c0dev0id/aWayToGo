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
                // Analog joystick takes priority — joy and key_press are mutually
                // exclusive in the same broadcast; prefer analog when present.
                intent.hasExtra("joy") -> onJoy(
                    value = intent.getStringExtra("joy") ?: "Y0X0",
                )
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

    private fun onJoy(value: String) {
        val (dx, dy) = parseJoy(value)
        _events.tryEmit(RemoteEvent.JoyInput(dx, dy))
    }

    /**
     * Parse a DMD joystick string into normalised (dx, dy) in [-1, 1].
     *
     * Format: optional vertical component (U|D + digit 2–5) followed by
     * optional horizontal component (L|R + digit 2–5).  Neutral strings
     * "Y0X0", "Y0", "X0" map to (0, 0).  Magnitude digit maps linearly:
     *   2 → 0.4,  3 → 0.6,  4 → 0.8,  5 → 1.0  (i.e. digit / 5f).
     *
     * Positive dy = joystick pushed up (towards screen top).
     * Positive dx = joystick pushed right.
     */
    private fun parseJoy(value: String): Pair<Float, Float> {
        if (value == "Y0X0" || value == "Y0" || value == "X0") return 0f to 0f

        fun magnitudeAfter(prefix: Char): Float {
            val idx = value.indexOf(prefix)
            if (idx < 0) return 0f
            val digit = value.getOrNull(idx + 1)?.digitToIntOrNull() ?: return 0f
            return digit / 5f
        }

        val dy = magnitudeAfter('U') - magnitudeAfter('D')
        val dx = magnitudeAfter('R') - magnitudeAfter('L')
        return dx to dy
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
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered — harmless, treat unregister as idempotent.
        }
    }
}
