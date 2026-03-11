package de.codevoid.aWayToGo.map.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import java.util.concurrent.atomic.AtomicInteger

// ── Shared animation constants ─────────────────────────────────────────────────
//
// Central home for durations and interpolators so every animation in the app
// uses the same vocabulary.  Add new names here rather than scattering magic
// numbers across files.

object Anim {
    const val FAST   = 150L   // quick fades, small reveals
    const val NORMAL = 220L   // menu expand/collapse, slides
    const val SLOW   = 350L   // satellite layer opacity

    /** Decelerates into the final position — use for elements entering the screen. */
    val ENTER get() = DecelerateInterpolator()

    /** Accelerates away from rest — use for elements leaving the screen. */
    val EXIT  get() = AccelerateInterpolator()
}

// ── AnimatorBag ────────────────────────────────────────────────────────────────
//
// Tracks a set of ValueAnimators and cancels all of them on demand.
// Attach one to an Activity/Fragment and call cancelAll() from onDestroy() to
// prevent animators from holding view references after the host is gone.
//
// Animators remove themselves from the bag when they finish naturally, so the
// bag stays small during normal operation.

class AnimatorBag {
    private val animators = mutableListOf<ValueAnimator>()

    /**
     * Register [animator] with this bag.  It will be cancelled by [cancelAll]
     * and will remove itself automatically when it ends or is cancelled.
     * Returns the same [animator] for convenient chaining.
     */
    fun add(animator: ValueAnimator): ValueAnimator {
        animators += animator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                animators.remove(animation as? ValueAnimator)
            }
            override fun onAnimationCancel(animation: Animator) {
                animators.remove(animation as? ValueAnimator)
            }
        })
        return animator
    }

    /** Cancel every tracked animator. Safe to call multiple times. */
    fun cancelAll() {
        animators.toList().forEach { it.cancel() }
        animators.clear()
    }
}

// ── View extension helpers ─────────────────────────────────────────────────────
//
// Thin wrappers around ViewPropertyAnimator for the four patterns that appear
// repeatedly in the UI.  Each one cancels any in-flight animation on the same
// view before starting, preventing conflicting animators.

/** Fade this view in from transparent to fully opaque and make it VISIBLE. */
fun View.fadeIn(duration: Long = Anim.FAST) {
    animate().cancel()
    alpha = 0f
    visibility = View.VISIBLE
    animate().alpha(1f).setDuration(duration).setInterpolator(Anim.ENTER).start()
}

/** Fade this view out to transparent, then set it to GONE and reset alpha. */
fun View.fadeOut(duration: Long = Anim.FAST, onEnd: (() -> Unit)? = null) {
    animate().cancel()
    animate().alpha(0f).setDuration(duration).setInterpolator(Anim.EXIT).withEndAction {
        visibility = View.GONE
        alpha = 1f   // reset so the next fadeIn starts from the correct baseline
        onEnd?.invoke()
    }.start()
}

/** Slide this view in from [offsetX] pixels to its natural position (translationX = 0). */
fun View.slideInX(offsetX: Float, duration: Long = Anim.NORMAL) {
    animate().cancel()
    translationX = offsetX
    visibility = View.VISIBLE
    animate().translationX(0f).setDuration(duration).setInterpolator(Anim.ENTER).start()
}

/** Slide this view out to [offsetX] pixels, then hide it and reset translation. */
fun View.slideOutX(
    offsetX: Float,
    duration: Long = Anim.NORMAL,
    onEnd: (() -> Unit)? = null,
) {
    animate().cancel()
    animate().translationX(offsetX).setDuration(duration).setInterpolator(Anim.EXIT)
        .withEndAction {
            visibility = View.GONE
            translationX = 0f
            onEnd?.invoke()
        }.start()
}

/** Slide this view in from [offsetY] pixels to its natural position (translationY = 0). */
fun View.slideInY(offsetY: Float, duration: Long = Anim.NORMAL) {
    animate().cancel()
    translationY = offsetY
    visibility = View.VISIBLE
    animate().translationY(0f).setDuration(duration).setInterpolator(Anim.ENTER).start()
}

/** Slide this view out to [offsetY] pixels, then hide it and reset translation. */
fun View.slideOutY(
    offsetY: Float,
    duration: Long = Anim.NORMAL,
    onEnd: (() -> Unit)? = null,
) {
    animate().cancel()
    animate().translationY(offsetY).setDuration(duration).setInterpolator(Anim.EXIT)
        .withEndAction {
            visibility = View.GONE
            translationY = 0f
            onEnd?.invoke()
        }.start()
}

// ── AnimationLatch ─────────────────────────────────────────────────────────────
//
// Drop-in replacement for the intArrayOf(n) latch pattern used in multi-view
// phase transitions.  Call decrement() from each animation's withEndAction; when
// the last one fires, [onDone] is called.
//
// Usage:
//   val latch = AnimationLatch(3) { slideIn() }
//   view.animate().translationX(...).withEndAction { latch.decrement() }.start()
//
// IMPORTANT: withEndAction does NOT fire when a ViewPropertyAnimator is externally
// cancelled — the latch will simply never reach zero in that case.  This mirrors
// the existing behaviour; callers that need to guard against rapid mode switches
// should cancel in-flight animations before starting a new transition.

class AnimationLatch(count: Int, private val onDone: () -> Unit) {
    private val remaining = AtomicInteger(count)
    fun decrement() {
        if (remaining.decrementAndGet() == 0) onDone()
    }
}
