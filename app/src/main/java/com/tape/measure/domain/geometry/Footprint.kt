package com.tape.measure.domain.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/** A point on the floor plan: world X and Z, looking down. */
data class Flat(val x: Float, val z: Float)

/**
 * The smallest rectangle, at any rotation, that encloses a set of points on
 * the floor plan.
 *
 * [corners] go round the rectangle in order; the first edge is [length] long
 * and the second [width], with [length] ≥ [width].
 */
class Footprint(val corners: List<Flat>, val length: Float, val width: Float) {

    val area: Float get() = length * width

    /** This rectangle grown outwards by [margin] on every side. */
    fun grownBy(margin: Float): Footprint {
        val cx = corners.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cz = corners.sumOf { it.z.toDouble() }.toFloat() / 4f
        val (a, b, _, d) = corners
        // Unit vectors along the two edges leaving the first corner.
        val ux = (b.x - a.x) / length; val uz = (b.z - a.z) / length
        val vx = (d.x - a.x) / width;  val vz = (d.z - a.z) / width
        val hl = length / 2f + margin
        val hw = width / 2f + margin
        fun corner(su: Float, sv: Float) = Flat(cx + ux * hl * su + vx * hw * sv, cz + uz * hl * su + vz * hw * sv)
        return Footprint(
            corners = listOf(corner(-1f, -1f), corner(1f, -1f), corner(1f, 1f), corner(-1f, 1f)),
            length = 2f * hl,
            width = 2f * hw,
        )
    }

    companion object {
        /**
         * Rotating calipers over the convex hull: the minimum-area enclosing
         * rectangle always has one side flush with a hull edge. Null for fewer
         * than three distinct points, which have no area to enclose.
         */
        fun enclosing(points: List<Flat>): Footprint? {
            val hull = convexHull(points)
            if (hull.size < 3) return null

            var best: Footprint? = null
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val ex = b.x - a.x
                val ez = b.z - a.z
                val len = sqrt(ex * ex + ez * ez)
                if (len < 1e-6f) continue
                val ux = ex / len; val uz = ez / len   // along the edge
                val vx = -uz;      val vz = ux         // across it

                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                for (p in hull) {
                    val u = (p.x - a.x) * ux + (p.z - a.z) * uz
                    val v = (p.x - a.x) * vx + (p.z - a.z) * vz
                    if (u < minU) minU = u
                    if (u > maxU) maxU = u
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
                val du = maxU - minU
                val dv = maxV - minV
                if (best != null && du * dv >= best.area) continue

                fun at(u: Float, v: Float) = Flat(a.x + ux * u + vx * v, a.z + uz * u + vz * v)
                val c = listOf(at(minU, minV), at(maxU, minV), at(maxU, maxV), at(minU, maxV))
                best = if (du >= dv) {
                    Footprint(c, du, dv)
                } else {
                    // Start at a corner whose first edge is the long one.
                    Footprint(listOf(c[1], c[2], c[3], c[0]), dv, du)
                }
            }
            return best
        }

        /** Andrew's monotone chain. Counter-clockwise, no repeated or collinear points. */
        internal fun convexHull(points: List<Flat>): List<Flat> {
            val sorted = points.distinct().sortedWith(compareBy<Flat>({ it.x }, { it.z }))
            if (sorted.size < 3) return sorted

            fun cross(o: Flat, a: Flat, b: Flat) = (a.x - o.x) * (b.z - o.z) - (a.z - o.z) * (b.x - o.x)

            val hull = ArrayList<Flat>(sorted.size * 2)
            for (p in sorted) {
                while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) hull.removeAt(hull.lastIndex)
                hull += p
            }
            val lowerSize = hull.size + 1
            for (p in sorted.asReversed()) {
                while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) hull.removeAt(hull.lastIndex)
                hull += p
            }
            hull.removeAt(hull.lastIndex)
            return hull.filterIndexed { i, p -> i == 0 || abs(p.x - hull[i - 1].x) + abs(p.z - hull[i - 1].z) > 0f }
        }
    }
}
