package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetStatusTest {
    private fun ok(n: Int) = FleetStatus.Fetch(count = n)
    private fun err(e: String) = FleetStatus.Fetch(error = e)

    @Test fun zeroDronesSaysWhy() {
        assertEquals(FleetStatus.Status(false, FleetStatus.NO_TOKEN), FleetStatus.of(true, FleetStatus.Fetch(), FleetStatus.Fetch()))
        assertEquals(FleetStatus.Status(true, "no drones in feed"), FleetStatus.of(false, ok(0), ok(0)))
        val rejected = FleetStatus.of(false, err("HTTP 401"), err("HTTP 401"))
        assertFalse(rejected.ok); assertEquals("TOKEN REJECTED (HTTP 401): check the fleet token", rejected.detail)
        assertEquals("TOKEN REJECTED (HTTP 403): check the fleet token", FleetStatus.of(false, err("HTTP 500"), err("HTTP 403")).detail)
        assertEquals("feed error: HTTP 502", FleetStatus.of(false, err("HTTP 502"), err("HTTP 502")).detail)
        assertEquals("feed error: timeout; HTTP 500", FleetStatus.of(false, err("timeout"), err("HTTP 500")).detail)
    }

    @Test fun countsAndPartialFailure() {
        assertEquals("1 drone in feed", FleetStatus.of(false, ok(0), ok(1)).detail)
        assertEquals("3 drones in feed", FleetStatus.of(false, ok(1), ok(2)).detail)
        val partial = FleetStatus.of(false, err("HTTP 500"), ok(2))
        assertTrue(partial.ok); assertEquals("2 drones in feed (our-drones: HTTP 500)", partial.detail)
        assertEquals("no drones in feed (dronesense: HTTP 401)", FleetStatus.of(false, ok(0), err("HTTP 401")).detail)
    }
}
