package com.tape.measure.domain.geometry

/**
 * Maps between world space and screen pixels for one camera frame.
 *
 * [view] and [projection] are 4×4 column-major matrices in the OpenGL
 * convention (the camera looks down −Z in view space), which is what both
 * ARCore and the practice room produce.
 *
 * Pure Kotlin on purpose: the same code projects the live overlay on a phone
 * and runs in JVM unit tests.
 */
class Projector(
    private val view: FloatArray,
    private val projection: FloatArray,
    private val width: Int,
    private val height: Int,
) {
    /** Where the camera is in world space. */
    val cameraPosition: Vec3 = run {
        val tx = view[12]; val ty = view[13]; val tz = view[14]
        Vec3(
            -(view[0] * tx + view[1] * ty + view[2] * tz),
            -(view[4] * tx + view[5] * ty + view[6] * tz),
            -(view[8] * tx + view[9] * ty + view[10] * tz),
        )
    }

    /** The world-space ray that passes through pixel ([x], [y]). */
    fun rayThrough(x: Float, y: Float): Ray {
        val ndcX = x / width * 2f - 1f
        val ndcY = 1f - y / height * 2f
        // Direction in view space at depth 1, allowing for an off-centre projection.
        val vx = (ndcX + projection[8]) / projection[0]
        val vy = (ndcY + projection[9]) / projection[5]
        val vz = -1f
        // Rotate into world space with the transpose of the view rotation.
        val dir = Vec3(
            view[0] * vx + view[1] * vy + view[2] * vz,
            view[4] * vx + view[5] * vy + view[6] * vz,
            view[8] * vx + view[9] * vy + view[10] * vz,
        )
        return Ray(cameraPosition, dir.normalized() ?: Vec3(0f, 0f, -1f))
    }

    /** Screen position of [p], or null when it is behind the camera. */
    fun project(p: Vec3): Point2? {
        val v = toView(p)
        if (v.z > -NEAR) return null
        return viewToScreen(v)
    }

    /** Screen segment for [a]–[b], cut at the camera plane; null when all of it is behind. */
    fun projectSegment(a: Vec3, b: Vec3): Segment2? {
        var va = toView(a)
        var vb = toView(b)
        val aBehind = va.z > -NEAR
        val bBehind = vb.z > -NEAR
        if (aBehind && bBehind) return null
        if (aBehind) va = cutAtNear(vb, va)
        if (bBehind) vb = cutAtNear(va, vb)
        return Segment2(viewToScreen(va), viewToScreen(vb))
    }

    /** Screen outline of a convex polygon, cut at the camera plane; empty when hidden. */
    fun projectPolygon(points: List<Vec3>): List<Point2> {
        if (points.size < 3) return emptyList()
        val input = points.map(::toView)
        val clipped = ArrayList<Vec3>(input.size + 2)
        for (i in input.indices) {
            val cur = input[i]
            val next = input[(i + 1) % input.size]
            val curIn = cur.z <= -NEAR
            val nextIn = next.z <= -NEAR
            if (curIn) clipped += cur
            if (curIn != nextIn) clipped += if (curIn) cutAtNear(cur, next) else cutAtNear(next, cur)
        }
        return if (clipped.size < 3) emptyList() else clipped.map(::viewToScreen)
    }

    private fun toView(p: Vec3) = Vec3(
        view[0] * p.x + view[4] * p.y + view[8] * p.z + view[12],
        view[1] * p.x + view[5] * p.y + view[9] * p.z + view[13],
        view[2] * p.x + view[6] * p.y + view[10] * p.z + view[14],
    )

    /** The point on the segment [inFront]→[behind] that sits exactly on the near plane. */
    private fun cutAtNear(inFront: Vec3, behind: Vec3): Vec3 {
        val t = (-NEAR - inFront.z) / (behind.z - inFront.z)
        return inFront + (behind - inFront) * t
    }

    private fun viewToScreen(v: Vec3): Point2 {
        val clipX = projection[0] * v.x + projection[8] * v.z
        val clipY = projection[5] * v.y + projection[9] * v.z
        val w = -v.z
        return Point2(
            x = (clipX / w + 1f) / 2f * width,
            y = (1f - clipY / w) / 2f * height,
        )
    }

    companion object {
        /** Nearest distance from the camera that is drawn, in metres. */
        const val NEAR = 0.05f
        const val FAR = 100f

        /** A symmetric perspective projection with vertical field of view [fovYDegrees]. */
        fun perspective(fovYDegrees: Float, aspect: Float): FloatArray {
            val f = 1f / kotlin.math.tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
            return FloatArray(16).also {
                it[0] = f / aspect
                it[5] = f
                it[10] = (FAR + NEAR) / (NEAR - FAR)
                it[11] = -1f
                it[14] = 2f * FAR * NEAR / (NEAR - FAR)
            }
        }
    }
}
