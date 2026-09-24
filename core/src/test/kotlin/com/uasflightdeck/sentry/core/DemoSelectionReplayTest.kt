package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * The demo data through the FULL live path: DroneSelector (callsign
 * pattern / serial / controller fallback) feeding AlertEngine, 1 s ticks,
 * exactly as SentryService does it.
 */
class DemoSelectionReplayTest {
    private val assets = File(System.getProperty("sentry.assets") ?: "../app/src/main/assets")
    private fun read(name: String) = File(assets, "replay/$name").readText()
    private val sc by lazy { DemoReplayFixture.load(read("demo_drone.json"), read("n388km_merged.json"), read("tfr_demo.json")) }

    private val fmt = SimpleDateFormat("HH:mm:ss").apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }
    private fun hms(ms: Long) = fmt.format(Date(ms))

    private data class Run(val events: List<AlertEvent>, val modes: List<Pair<Long, SelectionMode>>)

    private fun run(pattern: String, cylinders: List<Cylinder>, force: Boolean = false): Run {
        val selector = DroneSelector(DroneSelector.SelectorConfig(pattern = pattern, forceController = force))
        val engine = AlertEngine(externalSelection = true)
        val out = ArrayList<AlertEvent>(); val modes = ArrayList<Pair<Long, SelectionMode>>()
        var t = sc.startMs
        while (t <= sc.endMs) {
            val sel = selector.step(t, sc.dronesAt(t), DemoReplayFixture.launchController(t))
            val zones = sc.zones + if (sel.mode == SelectionMode.CONTROLLER && sel.ownship != null)
                cylinders.filter { it.enabled }.map { it.toZone(sel.ownship!!.pos) } else emptyList()
            out += sel.events
            out += engine.step(t, sel.ownship, sc.trafficAt(t), zones, 0.0).events
            modes += t to sel.mode
            t += 1000
        }
        return Run(out, modes)
    }

    private fun dump(label: String, ev: List<AlertEvent>) {
        println("── $label ──")
        ev.forEach { println("${hms(it.timeMs)}  ${it.severity.label.padEnd(8)} ${it.kind.name.padEnd(16)} ${it.text}   [tts: ${it.speech}]") }
    }

    @Test fun patternSelectsDEMO-1AndStillWarnsAt115257() {
        assertEquals("DEMO-1 Pilot", DemoReplayFixture.DRONE_CALLSIGN)
        val r = run("DEMO-# Pilot", Cylinder.DEFAULTS)
        dump("pattern 'DEMO-# Pilot'", r.events)
        assertTrue("never left callsign mode", r.modes.all { it.second == SelectionMode.CALLSIGN })
        val first = r.events.first()
        assertEquals(EventKind.SELECTION, first.kind)
        assertEquals("Watching DEMO-1 Pilot.", first.text)
        assertEquals(sc.startMs, first.timeMs)

        val warn = r.events.first { it.hex == DemoReplayFixture.HEX && it.severity == Severity.WARNING }
        assertEquals("11:52:57", hms(warn.timeMs))
        assertEquals(EventKind.PREDICTIVE, warn.kind)
        assertTrue(warn.timeMs < DemoReplayFixture.CLOSEST_MS)
        val tfr = r.events.single { it.kind == EventKind.TFR_ENTRY }
        assertTrue(kotlin.math.abs(tfr.timeMs - DemoReplayFixture.TFR_ENTRY_MS) <= 4000)
        assertTrue("no cylinder callouts while a drone is watched", r.events.none { it.kind == EventKind.CYLINDER_ENTRY })
        assertTrue(r.events.none { it.kind == EventKind.OWNSHIP_LOST })
    }

    @Test fun nonMatchingPatternFallsBackToController() {
        val r = run("DEMO## Smith", Cylinder.DEFAULTS)
        assertTrue(r.modes.all { it.second == SelectionMode.CONTROLLER })
        assertEquals("No drone selected. Protecting this controller.", r.events.first { it.kind == EventKind.SELECTION }.text)
    }

    /**
     * No pattern: Sentry protects the controller at DEMO-1's launch point
     * (39.4290, -120.0344, 5,100 ft). N388KM was ~7,500 ft pressure altitude
     * (≈7,800 MSL with the +300 ft estimate), i.e. ~2,700 ft above the
     * controller, so the 1 nm cylinder here is SFC–3,000 ft above controller.
     * Geometry (from the fixture): N388KM is 1.03 nm from the launch point at
     * 11:53:39 and 0.81 nm at 11:53:44, so it physically crosses 1 nm at about
     * 11:53:39.7; its closest approach to the controller is 0.23 nm at 11:54:03.
     */
    @Test fun noPatternOneMileCylinderAroundController() {
        val cyl = listOf(Cylinder("ops", "ops area", 1.0, 0.0, 3000.0))
        val r = run("", cyl)
        dump("no pattern, 1 nm SFC-3,000 ft cylinder at the DEMO-1 launch point", r.events)
        assertTrue(r.modes.all { it.second == SelectionMode.CONTROLLER })
        val sel = r.events.first { it.kind == EventKind.SELECTION }
        assertEquals("No drone selected. Protecting this controller.", sel.text)

        val entry = r.events.firstOrNull { it.kind == EventKind.CYLINDER_ENTRY && it.hex == DemoReplayFixture.HEX }
        assertNotNull("no cylinder-entry callout", entry)
        assertTrue(entry!!.text, entry.text.startsWith("Traffic entering ops area, N388KM, south, "))   // it came up from the south of the launch point
        // the 1 s tick after the true 1 nm crossing (11:53:39.7), dead-reckoned
        assertTrue("entry at ${hms(entry.timeMs)}", entry.timeMs <= DemoReplayFixture.CLOSEST_MS + 1000)
        assertTrue("entry at ${hms(entry.timeMs)}", entry.timeMs >= DemoReplayFixture.CLOSEST_MS - 2000)

        // the predictive rule about the controller warns well before the crossing
        val warn = r.events.first { it.hex == DemoReplayFixture.HEX && it.severity == Severity.WARNING }
        assertEquals(EventKind.PREDICTIVE, warn.kind)
        assertTrue("predictive at ${hms(warn.timeMs)}", warn.timeMs < DemoReplayFixture.CLOSEST_MS - 30_000)
        assertTrue(warn.text.contains("above"))
        assertEquals(1, r.events.count { it.kind == EventKind.CYLINDER_ENTRY })
        val clear = r.events.first { it.kind == EventKind.CLEAR && it.hex == DemoReplayFixture.HEX }
        assertTrue(clear.timeMs > entry.timeMs)
    }

    /** Truthfulness: with the example 1,500 ft ceiling, N388KM passes ~1,200 ft OVER the cylinder, and Sentry stays silent about it. */
    @Test fun aircraftOverTheTopOfTheCylinderIsNotAnEntry() {
        val r = run("", listOf(Cylinder("ops", "ops area", 1.0, 0.0, 1500.0)))
        assertTrue(r.events.none { it.kind == EventKind.CYLINDER_ENTRY })
        assertTrue(r.events.none { it.kind == EventKind.PREDICTIVE })
    }
}
