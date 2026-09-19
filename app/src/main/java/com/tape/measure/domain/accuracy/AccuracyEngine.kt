package com.tape.measure.domain.accuracy

import com.tape.measure.domain.model.AccuracyEstimate
import com.tape.measure.domain.model.AccuracyGrade
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Why the AR session is struggling, mirrored from ARCore's `TrackingFailureReason`.
 *
 * Mirrored (rather than used directly) so [AccuracyEngine] stays a pure-Kotlin,
 * JVM-testable unit with no ARCore dependency — ARCore classes are native-backed
 * and cannot be instantiated in a local unit test.
 */
enum class TrackingIssue {
    NONE,
    INSUFFICIENT_LIGHT,
    EXCESSIVE_MOTION,
    INSUFFICIENT_FEATURES,
    BAD_STATE,
    CAMERA_UNAVAILABLE,
}

/**
 * What an anchor was placed on. Plane hits are materially more reliable than
 * raw feature-point / depth hits, so we penalise the latter.
 */
enum class AnchorSurface {
    /** Hit a tracked Plane — best case. */
    PLANE,

    /** Hit a depth or feature point — geometry is inferred, not fitted. */
    POINT,
}

/**
 * One endpoint of a measurement, as observed at placement + read time.
 *
 * @param placementRangeMeters how far the camera was from the surface when the
 *        user tapped. ARCore depth quality degrades with range, so a point
 *        placed at 5 m is inherently less certain than one placed at 0.5 m.
 * @param surface what the anchor was attached to.
 * @param isTracking whether the anchor is STILL tracking now. A dropped anchor
 *        has unbounded drift — we refuse to quote a tolerance for it.
 */
data class AnchorObservation(
    val placementRangeMeters: Float,
    val surface: AnchorSurface,
    val isTracking: Boolean,
)

/**
 * Everything the engine needs to bound the error on one measurement.
 */
data class AccuracyInput(
    val distanceMeters: Float,
    val cameraIsTracking: Boolean,
    val issue: TrackingIssue,
    val anchorA: AnchorObservation,
    val anchorB: AnchorObservation,
)

/**
 * Turns observable AR session signals into a defensible ± tolerance.
 *
 * Philosophy
 * ──────────
 * Tape cannot know the true error of a measurement — no phone can without a
 * reference. What it CAN do is bound the error from things it actually observes,
 * and refuse to answer when it can't.
 *
 * Every term below maps to a real, observed signal. Nothing here is a lookup of
 * device model or a fabricated "confidence score". If we had no signal for a
 * term, the term would not exist.
 *
 * Composition
 * ───────────
 * The four error sources are independent, so they combine in quadrature (root
 * sum of squares) rather than by naive addition — adding them would systematically
 * overstate the error and make every reading look worse than it is.
 *
 *     totalRelative = sqrt(base² + range² + tracking² + placement²)
 *     tolerance     = max(FLOOR, distance × totalRelative)
 *
 * The floor exists because no consumer phone earns a sub-5 mm claim, however
 * good the conditions look.
 */
object AccuracyEngine {

    // ── Tunable constants ────────────────────────────────────────────────────

    /**
     * ARCore's irreducible relative error under ideal conditions.
     * Google does not publish a figure; 1% is the conservative low end of
     * independently reported flagship results and matches the "±1-2% premium
     * tier" row in the project's accuracy table (README → Accuracy breakdown).
     */
    const val BASE_RELATIVE = 0.010f

    /** We never claim better than ±5 mm regardless of how good conditions are. */
    const val FLOOR_METERS = 0.005f

    /** Placement range at or below which range contributes no extra error. */
    const val RANGE_CLEAN_METERS = 1.0f

    /** Placement range at or beyond which the range penalty saturates. */
    const val RANGE_SATURATION_METERS = 5.0f

    /** Maximum relative error contributed by long-range placement. */
    const val RANGE_MAX_RELATIVE = 0.040f

    /** Extra relative error when an endpoint sits on a feature point, not a plane. */
    const val POINT_SURFACE_RELATIVE = 0.025f

    /** Grade boundary: at or below this relative error, the reading is HIGH. */
    const val HIGH_THRESHOLD = 0.02f

    /** Grade boundary: at or below this relative error, the reading is FAIR. */
    const val FAIR_THRESHOLD = 0.05f

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Bounds the error on a measurement, or refuses to.
     *
     * Returns [AccuracyGrade.UNAVAILABLE] — with an explanation — when either
     * anchor has stopped tracking or the camera itself isn't tracking. In those
     * states anchor drift is unbounded and any number we printed would be a guess.
     */
    fun estimate(input: AccuracyInput): AccuracyEstimate {
        // ── Refusal cases: we cannot bound the error, so we don't pretend to.
        if (!input.cameraIsTracking) {
            return AccuracyEstimate.unavailable(
                "Camera isn't tracking. Move the phone slowly to re-lock."
            )
        }
        if (!input.anchorA.isTracking || !input.anchorB.isTracking) {
            return AccuracyEstimate.unavailable(
                "A point lost tracking and may have drifted. Re-place it."
            )
        }
        if (input.distanceMeters <= 0f) {
            return AccuracyEstimate.unavailable("No measurement yet.")
        }

        // ── Error terms (all relative, i.e. fractions of the measured span).
        val rangeRel     = rangeRelative(input.anchorA, input.anchorB)
        val trackingRel  = input.issue.relativeError()
        val placementRel = placementRelative(input.anchorA, input.anchorB)

        val totalRel = sqrt(
            BASE_RELATIVE * BASE_RELATIVE +
                rangeRel * rangeRel +
                trackingRel * trackingRel +
                placementRel * placementRel
        )

        val toleranceMeters = max(FLOOR_METERS, input.distanceMeters * totalRel)

        // Re-derive the effective relative error after the floor is applied, so
        // the displayed % and the displayed ± always agree with each other.
        val effectiveRel = toleranceMeters / input.distanceMeters

        val grade = when {
            effectiveRel <= HIGH_THRESHOLD -> AccuracyGrade.HIGH
            effectiveRel <= FAIR_THRESHOLD -> AccuracyGrade.FAIR
            else                           -> AccuracyGrade.LOW
        }

        return AccuracyEstimate(
            grade           = grade,
            toleranceMeters = toleranceMeters,
            relative        = effectiveRel,
            reason          = buildReason(
                input        = input,
                rangeRel     = rangeRel,
                trackingRel  = trackingRel,
                placementRel = placementRel,
            ),
        )
    }

