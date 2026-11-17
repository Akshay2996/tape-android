package com.tape.measure.domain.shape

import com.tape.measure.domain.geometry.Ray
import com.tape.measure.domain.geometry.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** Where the reticle meets the side of the shape being raised. */
data class LiftHit(
    /** Signed height of [point] above the base, along the base normal. */
    val height: Float,
    val point: Vec3,
)

/**
 * Finds a height by tilting the phone, with nothing to tap on.
 *
 * The top (or bottom) of an object is usually an edge in mid-air: there is no
 * surface there for a hit test to land on. So instead of hit-testing, Tape
 * extends the base the user already placed into an imaginary wall — a vertical
 * plane for one point, the four side planes for a box, the curved side for a
 * cylinder — and asks how high up that wall the reticle is pointing.
 */
object Lift {

    /** Below this the reticle ray is too nearly parallel to the wall to trust. */
    private const val MIN_INCIDENCE = 1e-3f

    /** How far past a box side's ends the reticle may point and still count as on it. */
    private const val SIDE_SLACK = 0.25f

    /**
     * @param points the base points placed so far, in placement order.
     * @param normal unit normal of the base.
     * @return null when [tool] has no height step, the base isn't complete, or
     *         the reticle isn't pointing at the wall.
     */
    fun solve(tool: MeasureTool, points: List<Vec3>, normal: Vec3, ray: Ray): LiftHit? =
        when (tool) {
            MeasureTool.HEIGHT ->
                points.getOrNull(0)?.let { facingPlane(it, normal, ray) }

            MeasureTool.BOX ->
                if (points.size < 3) {
                    null
                } else {
                    ShapeSolver.boxBase(points[0], points[1], points[2], normal)
                        ?.let { prism(it.corners, normal, ray) }
                }

            MeasureTool.CYLINDER ->
                if (points.size < 2) {
                    null
                } else {
                    cylinder(
                        centre = (points[0] + points[1]) * 0.5f,
                        radius = points[0].distanceTo(points[1]) / 2f,
                        normal = normal,
                        ray = ray,
                    )
                }

            else -> null
        }

    /** The plane through [base] that stands along [normal] and faces the camera. */
    private fun facingPlane(base: Vec3, normal: Vec3, ray: Ray): LiftHit? {
        val toCamera = (ray.origin - base).rejectFrom(normal).normalized() ?: return null
        val distance = planeDistance(ray, base, toCamera) ?: return null
        val point = ray.at(distance)
        return LiftHit((point - base) dot normal, point)
    }

    /** The nearest side of the prism standing on [corners]. */
    private fun prism(corners: List<Vec3>, normal: Vec3, ray: Ray): LiftHit? {
        class Candidate(val distance: Float, val along: Float, val hit: LiftHit)

        val candidates = corners.indices.mapNotNull { i ->
            val a = corners[i]
            val side = corners[(i + 1) % corners.size] - a
            val sideNormal = (side cross normal).normalized() ?: return@mapNotNull null
            val distance = planeDistance(ray, a, sideNormal) ?: return@mapNotNull null
            val point = ray.at(distance)
            Candidate(
                distance = distance,
                along = ((point - a) dot side) / (side dot side),
                hit = LiftHit((point - a) dot normal, point),
            )
        }
        val onASide = candidates.filter { it.along in -SIDE_SLACK..(1f + SIDE_SLACK) }
        return onASide.ifEmpty { candidates }.minByOrNull { it.distance }?.hit
    }

    /** The curved side of the cylinder of [radius] around the axis through [centre]. */
    private fun cylinder(centre: Vec3, radius: Float, normal: Vec3, ray: Ray): LiftHit? {
        // Work in the plane perpendicular to the axis, where the side is a circle.
        val d = ray.direction.rejectFrom(normal)
        val w = (ray.origin - centre).rejectFrom(normal)
        val a = d dot d
        if (a < MIN_INCIDENCE * MIN_INCIDENCE) return null   // looking straight along the axis
        val b = 2f * (d dot w)
        val c = (w dot w) - radius * radius
        val discriminant = b * b - 4f * a * c

        val distance = if (discriminant >= 0f) {
            val root = sqrt(discriminant)
            val near = (-b - root) / (2f * a)
            if (near > 0f) near else (-b + root) / (2f * a)
        } else {
            // The reticle is pointing past the side: use where it passes closest.
            -b / (2f * a)
        }
        if (distance <= 0f) return null
        val point = ray.at(distance)
        return LiftHit((point - centre) dot normal, point)
    }

    /** Distance along [ray] to the plane through [point] with unit [planeNormal]. */
    private fun planeDistance(ray: Ray, point: Vec3, planeNormal: Vec3): Float? {
        val incidence = ray.direction dot planeNormal
        if (abs(incidence) < MIN_INCIDENCE) return null
        val distance = ((point - ray.origin) dot planeNormal) / incidence
        return if (distance > 0f) distance else null
    }
}
