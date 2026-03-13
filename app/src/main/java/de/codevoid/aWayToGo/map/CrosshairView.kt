package de.codevoid.aWayToGo.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import de.codevoid.aWayToGo.R

/**
 * Crosshair reticle shown in panning mode, centred on the screen.
 *
 * Optionally draws an animated lock-ring arc around the crosshair when
 * [lockRingSweep] is non-zero (0 = no ring, 360 = full circle).  The arc is
 * drawn before the crosshair so the crosshair icon remains fully visible.
 *
 * The ring is driven externally by [MapActivity]'s lock-ring animator:
 *   - Animation begins 50 ms after long-press starts.
 *   - Arc grows from 0° to 360° over 450 ms (total elapsed: 50–500 ms).
 *   - At 500 ms the long-press fires and the map-lock menu opens.
 *   - Resetting [lockRingSweep] to 0f when the menu closes hides the ring.
 */
class CrosshairView(context: Context) : View(context) {

    private companion object {
        const val CENTER_SIZE_DP     = 50f
        const val LOCK_RING_RADIUS_DP = 40f  // slightly outside the 25 dp crosshair radius
        const val LOCK_RING_STROKE_DP =  3f
    }

    private val density      = context.resources.displayMetrics.density
    private val centerRadius = CENTER_SIZE_DP * density / 2f
    private val drawable     = ContextCompat.getDrawable(context, R.drawable.ic_crosshair_center)

    // ── Lock ring ─────────────────────────────────────────────────────────────

    private val lockRingRadius = LOCK_RING_RADIUS_DP * density
    private val lockRingStroke = LOCK_RING_STROKE_DP * density
    private val lockRingOval   = RectF()

    private val lockRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        color     = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Arc sweep in degrees [0, 360].  Setting this to a non-zero value causes
     * the lock ring to be drawn; call [invalidate] after each change, or use
     * the property setter which calls it automatically.
     */
    var lockRingSweep: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        lockRingOval.set(
            cx - lockRingRadius, cy - lockRingRadius,
            cx + lockRingRadius, cy + lockRingRadius,
        )
        lockRingPaint.strokeWidth = lockRingStroke
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f

        // Draw the lock ring arc first so it renders behind the crosshair icon.
        if (lockRingSweep > 0f) {
            canvas.drawArc(lockRingOval, -90f, lockRingSweep, false, lockRingPaint)
        }

        drawable?.let { d ->
            d.setBounds(
                (cx - centerRadius).toInt(), (cy - centerRadius).toInt(),
                (cx + centerRadius).toInt(), (cy + centerRadius).toInt(),
            )
            d.draw(canvas)
        }
    }
}
