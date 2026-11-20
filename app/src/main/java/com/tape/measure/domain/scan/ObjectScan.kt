package com.tape.measure.domain.scan

import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.DepthGrid
import com.tape.measure.domain.ar.SurfacePlane
import com.tape.measure.domain.geometry.Flat
import com.tape.measure.domain.geometry.Footprint
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.ScreenBox
import com.tape.measure.domain.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max

/**
 * The box that encloses an object, as far as the camera has seen it.
 *
 * @param corners the four corners of its base, on the surface it stands on.
 * @param height from that surface to the object's top.
 * @param rangeMeters typical distance the object was seen from.
 * @param resolution the smallest detail the depth data could resolve at that
 *        range, in metres — an error floor on every edge of the box.
 * @param complete false when the object has only been seen from one side, so
 *        its far side is a guess.
 */
class ObjectFit(
    val corners: List<Vec3>,
    val height: Float,
    val rangeMeters: Float,
    val resolution: Float,
    val complete: Boolean,
) {
    /**
     * True when [p] lies within this box grown on every side by a margin that
     * allows for real growth as more of the object comes into view.
     */
    internal fun encloses(p: Vec3): Boolean {
        val (c0, c1, _, c3) = corners
        val along = c1 - c0
        val across = c3 - c0
        val length = along.length
        val width = across.length
        val margin = max(MIN_GROWTH_METERS, GROWTH_FRACTION * maxOf(length, width, height))
        val local = p - c0
        val u = (local dot along) / length
        val v = (local dot across) / width
        val up = p.y - c0.y
        return u in -margin..(length + margin) && v in -margin..(width + margin) && up in -margin..(height + margin)
    }

    private companion object {
        const val MIN_GROWTH_METERS = 0.15f
        const val GROWTH_FRACTION = 0.25f
    }
}

/**
 * Where an object can be: inside its outline as seen from where it was
 * tapped. Everything that is part of the object projects inside its outline
 * from that viewpoint, whichever way the camera looks at it later, so this
 * holds for every frame of a scan. The wall behind and the desk around it
 * mostly fall outside.
 */
class Silhouette(private val tappedFrom: Projector, private val outline: ScreenBox) {
    fun contains(p: Vec3): Boolean = tappedFrom.project(p)?.let(outline::contains) ?: false
}

/**
 * Measures an object the user tapped, without them placing any corners.
 *
 * How
 * ───
 * 1. Every frame, the depth data is a grid of points in the room. Starting
 *    from the point the user tapped, Tape grows outwards to neighbouring
 *    points that are close in 3-D — the same surface, or one touching it. A
 *    jump in depth (the object's edge, with the wall behind) stops the growth.
 * 2. Large flat surfaces — the floor, a table top, walls — are cut out first,
 *    so growth can't run from the object onto whatever it stands on or
 *    leans against.
 * 3. What is left is the object. Its points are kept on a 3-D grid across
 *    frames, so walking around it fills in the sides the first view missed.
 * 4. The fitted box stands upright on the surface the object rests on: the
 *    smallest rectangle around its footprint, up to its highest point.
 *
 * Pure Kotlin: everything comes through [ArFrame], so the same code runs on a
 * phone and in the practice room.
 */
