package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalloutQueueTest {
    private fun ev(text: String, sev: Severity, hex: String? = null, nm: Double? = null) =
        AlertEvent(0, EventKind.PROXIMITY, sev, text, hex = hex, distNm = nm)

    @Test fun onePerAircraftNewestWins() {
        val q = CalloutQueue()
        q.offer(ev("old", Severity.CAUTION, "a", 0.9), 0)
        q.offer(ev("new", Severity.CAUTION, "a", 0.8), 1000)
        assertEquals(1, q.size)
        assertEquals("new", q.poll(1000)!!.ev.text)
    }

    @Test fun severityThenCloserFirstThenFifo() {
        val q = CalloutQueue()
        q.offer(ev("far caution", Severity.CAUTION, "a", 0.9), 0)
        q.offer(ev("near caution", Severity.CAUTION, "b", 0.6), 0)
        q.offer(ev("system 1", Severity.INFO), 0)
        q.offer(ev("warning far", Severity.WARNING, "c", 1.9), 0)
        q.offer(ev("system 2", Severity.INFO), 0)
        assertEquals(listOf("warning far", "near caution", "far caution", "system 1", "system 2"),
            generateSequence { q.poll(0) }.map { it.ev.text }.toList())
    }

    @Test fun staleCalloutsAreDroppedNotSpokenLate() {
        val q = CalloutQueue(maxWaitMs = 15_000)
        q.offer(ev("stale", Severity.WARNING, "a"), 0)
        q.offer(ev("fresh", Severity.CAUTION, "b"), 10_000)
        val dropped = ArrayList<String>()
        assertEquals("fresh", q.poll(16_000) { dropped += it.ev.text }!!.ev.text)
        assertEquals(listOf("stale"), dropped)
        assertNull(q.poll(16_000))
    }

    @Test fun clipTimelineIsBackToBackWithPauses() {
        val t = ClipTimeline.build(listOf("warning", ".", "traffic", ",", "l_n")) { mapOf("warning" to 500, "traffic" to 400, "l_n" to 250)[it]!! }
        assertEquals(listOf(0, 500, 650, 1050, 1110), t.map { it.startMs })
        assertEquals(1360, ClipTimeline.totalMs(t))
    }
}
