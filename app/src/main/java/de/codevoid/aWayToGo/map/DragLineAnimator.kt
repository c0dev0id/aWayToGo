package de.codevoid.aWayToGo.map

import kotlin.math.abs
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

    enum class Style { WHIP, LASSO, FALL, SINE, GRAVITY }

    var style: Style = Style.WHIP

    /** Number of output sample points along the line. */
    var resolution: Int = 18

    // ── Whip tunables ───────────────────────────────────────────────────────

    /** Duration (s) of the throw ramp-up phase. */
    var whipThrowDuration: Double = 0.15

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

    /**
     * Amplitude of the oval breathing mode as a fraction of [lassoLoopRadius].
     * The n=2 spinning-ring mode oscillates at 0.87 × spin rate (Brun & Audoly 2014);
     * the two semi-axes oscillate in anti-phase so the loop alternates between a
     * tall oval and a wide oval.  0.12 gives a subtle but visible flutter.
     */
    var lassoBreathAmplitude: Double = 0.12

    // ── Fall tunables ────────────────────────────────────────────────────────

    /** Gravitational acceleration for the fall (fraction-of-length per s²). */
    var fallAcceleration: Double = 0.45

    /** Oscillation amplitude superimposed on the fall. */
    var fallWobbleAmplitude: Double = 0.07

    // ── Sine tunables ────────────────────────────────────────────────────────

    /** Peak lateral displacement as a fraction of line length. */
    var sineAmplitude: Double = 0.14

    /** Number of spatial wave cycles visible along the line. */
    var sineFrequency: Double = 1.5

    /** Wave travel speed (rad/s) — much slower than WHIP for a drifting feel. */
    var sineSpeed: Double = 2.2

    /** Exponential decay time constant (s) after the throw ramp-up. */
    var sineDecayTau: Double = 1.0

    /** Duration (s) of the throw ramp-up phase. */
    var sineThrowDuration: Double = 0.12

    /** Fraction of the line near the puck kept straight. */
    var sineStraightFrac: Double = 0.08

    // ── Gravity tunables ─────────────────────────────────────────────────────

    /**
     * Initial upward displacement given to the rope on reset, as a fraction of
     * line length.  The rope is thrown up and then falls under [gravityStrength].
     */
    var gravThrowImpulse: Double = 0.18

    /**
     * Downward acceleration added per frame² in normalised coordinates.
     * At 60 Hz this causes a noticeable sag within ~1 s and the rope settles to
     * a catenary curve whose depth is determined by [gravSlack].
     */
    var gravityStrength: Double = 8e-5

    /** Fraction of velocity retained each frame — controls how quickly the rope settles. */
    var gravDamping: Double = 0.982

    /** Jakobsen constraint-relaxation iterations.  More = stiffer, less stretchy rope. */
    var gravConstraintIters: Int = 8

    /**
     * Extra rope length relative to the straight puck→anchor distance.
     * This slack allows the rope to form a catenary below the straight line.
     * 0.08 → 8 % extra length → visible but subtle mid-span sag.
     */
    var gravSlack: Double = 0.08

    // ── Internal Verlet state (whip mode) ───────────────────────────────────

    private var particles = DoubleArray(0)   // interleaved [x0,y0, x1,y1, …]
    private var oldParticles = DoubleArray(0)
    private var segmentLen = 0.0
    private var verletInited = false

    // ── Internal Verlet state (gravity mode) ────────────────────────────────

    private var gravParticles    = DoubleArray(0)
    private var gravOldParticles = DoubleArray(0)
    private var gravSegLen       = 0.0
    private var gravInited       = false

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
        gravInited   = false
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
            Style.WHIP    -> generateWhip(elapsedSec)
            Style.LASSO   -> generateLasso(elapsedSec)
            Style.FALL    -> generateFall(elapsedSec)
            Style.SINE    -> generateSine(elapsedSec)
            Style.GRAVITY -> generateGravity()
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
    //   Phase 2 – Throw   (1.5 – 2.8 s): the oval travels toward the anchor; the
    //                      loop plane rotates 180° (the "half-rotation rule" for a
    //                      correct catch, Brun & Audoly 2014).
    //   Phase 3 – Catch   (2.8 – 3.8 s): the oval reaches the anchor and collapses.
    //
    // Three physics improvements applied (Brun & Audoly 2014):
    //
    //  1. Oval breathing mode — the spinning ring's n=2 natural mode oscillates at
    //     ~0.87 × the spin frequency.  Both semi-axes are modulated in anti-phase so
    //     the loop continuously alternates between a tall and a wide oval.
    //
    //  2. Half-rotation throw — rather than growing 3.5× elongated during flight, the
    //     loop plane rotates 180° (|cos(π * phaseFrac)| modulates the perp radius so
    //     the loop goes edge-on at mid-throw and face-on again at the anchor).
    //
    //  3. Honda smoothing — the rope cannot make a sharp corner at the attachment ring
    //     (honda knot).  A short quarter-circle arc bridges the spoke tangent (+t) to
    //     the loop entry tangent (−offset), and the loop traversal always starts at the
    //     honda attachment so the polyline is geometrically continuous.
    //
    // Samples form a continuous polyline: trailing rope → honda arc → oval loop → tail.
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

        val n         = resolution.coerceAtLeast(6)
        val spinAngle = lassoSpinSpeed * elapsedSec
        val twoPI     = 2.0 * Math.PI

        // ── Loop center position along the line ─────────────────────────────
        val loopCenter = when (phase) {
            0    -> 0.03
            1    -> 0.03 + phaseFrac * 0.05
            2    -> 0.08 + phaseFrac * 0.87
            3    -> 0.95
            else -> 1.0
        }

        // ── Improvement 1: Oval breathing mode (0.87 × spin rate) ───────────
        // Active only during stable spinning (phases 1–2).  breathMod oscillates
        // between ±lassoBreathAmplitude; the two semi-axes are anti-phase.
        val breathPhase  = 0.87 * lassoSpinSpeed * elapsedSec
        val breathScale  = when (phase) {
            0    -> phaseFrac                                  // ramp in
            1    -> 1.0
            2    -> abs(cos(phaseFrac * Math.PI))              // tied to tilt factor
            else -> 0.0
        }
        val breathMod = lassoBreathAmplitude * breathScale * sin(breathPhase)

        // ── Improvement 2: Half-rotation throw ──────────────────────────────
        // In phase 2 the loop plane rotates 180°: face-on → edge-on → face-on.
        // tiltFactor=1 when face-on (max visibility), 0 when edge-on.
        val tiltFactor = if (phase == 2) abs(cos(phaseFrac * Math.PI)) else 1.0

        // ── Loop radii ───────────────────────────────────────────────────────
        val baseR = lassoLoopRadius

        val loopRadiusPerp = when (phase) {
            0    -> baseR * phaseFrac
            1    -> baseR * (1.0 + breathMod)
            2    -> {
                // Tilt-based visibility; taper closed in last 15% as loop arrives.
                val taper = if (phaseFrac > 0.85) (1.0 - (phaseFrac - 0.85) / 0.15) else 1.0
                baseR * tiltFactor * taper * (1.0 + breathMod)
            }
            3    -> baseR * (1.0 - phaseFrac)
            else -> 0.0
        }

        // Axial radius: anti-phase to perp (oval breathing).
        // In phase 2 the axial length stays close to baseR — no more 3.5× elongation.
        val loopRadiusAxial = when (phase) {
            2    -> (baseR * (1.0 + tiltFactor * 0.3 - breathMod)).coerceAtLeast(0.01)
            0, 3 -> loopRadiusPerp   // circular during formation and collapse
            else -> (baseR * (1.0 - breathMod)).coerceAtLeast(0.01)
        }

        // ── Trailing rope wobble ─────────────────────────────────────────────
        val wobbleAmp = when (phase) {
            0    -> lassoSwayAmplitude * (0.4 + 0.6 * phaseFrac)
            1    -> lassoSwayAmplitude * (1.0 - phaseFrac)
            else -> 0.0
        }

        val samples = ArrayList<Sample>(n + lassoLoopPoints + 16)

        // ── Trailing rope (puck → just before honda) ─────────────────────────
        val loopFront = (loopCenter - loopRadiusAxial).coerceAtLeast(0.01)
        val trailN    = ((loopFront / 1.0) * n).toInt().coerceAtLeast(2)

        for (i in 0 until trailN) {
            val localT = i.toDouble() / (trailN - 1)
            val t      = localT * loopFront
            val tAdj   = maxOf(0.0, (localT - lassoStraightFrac) / (1.0 - lassoStraightFrac))
            val env    = sin(Math.PI * tAdj)
            val offset = if (phase == 0) {
                val f1 = sin(twoPI * 2.5 * localT - 9.0 * elapsedSec) * env
                val f2 = sin(twoPI * 4.2 * localT - 15.0 * elapsedSec) * 0.45 * env
                wobbleAmp * (f1 + f2)
            } else {
                wobbleAmp * sin(twoPI * 1.2 * localT - 6.0 * elapsedSec) * env
            }
            samples.add(Sample(t, offset))
        }

        // ── Improvement 3: Honda smoothing ───────────────────────────────────
        // At the honda attachment the loop tangent is in the −offset direction
        // (d(offset)/dθ = R_perp·cos(π) = −R_perp < 0), so the spoke arrives
        // axially (+t) and the loop departs laterally (−offset).  A quarter-circle
        // arc smoothly bridges the two tangents (Brun & Audoly 2014).
        if (loopRadiusPerp > 1e-4 && phase >= 1) {
            val hondaR = loopRadiusPerp * 0.25   // tight bend radius at the ring
            val arcSteps = 4
            for (j in 1..arcSteps) {
                val a = j.toDouble() / arcSteps * Math.PI / 2.0   // 0 → π/2
                samples.add(Sample(
                    t      = loopFront + hondaR * sin(a),
                    offset = -hondaR * (1.0 - cos(a)),            // curves toward −offset
                ))
            }
        }

        // ── Oval / loop ───────────────────────────────────────────────────────
        if (loopRadiusPerp > 1e-4) {
            val loopN = lassoLoopPoints.coerceAtLeast(8)

            // Always start traversal at the honda attachment (theta = π in the
            // loop's intrinsic frame) so the polyline enters the loop at loopFront
            // with a continuous tangent from the honda arc.
            // theta = spinAngle + 2π·i/loopN = π  →  i = (π − spinAngle mod 2π) · loopN/2π
            val spinMod   = ((spinAngle % twoPI) + twoPI) % twoPI
            val attachOff = ((Math.PI - spinMod) + twoPI) % twoPI
            val attachI   = ((attachOff / twoPI * loopN) + 0.5).toInt() % loopN

            for (k in attachI until attachI + loopN + 1) {
                val i      = k % loopN
                val theta  = spinAngle + twoPI * i.toDouble() / loopN
                val t      = loopCenter + loopRadiusAxial * cos(theta)
                val offset = loopRadiusPerp  * sin(theta)
                samples.add(Sample(t.coerceIn(0.0, 1.1), offset))
            }
        }

        // ── Connection from loop back edge to anchor ─────────────────────────
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

    // ── Sine implementation ─────────────────────────────────────────────────
    //
    // A clean travelling sinusoidal wave with an envelope that keeps the
    // endpoints pinned.  No Verlet — the geometry is computed analytically every
    // frame so there is no accumulated state beyond the elapsed time.
    //
    // Compared to WHIP:
    //   • Much slower travel speed (2.2 vs 11 rad/s) — languid, drifting feel.
    //   • No Jakobsen distance constraints — the wave is perfectly smooth.
    //   • Slightly lower amplitude — less dramatic, more wire-like.
    //
    // Good baseline for comparing against the physics-based styles.

    private fun generateSine(elapsedSec: Double): List<Sample>? {
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

    // ── Gravity implementation ───────────────────────────────────────────────
    //
    // Verlet particle chain with real gravity and distance constraints (Jakobsen
    // relaxation).  Inspired by Goriely & McMillen 2002 and Macklin's PBD work.
    //
    // On reset the rope is initialised with an upward velocity impulse so it
    // looks like it was thrown.  Gravity (always in the +offset direction)
    // pulls it back down, the rope overshoots the catenary, oscillates, and
    // settles.  Velocity damping ensures high-frequency ripples die first,
    // matching the modal analysis prediction (Early & Sykulski 2020).
    //
    // The rope is [gravSlack] longer than the straight puck→anchor distance so
    // it can form a visible catenary when the kinetic energy has dissipated.
    //
    // NOTE: generateGravity() is called once per Choreographer frame (no
    // elapsedSec parameter) — each call advances the simulation by one step.

    private fun generateGravity(): List<Sample>? {
        val n = resolution.coerceAtLeast(4)
        if (!gravInited || gravParticles.size != n * 2) {
            initGravVerlet(n)
        }

        // ── Verlet integration + gravity ──────────────────────────────────
        for (i in 1 until n - 1) {
            val ix = i * 2; val iy = ix + 1
            val cx = gravParticles[ix];    val cy = gravParticles[iy]
            val ox = gravOldParticles[ix]; val oy = gravOldParticles[iy]
            // Velocity with damping; gravity accel in +offset direction.
            val vx = (cx - ox) * gravDamping
            val vy = (cy - oy) * gravDamping
            gravOldParticles[ix] = cx; gravOldParticles[iy] = cy
            gravParticles[ix]    = cx + vx
            gravParticles[iy]    = cy + vy + gravityStrength
        }

        // Pin endpoints before constraint pass.
        gravParticles[0] = 0.0;           gravParticles[1] = 0.0
        gravParticles[(n - 1) * 2] = 1.0; gravParticles[(n - 1) * 2 + 1] = 0.0
        gravOldParticles[0] = 0.0;            gravOldParticles[1] = 0.0
        gravOldParticles[(n - 1) * 2] = 1.0;  gravOldParticles[(n - 1) * 2 + 1] = 0.0

        // ── Jakobsen distance constraints ─────────────────────────────────
        for (iter in 0 until gravConstraintIters) {
            for (i in 0 until n - 1) {
                val ax = gravParticles[i * 2];       val ay = gravParticles[i * 2 + 1]
                val bx = gravParticles[(i + 1) * 2]; val by = gravParticles[(i + 1) * 2 + 1]
                val ddx = bx - ax; val ddy = by - ay
                val dist = sqrt(ddx * ddx + ddy * ddy)
                if (dist < 1e-12) continue
                val diff = (gravSegLen - dist) / dist * 0.5
                val ox2 = ddx * diff; val oy2 = ddy * diff
                if (i > 0)     { gravParticles[i * 2]       -= ox2; gravParticles[i * 2 + 1]       -= oy2 }
                if (i + 1 < n - 1) { gravParticles[(i + 1) * 2] += ox2; gravParticles[(i + 1) * 2 + 1] += oy2 }
            }
        }

        // Re-pin endpoints after constraints.
        gravParticles[0] = 0.0;           gravParticles[1] = 0.0
        gravParticles[(n - 1) * 2] = 1.0; gravParticles[(n - 1) * 2 + 1] = 0.0

        // ── Settle check ─────────────────────────────────────────────────
        var maxKE = 0.0
        for (i in 1 until n - 1) {
            val vx = gravParticles[i * 2]     - gravOldParticles[i * 2]
            val vy = gravParticles[i * 2 + 1] - gravOldParticles[i * 2 + 1]
            maxKE = maxOf(maxKE, vx * vx + vy * vy)
        }
        if (maxKE < 1e-12) return null

        return (0 until n).map { i ->
            Sample(t = gravParticles[i * 2], offset = gravParticles[i * 2 + 1])
        }
    }

    private fun initGravVerlet(n: Int) {
        gravParticles    = DoubleArray(n * 2)
        gravOldParticles = DoubleArray(n * 2)
        // Rope is slightly longer than the straight line so it can sag.
        gravSegLen = (1.0 + gravSlack) / (n - 1)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            // Start at the straight line position (y = 0).
            gravParticles[i * 2]     = t
            gravParticles[i * 2 + 1] = 0.0
            // Give each particle an upward (−y) initial velocity by placing the
            // "old" position below the current one.
            // Verlet velocity = current − old, so old = current − velocity.
            // We want velocity = −impulse * sin(π*t) (upward bell shape).
            val impulseVel = gravThrowImpulse * sin(Math.PI * t)   // ≥ 0
            gravOldParticles[i * 2]     = t
            gravOldParticles[i * 2 + 1] = impulseVel   // old_y = cur_y − (−impulseVel) = +impulseVel
        }
        gravInited = true
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
