package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Controller-centred protection cylinders: geometry, floor/ceiling, and the engine's controller mode. */
class CylinderTest {
    private val C = LatLon(39.43, -120.03)
    private val ELEV = 5100.0
    private val T0 = 1_790_000_000_000L
    private fun sec(n: Int) = T0 + n * 1000L

    private fun at(bearing: Double, nm: Double): LatLon {
        val b = Math.toRadians(bearing); val d = Units.nmToM(nm)
        return Geo.fromEN(C, EN(d * sin(b), d * cos(b)))
    }

    private fun controller(t: Long, elev: Double? = ELEV) =
        Ownship("controller", "this controller", C.lat, C.lon, elev, elev?.let { 0.0 }, t, OwnshipSource.CONTROLLER)

    private fun tgt(t: Long, bearing: Double, nm: Double, geomFt: Double?, gs: Double = 0.0, trk: Double = 0.0) =
        at(bearing, nm).let { p -> Target("abc123", "N388KM", lat = p.lat, lon = p.lon, altGeomFt = geomFt, gsKt = gs, trackDeg = trk, posTimeMs = t) }

    private val ops = Cylinder("ops", "ops area", 1.0, 0.0, 1500.0)
    private val adv = Cylinder("adv", "advisory area", 3.0, 0.0, 3000.0)

    private fun engine() = AlertEngine(externalSelection = true)

    /** One step with a single stationary target; returns (severity, events). */
    private fun probe(cyl: List<Cylinder>, bearing: Double, nm: Double, altMsl: Double?, elev: Double? = ELEV): Pair<Severity, List<AlertEvent>> {
        val e = engine()
        e.step(sec(0), controller(sec(0), elev), emptyList(), cyl.map { it.toZone(C) }, 0.0)
        val r = e.step(sec(1), controller(sec(1), elev), listOf(tgt(sec(1), bearing, nm, altMsl)), cyl.map { it.toZone(C) }, 0.0)
        return (r.targets.firstOrNull()?.severity ?: Severity.NONE) to r.events
    }

    @Test fun insideOutsideHorizontal() {
        assertEquals(Severity.CAUTION, probe(listOf(ops), 90.0, 0.95, ELEV + 500).first)
        assertEquals(Severity.NONE, probe(listOf(ops), 90.0, 1.05, ELEV + 500).first)
        // exact circle, not the 48-gon: just inside on a between-vertex bearing
        assertEquals(Severity.CAUTION, probe(listOf(ops), 3.75, 0.999, ELEV + 500).first)
    }

    @Test fun floorAndCeilingAboveController() {
        assertEquals(Severity.CAUTION, probe(listOf(ops), 0.0, 0.5, ELEV + 1499).first)
        assertEquals(Severity.NONE, probe(listOf(ops), 0.0, 0.5, ELEV + 1501).first)       // over the top
        val raised = ops.copy(floorFt = 400.0)
        assertEquals(Severity.NONE, probe(listOf(raised), 0.0, 0.5, ELEV + 300).first)    // under the floor
        assertEquals(Severity.CAUTION, probe(listOf(raised), 0.0, 0.5, ELEV + 450).first)
    }

    @Test fun mslLimits() {
        val msl = Cylinder("m", "msl box", 1.0, 6000.0, 7000.0, CylinderAltRef.MSL)
        assertEquals(Severity.NONE, probe(listOf(msl), 0.0, 0.5, 5900.0).first)
        assertEquals(Severity.CAUTION, probe(listOf(msl), 0.0, 0.5, 6500.0).first)
        assertEquals(Severity.NONE, probe(listOf(msl), 0.0, 0.5, 7100.0).first)
        // MSL limits don't depend on the controller's elevation
        assertEquals(Severity.CAUTION, probe(listOf(msl), 0.0, 0.5, 6500.0, elev = null).first)
    }

    @Test fun unknownAltitudeCountsAsInside() {
        assertEquals(Severity.CAUTION, probe(listOf(ops), 0.0, 0.5, null).first)
    }

    @Test fun unknownControllerElevationFailsWide() {
        // AGL ceiling can't be resolved without the controller's elevation: never silently narrow the zone
        assertEquals(Severity.CAUTION, probe(listOf(ops), 0.0, 0.5, 9000.0, elev = null).first)
    }

    @Test fun ringsDoNotApplyInControllerMode() {
        // 0.3 nm, co-altitude, but no cylinder covers it: not a ring alert
        assertEquals(Severity.NONE, probe(emptyList(), 0.0, 0.3, ELEV + 200).first)
    }

    @Test fun entryCalloutWording() {
        val e = engine()
        val zones = listOf(ops, adv).map { it.toZone(C) }
        val out = ArrayList<AlertEvent>()
        // inbound from the southwest at 120 kt, 400 ft above the controller, starting outside both
        for (n in 0..120) {
            val nm = 3.3 - n * (120.0 / 3600.0)
            if (nm < 0.2) break
            out += e.step(sec(n), controller(sec(n)), listOf(tgt(sec(n), 225.0, nm, ELEV + 400, gs = 120.0, trk = 45.0)), zones, 0.0).events
        }
        val entries = out.filter { it.kind == EventKind.CYLINDER_ENTRY }
        assertEquals(listOf("advisory area", "ops area"), entries.map { it.text.removePrefix("Traffic entering ").substringBefore(",") })
        val opsEntry = entries[1]
        assertTrue(opsEntry.text, opsEntry.text.matches(Regex("Traffic entering ops area, N388KM, southwest, [0-9,]+ feet, 400 above, converging\\.")))
        assertTrue(opsEntry.speech.contains("N 3 8 8 K M"))
        assertTrue(opsEntry.severity >= Severity.CAUTION)
        // predictive: CPA 0 nm inside the warning ring, altitude inside a cylinder -> WARNING before ops entry
        val warn = out.first { it.severity == Severity.WARNING }
        assertTrue(warn.timeMs <= opsEntry.timeMs)
    }

    @Test fun jsonRoundTripAndValidation() {
        val list = listOf(ops, adv.copy(enabled = false), Cylinder("m", "msl", 0.25, 100.0, 900.0, CylinderAltRef.MSL))
        assertEquals(list, Cylinder.fromJson(Cylinder.toJson(list)))
        assertTrue(Cylinder.fromJson("not json").isEmpty())
        assertFalse(ops.copy(ceilingFt = 0.0).valid())
        assertFalse(ops.copy(radiusNm = 0.0).valid())
        assertEquals("ops area · 1.0 nm · SFC–1,500 ft above controller", ops.describe())
        assertEquals("m · 1,519 ft · 100–900 ft MSL", Cylinder("m", "m", 0.25, 100.0, 900.0, CylinderAltRef.MSL).describe())
    }
}
