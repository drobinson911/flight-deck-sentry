package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The banner's fixed shape and the "Clear:" hint. */
class BannerTest {
    private fun v(
        tier: Tier, dist: Double = 7.9, brg: Double = 225.0, dv: Double? = -300.0, est: Boolean = false, vs: Double? = 800.0,
        gs: Double? = 160.0, pred: Prediction.Result? = Prediction.Result(178.0, 0.3, -100.0, 7.9, -80.0, null),
        escape: Double? = 315.0, vert: Int = 1, type: String? = "C172", droneVs: Double? = 0.0,
    ) = AlertEngine.TargetView(hex = "a1", displayId = "N388KM", distNm = dist, bearingDeg = brg, dvFt = dv, altEstimated = est,
        trend = Trend.CONVERGING, tier = tier, prediction = pred, zones = emptyList(), ageSec = 1.0, sources = emptySet(),
        groundModeAirborne = false, type = type, gsKt = gs, vsFpm = vs, droneVsFpm = droneVs, escapeBearingDeg = escape, escapeVertical = vert)

    @Test fun trackBannerExactlyAsPlanned() {
        val b = Banner.traffic(v(Tier.TRACK))
        assertEquals("▲ TRACK · N388KM Cessna", b.title)
        assertEquals("SW 7.9 mi · 160 kt · 300 below, climbing", b.line2)
        assertEquals("Passing within 0.3 mi in 2:58", b.line3)
        assertEquals("Clear: move NW ↑", b.line4)
    }

    @Test fun warningBanner() {
        val b = Banner.traffic(v(Tier.WARNING, dist = 1.9, pred = Prediction.Result(89.0, 2700 / Units.FT_PER_NM, -100.0, 1.9, -80.0, null)))
        assertEquals("⚠ WARNING · N388KM", b.title)
        assertEquals("Closest 2,700 ft in 1:29", b.line3)
    }

    @Test fun collisionBannerCrossing() {
        val b = Banner.traffic(v(Tier.COLLISION, dist = 0.6, dv = -160.0, vs = 500.0,
            pred = Prediction.Result(20.0, 0.2, 0.0, 0.6, -80.0, crossingInSec = 12.2)))
        assertEquals("‼ COLLISION RISK · N388KM", b.title)
        assertEquals("200 below, climbing through your altitude · 12 s", b.line3)
        // no crossing: closest + the vertical at CPA
        val c = Banner.traffic(v(Tier.COLLISION, dist = 0.6, pred = Prediction.Result(45.0, 300 / Units.FT_PER_NM, -120.0, 0.6, -80.0, null)))
        assertEquals("Closest 300 ft in 0:45, 100 below", c.line3)
    }

    @Test fun otherTitles() {
        assertEquals("◆ CAUTION · N388KM", Banner.traffic(v(Tier.CAUTION)).title)
        assertEquals("△ ADVISORY · N388KM Cessna", Banner.traffic(v(Tier.ADVISORY)).title)
        assertEquals("● PASSING · N388KM", Banner.passing(v(Tier.CAUTION), 0.24).title)
        assertEquals("Diverging · closest was 1,500 ft", Banner.passing(v(Tier.CAUTION), 0.24).line3)
        assertEquals("○ CLEAR · N388KM", Banner.clear("N388KM", "Outside 3.0 mi").title)
        assertEquals("No longer a factor", Banner.noLongerFactor(v(Tier.NONE)).line3)
    }

