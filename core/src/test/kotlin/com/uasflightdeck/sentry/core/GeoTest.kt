package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test fun oneDegreeOfLatitudeIsSixtyNm() {
        val d = Geo.distanceNm(LatLon(39.0, -120.0), LatLon(40.0, -120.0))
        assertEquals(60.04, d, 0.02)
    }

    @Test fun haversineLongHaul() {
        // LAX -> JFK great circle ~2,145 nm (haversine, mean radius)
        val d = Geo.distanceNm(LatLon(33.9425, -118.4081), LatLon(40.6397, -73.7789))
        assertEquals(2145.0, d, 6.0)
    }

    @Test fun bearingsCardinal() {
        assertEquals(0.0, Geo.bearingDeg(39.0, -120.0, 40.0, -120.0), 1e-6)
        assertEquals(180.0, Geo.bearingDeg(40.0, -120.0, 39.0, -120.0), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(270.0, Geo.bearingDeg(0.0, 1.0, 0.0, 0.0), 1e-6)
    }

    @Test fun cardinalWords() {
        assertEquals("north", Geo.cardinalWord(0.0))
        assertEquals("north", Geo.cardinalWord(359.0))
        assertEquals("north", Geo.cardinalWord(22.4))
        assertEquals("northeast", Geo.cardinalWord(22.5))
        assertEquals("southwest", Geo.cardinalWord(225.0))
        assertEquals("west", Geo.cardinalWord(-90.0))
        assertEquals("NW", Geo.cardinalAbbrev(315.0))
    }

    @Test fun enRoundTripAndAgreesWithHaversine() {
        val o = LatLon(39.41, -120.04)
        val p = LatLon(39.43, -120.01)
        val en = Geo.toEN(o, p)
        val back = Geo.fromEN(o, en)
        assertEquals(p.lat, back.lat, 1e-7)
        assertEquals(p.lon, back.lon, 1e-7)
        assertEquals(Geo.distanceM(o.lat, o.lon, p.lat, p.lon), en.norm, 1.0)
    }

    @Test fun velocityFromTrack() {
        val v = Geo.velocity(100.0, 90.0)
        assertEquals(Units.ktToMs(100.0), v.e, 1e-9)
        assertEquals(0.0, v.n, 1e-9)
    }

    @Test fun unitConversions() {
        assertEquals(6076.1, Units.FT_PER_NM, 0.1)
        assertEquals(1852.0, Units.nmToM(1.0), 0.0)
        assertEquals(3.048, Units.ftToM(10.0), 1e-9)
        assertEquals(0.5144, Units.ktToMs(1.0), 1e-4)
        assertEquals(5.08, Units.fpmToMs(1000.0), 1e-9)
    }

    private val tfr = Polygon(listOf(
        LatLon(39.43333333, -120.06666667), LatLon(39.44166667, -120.03333333),
        LatLon(39.40833333, -120.01666667), LatLon(39.4, -120.05)))

    @Test fun pointInPolygon() {
        assertTrue(Geo.pointInPolygon(LatLon(39.42, -120.04), tfr))
        assertFalse(Geo.pointInPolygon(LatLon(39.39, -120.04), tfr))
        val holed = tfr.copy(holes = listOf(listOf(LatLon(39.415, -120.045), LatLon(39.425, -120.045),
            LatLon(39.425, -120.035), LatLon(39.415, -120.035))))
        assertFalse(Geo.pointInPolygon(LatLon(39.42, -120.04), holed))
    }

    @Test fun distanceToPolygon() {
        assertEquals(0.0, Geo.distanceToPolygonM(LatLon(39.42, -120.04), tfr), 0.0)
        // due south of the southern vertex by 0.01 deg lat = ~1112 m
        assertEquals(1112.0, Geo.distanceToPolygonM(LatLon(39.39, -120.05), tfr), 5.0)
    }

    @Test fun cpaHeadOn() {
        val c = CpaMath.cpa(EN(1000.0, 0.0), EN(-10.0, 0.0))
        assertEquals(100.0, c.tSec, 1e-9); assertEquals(0.0, c.distM, 1e-9)
    }

    @Test fun cpaOffsetPass() {
        val c = CpaMath.cpa(EN(1000.0, 100.0), EN(-10.0, 0.0))
        assertEquals(100.0, c.tSec, 1e-9); assertEquals(100.0, c.distM, 1e-9)
    }

    @Test fun cpaDivergingIsNow() {
        val c = CpaMath.cpa(EN(1000.0, 0.0), EN(10.0, 0.0))
        assertEquals(0.0, c.tSec, 0.0); assertEquals(1000.0, c.distM, 1e-9)
    }

    @Test fun cpaNoRelativeMotion() {
        val c = CpaMath.cpa(EN(300.0, 400.0), EN(0.0, 0.0))
        assertEquals(0.0, c.tSec, 0.0); assertEquals(500.0, c.distM, 1e-9)
    }

    @Test fun cpaCrossing() {
        // target 1000 m east moving north at 10 m/s, ownship moving east at 10 m/s:
        // rel velocity (-10, 10); closest at t=50 s, distance 707 m
        val c = CpaMath.cpa(EN(1000.0, 0.0), EN(0.0, 10.0) - EN(10.0, 0.0))
        assertEquals(50.0, c.tSec, 1e-9); assertEquals(707.1, c.distM, 0.1)
    }

    @Test fun rangeRateSign() {
        assertTrue(CpaMath.rangeRate(EN(1000.0, 0.0), EN(-10.0, 0.0)) < 0)
        assertTrue(CpaMath.rangeRate(EN(1000.0, 0.0), EN(10.0, 0.0)) > 0)
        assertEquals(0.0, CpaMath.rangeRate(EN(1000.0, 0.0), EN(0.0, 10.0)), 1e-9)
    }
}
