package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Result of [buildNavigateOverlay].
 *
 * [root] is the full-screen FrameLayout added to the map root.
 * [banner] and [stopBtn] are stored separately so [MapActivity.animateModeTransition]
 * can address them for the slide-in / slide-out animation.
 */
data class NavigateOverlayResult(
    val root: FrameLayout,
    val banner: View,
    val stopBtn: View,
)

/**
 * Full-screen overlay for Navigate mode: green top banner + STOP button at bottom.
 *
 * @param onStop  Called when the user taps the STOP button (typically returns to Explore mode).
 */
fun buildNavigateOverlay(context: Context, onStop: () -> Unit): NavigateOverlayResult {
    val d      = context.resources.displayMetrics.density
    val hPad   = (16 * d).toInt()
    val vPad   = (12 * d).toInt()
    val margin = (16 * d).toInt()

    val banner = TextView(context).apply {
        text = "▶  NAVIGATION"
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setBackgroundColor(Color.argb(220, 0, 140, 60))
        setPadding(hPad, vPad, hPad, vPad)
    }

    val stopBtn = makePillButton(context, "■  STOP", onStop)

    val root = FrameLayout(context).apply {
        // Hidden by default — only shown in NAVIGATE mode.
        visibility = View.GONE
        addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))
        addView(stopBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, 0, 0, margin) })
    }

    return NavigateOverlayResult(root = root, banner = banner, stopBtn = stopBtn)
}
