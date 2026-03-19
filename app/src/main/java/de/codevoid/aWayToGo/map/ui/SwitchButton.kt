package de.codevoid.aWayToGo.map.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat

data class SwitchEntry(val iconRes: Int, val onAction: () -> Unit)

/**
 * Horizontally expandable button strip.
 *
 * Collapsed: shows only the primary icon as a pill-shaped button.
 * Expanded (after long-press): reveals secondary icons to the right.
 * Tapping a secondary executes its action and swaps it into the primary slot.
 */
class SwitchButton(context: Context, private val entries: List<SwitchEntry>) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density
    val btnSizePx = (64 * density).toInt()

    private var primaryIndex: Int = 0
    private var isExpanded: Boolean = false
    private var expandAnimator: ValueAnimator? = null
    private var dismissOverlay: View? = null

    private val iconViews: List<ImageView>

    init {
        val radius = 32 * density
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

        val pad = (12 * density).toInt()
        iconViews = entries.mapIndexed { i, entry ->
            ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, entry.iconRes))
                setPadding(pad, pad, pad, pad)
                translationX = (i * btnSizePx).toFloat()
                alpha = if (i == 0) 1f else 0f
                visibility = if (i == 0) View.VISIBLE else View.INVISIBLE
            }.also { iv ->
                addView(iv, FrameLayout.LayoutParams(btnSizePx, btnSizePx))
            }
        }

        // Wire all gestures on the icon views, not the container.  Because the icons
        // are clickable children they consume touch events — the container's listeners
        // would never fire for touches that land on an icon.
        iconViews.forEachIndexed { i, iv ->
            // Short tap
            iv.setOnClickListener {
                if (isExpanded) {
                    if (i == primaryIndex) collapse() else onSecondaryTap(i)
                } else {
                    // Collapsed: only primary icon is reachable (secondaries are outside bounds).
                    entries[primaryIndex].onAction()
                }
            }
            // Long-press on primary when collapsed → expand
            iv.setOnLongClickListener {
                if (!isExpanded && i == primaryIndex) { expand(); true } else false
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Sync primary to app state without swap animation.
     * Call from renderUiState() after external state changes.
     */
    fun setPrimary(index: Int) {
        if (isExpanded) collapseInstant()
        primaryIndex = index
        resetIconPositions()
    }

    /** Snap to collapsed state with no animation. */
    fun collapseInstant() {
        expandAnimator?.cancel()
        expandAnimator = null
        val lp = layoutParams ?: return
        lp.width = btnSizePx
        layoutParams = lp
        removeDismissOverlay()
        isExpanded = false
        resetIconPositions()
    }

    // ── Expand / collapse ──────────────────────────────────────────────────────

    private fun expand() {
        isExpanded = true
        val targetW = entries.size * btnSizePx

        // Fade secondaries in.
        iconViews.forEachIndexed { i, iv ->
            if (i != primaryIndex) {
                iv.visibility = View.VISIBLE
                iv.animate().alpha(1f).setDuration(Anim.FAST).setInterpolator(Anim.ENTER).start()
            }
        }

        // Animate width. startW is always btnSizePx — expand() is only called when collapsed,
        // and lp.width may not yet be a pixel value (e.g. WRAP_CONTENT on first expansion).
        val lp = layoutParams
        lp.width = btnSizePx  // ensure pixel baseline
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.ENTER
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width = (btnSizePx + (targetW - btnSizePx) * t).toInt()
                layoutParams = lp
            }
            start()
        }

        // Add dismiss overlay so tapping outside collapses the button.
        addDismissOverlay()
        bringToFront()
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false

        // Fade secondaries out.
        iconViews.forEachIndexed { i, iv ->
            if (i != primaryIndex) {
                iv.animate().alpha(0f).setDuration(Anim.FAST).setInterpolator(Anim.EXIT)
                    .withEndAction { iv.visibility = View.INVISIBLE }.start()
            }
        }

        // Animate width back to a single button.
        // Cancel first so lp.width holds the last animated value.
        expandAnimator?.cancel()
        val lp = layoutParams
        val startW = if (lp.width > 0) lp.width else entries.size * btnSizePx
        expandAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.EXIT
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width = (startW + (btnSizePx - startW) * t).toInt()
                layoutParams = lp
            }
            start()
        }

        removeDismissOverlay()
    }

    private fun onSecondaryTap(i: Int) {
        entries[i].onAction()

        val prevPrimary = primaryIndex

        // Swap animation: selected icon slides to slot 0, old primary fades right.
        iconViews[i].animate()
            .translationX(0f)
            .setDuration(Anim.FAST).setInterpolator(Anim.ENTER).start()
        iconViews[prevPrimary].animate()
            .translationX(btnSizePx.toFloat()).alpha(0f)
            .setDuration(Anim.FAST).setInterpolator(Anim.EXIT).start()

        // Shrink width back.
        expandAnimator?.cancel()
        val lp = layoutParams
        val startW = if (lp.width > 0) lp.width else entries.size * btnSizePx
        expandAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Anim.NORMAL
            interpolator = Anim.EXIT
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                lp.width = (startW + (btnSizePx - startW) * t).toInt()
                layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    primaryIndex = i
                    resetIconPositions()
                }
            })
            start()
        }

        removeDismissOverlay()
        isExpanded = false
    }

    // ── Icon positioning ───────────────────────────────────────────────────────

    /**
     * Snap all icons to their resting state.
     * Primary slot (primaryIndex) at translationX=0, alpha=1, VISIBLE.
     * All others at their expanded offset, alpha=0, INVISIBLE.
     */
    private fun resetIconPositions() {
        iconViews.forEachIndexed { i, iv ->
            iv.animate().cancel()
            if (i == primaryIndex) {
                iv.translationX = 0f
                iv.alpha        = 1f
                iv.visibility   = View.VISIBLE
            } else {
                iv.translationX = (i * btnSizePx).toFloat()
                iv.alpha        = 0f
                iv.visibility   = View.INVISIBLE
            }
        }
    }

    // ── Dismiss overlay ────────────────────────────────────────────────────────

    private fun addDismissOverlay() {
        val parent = parent as? FrameLayout ?: return
        if (dismissOverlay != null) return
        val overlay = View(context).apply {
            setOnClickListener { collapse() }
        }
        parent.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        dismissOverlay = overlay
        // SwitchButton must sit above the overlay.
        bringToFront()
    }

    private fun removeDismissOverlay() {
        val overlay = dismissOverlay ?: return
        (overlay.parent as? FrameLayout)?.removeView(overlay)
        dismissOverlay = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        expandAnimator?.cancel()
        removeDismissOverlay()
    }
}
