package de.codevoid.aWayToGo.map

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generates animated drag-line geometries using physically-inspired techniques.
 *
 * Two animation styles are supported:
 *
 * **[Style.WHIP]** — Verlet-integrated rope that cracks like a whip.
 *   A chain of particles connected by distance constraints is "thrown" from the
 *   puck toward the anchor.  The tip accelerates as the wave propagates down the
 *   chain, mimicking a real whip crack.  After the throw, amplitude decays
 *   exponentially until the line goes taut.
 *
 * **[Style.LASSO]** — Spinning loop at the anchor end, rope trailing behind.
 *   The last portion of the line forms a circular loop that spins around the
 *   anchor point.  The rest of the line trails behind as a catenary-like curve
 *   that settles over time.
 *
 * Both styles output a list of normalised (t, lateral-offset) pairs that the
 * caller maps onto the actual lat/lon line between puck and anchor.
 *
 * All state is internal; call [reset] when the anchor changes, then call
 * [generate] every frame with the elapsed time.
 */
class DragLineAnimator {

    // ── Public configuration ────────────────────────────────────────────────

    enum class Style { WHIP, LASSO }

    var style: Style = Style.WHIP

    /** Number of output sample points along the line. */
    var resolution: Int = 18

    // ── Whip tunables ───────────────────────────────────────────────────────

    /** Duration (s) of the throw ramp-up phase. */
    var whipThrowDuration: Double = 0.12

    /** Exponential decay time constant (s) after the throw. */
    var whipDecayTau: Double = 0.50

    /** Peak lateral displacement as a fraction of line length. */
    var whipAmplitude: Double = 0.30

    /** Number of spatial wave cycles along the line. */
    var whipFrequency: Double = 1.8

    /** Wave propagation speed (rad/s) from puck toward anchor. */
    var whipSpeed: Double = 16.0

    /** Fraction of the line near the puck kept straight (clean attach). */
    var whipStraightFrac: Double = 0.08

    /**
     * Verlet stiffness iterations.  Higher = stiffer rope, sharper crack tip.
     * Range 1–8 is practical; each iteration is cheap.
     */
    var whipStiffnessIters: Int = 3

    // ── Lasso tunables ──────────────────────────────────────────────────────

    /** Radius of the spinning loop as a fraction of line length. */
    var lassoLoopRadius: Double = 0.10

    /** Spin speed of the loop (rad/s). */
    var lassoSpinSpeed: Double = 8.0

    /** Number of sample points dedicated to the loop circle. */
    var lassoLoopPoints: Int = 10

    /** Exponential settle time constant (s) for the trailing rope. */
    var lassoSettleTau: Double = 0.70

    /** Peak sway amplitude for the trailing rope (fraction of length). */
    var lassoSwayAmplitude: Double = 0.18

    /** Fraction of the line near the puck kept straight. */
    var lassoStraightFrac: Double = 0.06

    // ── Internal Verlet state (whip mode) ───────────────────────────────────

    private var particles = DoubleArray(0)   // interleaved [x0,y0, x1,y1, …]
    private var oldParticles = DoubleArray(0)
    private var segmentLen = 0.0
    private var verletInited = false

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
    fun reset() {
        verletInited = false
    }

    /**
     * Generate the animated line for the current frame.
     *
     * @param elapsedSec  Seconds since the anchor was placed.
     * @return  List of [Sample]s along the line (sorted by increasing [Sample.t]).
     *          Returns `null` when the animation has settled and a plain 2-point
     *          straight line should be used instead.
     */
    fun generate(elapsedSec: Double): List<Sample>? {
        return when (style) {
            Style.WHIP  -> generateWhip(elapsedSec)
            Style.LASSO -> generateLasso(elapsedSec)
        }
    }

    // ── Whip implementation ─────────────────────────────────────────────────
    //
    // Phase 1 (throw):  A sinusoidal travelling wave is spawned along the rope
    //   with increasing amplitude.  Simultaneously, a Verlet chain with distance
    //   constraints is iterated to keep segment lengths fixed — this causes the
    //   tip to accelerate (whip-crack effect) as the wave energy concentrates
    //   into fewer moving particles.
    //
    // Phase 2 (settle):  Amplitude decays exponentially.  Once displacement is
    //   negligible, we return null so the caller can use a cheap 2-point line.

    private fun generateWhip(elapsedSec: Double): List<Sample>? {
        val n = resolution.coerceAtLeast(4)

        // Amplitude envelope: ramp up, then exponential decay.
        val amplitude = when {
            elapsedSec < whipThrowDuration ->
                whipAmplitude * (elapsedSec / whipThrowDuration)
            else ->
                whipAmplitude * exp(-(elapsedSec - whipThrowDuration) / whipDecayTau)
        }

        // Settled — tell caller to use a straight line.
        if (amplitude < 1e-4) return null

        // ── Verlet integration ──────────────────────────────────────────────
        // We simulate the chain in a 1-D normalised space where the puck is at
        // (0, 0) and the anchor is at (1, 0).  Lateral displacements are the
        // "y" coordinate.
        if (!verletInited || particles.size != n * 2) {
            initVerlet(n)
        }

        // Drive particles with the travelling wave (external force).
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            val tAdj = maxOf(0.0, (t - whipStraightFrac) / (1.0 - whipStraightFrac))
            val envelope = sin(Math.PI * tAdj)   // zero at both endpoints
            val phase = 2.0 * Math.PI * whipFrequency * t - whipSpeed * elapsedSec
            val targetY = amplitude * sin(phase) * envelope

            // Verlet: blend toward target (soft constraint).
            val ix = i * 2
            val iy = ix + 1
            // Position stays on the line (x = t), only y gets the wave.
            particles[ix] = t
            // Blend old position toward the wave target for smooth damping.
            val blend = 0.35
            particles[iy] = particles[iy] * (1.0 - blend) + targetY * blend
        }

