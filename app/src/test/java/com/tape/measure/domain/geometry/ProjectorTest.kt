package com.tape.measure.domain.geometry

import com.tape.measure.sim.SimCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectorTest {

    private val width = 1080
    private val height = 2400
    private val camera = SimCamera(Vec3(0.3f, 1.4f, 1.2f), yawDegrees = 20f, pitchDegrees = -15f)
    private val projector = Projector(
        camera.viewMatrix(),
        Projector.perspective(60f, width.toFloat() / height),
        width,
        height,
    )

    @Test
    fun `camera position is recovered from the view matrix`() {
        val p = projector.cameraPosition

        assertEquals(0.3f, p.x, 1e-5f)
        assertEquals(1.4f, p.y, 1e-5f)
        assertEquals(1.2f, p.z, 1e-5f)
    }

    @Test
    fun `the centre of the screen looks straight ahead`() {
        val ray = projector.rayThrough(width / 2f, height / 2f)
        val forward = camera.forward

        assertEquals(forward.x, ray.direction.x, 1e-5f)
        assertEquals(forward.y, ray.direction.y, 1e-5f)
        assertEquals(forward.z, ray.direction.z, 1e-5f)
    }

    @Test
    fun `a point projects back onto the pixel its ray came from`() {
        // The overlay and the hit test must agree about where things are, or
        // dots would drift off the points they mark.
        for ((x, y) in listOf(540f to 1200f, 100f to 300f, 1000f to 2200f, 37f to 1999f)) {
            val onScreen = projector.project(projector.rayThrough(x, y).at(2.5f))

            assertNotNull(onScreen)
            assertEquals(x, onScreen!!.x, 0.05f)
            assertEquals(y, onScreen.y, 0.05f)
        }
    }

    @Test
    fun `a point behind the camera is not drawn`() {
        val behind = camera.position - camera.forward * 2f

        assertNull(projector.project(behind))
    }

    @Test
    fun `a segment that runs behind the camera is cut, not dropped`() {
        val ahead = camera.position + camera.forward * 2f
        val behind = camera.position - camera.forward * 2f

        val cut = projector.projectSegment(ahead, behind)

        assertNotNull("the visible half must still be drawn", cut)
        assertTrue(cut!!.a.x.isFinite() && cut.b.x.isFinite() && cut.b.y.isFinite())
        assertNull(projector.projectSegment(behind, behind - camera.forward))
    }

    @Test
    fun `a polygon partly behind the camera keeps a drawable outline`() {
        val f = camera.forward
        val side = (f cross Vec3.UP).normalized()!!
        val origin = camera.position
        val quad = listOf(
            origin + f * 3f - side,
            origin + f * 3f + side,
            origin - f * 3f + side,
            origin - f * 3f - side,
        )

        val outline = projector.projectPolygon(quad)

        assertEquals("two corners cut away, two cut points added", 4, outline.size)
        assertTrue(outline.all { it.x.isFinite() && it.y.isFinite() })
    }
}
