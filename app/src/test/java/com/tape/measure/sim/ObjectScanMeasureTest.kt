package com.tape.measure.sim

import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.PendingIdentification
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import com.tape.measure.domain.model.AccuracyGrade
import com.tape.measure.domain.shape.MeasureSession
import com.tape.measure.domain.shape.MeasureTool
import com.tape.measure.domain.shape.Reading
import com.tape.measure.domain.shape.SessionState
import com.tape.measure.domain.shape.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Object tool, end to end in the practice room: aim at an object, tap,
 * look at it from a few places, Done — then compare with what the room was
 * built to be.
 *
 * Depth here is exact, so these tests isolate the segmentation and box
 * fitting. A phone's depth is noisier; that is what the tolerance is for, and
 * what the on-device check in TESTING.md is for.
 */
class ObjectScanMeasureTest {

    private val world = SimWorld.practiceRoom()
    private val session = MeasureSession().apply { select(MeasureTool.OBJECT) }

    private val width = 1080
    private val height = 2400

    private fun frame(
        camera: SimCamera,
        tap: Boolean = false,
        wrap: (ArFrame) -> ArFrame = { it },
    ): SessionState {
        val frame = SimFrame(world, camera, width, height)
        val projector = Projector(frame.viewMatrix, frame.projectionMatrix, width, height)
        return session.update(wrap(frame), projector, width / 2f, height / 2f, tap)
    }

    /** A camera [distance] from [target] (on the floor plan), [height] up, looking at it from [degrees] round. */
    private fun around(target: Vec3, degrees: Double, distance: Float, height: Float): SimCamera {
        val a = Math.toRadians(degrees)
        val position = Vec3(target.x + distance * sin(a).toFloat(), height, target.z + distance * cos(a).toFloat())
        return SimCamera(position, 0f, 0f).lookingAt(target)
    }

    private fun SessionState.reading(name: String): Reading =
        solution.readings.firstOrNull { it.name == name }
            ?: error("no '$name' in ${solution.readings.map { it.name }}; hint: $hint")

    /** Close to the truth, and honest about how close. */
    private fun assertMeasures(truth: Float, reading: Reading, within: Float) {
        assertEquals("${reading.name} is off", truth, reading.value, within)
        val tolerance = reading.tolerance ?: error("${reading.name} has no tolerance")
        assertTrue(
            "${reading.name}: truth $truth is outside ${reading.value} ±$tolerance",
            abs(reading.value - truth) <= tolerance,
        )
    }

    /** Taps [target] from [views] camera positions, then presses Done. */
    private fun scan(target: Vec3, views: List<SimCamera>): SessionState {
        val first = views.first()
        val started = frame(first, tap = true)
        assertEquals("tapping the object starts a scan: ${started.hint}", Stage.SCANNING, started.stage)
        views.drop(1).forEach { frame(it) }
        session.finish()
        return frame(views.last())
    }

    @Test
    fun `crate - one tap and a look from above gives its size`() {
        val crate = world.boxes.single()
        val top = crate.base + Vec3.UP * crate.height

        val state = scan(top, listOf(around(top, 20.0, 1.0f, 1.3f)))

        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(SimWorld.CRATE_LENGTH, state.reading("Length"), within = 0.025f)
        assertMeasures(SimWorld.CRATE_WIDTH, state.reading("Width"), within = 0.025f)
        assertMeasures(SimWorld.CRATE_HEIGHT, state.reading("Height"), within = 0.025f)
        assertEquals("Volume", state.solution.readings.first().name)
        assertTrue(state.canSave)
        assertEquals("Crate", state.objectName)
        assertTrue(state.hint, state.hint.isNotBlank())
    }

    @Test
    fun `the drum is named a drum`() {
        val drum = world.drums.single()
        val side = drum.base + Vec3.UP * (drum.height * 0.6f) + Vec3(0f, 0f, drum.radius)

        val state = scan(side, listOf(around(side, 0.0, 1.0f, 1.2f)))

        assertEquals("Drum", state.objectName)
    }

    @Test
    fun `slow identification gives up and measures anyway`() {
        // A phone that never finishes identifying must not leave the user
        // stuck on "Identifying…".
        val crate = world.boxes.single()
        val top = crate.base + Vec3.UP * crate.height
        val camera = around(top, 20.0, 1.0f, 1.3f)
        val neverDone: (ArFrame) -> ArFrame = { real ->
            object : ArFrame by real {
                override fun identify(x: Float, y: Float) = PendingIdentification { null }
            }
        }

        val tapped = frame(camera, tap = true, wrap = neverDone)
        assertEquals(Stage.IDENTIFYING, tapped.stage)
        assertEquals("Identifying…", tapped.hint)

        var state = tapped
        repeat(60) { state = frame(camera, wrap = neverDone) }

        assertEquals(Stage.SCANNING, state.stage)
        assertNull("no name was found", state.objectName)
        assertTrue(state.solution.readings.isNotEmpty())
    }

