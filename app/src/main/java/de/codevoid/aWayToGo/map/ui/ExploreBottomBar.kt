package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import de.codevoid.aWayToGo.R

/**
 * Explore mode action bar: two half-pill buttons flanking a large central search
 * circle, with a clean 2dp gap. Each button's inner edge is a concave arc that
 * follows the circle outline exactly.
 *
 * Layout (z-order: row behind, circle on top):
 *
 *       ╭──────╮           ╭──────╮
 *       │ RIDE )           ( Plan │  ← concave inner edge, 2dp gap from circle
 *       ╰──────╯           ╰──────╯
 *                ╔══════╗
 *                ║  🔍  ║  ← circle, sits cleanly between the two buttons
 *                ║Search║
 *                ╚══════╝
 *
 * Implemented via Path.Op.DIFFERENCE: half-pill base shape (addRoundRect) minus
 * a circle enlarged by 2dp (the gap) punched out of the inner edge.
 *
 * @param onRide  Called when the user taps the Ride button.
 * @param onPlan  Called when the user taps the Plan button.
 */
fun buildExploreBottomBar(
    context: Context,
    onRide: () -> Unit,
    onSearch: () -> Unit,
    onPlan: () -> Unit,
): FrameLayout {
    val d          = context.resources.displayMetrics.density
    val circleSize = (144 * d).toInt()
    val btnH       = (64 * d).toInt()
    val btnW       = (172 * d).toInt()
    val overlap    = (44 * d).toInt()
    val spacerW    = (circleSize - overlap * 2).coerceAtLeast(0)
    val outerR     = btnH / 2f
    val cutoutR    = circleSize / 2f + (2 * d)   // circle radius + 2dp gap

    // Circle centre in row-local coordinates.
    // Both the row and the circle are centred (Gravity.CENTER) in the FrameLayout,
    // so the circle centre x = rowWidth / 2.
    val rowWidth     = 2f * btnW + spacerW
    val circleCX_row = rowWidth / 2f
    val circleCY     = btnH / 2f   // circle and buttons share the same vertical centre

    // Minimal path-backed solid-colour Drawable (GradientDrawable can't do concave arcs).
    fun pathDrawable(path: Path, color: Int): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        }
        override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)
        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // Half-pill outline with a circular notch on the inner edge (Path.Op.DIFFERENCE).
    fun makeButtonPath(roundLeft: Boolean): Path {
        // planBtn starts further right in the row, so its circle centre is negative in local x.
        val btnLeft = if (roundLeft) 0f else (btnW + spacerW).toFloat()
        val cx = circleCX_row - btnLeft   // circle centre x in button-local coords
        val cy = circleCY
        val radii = if (roundLeft)
            floatArrayOf(outerR, outerR, 0f, 0f, 0f, 0f, outerR, outerR)
        else
            floatArrayOf(0f, 0f, outerR, outerR, outerR, outerR, 0f, 0f)
        return Path().apply {
            addRoundRect(RectF(0f, 0f, btnW.toFloat(), btnH.toFloat()), radii, Path.Direction.CW)
            val circle = Path()
            circle.addCircle(cx, cy, cutoutR, Path.Direction.CW)
            op(circle, Path.Op.DIFFERENCE)
        }
    }

    fun makeHalfPill(label: String, roundLeft: Boolean, onClick: () -> Unit): TextView {
        val path = makeButtonPath(roundLeft)
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
                pathDrawable(path, Color.argb(180, 0, 0, 0)),
                pathDrawable(path, Color.WHITE),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    val rideBtn = makeHalfPill("Ride", roundLeft = true,  onClick = onRide)
    val planBtn = makeHalfPill("Plan", roundLeft = false, onClick = onPlan)

    // Search circle: icon above label, larger than the side buttons.
    val iconPad  = (16 * d).toInt()
    val iconSize = (44 * d).toInt()
    val searchBtn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity     = Gravity.CENTER
        background  = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(210, 0, 0, 0)) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
        )
        setPadding(iconPad, iconPad, iconPad, iconPad)
        addView(
            ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_search))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(iconSize, iconSize),
        )
        addView(TextView(context).apply {
            text = "Search"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        })
        isClickable = true
        isFocusable = true
        setOnClickListener { onSearch() }
    }

    // Row sits behind the circle (added first → lower z-order).
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        addView(rideBtn, LinearLayout.LayoutParams(btnW, btnH))
        addView(View(context), LinearLayout.LayoutParams(spacerW, btnH))
        addView(planBtn, LinearLayout.LayoutParams(btnW, btnH))
    }

    return FrameLayout(context).apply {
        addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        // Circle added second → drawn on top, covers inner edges of both buttons.
        addView(searchBtn, FrameLayout.LayoutParams(
            circleSize, circleSize, Gravity.CENTER,
        ))
    }
}
