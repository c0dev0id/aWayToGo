package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * Circular icon button with dark semi-transparent background and ripple.
 *
 * Matches the visual style of the my-location button: 64dp circle,
 * 12dp internal padding, white ripple.
 */
fun makeCircleButton(context: Context, iconRes: Int, onClick: () -> Unit): ImageView {
    val d   = context.resources.displayMetrics.density
    val pad = (12 * d).toInt()
    return ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, iconRes))
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(180, 0, 0, 0)) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
        )
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

/**
 * Pill-shaped text button with dark semi-transparent background and ripple.
 *
 * Width and height are set by the caller via LayoutParams.
 */
fun makePillButton(context: Context, label: String, onClick: () -> Unit): TextView {
    val d      = context.resources.displayMetrics.density
    val hPad   = (20 * d).toInt()
    val vPad   = (10 * d).toInt()
    val radius = 24 * d
    return TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.argb(180, 0, 0, 0))
            },
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(Color.WHITE)
            },
        )
        setPadding(hPad, vPad, hPad, vPad)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}
