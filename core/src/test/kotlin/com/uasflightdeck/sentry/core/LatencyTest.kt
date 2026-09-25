package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ring / tier crossing -> first sound < 2 s. Two independent measures on the demo replay:
 *  1. the engine's interpolated crossing time on every escalation (what the app logs as "alert latency");
 *  2. the 1 s engine against a 10 Hz engine on the same data: the 10 Hz one sees each crossing within 0.1 s,
 *     so its escalation time is the reference.
 */
class LatencyTest {
    private val soundStartBudgetMs = 250L      // measured on the emulator: MediaPlayer start after the tick

    @Test fun everyEscalationWithinTwoSeconds() {
        for (crossing in listOf(false, true)) {
            val coarse = ReplayTimeline.run(ReplayTimeline.scenario(crossing = crossing))
            val fine = ReplayTimeline.run(ReplayTimeline.scenario(crossing = crossing), tickMs = 100)
            val esc = coarse.events.filter { it.phase == Phase.ESCALATION && it.hex != null }
            assertTrue(esc.isNotEmpty())
            for (e in esc) {
                val lat = AlertLatency.ms(e, tickWallMs = 0, soundStartWallMs = soundStartBudgetMs)
                if (lat != null) {
                    println("${if (crossing) "crossing" else "pinned  "} ${ReplayTimeline.hms(e.timeMs)} ${e.tier!!.label.padEnd(14)} ${AlertLatency.label(lat)} (interpolated)")
                    assertTrue(AlertLatency.label(lat), lat < AlertLatency.BUDGET_MS)
                }
                val ref = fine.events.firstOrNull { it.hex == e.hex && it.tier == e.tier && it.phase == Phase.ESCALATION && it.timeMs > e.timeMs - 3000 }
                if (ref != null) {
                    val d = e.timeMs - ref.timeMs + soundStartBudgetMs
                    println("${if (crossing) "crossing" else "pinned  "} ${ReplayTimeline.hms(e.timeMs)} ${e.tier!!.label.padEnd(14)} vs 10 Hz: ${AlertLatency.label(d)}")
                    assertTrue("$d ms", d < AlertLatency.BUDGET_MS)
                }
            }
        }
    }

    @Test fun formula() {
        val e = AlertEvent(10_000, EventKind.TRAFFIC, Severity.WARNING, "x", crossedAtMs = 9_400)
        assertEquals(600L + 150L, AlertLatency.ms(e, tickWallMs = 5_000, soundStartWallMs = 5_150))
        assertEquals(150L + 150L, AlertLatency.ms(e, 5_000, 5_150, replaySpeed = 4.0))
        assertEquals(null, AlertLatency.ms(e.copy(crossedAtMs = null), 0, 0))
        assertEquals("alert latency 0.4 s", AlertLatency.label(400))
    }
}
