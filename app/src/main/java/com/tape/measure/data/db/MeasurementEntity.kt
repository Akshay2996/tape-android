package com.tape.measure.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved measurement.
 *
 * Accuracy fields ([toleranceMeters], [accuracyGrade]) were added in schema v2.
 * They store the real, derived error bound from AccuracyEngine.
 *
 * Rows written before v2 carry `accuracyGrade = "UNKNOWN"` and a zero tolerance.
 * We deliberately do NOT back-fill a plausible-looking tolerance for those rows:
 * we have no record of the tracking conditions they were taken under, and
 * inventing one would reintroduce the exact dishonesty the accuracy engine
 * exists to remove.
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey val id: String,
    /**
     * The shape's main length in metres: the line itself, a path's total, a
     * box's length. 0 for a shape with no length at all (an angle).
     */
    val distanceMeters: Float,
    val unit: String,
    val label: String?,
    /** ± half-width of the error bound on [distanceMeters]. 0 for legacy rows. */
    val toleranceMeters: Float,
    /**
     * Name of a [com.tape.measure.domain.model.AccuracyGrade] value: how far
     * the measurement as a whole can be trusted.
     */
    val accuracyGrade: String,
    val createdAt: Long,
    /** Name of the [com.tape.measure.domain.shape.MeasureTool] used. Added in schema v3. */
    @ColumnInfo(defaultValue = "LINE") val kind: String = "LINE",
    /**
     * Every figure the shape reported, as JSON (see [ReadingsJson]), headline
     * first. Null for rows saved before schema v3, which only ever held a line.
     */
    val readings: String? = null,
)
