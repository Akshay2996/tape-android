package com.tape.measure.domain.shape

import com.tape.measure.domain.accuracy.AnchorObservation
import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.Pin
import com.tape.measure.domain.ar.SurfaceHit
import com.tape.measure.domain.ar.TrackingStatus
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import kotlin.math.abs

/** What the next tap will do. */
enum class Stage {
    /** Place a point where the reticle meets a surface. */
    AIM_SURFACE,

    /** Set a height by tilting the phone. */
    AIM_LIFT,

    /** The shape is finished; a tap starts a new one. */
    COMPLETE,
}

/** One frame's view of the measurement in progress, in world space. */
data class SessionState(
    val tool: MeasureTool,
    val stage: Stage,
    /** The points placed so far. The reticle itself marks the one being aimed. */
    val dots: List<Vec3>,
    val solution: Solution,
    /** True when the reticle is on something a tap could place. */
    val reticleLocked: Boolean,
    val canUndo: Boolean,
    val canFinish: Boolean,
    val canSave: Boolean,
    val hint: String,
)

/**
 * The measurement in progress: which tool, which points are placed, and what
 * the next tap means.
 *
 * Aim-then-tap
 * ────────────
 * Points go where the centre reticle is aiming, not under the finger — a
 * fingertip hides the corner it is trying to hit. Because the reticle is always
 * somewhere, the shape is solved every frame with the reticle standing in for
 * the point not yet placed, so the user sees the measurement before committing.
 *
 * Not thread-safe: drive it from one thread (the frame callback).
 */
class MeasureSession {

    var tool: MeasureTool = MeasureTool.LINE
        private set

    private class Placed(
        val pin: Pin,
        val observation: AnchorObservation,
        val normal: Vec3,
    )

    private val placed = ArrayList<Placed>()
    private var lift: ObservedLift? = null

    /** Set when the user ends a tool early or closes an open-ended one. */
    private var finished = false

    fun select(tool: MeasureTool) {
        clear()
        this.tool = tool
    }

    fun reset() = clear()

    /** Steps back one action: un-finish, then drop the height, then the last point. */
    fun undo() {
        when {
            finished            -> finished = false
            lift != null        -> lift = null
            placed.isNotEmpty() -> placed.removeAt(placed.lastIndex).pin.release()
        }
    }

    /** Ends an open-ended tool, or keeps a box or cylinder flat. */
    fun finish() {
        if (canFinish()) finished = true
    }

    /**
     * Advances the session by one camera frame.
     *
     * @param confirm true when the user tapped since the last frame.
     */
    fun update(frame: ArFrame, projector: Projector, centerX: Float, centerY: Float, confirm: Boolean): SessionState {
        val tracking = frame.status == TrackingStatus.TRACKING
        val conditions = Conditions(cameraIsTracking = tracking, issue = frame.issue)

        if (confirm && tracking) {
            when (stage()) {
                Stage.AIM_SURFACE -> frame.hitTest(centerX, centerY)?.takeIf(::isNewPoint)?.let(::place)
                Stage.AIM_LIFT    -> aimLift(projector, centerX, centerY)?.let { lift = it }
                Stage.COMPLETE    -> clear()   // a tap on a finished shape starts over
            }
        }

        val stage = stage()
        val positions = placed.map { it.pin.position }
        val allTracked = positions.none { it == null }

        // What the reticle is on, and — once it has moved off the last point —
        // where it would place the next one.
        val underReticle: SurfaceHit? =
            if (tracking && stage == Stage.AIM_SURFACE) frame.hitTest(centerX, centerY) else null
        val cursor: SurfaceHit? = underReticle?.takeIf(::isNewPoint)
        val liftPreview: ObservedLift? =
            if (tracking && allTracked && stage == Stage.AIM_LIFT) {
                aimLift(projector, centerX, centerY)
            } else {
                null
            }

        val solution = when {
            placed.isEmpty() -> Solution()
            !tracking || !allTracked ->
                Solution(accuracy = ShapeSolver.refusal(conditions))
            else -> ShapeSolver.solve(
                tool = tool,
                points = placed.indices.map { ObservedPoint(positions[it]!!, placed[it].observation) } +
                    listOfNotNull(cursor?.let { ObservedPoint(it.point, it.observation()) }),
                normal = baseNormal(),
                lift = lift ?: liftPreview,
                conditions = conditions,
            )
        }

        val complete = stage == Stage.COMPLETE
        val reticleLocked = underReticle != null || liftPreview != null
        val verdict = solution.accuracy
        return SessionState(
            tool = tool,
            stage = stage,
            dots = positions.filterNotNull(),
            solution = solution,
            reticleLocked = reticleLocked,
            canUndo = placed.isNotEmpty(),
            canFinish = canFinish(),
            canSave = complete &&
                solution.readings.isNotEmpty() &&
                verdict?.grade?.hasTolerance == true,
            // Once there is a verdict worth reading — a finished shape, or a
            // refusal at any point — it replaces the placing instructions: it
            // tells the user what to change, which is strictly more useful.
            hint = if (verdict != null && (complete || !verdict.grade.hasTolerance)) {
                verdict.reason
            } else {
                guidance(stage, tracking, reticleLocked)
            },
        )
    }

