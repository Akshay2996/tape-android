package com.tape.measure.domain.model

/**
 * How much we trust a measurement, expressed as a tolerance rather than a
 * confidence percentage.
 *
 * Design rationale
 * ────────────────
 * Tape's product wedge is honesty about accuracy (see design principle 3).
 * A confidence percentage ("92% confident") is not falsifiable and is what every
 * competitor fakes. A tolerance ("±3 cm") is a concrete, checkable claim: the user
 * can hold a physical tape against the same span and see whether we were right.
 *
 * We therefore lead with tolerance in the UI and never display a bare percentage.
 *
 * @see com.tape.measure.domain.accuracy.AccuracyEngine for how these are derived.
 */
enum class AccuracyGrade {
    /** Tolerance ≤ 2% of the measured span. Safe for buying-decision use. */
    HIGH,

    /** Tolerance 2–5%. Usable for rough planning, not for cutting material. */
    FAIR,

    /** Tolerance > 5%. Directional only — tell the user to grab a real tape. */
    LOW,

    /** We cannot bound the error at all. Refuse to show a number. */
    UNAVAILABLE,

    /**
     * Saved before the accuracy engine existed. We deliberately do NOT
     * back-fill a tolerance for these — inventing one retroactively would be
     * the same dishonesty in a new place.
     */
    UNKNOWN;

    /** Short label for the confidence badge. */
    fun label(): String = when (this) {
        HIGH        -> "Good"
        FAIR        -> "Lower accuracy"
        LOW         -> "Unreliable"
        UNAVAILABLE -> "Can't verify"
        UNKNOWN     -> "Not recorded"
    }

    /** False for the two grades that have no defensible number to show. */
    val hasTolerance: Boolean
        get() = this != UNAVAILABLE && this != UNKNOWN

    companion object {
        /** Reads a persisted grade name; anything unrecognised is [UNKNOWN]. */
        fun parse(name: String): AccuracyGrade =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * The result of bounding the error on a single measurement.
 *
 * [toleranceMeters] is a ± half-width: a reading of 1.40 m with a tolerance of
 * 0.03 m means we believe the true value lies in [1.37, 1.43].
 *
 * [relative] is [toleranceMeters] / distance. It is what the grade is decided
 * on, and what error propagation into areas and volumes works from.
 *
 * [reason] is a plain-language sentence explaining the dominant error source.
 * It is always non-empty — if we can't explain it, we shouldn't claim it.
 */
data class AccuracyEstimate(
    val grade: AccuracyGrade,
    val toleranceMeters: Float,
    val relative: Float,
    val reason: String,
) {
    companion object {
        /**
         * Used when anchors aren't tracking or no measurement exists.
         * Carries an explanation so the UI never shows a bare dash.
         */
        fun unavailable(reason: String): AccuracyEstimate = AccuracyEstimate(
            grade           = AccuracyGrade.UNAVAILABLE,
            toleranceMeters = 0f,
            relative        = 0f,
            reason          = reason,
        )
    }
}