    // ── Error terms ──────────────────────────────────────────────────────────

    /**
     * Relative error contributed by how far away the points were placed.
     *
     * Uses the WORSE (larger) of the two placement ranges rather than the mean:
     * a measurement is only as trustworthy as its least certain endpoint.
     *
     * Ramps linearly from 0 at [RANGE_CLEAN_METERS] to [RANGE_MAX_RELATIVE] at
     * [RANGE_SATURATION_METERS], then saturates. Linear (not quadratic) because
     * we have no field data justifying a steeper curve, and overstating error is
     * its own form of dishonesty.
     */
    internal fun rangeRelative(a: AnchorObservation, b: AnchorObservation): Float {
        val worstRange = max(a.placementRangeMeters, b.placementRangeMeters)
        if (worstRange <= RANGE_CLEAN_METERS) return 0f

        val span = RANGE_SATURATION_METERS - RANGE_CLEAN_METERS
        val over = (worstRange - RANGE_CLEAN_METERS).coerceAtMost(span)
        return RANGE_MAX_RELATIVE * (over / span)
    }

    /**
     * Relative error contributed by endpoints that aren't on detected planes.
     * Both endpoints off-plane is worse than one, so the penalties add in
     * quadrature here too.
     */
    internal fun placementRelative(a: AnchorObservation, b: AnchorObservation): Float {
        val penaltyA = if (a.surface == AnchorSurface.POINT) POINT_SURFACE_RELATIVE else 0f
        val penaltyB = if (b.surface == AnchorSurface.POINT) POINT_SURFACE_RELATIVE else 0f
        return sqrt(penaltyA * penaltyA + penaltyB * penaltyB)
    }

    /**
     * Relative error contributed by an active tracking problem.
     *
     * Magnitudes are ordered by how badly each condition degrades ARCore's pose
     * estimate: featureless surfaces and violent motion are worse for anchor
     * stability than dim light, which mostly costs detection latency.
     */
    internal fun TrackingIssue.relativeError(): Float = when (this) {
        TrackingIssue.NONE                  -> 0.000f
        TrackingIssue.INSUFFICIENT_LIGHT    -> 0.050f
        TrackingIssue.EXCESSIVE_MOTION      -> 0.080f
        TrackingIssue.INSUFFICIENT_FEATURES -> 0.090f
        TrackingIssue.BAD_STATE             -> 0.120f
        TrackingIssue.CAMERA_UNAVAILABLE    -> 0.120f
    }

    // ── Explanation ──────────────────────────────────────────────────────────

    /**
     * Builds the plain-language sentence shown under the tolerance.
     *
     * Names the DOMINANT error source only. Listing every contributing term
     * produces a wall of caveats the user won't read; naming the biggest one
     * tells them what to actually change.
     */
    private fun buildReason(
        input: AccuracyInput,
        rangeRel: Float,
        trackingRel: Float,
        placementRel: Float,
    ): String {
        val dominant = maxOf(rangeRel, trackingRel, placementRel)

        // No term is material next to the irreducible base error, so there is
        // nothing useful the user could change. Don't nag them to "improve" a
        // reading that is already as good as the hardware allows — an unhelpful
        // suggestion erodes trust in the suggestions that do matter.
        if (dominant <= BASE_RELATIVE) {
            return "Both points on locked planes, " +
                "%.1f m apart. This is as good as Tape gets.".format(input.distanceMeters)
        }

        return when (dominant) {
            trackingRel -> when (input.issue) {
                TrackingIssue.INSUFFICIENT_LIGHT ->
                    "Low light is hurting tracking. Move somewhere brighter."
                TrackingIssue.EXCESSIVE_MOTION ->
                    "The phone is moving too fast. Hold steadier and re-measure."
                TrackingIssue.INSUFFICIENT_FEATURES ->
                    "This surface is too plain for the camera to lock onto. " +
                        "Try a spot with more texture or pattern."
                TrackingIssue.BAD_STATE, TrackingIssue.CAMERA_UNAVAILABLE ->
                    "The AR session is unstable. Restart the measurement."
                TrackingIssue.NONE -> ""   // unreachable: NONE contributes 0
            }

            placementRel ->
                "One or both points aren't on a detected surface, so their " +
                    "depth is estimated. Aim at a flat plane for a tighter reading."

            else -> {
                val worst = max(
                    input.anchorA.placementRangeMeters,
                    input.anchorB.placementRangeMeters,
                )
                "You placed a point %.1f m from the camera. ".format(worst) +
                    "Get closer to the surface for a tighter reading."
            }
        }
    }
}
