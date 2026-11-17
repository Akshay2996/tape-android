package com.tape.measure.domain.shape

import com.tape.measure.domain.accuracy.AnchorObservation
import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.geometry.Vec3
import com.tape.measure.domain.model.AccuracyGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The shape maths, checked against shapes whose answers are known by hand.
 *
 * Geometry bugs here are invisible on a phone — a box that reads 3% too big
 * still looks like a plausible box — so every tool is pinned to exact figures.
 */
class ShapeSolverTest {

    private val good = Conditions(cameraIsTracking = true, issue = TrackingIssue.NONE)

    private fun point(
        x: Float, y: Float, z: Float,
        range: Float = 0.5f,
        surface: AnchorSurface = AnchorSurface.PLANE,
    ) = ObservedPoint(Vec3(x, y, z), AnchorObservation(range, surface, isTracking = true))

    private fun lift(height: Float, range: Float = 0.5f) =
        ObservedLift(height, AnchorObservation(range, AnchorSurface.POINT, isTracking = true))

    private fun Solution.value(name: String): Float =
        readings.first { it.name == name }.value

    private fun Solution.reading(name: String): Reading =
        readings.first { it.name == name }

    private fun solve(
        tool: MeasureTool,
        points: List<ObservedPoint>,
        lift: ObservedLift? = null,
        normal: Vec3 = Vec3.UP,
        conditions: Conditions = good,
    ) = ShapeSolver.solve(tool, points, normal, lift, conditions)

    // ── Line and path ────────────────────────────────────────────────────────

    @Test
    fun `line measures the straight distance between two points`() {
        val s = solve(MeasureTool.LINE, listOf(point(0f, 0f, 0f), point(3f, 0f, 4f)))

        assertEquals(5f, s.value("Length"), 1e-5f)
        assertEquals(1, s.wire.size)
        assertEquals("the edge carries its own label", 1, s.labels.size)
    }

    @Test
    fun `one point is not a measurement`() {
        val s = solve(MeasureTool.LINE, listOf(point(0f, 0f, 0f)))

        assertTrue(s.readings.isEmpty())
        assertNull("no reading means no verdict to show", s.accuracy)
    }

    @Test
    fun `path adds up its segments and its tolerance grows slower than their sum`() {
        val points = listOf(point(0f, 0f, 0f), point(1f, 0f, 0f), point(1f, 0f, 2f))
        val s = solve(MeasureTool.PATH, points)
        val total = s.reading("Length")

        assertEquals(3f, total.value, 1e-5f)
        assertEquals(2, s.labels.size)

        // Independent errors combine in quadrature, not by plain addition.
        val perSegment = s.labels.map { it.tolerance!! }
        assertEquals(sqrt(perSegment.sumOf { (it * it).toDouble() }).toFloat(), total.tolerance!!, 1e-6f)
        assertTrue(total.tolerance!! < perSegment.sum())
    }

    // ── Area ─────────────────────────────────────────────────────────────────

    @Test
    fun `area of a rectangle on the floor`() {
        val corners = listOf(point(0f, 0f, 0f), point(1.2f, 0f, 0f), point(1.2f, 0f, -0.8f), point(0f, 0f, -0.8f))
        val s = solve(MeasureTool.AREA, corners)

        assertEquals(0.96f, s.value("Area"), 1e-5f)
        assertEquals(4f, s.value("Perimeter"), 1e-5f)
        assertEquals("the shape closes back to its first corner", 4, s.wire.size)
    }

    @Test
    fun `area works on a wall as well as a floor`() {
        // A right triangle standing upright: orientation must not matter.
        val corners = listOf(point(0f, 0f, -2f), point(3f, 0f, -2f), point(0f, 4f, -2f))
        val s = solve(MeasureTool.AREA, corners)

        assertEquals(6f, s.value("Area"), 1e-5f)
        assertEquals(12f, s.value("Perimeter"), 1e-5f)
    }

    // ── Angle ────────────────────────────────────────────────────────────────

    @Test
    fun `angle at a square corner is ninety degrees`() {
        val s = solve(MeasureTool.ANGLE, listOf(point(1f, 0f, 0f), point(0f, 0f, 0f), point(0f, 0f, -1f)))
        val angle = s.reading("Angle")

        assertEquals(90f, angle.value, 1e-3f)
        assertEquals(Quantity.ANGLE, angle.quantity)
        assertNotNull("an angle carries a tolerance in degrees", angle.tolerance)
        assertTrue("a degree or so on good arms, got ${angle.tolerance}", angle.tolerance!! in 0.1f..3f)
    }

