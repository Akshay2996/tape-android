package com.tape.measure.sim

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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * End-to-end: drive every tool the way a user would — aim the reticle, tap —
 * inside the practice room, and compare what Tape reports with what the room
 * was built to be.
 *
 * This covers everything between a camera frame and the numbers on screen:
 * hit testing, placing, the height step, shape solving and error bounds. Only
 * the ARCore adapter itself is outside it.
 *
 * Two things are checked for each tool: the reading is close to the truth, and
 * the truth lies inside the reading's own stated tolerance. The second is the
 * honesty check — a reading that is close but claims to be closer is a bug.
 */
class PracticeRoomMeasureTest {

    private class Rig {
        val world = SimWorld.practiceRoom()
        val session = MeasureSession()
        var camera = SimCamera.START

        private val width = 1080
        private val height = 2400

        /** One camera frame; [tap] is whether the user tapped during it. */
        fun frame(tap: Boolean = false): SessionState {
            val frame = SimFrame(world, camera, width, height)
            val projector = Projector(frame.viewMatrix, frame.projectionMatrix, width, height)
            return session.update(frame, projector, width / 2f, height / 2f, tap)
        }

        /** Turn the phone so the reticle is on [target], then tap. */
        fun tapAt(target: Vec3): SessionState {
            camera = camera.lookingAt(target)
            return frame(tap = true)
        }

        fun aimAt(target: Vec3): SessionState {
            camera = camera.lookingAt(target)
            return frame()
        }
    }

    private fun SessionState.reading(name: String): Reading =
        solution.readings.firstOrNull { it.name == name }
            ?: error("no '$name' in ${solution.readings.map { it.name }}")

    /** Close to the truth, and honest about how close. */
    private fun assertMeasures(truth: Float, reading: Reading, within: Float) {
        assertEquals("${reading.name} is off", truth, reading.value, within)
        val tolerance = reading.tolerance ?: error("${reading.name} has no tolerance")
        assertTrue(
            "${reading.name}: truth $truth is outside ${reading.value} ±$tolerance",
            abs(reading.value - truth) <= tolerance,
        )
    }

    /** [corner] moved [by] metres towards [centre], so the reticle lands on the face, not past it. */
    private fun inset(corner: Vec3, centre: Vec3, by: Float = 0.002f): Vec3 =
        corner + (centre - corner).normalized()!! * by

    // ── Flat tools ───────────────────────────────────────────────────────────

    @Test
    fun `line - width of the door`() {
        val rig = Rig()
        val door = SimWorld.DOOR_CORNERS

        rig.tapAt(door[0])
        val state = rig.tapAt(door[1])

        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(SimWorld.DOOR_WIDTH, state.reading("Length"), within = 0.001f)
        assertTrue(state.canSave)
    }

    @Test
    fun `the measurement is previewed before the second tap`() {
        val rig = Rig()
        val door = SimWorld.DOOR_CORNERS

        rig.tapAt(door[0])
        val preview = rig.aimAt(door[1])

        assertEquals(Stage.AIM_SURFACE, preview.stage)
        assertEquals(SimWorld.DOOR_WIDTH, preview.reading("Length").value, 0.001f)
        assertFalse("a preview is not a measurement to keep", preview.canSave)
    }

    @Test
    fun `path - round three sides of the rug`() {
        val rig = Rig()
        rig.session.select(MeasureTool.PATH)
        val rug = SimWorld.RUG_CORNERS

        rug.forEach { rig.tapAt(it) }
        rig.session.finish()
        val state = rig.frame()

        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(
            SimWorld.RUG_LENGTH + SimWorld.RUG_WIDTH + SimWorld.RUG_LENGTH,
            state.reading("Length"),
            within = 0.001f,
        )
    }

    @Test
    fun `area - the rug`() {
        val rig = Rig()
        rig.session.select(MeasureTool.AREA)

        SimWorld.RUG_CORNERS.forEach { rig.tapAt(it) }
        assertTrue(rig.frame().canFinish)
        rig.session.finish()
        val state = rig.frame()

        assertMeasures(SimWorld.RUG_LENGTH * SimWorld.RUG_WIDTH, state.reading("Area"), within = 0.001f)
        assertMeasures(2f * (SimWorld.RUG_LENGTH + SimWorld.RUG_WIDTH), state.reading("Perimeter"), within = 0.001f)
    }

