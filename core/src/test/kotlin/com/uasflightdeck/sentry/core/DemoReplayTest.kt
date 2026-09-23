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
 * Replays the REAL 2026-09-23 data (the exact bytes bundled in the app's
 * assets) through the engine at 1 s ticks, as the service does, and asserts
 * Sentry would have spoken BEFORE N388KM passed DEMO-1.
 */
class DemoReplayTest {
    private val assets = File(System.getProperty("sentry.assets") ?: "../app/src/main/assets")
    private fun read(name: String) = File(assets, "replay/$name").readText()

    private fun run(cloudView: Boolean): List<AlertEvent> {
        val sc = DemoReplayFixture.load(read("demo_drone.json"), read("n388km_merged.json"), read("tfr_demo.json"), cloudView)
        val engine = AlertEngine()
        val out = ArrayList<AlertEvent>()
        var t = sc.startMs
        while (t <= sc.endMs) {
            out += engine.step(t, sc.ownshipAt(t), sc.trafficAt(t), sc.zones, 0.0).events
            t += 1000
        }
        return out
    }

    private val fmt = SimpleDateFormat("HH:mm:ss").apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }
    private fun hms(ms: Long) = fmt.format(Date(ms))
    private fun dump(label: String, ev: List<AlertEvent>) {
        println("── $label ──")
        ev.forEach { println("${hms(it.timeMs)}  ${it.severity.label.padEnd(8)} ${it.kind.name.padEnd(16)} ${it.text}   [tts: ${it.speech}]") }
    }

    @Test
    fun mergedTrack_speaksBeforeThePass() {
        val ev = run(cloudView = false)
        dump("merged (truck Mode S altitude)", ev)
        val closest = DemoReplayFixture.CLOSEST_MS

        val tfr = ev.firstOrNull { it.kind == EventKind.TFR_ENTRY && it.hex == DemoReplayFixture.HEX }
        assertNotNull("no TFR-entry callout", tfr)
        // Entry was 11:53:22.485; dead-reckoning at 1 s ticks should land within a few seconds.
        assertTrue("TFR entry at ${hms(tfr!!.timeMs)} not ~11:53:22",
            kotlin.math.abs(tfr.timeMs - DemoReplayFixture.TFR_ENTRY_MS) <= 4000)
        assertTrue(tfr.text.startsWith("Traffic entering TFR 0/0000, N388KM,"))
        assertTrue(tfr.speech.contains("TFR 0 0000") && tfr.speech.contains("N 3 8 8 K M"))

        val warn = ev.firstOrNull { it.hex == DemoReplayFixture.HEX && it.severity == Severity.WARNING }
        assertNotNull("no WARNING-level callout", warn)
        assertTrue("warning at ${hms(warn!!.timeMs)} is not before 11:53:40", warn.timeMs < closest)

        val clear = ev.firstOrNull { it.kind == EventKind.CLEAR && it.hex == DemoReplayFixture.HEX }
        assertNotNull("no clear callout", clear)
        assertTrue(clear!!.timeMs > closest)
        assertTrue(clear.text.contains("clear"))

        // nothing spoken about the aircraft after "clear"
        assertTrue(ev.none { it.hex == DemoReplayFixture.HEX && it.timeMs > clear.timeMs })
        // exactly one TFR entry for one crossing
        assertEquals(1, ev.count { it.kind == EventKind.TFR_ENTRY })
        // the drone never "lost" in the replay (rows are ~5 s apart)
        assertTrue(ev.none { it.kind == EventKind.OWNSHIP_LOST })
    }

    /**
     * The public feed carried N388KM as alt_baro "ground", no track, at 160 kt.
     * A naive filter drops "ground" traffic — and would have stayed SILENT.
     */
    @Test
    fun publicFeedGroundMode_stillWarnsWithAltitudeUnknown() {
        val ev = run(cloudView = true)
        dump("public-feed view (alt 'ground', no track)", ev)
        val warn = ev.firstOrNull { it.hex == DemoReplayFixture.HEX && it.severity == Severity.WARNING }
        assertNotNull(warn)
        assertTrue(warn!!.timeMs < DemoReplayFixture.CLOSEST_MS)
        assertTrue(warn.text.contains("altitude unknown"))
        val tfr = ev.firstOrNull { it.kind == EventKind.TFR_ENTRY }
        assertNotNull(tfr)
        assertTrue(kotlin.math.abs(tfr!!.timeMs - DemoReplayFixture.TFR_ENTRY_MS) <= 4000)
    }
}
