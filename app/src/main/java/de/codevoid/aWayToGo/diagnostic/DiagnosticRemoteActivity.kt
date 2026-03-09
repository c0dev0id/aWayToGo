package de.codevoid.aWayToGo.diagnostic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sniffs every broadcast under the com.thorkracing.wireddevices.* namespace and
 * displays the full intent payload on screen.
 *
 * Android does not support wildcard intent filters — action matching is always
 * exact.  We work around this by registering a single BroadcastReceiver with one
 * IntentFilter that lists every known and plausible action name.  Any action NOT
 * in [ACTIONS] will silently be missed; add it to the list and rebuild if needed.
 *
 * Launch:
 *   adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticRemoteActivity
 * Or:
 *   make diag-remote
 */
class DiagnosticRemoteActivity : ComponentActivity() {

    // ── Constants ──────────────────────────────────────────────────────────────

    private companion object {
        const val MAX_LOG_ENTRIES = 60
        const val PREFIX = "com.thorkracing.wireddevices"

        /**
         * All confirmed actions under [PREFIX], verified by grepping the
         * decompiled DMD2 APK (smali bytecode under com/thorkracing/).
         *
         * keypress  — remote control buttons; extras: key_press (int),
         *             key_release (int), joy (String), deviceName (String)
         * sensor    — vehicle OBD data; extras: gear, rpm, engine_temp,
         *             amb_temp (int), tpms_front, tpms_rear (float),
         *             fuel_level, tb_position (int).
         *             May also carry 360° heading — to be confirmed by this tool.
         * screenlock — UI lock state; extras: state (boolean)
         * actions    — UI commands; extras: action (int, values 14–29 + 1000/1001)
         */
        val ACTIONS = listOf(
            "$PREFIX.keypress",
            "$PREFIX.sensor",
            "$PREFIX.screenlock",
            "$PREFIX.actions",
        )
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private val logEntries = ArrayDeque<String>(MAX_LOG_ENTRIES + 1)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // ── Receiver ───────────────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val time   = timeFmt.format(Date())
            val action = intent.action ?: "(null)"
            val shortAction = action.removePrefix("$PREFIX.")

            val sb = StringBuilder()
            sb.append("▶  $shortAction   [$time]\n")

            val extras = intent.extras
            if (extras == null || extras.isEmpty) {
                sb.append("   (no extras)\n")
            } else {
                for (key in extras.keySet().sorted()) {
                    val value    = extras.get(key)
                    val typeName = value?.javaClass?.simpleName ?: "null"
                    sb.append("   $key  ($typeName)\n")
                    sb.append("   → $value\n")
                }
            }

            logEntries.addFirst(sb.toString())
            while (logEntries.size > MAX_LOG_ENTRIES) logEntries.removeLast()

            val divider = "─".repeat(36) + "\n"
            logView.text = logEntries.joinToString(divider)

            // Keep newest entry visible at the top.
            scrollView.post { scrollView.scrollTo(0, 0) }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Header — shows which namespace we're watching and how many actions.
        root.addView(
            TextView(this).apply {
                text = "Intent sniffer: $PREFIX.*\n" +
                       "Registered for ${ACTIONS.size} actions — waiting…"
                setTextColor(Color.YELLOW)
                textSize   = 12f
                typeface   = Typeface.MONOSPACE
                setBackgroundColor(Color.argb(220, 20, 20, 20))
                setPadding(20, 12, 20, 12)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        )

        // Log area — scrollable, newest entry at top.
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(20, 12, 20, 12)
            text = "(no broadcasts received yet)"
        }

        scrollView = ScrollView(this).apply { addView(logView) }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply { ACTIONS.forEach { addAction(it) } }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Was not registered — harmless.
        }
        super.onPause()
    }
}
