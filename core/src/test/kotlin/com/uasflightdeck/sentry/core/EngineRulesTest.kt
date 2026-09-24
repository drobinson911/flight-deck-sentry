package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic scenarios for every rule in the spec. Drone hovers at O, 8,000 ft MSL. */
class EngineRulesTest {
    private val O = LatLon(39.4, -120.0)
    private val T0 = 1_790_000_000_000L

    private fun own(t: Long, src: OwnshipSource = OwnshipSource.FLEET_FDA, posT: Long = t) =
        Ownship("D1", "DEMO-1", O.lat, O.lon, 8000.0, 400.0, posT, src)

    private fun at(bearing: Double, nm: Double): LatLon {
        val b = Math.toRadians(bearing); val d = Units.nmToM(nm)
        return Geo.fromEN(O, EN(d * sin(b), d * cos(b)))
    }

    private fun tgt(
        t: Long, bearing: Double, nm: Double, geomFt: Double? = 8000.0, gs: Double = 0.0, trk: Double = 0.0,
        hex: String = "abc123", baroFt: Double? = null, ground: Boolean = false, posT: Long = t,
    ): Target {
        val p = at(bearing, nm)
        return Target(hex = hex, callsign = "N1234", lat = p.lat, lon = p.lon, altGeomFt = geomFt, altBaroFt = baroFt,
            reportsGround = ground, gsKt = gs, trackDeg = trk, posTimeMs = posT, sources = setOf("test"))
    }

    private fun AlertEngine.s(t: Long, targets: List<Target>, zones: List<Zone> = emptyList(), o: Ownship? = own(t), trafficAge: Double = 0.0) =
        step(t, o, targets, zones, trafficAge)

    private fun sec(n: Int) = T0 + n * 1000L

    /**
     * Callout cadence in controller mode (v0.3.5 README "Alert cadence"): one entry callout, then the RANGE cadence
     * (0.8 nm, not diverging = every 12 s) instead of a flat 20 s, one "clear" on exit.
     */
    @Test fun cylinderCadenceEntryThenRangeCadenceThenClearOnce() {
        val e = AlertEngine()
        val ctl = { t: Long -> own(t, src = OwnshipSource.CONTROLLER) }
        val cyl = Cylinder("ops", "ops area", 1.0, 0.0, 3000.0).toZone(O)
        e.s(sec(0), emptyList(), listOf(cyl), o = ctl(sec(0)))
        val ev = ArrayList<AlertEvent>()
        // outside (1.2 nm) for 2 s, then parked inside at 0.8 nm, 300 ft above the controller, for 60 s
        for (i in 1..2) ev += e.s(sec(i), listOf(tgt(sec(i), 90.0, 1.2, geomFt = 8300.0)), listOf(cyl), o = ctl(sec(i))).events
        for (i in 3..62) ev += e.s(sec(i), listOf(tgt(sec(i), 90.0, 0.8, geomFt = 8300.0)), listOf(cyl), o = ctl(sec(i))).events
        // then out to 1.5 nm (beyond the 0.2 nm exit hysteresis)
        for (i in 63..70) ev += e.s(sec(i), listOf(tgt(sec(i), 90.0, 1.5, geomFt = 8300.0)), listOf(cyl), o = ctl(sec(i))).events
        val spoken = ev.map { (it.timeMs - T0) / 1000 to it.kind }
        assertEquals(listOf(3L to EventKind.CYLINDER_ENTRY, 15L to EventKind.PROXIMITY, 27L to EventKind.PROXIMITY,
            39L to EventKind.PROXIMITY, 51L to EventKind.PROXIMITY, 63L to EventKind.CLEAR), spoken)
        assertTrue(ev[0].text, ev[0].text.startsWith("Traffic entering ops area, N1234, east"))
        assertEquals(Severity.CAUTION, ev[1].severity)
        assertEquals("N1234 clear.", ev[5].text)
    }

