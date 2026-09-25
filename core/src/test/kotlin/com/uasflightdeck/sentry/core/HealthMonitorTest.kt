package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MainRowLabelWidth = 17

class HealthMonitorTest {
    @Test fun lostAndRegained() {
        val h = HealthMonitor()
        h.register("station", SystemText.HEALTH_SOURCES.toMap().getValue("station"), 10.0)
        h.setEnabled("station", true, 0)
        h.ok("station", 1000)
        assertTrue(h.step(1000).isEmpty())
        assertTrue(h.step(11_000).isEmpty())
        assertEquals("Overwatch station link lost", h.step(11_001).single().text)
        assertTrue(h.step(12_000).isEmpty())
        h.ok("station", 13_000)
        assertEquals("Overwatch station link regained", h.step(13_000).single().text)
    }

    @Test fun neverReachable() {
        val h = HealthMonitor()
        h.register("station", SystemText.HEALTH_SOURCES.toMap().getValue("station"), 10.0)
        h.setEnabled("station", true, 0)
        assertTrue(h.step(5_000).isEmpty())
        assertEquals("Overwatch station link not reachable", h.step(10_001).single().text)
        assertEquals(HealthMonitor.State.LOST, h.stateOf(h.get("station")!!, 10_001))
    }

    @Test fun disabledIsSilent() {
        val h = HealthMonitor()
        h.register("cloud", "Cloud traffic", 20.0)
        h.setEnabled("cloud", false, 0)
        assertTrue(h.step(100_000).isEmpty())
        assertEquals(HealthMonitor.State.DISABLED, h.stateOf(h.get("cloud")!!, 100_000))
    }

    @Test fun stationLinkIsCalledTheOverwatchStation() {
        assertEquals("Overwatch station link", SystemText.HEALTH_SOURCES.toMap()["station"])
        assertEquals("Overwatch station", SystemText.rowLabel("station", "Overwatch station link"))
        assertEquals("Drone feed", SystemText.rowLabel("fleet", "Drone feed"))
        assertTrue(SystemText.rowLabel("station", "x").length <= MainRowLabelWidth)
    }
}
