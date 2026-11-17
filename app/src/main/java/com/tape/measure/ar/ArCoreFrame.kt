package com.tape.measure.ar

import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.Pin
import com.tape.measure.domain.ar.SurfaceHit
import com.tape.measure.domain.ar.TrackingStatus
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import kotlin.math.max

/**
 * Turns on the ARCore features Tape measures with.
 *
 * Depth lets a hit test land on any real surface — the top of a box, the side
 * of a vase — instead of only on large flat planes. It is the difference
 * between measuring rooms and measuring objects. Devices without it still
 * work; they are simply limited to planes and sparse feature points.
 */
fun configureForMeasuring(session: Session, config: Config) {
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
        config.depthMode = Config.DepthMode.AUTOMATIC
    }
}

/**
 * One ARCore frame, seen through [ArFrame]. This file is the only place the
 * measuring logic touches ARCore.
 *
 * Must be created and used on the thread that called `Session.update()`.
 */
class ArCoreFrame(private val frame: Frame) : ArFrame {

    private val camera = frame.camera

    override val status: TrackingStatus = when (camera.trackingState) {
        TrackingState.TRACKING -> TrackingStatus.TRACKING
        TrackingState.PAUSED   -> TrackingStatus.PAUSED
        TrackingState.STOPPED  -> TrackingStatus.STOPPED
    }

    override val issue: TrackingIssue = when (camera.trackingFailureReason) {
        TrackingFailureReason.NONE                  -> TrackingIssue.NONE
        TrackingFailureReason.BAD_STATE             -> TrackingIssue.BAD_STATE
        TrackingFailureReason.INSUFFICIENT_LIGHT    -> TrackingIssue.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION      -> TrackingIssue.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingIssue.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE    -> TrackingIssue.CAMERA_UNAVAILABLE
    }

    override val viewMatrix: FloatArray =
        FloatArray(16).also { camera.getViewMatrix(it, 0) }

    override val projectionMatrix: FloatArray =
        FloatArray(16).also { camera.getProjectionMatrix(it, 0, Projector.NEAR, Projector.FAR) }

    /**
     * ARCore returns every trackable along the ray, nearest first: planes,
     * depth points and feature points mixed together.
     *
     * The nearest one is what the user is actually looking at — a box on the
     * floor is in front of the floor behind it. But where a detected plane is
     * practically at the same distance, the plane is the better anchor: it is
     * a surface fitted over many frames rather than a single depth sample.
     */
    override fun hitTest(x: Float, y: Float): SurfaceHit? {
        val usable = frame.hitTest(x, y).filter { it.surfaceOrNull() != null }
        val nearest = usable.firstOrNull() ?: return null
        val plane = usable.firstOrNull { it.surfaceOrNull() == AnchorSurface.PLANE }
        val planeIsAsNear = plane != null &&
            plane.distance - nearest.distance <= max(PLANE_PREFERENCE_METERS, PLANE_PREFERENCE_RELATIVE * nearest.distance)
        return ArCoreHit(if (planeIsAsNear) plane!! else nearest)
    }

    private class ArCoreHit(private val hit: HitResult) : SurfaceHit {
        override val point: Vec3 = hit.hitPose.let { Vec3(it.tx(), it.ty(), it.tz()) }

        // For planes, depth points and oriented feature points, the hit pose's
        // +Y axis is the surface normal. A feature point without an estimated
        // normal has no meaningful orientation, so fall back to "level".
        override val normal: Vec3 = run {
            val trackable = hit.trackable
            val unoriented = trackable is Point &&
                trackable.orientationMode != Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            if (unoriented) {
                Vec3.UP
            } else {
                hit.hitPose.yAxis.let { Vec3(it[0], it[1], it[2]) }.normalized() ?: Vec3.UP
            }
        }

        override val rangeMeters: Float = hit.distance

        override val surface: AnchorSurface = hit.surfaceOrNull() ?: AnchorSurface.POINT

        override fun pin(): Pin = ArCorePin(hit.createAnchor())
    }

    private class ArCorePin(private val anchor: Anchor) : Pin {
        override val position: Vec3?
            get() = if (anchor.trackingState == TrackingState.TRACKING) {
                anchor.pose.let { Vec3(it.tx(), it.ty(), it.tz()) }
            } else {
                null
            }

        override fun release() = anchor.detach()
    }

    private companion object {
        /** A plane this close behind the nearest hit is treated as the same surface. */
        const val PLANE_PREFERENCE_METERS = 0.03f
        const val PLANE_PREFERENCE_RELATIVE = 0.03f

        /**
         * What kind of surface a hit landed on, or null when it isn't one Tape
         * can measure from: a plane that has stopped tracking, a hit outside a
         * plane's detected outline, or some other trackable.
         */
        fun HitResult.surfaceOrNull(): AnchorSurface? = when (val t = trackable) {
            is Plane ->
                if (t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hitPose)) {
                    AnchorSurface.PLANE
                } else {
                    null
                }
            is DepthPoint, is Point -> AnchorSurface.POINT
            else -> null
        }
    }
}