    /** v0.3.5: 0.5-1 nm and not diverging = every 12 s (was a flat 20 s). */
    @Test fun ringsAndReannounceEvery12sBetweenHalfAndOneMile() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())                             // acquire
        val spoken = ArrayList<AlertEvent>()
        for (i in 1..45) spoken += e.s(sec(i), listOf(tgt(sec(i), 90.0, 0.8))).events
        val prox = spoken.filter { it.kind == EventKind.PROXIMITY }
        assertEquals(listOf(sec(1), sec(13), sec(25), sec(37)), prox.map { it.timeMs })
        assertTrue(prox.all { it.severity == Severity.CAUTION })
        assertTrue(prox[0].text.startsWith("Caution. Traffic, N1234, east, 4,900 feet, same altitude"))
    }

    @Test fun advisoryReannouncesEvery30s() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val ev = (1..65).flatMap { e.s(sec(it), listOf(tgt(sec(it), 0.0, 2.0))).events }.filter { it.kind == EventKind.PROXIMITY }
        assertEquals(listOf(sec(1), sec(31), sec(61)), ev.map { it.timeMs })
        assertTrue(ev[0].text.startsWith("Traffic, N1234, north, 2.0 miles"))
    }

    @Test fun escalationIsImmediate() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val a = e.s(sec(1), listOf(tgt(sec(1), 0.0, 2.0))).events
        val b = e.s(sec(3), listOf(tgt(sec(3), 0.0, 0.9))).events
        val c = e.s(sec(5), listOf(tgt(sec(5), 0.0, 0.4))).events
        assertEquals(Severity.ADVISORY, a.single().severity)
        assertEquals(Severity.CAUTION, b.single().severity)
        assertEquals(Severity.WARNING, c.single().severity)
        assertTrue(c.single().text.startsWith("Warning. Traffic"))
    }

    /** v0.3.3: the protected volume is SURFACE up to 2,000 ft above the drone (8,000 MSL here). */
    @Test fun ceilingAboveTheDroneFilters() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        assertTrue(e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.3, geomFt = 10_100.0))).events.isEmpty())
        val r = e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.3, geomFt = 9_900.0)))
        assertEquals(Severity.WARNING, r.events.single().severity)
        assertTrue(r.events.single().text.contains("1,900 above"))
    }

    /** Owner: "we never want anything flying under us". 3,000 ft BELOW at 0.4 nm warns (the old ±2,000 band was silent). */
    @Test fun trafficFarBelowTheDroneWarns() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val r = e.s(sec(1), listOf(tgt(sec(1), 90.0, 0.4, geomFt = 5_000.0)))
        assertEquals(Severity.WARNING, r.events.single().severity)
        assertTrue(r.events.single().text, r.events.single().text.contains("3,000 below"))
        // and one right at the surface under the drone too
        val e2 = AlertEngine(); e2.s(sec(0), emptyList())
        assertEquals(Severity.WARNING, e2.s(sec(1), listOf(tgt(sec(1), 90.0, 0.4, geomFt = 500.0))).events.single().severity)
    }

    @Test fun trafficThreeThousandAboveAtPointFourIsSilent() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val r = e.s(sec(1), listOf(tgt(sec(1), 90.0, 0.4, geomFt = 11_000.0)))
        assertTrue(r.events.isEmpty())
        assertEquals(Severity.NONE, r.targets.single().severity)
    }

    @Test fun ceilingIsASetting() {
        val e = AlertEngine(SentryConfig(ceilingAboveFt = 4000.0))
        e.s(sec(0), emptyList())
        assertEquals(Severity.WARNING, e.s(sec(1), listOf(tgt(sec(1), 90.0, 0.4, geomFt = 11_000.0))).events.single().severity)
    }

    /** The predictive rule uses the same volume: a fast mover 3,000 ft below converging warns early; 3,000 ft above doesn't. */
    @Test fun predictiveUsesSurfaceToCeiling() {
        val below = AlertEngine(); below.s(sec(0), emptyList())
        val ev = below.s(sec(1), listOf(tgt(sec(1), 0.0, 2.8, geomFt = 5_000.0, gs = 200.0, trk = 180.0))).events.single()
        assertEquals(EventKind.PREDICTIVE, ev.kind); assertEquals(Severity.WARNING, ev.severity)
        assertTrue(ev.text, ev.text.contains("3,000 below"))
        val above = AlertEngine(); above.s(sec(0), emptyList())
        val ev2 = above.s(sec(1), listOf(tgt(sec(1), 0.0, 2.8, geomFt = 11_000.0, gs = 200.0, trk = 180.0))).events
        assertTrue(ev2.toString(), ev2.none { it.kind == EventKind.PREDICTIVE || it.severity >= Severity.ADVISORY })
    }

    @Test fun baroCorrectionIsEstimatedGeomIsNot() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val r = e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.8, geomFt = null, baroFt = 7400.0)))
        val v = r.targets.single()
        assertTrue(v.altEstimated)
        assertEquals(-300.0, v.dvFt!!, 1e-9)                   // 7400 + 300 - 8000
        val r2 = e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.8, geomFt = 7500.0, baroFt = 7400.0)))
        assertFalse(r2.targets.single().altEstimated)
        assertEquals(-500.0, r2.targets.single().dvFt!!, 1e-9)
    }

    @Test fun staleTargetIsNeverAnnouncedOrShown() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val r = e.s(sec(40), listOf(tgt(sec(40), 0.0, 0.2, posT = sec(9))))   // 31 s old
        assertTrue(r.events.none { it.hex != null })
        assertTrue(r.targets.isEmpty())
    }

    @Test fun trackLostWhenAnAnnouncedTargetDisappears() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.4)))
        val r = e.s(sec(2), emptyList())
        assertEquals("N1234 track lost.", r.events.single().text)
    }

    @Test fun groundSlowIgnoredGroundFastIsAirborneAltitudeUnknown() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val slow = e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.3, geomFt = null, ground = true, gs = 12.0)))
        assertTrue(slow.events.isEmpty()); assertTrue(slow.targets.isEmpty())
        val fast = e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.3, geomFt = null, ground = true, gs = 160.0, trk = 90.0)))
        val ev = fast.events.single()
        assertEquals(Severity.WARNING, ev.severity)
        assertTrue(ev.text.contains("altitude unknown"))
        assertTrue(fast.targets.single().groundModeAirborne)
    }

    @Test fun predictiveCpaWarnsBeforeTheRings() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        // 2.8 nm north, heading south at 200 kt straight at the drone: CPA ~50 s, 0 nm
        val r = e.s(sec(1), listOf(tgt(sec(1), 0.0, 2.8, gs = 200.0, trk = 180.0)))
        val ev = r.events.single()
        assertEquals(EventKind.PREDICTIVE, ev.kind)
        assertEquals(Severity.WARNING, ev.severity)
        assertTrue(ev.text, ev.text.contains("converging, closest"))
        assertEquals(50.0, r.targets.single().cpa!!.tSec, 1.5)
    }

    @Test fun predictiveNeedsTheMissInsideWarningRing() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        // offset 1 nm east, passing south: CPA 1 nm -> only the advisory ring
        val p = at(0.0, 2.8)
        val off = Geo.fromEN(p, EN(Units.nmToM(1.0), 0.0))
        val t = tgt(sec(1), 0.0, 2.8, gs = 200.0, trk = 180.0).copy(lat = off.lat, lon = off.lon)
        val ev = e.s(sec(1), listOf(t)).events.single()
        assertEquals(EventKind.PROXIMITY, ev.kind); assertEquals(Severity.ADVISORY, ev.severity)
    }

    @Test fun predictiveBeyondHorizonIsIgnored() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        // 2.9 nm at 100 kt: CPA in ~104 s > 60 s
        val ev = e.s(sec(1), listOf(tgt(sec(1), 0.0, 2.9, gs = 100.0, trk = 180.0))).events.single()
        assertEquals(Severity.ADVISORY, ev.severity)
    }

    @Test fun clearOnceWithHysteresis() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 2.9)))            // advisory
        // just outside 3.0 but inside 3.0 + 0.2 hysteresis: not clear yet
        assertTrue(e.s(sec(2), listOf(tgt(sec(2), 0.0, 3.1, gs = 150.0, trk = 0.0))).events.isEmpty())
        val c = e.s(sec(3), listOf(tgt(sec(3), 0.0, 3.3, gs = 150.0, trk = 0.0))).events
        assertEquals("N1234 clear, diverging.", c.single().text)
        assertTrue(e.s(sec(4), listOf(tgt(sec(4), 0.0, 3.4, gs = 150.0, trk = 0.0))).events.isEmpty())
    }

    @Test fun divergingTargetIsNotNaggedButConvergingAgainIs() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.8, gs = 5.0, trk = 0.0)))       // caution, drifting away slowly
        // v0.3.5: it was called inside 1 nm, so opening = one "passing, diverging", then nothing for 45 s
        val quiet = (2..30).flatMap { e.s(sec(it), listOf(tgt(sec(it), 0.0, 0.8, gs = 20.0, trk = 0.0))).events }
        assertEquals(listOf("N1234 passing, diverging."), quiet.map { it.text })
        assertEquals(sec(2), quiet.single().timeMs)
        val back = e.s(sec(31), listOf(tgt(sec(31), 0.0, 0.8, gs = 20.0, trk = 180.0))).events
        assertEquals(Severity.CAUTION, back.single().severity)
        assertTrue(back.single().text.endsWith("converging."))
    }

    @Test fun ownshipLostAndRegained() {
        val e = AlertEngine()
        assertEquals("Watching DEMO-1", e.s(sec(0), emptyList()).events.single().text)
        assertTrue(e.s(sec(10), emptyList(), o = own(sec(10), posT = sec(0))).events.isEmpty())   // 10 s old: ok
        assertEquals("Drone position lost", e.s(sec(16), emptyList(), o = own(sec(16), posT = sec(0))).events.single().text)
        assertTrue(e.s(sec(17), emptyList(), o = null).events.isEmpty())                            // said once
        assertEquals("Drone position regained", e.s(sec(18), emptyList()).events.single().text)
    }

    @Test fun ownshipLostReminder() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(20), emptyList(), o = null)
        assertTrue(e.s(sec(100), emptyList(), o = null).events.isEmpty())
        assertEquals("Drone position still lost", e.s(sec(140), emptyList(), o = null).events.single().text)
    }

    @Test fun noOwnshipAtStartIsAnnounced() {
        val e = AlertEngine()
        assertTrue(e.s(sec(0), emptyList(), o = null).events.isEmpty())
        assertEquals("No drone position", e.s(sec(15), emptyList(), o = null).events.single().text)
    }

    @Test fun manualFallbackIsSpoken() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val ev = e.s(sec(1), emptyList(), o = own(sec(1), src = OwnshipSource.MANUAL_PINNED)).events.single()
        assertEquals("Drone feed lost, using manual position", ev.text)
        assertEquals("Drone position regained, watching DEMO-1", e.s(sec(2), emptyList()).events.single().text)
    }

    @Test fun landingTargetSaysOnTheGroundNotTrackLost() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 270.0, 1.5, gs = 130.0, trk = 270.0)))
        val ev = e.s(sec(2), listOf(tgt(sec(2), 270.0, 1.6, geomFt = null, ground = true, gs = 20.0))).events
        assertEquals("N1234 on the ground.", ev.single().text)
        assertTrue(e.s(sec(3), emptyList()).events.isEmpty())             // no "track lost" afterwards
    }

    @Test fun switchingToAnotherDroneResetsTracksSilently() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.8)))                        // caution on D1
        val other = Ownship("D2", "DEMO-2", 36.6, -118.9, 3700.0, 300.0, sec(2), OwnshipSource.FLEET_DRONESENSE)
        val ev = e.s(sec(2), emptyList(), o = other).events
        assertEquals(listOf("Now watching DEMO-2"), ev.map { it.text })       // and no "N1234 track lost"
    }

    @Test fun trafficStaleOnceAndRestored() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        assertTrue(e.s(sec(1), emptyList(), trafficAge = 30.0).events.isEmpty())
        assertEquals("Traffic data stale", e.s(sec(2), emptyList(), trafficAge = 31.0).events.single().text)
        assertTrue(e.s(sec(3), emptyList(), trafficAge = 32.0).events.isEmpty())
        assertEquals("Traffic data restored", e.s(sec(4), emptyList(), trafficAge = 1.0).events.single().text)
    }

    private val box = Zone("tfr:6/9999", "6/9999", ZoneKind.TFR, listOf(Polygon(listOf(
        at(315.0, 2.0), at(45.0, 2.0), at(135.0, 2.0), at(225.0, 2.0)))),
        floor = AltLimit.SURFACE, ceiling = AltLimit(8500.0, AltRef.MSL))

    @Test fun tfrEntryRespectsVerticalBand() {
        // high target crossing the box above 8,500: no entry; lower one: entry once
        val e = AlertEngine(SentryConfig(ceilingAboveFt = 500.0))
        e.s(sec(0), emptyList(), listOf(box))
        val hi = (1..3).flatMap { i -> e.s(sec(i), listOf(tgt(sec(i), 90.0, 2.0 - i * 0.3, geomFt = 9000.0, hex = "high01")), listOf(box)).events }
        assertTrue(hi.none { it.kind == EventKind.TFR_ENTRY })
        val e2 = AlertEngine(SentryConfig(ceilingAboveFt = 500.0))
        e2.s(sec(0), emptyList(), listOf(box))
        val lo = (1..6).flatMap { i -> e2.s(sec(i), listOf(tgt(sec(i), 90.0, 2.0 - i * 0.2, geomFt = 5000.0, hex = "low001")), listOf(box)).events }
        val entry = lo.filter { it.kind == EventKind.TFR_ENTRY }
        assertEquals(1, entry.size)
        assertTrue(entry[0].text, entry[0].text.startsWith("Traffic entering TFR 6/9999, N1234, east,"))
        assertTrue(entry[0].text.contains("3,000 below"))
        assertEquals(Severity.CAUTION, entry[0].severity)
    }

    @Test fun tfrUnknownAltitudeCountsAsInside() {
        val e = AlertEngine()
        e.s(sec(0), emptyList(), listOf(box))
        e.s(sec(1), listOf(tgt(sec(1), 90.0, 1.6, geomFt = null)), listOf(box))   // box half-width is 1.41 nm
        val ev = e.s(sec(2), listOf(tgt(sec(2), 90.0, 1.2, geomFt = null)), listOf(box)).events
        val entry = ev.single { it.kind == EventKind.TFR_ENTRY }
        assertTrue(entry.text.contains("altitude unknown"))
    }

    @Test fun firstSeenAlreadyInsideSaysInside() {
        val e = AlertEngine(SentryConfig(advisoryNm = 0.5, cautionNm = 0.5, warningNm = 0.5))
        e.s(sec(0), emptyList(), listOf(box))
        val ev = e.s(sec(1), listOf(tgt(sec(1), 90.0, 1.0, geomFt = 6000.0)), listOf(box)).events
        assertTrue(ev.single().text.startsWith("Traffic inside TFR 6/9999"))
    }

    @Test fun farTfrIsNotWatched() {
        val far = box.copy(id = "far", polygons = listOf(Polygon(listOf(at(0.0, 20.0), at(10.0, 20.0), at(5.0, 25.0)))))
        val e = AlertEngine()
        assertTrue(e.s(sec(0), emptyList(), listOf(box, far)).watchedZones == listOf("TFR 6/9999"))
    }

    @Test fun aglCeilingUsesGroundUnderDrone() {
        // ceiling 1,000 AGL; ground under drone = 8000 - 400 = 7600 -> top 8600 MSL
        val z = box.copy(ceiling = AltLimit(1000.0, AltRef.AGL))
        val e = AlertEngine()
        e.s(sec(0), emptyList(), listOf(z))
        assertTrue(e.s(sec(1), listOf(tgt(sec(1), 90.0, 1.0, geomFt = 8700.0)), listOf(z)).targets.single().zones.isEmpty())
        assertEquals(listOf("TFR 6/9999"), e.s(sec(2), listOf(tgt(sec(2), 90.0, 1.0, geomFt = 8500.0, hex = "x2")), listOf(z)).targets.first { it.hex == "x2" }.zones)
    }

    @Test fun deadReckonsUpToTenSeconds() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        // reported 3 nm north 20 s ago, 180 kt south: extrapolated by only 10 s (0.5 nm)
        val r = e.s(sec(20), listOf(tgt(sec(20), 0.0, 3.0, gs = 180.0, trk = 180.0, posT = sec(0))))
        assertEquals(2.5, r.targets.single().distNm, 0.01)
    }

    @Test fun velocityDerivedWhenTrackMissing() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val a = tgt(sec(1), 0.0, 2.0).copy(gsKt = 160.0, trackDeg = null)
        val b = tgt(sec(4), 0.0, 1.8667).copy(gsKt = 160.0, trackDeg = null)   // 0.1333 nm in 3 s = 160 kt
        e.s(sec(1), listOf(a))
        val r = e.s(sec(4), listOf(b))
        assertEquals(Trend.CONVERGING, r.targets.single().trend)
        assertTrue(r.targets.single().cpa!!.tSec in 40.0..44.0)
    }
}
