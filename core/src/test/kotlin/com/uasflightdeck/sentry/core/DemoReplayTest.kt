package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The REAL 2026-09-23 data (the exact bytes bundled in the app's assets) through the full live path at 1 s ticks.
 * Prints the whole alert timeline (tier, time, sound, banner text) for the pinned drone AND the synthetic crossing
 * variant (N388KM climbing 500 fpm through the levelled drone's altitude) that shows COLLISION RISK firing.
 */
class DemoReplayTest {
    private val closest = DemoReplayFixture.CLOSEST_MS
    private fun Run(label: String, r: ReplayTimeline.Run) = r.also { ReplayTimeline.dump(label, it) }

    @Test fun pinnedDrone_timeline() {
        val r = Run("PINNED DRONE (DEMO-1 by serial) vs N388KM, merged track", ReplayTimeline.run(ReplayTimeline.scenario()))
        val ev = r.events
        assertTrue("never left pinned mode", r.modes.all { it.second == SelectionMode.PINNED })
        assertEquals("Watching DEMO-1 Pilot, this controller's aircraft.", ev.first().text)

        val n = ev.filter { it.hex == DemoReplayFixture.HEX }
        val warn = n.firstOrNull { it.tier == Tier.WARNING && it.phase == Phase.ESCALATION }
        assertNotNull("no WARNING escalation", warn)
        assertTrue("warning at ${ReplayTimeline.hms(warn!!.timeMs)} is not before the pass", warn.timeMs < closest - 20_000)
        val esc = r.outputs.filter { it.event.hex == DemoReplayFixture.HEX && it.event.phase == Phase.ESCALATION }
        assertTrue("every escalation above advisory makes a sound", esc.filter { it.event.tier!! > Tier.ADVISORY }.all { it.sounds })
        // a real pass ~0.24 nm at ~2,000+ ft vertical never reaches COLLISION RISK
        assertTrue(n.none { it.tier == Tier.COLLISION })
        // the TFR contains the drone: one zone entry
        assertEquals(1, ev.count { it.kind == EventKind.TFR_ENTRY })
        val passing = n.first { it.kind == EventKind.PASSING }
        assertTrue(passing.timeMs > closest - 5000)
        val clear = n.last()
        assertTrue(clear.kind == EventKind.CLEAR || clear.kind == EventKind.NO_LONGER_FACTOR)
        assertTrue("a closeness score is logged", n.all { it.closenessS != null })
        assertTrue(ev.none { it.kind == EventKind.OWNSHIP_LOST })
        // one sound at most per tick
        assertTrue(r.outputs.groupBy { it.event.timeMs }.values.all { tick -> tick.count { it.sounds } <= 1 })
    }

    @Test fun crossingVariant_collisionRiskFires() {
        val r = Run("SYNTHETIC CROSSING: N388KM climbing 500 fpm through the level drone's altitude at the pass",
            ReplayTimeline.run(ReplayTimeline.scenario(crossing = true)))
        val n = r.outputs.filter { it.event.hex == DemoReplayFixture.HEX }
        val col = n.firstOrNull { it.event.tier == Tier.COLLISION && it.event.phase == Phase.ESCALATION }
        assertNotNull("no COLLISION RISK", col)
        assertTrue(col!!.sounds && col.level == SoundLevel.COLLISION)
        assertTrue("collision at ${ReplayTimeline.hms(col.event.timeMs)}", col.event.timeMs < closest - 20_000)
        assertTrue(col.event.banner!!.title == "‼ COLLISION RISK · N388KM")
        assertTrue(col.event.banner!!.line3, col.event.banner!!.line3.contains("climbing through your altitude"))
        // the tone repeats every 3 s while it is a collision risk
        val tones = n.filter { it.event.tier == Tier.COLLISION && it.sounds }.map { it.event.timeMs }
        assertTrue(tones.size >= 5)
        assertTrue(tones.zipWithNext().all { (a, b) -> b - a >= 3000 })
        // after the pass: one PASSING sound, then clear
        val passing = n.first { it.event.kind == EventKind.PASSING }
        assertTrue(passing.sounds)
        assertTrue(n.none { it.event.tier == Tier.COLLISION && it.event.timeMs > passing.event.timeMs })
    }

    /** The public feed carried N388KM as alt_baro "ground", no track, at 160 kt: altitude unknown counts as inside. */
    @Test fun publicFeedGroundMode_stillWarns() {
        val r = Run("PUBLIC-FEED VIEW (alt 'ground', no track)", ReplayTimeline.run(ReplayTimeline.scenario(cloudView = true)))
        val warn = r.events.firstOrNull { it.hex == DemoReplayFixture.HEX && it.tier == Tier.WARNING }
        assertNotNull(warn)
        assertTrue(warn!!.timeMs < closest)
        assertTrue(warn.banner!!.line2, warn.banner!!.line2.contains("alt unknown"))
    }
}
