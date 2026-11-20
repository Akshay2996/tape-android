package com.tape.measure.domain.ar

import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.geometry.ScreenBox
import com.tape.measure.domain.geometry.Vec3

/** Whether the camera currently knows where it is in the room. */
enum class TrackingStatus { TRACKING, PAUSED, STOPPED }

/**
 * Everything the measuring logic needs from one camera frame.
 *
 * This is the seam between Tape's measuring logic and whatever supplies the
 * world: ARCore on a phone, or the practice room where ARCore can't run (the
 * Android emulator on Apple-silicon Macs, unsupported phones, unit tests).
 * Nothing behind this interface may leak ARCore types, which is what keeps the
 * logic above it testable on the JVM.
 */
interface ArFrame {
    val status: TrackingStatus
    val issue: TrackingIssue

    /** World → camera, 4×4 column-major, OpenGL convention. */
    val viewMatrix: FloatArray

    /** Camera → clip space, 4×4 column-major, OpenGL convention. */
    val projectionMatrix: FloatArray

    /** The nearest real surface under pixel ([x], [y]), or null when there is none. */
    fun hitTest(x: Float, y: Float): SurfaceHit?

    /** What the depth sensor sees this frame, or null when depth isn't available. */
    fun depthGrid(): DepthGrid?

    /** Flat surfaces found so far: floors, tables, walls. */
    fun planes(): List<SurfacePlane>

    /**
     * Starts working out what object is at pixel ([x], [y]): its name and its
     * outline on screen. Takes what it needs from this frame now and finishes
     * in the background, so call it once, on a tap.
     */
    fun identify(x: Float, y: Float): PendingIdentification
}

/** What an object is, as far as the camera can tell. */
class Identified(
    /** A short name such as "Chair", or null when it isn't recognised. */
    val name: String?,
    /** Its outline on screen in the frame that was tapped, or null when none was found. */
    val outline: ScreenBox?,
)

/** An identification still running. Poll [result]; it is null until done. */
fun interface PendingIdentification {
    fun result(): Identified?
}

/**
 * A coarse grid of what the camera sees, as points in world space.
 *
 * Neighbouring cells are neighbouring directions from the camera, so two cells
 * next to each other whose points are close together lie on the same surface.
 */
class DepthGrid(
    val width: Int,
    val height: Int,
    /** Row-major, [width] × [height]. Null where depth is unknown. */
    val points: Array<Vec3?>,
    /** Angle between neighbouring cells, in radians. */
    val cellAngle: Float,
    val cameraPosition: Vec3,
) {
    init {
        require(points.size == width * height) { "points must be width × height" }
    }
}

/** A detected flat surface. */
interface SurfacePlane {
    /** Any point on the surface. */
    val point: Vec3

    /** Unit normal. Points up for a floor or table top. */
    val normal: Vec3

    /** Detected extent, in square metres. Grows as more of it is seen. */
    val area: Float

    /** True when [p], dropped onto this surface, falls inside its detected outline. */
    fun contains(p: Vec3): Boolean
}

/** A point on a real surface, found by [ArFrame.hitTest]. */
interface SurfaceHit {
    val point: Vec3

    /** Unit normal of the surface at [point]. */
    val normal: Vec3

    /** Distance from the camera to [point], in metres. */
    val rangeMeters: Float

    val surface: AnchorSurface

    /**
     * Fixes a point to the world so it stays put as the camera moves: [at]
     * if given — a steadier estimate of this same spot — or the hit itself.
     */
    fun pin(at: Vec3 = point): Pin
}

/** A point fixed to the world. */
interface Pin {
    /** Where the point is now, or null while it can't be tracked. */
    val position: Vec3?

    fun release()
}
