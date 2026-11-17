package com.tape.measure.data.db

import com.tape.measure.domain.shape.Reading
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stores a shape's readings in the `readings` column of [MeasurementEntity]. */
object ReadingsJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(readings: List<Reading>): String = json.encodeToString(readings)

    /**
     * Empty for rows that predate the column, and for text this build can't
     * read — callers then fall back to the row's plain length columns.
     */
    fun decode(text: String?): List<Reading> {
        if (text == null) return emptyList()
        return runCatching { json.decodeFromString<List<Reading>>(text) }.getOrDefault(emptyList())
    }
}