    @Test
    fun `angle - corner of the rug`() {
        val rig = Rig()
        rig.session.select(MeasureTool.ANGLE)
        val rug = SimWorld.RUG_CORNERS

        rig.tapAt(rug[0])
        rig.tapAt(rug[1])
        val state = rig.tapAt(rug[2])

        assertMeasures(90f, state.reading("Angle"), within = 0.05f)
    }

    // ── Tools with a height step ─────────────────────────────────────────────

    @Test
    fun `height - the door, from threshold to lintel`() {
        val rig = Rig()
        rig.session.select(MeasureTool.HEIGHT)
        val door = SimWorld.DOOR_CORNERS

        val afterBase = rig.tapAt(door[0])
        assertEquals(Stage.AIM_LIFT, afterBase.stage)
        val state = rig.tapAt(door[3])

        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(SimWorld.DOOR_HEIGHT, state.reading("Height"), within = 0.002f)
    }

    @Test
    fun `circle - top of the drum`() {
        val rig = Rig()
        rig.session.select(MeasureTool.CIRCLE)
        val drum = rig.world.drums.single()
        val top = drum.base + Vec3.UP * drum.height
        val left = top + Vec3(-drum.radius, 0f, 0f)
        val right = top + Vec3(drum.radius, 0f, 0f)

        rig.tapAt(inset(left, top))
        val state = rig.tapAt(inset(right, top))

        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(SimWorld.DRUM_DIAMETER, state.reading("Diameter"), within = 0.006f)
        assertMeasures(
            (PI * SimWorld.DRUM_DIAMETER).toFloat(), state.reading("Circumference"), within = 0.02f,
        )
    }

    @Test
    fun `cylinder - the drum, top rim then down to the floor`() {
        val rig = Rig()
        rig.session.select(MeasureTool.CYLINDER)
        val drum = rig.world.drums.single()
        val top = drum.base + Vec3.UP * drum.height
        val left = top + Vec3(-drum.radius, 0f, 0f)
        val right = top + Vec3(drum.radius, 0f, 0f)
        // Where the side of the drum meets the floor, on the side facing the camera.
        val towardsCamera = (rig.camera.position - drum.base).rejectFrom(Vec3.UP).normalized()!!
        val foot = drum.base + towardsCamera * drum.radius + Vec3.UP * 0.002f

        rig.tapAt(inset(left, top))
        val afterRim = rig.tapAt(inset(right, top))
        assertEquals(Stage.AIM_LIFT, afterRim.stage)
        val state = rig.tapAt(foot)

        val r = SimWorld.DRUM_DIAMETER / 2f
        val volume = (PI * r * r * SimWorld.DRUM_HEIGHT).toFloat()
        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(SimWorld.DRUM_HEIGHT, state.reading("Height"), within = 0.006f)
        assertMeasures(volume, state.reading("Volume"), within = volume * 0.04f)
        assertEquals("Volume", state.solution.readings.first().name)
    }

    @Test
    fun `box - the crate, top face then down to the floor`() {
        val rig = Rig()
        rig.session.select(MeasureTool.BOX)
        val crate = rig.world.boxes.single()
        val top = crate.topCorners
        val topCentre = (top[0] + top[2]) * 0.5f
        // The bottom of whichever side face is turned towards the camera.
        val facing = crate.faces.drop(1).maxBy { it.normal dot (rig.camera.position - it.centre) }
        val foot = facing.centre.copy(y = 0.002f)

        rig.tapAt(inset(top[0], topCentre))
        rig.tapAt(inset(top[1], topCentre))
        val afterFace = rig.tapAt(inset(top[2], topCentre))
        assertEquals(Stage.AIM_LIFT, afterFace.stage)
        assertTrue("a flat rectangle can be kept as it is", afterFace.canFinish)
        val state = rig.tapAt(foot)

        val volume = SimWorld.CRATE_LENGTH * SimWorld.CRATE_WIDTH * SimWorld.CRATE_HEIGHT
        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(SimWorld.CRATE_LENGTH, state.reading("Length"), within = 0.006f)
        assertMeasures(SimWorld.CRATE_WIDTH, state.reading("Width"), within = 0.006f)
        assertMeasures(SimWorld.CRATE_HEIGHT, state.reading("Height"), within = 0.006f)
        assertMeasures(volume, state.reading("Volume"), within = volume * 0.04f)
        assertEquals(12, state.solution.wire.size)
        assertTrue(state.canSave)
    }

