package com.tape.measure.data.db

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
    val distanceMeters: Float,
    val unit: String,
    val label: String?,
    /** ± half-width of the error bound, in metres. 0 for legacy rows. */
    val toleranceMeters: Float,
    /** Name of a [com.tape.measure.domain.model.AccuracyGrade] value. */
    val accuracyGrade: String,
    val createdAt: Long,
)
