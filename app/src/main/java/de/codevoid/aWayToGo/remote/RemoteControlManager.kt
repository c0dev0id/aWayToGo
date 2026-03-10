package de.codevoid.aWayToGo.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        private const val TAG = "RemoteControl"
        private const val ACTION = "com.thorkracing.wireddevices.keypress"
        private const val LONG_PRESS_THRESHOLD_MS = 500L
        private val LONG_PRESS_KEYS = setOf(RemoteKey.CONFIRM, RemoteKey.BACK)
    }

    private val _events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RemoteEvent> = _events.asSharedFlow()

    // Handler for scheduling the eager long-press timer on the main thread.
    private val handler = Handler(Looper.getMainLooper())

    // Per-key pending long-press Runnables (cancelled on key-up if not yet fired).
    private val pendingLongPress = mutableMapOf<RemoteKey, Runnable>()

    // Keys for which the long-press timer has already fired this press cycle.
    private val longPressEmitted = mutableSetOf<RemoteKey>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return

            val extras = intent.extras?.keySet()?.joinToString() ?: "(none)"
            Log.d(TAG, "broadcast received — extras: $extras")

            when {
                // Analog joystick takes priority — joy and key_press are mutually
                // exclusive in the same broadcast; prefer analog when present.
                intent.hasExtra("joy") -> {
                    val value = intent.getStringExtra("joy") ?: "Y0X0"
                    Log.d(TAG, "joy=$value")
                    onJoy(value)
                }
                intent.hasExtra("key_press") -> {
                    val code = intent.getIntExtra("key_press", 0)
                    Log.d(TAG, "key_press=$code → ${RemoteKey.fromKeyCode(code)}")
                    onKeyPress(code)
                }
                intent.hasExtra("key_release") -> {
                    val code = intent.getIntExtra("key_release", 0)
                    Log.d(TAG, "key_release=$code → ${RemoteKey.fromKeyCode(code)}")
                    onKeyRelease(code)
                }
                else -> Log.w(TAG, "broadcast with no recognised extra — extras: $extras")
            }
        }
    }

    private fun onKeyPress(keyCode: Int) {
        val key = RemoteKey.fromKeyCode(keyCode) ?: return
        Log.d(TAG, "key_press=$keyCode → $key")
        _events.tryEmit(RemoteEvent.KeyDown(key))
        if (key in LONG_PRESS_KEYS) {
            // Schedule the LongPress event to fire after the threshold while the
            // key is still held — no need to wait for key_release.
            val runnable = Runnable {
                Log.d(TAG, "long_press fired for $key (held >= ${LONG_PRESS_THRESHOLD_MS}ms)")
                longPressEmitted += key
                _events.tryEmit(RemoteEvent.LongPress(key))
            }
            pendingLongPress[key] = runnable
            handler.postDelayed(runnable, LONG_PRESS_THRESHOLD_MS)
        }
    }

    private fun onKeyRelease(keyCode: Int) {
        val key = RemoteKey.fromKeyCode(keyCode) ?: return
        Log.d(TAG, "key_release=$keyCode → $key")
        _events.tryEmit(RemoteEvent.KeyUp(key))

        // Cancel the pending timer if the key was released before it fired.
        pendingLongPress.remove(key)?.let { handler.removeCallbacks(it) }

        // Emit ShortPress only if the long-press timer did NOT already fire.
        if (key !in longPressEmitted) {
            _events.tryEmit(RemoteEvent.ShortPress(key))
        } else {
            longPressEmitted -= key
        }
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
        Log.d(TAG, "receiver registered for $ACTION")
    }

    fun unregister() {
        // Cancel any in-flight long-press timers so they don't fire after unregister.
        for ((_, runnable) in pendingLongPress) handler.removeCallbacks(runnable)
        pendingLongPress.clear()
        longPressEmitted.clear()
        try {
            context.unregisterReceiver(receiver)
            Log.d(TAG, "receiver unregistered")
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered — harmless, treat unregister as idempotent.
        }
    }
}