    // ── Height ───────────────────────────────────────────────────────────────

    @Test
    fun `height is the lift, whichever way it points`() {
        val up = solve(MeasureTool.HEIGHT, listOf(point(0f, 0f, 0f)), lift(2f))
        val down = solve(MeasureTool.HEIGHT, listOf(point(0f, 0.75f, 0f)), lift(-0.75f))

        assertEquals(2f, up.value("Height"), 1e-5f)
        assertEquals(0.75f, down.value("Height"), 1e-5f)
    }

    @Test
    fun `height has nothing to report until the phone is tilted`() {
        val s = solve(MeasureTool.HEIGHT, listOf(point(0f, 0f, 0f)), lift = null)

        assertTrue(s.readings.isEmpty())
    }

    // ── Circle and cylinder ──────────────────────────────────────────────────

    @Test
    fun `circle from two opposite points`() {
        val s = solve(MeasureTool.CIRCLE, listOf(point(-0.2f, 0f, 0f), point(0.2f, 0f, 0f)))
        val pi = PI.toFloat()

        assertEquals(0.4f, s.value("Diameter"), 1e-5f)
        assertEquals(pi * 0.4f, s.value("Circumference"), 1e-5f)
        assertEquals(pi * 0.04f, s.value("Area"), 1e-5f)
    }

    @Test
    fun `area tolerance of a circle is twice the relative tolerance of its diameter`() {
        // Area goes with the square of the diameter, so its relative error doubles.
        val s = solve(MeasureTool.CIRCLE, listOf(point(-1f, 0f, 0f), point(1f, 0f, 0f)))
        val diameter = s.reading("Diameter")
        val area = s.reading("Area")

        assertEquals(
            2f * diameter.tolerance!! / diameter.value,
            area.tolerance!! / area.value,
            1e-5f,
        )
    }

    @Test
    fun `cylinder volume and surface`() {
        val s = solve(
            MeasureTool.CYLINDER,
            listOf(point(-0.2f, 0f, 0f), point(0.2f, 0f, 0f)),
            lift(0.5f),
        )
        val pi = PI.toFloat()

        assertEquals(0.4f, s.value("Diameter"), 1e-5f)
        assertEquals(0.5f, s.value("Height"), 1e-5f)
        assertEquals(pi * 0.04f * 0.5f, s.value("Volume"), 1e-5f)
        assertEquals(2f * pi * 0.04f + pi * 0.4f * 0.5f, s.value("Surface"), 1e-5f)
        assertEquals("volume is the headline figure", "Volume", s.readings.first().name)
    }

    // ── Box ──────────────────────────────────────────────────────────────────

    @Test
    fun `three points make a rectangle`() {
        // Third point only has to be somewhere on the opposite edge.
        val s = solve(MeasureTool.BOX, listOf(point(0f, 0f, 0f), point(0.6f, 0f, 0f), point(0.17f, 0f, -0.4f)))

        assertEquals(0.6f, s.value("Length"), 1e-5f)
        assertEquals(0.4f, s.value("Width"), 1e-5f)
        assertEquals(0.24f, s.value("Area"), 1e-5f)
        assertEquals(2f, s.value("Perimeter"), 1e-5f)
        assertEquals(4, s.wire.size)
    }

    @Test
    fun `box volume and surface`() {
        val s = solve(
            MeasureTool.BOX,
            listOf(point(0f, 0f, 0f), point(0.6f, 0f, 0f), point(0f, 0f, 0.4f)),
            lift(0.35f),
        )

        assertEquals(0.6f * 0.4f * 0.35f, s.value("Volume"), 1e-5f)
        assertEquals(2f * (0.24f + 0.21f + 0.14f), s.value("Surface"), 1e-5f)
        assertEquals(0.35f, s.value("Height"), 1e-5f)
        assertEquals("twelve edges", 12, s.wire.size)
        assertEquals("length, width and height are each labelled", 3, s.labels.size)
    }

