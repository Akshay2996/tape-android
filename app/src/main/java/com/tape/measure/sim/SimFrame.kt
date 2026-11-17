package com.tape.measure.sim

import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.accuracy.TrackingIssue
import com.tape.measure.domain.ar.ArFrame
import com.tape.measure.domain.ar.Pin
import com.tape.measure.domain.ar.SurfaceHit
import com.tape.measure.domain.ar.TrackingStatus
import com.tape.measure.domain.geometry.Projector
import com.tape.measure.domain.geometry.Vec3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the practice-room camera is and which way it faces.
 *
 * Yaw 0 looks along −Z; positive yaw turns right. Positive pitch looks up.
 */
data class SimCamera(
    val position: Vec3,
    val yawDegrees: Float,
    val pitchDegrees: Float,
) {
    private val yaw get() = Math.toRadians(yawDegrees.toDouble()).toFloat()
    private val pitch get() = Math.toRadians(pitchDegrees.toDouble()).toFloat()

    val forward: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))

    private val right: Vec3 get() = Vec3(cos(yaw), 0f, sin(yaw))

    fun look(deltaYawDegrees: Float, deltaPitchDegrees: Float) = copy(
        yawDegrees = yawDegrees + deltaYawDegrees,
        pitchDegrees = (pitchDegrees + deltaPitchDegrees).coerceIn(-MAX_PITCH, MAX_PITCH),
    )

    /** Walks along the floor ([forward], [right]) and rises or crouches ([up]), in metres. */
    fun move(forward: Float, right: Float, up: Float): SimCamera {
        val ahead = Vec3(sin(yaw), 0f, -cos(yaw))
        val next = position + ahead * forward + this.right * right + Vec3.UP * up
        return copy(position = next.copy(y = next.y.coerceIn(MIN_HEIGHT, MAX_HEIGHT)))
    }

    /** The same position, turned so the centre of the screen is on [target]. */
    fun lookingAt(target: Vec3): SimCamera {
        val direction = (target - position).normalized() ?: return this
        return copy(
            yawDegrees = Math.toDegrees(atan2(direction.x, -direction.z).toDouble()).toFloat(),
            pitchDegrees = Math.toDegrees(asin(direction.y.coerceIn(-1f, 1f)).toDouble()).toFloat(),
        )
    }

    /** World → camera, 4×4 column-major. */
    fun viewMatrix(): FloatArray {
        val f = forward
        val r = right
        val u = r cross f
        return FloatArray(16).also {
            it[0] = r.x; it[4] = r.y; it[8] = r.z; it[12] = -(r dot position)
            it[1] = u.x; it[5] = u.y; it[9] = u.z; it[13] = -(u dot position)
            it[2] = -f.x; it[6] = -f.y; it[10] = -f.z; it[14] = f dot position
            it[15] = 1f
        }
    }

    companion object {
        private const val MAX_PITCH = 85f
        private const val MIN_HEIGHT = 0.15f
        private const val MAX_HEIGHT = 2.4f

        /** Standing at the near end of the room, looking slightly down at it. */
        val START = SimCamera(Vec3(0f, 1.5f, 1.6f), yawDegrees = 0f, pitchDegrees = -21f)
    }
}

/** One frame of the practice room, seen through [camera]. */
class SimFrame(
    private val world: SimWorld,
    camera: SimCamera,
    private val width: Int,
    private val height: Int,
    override val issue: TrackingIssue = TrackingIssue.NONE,
) : ArFrame {

    override val status = TrackingStatus.TRACKING
    override val viewMatrix: FloatArray = camera.viewMatrix()
    override val projectionMatrix: FloatArray =
        Projector.perspective(FOV_Y_DEGREES, width.toFloat() / height)

    private val projector = Projector(viewMatrix, projectionMatrix, width, height)

    override fun hitTest(x: Float, y: Float): SurfaceHit? {
        val hit = world.raycast(projector.rayThrough(x, y)) ?: return null
        return SimSurfaceHit(hit.point, hit.normal, hit.distance, hit.surface)
    }

    private class SimSurfaceHit(
        override val point: Vec3,
        override val normal: Vec3,
        override val rangeMeters: Float,
        override val surface: AnchorSurface,
    ) : SurfaceHit {
        // Nothing drifts in a simulated room, so a pin is just the point itself.
        override fun pin(): Pin = object : Pin {
            override val position: Vec3 = point
            override fun release() = Unit
        }
    }

    companion object {
        const val FOV_Y_DEGREES = 60f
    }
}