    @Test fun numbers() {
        assertEquals("7.9 mi", Banner.dist(7.94)); assertEquals("1.0 mi", Banner.dist(1.0))
        assertEquals("2,700 ft", Banner.dist(0.444)); assertEquals("100 ft", Banner.dist(0.001))
        assertEquals("0.3 mi", Banner.miles(0.26)); assertEquals("300 ft", Banner.miles(0.05))
        assertEquals("2:58", Banner.clock(178.2)); assertEquals("0:09", Banner.clock(9.4)); assertEquals("0:00", Banner.clock(-3.0))
        assertEquals("300 below", Banner.vertical(-312.0)); assertEquals("1,200 above", Banner.vertical(1234.0))
        assertEquals("same alt", Banner.vertical(40.0)); assertEquals("alt unknown", Banner.vertical(null))
        assertEquals("≈300 below", Banner.vertical(-290.0, estimated = true))
        assertEquals("level", Banner.trendWord(100.0)); assertEquals("descending", Banner.trendWord(-700.0)); assertNull(Banner.trendWord(null))
        assertEquals("SW 7.9 mi · 160 kt · alt unknown", Banner.line2(v(Tier.TRACK, dv = null)))
        assertEquals("SW 7.9 mi · ≈300 below", Banner.line2(v(Tier.TRACK, gs = null, vs = null, est = true)))
    }

    @Test fun hintSideOfHisTrack() {
        // He flies north (000). Drone 0.5 nm EAST of him -> drone is on his right -> move E (right of his track).
        assertEquals(90.0, Banner.escapeBearing(0.0, EN(-Units.nmToM(0.5), 0.0), null), 1e-9)
        // Drone WEST of him -> move W.
        assertEquals(270.0, Banner.escapeBearing(0.0, EN(Units.nmToM(0.5), 0.0), null), 1e-9)
        // Centred (dead ahead): he turns right -> move left (W); he turns left -> right (E); straight -> right.
        val ahead = EN(0.0, -Units.nmToM(2.0))
        assertEquals(270.0, Banner.escapeBearing(0.0, ahead, 2.0), 1e-9)
        assertEquals(90.0, Banner.escapeBearing(0.0, ahead, -2.0), 1e-9)
        assertEquals(90.0, Banner.escapeBearing(0.0, ahead, null), 1e-9)
        // southbound, drone on his right (west): move W
        assertEquals(270.0, Banner.escapeBearing(180.0, EN(Units.nmToM(0.3), Units.nmToM(1.0)), null), 1e-9)
    }

    @Test fun hintVerticalArrows() {
        assertEquals(1, Banner.escapeVertical(-160.0, 500.0, crossing = true))     // below and climbing -> ↑
        assertEquals(-1, Banner.escapeVertical(400.0, -800.0, crossing = false))   // above and descending -> ↓
        assertEquals(0, Banner.escapeVertical(400.0, 800.0, crossing = false))     // above and climbing away
        assertEquals(0, Banner.escapeVertical(-400.0, -800.0, crossing = false))   // below and descending away
        assertEquals(0, Banner.escapeVertical(-100.0, 100.0, crossing = false))    // level
        assertEquals(0, Banner.escapeVertical(null, 800.0, crossing = false))
        assertEquals("Clear: move SE ↓", Banner.hint(v(Tier.WARNING, escape = 135.0, vert = -1)))
        assertEquals("Clear: move E", Banner.hint(v(Tier.WARNING, escape = 90.0, vert = 0)))
        assertNull(Banner.hint(v(Tier.WARNING, escape = null)))
    }

    @Test fun makers() {
        assertEquals("Cessna", Banner.maker("C172")); assertEquals("Cessna", Banner.maker("C208"))
        assertEquals("Cirrus", Banner.maker("S22T")); assertEquals("Lockheed", Banner.maker("C130"))
        assertEquals("Helicopter", Banner.maker("R44")); assertEquals("Air Tractor", Banner.maker("AT8T"))
        assertEquals("ATR", Banner.maker("AT72")); assertEquals("XYZ9", Banner.maker("xyz9")); assertNull(Banner.maker(" "))
    }

    @Test fun displayFormatting() {
        assertEquals("0.24 nm (1,458 ft)", Phrasing.displayDistance(0.24))
        assertEquals("250 ft below (est.)", Phrasing.displayVertical(-250.0, true))
    }
}
