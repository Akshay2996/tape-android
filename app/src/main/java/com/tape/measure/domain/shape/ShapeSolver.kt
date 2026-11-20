package com.tape.measure.domain.shape

import com.tape.measure.domain.accuracy.AccuracyEngine
import com.tape.measure.domain.accuracy.AccuracyInput
import com.tape.measure.domain.accuracy.AnchorObservation
import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.geometry.Segment3
import com.tape.measure.domain.geometry.Vec3
import com.tape.measure.domain.model.AccuracyEstimate
import com.tape.measure.domain.model.AccuracyGrade
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the points a user has placed into a shape: the outline to draw and
 * the numbers it measures, each with an error bound.
 *
 * Accuracy
 * ────────
 * Every straight span between two placed points is bounded by [AccuracyEngine],
 * exactly as a plain two-point measurement is. Figures computed FROM spans
 * (area, volume, circumference…) get their bound by first-order error
 * propagation: each span's tolerance is scaled by how strongly the figure
 * depends on that span, and the contributions are combined in quadrature.
 * Nothing is bounded by assumption — if any span feeding a figure can't be
 * bounded, neither can the figure.
 *
 * Pure Kotlin, no Android or ARCore imports: this is all JVM-testable.
 */
object ShapeSolver {

    /** Straight segments used to draw a circle. */
    private const val CIRCLE_SEGMENTS = 48

    /** Spans shorter than this are treated as "not placed yet". */
    private const val MIN_SPAN_METERS = 1e-4f

    /**
     * Solves [tool] for the [points] placed so far. Works on partial input, so
     * the same call drives the live preview while the user is still placing.
     *
     * @param normal unit normal of the surface the shape sits on.
     * @param lift the height step, for tools that have one and once it is set.
     * @param resolution smallest detail the points could resolve, in metres,
     *        added to every span's bound. Zero for tapped points; a box fitted
     *        to depth data is only as sharp as that data.
     */
    fun solve(
        tool: MeasureTool,
        points: List<ObservedPoint>,
        normal: Vec3,
        lift: ObservedLift?,
        conditions: Conditions,
        resolution: Float = 0f,
    ): Solution {
        val builder = Builder(conditions, resolution)
        when (tool) {
            MeasureTool.LINE     -> builder.path(points.take(2))
            MeasureTool.PATH     -> builder.path(points)
            MeasureTool.AREA     -> builder.area(points)
            MeasureTool.ANGLE    -> builder.angle(points)
            MeasureTool.HEIGHT   -> builder.height(points, normal, lift)
            MeasureTool.CIRCLE   -> builder.circle(points, normal, lift = null)
            MeasureTool.CYLINDER -> builder.circle(points, normal, lift)
            MeasureTool.BOX,
            MeasureTool.OBJECT   -> builder.box(points, normal, lift)
        }
        return builder.build()
    }

    /**
     * The verdict when no shape can be solved at all — the camera isn't
     * tracking, or a placed point has been lost. The wording comes from
     * [AccuracyEngine] so there is one source for every refusal message.
     */
    fun refusal(conditions: Conditions): ShapeAccuracy {
        val lost = AnchorObservation(0f, AnchorSurface.PLANE, isTracking = false)
        val estimate = AccuracyEngine.estimate(
            AccuracyInput(0f, conditions.cameraIsTracking, conditions.issue, lost, lost)
        )
        return ShapeAccuracy(estimate.grade, estimate.reason)
    }

    /** The four corners of a box face, in order, with its side lengths. */
    internal class BoxBase(val corners: List<Vec3>, val length: Float, val width: Float)

    /**
     * The rectangle lying on the plane with unit [normal] whose first edge runs
     * [p0]→[p1] and whose opposite edge passes through [p2].
     */
    internal fun boxBase(p0: Vec3, p1: Vec3, p2: Vec3, normal: Vec3): BoxBase? {
        val along = (p1 - p0).rejectFrom(normal)
        val u = along.normalized() ?: return null
        val length = along.length
        val v = normal cross u
        val offset = (p2 - p0) dot v
        val c1 = p0 + u * length
        return BoxBase(
            corners = listOf(p0, c1, c1 + v * offset, p0 + v * offset),
            length = length,
            width = abs(offset),
        )
    }