    @Test
    fun `crate - walking round it measures the same box`() {
        val crate = world.boxes.single()
        val top = crate.base + Vec3.UP * crate.height
        val views = listOf(-40.0, 0.0, 40.0, 80.0).map { around(top, it, 1.1f, 1.2f) }

        val state = scan(top, views)

        assertMeasures(SimWorld.CRATE_LENGTH, state.reading("Length"), within = 0.025f)
        assertMeasures(SimWorld.CRATE_WIDTH, state.reading("Width"), within = 0.025f)
        assertMeasures(SimWorld.CRATE_HEIGHT, state.reading("Height"), within = 0.025f)
    }

    @Test
    fun `drum - its bounding box is its diameter square and its height`() {
        val drum = world.drums.single()
        val side = drum.base + Vec3.UP * (drum.height * 0.6f) + Vec3(0f, 0f, drum.radius)

        val state = scan(side, listOf(around(side, 0.0, 1.0f, 1.2f), around(side, 35.0, 1.0f, 1.2f)))

        assertMeasures(SimWorld.DRUM_DIAMETER, state.reading("Length"), within = 0.03f)
        assertMeasures(SimWorld.DRUM_DIAMETER, state.reading("Width"), within = 0.03f)
        assertMeasures(SimWorld.DRUM_HEIGHT, state.reading("Height"), within = 0.025f)
    }

    @Test
    fun `the box stops at the object - not the floor or the wall behind it`() {
        val crate = world.boxes.single()
        val front = crate.faces.drop(1).maxBy { it.normal.z }   // the side facing the near end of the room

        val state = scan(front.centre, listOf(around(front.centre, 0.0, 1.2f, 1.0f)))

        assertTrue("got ${state.reading("Length").value} m", state.reading("Length").value < 0.7f)
        assertTrue("got ${state.reading("Height").value} m", state.reading("Height").value < 0.45f)
    }

    @Test
    fun `seen from one side only it says the far side is a guess`() {
        // Low down, level with the crate's side: its top and back are hidden.
        val crate = world.boxes.single()
        val front = crate.faces.drop(1).maxBy { it.normal.z }
        val low = SimCamera(front.centre + front.normal * 1.0f + Vec3(0f, -0.02f, 0f), 0f, 0f).lookingAt(front.centre)

        val state = scan(front.centre, listOf(low))

        assertEquals(AccuracyGrade.LOW, state.solution.accuracy!!.grade)
        assertTrue(state.hint, state.hint.contains("far side", ignoreCase = true))
    }

    @Test
    fun `a tap right at the object's edge still finds the object`() {
        // From the far end of the room, a point on the crate's back edge has
        // floor cells behind it that are nearer than any crate cell.
        val crate = world.boxes.single()
        val backEdge = crate.topCorners.minBy { it.z }.let { corner ->
            val centre = crate.base + Vec3.UP * crate.height
            corner + (centre - corner) * 0.02f
        }

        val state = scan(backEdge, listOf(SimCamera.START.lookingAt(backEdge)))

        assertTrue("no box from an edge tap: ${state.hint}", state.solution.readings.isNotEmpty())
    }

    @Test
    fun `tapping the floor asks for the object instead`() {
        val floorSpot = Vec3(0f, 0f, -0.5f)

        val state = frame(around(floorSpot, 0.0, 1.2f, 1.4f), tap = true)

        assertEquals(Stage.AIM_SURFACE, state.stage)
        assertTrue(state.hint, state.hint.contains("floor", ignoreCase = true))
    }

    @Test
    fun `undo while scanning goes back to aiming`() {
        val crate = world.boxes.single()
        val top = crate.base + Vec3.UP * crate.height
        frame(around(top, 20.0, 1.0f, 1.3f), tap = true)

        session.undo()
        val state = frame(around(top, 20.0, 1.0f, 1.3f))

        assertEquals(Stage.AIM_SURFACE, state.stage)
        assertFalse(state.canUndo)
        assertTrue(state.solution.readings.isEmpty())
    }

    @Test
    fun `done appears once there is a box to keep`() {
        val crate = world.boxes.single()
        val top = crate.base + Vec3.UP * crate.height

        val state = frame(around(top, 20.0, 1.0f, 1.3f), tap = true)

        assertTrue(state.canFinish)
        assertTrue(state.solution.readings.isNotEmpty())
    }
}
