package com.tape.measure.domain.accuracy

import com.tape.measure.domain.model.UnitSystem
import org.junit.Test

/**
 * Not an assertion suite — a readable dump of what the engine actually claims in
 * realistic situations, so the numbers can be eyeballed against physical reality
 * and against the old hardcoded "±1 cm".
 *
 * Kept in the repo deliberately: if someone retunes the constants, this prints the
 * blast radius in human units instead of leaving it to trust.
 */
class AccuracyEngineScenarioTest {

    private fun obs(range: Float, surface: AnchorSurface = AnchorSurface.PLANE) =
        AnchorObservation(range, surface, isTracking = true)

    @Test
    fun `print realistic scenarios`() {
        data class Scenario(val name: String, val input: AccuracyInput)

        val scenarios = listOf(
            Scenario(
                "Laptop width, close up, good light",
                AccuracyInput(0.35f, true, TrackingIssue.NONE, obs(0.4f), obs(0.4f)),
            ),
            Scenario(
                "Doorway width (the 'will the sofa fit' case)",
                AccuracyInput(0.82f, true, TrackingIssue.NONE, obs(0.9f), obs(0.9f)),
            ),
            Scenario(
                "Sofa length, arm's reach",
                AccuracyInput(2.10f, true, TrackingIssue.NONE, obs(1.2f), obs(1.4f)),
            ),
            Scenario(
                "Living room wall, standing back",
                AccuracyInput(6.00f, true, TrackingIssue.NONE, obs(3.0f), obs(3.5f)),
            ),
            Scenario(
                "Same wall, but dim evening light",
                AccuracyInput(6.00f, true, TrackingIssue.INSUFFICIENT_LIGHT, obs(3.0f), obs(3.5f)),
            ),
            Scenario(
                "Blank white wall (no texture to lock onto)",
                AccuracyInput(3.00f, true, TrackingIssue.INSUFFICIENT_FEATURES, obs(2.0f), obs(2.0f)),
            ),
            Scenario(
                "Rushing, phone waving around",
                AccuracyInput(1.50f, true, TrackingIssue.EXCESSIVE_MOTION, obs(1.0f), obs(1.0f)),
            ),
            Scenario(
                "Ceiling height, points on feature cloud not planes",
                AccuracyInput(
                    2.70f, true, TrackingIssue.NONE,
                    obs(2.5f, AnchorSurface.POINT), obs(2.6f, AnchorSurface.POINT),
                ),
            ),
        )

        println()
        println("╔══════════════════════════════════════════════════════════════════════════╗")
        println("║  ACCURACY ENGINE — what Tape actually claims                             ║")
        println("╚══════════════════════════════════════════════════════════════════════════╝")
        println()
        println("%-46s %9s %9s %6s %6s".format("SCENARIO", "SPAN", "TOLERANCE", "REL", "GRADE"))
        println("-".repeat(82))

        scenarios.forEach { (name, input) ->
            val r = AccuracyEngine.estimate(input)
            println(
                "%-46s %9s %9s %6s %6s".format(
                    name.take(46),
                    UnitSystem.CM.format(input.distanceMeters),
                    r.formatTolerance(UnitSystem.CM) ?: "—",
                    r.formatRelative() ?: "—",
                    r.grade.name,
                )
            )
            println("      └─ ${r.reason}")
        }

        println("-".repeat(82))
        println("For reference, the code this replaces claimed a flat '±1 cm' for")
        println("every single row above whenever tracking was good.")
        println()
    }
}