    @Test
    fun `a box sketched on its top face and pulled down measures the same`() {
        val raised = solve(
            MeasureTool.BOX,
            listOf(point(0f, 0f, 0f), point(0.6f, 0f, 0f), point(0f, 0f, 0.4f)),
            lift(0.35f),
        )
        val lowered = solve(
            MeasureTool.BOX,
            listOf(point(0f, 0.35f, 0f), point(0.6f, 0.35f, 0f), point(0f, 0.35f, 0.4f)),
            lift(-0.35f),
        )

        assertEquals(raised.value("Volume"), lowered.value("Volume"), 1e-6f)
    }

    @Test
    fun `box volume tolerance follows from its three sides`() {
        val s = solve(
            MeasureTool.BOX,
            listOf(point(0f, 0f, 0f), point(2f, 0f, 0f), point(0f, 0f, 1.5f)),
            lift(1f),
        )
        val l = s.reading("Length")
        val w = s.reading("Width")
        val h = s.reading("Height")
        val volume = s.reading("Volume")

        // For a product, relative errors combine in quadrature.
        val expected = sqrt(
            (l.tolerance!! / l.value).let { it * it } +
                (w.tolerance!! / w.value).let { it * it } +
                (h.tolerance!! / h.value).let { it * it }
        )
        assertEquals(expected, volume.tolerance!! / volume.value, 1e-5f)
        assertTrue(
            "a figure built from three uncertain sides can't be tighter than any one of them",
            volume.tolerance!! / volume.value > l.tolerance!! / l.value,
        )
    }

    // ── Accuracy verdict ─────────────────────────────────────────────────────

    @Test
    fun `the tilted height is graded as an off-plane point`() {
        // Nothing was tapped at the top, so it must not be trusted like a
        // point on a detected plane.
        val s = solve(MeasureTool.HEIGHT, listOf(point(0f, 0f, 0f)), lift(2f))
        val onPlanes = solve(MeasureTool.LINE, listOf(point(0f, 0f, 0f), point(0f, 2f, 0f)))

        assertTrue(s.reading("Height").tolerance!! > onPlanes.reading("Length").tolerance!!)
    }

    @Test
    fun `a shape is graded by its least certain figure`() {
        val s = solve(
            MeasureTool.BOX,
            listOf(point(0f, 0f, 0f), point(2f, 0f, 0f), point(0f, 0f, 1.5f, range = 5f)),
            lift(1f),
        )
        val worst = s.readings.maxOf { it.grade.ordinal }

        assertEquals(worst, s.accuracy!!.grade.ordinal)
        assertTrue(s.accuracy!!.reason.isNotBlank())
    }

    @Test
    fun `tracking trouble widens every figure and is named in the reason`() {
        val points = listOf(point(0f, 0f, 0f), point(0.6f, 0f, 0f), point(0f, 0f, 0.4f))
        val clear = solve(MeasureTool.BOX, points, lift(0.35f))
        val dim = solve(
            MeasureTool.BOX, points, lift(0.35f),
            conditions = Conditions(cameraIsTracking = true, issue = TrackingIssue.INSUFFICIENT_LIGHT),
        )

        assertTrue(dim.reading("Volume").tolerance!! > clear.reading("Volume").tolerance!!)
        assertTrue(
            "got: ${dim.accuracy!!.reason}",
            dim.accuracy!!.reason.contains("light", ignoreCase = true),
        )
    }

    @Test
    fun `a figure whose sides can't be bounded shows no tolerance`() {
        val s = solve(
            MeasureTool.BOX,
            listOf(point(0f, 0f, 0f), point(0.6f, 0f, 0f), point(0f, 0f, 0.4f)),
            lift(0.35f),
            conditions = Conditions(cameraIsTracking = false, issue = TrackingIssue.NONE),
        )

        assertEquals(AccuracyGrade.UNAVAILABLE, s.accuracy!!.grade)
        assertTrue(s.readings.all { it.tolerance == null })
    }

    @Test
    fun `refusal says why`() {
        val cameraLost = ShapeSolver.refusal(Conditions(cameraIsTracking = false, issue = TrackingIssue.NONE))
        val pointLost = ShapeSolver.refusal(good)

        assertEquals(AccuracyGrade.UNAVAILABLE, cameraLost.grade)
        assertTrue(cameraLost.reason.contains("Camera", ignoreCase = true))
        assertEquals(AccuracyGrade.UNAVAILABLE, pointLost.grade)
        assertTrue(pointLost.reason.contains("point", ignoreCase = true))
    }
}
