package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.5: the "Clear: move …" hint flipped NW -> SE -> NW mid-pass in the demo replay (0.4.4 flight-view shots).
 *
 * Diagnosis (per-tick log of the hint's inputs): at 11:53:06 PDT (t = 1790189586000) a new N388KM fix (11:53:05.754)
 * turned his DERIVED track (the feed has no track) from 36.5° to 34.0°, and that line passed 67 ft from the drone,
 * inside the 150 ft "centred" band, so the side fell to the turn-rate fallback (-2.49 °/s = turning left -> "go right"
 * = 124° = SE). The drone was itself flying W at ~30 kt: at the closest approach (35.8 s ahead, 0.26 nm) it would be
 * 1,558 ft on his LEFT, i.e. NW was right all along. Not legitimate geometry (he was 1.5 nm out, not abeam): the side
 * test ignored the drone's own motion. Fix: side from the CPA miss vector (relative motion) + [HintHold].
 */
class HintStabilityTest {
    // The replay's own numbers at 11:53:06 PDT (engine inputs, logged per tick).
    private val trk = 34.03
    private val rel = EN(-1576.2, -2297.8)
    private val vRel = EN(53.654, 54.738)
    private val tCpa = 35.80
    private val turn = -2.494

    @Test fun the115306TickFlippedUnderTheOldRule() {
        assertEquals(67.0, Banner.sideOffsetFt(trk, rel), 1.0)                   // on his line NOW: "centred"
        assertEquals("SE", Geo.cardinalAbbrev(Banner.escapeBearing(trk, rel, turn)))   // 0.4.4 rule
        assertEquals(-1558.0, Banner.sideOffsetFt(trk, rel, vRel, tCpa), 2.0)   // his LEFT at the CPA
        assertEquals("NW", Geo.cardinalAbbrev(Banner.escapeBearing(trk, rel, turn, vRel = vRel, tCpaSec = tCpa)))
    }

    @Test fun notConvergingUsesTheOffsetNow() {
        // tCpa 0 / null -> the current offset decides, as before (existing BannerTest cases still hold).
        assertEquals(90.0, Banner.escapeBearing(0.0, EN(-Units.nmToM(0.5), 0.0), null, vRel = EN(0.0, 10.0), tCpaSec = 0.0), 1e-9)
        assertEquals(270.0, Banner.escapeBearing(0.0, EN(Units.nmToM(0.5), 0.0), null, vRel = EN(0.0, 10.0), tCpaSec = null), 1e-9)
    }

    @Test fun holdSuppressesTheOneTickFlipFromTheReplay() {
        // 0.4.4's computed bearings 11:53:03 .. 11:53:10 (t, candidate, his track): the SE at 11:53:06 lasts one tick.
        val seq = listOf(
            Triple(1790189583000L, 306.5, 36.53), Triple(1790189584000L, 306.5, 36.53), Triple(1790189585000L, 306.5, 36.53),
            Triple(1790189586000L, 124.0, 34.03), Triple(1790189587000L, 304.0, 34.03), Triple(1790189588000L, 304.0, 34.03),
            Triple(1790189589000L, 304.5, 34.49), Triple(1790189590000L, 304.5, 34.49))
        val h = HintHold()
        val shown = seq.map { (t, c, k) -> Geo.cardinalAbbrev(h.step(t, c, k)!!) }
        assertEquals(List(seq.size) { "NW" }, shown)
    }

    @Test fun holdChangesAfterFiveSecondsHeld() {
        val h = HintHold()
        assertEquals(315.0, h.step(0, 315.0, 45.0)!!, 0.0)
        // SE computed from t=1 s on, same track: still NW until it has held 5 s (t = 6 s)
        for (t in 1..5) assertEquals("t=$t", "NW", Geo.cardinalAbbrev(h.step(t * 1000L, 135.0, 45.0)!!))
        assertEquals("SE", Geo.cardinalAbbrev(h.step(6000, 135.0, 45.0)!!))
    }

    @Test fun holdRestartsWhenTheCandidateWobbles() {
        val h = HintHold()
        h.step(0, 315.0, 45.0)
        h.step(1000, 135.0, 45.0); h.step(2000, 135.0, 45.0)
        h.step(3000, 310.0, 45.0)                                   // back to NW: the SE timer restarts
        for (t in 4..8) assertEquals("NW", Geo.cardinalAbbrev(h.step(t * 1000L, 135.0, 45.0)!!))
        assertEquals("SE", Geo.cardinalAbbrev(h.step(9000, 135.0, 45.0)!!))
    }

    @Test fun realTurnOverFortyFiveDegreesSwitchesAtOnce() {
        val h = HintHold()
        h.step(0, 315.0, 45.0)
        assertEquals("NW", Geo.cardinalAbbrev(h.step(1000, 225.0, 80.0)!!))    // 35° turn: held
        assertEquals("SW", Geo.cardinalAbbrev(h.step(2000, 225.0, 135.0)!!))   // 90° turn: shown now
    }

    @Test fun sameWordFollowsTheBearingAndNoTrackResets() {
        val h = HintHold()
        h.step(0, 315.0, 45.0)
        assertEquals(300.0, h.step(1000, 300.0, 40.0)!!, 0.0)                   // still "NW": follows
        assertNull(h.step(2000, null, null))
        assertEquals(135.0, h.step(3000, 135.0, 45.0)!!, 0.0)                   // fresh start: shown at once
    }

    private fun bearingOf(w: String) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW").indexOf(w) * 45.0

    /** The whole demo replay (and the crossing variant): the shown hint never changes direction during the pass. */
    @Test fun replayHintIsStableThroughThePass() {
        for (crossing in listOf(false, true)) {
            val r = ReplayTimeline.run(ReplayTimeline.scenario(crossing = crossing))
            val hints = r.views.mapNotNull { (t, vs) ->
                vs.firstOrNull { it.hex == DemoReplayFixture.HEX && it.tier >= Tier.TRACK }?.let { v -> Banner.traffic(v).line4?.let { t to it } }
            }
            assertTrue("crossing=$crossing: hints shown", hints.size > 30)
            val dirs = hints.map { it.first to it.second.removePrefix("Clear: move ").substringBefore(' ') }
            val changes = dirs.zipWithNext().filter { (a, b) -> a.second != b.second }
            println("crossing=$crossing hint changes: " + changes.joinToString { (a, b) -> "${ReplayTimeline.hms(b.first)} ${a.second}->${b.second}" })
            // never a reversal (the 0.4.4 NW -> SE -> NW): in this replay the hint never changes at all
            assertTrue("crossing=$crossing: $changes", changes.isEmpty())
            changes.forEach { (a, b) -> assertTrue("$a -> $b", Geo.angleDiff(bearingOf(a.second), bearingOf(b.second)) <= 45.0) }
            // from the WARNING escalation to the pass: one direction only
            val warnFrom = r.views.entries.first { (_, vs) -> vs.any { it.hex == DemoReplayFixture.HEX && it.tier >= Tier.WARNING } }.key
            assertEquals("crossing=$crossing", setOf("NW"), dirs.filter { it.first >= warnFrom }.map { it.second }.toSet())
        }
    }
}