    private class Builder(
        private val conditions: Conditions,
        private val resolution: Float,
    ) {
        private val wire = ArrayList<Segment3>()
        private val labels = ArrayList<Label3>()
        private val readings = ArrayList<Reading>()
        private val spans = ArrayList<AccuracyEstimate>()

        // ── Tools ────────────────────────────────────────────────────────────

        /** A run of straight segments. With two points this is the Line tool. */
        fun path(points: List<ObservedPoint>) {
            if (points.size < 2) return
            val segments = points.zipWithNext { a, b -> measuredEdge(a, b) }
            if (segments.size == 1) {
                report("Length", segments[0])
            } else {
                derived(
                    "Length", Quantity.LENGTH, segments.total { it.length },
                    segments.map { 1f to it.estimate },
                )
            }
        }

        fun area(points: List<ObservedPoint>) {
            if (points.size < 2) return
            if (points.size == 2) {
                measuredEdge(points[0], points[1])
                return
            }
            val sides = points.indices.map { i ->
                measuredEdge(points[i], points[(i + 1) % points.size])
            }

            // Newell's method: half the length of the summed cross products of
            // consecutive corners. Works for a flat polygon in any orientation.
            var sum = Vec3.ZERO
            for (i in points.indices) {
                sum += points[i].position cross points[(i + 1) % points.size].position
            }
            val area = sum.length / 2f

            // A polygon's area depends on its corners, not on independent side
            // lengths, so there is no clean per-side derivative. Bound it as if
            // the whole shape were scaled by its least certain side — area then
            // moves by twice that relative error. Deliberately conservative.
            if (area > 0f && sides.all { it.estimate.grade.hasTolerance }) {
                val relative = 2f * sides.maxOf { it.estimate.relative }
                readings += Reading(
                    "Area", Quantity.AREA, area, area * relative, AccuracyEngine.gradeFor(relative),
                )
            } else {
                readings += Reading("Area", Quantity.AREA, area, null, AccuracyGrade.UNAVAILABLE)
            }
            derived(
                "Perimeter", Quantity.LENGTH, sides.total { it.length },
                sides.map { 1f to it.estimate },
            )
        }

        fun angle(points: List<ObservedPoint>) {
            if (points.size < 2) return
            val arm = points[0]
            val corner = points[1]
            edge(corner.position, arm.position)
            if (points.size < 3) return
            val other = points[2]
            edge(corner.position, other.position)

            val a = arm.position - corner.position
            val b = other.position - corner.position
            if (a.length < MIN_SPAN_METERS || b.length < MIN_SPAN_METERS) return
            val cosine = ((a dot b) / (a.length * b.length)).coerceIn(-1f, 1f)
            val degrees = Math.toDegrees(acos(cosine).toDouble()).toFloat()

            // Each arm's direction is uncertain by about its relative length
            // error (in radians); the two arms are independent.
            val armA = span(a.length, corner.observation, arm.observation)
            val armB = span(b.length, corner.observation, other.observation)
            val bounded = armA.grade.hasTolerance && armB.grade.hasTolerance
            val tolerance = if (bounded) {
                Math.toDegrees(
                    sqrt(armA.relative * armA.relative + armB.relative * armB.relative).toDouble()
                ).toFloat()
            } else {
                null
            }
            val grade = worse(armA.grade, armB.grade)
            labels += Label3(corner.position, Quantity.ANGLE, degrees, tolerance, grade)
            readings += Reading("Angle", Quantity.ANGLE, degrees, tolerance, grade)
        }

        fun height(points: List<ObservedPoint>, normal: Vec3, lift: ObservedLift?) {
            val base = points.firstOrNull() ?: return
            if (lift == null) return
            val top = base.position + normal * lift.height
            edge(base.position, top)
            val rise = Measured(
                length = abs(lift.height),
                estimate = span(abs(lift.height), base.observation, lift.observation),
            )
            label(midpoint(base.position, top), rise)
            report("Height", rise)
        }

        /** A circle from two opposite rim points; with [lift], a cylinder. */
        fun circle(points: List<ObservedPoint>, normal: Vec3, lift: ObservedLift?) {
            if (points.size < 2) return
            val a = points[0]
            val b = points[1]
            val across = b.position - a.position
            val u = across.rejectFrom(normal).normalized() ?: return
            val v = normal cross u
            val centre = midpoint(a.position, b.position)
            val diameter = measuredEdge(a, b)
            val d = diameter.length
            val radius = d / 2f
            ring(centre, u, v, radius)

            val pi = PI.toFloat()
            if (lift == null) {
                report("Diameter", diameter)
                derived("Circumference", Quantity.LENGTH, pi * d, listOf(pi to diameter.estimate))
                derived("Area", Quantity.AREA, pi * d * d / 4f, listOf(pi * d / 2f to diameter.estimate))
                return
            }

            val rise = normal * lift.height
            ring(centre + rise, u, v, radius)
            for (quarter in 0 until 4) {
                val angle = quarter * pi / 2f
                val rim = centre + (u * cos(angle) + v * sin(angle)) * radius
                edge(rim, rim + rise)
            }
            val h = abs(lift.height)
            val heightSide = Measured(
                length = h,
                estimate = span(h, worst(a.observation, b.observation), lift.observation),
            )
            label(centre + u * radius + rise * 0.5f, heightSide)

            derived(
                "Volume", Quantity.VOLUME, pi * d * d * h / 4f,
                listOf(pi * d * h / 2f to diameter.estimate, pi * d * d / 4f to heightSide.estimate),
            )
            report("Diameter", diameter)
            report("Height", heightSide)
            derived(
                "Surface", Quantity.AREA, pi * d * d / 2f + pi * d * h,
                listOf(pi * d + pi * h to diameter.estimate, pi * d to heightSide.estimate),
            )
        }

        /** A rectangle from three points; with [lift], a box. */
        fun box(points: List<ObservedPoint>, normal: Vec3, lift: ObservedLift?) {
            if (points.size < 2) return
            if (points.size == 2) {
                report("Length", measuredEdge(points[0], points[1]))
                return
            }
            val (p0, p1, p2) = points
            val base = boxBase(p0.position, p1.position, p2.position, normal) ?: return
            val c = base.corners
            for (i in 0 until 4) edge(c[i], c[(i + 1) % 4])

            val l = base.length
            val w = base.width
            val lengthSide = Measured(l, span(l, p0.observation, p1.observation))
            val widthSide = Measured(w, span(w, worst(p0.observation, p1.observation), p2.observation))
            label(midpoint(c[0], c[1]), lengthSide)
            label(midpoint(c[1], c[2]), widthSide)

            if (lift == null) {
                derived(
                    "Area", Quantity.AREA, l * w,
                    listOf(w to lengthSide.estimate, l to widthSide.estimate),
                )
                report("Length", lengthSide)
                report("Width", widthSide)
                derived(
                    "Perimeter", Quantity.LENGTH, 2f * (l + w),
                    listOf(2f to lengthSide.estimate, 2f to widthSide.estimate),
                )
                return
            }

            val rise = normal * lift.height
            for (i in 0 until 4) {
                edge(c[i] + rise, c[(i + 1) % 4] + rise)
                edge(c[i], c[i] + rise)
            }
            val h = abs(lift.height)
            val heightSide = Measured(
                length = h,
                estimate = span(
                    h, worst(p0.observation, p1.observation, p2.observation), lift.observation,
                ),
            )
            label(c[1] + rise * 0.5f, heightSide)

            derived(
                "Volume", Quantity.VOLUME, l * w * h,
                listOf(w * h to lengthSide.estimate, l * h to widthSide.estimate, l * w to heightSide.estimate),
            )
            report("Length", lengthSide)
            report("Width", widthSide)
            report("Height", heightSide)
            derived(
                "Surface", Quantity.AREA, 2f * (l * w + l * h + w * h),
                listOf(
                    2f * (w + h) to lengthSide.estimate,
                    2f * (l + h) to widthSide.estimate,
                    2f * (l + w) to heightSide.estimate,
                ),
            )
        }

        fun build(): Solution {
            if (readings.isEmpty()) return Solution(wire, labels)
            // The shape is only as trustworthy as its least certain figure, and
            // the explanation should name whatever is hurting it most.
            val refused = spans.firstOrNull { !it.grade.hasTolerance }
            val explained = refused ?: spans.maxByOrNull { it.relative }
            return Solution(
                wire = wire,
                labels = labels,
                readings = readings,
                accuracy = ShapeAccuracy(
                    grade = readings.map { it.grade }.reduce(::worse),
                    reason = explained?.reason.orEmpty(),
                ),
            )
        }

        // ── Building blocks ──────────────────────────────────────────────────

        /** A straight span with its error bound. */
        private class Measured(val length: Float, val estimate: AccuracyEstimate)

        private fun edge(a: Vec3, b: Vec3) {
            wire += Segment3(a, b)
        }

        /** Bounds the error on one span and remembers it for the overall verdict. */
        private fun span(
            length: Float,
            a: AnchorObservation,
            b: AnchorObservation,
        ): AccuracyEstimate {
            val estimate = AccuracyEngine.estimate(
                AccuracyInput(length, conditions.cameraIsTracking, conditions.issue, a, b)
            )
            val widened = if (resolution > 0f && estimate.grade.hasTolerance) {
                // Independent of the tracking error, so it adds in quadrature.
                val tolerance = sqrt(estimate.toleranceMeters * estimate.toleranceMeters + resolution * resolution)
                val relative = tolerance / length
                estimate.copy(toleranceMeters = tolerance, relative = relative, grade = AccuracyEngine.gradeFor(relative))
            } else {
                estimate
            }
            return widened.also { spans += it }
        }

        /** Draws, labels and bounds the span between two placed points. */
        private fun measuredEdge(a: ObservedPoint, b: ObservedPoint): Measured {
            edge(a.position, b.position)
            val length = a.position.distanceTo(b.position)
            return Measured(length, span(length, a.observation, b.observation))
                .also { label(midpoint(a.position, b.position), it) }
        }

        private fun label(at: Vec3, measured: Measured) {
            labels += Label3(
                at, Quantity.LENGTH, measured.length,
                measured.estimate.toleranceOrNull(), measured.estimate.grade,
            )
        }

        private fun report(name: String, measured: Measured) {
            readings += Reading(
                name, Quantity.LENGTH, measured.length,
                measured.estimate.toleranceOrNull(), measured.estimate.grade,
            )
        }

        /**
         * A figure computed from spans. [terms] pairs each span's estimate with
         * the figure's partial derivative with respect to that span.
         */
        private fun derived(
            name: String,
            quantity: Quantity,
            value: Float,
            terms: List<Pair<Float, AccuracyEstimate>>,
        ) {
            val bounded = value > 0f && terms.all { it.second.grade.hasTolerance }
            if (!bounded) {
                readings += Reading(name, quantity, value, null, AccuracyGrade.UNAVAILABLE)
                return
            }
            var sumOfSquares = 0f
            for ((partial, estimate) in terms) {
                val contribution = partial * estimate.toleranceMeters
                sumOfSquares += contribution * contribution
            }
            val tolerance = sqrt(sumOfSquares)
            readings += Reading(
                name, quantity, value, tolerance, AccuracyEngine.gradeFor(tolerance / value),
            )
        }

        private fun ring(centre: Vec3, u: Vec3, v: Vec3, radius: Float) {
            var previous = centre + u * radius
            for (i in 1..CIRCLE_SEGMENTS) {
                val angle = (2.0 * PI * i / CIRCLE_SEGMENTS).toFloat()
                val next = centre + (u * cos(angle) + v * sin(angle)) * radius
                edge(previous, next)
                previous = next
            }
        }
    }

    private fun midpoint(a: Vec3, b: Vec3): Vec3 = (a + b) * 0.5f

    private inline fun <T> List<T>.total(selector: (T) -> Float): Float {
        var sum = 0f
        for (item in this) sum += selector(item)
        return sum
    }

    private fun AccuracyEstimate.toleranceOrNull(): Float? =
        if (grade.hasTolerance) toleranceMeters else null

    /** The least certain of several endpoints: a span is only as good as its worst end. */
    private fun worst(vararg observations: AnchorObservation) = AnchorObservation(
        placementRangeMeters = observations.maxOf { it.placementRangeMeters },
        surface = if (observations.any { it.surface == AnchorSurface.POINT }) {
            AnchorSurface.POINT
        } else {
            AnchorSurface.PLANE
        },
        isTracking = observations.all { it.isTracking },
    )

    /** HIGH < FAIR < LOW < UNAVAILABLE, in declaration order. */
    private fun worse(a: AccuracyGrade, b: AccuracyGrade): AccuracyGrade =
        if (a.ordinal >= b.ordinal) a else b
}
