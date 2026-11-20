package com.tape.measure.domain.shape

import com.tape.measure.domain.accuracy.AnchorObservation
import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.PendingIdentification
import com.tape.measure.domain.ar.Pin
import com.tape.measure.domain.ar.SurfaceHit
import com.tape.measure.domain.ar.TrackingStatus
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import com.tape.measure.domain.model.AccuracyGrade
import com.tape.measure.domain.scan.ObjectFit
import com.tape.measure.domain.scan.ObjectScan
import com.tape.measure.domain.scan.Silhouette
import kotlin.math.abs

/** What the next tap will do. */
enum class Stage {
    /** Place a point where the reticle meets a surface. */
    AIM_SURFACE,

    /** Set a height by tilting the phone. */
    AIM_LIFT,

    /** Working out what the tapped object is and where its outline is. */
    IDENTIFYING,

    /** Fitting a box to the tapped object while the user moves around it. */
    SCANNING,

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
    /** What the measured object is, such as "Chair", when it was recognised. */
    val objectName: String? = null,
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

    var tool: MeasureTool = MeasureTool.OBJECT
        private set

    private class Placed(
        val pin: Pin,
        val observation: AnchorObservation,
        val normal: Vec3,
    )

    private val placed = ArrayList<Placed>()
    private var lift: ObservedLift? = null

    /** The object being measured, for [MeasureTool.OBJECT]. */
    private var scan: ObjectScan? = null

    /** Why the last tap couldn't start a scan, until the next tap. */
    private var refusal: String? = null

    /** The tap being identified: what is running, where it was, how it was seen. */
    private class Identifying(
        val pending: PendingIdentification,
        val seed: Vec3,
        val tappedFrom: Projector,
        var frames: Int = 0,
    )

    private var identifying: Identifying? = null
    private var objectName: String? = null

