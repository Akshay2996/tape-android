package com.tape.measure.domain.shape

/**
 * What the user is measuring.
 *
 * Every tool is built from the same two gestures: tapping points on real
 * surfaces, and (for [hasLift] tools) tilting the phone to set a height.
 *
 * @param surfacePoints how many surface points the tool needs, or null when the
 *        user decides (they press Done).
 * @param hasLift whether a height step follows the surface points.
 */
enum class MeasureTool(
    val label: String,
    val surfacePoints: Int?,
    val hasLift: Boolean,
) {
    /** Straight distance between two points. */
    LINE("Line", 2, false),

    /** Total length along any number of points. */
    PATH("Path", null, false),

    /** Area and perimeter of a flat shape with any number of corners. */
    AREA("Area", null, false),

    /** Angle at a corner: arm, corner, arm. */
    ANGLE("Angle", 3, false),

    /** Vertical height above (or below) one point. */
    HEIGHT("Height", 1, true),

    /** Circle from two opposite points on its edge. */
    CIRCLE("Circle", 2, false),

    /** Box from three corners of one face plus its depth. Done early gives a rectangle. */
    BOX("Box", 3, true),

    /** Cylinder from two opposite points on its base plus its height. */
    CYLINDER("Cylinder", 2, true);

    companion object {
        /** Reads a persisted tool name; anything unrecognised is [LINE]. */
        fun parse(name: String): MeasureTool = entries.firstOrNull { it.name == name } ?: LINE
    }
}
