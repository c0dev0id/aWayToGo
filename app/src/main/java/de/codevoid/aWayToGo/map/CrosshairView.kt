package de.codevoid.aWayToGo.map

import android.content.Context
import android.graphics.Canvas
import android.view.View
import androidx.core.content.ContextCompat
import de.codevoid.aWayToGo.R

/**
 * Crosshair reticle shown in panning mode, centred on the screen.
 */
class CrosshairView(context: Context) : View(context) {

    private companion object {
        const val CENTER_SIZE_DP = 50f
    }

    private val centerRadius = CENTER_SIZE_DP * context.resources.displayMetrics.density / 2f
    private val drawable = ContextCompat.getDrawable(context, R.drawable.ic_crosshair_center)

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f
        drawable?.let { d ->
            d.setBounds(
                (cx - centerRadius).toInt(), (cy - centerRadius).toInt(),
                (cx + centerRadius).toInt(), (cy + centerRadius).toInt(),
            )
            d.draw(canvas)
        }
    }
}
