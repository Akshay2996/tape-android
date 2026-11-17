package com.tape.measure.ui.screens.measure

import com.tape.measure.domain.geometry.Point2
import com.tape.measure.domain.geometry.Segment2
import com.tape.measure.domain.model.AccuracyGrade
import com.tape.measure.domain.model.UnitSystem
import com.tape.measure.domain.shape.MeasureTool
import com.tape.measure.domain.shape.Quantity
import com.tape.measure.domain.shape.Reading
import com.tape.measure.domain.shape.ShapeAccuracy

/**
 * Reflects the camera's tracking quality.
 *
 *  NOT_TRACKING – session paused or stopped; can't place points
 *  LOW          – tracking is shaky / recovering; accuracy worse
 *  HIGH         – full tracking; best accuracy
 *
 * NOTE: this describes the SESSION, not the measurement. How much to trust a
 * given reading lives in [MeasureUiState.accuracy], which is derived from
 * distance, placement range, surface type and tracking failure reason — not
 * from this enum alone. The two are deliberately separate: good session
 * tracking does not by itself make a 6 m span accurate.
 */
enum class TrackingConfidence {
    NOT_TRACKING,
    LOW,
    HIGH;

    /** Short label shown in the confidence badge. */
    fun label(): String = when (this) {
        NOT_TRACKING -> "No tracking"
        LOW          -> "Tracking recovering"
        HIGH         -> "Tracking locked"
    }
}

/** A dimension drawn next to the edge it describes, in screen pixels. */
data class OverlayLabel(
    val at: Point2,
    val quantity: Quantity,
    val value: Float,
    val tolerance: Float?,
    val grade: AccuracyGrade,
)

/** The shape in progress, projected onto the screen for this frame. */
data class MeasureOverlay(
    val segments: List<Segment2> = emptyList(),
    val dots: List<Point2> = emptyList(),
    val labels: List<OverlayLabel> = emptyList(),
)

/**
 * Complete UI snapshot for MeasureScreen — owned by [MeasureViewModel] and
 * rebuilt every camera frame.
 */
data class MeasureUiState(
    val tool: MeasureTool = MeasureTool.LINE,
    val confidence: TrackingConfidence = TrackingConfidence.NOT_TRACKING,
    val overlay: MeasureOverlay = MeasureOverlay(),
    /** Every figure the shape reports, headline first. Empty until there is one. */
    val readings: List<Reading> = emptyList(),
    /** How far the shape can be trusted, and why. Null until there is a reading. */
    val accuracy: ShapeAccuracy? = null,
    /** What to do next, or — once there is a verdict — why it is what it is. */
    val hint: String = "",
    /** True when the reticle is on something a tap would place. */
    val reticleLocked: Boolean = false,
    val canUndo: Boolean = false,
    /** True when Done would end the shape here (a path, or a box kept flat). */
    val canFinish: Boolean = false,
    /** True once the shape is finished, whether or not it can be saved. */
    val isComplete: Boolean = false,
    val canSave: Boolean = false,
    /** Which unit to display in. */
    val unitSystem: UnitSystem = UnitSystem.CM,
    /** True while the save is in flight. */
    val isSaving: Boolean = false,
    /** True once the shape on screen has been saved, until it changes. */
    val isSaved: Boolean = false,
) {
    /**
     * True when the shape's only figure is written on its only edge — a line or
     * a height. Then that label is the headline and nothing else needs to show
     * the number. Otherwise (a path's total, a box's volume) the figures go in
     * the readings table.
     */
    val headlineOnLabel: Boolean
        get() = readings.size == 1 && overlay.labels.size == 1
}