    /**
     * Where the reticle landed on recent frames, and what height it pointed
     * at. A single frame's reading wobbles with depth and tracking noise; a
     * tap uses the median of the recent frames that agree with it.
     */
    private val recentAims = ArrayDeque<Vec3>()
    private val recentLifts = ArrayDeque<Float>()

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
            identifying != null -> identifying = null
            scan != null        -> { scan = null; objectName = null }
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
                Stage.AIM_SURFACE ->
                    if (tool == MeasureTool.OBJECT) {
                        frame.hitTest(centerX, centerY)?.let {
                            identifying = Identifying(frame.identify(centerX, centerY), it.point, projector)
                            refusal = null
                        }
                    } else {
                        frame.hitTest(centerX, centerY)?.takeIf(::isNewPoint)?.let { place(it, steadyAim(it.point)) }
                    }
                Stage.AIM_LIFT    -> aimLift(projector, centerX, centerY)?.let { lift = it.copy(height = steadyLift(it.height)) }
                Stage.IDENTIFYING -> Unit   // already on it
                Stage.SCANNING    -> finish()
                Stage.COMPLETE    -> clear()   // a tap on a finished shape starts over
            }
        }
        if (tool == MeasureTool.OBJECT) return updateObject(frame, tracking, conditions, centerX, centerY)

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
        remember(recentAims, cursor?.point)
        remember(recentLifts, liftPreview?.height)

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
        recentAims.clear()
        recentLifts.clear()
        lift = null
        scan = null
        refusal = null
        identifying = null
        objectName = null
        finished = false
    }

    // ── Object tool ──────────────────────────────────────────────────────────

    /**
     * Starts the scan once identification is done — or once it has taken too
     * long, in which case the object is measured without a name or outline
     * rather than leaving the user waiting.
     */
    private fun continueIdentifying(frame: ArFrame) {
        val pending = identifying ?: return
        pending.frames++
        val identified = pending.pending.result()
        if (identified == null && pending.frames < IDENTIFY_TIMEOUT_FRAMES) return
        identifying = null

        val silhouette = identified?.outline?.let { Silhouette(pending.tappedFrom, it.grownBy(OUTLINE_MARGIN)) }
        when (val start = ObjectScan.start(pending.seed, frame, silhouette)) {
            is ObjectScan.Start.Started -> {
                scan = start.scan
                objectName = identified?.name
                refusal = null
            }
            is ObjectScan.Start.Refused -> refusal = start.reason
        }
    }

    private fun updateObject(
        frame: ArFrame,
        tracking: Boolean,
        conditions: Conditions,
        centerX: Float,
        centerY: Float,
    ): SessionState {
        if (identifying != null && tracking) continueIdentifying(frame)
        val scan = scan
        // Keep learning the object while scanning; a finished one stays put.
        if (scan != null && !finished && tracking) scan.add(frame)

        val stage = stage()
        val fit = scan?.fit
        val solution = when {
            fit == null -> Solution()
            !tracking -> Solution(accuracy = ShapeSolver.refusal(conditions))
            else -> objectSolution(fit, conditions)
        }
        val reticleLocked = stage == Stage.AIM_SURFACE && tracking && frame.hitTest(centerX, centerY) != null
        val verdict = solution.accuracy
        return SessionState(
            tool = tool,
            stage = stage,
            dots = emptyList(),
            solution = solution,
            reticleLocked = reticleLocked,
            canUndo = scan != null || identifying != null,
            canFinish = canFinish(),
            canSave = stage == Stage.COMPLETE && verdict?.grade?.hasTolerance == true,
            objectName = objectName,
            hint = when {
                !tracking -> guidance(stage, tracking = false, reticleLocked = false)
                stage == Stage.IDENTIFYING -> "Identifying…"
                stage == Stage.AIM_SURFACE -> refusal ?: guidance(stage, tracking = true, reticleLocked)
                stage == Stage.COMPLETE -> verdict?.reason ?: guidance(stage, tracking = true, reticleLocked)
                fit == null -> named("Hold steady on it. Get closer if no box appears.")
                !fit.complete -> named("Look down on it, or walk part way round. Then tap Done.")
                else -> named("Got it. Tap Done.")
            },
        )
    }

    /** [text], led by what the object is when that is known: "Chair · Got it." */
    private fun named(text: String): String = objectName?.let { "$it · $text" } ?: text

    /**
     * The fitted box, solved like a box placed by hand, so its readings and
     * tolerances come from the same code. Its corners were never tapped: they
     * are inferred from depth, so they are graded as off-plane points, and
     * the depth resolution is added to every edge.
     */
    private fun objectSolution(fit: ObjectFit, conditions: Conditions): Solution {
        val observation = AnchorObservation(fit.rangeMeters, AnchorSurface.POINT, isTracking = true)
        val (c0, c1, _, c3) = fit.corners
        val solution = ShapeSolver.solve(
            tool = MeasureTool.OBJECT,
            points = listOf(c0, c1, c3).map { ObservedPoint(it, observation) },
            normal = Vec3.UP,
            lift = ObservedLift(fit.height, observation),
            conditions = conditions,
            resolution = fit.resolution,
        )
        val accuracy = solution.accuracy ?: return solution
        return solution.copy(
            accuracy = if (fit.complete) {
                accuracy.copy(reason = "Box fitted to what the camera saw. Get closer for sharper edges.")
            } else {
                // A box seen from one side has a depth that is only a guess,
                // however tight each edge's bound looks.
                ShapeAccuracy(
                    grade = if (accuracy.grade.ordinal >= AccuracyGrade.LOW.ordinal) accuracy.grade else AccuracyGrade.LOW,
                    reason = "Seen from one side only, so its far side is a guess.",
                )
            },
        )
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

    private fun place(hit: SurfaceHit, at: Vec3 = hit.point) {
        placed += Placed(hit.pin(at), hit.observation(), hit.normal)
        recentAims.clear()
    }

    // ── Steadying ────────────────────────────────────────────────────────────

    /** Keeps the last few readings; a frame with none breaks the run. */
    private fun <T> remember(recent: ArrayDeque<T>, reading: T?) {
        if (reading == null) {
            recent.clear()
            return
        }
        recent.addLast(reading)
        while (recent.size > STEADY_FRAMES) recent.removeFirst()
    }

    /**
     * The median of the recent aims that agree with [current], or [current]
     * itself while the phone is moving and fewer than three agree.
     */
    private fun steadyAim(current: Vec3): Vec3 {
        val agreeing = recentAims.filter { it.distanceTo(current) <= STEADY_RADIUS_METERS } + current
        if (agreeing.size < MIN_STEADY_SAMPLES) return current
        return Vec3(median(agreeing.map { it.x }), median(agreeing.map { it.y }), median(agreeing.map { it.z }))
    }

    /** As [steadyAim], for the height the phone is tilted to. */
    private fun steadyLift(current: Float): Float {
        val agreeing = recentLifts.filter { abs(it - current) <= STEADY_RADIUS_METERS } + current
        return if (agreeing.size < MIN_STEADY_SAMPLES) current else median(agreeing)
    }

    private fun median(values: List<Float>): Float = values.sorted().let { it[it.size / 2] }

    private fun stage(): Stage {
        val needed = tool.surfacePoints
        return when {
            finished                     -> Stage.COMPLETE
            tool == MeasureTool.OBJECT   -> when {
                identifying != null -> Stage.IDENTIFYING
                scan == null        -> Stage.AIM_SURFACE
                else                -> Stage.SCANNING
            }
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
        MeasureTool.OBJECT -> scan?.fit != null
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
            Stage.SCANNING -> "Walk slowly round it, then tap Done."
            Stage.IDENTIFYING -> "Identifying…"
            Stage.AIM_LIFT -> when (tool) {
                MeasureTool.HEIGHT -> "Tilt to the top and tap."
                else               -> "Tilt to the other face and tap. Done keeps it flat."
            }
            Stage.AIM_SURFACE -> when (tool) {
                MeasureTool.OBJECT -> "Aim at the object and tap."
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
        /** Readings remembered for steadying a tap: about a quarter of a second. */
        const val STEADY_FRAMES = 8

        /** Readings further than this from the tapped one are from a moving phone. */
        const val STEADY_RADIUS_METERS = 0.015f

        /** Fewer agreeing readings than this, and the tap's own reading is used. */
        const val MIN_STEADY_SAMPLES = 3

        /** About a second and a half: after this, measure without a name. */
        const val IDENTIFY_TIMEOUT_FRAMES = 45

        /** Outlines from the detector are tight; allow a little either side. */
        const val OUTLINE_MARGIN = 0.08f

        /** Closer than this to the last point, the reticle hasn't moved on yet. */
        const val MIN_SEPARATION_METERS = 0.005f

        /** cos 15°: plane normals within this of vertical count as level. */
        const val LEVEL_SNAP_PLANE = 0.966f

        /** cos 35°: off-plane normals are noisier, so they snap from further out. */
        const val LEVEL_SNAP_POINT = 0.819f
    }
}
