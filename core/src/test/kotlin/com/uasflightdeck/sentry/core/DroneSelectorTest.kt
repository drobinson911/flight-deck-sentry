package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneSelectorTest {
    private val T0 = 1_790_000_000_000L
    private fun sec(n: Int) = T0 + n * 1000L
    private val ctl = ControllerFix(39.43, -120.03, 5100.0, 4.0, T0)

    private fun drone(id: String, cs: String?, posT: Long, serial: String? = null, aglFt: Double? = 300.0, speed: Double? = 3.0) =
        Ownship(id, cs ?: id, 39.4, -120.0, 8000.0, aglFt, posT, OwnshipSource.FLEET_DRONESENSE, callsign = cs, serial = serial, speedMs = speed)

    private fun sel(pattern: String = "DEMO-# Pilot", serials: String = "", force: Boolean = false) =
        DroneSelector(DroneSelector.SelectorConfig(pattern = pattern, serials = SerialList.parse(serials), forceController = force))

    private fun DroneSelector.at(n: Int, vararg d: Ownship, fix: ControllerFix? = ctl.copy(timeMs = sec(n))) = step(sec(n), d.toList(), fix)

    @Test fun patternSelectsMatchingAirborneDrone() {
        val s = sel()
        val r = s.at(0, drone("a", "DEMO-2 Smith", sec(0)), drone("b", "DEMO-1 Pilot", sec(0)))
        assertEquals(SelectionMode.CALLSIGN, r.mode)
        assertEquals("b", r.ownship!!.id)
        assertEquals(listOf("Watching DEMO-1 Pilot."), r.events.map { it.text })
    }

    @Test fun multipleMatchesPreferFreshestAndSaySo() {
        val s = sel()
        val r = s.at(10, drone("a", "DEMO-1 Pilot", sec(7)), drone("b", "DEMO-32 Pilot", sec(9)))
        assertEquals("b", r.ownship!!.id)
        assertEquals("Multiple matches, watching DEMO-32 Pilot.", r.events.single().text)
        // next tick "a" is fresher, but we stay on "b" (no flapping)
        val r2 = s.at(11, drone("a", "DEMO-1 Pilot", sec(11)), drone("b", "DEMO-32 Pilot", sec(10)))
        assertEquals("b", r2.ownship!!.id); assertTrue(r2.events.isEmpty())
    }

    @Test fun serialAllowlistWhenPatternMatchesNothingAirborne() {
        val s = sel(serials = "1581F5FJ")
        val r = s.at(0, drone("x", "Engine 5 drone", sec(0), serial = "1581f5fj"), drone("y", "Other", sec(0)))
        assertEquals(SelectionMode.SERIAL, r.mode)
        assertEquals("x", r.ownship!!.id)
        assertEquals("Watching Engine 5 drone by serial.", r.events.single().text)
    }

    @Test fun airbornePatternBeatsAirborneSerialBeatsGroundedPattern() {
        val s = sel(serials = "SER1")
        val grounded = drone("g", "DEMO-1 Pilot", sec(0), aglFt = 0.0, speed = 0.0)
        val bySerial = drone("s", "Spare", sec(0), serial = "SER1")
        assertEquals("s", s.at(0, grounded, bySerial).ownship!!.id)
        // a pattern drone gets airborne: switch to it, "Now watching"
        val r = s.at(1, grounded.copy(altAglFt = 50.0, posTimeMs = sec(1)), bySerial.copy(posTimeMs = sec(1)))
        assertEquals("g", r.ownship!!.id); assertEquals(SelectionMode.CALLSIGN, r.mode)
        assertEquals("Now watching DEMO-1 Pilot.", r.events.single().text)
    }

    @Test fun groundedMatchIsStillWatchedWhenNothingElse() {
        val r = sel().at(0, drone("g", "DEMO-1 Pilot", sec(0), aglFt = 0.0, speed = 0.0))
        assertEquals(SelectionMode.CALLSIGN, r.mode)
    }

    @Test fun noMatchFallsBackToControllerAfterStartupGrace() {
        val s = sel()
        val r0 = s.at(0, drone("a", "DEMO-2 Smith", sec(0)))
        assertEquals(SelectionMode.CONTROLLER, r0.mode)
        assertEquals(OwnshipSource.CONTROLLER, r0.ownship!!.source)
        assertTrue("speech held during start-up grace", r0.events.isEmpty())
        val spoken = (1..6).flatMap { s.at(it, drone("a", "DEMO-2 Smith", sec(it))).events }
        assertEquals(listOf("No drone selected. Protecting this controller."), spoken.map { it.text })
        assertEquals(sec(5), spoken.single().timeMs)
    }

    @Test fun matchingDroneAppearingLaterIsAcquired() {
        val s = sel()
        (0..6).forEach { s.at(it) }
        val r = s.at(7, drone("b", "DEMO-1 Pilot", sec(7)))
        assertEquals(SelectionMode.CALLSIGN, r.mode)
        assertEquals("Watching DEMO-1 Pilot.", r.events.single().text)
    }

    @Test fun staleThenFallbackThenReacquire() {
        val s = sel()
        s.at(0, drone("b", "DEMO-1 Pilot", sec(0)))
        // feed stops updating the drone at t=0 (last report stays in the list)
        for (n in 1..29) {
            val r = s.at(n, drone("b", "DEMO-1 Pilot", sec(0)))
            assertEquals("t=$n still on the drone", SelectionMode.CALLSIGN, r.mode)
            assertEquals("b", r.ownship!!.id)
            assertTrue(r.events.isEmpty())
        }
        val fb = s.at(31, drone("b", "DEMO-1 Pilot", sec(0)))
        assertEquals(SelectionMode.CONTROLLER, fb.mode)
        assertEquals("No drone position for 30 seconds. Protecting this controller.", fb.events.single().text)
        assertEquals(OwnshipSource.CONTROLLER, fb.ownship!!.source)
        // it comes back
        val back = s.at(40, drone("b", "DEMO-1 Pilot", sec(40)))
        assertEquals(SelectionMode.CALLSIGN, back.mode)
        assertEquals("Watching DEMO-1 Pilot.", back.events.single().text)
    }

    @Test fun droneDroppedFromFeedEntirelyAlsoFallsBack() {
        val s = sel()
        s.at(0, drone("b", "DEMO-1 Pilot", sec(0)))
        assertEquals(SelectionMode.CALLSIGN, s.at(20).mode)                 // absent, 20 s old: still held
        assertEquals("b", s.at(20).drone!!.id)
        assertEquals(SelectionMode.CONTROLLER, s.at(31).mode)
    }

    @Test fun forceControllerIgnoresDrones() {
        val s = sel(force = true)
        val r = s.at(0, drone("b", "DEMO-1 Pilot", sec(0)))
        assertEquals(SelectionMode.CONTROLLER, r.mode)
        assertEquals("Protecting this controller.", r.events.single().text)
    }

    @Test fun patternChangeReleasesDrone() {
        val s = sel()
        s.at(0, drone("b", "DEMO-1 Pilot", sec(0)))
        s.config = s.config.copy(pattern = "DEMO## Smith")
        val r = s.at(1, drone("b", "DEMO-1 Pilot", sec(1)))
        assertEquals(SelectionMode.CONTROLLER, r.mode)
        assertEquals("No drone selected. Protecting this controller.", r.events.single().text)
    }

    @Test fun controllerGpsUnavailableIsSpokenOnceAndRegained() {
        val s = sel(pattern = "")
        val ev = ArrayList<AlertEvent>()
        for (n in 0..20) { val r = s.at(n, fix = null); ev += r.events; assertNull(r.ownship); assertEquals(SelectionMode.CONTROLLER, r.mode) }
        assertEquals(listOf("No drone selected. Protecting this controller.", "Controller GPS unavailable. Nothing protected."), ev.map { it.text })
        val r = s.at(21)
        assertNotNull(r.ownship)
        assertEquals("Controller GPS regained.", r.events.single().text)
    }

    @Test fun oldControllerFixIsNotUsed() {
        val s = sel(pattern = "")
        val r = s.at(100, fix = ctl.copy(timeMs = sec(30)))       // 70 s old > 60 s
        assertNull(r.ownship); assertTrue(!r.controllerUsable)
        assertEquals(70.0, r.controllerFixAgeSec!!, 0.01)
    }

    @Test fun knownDronesHistory() {
        val k = KnownDrones()
        assertTrue(k.observe(listOf(drone("a", "DEMO-1 Pilot", 0, serial = "S1")), sec(0)))
        assertTrue(k.observe(listOf(drone("b", "DEMO-2", 0)), sec(5)))
        assertTrue(!k.observe(listOf(drone("a", "DEMO-1 Pilot", 0, serial = "S1")), sec(9)))   // nothing new
        assertEquals(listOf("DEMO-1 Pilot", "DEMO-2"), k.callsigns())
        assertEquals(listOf("S1"), k.serials())
        val back = KnownDrones.fromJson(k.toJson())
        assertEquals(k.entries(), back.entries())
        assertEquals(sec(9), back.entries().first().lastSeenMs)
    }
}
