package com.tape.measure.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations for Tape.
 *
 * We use Navigation Compose 2.8's serializable-object routes instead of
 * string literals so the compiler catches typos and back-stack operations
 * are type-safe (popUpTo<Route.Welcome> {} etc.).
 */
sealed interface Route {

    /** First screen: welcome / brand splash. */
    @Serializable data object Welcome : Route

    /**
     * Camera-permission gate.
     * Also checks ARCore availability before asking for camera.
     * Routes to [Measure] on success, [Unsupported] if device lacks ARCore.
     */
    @Serializable data object Permission : Route

    /** Shown when ARCore can't run here. Offers the practice room instead. */
    @Serializable data object Unsupported : Route

    /**
     * The measurement screen. [simulated] measures the practice room instead
     * of the camera feed, for where ARCore can't run.
     */
    @Serializable data class Measure(val simulated: Boolean = false) : Route

    /** List of saved measurements (Room-backed). */
    @Serializable data object Saved : Route

    /** App settings: default unit, theme, about. */
    @Serializable data object Settings : Route
}
