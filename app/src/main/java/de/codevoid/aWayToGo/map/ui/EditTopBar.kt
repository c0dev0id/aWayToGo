package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Top bar for Edit mode: [✕ DISCARD]  [trip title]  [✓ SAVE].
 *
 * @param onDiscard  Called when the user taps DISCARD (typically returns to Explore mode).
 * @param onSave     Called when the user taps SAVE (typically returns to Explore mode).
 */
fun buildEditTopBar(
    context: Context,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
): LinearLayout {
    val d    = context.resources.displayMetrics.density
    val hPad = (16 * d).toInt()
    val vPad = (8  * d).toInt()

    val discardBtn = makePillButton(context, "✕  DISCARD", onDiscard)
    val saveBtn    = makePillButton(context, "✓  SAVE",    onSave)
    val titleView  = TextView(context).apply {
        text = "New Trip"
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    return LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // Hidden by default — only shown in EDIT mode.
        visibility = View.GONE
        setBackgroundColor(Color.argb(220, 0, 80, 160))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(hPad, vPad, hPad, vPad)
        addView(discardBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(saveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }
}
