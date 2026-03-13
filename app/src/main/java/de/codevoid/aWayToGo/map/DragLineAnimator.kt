package de.codevoid.aWayToGo.map

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generates animated drag-line geometries using physically-inspired techniques.
 *
 * Three animation styles are supported:
 *
 * **[Style.WHIP]** — Verlet-integrated rope that cracks like a whip.
 *   A chain of particles connected by distance constraints is "thrown" from the
 *   puck toward the anchor.  The tip accelerates as the wave propagates down the
 *   chain, mimicking a real whip crack.  After the throw, amplitude decays
 *   exponentially until the line goes taut.
 *
 * **[Style.LASSO]** — Rope thrown from the puck as a spinning oval loop.
 *   Phase 1: squirly wobble near the puck.
 *   Phase 2: a clear rotating oval forms at the puck end.
 *   Phase 3: the oval is thrown toward the anchor, deforming as it travels.
 *   Phase 4: the oval catches the anchor and the line straightens.
 *
 * **[Style.FALL]** — Old-line dismissal: both endpoints detach and the rope
 *   falls out of the frame under simulated gravity with a loose-string wobble.
 *   Returns `null` once the rope has left the screen area.
 *
 * Both styles output a list of normalised (t, lateral-offset) pairs that the
 * caller maps onto the actual lat/lon line between puck and anchor.
 *
 * All state is internal; call [reset] when the anchor changes, then call
 * [generate] every frame with the elapsed time.
 */
class DragLineAnimator {

    // ── Public configuration ────────────────────────────────────────────────

    enum class Style { WHIP, LASSO, FALL }

    var style: Style = Style.WHIP

    /** Number of output sample points along the line. */
    var resolution: Int = 18

    // ── Whip tunables ───────────────────────────────────────────────────────

    /** Duration (s) of the throw ramp-up phase. */
    var whipThrowDuration: Double = 0.12

    /** Exponential decay time constant (s) after the throw — larger = longer settle. */
    var whipDecayTau: Double = 1.2

    /** Peak lateral displacement as a fraction of line length. */
    var whipAmplitude: Double = 0.18

    /** Number of spatial wave cycles along the line. */
    var whipFrequency: Double = 1.8

    /** Wave propagation speed (rad/s) from puck toward anchor. */
    var whipSpeed: Double = 11.0

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
    var lassoLoopPoints: Int = 12

    /** Peak sway amplitude for the trailing rope during wobble (fraction of length). */
    var lassoSwayAmplitude: Double = 0.18

    /** Fraction of the line near the puck kept straight. */
    var lassoStraightFrac: Double = 0.06

    // ── Fall tunables ────────────────────────────────────────────────────────

    /** Gravitational acceleration for the fall (fraction-of-length per s²). */
    var fallAcceleration: Double = 0.45

    /** Oscillation amplitude superimposed on the fall. */
    var fallWobbleAmplitude: Double = 0.07

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
            Style.FALL  -> generateFall(elapsedSec)
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
    //
    // Tuned to be less aggressive (lower amplitude/speed) with a longer settle.

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
    // The user is at the puck.  They swing the lasso at their own location,
    // so the loop starts near t = 0, not t = 1.
    //
    // Four phases:
    //   Phase 0 – Wobble  (0 – 0.5 s): squirly, chaotic rope near the puck.
    //   Phase 1 – Spin    (0.5 – 1.5 s): a clear rotating oval forms at the puck end.
    //   Phase 2 – Throw   (1.5 – 2.8 s): the oval travels toward the anchor,
    //                      deforming into an elongated ellipse due to wind / force.
    //   Phase 3 – Catch   (2.8 – 3.8 s): the oval reaches the anchor and its
    //                      radius shrinks until the rope goes straight.
    //
    // Samples form a continuous polyline: trailing rope → oval loop → tail to anchor.
    // The order is NOT sorted by t; the loop section intentionally doubles back.

