package com.uasflightdeck.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationUrlTest {
    @Test fun normalizesWhatAPilotTypes() {
        assertEquals("http://192.168.1.20:8080", Settings.normalizeStationBase("192.168.1.20"))
        assertEquals("http://192.168.1.20:9000", Settings.normalizeStationBase("192.168.1.20:9000/"))
        assertEquals("http://t91.local:8080", Settings.normalizeStationBase("http://t91.local/data/aircraft.json"))
        assertEquals("https://x.example:8443", Settings.normalizeStationBase(" https://x.example:8443 "))
        assertNull(Settings.normalizeStationBase("  "))
    }
}
