package com.tape.measure.domain.ar

import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.accuracy.TrackingIssue
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
}

/** A point on a real surface, found by [ArFrame.hitTest]. */
interface SurfaceHit {
    val point: Vec3

    /** Unit normal of the surface at [point]. */
    val normal: Vec3

    /** Distance from the camera to [point], in metres. */
    val rangeMeters: Float

    val surface: AnchorSurface

    /** Fixes this point to the world so it stays put as the camera moves. */
    fun pin(): Pin
}

/** A point fixed to the world. */
interface Pin {
    /** Where the point is now, or null while it can't be tracked. */
    val position: Vec3?

    fun release()
}
