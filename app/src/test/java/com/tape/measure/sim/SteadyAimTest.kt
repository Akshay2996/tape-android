package com.tape.measure.sim

import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.SurfaceHit
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import com.tape.measure.domain.shape.MeasureSession
import com.tape.measure.domain.shape.MeasureTool
import com.tape.measure.domain.shape.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A phone's hit point wobbles from frame to frame. A tap must use the
 * agreement of the last few frames, not whichever reading the tap landed on.
 */
class SteadyAimTest {

    private val world = SimWorld.practiceRoom()
    private val width = 1080
    private val height = 2400

    /** The same view, with the hit pushed sideways by [wobble] metres this frame. */
    private fun wobbly(camera: SimCamera, wobble: Float): ArFrame {
        val real = SimFrame(world, camera, width, height)
        return object : ArFrame by real {
            override fun hitTest(x: Float, y: Float): SurfaceHit? {
                val hit = real.hitTest(x, y) ?: return null
                return object : SurfaceHit by hit {
                    override val point = hit.point + Vec3(wobble, 0f, 0f)
                    override fun pin(at: Vec3) = hit.pin(at)
                }
            }
        }
    }

    private fun MeasureSession.step(camera: SimCamera, wobble: Float, tap: Boolean = false): SessionState {
        val frame = wobbly(camera, wobble)
        val projector = Projector(frame.viewMatrix, frame.projectionMatrix, width, height)
        return update(frame, projector, width / 2f, height / 2f, tap)
    }

    /** Holds on [target] for a few wobbling frames, then taps on a frame that wobbled [atTap]. */
    private fun MeasureSession.holdAndTap(target: Vec3, atTap: Float, hold: List<Float>): SessionState {
        val camera = SimCamera.START.lookingAt(target)
        hold.forEach { step(camera, it) }
        return step(camera, atTap, tap = true)
    }

    private val wobbles = listOf(-0.006f, 0f, 0.004f, -0.003f, 0.006f, -0.005f, 0.002f, 0f)

    @Test
    fun `a tap uses the steady reading, not the frame it landed on`() {
        val session = MeasureSession().apply { select(MeasureTool.LINE) }
        val door = SimWorld.DOOR_CORNERS

        // Both taps land on a frame that wobbled 6 mm the same way: on their
        // own they would make the door read 12 mm narrow... or not at all, if
        // they wobbled together. Make them wobble apart.
        session.holdAndTap(door[0], atTap = 0.006f, hold = wobbles)
        val state = session.holdAndTap(door[1], atTap = -0.006f, hold = wobbles)

        val length = state.solution.readings.first { it.name == "Length" }.value
        assertTrue(
            "steadied ${length} m should be well within the 12 mm a single frame would give",
            abs(length - SimWorld.DOOR_WIDTH) < 0.004f,
        )
    }

    @Test
    fun `a moving phone uses the tap's own reading`() {
        // Readings far from the tapped one come from before the phone settled:
        // they must not drag the point back to where it was aimed earlier.
        val session = MeasureSession().apply { select(MeasureTool.LINE) }
        val door = SimWorld.DOOR_CORNERS
        val earlier = SimCamera.START.lookingAt(door[3])   // the top of the door, far away

        repeat(8) { session.step(earlier, 0f) }
        session.step(SimCamera.START.lookingAt(door[0]), 0f, tap = true)
        val state = session.step(SimCamera.START.lookingAt(door[1]), 0f, tap = true)

        assertEquals(SimWorld.DOOR_WIDTH, state.solution.readings.first { it.name == "Length" }.value, 0.001f)
    }
}
