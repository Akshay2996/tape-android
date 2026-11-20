package com.tape.measure.sim

import com.tape.measure.domain.accuracy.AnchorSurface
import com.tape.measure.domain.ar.SurfacePlane
import com.tape.measure.domain.geometry.Ray
import com.tape.measure.domain.geometry.Segment3
import com.tape.measure.domain.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A flat rectangular face: [centre] ± [halfU] ± [halfV], facing along unit [normal]. */
class SimQuad(val centre: Vec3, val halfU: Vec3, val halfV: Vec3, val normal: Vec3) {
    val corners: List<Vec3> = listOf(
        centre - halfU - halfV,
        centre + halfU - halfV,
        centre + halfU + halfV,
        centre - halfU + halfV,
    )
}

/**
 * A box standing on the floor.
 *
 * @param base centre of the bottom face.
 * @param length size along the box's own x axis; [width] along its z axis.
 * @param yawDegrees rotation about the vertical, so the box isn't axis-aligned.
 */
class SimBox(
    val name: String,
    val base: Vec3,
    val length: Float,
    val width: Float,
    val height: Float,
    yawDegrees: Float,
) {
    private val yaw = Math.toRadians(yawDegrees.toDouble()).toFloat()
    private val ux = Vec3(cos(yaw), 0f, -sin(yaw))
    private val uz = Vec3(sin(yaw), 0f, cos(yaw))

    private val middle = base + Vec3.UP * (height / 2f)
    private val halfL = ux * (length / 2f)
    private val halfW = uz * (width / 2f)
    private val halfH = Vec3.UP * (height / 2f)

    /** Top face first, then the four sides. The bottom is never visible. */
    val faces: List<SimQuad> = listOf(
        SimQuad(middle + halfH, halfL, halfW, Vec3.UP),
        SimQuad(middle + halfL, halfW, halfH, ux),
        SimQuad(middle - halfL, halfW, halfH, -ux),
        SimQuad(middle + halfW, halfL, halfH, uz),
        SimQuad(middle - halfW, halfL, halfH, -uz),
    )

    /** Corners of the top face, in order around it. */
    val topCorners: List<Vec3> get() = faces[0].corners

    /** All eight corners: everything the box covers lies within them. */
    val outline: List<Vec3> get() = topCorners + topCorners.map { it - Vec3.UP * height }
}

/** An upright cylinder standing on the floor. [base] is the centre of its bottom. */
class SimDrum(val name: String, val base: Vec3, val radius: Float, val height: Float) {
    /** Points round its top and bottom rims, enough to outline it from any side. */
    val outline: List<Vec3>
        get() = (0 until 24).flatMap { i ->
            val a = (2.0 * PI * i / 24).toFloat()
            val rim = base + Vec3(cos(a), 0f, sin(a)) * radius
            listOf(rim, rim + Vec3.UP * height)
        }
}

/** A flat face of the room, the way ARCore would report it once detected. */
class SimPlane(
    override val point: Vec3,
    override val normal: Vec3,
    override val area: Float,
    private val inside: (Vec3) -> Boolean,
) : SurfacePlane {
    override fun contains(p: Vec3): Boolean = inside(p)

    companion object {
        fun of(quad: SimQuad) = SimPlane(
            point = quad.centre,
            normal = quad.normal,
            area = 4f * quad.halfU.length * quad.halfV.length,
            inside = { p ->
                val local = p - quad.centre
                abs((local dot quad.halfU) / (quad.halfU dot quad.halfU)) <= 1f &&
                    abs((local dot quad.halfV) / (quad.halfV dot quad.halfV)) <= 1f
            },
        )
    }
}

class SimHit(
    val distance: Float,
    val point: Vec3,
    val normal: Vec3,
    val surface: AnchorSurface,
    /** The box or drum hit, or null for the floor and walls. */
    val owner: Any? = null,
)

/**
 * A small furnished room with known dimensions, used where ARCore can't run.
 *
 * Because every object's true size is known, measuring this room is also a
 * ground-truth check on the whole measuring pipeline.
 */
