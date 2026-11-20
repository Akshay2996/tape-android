package com.tape.measure.ar

import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.DepthGrid
import com.tape.measure.domain.ar.Identified
import com.tape.measure.domain.ar.PendingIdentification
import com.tape.measure.domain.ar.Pin
import com.tape.measure.domain.ar.SurfaceHit
import com.tape.measure.domain.ar.SurfacePlane
import com.tape.measure.domain.ar.TrackingStatus
import com.tape.measure.domain.geometry.Point2
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import java.nio.ByteOrder
import kotlin.math.max

private const val TAG = "TapeAr"

/**
 * Turns on the ARCore features Tape measures with.
 *
 * Depth lets a hit test land on any real surface — the top of a box, the side
 * of a vase — instead of only on large flat planes. It is the difference
 * between measuring rooms and measuring objects. Devices without it still
 * work; they are simply limited to planes and sparse feature points.
 */
fun configureForMeasuring(session: Session, config: Config) {
    val depth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
    if (depth) config.depthMode = Config.DepthMode.AUTOMATIC
    // One line per session, so a phone's log shows whether depth was on.
    Log.i(TAG, "depth supported=$depth mode=${config.depthMode}")
}

/**
 * One ARCore frame, seen through [ArFrame]. This file is the only place the
 * measuring logic touches ARCore.
 *
 * Must be created and used on the thread that called `Session.update()`.
 */
class ArCoreFrame(private val session: Session, private val frame: Frame) : ArFrame {

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

    /**
     * Raw depth, not ARCore's smoothed depth image. The smoothed one fills in
     * the gaps at object edges with blended values, so an object and the
     * wall behind it look joined; raw depth leaves those pixels empty or
     * low-confidence, and the gap is what tells one thing from the next.
     */
    override fun depthGrid(): DepthGrid? {
        if (camera.trackingState != TrackingState.TRACKING) return null
        val depthImage = try {
            frame.acquireRawDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return null
        } catch (e: IllegalStateException) {
            return null   // depth is off on this session
        }
        depthImage.use { depth ->
            val confidenceImage = try {
                frame.acquireRawDepthConfidenceImage()
            } catch (e: NotYetAvailableException) {
                return null
            }
            confidenceImage.use { confidence ->
                val width = depth.width
                val height = depth.height
                val depthPlane = depth.planes[0]
                val depthBuffer = depthPlane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                val confidencePlane = confidence.planes[0]
                val confidenceBuffer = confidencePlane.buffer

                // The depth image lines up with the camera image, so the
                // camera's intrinsics, scaled to its size, unproject it.
                val intrinsics = camera.textureIntrinsics
                val focal = intrinsics.focalLength
                val principal = intrinsics.principalPoint
                val dims = intrinsics.imageDimensions
                val sx = width.toFloat() / dims[0]
                val sy = height.toFloat() / dims[1]
                val fx = focal[0] * sx
                val fy = focal[1] * sy
                val cx = principal[0] * sx
                val cy = principal[1] * sy

                val pose = camera.pose
                val points = arrayOfNulls<Vec3>(width * height)
                val local = FloatArray(3)
                for (v in 0 until height) {
                    for (u in 0 until width) {
                        val sure = confidenceBuffer.get(v * confidencePlane.rowStride + u * confidencePlane.pixelStride)
                            .toInt() and 0xFF
                        if (sure < MIN_CONFIDENCE) continue
                        val millimetres = depthBuffer.getShort(v * depthPlane.rowStride + u * depthPlane.pixelStride)
                            .toInt() and 0xFFFF
                        if (millimetres == 0 || millimetres > MAX_DEPTH_MILLIMETRES) continue
                        val d = millimetres / 1000f
                        // Camera space: +X right and +Y up in the image, looking down -Z.
                        local[0] = d * (u - cx) / fx
                        local[1] = d * (cy - v) / fy
                        local[2] = -d
                        val world = pose.transformPoint(local)
                        points[v * width + u] = Vec3(world[0], world[1], world[2])
                    }
                }
                return DepthGrid(
                    width = width,
                    height = height,
                    points = points,
                    cellAngle = 1f / fx,
                    cameraPosition = Vec3(pose.tx(), pose.ty(), pose.tz()),
                )
            }
        }
    }

    override fun identify(x: Float, y: Float): PendingIdentification {
        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return PendingIdentification { Identified(name = null, outline = null) }
        }
        image.use {
            // How camera-image pixels land on screen, captured while this
            // frame is still current.
            val corners = floatArrayOf(0f, 0f, it.width.toFloat(), 0f, 0f, it.height.toFloat())
            val onView = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW, onView)
            return MlKitIdentifier.identify(it, ImageToView.from(onView, it.width, it.height), Point2(x, y))
        }
    }

    override fun planes(): List<SurfacePlane> =
        session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .map(::ArCorePlane)

    private class ArCorePlane(private val plane: Plane) : SurfacePlane {
        override val point: Vec3 = plane.centerPose.let { Vec3(it.tx(), it.ty(), it.tz()) }
        override val normal: Vec3 =
            plane.centerPose.yAxis.let { Vec3(it[0], it[1], it[2]) }.normalized() ?: Vec3.UP
        override val area: Float = plane.extentX * plane.extentZ

        override fun contains(p: Vec3): Boolean {
            val onPlane = p - normal * ((p - point) dot normal)
            return plane.isPoseInPolygon(Pose.makeTranslation(onPlane.x, onPlane.y, onPlane.z))
        }
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
        /** Depth beyond this is too noisy to measure an object from. */
        const val MAX_DEPTH_MILLIMETRES = 6000

        /** Raw depth pixels ARCore is less sure of than this (of 255) are ignored. */
        const val MIN_CONFIDENCE = 150

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