    private fun generateLasso(elapsedSec: Double): List<Sample>? {
        val wobbleEnd = 0.50
        val spinEnd   = 1.50
        val throwEnd  = 2.80
        val catchEnd  = 3.80

        if (elapsedSec > catchEnd + 0.4) return null

        val phase: Int
        val phaseFrac: Double
        when {
            elapsedSec < wobbleEnd -> { phase = 0; phaseFrac = elapsedSec / wobbleEnd }
            elapsedSec < spinEnd   -> { phase = 1; phaseFrac = (elapsedSec - wobbleEnd) / (spinEnd - wobbleEnd) }
            elapsedSec < throwEnd  -> { phase = 2; phaseFrac = (elapsedSec - spinEnd)   / (throwEnd - spinEnd) }
            elapsedSec < catchEnd  -> { phase = 3; phaseFrac = (elapsedSec - throwEnd)  / (catchEnd - throwEnd) }
            else                   -> { phase = 4; phaseFrac = 1.0 }
        }

        val n = resolution.coerceAtLeast(6)
        val spinAngle = lassoSpinSpeed * elapsedSec

        // ── Loop center position along the line ─────────────────────────────
        // Starts just ahead of the puck, travels toward the anchor.
        val loopCenter = when (phase) {
            0    -> 0.03
            1    -> 0.03 + phaseFrac * 0.05     // drifts slightly while spinning
            2    -> 0.08 + phaseFrac * 0.87     // travels from 8 % to 95 %
            3    -> 0.95
            else -> 1.0
        }

        // ── Loop radii ───────────────────────────────────────────────────────
        val baseR = lassoLoopRadius             // e.g. 0.10
        val loopRadiusPerp = when (phase) {
            0    -> baseR * phaseFrac           // grows 0 → R as wobble → spin
            1    -> baseR
            2    -> baseR * (1.0 - phaseFrac * 0.25)  // slight shrink from drag
            3    -> baseR * (1.0 - phaseFrac)  // collapses to 0 at anchor
            else -> 0.0
        }
        // Axial stretch: oval elongates along line direction during throw (wind).
        val loopRadiusAxial = when (phase) {
            2    -> loopRadiusPerp * (1.0 + phaseFrac * 1.0)  // up to 2× elongation
            else -> loopRadiusPerp
        }

        // ── Trailing rope wobble ─────────────────────────────────────────────
        val wobbleAmp = when (phase) {
            0    -> lassoSwayAmplitude * (0.4 + 0.6 * phaseFrac)  // grows from 40 % → 100 %
            1    -> lassoSwayAmplitude * (1.0 - phaseFrac)         // settles
            else -> 0.0
        }

        val samples = ArrayList<Sample>(n + lassoLoopPoints + 4)

        // ── Trailing rope (puck → front edge of loop) ───────────────────────
        val loopFront = (loopCenter - loopRadiusAxial).coerceAtLeast(0.01)
        val trailN = ((loopFront / 1.0) * n).toInt().coerceAtLeast(2)

        for (i in 0 until trailN) {
            val localT = i.toDouble() / (trailN - 1)
            val t = localT * loopFront
            val tAdj = maxOf(0.0, (localT - lassoStraightFrac) / (1.0 - lassoStraightFrac))
            val env = sin(Math.PI * tAdj)

            val offset = if (phase == 0) {
                // Chaotic wobble: two overlapping frequencies
                val f1 = sin(2.0 * Math.PI * 2.5 * localT - 9.0 * elapsedSec) * env
                val f2 = sin(2.0 * Math.PI * 4.2 * localT - 15.0 * elapsedSec) * 0.45 * env
                wobbleAmp * (f1 + f2)
            } else {
                val wave = sin(2.0 * Math.PI * 1.2 * localT - 6.0 * elapsedSec) * env
                wobbleAmp * wave
            }
            samples.add(Sample(t, offset))
        }

        // ── Oval / loop ───────────────────────────────────────────────────────
        if (loopRadiusPerp > 1e-4) {
            val loopN = lassoLoopPoints.coerceAtLeast(8)
            // Traverse the full ellipse (+1 to close the loop).
            for (i in 0..loopN) {
                val theta = spinAngle + 2.0 * Math.PI * i.toDouble() / loopN
                val t      = loopCenter + loopRadiusAxial * cos(theta)
                val offset = loopRadiusPerp  * sin(theta)
                samples.add(Sample(t.coerceIn(0.0, 1.1), offset))
            }
        }

        // ── Connection from loop back edge to anchor ─────────────────────────
        // Only shown once the loop has started travelling so there is a visible
        // "tail" behind it.
        if (phase >= 2 || (phase == 1 && phaseFrac > 0.5)) {
            val loopBack = (loopCenter + loopRadiusAxial).coerceAtMost(0.98)
            val connectN = 4
            for (i in 1..connectN) {
                val t = loopBack + (1.0 - loopBack) * i.toDouble() / connectN
                samples.add(Sample(t, 0.0))
            }
        }

        return samples
    }

    // ── Fall implementation ─────────────────────────────────────────────────
    //
    // Used when a new drag line replaces an existing one.  Both endpoints
    // "detach" and the rope falls out of frame under simulated gravity with a
    // loose-string wobble superimposed.  Stateless — all motion is derived from
    // elapsedSec so no reset() is required before calling.

    private fun generateFall(elapsedSec: Double): List<Sample>? {
        // Quadratic gravity: displacement grows with t².
        val sag = fallAcceleration * elapsedSec * elapsedSec
        // Clear once the line has fallen well off-screen.
        if (sag > 1.4) return null

        val n = resolution.coerceAtLeast(6)
        // Wobble decays quickly — the rope should look limp, not elastic.
        val wobbleDecay = exp(-elapsedSec * 1.8)

        return (0 until n).map { i ->
            val t = i.toDouble() / (n - 1)
            // Catenary-shaped sag: endpoints fall and the mid-span falls further.
            // Offset is negative = consistently to one side (rope "falls" sideways).
            val sagOffset = -sag * (1.0 + 1.5 * t * (1.0 - t))
            // Decaying oscillation for the loose-string wobble.
            val wobble = fallWobbleAmplitude * wobbleDecay *
                sin(2.0 * Math.PI * 2.5 * t - 7.0 * elapsedSec)
            Sample(t, sagOffset + wobble)
        }
    }
}
