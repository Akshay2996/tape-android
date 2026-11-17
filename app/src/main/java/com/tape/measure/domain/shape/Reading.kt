package com.tape.measure.domain.shape

import com.tape.measure.domain.accuracy.AnchorObservation
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.geometry.Segment3
import com.tape.measure.domain.geometry.Vec3
import com.tape.measure.domain.model.AccuracyGrade
import kotlinx.serialization.Serializable

/** The physical kind of a number, which decides how it is formatted. */
enum class Quantity { LENGTH, AREA, VOLUME, ANGLE }

/**
 * One number a shape reports, with its error bound.
 *
 * [value] and [tolerance] are in SI units for the [quantity]: metres, square
 * metres, cubic metres, or degrees. [tolerance] is a ± half-width and is null
 * when Tape can't bound the error — in which case no figure is shown at all.
 */
@Serializable
data class Reading(
    val name: String,
    val quantity: Quantity,
    val value: Float,
    val tolerance: Float?,
    val grade: AccuracyGrade,
)

/** A point the user placed, with what Tape observed about it when they did. */
data class ObservedPoint(
    val position: Vec3,
    val observation: AnchorObservation,
)

/** A height set by tilting the phone. Signed: negative means below the base. */
data class ObservedLift(
    val height: Float,
    val observation: AnchorObservation,
)

/** Session-wide conditions that affect every reading taken this frame. */
data class Conditions(
    val cameraIsTracking: Boolean,
    val issue: TrackingIssue,
)

/** A dimension written next to the edge it describes. */
data class Label3(
    val at: Vec3,
    val quantity: Quantity,
    val value: Float,
    val tolerance: Float?,
    val grade: AccuracyGrade,
)

/** How far the shape as a whole can be trusted, and why. */
data class ShapeAccuracy(
    val grade: AccuracyGrade,
    val reason: String,
)

/**
 * A solved shape: what to draw and what it measures.
 *
 * [readings] is ordered with the headline figure first. [accuracy] is null
 * until there is at least one reading.
 */
data class Solution(
    val wire: List<Segment3> = emptyList(),
    val labels: List<Label3> = emptyList(),
    val readings: List<Reading> = emptyList(),
    val accuracy: ShapeAccuracy? = null,
)
