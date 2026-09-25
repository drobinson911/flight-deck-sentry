package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PollRatesTest {
    @Test fun lowPowerOnThePadOrAbsent() {
        assertEquals(PollRates.Rates(2_000, 2_000, 1_000, lowPower = false), PollRates.of(boundAirborne = true))
        assertEquals(PollRates.Rates(5_000, 5_000, 1_000, lowPower = true), PollRates.of(boundAirborne = false))
        assertEquals(PollRates.LOW_POWER, PollRates.of(boundAirborne = null))
        assertEquals("low power (on pad / absent)", PollRates.of(null).label)
    }
}
