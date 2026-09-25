package com.uasflightdeck.sentry.core

/**
 * Low power (v0.4.0): poll slower while the bound aircraft is on the pad or absent. Owner's numbers: cloud traffic
 * and the drone feed every 5 s on the pad / absent, every 2 s airborne; the Overwatch station link stays 1 s when present.
 */
object PollRates {
    data class Rates(val fleetMs: Long, val cloudMs: Long, val stationMs: Long, val lowPower: Boolean) {
        val label: String get() = if (lowPower) "low power (on pad / absent)" else "airborne"
    }

    val AIRBORNE = Rates(2_000, 2_000, 1_000, lowPower = false)
    val LOW_POWER = Rates(5_000, 5_000, 1_000, lowPower = true)

    /** [boundAirborne] null = the bound aircraft is absent (or nothing pinned: protecting the controller). */
    fun of(boundAirborne: Boolean?): Rates = if (boundAirborne == true) AIRBORNE else LOW_POWER
}
