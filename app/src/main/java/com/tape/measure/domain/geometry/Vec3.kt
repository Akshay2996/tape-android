package com.tape.measure.domain.geometry

import kotlin.math.sqrt

/** A point or direction in world space, in metres. Y is up (gravity-aligned). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    val length: Float get() = sqrt(this dot this)

    fun distanceTo(o: Vec3): Float = (this - o).length

    /** Unit vector in this direction, or null when this is too short to have one. */
    fun normalized(): Vec3? {
        val len = length
        return if (len < 1e-6f) null else this * (1f / len)
    }

    /** The part of this vector perpendicular to the unit vector [n]. */
    fun rejectFrom(n: Vec3): Vec3 = this - n * (this dot n)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** A half-line from [origin] along the unit vector [direction]. */
data class Ray(val origin: Vec3, val direction: Vec3) {
    fun at(distance: Float): Vec3 = origin + direction * distance
}

/** A point on screen, in pixels from the top-left corner. */
data class Point2(val x: Float, val y: Float)

/** A rectangle on screen, in pixels. */
data class ScreenBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(p: Point2): Boolean = p.x in left..right && p.y in top..bottom

    /** Grown by [fraction] of its size on every side. */
    fun grownBy(fraction: Float): ScreenBox {
        val dx = (right - left) * fraction
        val dy = (bottom - top) * fraction
        return ScreenBox(left - dx, top - dy, right + dx, bottom + dy)
    }

    companion object {
        /** The smallest box around [points], or null when there are none. */
        fun around(points: List<Point2>): ScreenBox? =
            if (points.isEmpty()) {
                null
            } else {
                ScreenBox(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
            }
    }
}

data class Segment2(val a: Point2, val b: Point2)

data class Segment3(val a: Vec3, val b: Vec3)