class ObjectScan private constructor(
    seed: Vec3,
    private val seedPlaneNormal: Vec3?,
    private val silhouette: Silhouette?,
) {
    sealed interface Start {
        class Started(val scan: ObjectScan) : Start
        class Refused(val reason: String) : Start
    }

    private class Voxel(var sumX: Float, var sumY: Float, var sumZ: Float, var count: Int)

    private val voxels = HashMap<Long, Voxel>()
    private val seedPoint = seed
    private val azimuths = ArrayList<Float>()
    private var highestCamera = -Float.MAX_VALUE
    private val ranges = ArrayList<Float>()
    private var cellAngle = 0f

    var fit: ObjectFit? = null
        private set

    /** Frames that saw the object. */
    var views: Int = 0
        private set

    /**
     * Adds what [frame] sees of the object.
     *
     * Skipped: frames without depth, frames taken while the phone moves too
     * fast to track cleanly, and frames that would suddenly stretch the box
     * well past the object — that is growth leaking onto whatever the object
     * touches, and one such frame would otherwise seed every later one.
     */
    fun add(frame: ArFrame) {
        if (frame.issue != TrackingIssue.NONE) return
        val grid = frame.depthGrid() ?: return
        val planes = frame.planes()
        val region = grow(grid, planes)
        if (region.isEmpty()) return
        fit?.let { current ->
            val outside = region.count { !current.encloses(grid.points[it]!!) }
            if (outside > region.size * MAX_OUTSIDE_FRACTION) return
        }

        views++
        cellAngle = grid.cellAngle
        for (i in region) {
            val p = grid.points[i]!!
            val v = voxels.getOrPut(key(p)) { Voxel(0f, 0f, 0f, 0) }
            v.sumX += p.x; v.sumY += p.y; v.sumZ += p.z; v.count++
            ranges += p.distanceTo(grid.cameraPosition)
        }
        // Where the camera was, seen from the object: how far round it the
        // user has walked.
        val centre = seedPoint
        azimuths += atan2(grid.cameraPosition.z - centre.z, grid.cameraPosition.x - centre.x)
        highestCamera = max(highestCamera, grid.cameraPosition.y)
        // Keep the range sample bounded; it only feeds a median.
        if (ranges.size > MAX_RANGE_SAMPLES) ranges.subList(0, ranges.size - MAX_RANGE_SAMPLES).clear()

        fit = fitBox(planes)
    }

    // ── Segmentation ─────────────────────────────────────────────────────────

    /** Indices of the grid cells that belong to the object this frame. */
    private fun grow(grid: DepthGrid, planes: List<SurfacePlane>): List<Int> {
        val excluded = planes.filter { isStructural(it) && !isSeedPlane(it) }
        fun usable(p: Vec3?): Boolean =
            p != null &&
                (silhouette?.contains(p) ?: true) &&
                excluded.none { abs((p - it.point) dot it.normal) < ON_PLANE_METERS && it.contains(p) }

        // Seeds: on the first frame, the cell nearest the tapped point; after
        // that, every cell that lands on something already scanned. The
        // second keeps the scan anchored as the viewpoint changes.
        val seeds = ArrayList<Int>()
        if (voxels.isEmpty()) {
            // The nearest cell that isn't floor or wall: a tap near the
            // object's edge may land closer to the floor behind it.
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for (i in grid.points.indices) {
                val p = grid.points[i] ?: continue
                val d = p.distanceTo(seedPoint)
                if (d < bestDistance && usable(p)) { bestDistance = d; best = i }
            }
            // Cells land further apart on a surface seen at a slant, so allow
            // a few cells' spacing, not one.
            val reach = max(SEED_REACH_METERS, STEP_CELLS * grid.cellAngle * seedPoint.distanceTo(grid.cameraPosition))
            if (best >= 0 && bestDistance <= reach) seeds += best
        } else {
            for (i in grid.points.indices) {
                val p = grid.points[i] ?: continue
                if (voxels.containsKey(key(p)) && usable(p)) seeds += i
            }
        }
        if (seeds.isEmpty()) return emptyList()

        val inRegion = BooleanArray(grid.points.size)
        val queue = ArrayDeque<Int>()
        for (s in seeds) { inRegion[s] = true; queue += s }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val p = grid.points[i]!!
            // Neighbouring cells on one surface sit about range × cellAngle
            // apart, and several times that on a surface seen nearly edge-on.
            // Allow for that; a depth jump at an edge is far larger.
            val limit = max(MIN_STEP_METERS, STEP_CELLS * grid.cellAngle * p.distanceTo(grid.cameraPosition))
            val x = i % grid.width
            val y = i / grid.width
            for ((dx, dy) in NEIGHBOURS) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= grid.width || ny >= grid.height) continue
                val j = ny * grid.width + nx
                if (inRegion[j]) continue
                val q = grid.points[j]
                if (!usable(q) || q!!.distanceTo(p) > limit || q.distanceTo(seedPoint) > MAX_OBJECT_METERS) continue
                inRegion[j] = true
                queue += j
            }
        }
        return inRegion.indices.filter { inRegion[it] }
    }

    /** A surface big enough to be part of the room rather than the object. */
    private fun isStructural(plane: SurfacePlane): Boolean =
        if (plane.normal.y > LEVEL_COSINE) plane.area >= LARGE_LEVEL_AREA else plane.area >= LARGE_OTHER_AREA

    /** The surface the user tapped is the object, however big it is. */
    private fun isSeedPlane(plane: SurfacePlane): Boolean {
        val n = seedPlaneNormal ?: return false
        return (plane.normal dot n) > SAME_PLANE_COSINE &&
            abs((seedPoint - plane.point) dot plane.normal) < ON_PLANE_METERS &&
            plane.contains(seedPoint)
    }

    // ── Fitting ──────────────────────────────────────────────────────────────

    private fun fitBox(planes: List<SurfacePlane>): ObjectFit? {
        val points = voxels.values.map { Vec3(it.sumX / it.count, it.sumY / it.count, it.sumZ / it.count) }
        val footprint = Footprint.enclosing(points.map { Flat(it.x, it.z) }) ?: return null

        val lowest = points.minOf { it.y }
        val highest = points.maxOf { it.y }
        val centre = Vec3(
            footprint.corners.sumOf { it.x.toDouble() }.toFloat() / 4f,
            lowest,
            footprint.corners.sumOf { it.z.toDouble() }.toFloat() / 4f,
        )
        // It stands on the highest level surface at (or just under) its
        // lowest point, below where it is. With none detected, its own lowest
        // point is the best there is.
        val base = planes
            .filter { it.normal.y > LEVEL_COSINE && it.point.y <= lowest + SUPPORT_GAP_METERS && it.contains(centre) }
            .maxOfOrNull { it.point.y }
            ?: lowest

        val range = ranges.sorted().let { it[it.size / 2] }
        val spacing = cellAngle * range
        // Depth samples sit up to one cell inside the true edge, so the box
        // fitted to them is short by about half a cell on each side.
        val grown = footprint.grownBy(spacing / 2f)
        val corners = grown.corners.map { Vec3(it.x, base, it.z) }
        val height = highest + spacing / 2f - base

        // Seen enough to trust the far side? Either the camera looked down on
        // its top, which shows how deep it is, or the user walked round it.
        val complete = highestCamera > highest + ABOVE_TOP_METERS || azimuthSpread() >= MIN_SPREAD_RADIANS

        return ObjectFit(
            corners = corners,
            height = height,
            rangeMeters = range,
            resolution = spacing + VOXEL_METERS / 2f,
            complete = complete,
        )
    }

    /** How far round the object the camera has been: 360° minus the biggest gap. */
    private fun azimuthSpread(): Float {
        if (azimuths.size < 2) return 0f
        val sorted = azimuths.sorted()
        var biggestGap = (sorted.first() + 2f * PI.toFloat()) - sorted.last()
        for (i in 1 until sorted.size) biggestGap = max(biggestGap, sorted[i] - sorted[i - 1])
        return 2f * PI.toFloat() - biggestGap
    }

    companion object {
        /** Side of the cubes object points are pooled into across frames. */
        const val VOXEL_METERS = 0.015f

        private const val ON_PLANE_METERS = 0.02f
        private const val SEED_REACH_METERS = 0.04f
        private const val MIN_STEP_METERS = 0.02f
        private const val STEP_CELLS = 6f
        private const val MAX_OBJECT_METERS = 2.5f
        private const val MAX_OUTSIDE_FRACTION = 0.15f
        private const val SUPPORT_GAP_METERS = 0.03f
        private const val LEVEL_COSINE = 0.9f
        private const val SAME_PLANE_COSINE = 0.98f
        private const val LARGE_LEVEL_AREA = 0.25f
        private const val LARGE_OTHER_AREA = 1.5f
        private const val ABOVE_TOP_METERS = 0.1f
        private val MIN_SPREAD_RADIANS = (100.0 * PI / 180.0).toFloat()
        private const val MAX_RANGE_SAMPLES = 20_000

        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        // Voxel keys pack the x, y and z cell indices into 21 bits each.
        private const val BITS = 21
        private const val OFFSET = 1 shl (BITS - 1)
        private const val FIELD = (1L shl BITS) - 1

        private fun key(p: Vec3): Long {
            fun cell(v: Float) = (floor(v / VOXEL_METERS).toInt() + OFFSET).toLong() and FIELD
            return (cell(p.x) shl (2 * BITS)) or (cell(p.y) shl BITS) or cell(p.z)
        }

        /**
         * Starts measuring the object at [seed], the point the user tapped.
         *
         * Refuses when the tap is on the floor itself, or when no level
         * surface has been found yet — without one, the object can't be told
         * apart from what it stands on.
         *
         * @param silhouette where the object can be, when it was identified.
         */
        fun start(seed: Vec3, frame: ArFrame, silhouette: Silhouette? = null): Start {
            if (frame.depthGrid() == null) {
                return Start.Refused("Depth isn't ready yet. Move the phone slowly for a moment, then tap again.")
            }
            val planes = frame.planes()
            val level = planes.filter { it.normal.y > LEVEL_COSINE }
            if (level.isEmpty()) {
                return Start.Refused("Sweep the floor or table around the object first, so Tape can find what it stands on.")
            }
            val seedPlane = planes.firstOrNull {
                abs((seed - it.point) dot it.normal) < ON_PLANE_METERS && it.contains(seed)
            }
            val floor = level.minByOrNull { it.point.y }
            if (seedPlane != null && seedPlane === floor) {
                return Start.Refused("That's the floor. Aim at the object itself, then tap.")
            }
            return Start.Started(ObjectScan(seed, seedPlane?.normal, silhouette))
        }
    }
}
