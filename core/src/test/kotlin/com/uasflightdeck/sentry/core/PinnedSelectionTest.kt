package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.3 selection: this controller's pinned aircraft (by serial), else the controller. Owner:
 * "We can't have the pilot thinking his drone is protected but really it's protecting another."
 * Other drones are never candidates.
 */
class PinnedSelectionTest {
    private val T0 = 1_790_000_000_000L
    private fun sec(n: Int) = T0 + n * 1000L
    private val ctl = ControllerFix(39.43, -120.03, 5100.0, 4.0, T0)
    private val PIN = "1581F7K3C251F00C9B34"

    private fun drone(id: String, cs: String, posT: Long, serial: String? = null, aglFt: Double? = 300.0, speed: Double? = 3.0) =
        Ownship(id, cs, 39.4, -120.0, 8000.0, aglFt, posT, OwnshipSource.FLEET_DRONESENSE, callsign = cs, serial = serial, speedMs = speed)

    private fun sel(pinned: String = PIN) = DroneSelector(DroneSelector.SelectorConfig(pinnedSerial = pinned))
    private fun DroneSelector.at(n: Int, vararg d: Ownship, fix: ControllerFix? = ctl.copy(timeMs = sec(n))) = step(sec(n), d.toList(), fix)
    private fun Ownship.at(n: Int) = copy(posTimeMs = sec(n))

    private val mine = drone("m", "Spare 1", sec(0), serial = PIN)
    private val mineOnPad = drone("m", "Spare 1", sec(0), serial = PIN, aglFt = 0.0, speed = 0.0)
    private val demo1 = drone("u", "DEMO-1 Pilot", sec(0), serial = "OTHER1")      // someone else's aircraft, airborne
    private val demo2 = drone("x", "DEMO-2", sec(0), serial = "OTHER2")

    // ── pinned present ───────────────────────────────────────────────────────
    @Test fun pinnedPresentAirborneIsWatchedBesideOtherDrones() {
        for (others in listOf(emptyList(), listOf(demo1), listOf(demo1, demo2))) {
            val r = sel().step(sec(0), others + mine, ctl)
            assertEquals(SelectionMode.PINNED, r.mode)
            assertEquals("m", r.drone!!.id); assertEquals("m", r.ownship!!.id)
            assertEquals(listOf("Watching Spare 1, this controller's aircraft."), r.events.map { it.text })
            assertEquals(PIN, r.boundSerial); assertFalse(r.waitingForBound)
        }
    }

    @Test fun pinnedPresentOnThePadIsWatched() {
        val r = sel().at(0, demo1, mineOnPad)
        assertEquals(SelectionMode.PINNED, r.mode); assertEquals("m", r.drone!!.id)
    }

    @Test fun serialComparedTrimmedAndCaseInsensitive() {
        val r = sel(pinned = "  1581f7k3c251f00c9b34 ").at(0, mine)
        assertEquals("m", r.drone!!.id); assertEquals("1581f7k3c251f00c9b34", r.boundSerial)
    }

    @Test fun padToAirborneIsTheSameAircraftNoChatter() {
        val s = sel()
        s.at(0, demo1, mineOnPad)
        val r = s.at(1, demo1.at(1), mine.at(1))
        assertEquals("m", r.drone!!.id); assertTrue(r.events.isEmpty())
    }

    // ── pinned absent: controller; other drones are NEVER watched ─────────────
    @Test fun pinnedAbsentProtectsControllerAndNeverWatchesAnotherDrone() {
        val s = sel()
        val spoken = ArrayList<String>()
        for (n in 0..600) {
            val r = s.at(n, demo1.at(n), demo2.at(n))
            assertEquals("tick $n", SelectionMode.CONTROLLER, r.mode)
            assertNull("tick $n: no drone watched", r.drone)
            assertEquals("tick $n: the engine protects the controller", OwnshipSource.CONTROLLER, r.ownship!!.source)
            assertTrue(r.waitingForBound)
            spoken += r.events.map { it.text }
        }
        assertEquals("said once on arm (after the start-up grace), never repeated",
            listOf("Waiting for this controller's aircraft."), spoken)
        assertTrue(s.at(601, demo1.at(601)).note.contains("$PIN not in the feed; waiting for it"))
    }

    @Test fun nothingPinnedProtectsControllerOnly() {
        val s = sel(pinned = "")
        val spoken = (0..9).flatMap { n -> s.at(n, demo1.at(n), mine.at(n)).let { r ->
            assertEquals(SelectionMode.CONTROLLER, r.mode); assertNull(r.drone); assertNull(r.boundSerial); assertFalse(r.waitingForBound)
            r.events.map { it.text } } }
        assertEquals(listOf("No aircraft pinned. Protecting this controller."), spoken)
    }

