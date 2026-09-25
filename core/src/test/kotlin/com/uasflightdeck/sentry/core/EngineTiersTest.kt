package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.Synthetic.O
import com.uasflightdeck.sentry.core.Synthetic.at
import com.uasflightdeck.sentry.core.Synthetic.mover
import com.uasflightdeck.sentry.core.Synthetic.offset
import com.uasflightdeck.sentry.core.Synthetic.own
import com.uasflightdeck.sentry.core.Synthetic.run
import com.uasflightdeck.sentry.core.Synthetic.s
import com.uasflightdeck.sentry.core.Synthetic.sec
import com.uasflightdeck.sentry.core.Synthetic.tgt
import com.uasflightdeck.sentry.core.Synthetic.traffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v0.4.0 tiers, one rule per test. Drone hovers at O, 8,000 ft MSL. */
class EngineTiersTest {
    private fun tierOf(t: Target, cfg: SentryConfig = SentryConfig(), o: Ownship? = null): Tier {
        val e = AlertEngine(cfg)
        e.s(sec(0), emptyList())
        return e.s(sec(1), listOf(t), o = o ?: own(sec(1))).targets.single().tier
    }

    // ── rings (actual position) ─────────────────────────────────────────
    @Test fun ringsAreTheBackstop() {
        assertEquals(Tier.ADVISORY, tierOf(tgt(sec(1), 90.0, 2.5)))
        assertEquals(Tier.CAUTION, tierOf(tgt(sec(1), 90.0, 0.8)))
        assertEquals(Tier.WARNING, tierOf(tgt(sec(1), 90.0, 0.4)))            // stationary: ring backstop
        assertEquals(Tier.NONE, tierOf(tgt(sec(1), 90.0, 3.5)))
    }

    @Test fun ringsAreLiveWithTheDroneOnThePad() {
        val pad = Ownship("D1", "DEMO-1", O.lat, O.lon, 7600.0, 0.0, sec(1), OwnshipSource.FLEET_DRONESENSE, speedMs = 0.0)
        assertFalse(pad.isAirborne)
        assertEquals(Tier.CAUTION, tierOf(tgt(sec(1), 0.0, 0.8, geomFt = 8500.0), o = pad))
    }

    // ── protected volume: surface up to 1,500 ft above the drone ─────────
    @Test fun volumeIsSurfaceToCeilingAboveTheDrone() {
        assertEquals(Tier.WARNING, tierOf(tgt(sec(1), 0.0, 0.4, geomFt = 9400.0)))   // 1,400 above
        assertEquals(Tier.NONE, tierOf(tgt(sec(1), 0.0, 0.4, geomFt = 9600.0)))      // 1,600 above
        assertEquals(Tier.WARNING, tierOf(tgt(sec(1), 0.0, 0.4, geomFt = 3000.0)))   // far below: inside
        assertEquals(Tier.WARNING, tierOf(tgt(sec(1), 0.0, 0.4, geomFt = null)))     // unknown: inside
        assertEquals(1500.0, SentryConfig().ceilingAboveFt, 0.0)
    }

    @Test fun ceilingIsASetting() {
        assertEquals(Tier.WARNING, tierOf(tgt(sec(1), 0.0, 0.4, geomFt = 9600.0), SentryConfig(ceilingAboveFt = 2000.0)))
    }

