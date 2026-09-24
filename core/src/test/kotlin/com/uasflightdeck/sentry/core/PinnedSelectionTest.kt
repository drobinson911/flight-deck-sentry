package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "This controller's aircraft": the pinned airframe serial beats every other selection rule. */
class PinnedSelectionTest {
    private val T0 = 1_790_000_000_000L
    private fun sec(n: Int) = T0 + n * 1000L
    private val ctl = ControllerFix(39.43, -120.03, 5100.0, 4.0, T0)
    private val PIN = "1581F7K3C251F00C9B34"

    private fun drone(id: String, cs: String, posT: Long, serial: String? = null, aglFt: Double? = 300.0, speed: Double? = 3.0) =
        Ownship(id, cs, 39.4, -120.0, 8000.0, aglFt, posT, OwnshipSource.FLEET_DRONESENSE, callsign = cs, serial = serial, speedMs = speed)

    private fun sel(pinned: String = PIN, pattern: String = "DEMO-# Pilot", serials: String = "", force: Boolean = false) =
        DroneSelector(DroneSelector.SelectorConfig(pattern = pattern, serials = SerialList.parse(serials), pinnedSerial = pinned, forceController = force))

    private fun DroneSelector.at(n: Int, vararg d: Ownship) = step(sec(n), d.toList(), ctl.copy(timeMs = sec(n)))

    // ── one-tick decisions, table-driven ─────────────────────────────────────
    private data class Case(
        val name: String,
        val pinned: String,
        val pattern: String,
        val serials: String,
        val drones: List<Ownship>,
        val mode: SelectionMode,
        val watchedId: String?,
        val events: List<String>,
    )

    private val mine = drone("m", "Spare 1", sec(0), serial = PIN)
    private val mineOnPad = drone("m", "Spare 1", sec(0), serial = PIN, aglFt = 0.0, speed = 0.0)
    private val demo1 = drone("u", "DEMO-1 Pilot", sec(0), serial = "OTHER1")
    private val bySerial = drone("s", "Engine 5", sec(0), serial = "ALLOW1")

    private val table = listOf(
        Case("pinned present, nothing else", PIN, "", "", listOf(mine),
            SelectionMode.PINNED, "m", listOf("Watching Spare 1, this controller's aircraft.")),
        Case("pinned present, pattern matches the SAME drone: no conflict", PIN, "Spare #", "", listOf(mine),
            SelectionMode.PINNED, "m", listOf("Watching Spare 1, this controller's aircraft.")),
        Case("pinned absent: falls back to the pattern", PIN, "DEMO-# Pilot", "", listOf(demo1),
            SelectionMode.CALLSIGN, "u", listOf("Watching DEMO-1 Pilot.")),
        Case("pinned absent: falls back to the serial allowlist", PIN, "", "ALLOW1", listOf(bySerial),
            SelectionMode.SERIAL, "s", listOf("Watching Engine 5 by serial.")),
        Case("pinned absent, nothing matches: controller (speech held in start-up grace)", PIN, "", "", listOf(demo1),
            SelectionMode.CONTROLLER, null, emptyList()),
        Case("conflict: pattern matches a different drone, pin wins", PIN, "DEMO-# Pilot", "", listOf(demo1, mine),
            SelectionMode.PINNED, "m", listOf("Watching Spare 1, this controller's aircraft.", "Pinned aircraft wins over DEMO-1 Pilot.")),
        Case("conflict with the serial allowlist, pin wins", PIN, "", "ALLOW1", listOf(bySerial, mine),
            SelectionMode.PINNED, "m", listOf("Watching Spare 1, this controller's aircraft.", "Pinned aircraft wins over Engine 5.")),
        Case("pinned ON THE PAD beats an airborne pattern match", PIN, "DEMO-# Pilot", "", listOf(demo1, mineOnPad),
            SelectionMode.PINNED, "m", listOf("Watching Spare 1, this controller's aircraft.", "Pinned aircraft wins over DEMO-1 Pilot.")),
        Case("pinned serial compared trimmed + case-insensitive", "  1581f7k3c251f00c9b34 ", "", "", listOf(mine),
            SelectionMode.PINNED, "m", listOf("Watching Spare 1, this controller's aircraft.")),
        Case("no pin: v0.2 behaviour unchanged", "", "DEMO-# Pilot", "", listOf(demo1, mine),
            SelectionMode.CALLSIGN, "u", listOf("Watching DEMO-1 Pilot.")),
    )

