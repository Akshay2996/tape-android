package com.tape.measure.domain.scan

import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.DepthGrid
import com.tape.measure.domain.ar.PendingIdentification
import com.tape.measure.domain.ar.SurfaceHit
import com.tape.measure.domain.ar.SurfacePlane
import com.tape.measure.domain.ar.TrackingStatus
import com.tape.measure.domain.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan's guards, on hand-made depth frames: what a phone's depth can do
 * that the practice room's exact depth never does.
 */
class ObjectScanTest {

    private val floor = object : SurfacePlane {
        override val point = Vec3.ZERO
        override val normal = Vec3.UP
        override val area = 10f
        override fun contains(p: Vec3) = true
    }

    /** A depth frame whose cells see [cells]: (column, row) → world point. */
    private fun frame(
        cells: Map<Pair<Int, Int>, Vec3>,
        issue: TrackingIssue = TrackingIssue.NONE,
    ) = object : ArFrame {
        override val status = TrackingStatus.TRACKING
        override val issue = issue
        override val viewMatrix = FloatArray(16)
        override val projectionMatrix = FloatArray(16)
        override fun hitTest(x: Float, y: Float): SurfaceHit? = null
        override fun planes(): List<SurfacePlane> = listOf(floor)
        override fun identify(x: Float, y: Float) = PendingIdentification { null }
        override fun depthGrid(): DepthGrid {
            val points = arrayOfNulls<Vec3>(GRID * GRID)
            for ((cell, p) in cells) points[cell.second * GRID + cell.first] = p
            return DepthGrid(GRID, GRID, points, cellAngle = 0.005f, cameraPosition = Vec3(0.1f, 1.2f, 1f))
        }
    }

    /** A 0.1 m square patch of an object's top, 0.3 m up, one cell per centimetre. */
    private val patch: Map<Pair<Int, Int>, Vec3> =
        (0..10).flatMap { c -> (0..10).map { r -> (c to r) to Vec3(c * 0.01f, 0.3f, r * 0.01f) } }.toMap()

    private fun started(seed: Vec3): ObjectScan =
        (ObjectScan.start(seed, frame(patch)) as ObjectScan.Start.Started).scan

    @Test
    fun `a frame that would stretch the box far past the object is ignored`() {
        val scan = started(Vec3(0.05f, 0.3f, 0.05f))
        scan.add(frame(patch))
        val before = scan.fit!!

        // The same patch, now joined to a 0.7 m run of something it touches.
        val leak = patch + (11 until GRID).associate { c -> (c to 5) to Vec3(c * 0.01f + (c - 10) * 0.02f, 0.3f, 0.05f) }
        scan.add(frame(leak))

        assertEquals("the leaking frame must not count", 1, scan.views)
        assertEquals(before.corners, scan.fit!!.corners)
    }

    @Test
    fun `a stray depth reading does not stretch the box`() {
        // One reading 3 cm above the top, next to it in the depth image: near
        // enough to join the object, alone enough to be noise.
        val stray = patch + ((11 to 5) to Vec3(0.11f, 0.33f, 0.05f))
        val scan = started(Vec3(0.05f, 0.3f, 0.05f))

        scan.add(frame(stray))

        assertTrue("height ${scan.fit!!.height} m was raised by one stray point", scan.fit!!.height < 0.315f)
    }

    @Test
    fun `frames taken while the phone moves too fast are skipped`() {
        val scan = started(Vec3(0.05f, 0.3f, 0.05f))

        scan.add(frame(patch, issue = TrackingIssue.EXCESSIVE_MOTION))

        assertNull(scan.fit)
        assertEquals(0, scan.views)
    }

    @Test
    fun `a steady frame of the object gives a box`() {
        val scan = started(Vec3(0.05f, 0.3f, 0.05f))

        scan.add(frame(patch))

        val fit = scan.fit
        assertNotNull(fit)
        assertEquals(0.3f, fit!!.height, 0.02f)
        assertTrue("stands on the floor", fit.corners.all { it.y == 0f })
    }

    private companion object {
        const val GRID = 40
    }
}
