package com.tape.measure.domain.accuracy

import com.tape.measure.domain.model.AccuracyGrade
import com.tape.measure.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the honest-accuracy engine.
 *
 * These matter more than usual: the engine's whole purpose is to stop Tape from
 * printing numbers it can't defend. A regression here reintroduces exactly the
 * dishonesty the feature was built to remove, and it would be invisible on device
 * (a wrong tolerance still *looks* plausible).
 *
 * Runs on the JVM with no ARCore dependency — that is why the engine mirrors
 * ARCore's enums instead of importing them.
 */
class AccuracyEngineTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun anchor(
        range: Float = 0.5f,
        surface: AnchorSurface = AnchorSurface.PLANE,
        tracking: Boolean = true,
    ) = AnchorObservation(
        placementRangeMeters = range,
        surface              = surface,
        isTracking           = tracking,
    )

    private fun input(
        distance: Float = 1.0f,
        cameraTracking: Boolean = true,
        issue: TrackingIssue = TrackingIssue.NONE,
        a: AnchorObservation = anchor(),
        b: AnchorObservation = anchor(),
    ) = AccuracyInput(
        distanceMeters   = distance,
        cameraIsTracking = cameraTracking,
        issue            = issue,
        anchorA          = a,
        anchorB          = b,
    )

    // ── Refusal cases: the engine must decline, not guess ────────────────────

    @Test
    fun `refuses to estimate when camera is not tracking`() {
        val result = AccuracyEngine.estimate(input(cameraTracking = false))

        assertEquals(AccuracyGrade.UNAVAILABLE, result.grade)
        assertTrue(
            "refusal must explain itself, got: '${result.reason}'",
            result.reason.isNotBlank(),
        )
    }

    @Test
    fun `refuses to estimate when an anchor has lost tracking`() {
        val result = AccuracyEngine.estimate(
            input(a = anchor(tracking = false)),
        )

        assertEquals(AccuracyGrade.UNAVAILABLE, result.grade)
    }

    @Test
    fun `refuses to estimate a non-positive distance`() {
        assertEquals(
            AccuracyGrade.UNAVAILABLE,
            AccuracyEngine.estimate(input(distance = 0f)).grade,
        )
    }

    @Test
    fun `unavailable estimate formats to null rather than a fake number`() {
        val result = AccuracyEngine.estimate(input(cameraTracking = false))

        assertNull(
            "an unbounded estimate must not render a tolerance string",
            result.formatTolerance(UnitSystem.CM),
        )
        assertNull(result.formatRelative())
    }

    // ── The old hardcoded lie must not reappear ──────────────────────────────

    @Test
    fun `tolerance grows with distance instead of staying fixed`() {
        // The pre-engine bug: every HIGH-tracking reading claimed a flat "±1 cm",
        // whether the span was 20 cm or 6 m. Tolerance must scale with the span.
        val near = AccuracyEngine.estimate(input(distance = 0.5f))
        val far  = AccuracyEngine.estimate(input(distance = 6.0f))

        assertTrue(
            "6 m span (${far.toleranceMeters} m) must not claim the same or better " +
                "tolerance than a 0.5 m span (${near.toleranceMeters} m)",
            far.toleranceMeters > near.toleranceMeters,
        )
    }

    @Test
    fun `tolerance is monotonic in distance`() {
        val distances = listOf(0.3f, 0.5f, 1f, 2f, 4f, 8f)
        val tolerances = distances.map {
            AccuracyEngine.estimate(input(distance = it)).toleranceMeters
        }

        tolerances.zipWithNext().forEachIndexed { i, (lo, hi) ->
            assertTrue(
                "tolerance must never shrink as span grows: " +
                    "${distances[i]}m→${lo}m but ${distances[i + 1]}m→${hi}m",
                hi >= lo,
            )
        }
    }

    // ── Floor ────────────────────────────────────────────────────────────────

    @Test
    fun `never claims better than the 5mm floor`() {
        // Ideal conditions on a tiny span: base 1% of 10 cm = 1 mm, which is a
        // claim no consumer phone earns. The floor must clamp it.
        val result = AccuracyEngine.estimate(input(distance = 0.10f))

        assertTrue(
            "claimed ±${result.toleranceMeters * 1000} mm, floor is " +
                "${AccuracyEngine.FLOOR_METERS * 1000} mm",
            result.toleranceMeters >= AccuracyEngine.FLOOR_METERS,
        )
    }

    @Test
    fun `displayed percentage agrees with displayed tolerance after flooring`() {
        // When the floor kicks in, relative must be re-derived from the floored
        // tolerance — otherwise the badge shows "±5 mm (1%)" on a span where
        // 5 mm is actually 5%, and the two halves of the UI contradict.
        val result = AccuracyEngine.estimate(input(distance = 0.10f))

        val impliedTolerance = result.relative * 0.10f
        assertEquals(
            "relative and tolerance must describe the same claim",
            result.toleranceMeters,
            impliedTolerance,
            1e-6f,
        )
    }

    // ── Grade boundaries ─────────────────────────────────────────────────────

    @Test
    fun `ideal conditions on a normal span grade HIGH`() {
        val result = AccuracyEngine.estimate(
            input(distance = 1.4f, a = anchor(range = 0.8f), b = anchor(range = 0.8f)),
        )

        assertEquals(AccuracyGrade.HIGH, result.grade)
        assertTrue(result.relative <= AccuracyEngine.HIGH_THRESHOLD)
    }

    @Test
    fun `long-range placement degrades the grade`() {
        val close = AccuracyEngine.estimate(
            input(distance = 3f, a = anchor(range = 0.5f), b = anchor(range = 0.5f)),
        )
        val faraway = AccuracyEngine.estimate(
            input(distance = 3f, a = anchor(range = 5f), b = anchor(range = 5f)),
        )

        assertEquals(AccuracyGrade.HIGH, close.grade)
        assertTrue(
            "distant placement must widen tolerance",
            faraway.toleranceMeters > close.toleranceMeters,
        )
        assertTrue(faraway.grade != AccuracyGrade.HIGH)
    }

    @Test
    fun `severe tracking failure grades LOW`() {
        val result = AccuracyEngine.estimate(input(issue = TrackingIssue.BAD_STATE))

        assertEquals(AccuracyGrade.LOW, result.grade)
    }

    @Test
    fun `grade thresholds are consistent with the relative error`() {
        val cases = listOf(
            input(distance = 1.5f),
            input(distance = 4f, issue = TrackingIssue.INSUFFICIENT_LIGHT),
            input(distance = 6f, a = anchor(range = 6f), b = anchor(range = 6f)),
            input(distance = 2f, issue = TrackingIssue.EXCESSIVE_MOTION),
        )

        cases.forEach { case ->
            val r = AccuracyEngine.estimate(case)
            val expected = when {
                r.relative <= AccuracyEngine.HIGH_THRESHOLD -> AccuracyGrade.HIGH
                r.relative <= AccuracyEngine.FAIR_THRESHOLD -> AccuracyGrade.FAIR
                else                                        -> AccuracyGrade.LOW
            }
            assertEquals(
                "grade must follow from relative error (${r.relative})",
                expected,
                r.grade,
            )
        }
    }

    // ── Surface penalty ──────────────────────────────────────────────────────

    @Test
    fun `feature-point placement is penalised versus plane placement`() {
        val onPlane = AccuracyEngine.estimate(input(distance = 2f))
        val onPoint = AccuracyEngine.estimate(
            input(
                distance = 2f,
                a = anchor(surface = AnchorSurface.POINT),
                b = anchor(surface = AnchorSurface.POINT),
            ),
        )

        assertTrue(
            "point-cloud hits are less reliable than plane hits and must " +
                "report a wider tolerance",
            onPoint.toleranceMeters > onPlane.toleranceMeters,
        )
    }

    @Test
    fun `two off-plane points are worse than one`() {
        val one = AccuracyEngine.estimate(
            input(distance = 2f, a = anchor(surface = AnchorSurface.POINT)),
        )
        val two = AccuracyEngine.estimate(
            input(
                distance = 2f,
                a = anchor(surface = AnchorSurface.POINT),
                b = anchor(surface = AnchorSurface.POINT),
            ),
        )

        assertTrue(two.toleranceMeters > one.toleranceMeters)
    }

    @Test
    fun `worst endpoint drives the range penalty not the average`() {
        // A measurement is only as good as its least certain endpoint. Averaging
        // would let a close point mask a distant one.
        val bothMid  = AccuracyEngine.estimate(
            input(distance = 2f, a = anchor(range = 3f), b = anchor(range = 3f)),
        )
        val oneClose = AccuracyEngine.estimate(
            input(distance = 2f, a = anchor(range = 1f), b = anchor(range = 3f)),
        )

        assertEquals(
            "same worst-case range must yield the same range penalty",
            bothMid.toleranceMeters,
            oneClose.toleranceMeters,
            1e-6f,
        )
    }

    @Test
    fun `range penalty saturates rather than growing without bound`() {
        val atSaturation = AccuracyEngine.estimate(
            input(distance = 2f, a = anchor(range = 5f), b = anchor(range = 5f)),
        )
        val wayBeyond = AccuracyEngine.estimate(
            input(distance = 2f, a = anchor(range = 50f), b = anchor(range = 50f)),
        )

        assertEquals(
            atSaturation.toleranceMeters,
            wayBeyond.toleranceMeters,
            1e-6f,
        )
    }

    // ── Explanations ─────────────────────────────────────────────────────────

    @Test
    fun `every estimate carries a non-empty explanation`() {
        val cases = listOf(
            input(),
            input(cameraTracking = false),
            input(a = anchor(tracking = false)),
            input(issue = TrackingIssue.INSUFFICIENT_LIGHT),
            input(issue = TrackingIssue.EXCESSIVE_MOTION),
            input(issue = TrackingIssue.INSUFFICIENT_FEATURES),
            input(issue = TrackingIssue.BAD_STATE),
            input(issue = TrackingIssue.CAMERA_UNAVAILABLE),
            input(a = anchor(surface = AnchorSurface.POINT)),
            input(a = anchor(range = 6f), b = anchor(range = 6f)),
        )

        cases.forEach { case ->
            val reason = AccuracyEngine.estimate(case).reason
            assertTrue(
                "if we can't explain a claim we shouldn't make it. Blank for: $case",
                reason.isNotBlank(),
            )
        }
    }

    @Test
    fun `explanation names the dominant problem`() {
        val lowLight = AccuracyEngine.estimate(
            input(issue = TrackingIssue.INSUFFICIENT_LIGHT),
        )
        assertTrue(
            "expected a light-related hint, got: '${lowLight.reason}'",
            lowLight.reason.contains("light", ignoreCase = true) ||
                lowLight.reason.contains("brighter", ignoreCase = true),
        )

        val farAway = AccuracyEngine.estimate(
            input(distance = 2f, a = anchor(range = 6f), b = anchor(range = 6f)),
        )
        assertTrue(
            "expected a distance-related hint, got: '${farAway.reason}'",
            farAway.reason.contains("closer", ignoreCase = true) ||
                farAway.reason.contains("from the camera", ignoreCase = true),
        )
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    @Test
    fun `tolerance formats with a plus-minus sign in the active unit`() {
        val result = AccuracyEngine.estimate(input(distance = 2f))
        val formatted = result.formatTolerance(UnitSystem.CM)

        assertNotNull(formatted)
        assertTrue("expected a ± prefix, got: $formatted", formatted!!.startsWith("±"))
        assertTrue("expected cm units, got: $formatted", formatted.contains("cm"))
    }

    @Test
    fun `relative error formats as a whole percentage`() {
        val result = AccuracyEngine.estimate(input(distance = 2f))

        assertTrue(result.formatRelative()!!.endsWith("%"))
    }
}