    @Test
    fun `box kept flat is a rectangle`() {
        val rig = Rig()
        rig.session.select(MeasureTool.BOX)
        val rug = SimWorld.RUG_CORNERS

        rig.tapAt(rug[0])
        rig.tapAt(rug[1])
        rig.tapAt(rug[2])
        rig.session.finish()
        val state = rig.frame()

        assertEquals(Stage.COMPLETE, state.stage)
        assertMeasures(SimWorld.RUG_LENGTH * SimWorld.RUG_WIDTH, state.reading("Area"), within = 0.001f)
    }

    // ── Session behaviour ────────────────────────────────────────────────────

    @Test
    fun `undo steps back one action at a time`() {
        val rig = Rig()
        val door = SimWorld.DOOR_CORNERS
        rig.session.select(MeasureTool.HEIGHT)
        rig.tapAt(door[0])
        rig.tapAt(door[3])

        rig.session.undo()
        assertEquals("the height goes first", Stage.AIM_LIFT, rig.frame().stage)

        rig.session.undo()
        val empty = rig.frame()
        assertEquals("then the base point", Stage.AIM_SURFACE, empty.stage)
        assertFalse(empty.canUndo)
    }

    @Test
    fun `a tap on a finished shape starts a new one`() {
        val rig = Rig()
        val door = SimWorld.DOOR_CORNERS
        rig.tapAt(door[0])
        rig.tapAt(door[1])

        val state = rig.tapAt(door[2])

        assertEquals(Stage.AIM_SURFACE, state.stage)
        assertFalse(state.canUndo)
        assertTrue(state.solution.readings.isEmpty())
    }

    @Test
    fun `a second tap without moving the phone does nothing`() {
        // Right after a point is placed the reticle is still on it. That is
        // not a zero-length measurement, and tapping again must not make one.
        val rig = Rig()
        rig.tapAt(SimWorld.DOOR_CORNERS[0])

        val state = rig.frame(tap = true)

        assertEquals(Stage.AIM_SURFACE, state.stage)
        assertTrue(state.solution.readings.isEmpty())
        assertEquals(1, state.dots.size)
    }

    @Test
    fun `changing tool discards the shape in progress`() {
        val rig = Rig()
        rig.tapAt(SimWorld.DOOR_CORNERS[0])

        rig.session.select(MeasureTool.AREA)
        val state = rig.frame()

        assertEquals(MeasureTool.AREA, state.tool)
        assertFalse(state.canUndo)
    }

    @Test
    fun `aiming out of the room locks onto nothing and places nothing`() {
        val rig = Rig()
        rig.camera = rig.camera.copy(pitchDegrees = 80f)   // up at the missing ceiling

        val state = rig.frame(tap = true)

        assertFalse(state.reticleLocked)
        assertFalse(state.canUndo)
    }

    @Test
    fun `the curved side of the drum is graded as an off-plane point`() {
        // Same span, measured once on the flat floor and once across the
        // drum's curved side: the second must carry the wider tolerance.
        val rig = Rig()
        val drum = rig.world.drums.single()
        val towardsCamera = (rig.camera.position - drum.base).rejectFrom(Vec3.UP).normalized()!!
        val across = (towardsCamera cross Vec3.UP) * 0.1f
        val onSide = drum.base + towardsCamera * drum.radius + Vec3.UP * 0.25f

        rig.tapAt(onSide - across)
        val curved = rig.tapAt(onSide + across).reading("Length")

        val flat = Rig().run {
            val a = drum.base + towardsCamera * 0.6f
            tapAt(a - across)
            tapAt(a + across).reading("Length")
        }

        assertTrue(curved.grade != AccuracyGrade.UNAVAILABLE)
        assertTrue(
            "curved ±${curved.tolerance} should exceed flat ±${flat.tolerance}",
            curved.tolerance!! / curved.value > flat.tolerance!! / flat.value,
        )
    }
}
