package de.codevoid.aWayToGo.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import androidx.core.content.ContextCompat
import de.codevoid.aWayToGo.R

/**
 * Full-screen crosshair overlay shown in panning mode.
 *
 * Four gradient arms extend from the centre of the screen — each arm fades
 * from solid red at the reticle edge to fully transparent at [ARM_LENGTH_DP].
 * The [ARM_LENGTH_DP] value intentionally exceeds typical screen half-widths so
 * the fade completes before the screen boundary regardless of device size, with
 * no hard line at the edge.
 *
 * A circular reticle ([R.drawable.ic_crosshair_center]) is drawn on top of the
 * arm intersection to clearly mark the target point.
 */
class CrosshairView(context: Context) : View(context) {

    private companion object {
        const val ARM_LENGTH_DP   = 350f  // fade ends here; exceeds most screen half-widths
        const val ARM_THICKNESS_DP =  6f
        const val CENTER_SIZE_DP  = 100f  // diameter of the centre reticle drawable
    }

    private val density       = context.resources.displayMetrics.density
    private val armLength     = ARM_LENGTH_DP    * density
    private val armThickness  = ARM_THICKNESS_DP * density
    private val centerRadius  = CENTER_SIZE_DP   * density / 2f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = armThickness
        strokeCap   = Paint.Cap.BUTT
        style       = Paint.Style.STROKE
    }

    private val centerDrawable = ContextCompat.getDrawable(context, R.drawable.ic_crosshair_center)

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f

        // Four arms: each LinearGradient runs from the arm origin (full red) to
        // the tip (transparent), giving a natural fade without a visible edge.
        drawArm(canvas, cx, cy, cx + armLength, cy)   // right
        drawArm(canvas, cx, cy, cx - armLength, cy)   // left
        drawArm(canvas, cx, cy, cx, cy + armLength)   // down
        drawArm(canvas, cx, cy, cx, cy - armLength)   // up

        // Centre reticle drawn last so it sits on top of the arm intersection.
        centerDrawable?.let { d ->
            d.setBounds(
                (cx - centerRadius).toInt(), (cy - centerRadius).toInt(),
                (cx + centerRadius).toInt(), (cy + centerRadius).toInt(),
            )
            d.draw(canvas)
        }
    }

    private fun drawArm(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        paint.shader = LinearGradient(
            x0, y0, x1, y1,
            Color.RED, Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawLine(x0, y0, x1, y1, paint)
    }
}
