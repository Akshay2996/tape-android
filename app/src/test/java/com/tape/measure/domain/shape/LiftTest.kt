package com.tape.measure.domain.shape

import com.tape.measure.domain.geometry.Ray
import com.tape.measure.domain.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Setting a height by tilting the phone: the reticle ray against the imaginary
 * wall standing on the base.
 */
class LiftTest {

    private val eye = Vec3(0f, 1.5f, 2f)

    private fun rayTo(target: Vec3) = Ray(eye, (target - eye).normalized()!!)

    @Test
    fun `height above a single point`() {
        val base = Vec3(0f, 0f, 0f)

        val hit = Lift.solve(MeasureTool.HEIGHT, listOf(base), Vec3.UP, rayTo(Vec3(0f, 2f, 0f)))

        assertEquals(2f, hit!!.height, 1e-4f)
    }

    @Test
    fun `aiming to one side of the point still reads the height aimed at`() {
        // The wall faces the camera, so sideways aim doesn't change the height.
        val base = Vec3(0f, 0f, 0f)

        val hit = Lift.solve(MeasureTool.HEIGHT, listOf(base), Vec3.UP, rayTo(Vec3(0.3f, 1.2f, 0f)))

        assertEquals(1.2f, hit!!.height, 1e-4f)
    }

    @Test
    fun `a box raised from the floor reads the height of the edge aimed at`() {
        val base = listOf(Vec3(-0.3f, 0f, 0.2f), Vec3(0.3f, 0f, 0.2f), Vec3(0.3f, 0f, -0.2f))
        val topFrontEdge = Vec3(0.1f, 0.35f, 0.2f)

        val hit = Lift.solve(MeasureTool.BOX, base, Vec3.UP, rayTo(topFrontEdge))

        assertEquals(0.35f, hit!!.height, 1e-4f)
    }

    @Test
    fun `a box sketched on its top is pulled down to the floor`() {
        // The practical way to measure a real box: its top corners are all
        // visible, the bottom back edge never is.
        val top = listOf(Vec3(-0.3f, 0.35f, 0.2f), Vec3(0.3f, 0.35f, 0.2f), Vec3(0.3f, 0.35f, -0.2f))
        val bottomFrontEdge = Vec3(-0.1f, 0f, 0.2f)

        val hit = Lift.solve(MeasureTool.BOX, top, Vec3.UP, rayTo(bottomFrontEdge))

        assertEquals(-0.35f, hit!!.height, 1e-4f)
    }

    @Test
    fun `the near side of a box wins over the far side`() {
        // The ray crosses the plane of every side; only the one facing the
        // camera is the one the user is looking at.
        val base = listOf(Vec3(-0.3f, 0f, 0.2f), Vec3(0.3f, 0f, 0.2f), Vec3(0.3f, 0f, -0.2f))
        val onFrontFace = Vec3(0f, 0.2f, 0.2f)

        val hit = Lift.solve(MeasureTool.BOX, base, Vec3.UP, rayTo(onFrontFace))

        assertEquals(0.2f, hit!!.point.z, 1e-4f)
        assertEquals(0.2f, hit.height, 1e-4f)
    }

    @Test
    fun `a cylinder reads the height on its curved side`() {
        val rim = listOf(Vec3(-0.2f, 0f, 0f), Vec3(0.2f, 0f, 0f))
        val nearestPointOnSide = Vec3(0f, 0.5f, 0.2f)

        val hit = Lift.solve(MeasureTool.CYLINDER, rim, Vec3.UP, rayTo(nearestPointOnSide))

        assertEquals(0.5f, hit!!.height, 1e-4f)
    }

    @Test
    fun `aiming past the side of a cylinder still gives a height`() {
        val rim = listOf(Vec3(-0.2f, 0f, 0f), Vec3(0.2f, 0f, 0f))

        val hit = Lift.solve(MeasureTool.CYLINDER, rim, Vec3.UP, rayTo(Vec3(0.6f, 0.5f, 0f)))

        assertNotNull(hit)
    }

    @Test
    fun `no height without a complete base`() {
        val ray = rayTo(Vec3(0f, 1f, 0f))

        assertNull(Lift.solve(MeasureTool.BOX, listOf(Vec3.ZERO, Vec3(1f, 0f, 0f)), Vec3.UP, ray))
        assertNull(Lift.solve(MeasureTool.CYLINDER, listOf(Vec3.ZERO), Vec3.UP, ray))
        assertNull(Lift.solve(MeasureTool.HEIGHT, emptyList(), Vec3.UP, ray))
    }

    @Test
    fun `tools without a height step have none`() {
        val ray = rayTo(Vec3(0f, 1f, 0f))

        assertNull(Lift.solve(MeasureTool.LINE, listOf(Vec3.ZERO, Vec3(1f, 0f, 0f)), Vec3.UP, ray))
    }

    @Test
    fun `looking straight down the axis gives no height`() {
        val above = Ray(Vec3(0f, 3f, 0f), Vec3(0f, -1f, 0f))

        assertNull(Lift.solve(MeasureTool.HEIGHT, listOf(Vec3.ZERO), Vec3.UP, above))
        assertNull(
            Lift.solve(MeasureTool.CYLINDER, listOf(Vec3(-0.2f, 0f, 0f), Vec3(0.2f, 0f, 0f)), Vec3.UP, above)
        )
    }
}
