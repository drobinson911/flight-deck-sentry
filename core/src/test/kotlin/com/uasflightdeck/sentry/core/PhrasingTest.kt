package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhrasingTest {
    @Test fun distances() {
        assertEquals("1,500 feet", Phrasing.spokenDistance(0.24))
        assertEquals("100 feet", Phrasing.spokenDistance(0.001))
        assertEquals("6,000 feet", Phrasing.spokenDistance(0.99))
        assertEquals("1.0 miles", Phrasing.spokenDistance(1.0))
        assertEquals("2.1 miles", Phrasing.spokenDistance(2.14))
        assertEquals("0.24 nm (1,458 ft)", Phrasing.displayDistance(0.24))
    }

    @Test fun verticals() {
        assertEquals("600 below", Phrasing.spokenVertical(-612.0))
        assertEquals("1,200 above", Phrasing.spokenVertical(1234.0))
        assertEquals("same altitude", Phrasing.spokenVertical(40.0))
        assertEquals("altitude unknown", Phrasing.spokenVertical(null))
        assertEquals("250 ft below (est.)", Phrasing.displayVertical(-250.0, true))
    }

    @Test fun spelledIds() {
        assertEquals("N 3 8 8 K M", Phrasing.spelledId("N388KM"))
        assertEquals("hex A 4 7 9 E F", Phrasing.spelledId("hex A479EF"))
        assertEquals("U R 3 3", Phrasing.spelledId("DEMO-2"))
        assertEquals("manual pin", Phrasing.spelledId("manual pin"))
    }
}