        // Pin endpoints.
        particles[0] = 0.0; particles[1] = 0.0
        particles[(n - 1) * 2] = 1.0; particles[(n - 1) * 2 + 1] = 0.0

        // Distance constraint relaxation (Jakobsen).
        for (iter in 0 until whipStiffnessIters) {
            for (i in 0 until n - 1) {
                val ax = particles[i * 2];     val ay = particles[i * 2 + 1]
                val bx = particles[(i + 1) * 2]; val by = particles[(i + 1) * 2 + 1]
                val ddx = bx - ax; val ddy = by - ay
                val dist = sqrt(ddx * ddx + ddy * ddy)
                if (dist < 1e-12) continue
                val diff = (segmentLen - dist) / dist * 0.5
                val ox = ddx * diff; val oy = ddy * diff
                // Don't move pinned endpoints.
                if (i > 0) {
                    particles[i * 2] -= ox;     particles[i * 2 + 1] -= oy
                }
                if (i + 1 < n - 1) {
                    particles[(i + 1) * 2] += ox; particles[(i + 1) * 2 + 1] += oy
                }
            }
        }

        // Read out the result.
        return (0 until n).map { i ->
            Sample(
                t = particles[i * 2],
                offset = particles[i * 2 + 1],
            )
        }
    }

    private fun initVerlet(n: Int) {
        particles = DoubleArray(n * 2)
        oldParticles = DoubleArray(n * 2)
        segmentLen = 1.0 / (n - 1)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            particles[i * 2] = t
            particles[i * 2 + 1] = 0.0
            oldParticles[i * 2] = t
            oldParticles[i * 2 + 1] = 0.0
        }
        verletInited = true
    }

    // ── Lasso implementation ────────────────────────────────────────────────
    //
    // The line has two sections:
    //   1. Trailing rope (t ∈ [0, 1-loopFrac]): a settling sine wave similar to
    //      the whip, but with a slower initial throw and a catenary droop.
    //   2. Spinning loop (t near 1.0): particles arranged in a circle around the
    //      anchor point.  The circle spins and its radius can pulse slightly.
    //
    // The loop "opens up" during the throw phase and keeps spinning; the trailing
    // rope settles to a straight line.

    private fun generateLasso(elapsedSec: Double): List<Sample>? {
        val loopN = lassoLoopPoints.coerceAtLeast(6)
        val trailN = (resolution - loopN).coerceAtLeast(4)
        val totalN = trailN + loopN

        // Throw ramp-up: the loop opens from radius 0 to full over 0.3s.
        val throwPhase = 0.30
        val loopOpen = if (elapsedSec < throwPhase) (elapsedSec / throwPhase) else 1.0
        val radius = lassoLoopRadius * loopOpen

        // Settle amplitude for trailing rope.
        val trailAmp = lassoSwayAmplitude * exp(-elapsedSec / lassoSettleTau)

        // If both have settled, use straight line.
        if (trailAmp < 1e-4 && loopOpen >= 1.0 && elapsedSec > 3.0) return null

        val samples = ArrayList<Sample>(totalN)

        // ── Trailing rope section ───────────────────────────────────────────
        // Maps t ∈ [0, trailEnd] where trailEnd leaves room for the loop.
        val trailEnd = 1.0 - lassoLoopRadius * 2.0   // leave space for the loop
        for (i in 0 until trailN) {
            val localT = i.toDouble() / (trailN - 1)
            val t = localT * trailEnd

            val tAdj = maxOf(0.0, (localT - lassoStraightFrac) / (1.0 - lassoStraightFrac))
            // Envelope: rises then falls, peak near 0.6 of the trail.
            val env = sin(Math.PI * tAdj)
            val phase = 2.0 * Math.PI * 1.2 * localT - 6.0 * elapsedSec
            val offset = trailAmp * sin(phase) * env

            samples.add(Sample(t, offset))
        }

        // ── Spinning loop section ───────────────────────────────────────────
        // Circle centred at (1.0, 0.0) — the anchor point.
        val spinAngle = lassoSpinSpeed * elapsedSec
        for (i in 0 until loopN) {
            val angle = spinAngle + 2.0 * Math.PI * i.toDouble() / loopN
            val cx = 1.0 + radius * cos(angle)
            val cy = radius * sin(angle)
            // t = cx (position along the line axis), offset = cy.
            samples.add(Sample(cx.coerceIn(0.0, 1.0 + radius), cy))
        }
        // Close the loop by repeating the first loop point.
        val firstLoopAngle = spinAngle
        samples.add(Sample(
            (1.0 + radius * cos(firstLoopAngle)).coerceIn(0.0, 1.0 + radius),
            radius * sin(firstLoopAngle),
        ))

        return samples
    }
}
