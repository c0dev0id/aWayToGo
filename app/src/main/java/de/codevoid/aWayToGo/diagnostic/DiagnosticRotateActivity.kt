package de.codevoid.aWayToGo.diagnostic

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Diagnostic: screen rotation lifecycle behaviour.
 *
 * Declared with the same configChanges as MapActivity so the test mirrors
 * production conditions exactly.
 *
 * How to read the display after rotating the device:
 *
 *   onCreate = 1,  onConfigChanged > 0  →  GOOD: configChanges is working.
 *                                            The Activity is NOT recreated.
 *   onCreate > 1                         →  BAD: configChanges is not in effect.
 *                                            Either the manifest attribute is
 *                                            missing or the APK has not been
 *                                            reinstalled since the change.
 *
 * Launch: adb shell am start -n de.codevoid.aWayToGo/.diagnostic.DiagnosticRotateActivity
 */
class DiagnosticRotateActivity : ComponentActivity() {

    // Counts persist via onSaveInstanceState so they accumulate correctly even
    // when the Activity IS recreated (which is the failure case we want to detect).
    private var onCreateCount = 0
    private var onConfigChangedCount = 0

    private lateinit var statusLine: TextView
    private lateinit var onCreateLine: TextView
    private lateinit var onConfigLine: TextView
    private lateinit var orientationLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore accumulated counts when recreated (the BAD path).
        if (savedInstanceState != null) {
            onCreateCount       = savedInstanceState.getInt("onCreateCount", 0)
            onConfigChangedCount = savedInstanceState.getInt("onConfigChangedCount", 0)
        }
        onCreateCount++

        val d   = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(255, 16, 16, 16))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text      = "Rotation Diagnostic"
            textSize  = 24f
            typeface  = Typeface.DEFAULT_BOLD
            gravity   = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, (24 * d).toInt())
        }

        statusLine = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity  = Gravity.CENTER
            setPadding(0, 0, 0, (20 * d).toInt())
        }

        onCreateLine = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.MONOSPACE
            gravity  = Gravity.CENTER
        }

        onConfigLine = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.MONOSPACE
            gravity  = Gravity.CENTER
        }

        orientationLine = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.MONOSPACE
            gravity  = Gravity.CENTER
            setTextColor(Color.argb(180, 200, 200, 200))
            setPadding(0, (16 * d).toInt(), 0, 0)
        }

        val hint = TextView(this).apply {
            text     = "Rotate the screen and watch the counters."
            textSize = 14f
            gravity  = Gravity.CENTER
            setTextColor(Color.argb(120, 200, 200, 200))
            setPadding(0, (32 * d).toInt(), 0, 0)
        }

        layout.addView(title)
        layout.addView(statusLine)
        layout.addView(onCreateLine)
        layout.addView(onConfigLine)
        layout.addView(orientationLine)
        layout.addView(hint)

        root.addView(
            layout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        setContentView(root)
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onConfigChangedCount++
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("onCreateCount",        onCreateCount)
        outState.putInt("onConfigChangedCount", onConfigChangedCount)
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private fun render() {
        val working = onCreateCount == 1

        statusLine.apply {
            text = if (working) "✓  configChanges working" else "✗  Activity recreated"
            setTextColor(if (working) Color.GREEN else Color.RED)
        }

        onCreateLine.apply {
            text = "onCreate          $onCreateCount"
            // Stays GREEN at 1; turns RED if it climbs.
            setTextColor(if (onCreateCount == 1) Color.GREEN else Color.RED)
        }

        onConfigLine.apply {
            text = "onConfigChanged   $onConfigChangedCount"
            // YELLOW until first rotation, then GREEN.
            setTextColor(
                when {
                    onConfigChangedCount == 0 -> Color.YELLOW
                    working                   -> Color.GREEN
                    else                      -> Color.YELLOW   // increments but so did onCreate
                }
            )
        }

        val ori = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            Configuration.ORIENTATION_PORTRAIT  -> "PORTRAIT"
            else                                -> "UNDEFINED"
        }
        orientationLine.text = "orientation  $ori"
    }
}