    // ── pinned appears later: binds at once ──────────────────────────────────
    @Test fun pinnedAppearsLaterAndIsBoundAtOnce() {
        val s = sel()
        (0..9).forEach { s.at(it, demo1.at(it)) }
        (10..3600 step 50).forEach { assertEquals(SelectionMode.CONTROLLER, s.at(it, demo1.at(it)).mode) }   // an hour later
        val r = s.at(3601, demo1.at(3601), mine.at(3601))
        assertEquals(SelectionMode.PINNED, r.mode); assertEquals("m", r.drone!!.id); assertFalse(r.waitingForBound)
        assertEquals(listOf("Watching Spare 1, this controller's aircraft."), r.events.map { it.text })
    }

    @Test fun appearingDuringStartupGraceDropsTheWaitingLine() {
        val s = sel()
        assertTrue(s.at(0).events.isEmpty())                     // first fleet poll not in yet
        val r = s.at(2, mine.at(2))
        assertEquals("Watching Spare 1, this controller's aircraft.", r.events.single().text)
        assertTrue("held waiting line is dropped", (3..10).all { s.at(it, mine.at(it)).events.isEmpty() })
    }

    @Test fun aStaleReportIsNotAcquired() {
        val r = sel().at(20, mine)                               // last report 20 s old: not fresh
        assertEquals(SelectionMode.CONTROLLER, r.mode)
    }

    // ── drops out: held for the lost window, then waiting, then back ─────────
    @Test fun staleThenWaitingThenReacquire() {
        val s = sel()
        s.at(0, mine, demo1)
        for (n in 1..30) {                                       // feed keeps the old report; another drone keeps flying
            val r = s.at(n, mine, demo1.at(n))
            assertEquals("t=$n still on the pinned aircraft", SelectionMode.PINNED, r.mode)
            assertEquals("m", r.ownship!!.id); assertTrue(r.events.isEmpty())
        }
        val fb = s.at(31, mine, demo1.at(31))
        assertEquals(SelectionMode.CONTROLLER, fb.mode); assertNull(fb.drone); assertTrue(fb.waitingForBound)
        assertEquals(listOf("Waiting for this controller's aircraft."), fb.events.map { it.text })
        assertEquals(Severity.CAUTION, fb.events.single().severity)
        assertTrue((32..39).all { s.at(it, demo1.at(it)).events.isEmpty() })
        val back = s.at(40, mine.at(40), demo1.at(40))
        assertEquals(SelectionMode.PINNED, back.mode)
        assertEquals(listOf("Watching Spare 1, this controller's aircraft."), back.events.map { it.text })
    }

    /** Found on the emulator (v0.2): a drone that vanishes from the feed must be held until it is stale, not dropped at once. */
    @Test fun vanishingFromTheFeedIsHeldThenFallsBack() {
        val s = sel()
        s.at(0, mine, demo1)
        for (n in 1..30) assertEquals("t=$n", "m", s.at(n, demo1.at(n)).ownship!!.id)
        assertEquals(SelectionMode.CONTROLLER, s.at(31, demo1.at(31)).mode)
    }

    @Test fun unpinningReleasesTheAircraft() {
        val s = sel()
        s.at(0, mine)
        s.config = s.config.copy(pinnedSerial = "")
        val r = s.at(1, mine.at(1))
        assertEquals(SelectionMode.CONTROLLER, r.mode); assertNull(r.drone)
        assertEquals("No aircraft pinned. Protecting this controller.", r.events.single().text)
    }

    // ── controller GPS ───────────────────────────────────────────────────────
    @Test fun controllerGpsUnavailableIsSpokenOnceAndRegained() {
        val s = sel(pinned = "")
        val ev = ArrayList<AlertEvent>()
        for (n in 0..20) { val r = s.at(n, fix = null); ev += r.events; assertNull(r.ownship) }
        assertEquals(listOf("No aircraft pinned. Protecting this controller.", "Controller GPS unavailable. Nothing protected."), ev.map { it.text })
        val r = s.at(21)
        assertNotNull(r.ownship)
        assertEquals("Controller GPS regained.", r.events.single().text)
    }

    @Test fun oldControllerFixIsNotUsed() {
        val r = sel(pinned = "").at(100, fix = ctl.copy(timeMs = sec(30)))       // 70 s old > 60 s
        assertNull(r.ownship); assertTrue(!r.controllerUsable)
        assertEquals(70.0, r.controllerFixAgeSec!!, 0.01)
    }

    // ── known aircraft (serial autocomplete / "aircraft in feed now") ────────
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

    @Test fun lastKnownCallsignForSerial() {
        val k = KnownDrones()
        k.observe(listOf(drone("a", "DEMO-1 Pilot", 0, serial = PIN)), sec(0))
        k.observe(listOf(drone("b", "DEMO-2", 0, serial = "X")), sec(5))
        k.observe(listOf(drone("a", "DEMO-3 Pilot", 0, serial = PIN.lowercase())), sec(9))
        assertEquals("DEMO-3 Pilot", k.callsignForSerial(" $PIN "))
        assertNull(k.callsignForSerial("nope")); assertNull(k.callsignForSerial(""))
    }
}
