package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ShareLogTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private val now = 1_790_320_000_000L          // 2026-09-25 00:06:40 PDT
    private val dev = ShareLog.Device("0.4.2 (402)", "DJI RC Plus", "Android 10 (API 29)", "1920x1200 px · 400 dpi (density 2.5) · 768x480 dp")

    @Test fun encodeParseRoundTrip() {
        val e = ShareLog.Entry(123L, "ALERT[live] 11:52:57 WARNING\twith tab\nand newline")
        val back = ShareLog.parse(ShareLog.encode(e))!!
        assertEquals(123L, back.timeMs)
        assertEquals("ALERT[live] 11:52:57 WARNING with tab and newline", back.text)
        assertNull(ShareLog.parse("garbage")); assertNull(ShareLog.parse("x\ty")); assertNull(ShareLog.parse("\tno time"))
    }

    @Test fun headerWindowOrderAndTimes() {
        val entries = listOf(
            ShareLog.Entry(now - 60_000, "ARMED (live)"),
            ShareLog.Entry(now - 25 * 3_600_000L, "too old"),
            ShareLog.Entry(now - 120_000, "Service created"),
            ShareLog.Entry(now + 5_000, "future"),
        )
        val out = ShareLog.format(now, dev, "1581DEMO0000001", entries, emptyMap(), la)
        val lines = out.lines()
        assertEquals("Flight Deck Sentry log", lines[0])
        assertEquals("Generated 2026-09-25 00:06:40 PDT · last 24 h · 2 lines", lines[1])
        assertEquals("App 0.4.2 (402) · DJI RC Plus · Android 10 (API 29)", lines[2])
        assertEquals("Display 1920x1200 px · 400 dpi (density 2.5) · 768x480 dp", lines[3])
        assertEquals("Bound serial: 1581DEMO0000001", lines[4])
        val body = lines.dropWhile { !it.startsWith("----") }.drop(1).filter { it.isNotEmpty() }
        assertEquals(listOf("2026-09-25 00:04:40  Service created", "2026-09-25 00:05:40  ARMED (live)"), body)
        assertFalse(out.contains("too old")); assertFalse(out.contains("future"))
    }

    @Test fun noBoundSerialAndEmpty() {
        val out = ShareLog.format(now, dev, null, emptyList(), emptyMap(), la)
        assertTrue(out.contains("Bound serial: none (controller only)"))
        assertTrue(out.contains("(no log lines in this period)"))
    }

    @Test fun redactsCallsignsTokenHostsAddresses() {
        val red = mapOf("DEMO-1 Pilot" to "<drone>", "DEMO-1" to "<drone>", "s3cr3t-token-abc" to "<token>", "ab" to "<x>")
        val raw = "Watching DEMO-1 Pilot, this controller's aircraft. token=s3cr3t-token-abc via https://worker.example.dev/api/live/adsb " +
            "station http://192.168.4.20:8080/data · beacon from 10.0.0.7 · mail me@example.com · N388KM Cirrus WARNING · demo-1 again"
        val s = ShareLog.scrub(raw, red)
        assertEquals("Watching <drone callsign>, this controller's aircraft. token=<token> via https://<host>/api/live/adsb " +
            "station http://<host>/data · beacon from <ip> · mail <email> · N388KM Cirrus WARNING · <drone> again", s)
        // the bound serial and crewed-aircraft ids are kept
        assertEquals("Selection: bound to 1581F7K3C251F00C9B34", ShareLog.scrub("Selection: bound to 1581F7K3C251F00C9B34", red))
    }

    @Test fun alertsActionsAndSummariesPassThrough() {
        val entries = listOf(
            ShareLog.Entry(now - 3000, "ALERT[live] 22:26:37 WARNING TRAFFIC/ESCALATION sound=warning/full banner=POPUP: ⚠ WARNING · N388KM · SW 1.9 mi"),
            ShareLog.Entry(now - 2000, "ACTION Got it: N388KM muted 60 s"),
            ShareLog.Entry(now - 1000, "RESOURCES armed 5 min · CPU 5-min 0.33 % (1.3 % of one core)"),
        )
        val out = ShareLog.format(now, dev, null, entries, emptyMap(), la)
        entries.forEach { assertTrue(it.text, out.contains(it.text)) }
    }

    @Test fun dronePhrasesAreRedactedWithoutHistory() {
        val none = emptyMap<String, String>()
        assertEquals("ALERT: Watching <drone callsign>, this controller's aircraft.",
            ShareLog.scrub("ALERT: Watching DEMO-1 Pilot, this controller's aircraft.", none))
        assertEquals("Now watching <drone callsign>", ShareLog.scrub("Now watching Engine 7 Smith", none))
        assertEquals("Drone position regained, watching <drone callsign>", ShareLog.scrub("Drone position regained, watching UAS 12 Jones", none))
        assertEquals("Pre-flight OK   Bound aircraft: 1581F7K3C251F00C9B34 · <drone callsign> · airborne",
            ShareLog.scrub("Pre-flight OK   Bound aircraft: 1581F7K3C251F00C9B34 · DEMO-1 Pilot · airborne", none))
        assertEquals("Watchdog: restarting poller fleet", ShareLog.scrub("Watchdog: restarting poller fleet", none))
    }
}
