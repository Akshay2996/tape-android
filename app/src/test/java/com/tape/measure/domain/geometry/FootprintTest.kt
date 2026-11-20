package com.tape.measure.domain.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FootprintTest {

    /** Points filling a [length] × [width] rectangle turned by [degrees], centred on (cx, cz). */
    private fun rectangle(length: Float, width: Float, degrees: Double, cx: Float = 1f, cz: Float = -2f): List<Flat> {
        val a = Math.toRadians(degrees)
        val ux = cos(a).toFloat(); val uz = sin(a).toFloat()
        return (0..20).flatMap { i ->
            (0..10).map { j ->
                val u = (i / 20f - 0.5f) * length
                val v = (j / 10f - 0.5f) * width
                Flat(cx + ux * u - uz * v, cz + uz * u + ux * v)
            }
        }
    }

    @Test
    fun `finds the size of a rectangle at any rotation`() {
        for (degrees in listOf(0.0, 17.0, 45.0, 90.0, 133.0)) {
            val f = Footprint.enclosing(rectangle(0.6f, 0.4f, degrees))!!

            assertEquals("length at $degrees°", 0.6f, f.length, 1e-4f)
            assertEquals("width at $degrees°", 0.4f, f.width, 1e-4f)
        }
    }

    @Test
    fun `length is always the longer side`() {
        val f = Footprint.enclosing(rectangle(0.3f, 0.9f, 30.0))!!

        assertEquals(0.9f, f.length, 1e-4f)
        assertEquals(0.3f, f.width, 1e-4f)
    }

    @Test
    fun `a circle is enclosed by a square of its diameter`() {
        val circle = (0 until 72).map {
            val a = 2.0 * PI * it / 72
            Flat(0.2f * cos(a).toFloat(), 0.2f * sin(a).toFloat())
        }

        val f = Footprint.enclosing(circle)!!

        assertEquals(0.4f, f.length, 0.002f)
        assertEquals(0.4f, f.width, 0.002f)
    }

    @Test
    fun `growing adds the margin to both sides of each edge`() {
        val f = Footprint.enclosing(rectangle(0.6f, 0.4f, 20.0))!!.grownBy(0.01f)

        assertEquals(0.62f, f.length, 1e-4f)
        assertEquals(0.42f, f.width, 1e-4f)
    }

    @Test
    fun `points on a line have no footprint`() {
        assertNull(Footprint.enclosing(listOf(Flat(0f, 0f), Flat(1f, 0f), Flat(2f, 0f))))
    }
}
