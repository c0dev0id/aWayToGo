package de.codevoid.aWayToGo.map

import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates animated drag-line geometries using physically-inspired techniques.
 *
 * **[generate]** produces a travelling sinusoidal wave with an amplitude envelope
 * that keeps both endpoints pinned.  The wave ramps up on reset, then decays
 * exponentially until the line goes taut.
 *
 * Output is a list of normalised (t, lateral-offset) pairs that the caller maps
 * onto the actual lat/lon line between puck and anchor.
 *
 * All state is internal; call [reset] when the anchor changes, then call
 * [generate] every frame with the elapsed time.
 */
class DragLineAnimator {

    // ── Public configuration ────────────────────────────────────────────────

    /** Number of output sample points along the line. */
    var resolution: Int = 18

    // ── Sine tunables ────────────────────────────────────────────────────────

    /** Peak lateral displacement as a fraction of line length. */
    var sineAmplitude: Double = 0.14

    /** Number of spatial wave cycles visible along the line. */
    var sineFrequency: Double = 1.5

    /** Wave travel speed (rad/s) — slow for a drifting feel. */
    var sineSpeed: Double = 2.2

    /** Exponential decay time constant (s) after the throw ramp-up. */
    var sineDecayTau: Double = 1.0

    /** Duration (s) of the throw ramp-up phase. */
    var sineThrowDuration: Double = 0.12

    /** Fraction of the line near the puck kept straight. */
    var sineStraightFrac: Double = 0.08

    // ── Output ──────────────────────────────────────────────────────────────

    /**
     * A single sample point on the animated line.
     *
     * @param t  Normalised position along the straight puck→anchor line (0=puck, 1=anchor).
     * @param offset  Lateral displacement as a fraction of total line length.
     *                Positive = left of the puck→anchor direction.
     */
    data class Sample(val t: Double, val offset: Double)

    /** Reset internal state.  Call when the anchor point changes. */
    fun reset() {}

    /**
     * Generate the animated line for the current frame.
     *
     * @param elapsedSec  Seconds since the anchor was placed.
     * @return  List of [Sample]s along the line (sorted by increasing [Sample.t]).
     *          Returns `null` when the animation has settled and a plain 2-point
     *          straight line should be used instead.
     */
    fun generate(elapsedSec: Double): List<Sample>? {
        val amplitude = when {
            elapsedSec < sineThrowDuration ->
                sineAmplitude * (elapsedSec / sineThrowDuration)
            else ->
                sineAmplitude * exp(-(elapsedSec - sineThrowDuration) / sineDecayTau)
        }
        if (amplitude < 1e-4) return null

        val n = resolution.coerceAtLeast(4)
        return (0 until n).map { i ->
            val t    = i.toDouble() / (n - 1)
            val tAdj = maxOf(0.0, (t - sineStraightFrac) / (1.0 - sineStraightFrac))
            val env  = sin(Math.PI * tAdj)
            val phase = 2.0 * Math.PI * sineFrequency * t - sineSpeed * elapsedSec
            Sample(t, amplitude * sin(phase) * env)
        }
    }
}