    @Test fun baroCorrectionIsEstimatedGeomIsNot() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val v = e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.8, geomFt = null, baroFt = 7400.0))).targets.single()
        assertTrue(v.altEstimated)
        assertEquals(-300.0, v.dvFt!!, 1e-9)                   // 7400 + 300 - 8000
        val v2 = e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.8, geomFt = 7500.0, baroFt = 7400.0))).targets.single()
        assertFalse(v2.altEstimated)
        assertEquals(-500.0, v2.dvFt!!, 1e-9)
    }

    // ── TRACK ALERT ─────────────────────────────────────────────────────
    @Test fun trackAlertWithin120sAndOneMile() {
        // 4.0 nm north, 0.8 nm east, southbound 120 kt: CPA 0.8 nm in 120 s
        val start = offset(0.0, 4.0, 0.8)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..40) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) })
        val track = ev.first { it.phase == Phase.ESCALATION }
        assertEquals(Tier.TRACK, track.tier)
        assertEquals(Cue.FULL, track.cue); assertTrue(track.popup)
        assertTrue("fires at ~120 s to CPA, got t=${(track.timeMs - sec(0)) / 1000}", track.timeMs <= sec(2))
        assertTrue(ev.none { it.tier == Tier.WARNING })       // 0.8 nm miss: never a warning
    }

    @Test fun trackAlertNeedsTheVolumeAtCpa() {
        val start = offset(0.0, 3.8, 0.8)
        // 1,000 ft above, climbing 1,000 fpm: ~2,900 above at CPA (114 s) -> not in the volume
        val climber = AlertEngine()
        val a = traffic(run(climber, 0..5) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0, 9000.0, 1000.0)) })
        assertTrue(a.none { it.tier == Tier.TRACK })
        val level = AlertEngine()
        val b = traffic(run(level, 0..5) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0, 9000.0, 0.0)) })
        assertTrue(b.any { it.tier == Tier.TRACK })
    }

    @Test fun trackHorizonAndMissAreSettings() {
        val start = offset(0.0, 5.5, 0.8)                        // CPA in 165 s
        val def = AlertEngine()
        assertTrue(traffic(run(def, 0..3) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) }).isEmpty())
        val wide = AlertEngine(SentryConfig(trackSec = 180.0, warningSec = 90.0))
        assertEquals(Tier.TRACK, traffic(run(wide, 0..3) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) }).first().tier)
    }

    // ── corridor widening (a setting, default off) ────────────────────────
    @Test fun corridorWidensWithDistanceOnlyWhenSet() {
        assertEquals(0.0, SentryConfig().corridorDeg, 0.0)
        assertEquals(1.0 + 3.0 * 0.0874887, Prediction.corridorNm(1.0, 3.0, 5.0), 1e-6)
        // 3.9 nm out, miss 1.2 nm: outside the plain 1 nm, inside 1 + 3.9 x tan 5 = 1.34 nm
        val start = offset(0.0, 3.7, 1.2)
        val plain = AlertEngine()
        assertTrue(traffic(run(plain, 0..3) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) }).none { it.tier == Tier.TRACK })
        val widened = AlertEngine(SentryConfig(corridorDeg = 5.0))
        assertTrue(traffic(run(widened, 0..3) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) }).any { it.tier == Tier.TRACK })
    }

    // ── WARNING (predicted) ──────────────────────────────────────────────
    @Test fun predictedWarningBeforeTheRings() {
        // 2.8 nm north, 0.3 nm east, southbound 200 kt: CPA 0.3 nm in ~50 s
        val start = offset(0.0, 2.8, 0.3)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..3) { if (it == 0) emptyList() else listOf(mover(it, start, 200.0, 180.0)) })
        val w = ev.first()
        assertEquals(Tier.WARNING, w.tier)
        assertEquals(Phase.ESCALATION, w.phase)
        assertTrue(w.banner!!.line3, w.banner!!.line3.matches(Regex("Closest 1,800 ft in (49|50) s")))
    }

    @Test fun warningHorizonIs60sByDefault() {
        val start = offset(0.0, 3.3, 0.3)                        // CPA ~59 s at 200 kt
        val e = AlertEngine()
        assertEquals(Tier.WARNING, traffic(run(e, 0..2) { if (it == 0) emptyList() else listOf(mover(it, start, 200.0, 180.0)) }).first().tier)
        val far = offset(0.0, 4.0, 0.3)                          // CPA 72 s: a TRACK ALERT only
        val e2 = AlertEngine()
        assertEquals(Tier.TRACK, traffic(run(e2, 0..2) { if (it == 0) emptyList() else listOf(mover(it, far, 200.0, 180.0)) }).first().tier)
    }

    // ── COLLISION RISK ───────────────────────────────────────────────────
    @Test fun collisionRiskOnMissAndVertical() {
        // straight at the drone, co-altitude, 1.5 nm, 120 kt: CPA 0 ft in 45 s
        val start = at(0.0, 1.5)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..2) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) })
        assertEquals(Tier.COLLISION, ev.first().tier)
        assertEquals("‼ COLLISION RISK · N1234", ev.first().banner!!.title)
        // same, 400 ft above (level): not within 300 ft -> WARNING
        val e2 = AlertEngine()
        assertEquals(Tier.WARNING, traffic(run(e2, 0..2) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0, 8400.0)) }).first().tier)
        // miss 0.1 nm (608 ft) > 500 ft: WARNING
        val e3 = AlertEngine()
        assertEquals(Tier.WARNING, traffic(run(e3, 0..2) { if (it == 0) emptyList() else listOf(mover(it, offset(0.0, 1.5, 0.1), 120.0, 180.0)) }).first().tier)
    }

    @Test fun collisionRiskCrossingTest() {
        // miss 0.3 nm (not within 500 ft), 160 ft below, climbing 500 fpm: crosses the drone's altitude after 19 s,
        // while inside 0.5 nm (CPA at 30 s, 0.5 nm window ~ 18-42 s) -> COLLISION RISK
        val start = offset(0.0, 1.0, 0.3)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..2) { if (it == 0) emptyList() else listOf(mover(it - 1, start, 120.0, 180.0, 7840.0, 500.0, tOff = 1)) })
        val c = ev.first()
        assertEquals(Tier.COLLISION, c.tier)
        assertTrue(c.banner!!.line3, c.banner!!.line3.matches(Regex("(200|100) below, climbing through your altitude · 1[0-9] s")))
        // he is east of the drone flying south: the drone is on his right (west) -> move further west; climbing -> ↑
        assertEquals("Clear: move W ↑", c.banner!!.line4)
        // level at 160 below: no crossing -> WARNING (predicted within 0.5 nm)
        val e2 = AlertEngine()
        assertEquals(Tier.WARNING, traffic(run(e2, 0..2) { if (it == 0) emptyList() else listOf(mover(it - 1, start, 120.0, 180.0, 7840.0, 0.0, tOff = 1)) }).first().tier)
        // below and DESCENDING: moving away vertically -> WARNING
        val e3 = AlertEngine()
        assertEquals(Tier.WARNING, traffic(run(e3, 0..2) { if (it == 0) emptyList() else listOf(mover(it - 1, start, 120.0, 180.0, 7840.0, -500.0, tOff = 1)) }).first().tier)
    }

    @Test fun crossingTestIncludesTheDronesOwnVerticalRate() {
        // aircraft level 160 ft ABOVE; the drone climbs 500 fpm through it while it is inside 0.5 nm
        val start = offset(0.0, 1.0, 0.3)
        val e = AlertEngine()
        val drone = { i: Int -> own(sec(i), alt = 8000.0 + 500.0 * i / 60.0) }
        val ev = traffic(run(e, 0..3, o = drone) { if (it == 0) emptyList() else listOf(mover(it - 1, start, 120.0, 180.0, 8160.0 + 500.0 / 60.0, 0.0, tOff = 1)) })
        assertEquals(Tier.COLLISION, ev.first { it.phase == Phase.ESCALATION }.tier)
    }

    @Test fun crossingMathIsPure() {
        // head-on 1 nm at 100 m/s relative; radius 0.5 nm -> inside from ~9.3 s to 60 s horizon clip
        val rel = EN(0.0, Units.nmToM(1.0)); val v = EN(0.0, -100.0)
        val w = Prediction.insideWindow(rel, v, Units.nmToM(0.5), 60.0)!!
        assertEquals(9.26, w.first, 0.01)
        assertEquals(27.78, w.second, 0.01)
        assertEquals(15.0, Prediction.crossingTime(rel, v, -150.0, 600.0, Units.nmToM(0.5), 60.0)!!, 1e-9)
        assertNull(Prediction.crossingTime(rel, v, -500.0, 600.0, Units.nmToM(0.5), 60.0))   // crosses at 50 s: outside
        assertNull(Prediction.crossingTime(rel, v, 150.0, 600.0, Units.nmToM(0.5), 60.0))    // above and climbing: apart
    }

    // ── time to close, drone velocity ────────────────────────────────────
    @Test fun predictionMath() {
        // 2 nm north, closing 120 kt head-on, 500 ft above descending 1,000 fpm
        val r = Prediction.predict(EN(0.0, Units.nmToM(2.0)), EN(0.0, -Units.ktToMs(120.0)), 500.0, -1000.0, 0.5, 60.0)
        assertEquals(60.0, r.tCpaSec, 0.01)
        assertEquals(0.0, r.missNm, 1e-6)
        assertEquals(-500.0, r.dvAtCpaFt!!, 0.1)
        assertTrue(r.converging)
        val apart = Prediction.predict(EN(0.0, Units.nmToM(2.0)), EN(0.0, Units.ktToMs(120.0)), 0.0, 0.0, 0.5, 60.0)
        assertFalse(apart.converging)
        assertEquals(2.0, apart.missNm, 1e-6)
    }

    @Test fun droneVelocityFromItsLastTwoFixes() {
        // a hovering aircraft 1.4 nm north; the drone flies north at 60 kt -> converging, CPA 0 in 84 s -> TRACK then WARNING
        val e = AlertEngine()
        val drone = { i: Int -> own(sec(i), at = Geo.fromEN(O, Geo.velocity(60.0, 0.0) * i.toDouble())) }
        val parked = at(0.0, 1.4)
        val ev = traffic(run(e, 0..40, o = drone) { listOf(tgt(sec(it), 0.0, 0.0, pos = parked)) })
        assertTrue(ev.any { it.tier == Tier.TRACK || it.tier == Tier.WARNING })
        val v = e.s(sec(41), listOf(tgt(sec(41), 0.0, 0.0, pos = parked)), o = drone(41)).targets.single()
        assertEquals(Trend.CONVERGING, v.trend)
    }

    @Test fun droneSlowerThanOneKnotIsStationary() {
        // GPS jitter of 0.5 kt must not make a parked aircraft "converge"
        val e = AlertEngine()
        val drone = { i: Int -> own(sec(i), at = Geo.fromEN(O, Geo.velocity(0.5, 0.0) * i.toDouble())) }
        val parked = at(0.0, 2.0)
        run(e, 0..5, o = drone) { listOf(tgt(sec(it), 0.0, 0.0, pos = parked)) }
        val v = e.s(sec(6), listOf(tgt(sec(6), 0.0, 0.0, pos = parked)), o = drone(6)).targets.single()
        assertFalse(v.prediction!!.converging)
    }

    // ── holds, hysteresis, "no longer a factor" ─────────────────────────────
    @Test fun trackAlertCancelsAsNoLongerAFactorAfter5s() {
        // TRACK ALERT, then he turns 90 degrees away (still converging slowly): prediction leaves the corridor
        val start = offset(0.0, 3.5, 0.6)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..40) { i ->
            if (i == 0) emptyList()
            else if (i <= 10) listOf(mover(i, start, 120.0, 180.0))
            else listOf(mover(i - 10, Geo.fromEN(start, Geo.velocity(120.0, 180.0) * 10.0), 120.0, 250.0, tOff = 10))
        })
        val nlf = ev.first { it.kind == EventKind.NO_LONGER_FACTOR }
        assertEquals(Cue.NONE, nlf.cue)
        assertEquals("No longer a factor", nlf.banner!!.line3)
        assertTrue("held >= 5 s after the turn", nlf.timeMs - sec(11) >= 5000)
    }

    @Test fun clearOnceWithRingHysteresis() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 2.9)))             // advisory
        assertTrue(traffic(e.s(sec(2), listOf(tgt(sec(2), 0.0, 3.1, gs = 150.0, trk = 0.0))).events).none { it.kind == EventKind.CLEAR })
        val c = e.s(sec(3), listOf(tgt(sec(3), 0.0, 3.3, gs = 150.0, trk = 0.0))).events.single { it.kind == EventKind.CLEAR }
        assertEquals("○ CLEAR · N1234", c.banner!!.title)
        assertEquals(Cue.NONE, c.cue)
        assertTrue(traffic(e.s(sec(4), listOf(tgt(sec(4), 0.0, 3.4, gs = 150.0, trk = 0.0))).events).isEmpty())
    }

    @Test fun volumeHysteresis200ft() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        assertEquals(Tier.CAUTION, e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.8, geomFt = 9400.0))).targets.single().tier)
        // 1,650 above: outside the 1,500 ceiling but inside +200 hysteresis once alerting
        assertEquals(Tier.CAUTION, e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.8, geomFt = 9650.0))).targets.single().tier)
        assertEquals(Tier.NONE, e.s(sec(3), listOf(tgt(sec(3), 0.0, 0.8, geomFt = 9750.0))).targets.single().tier)
        val clear = e.s(sec(4), listOf(tgt(sec(4), 0.0, 0.8, geomFt = 9750.0))).events
        assertTrue(clear.isEmpty())                          // said at sec 3, once
    }

    // ── zones (fleet simulation: scoped to the drone) ─────────────────────
    private val box = Zone("tfr:6/9999", "6/9999", ZoneKind.TFR, listOf(Polygon(listOf(
        at(315.0, 2.0), at(45.0, 2.0), at(135.0, 2.0), at(225.0, 2.0)))),
        floor = AltLimit.SURFACE, ceiling = AltLimit(8500.0, AltRef.MSL))

    /** A 2 nm square whose near edge is [edgeNm] east of the drone. */
    private fun boxEast(edgeNm: Double) = Zone("tfr:far", "7/1111", ZoneKind.TFR, listOf(Polygon(listOf(
        offset(90.0, edgeNm, -1.0), offset(90.0, edgeNm + 2.0, -1.0), offset(90.0, edgeNm + 2.0, 1.0), offset(90.0, edgeNm, 1.0)))),
        floor = AltLimit.SURFACE, ceiling = AltLimit(18000.0, AltRef.MSL))

    private fun entriesCrossing(z: Zone, cfg: SentryConfig = SentryConfig(), alt: Double = 7000.0, edgeNm: Double): List<AlertEvent> {
        val e = AlertEngine(cfg)
        // westbound across the zone's east edge... start 0.3 nm outside its far (east) side, 150 kt west
        val start = at(90.0, edgeNm + 2.3)
        return run(e, 0..20, zones = listOf(z)) { if (it == 0) emptyList() else listOf(mover(it, start, 150.0, 270.0, alt)) }
            .filter { it.kind == EventKind.TFR_ENTRY }
    }

    @Test fun zoneEntryOnlyForZonesAtTheDrone() {
        assertEquals(2.0, SentryConfig().zoneAlertNm, 0.0)
        // containing the drone: alerted
        val e = AlertEngine()
        val inside = run(e, 0..40, zones = listOf(box)) { if (it == 0) emptyList() else listOf(mover(it, at(90.0, 1.8), 150.0, 270.0, 7000.0)) }
        assertEquals(1, inside.count { it.kind == EventKind.TFR_ENTRY })
        // edge 1.5 nm away: alerted; edge 6 nm away (the Disneyland case): never
        assertEquals(1, entriesCrossing(boxEast(1.5), edgeNm = 1.5).size)
        assertEquals(0, entriesCrossing(boxEast(6.0), edgeNm = 6.0).size)
        // ... unless the setting is widened
        assertEquals(1, entriesCrossing(boxEast(6.0), SentryConfig(zoneAlertNm = 7.0, tfrRelevanceNm = 10.0), edgeNm = 6.0).size)
    }

    @Test fun zoneEntryNeedsTheAircraftInsideTheVolume() {
        // 3,000 ft above the drone (below the TFR top): inside the TFR, outside the volume -> no alert
        assertEquals(0, entriesCrossing(boxEast(1.0), alt = 11000.0, edgeNm = 1.0).size)
        assertEquals(1, entriesCrossing(boxEast(1.0), alt = 9000.0, edgeNm = 1.0).size)
    }

    @Test fun zoneEntryIsOneAlertWithSound() {
        val z = entriesCrossing(boxEast(1.5), edgeNm = 1.5).single()
        assertEquals(Cue.FULL, z.cue); assertTrue(z.popup)
        assertTrue(z.banner!!.title, z.banner!!.title.startsWith("▣ ENTERING TFR 7/1111 · N1234"))
    }

    @Test fun farTfrIsNotWatched() {
        val far = box.copy(id = "far", polygons = listOf(Polygon(listOf(at(0.0, 20.0), at(10.0, 20.0), at(5.0, 25.0)))))
        val e = AlertEngine()
        assertEquals(listOf("TFR 6/9999"), e.s(sec(0), emptyList(), listOf(box, far)).watchedZones)
    }

    @Test fun aglCeilingUsesGroundUnderDrone() {
        val z = box.copy(ceiling = AltLimit(1000.0, AltRef.AGL))     // ground 7,600 -> top 8,600 MSL
        val e = AlertEngine()
        e.s(sec(0), emptyList(), listOf(z))
        assertTrue(e.s(sec(1), listOf(tgt(sec(1), 90.0, 1.0, geomFt = 8700.0)), listOf(z)).targets.single().zones.isEmpty())
        assertEquals(listOf("TFR 6/9999"), e.s(sec(2), listOf(tgt(sec(2), 90.0, 1.0, geomFt = 8500.0, hex = "x2")), listOf(z)).targets.first { it.hex == "x2" }.zones)
    }

    // ── feed hygiene (unchanged rules) ────────────────────────────────────
    @Test fun staleTargetIsNeverAlertedOrShown() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val r = e.s(sec(40), listOf(tgt(sec(40), 0.0, 0.2, posT = sec(9))))
        assertTrue(r.events.none { it.hex != null }); assertTrue(r.targets.isEmpty())
    }

    @Test fun groundSlowIgnoredGroundFastIsAirborneAltitudeUnknown() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val slow = e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.3, geomFt = null, ground = true, gs = 12.0)))
        assertTrue(slow.events.isEmpty()); assertTrue(slow.targets.isEmpty())
        val fast = e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.3, geomFt = null, ground = true, gs = 160.0, trk = 90.0)))
        val ev = fast.events.single()
        assertEquals(Tier.WARNING, ev.tier)
        assertTrue(ev.banner!!.line2, ev.banner!!.line2.endsWith("alt unknown"))
        assertTrue(fast.targets.single().groundModeAirborne)
    }

    @Test fun trackLostCancelsTheBanner() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.4)))
        val ev = e.s(sec(2), emptyList()).events.single()
        assertEquals(EventKind.TRACK_LOST, ev.kind)
        assertEquals("N1234 track lost.", ev.text)
    }

    @Test fun landingTargetSaysOnTheGround() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 270.0, 1.5, gs = 130.0, trk = 270.0)))
        val ev = e.s(sec(2), listOf(tgt(sec(2), 270.0, 1.6, geomFt = null, ground = true, gs = 20.0))).events
        assertEquals("N1234 on the ground.", ev.single().text)
        assertTrue(e.s(sec(3), emptyList()).events.isEmpty())
    }

    @Test fun deadReckonsUpToTenSeconds() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val r = e.s(sec(20), listOf(tgt(sec(20), 0.0, 3.0, gs = 180.0, trk = 180.0, posT = sec(0))))
        assertEquals(2.5, r.targets.single().distNm, 0.01)
    }

    @Test fun velocityDerivedWhenTrackMissing() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 2.0).copy(gsKt = 160.0, trackDeg = null)))
        val r = e.s(sec(4), listOf(tgt(sec(4), 0.0, 1.8667).copy(gsKt = 160.0, trackDeg = null)))
        assertEquals(Trend.CONVERGING, r.targets.single().trend)
        assertTrue(r.targets.single().tCpaSec!! in 40.0..44.0)
    }

    @Test fun ownshipLostAndRegained() {
        val e = AlertEngine()
        assertEquals("Watching DEMO-1", e.s(sec(0), emptyList()).events.single().text)
        assertTrue(e.s(sec(10), emptyList(), o = own(sec(10), posT = sec(0))).events.isEmpty())
        assertEquals("Drone position lost", e.s(sec(16), emptyList(), o = own(sec(16), posT = sec(0))).events.single().text)
        assertTrue(e.s(sec(17), emptyList(), o = null).events.isEmpty())
        assertEquals("Drone position regained", e.s(sec(18), emptyList()).events.single().text)
    }

    @Test fun ownshipLostReminderIsScreenOnly() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(20), emptyList(), o = null)
        val r = e.s(sec(140), emptyList(), o = null).events.single()
        assertEquals("Drone position still lost", r.text)
        assertTrue(r.repeat)
        assertNull(OutputPlanner.levelFor(r))
    }

    @Test fun switchingToAnotherDroneResetsTracksSilently() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.8)))
        val other = Ownship("D2", "DEMO-2", 36.6, -118.9, 3700.0, 300.0, sec(2), OwnshipSource.FLEET_DRONESENSE)
        assertEquals(listOf("Now watching DEMO-2"), e.s(sec(2), emptyList(), o = other).events.map { it.text })
    }

    @Test fun trafficStaleOnceAndRestored() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        assertTrue(e.s(sec(1), emptyList(), trafficAge = 30.0).events.isEmpty())
        assertEquals("Traffic data stale", e.s(sec(2), emptyList(), trafficAge = 31.0).events.single().text)
        assertEquals("Traffic data restored", e.s(sec(4), emptyList(), trafficAge = 1.0).events.single().text)
    }

    @Test fun closenessScoreIsLoggedWorstPerPass() {
        assertEquals(1.0, Closeness.score(2000.0, 0.0), 1e-9)
        assertEquals(1.0, Closeness.score(0.0, 500.0), 1e-9)
        assertEquals(Math.sqrt(2.0), Closeness.score(2000.0, -500.0), 1e-9)
        assertEquals(0.5, Closeness.score(1000.0, null), 1e-9)
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        val a = e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.4, geomFt = 8200.0))).events.single()
        assertNotNull(a.closenessS)
        assertEquals(Closeness.score(0.4 * Units.FT_PER_NM, 200.0), a.closenessS!!, 0.01)
    }
}