    /** Releases every pin. Call when the screen goes away. */
    fun release() = clear()

    // ── Internals ────────────────────────────────────────────────────────────

    private fun clear() {
        placed.forEach { it.pin.release() }
        placed.clear()
        lift = null
        finished = false
    }

    /**
     * False while the reticle is still on the point just placed. Until the
     * phone moves there is no span to measure, and a second tap there would
     * only add a point on top of the first.
     */
    private fun isNewPoint(hit: SurfaceHit): Boolean {
        val last = placed.lastOrNull()?.pin?.position ?: return true
        return hit.point.distanceTo(last) > MIN_SEPARATION_METERS
    }

    private fun place(hit: SurfaceHit) {
        placed += Placed(hit.pin(), hit.observation(), hit.normal)
    }

    private fun stage(): Stage {
        val needed = tool.surfacePoints
        return when {
            finished                     -> Stage.COMPLETE
            needed == null               -> Stage.AIM_SURFACE
            placed.size < needed         -> Stage.AIM_SURFACE
            tool.hasLift && lift == null -> Stage.AIM_LIFT
            else                         -> Stage.COMPLETE
        }
    }

    private fun canFinish(): Boolean = !finished && when (tool) {
        MeasureTool.PATH -> placed.size >= 2
        MeasureTool.AREA -> placed.size >= 3
        MeasureTool.BOX, MeasureTool.CYLINDER -> stage() == Stage.AIM_LIFT
        else -> false
    }

    private fun aimLift(projector: Projector, centerX: Float, centerY: Float): ObservedLift? {
        val positions = placed.map { it.pin.position ?: return null }
        val ray = projector.rayThrough(centerX, centerY)
        val hit = Lift.solve(tool, positions, baseNormal(), ray) ?: return null
        return ObservedLift(
            height = hit.height,
            // Nothing was tapped here: the top is inferred from where the phone
            // is pointing, so it is graded like an off-plane point, at the
            // range the phone was from it.
            observation = AnchorObservation(
                placementRangeMeters = hit.point.distanceTo(ray.origin),
                surface = AnchorSurface.POINT,
                isTracking = true,
            ),
        )
    }

    /**
     * The direction "up" means for this shape.
     *
     * Height is always measured against gravity. Other shapes stand on the
     * surface their first point was placed on; a surface that is nearly level
     * is treated as exactly level, so a little normal noise doesn't tilt a box.
     */
    private fun baseNormal(): Vec3 {
        if (tool == MeasureTool.HEIGHT) return Vec3.UP
        val first = placed.firstOrNull() ?: return Vec3.UP
        val snap = if (first.observation.surface == AnchorSurface.PLANE) LEVEL_SNAP_PLANE else LEVEL_SNAP_POINT
        return if (abs(first.normal dot Vec3.UP) >= snap) Vec3.UP else first.normal
    }

    private fun SurfaceHit.observation() = AnchorObservation(
        placementRangeMeters = rangeMeters,
        surface = surface,
        isTracking = true,
    )

    private fun guidance(stage: Stage, tracking: Boolean, reticleLocked: Boolean): String {
        if (!tracking) return "Move the phone slowly so Tape can find its place."
        if (stage == Stage.AIM_SURFACE && !reticleLocked) {
            return "Aim at a surface. Move the phone slowly if nothing locks on."
        }
        val n = placed.size
        return when (stage) {
            Stage.COMPLETE -> "Tap to start a new measurement."
            Stage.AIM_LIFT -> when (tool) {
                MeasureTool.HEIGHT -> "Tilt to the top and tap."
                else               -> "Tilt to the other face and tap. Done keeps it flat."
            }
            Stage.AIM_SURFACE -> when (tool) {
                MeasureTool.LINE ->
                    if (n == 0) "Aim at the first point and tap." else "Aim at the second point and tap."
                MeasureTool.PATH ->
                    if (n == 0) "Aim at the start and tap." else "Tap to add points. Done when finished."
                MeasureTool.AREA ->
                    if (n == 0) "Aim at a corner and tap." else "Tap each corner in order. Done closes the shape."
                MeasureTool.ANGLE -> when (n) {
                    0    -> "Tap a point on one arm."
                    1    -> "Tap the corner."
                    else -> "Tap a point on the other arm."
                }
                MeasureTool.HEIGHT -> "Aim at the base and tap."
                MeasureTool.CIRCLE, MeasureTool.CYLINDER ->
                    if (n == 0) "Tap one side of the circle." else "Tap the opposite side."
                MeasureTool.BOX -> when (n) {
                    0    -> "Tap a corner of one face."
                    1    -> "Tap the next corner along an edge."
                    else -> "Tap anywhere on the opposite edge."
                }
            }
        }
    }

    private companion object {
        /** Closer than this to the last point, the reticle hasn't moved on yet. */
        const val MIN_SEPARATION_METERS = 0.005f

        /** cos 15°: plane normals within this of vertical count as level. */
        const val LEVEL_SNAP_PLANE = 0.966f

        /** cos 35°: off-plane normals are noisier, so they snap from further out. */
        const val LEVEL_SNAP_POINT = 0.819f
    }
}