    @Test fun decisionTable() {
        for (c in table) {
            val s = sel(pinned = c.pinned, pattern = c.pattern, serials = c.serials)
            val r = s.step(sec(0), c.drones, ctl)
            assertEquals(c.name, c.mode, r.mode)
            assertEquals(c.name, c.watchedId, r.drone?.id)
            assertEquals(c.name, c.events, r.events.map { it.text })
        }
    }

    @Test fun conflictIsSpokenOnceAndSpeechIsShort() {
        val s = sel()
        val r0 = s.at(0, demo1, mine)
        assertEquals("Pinned aircraft wins.", r0.events.last().speech)
        val later = (1..20).flatMap { n -> s.at(n, demo1.copy(posTimeMs = sec(n)), mine.copy(posTimeMs = sec(n))).events }
        assertTrue(later.isEmpty())
        assertTrue(s.at(21, demo1.copy(posTimeMs = sec(21)), mine.copy(posTimeMs = sec(21))).note.contains("Pinned aircraft wins over DEMO-1 Pilot"))
    }

    @Test fun pinnedAppearsLaterWhileWatchingPatternDrone() {
        val s = sel()
        assertEquals(SelectionMode.CALLSIGN, s.at(0, demo1).mode)
        assertTrue(s.at(1, demo1.copy(posTimeMs = sec(1))).note.contains("Pinned $PIN not in the feed; still looking"))
        val r = s.at(2, demo1.copy(posTimeMs = sec(2)), mine.copy(posTimeMs = sec(2)))
        assertEquals(SelectionMode.PINNED, r.mode)
        assertEquals("m", r.drone!!.id)
        assertEquals(listOf("Now watching Spare 1, this controller's aircraft.", "Pinned aircraft wins over DEMO-1 Pilot."), r.events.map { it.text })
    }

    @Test fun pinnedAppearsLaterAfterControllerWasAnnounced() {
        val s = sel(pattern = "")
        val spoken = (0..9).flatMap { s.at(it).events.map { e -> e.text } }
        assertEquals(listOf("No drone selected. Protecting this controller."), spoken)
        // keeps looking forever: an hour later it shows up
        (10..3600 step 50).forEach { assertEquals(SelectionMode.CONTROLLER, s.at(it).mode) }
        val r = s.at(3601, mine.copy(posTimeMs = sec(3601)))
        assertEquals(SelectionMode.PINNED, r.mode)
        assertEquals("Now watching Spare 1, this controller's aircraft.", r.events.single().text)
    }

    @Test fun pinnedArrivingDuringStartupGraceSaysWatching() {
        val s = sel(pattern = "")
        assertTrue(s.at(0).events.isEmpty())                     // first fleet poll not in yet
        val r = s.at(2, mine.copy(posTimeMs = sec(2)))
        assertEquals("Watching Spare 1, this controller's aircraft.", r.events.single().text)
        assertTrue("held controller line is dropped", (3..10).all { s.at(it, mine.copy(posTimeMs = sec(it))).events.isEmpty() })
    }

    @Test fun padToAirborneIsTheSameAircraftNoChatter() {
        val s = sel()
        s.at(0, demo1, mineOnPad)
        val r = s.at(1, demo1.copy(posTimeMs = sec(1)), mine.copy(posTimeMs = sec(1)))
        assertEquals("m", r.drone!!.id); assertTrue(r.events.isEmpty())
    }

    @Test fun pinnedGoesStaleFallsBackThenReturns() {
        val s = sel()
        s.at(0, mine, demo1)
        // the pinned drone stops reporting; the pattern drone keeps going
        val r1 = s.at(16, mine, demo1.copy(posTimeMs = sec(16)))
        assertEquals(SelectionMode.CALLSIGN, r1.mode)
        assertEquals("Now watching DEMO-1 Pilot.", r1.events.single().text)
        val r2 = s.at(40, mine.copy(posTimeMs = sec(40)), demo1.copy(posTimeMs = sec(40)))
        assertEquals(SelectionMode.PINNED, r2.mode)
        assertEquals(listOf("Now watching Spare 1, this controller's aircraft.", "Pinned aircraft wins over DEMO-1 Pilot."), r2.events.map { it.text })
    }

    @Test fun forceControllerStillWins() {
        val r = sel(force = true).at(0, mine)
        assertEquals(SelectionMode.CONTROLLER, r.mode); assertNull(r.drone)
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