class SimWorld(
    /** Floor and walls. */
    val ground: List<SimQuad>,
    val boxes: List<SimBox>,
    val drums: List<SimDrum>,
    /** Lines painted on the ground: things to measure that have no thickness. */
    val markings: List<Segment3>,
) {
    /**
     * Every flat face, as ARCore would detect them: floor and wall, the
     * crate's top and the drum's top. Sides stay undetected, as small
     * vertical faces usually do on a phone.
     */
    val planes: List<SurfacePlane> =
        ground.map(SimPlane::of) +
            boxes.map { SimPlane.of(it.faces[0]) } +
            drums.map { drum ->
                val top = drum.base + Vec3.UP * drum.height
                SimPlane(
                    point = top,
                    normal = Vec3.UP,
                    area = (Math.PI * drum.radius * drum.radius).toFloat(),
                    inside = { p ->
                        val dx = p.x - top.x
                        val dz = p.z - top.z
                        dx * dx + dz * dz <= drum.radius * drum.radius
                    },
                )
            }

    /** The nearest surface along [ray], or null when it leaves the room. */
    fun raycast(ray: Ray): SimHit? {
        var nearest: SimHit? = null
        fun offer(hit: SimHit?) {
            if (hit != null && hit.distance < (nearest?.distance ?: Float.MAX_VALUE)) nearest = hit
        }
        ground.forEach { offer(hitQuad(it, ray)) }
        boxes.forEach { box -> box.faces.forEach { face -> offer(hitQuad(face, ray)?.ownedBy(box)) } }
        drums.forEach { offer(hitDrum(it, ray)) }
        return nearest
    }

    private fun SimHit.ownedBy(owner: Any) = SimHit(distance, point, normal, surface, owner)

    private fun hitQuad(quad: SimQuad, ray: Ray): SimHit? {
        val incidence = ray.direction dot quad.normal
        if (incidence >= -1e-6f) return null   // edge-on, or seen from behind
        val distance = ((quad.centre - ray.origin) dot quad.normal) / incidence
        if (distance <= 0f) return null
        val point = ray.at(distance)
        val local = point - quad.centre
        val u = (local dot quad.halfU) / (quad.halfU dot quad.halfU)
        val v = (local dot quad.halfV) / (quad.halfV dot quad.halfV)
        if (abs(u) > 1f || abs(v) > 1f) return null
        return SimHit(distance, point, quad.normal, AnchorSurface.PLANE)
    }

    private fun hitDrum(drum: SimDrum, ray: Ray): SimHit? {
        val top = drum.base.y + drum.height
        var best: SimHit? = null

        // Flat top.
        if (ray.direction.y < -1e-6f) {
            val distance = (top - ray.origin.y) / ray.direction.y
            val point = ray.at(distance)
            val dx = point.x - drum.base.x
            val dz = point.z - drum.base.z
            if (distance > 0f && dx * dx + dz * dz <= drum.radius * drum.radius) {
                best = SimHit(distance, point, Vec3.UP, AnchorSurface.PLANE, drum)
            }
        }

        // Curved side. A curved surface is never a detected plane, so a point
        // here is graded the way an off-plane point would be on a phone.
        val ox = ray.origin.x - drum.base.x
        val oz = ray.origin.z - drum.base.z
        val a = ray.direction.x * ray.direction.x + ray.direction.z * ray.direction.z
        if (a > 1e-8f) {
            val b = 2f * (ox * ray.direction.x + oz * ray.direction.z)
            val c = ox * ox + oz * oz - drum.radius * drum.radius
            val discriminant = b * b - 4f * a * c
            if (discriminant >= 0f) {
                val distance = (-b - sqrt(discriminant)) / (2f * a)
                val point = ray.at(distance)
                if (distance > 0f && point.y in drum.base.y..top &&
                    distance < (best?.distance ?: Float.MAX_VALUE)
                ) {
                    val outward = Vec3(point.x - drum.base.x, 0f, point.z - drum.base.z)
                    best = SimHit(
                        distance, point, outward.normalized() ?: Vec3.UP, AnchorSurface.POINT, drum,
                    )
                }
            }
        }
        return best
    }

    companion object {
        // Known sizes, in metres. Tests measure the room and compare to these.
        const val CRATE_LENGTH = 0.60f
        const val CRATE_WIDTH = 0.40f
        const val CRATE_HEIGHT = 0.35f
        const val DRUM_DIAMETER = 0.40f
        const val DRUM_HEIGHT = 0.50f
        const val DOOR_WIDTH = 0.90f
        const val DOOR_HEIGHT = 2.00f
        const val RUG_LENGTH = 1.20f
        const val RUG_WIDTH = 0.80f

        private const val WALL_Z = -4f

        val DOOR_CORNERS: List<Vec3> = listOf(
            Vec3(1.45f, 0f, WALL_Z),
            Vec3(1.45f + DOOR_WIDTH, 0f, WALL_Z),
            Vec3(1.45f + DOOR_WIDTH, DOOR_HEIGHT, WALL_Z),
            Vec3(1.45f, DOOR_HEIGHT, WALL_Z),
        )

        val RUG_CORNERS: List<Vec3> = listOf(
            Vec3(-0.6f, 0f, 0f),
            Vec3(-0.6f + RUG_LENGTH, 0f, 0f),
            Vec3(-0.6f + RUG_LENGTH, 0f, -RUG_WIDTH),
            Vec3(-0.6f, 0f, -RUG_WIDTH),
        )

        /** A floor, a back wall with a door outline, a rug, a crate and a drum. */
        fun practiceRoom(): SimWorld = SimWorld(
            ground = listOf(
                SimQuad(Vec3(0f, 0f, -1f), Vec3(3f, 0f, 0f), Vec3(0f, 0f, 3f), Vec3.UP),
                SimQuad(Vec3(0f, 1.3f, WALL_Z), Vec3(3f, 0f, 0f), Vec3(0f, 1.3f, 0f), Vec3(0f, 0f, 1f)),
            ),
            boxes = listOf(
                SimBox("Crate", Vec3(0.5f, 0f, -2.3f), CRATE_LENGTH, CRATE_WIDTH, CRATE_HEIGHT, yawDegrees = 25f),
            ),
            drums = listOf(
                SimDrum("Drum", Vec3(-0.55f, 0f, -2.4f), DRUM_DIAMETER / 2f, DRUM_HEIGHT),
            ),
            markings = outline(DOOR_CORNERS) + outline(RUG_CORNERS),
        )

        private fun outline(corners: List<Vec3>): List<Segment3> =
            corners.indices.map { Segment3(corners[it], corners[(it + 1) % corners.size]) }
    }
}
