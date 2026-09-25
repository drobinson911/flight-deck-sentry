package com.uasflightdeck.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.3, bug from the 0.4.2 emulator pass (crossing replay): COLLISION RISK 11:52:57, then WARNING for one tick at
 * 11:53:05, then COLLISION RISK again at 11:53:06. Cause: the crossing test ("his climb carries him through your
 * altitude while inside 0.5 nm") flickered with the feed's 25-50 ft altitude steps: the time he reaches the drone's
 * altitude (43 s) sat on the edge of the 0.5 nm window (CPA 37 s, miss ~2,600 ft), so it was true 11:52:57-59, false
 * 11:53:00-05, true again from 11:53:06. The 5 s prediction hold covered 11:53:00-04 and ran out at 11:53:05.
 * COLLISION RISK now gets the same hysteresis as the other predicted tiers once it is up (+0.2 nm window / miss,
 * +10 s horizon, +200 ft vertical), so single-tick prediction noise can't drop it.
 */
class CollisionHoldTest {
    private val run by lazy { ReplayTimeline.run(ReplayTimeline.scenario(crossing = true)) }
    private fun at(hms: String) = run.views.entries.first { ReplayTimeline.hms(it.key) == hms }.value.first { it.hex == DemoReplayFixture.HEX }

    @Test fun crossingReplay_collisionHoldsAt115305() {
        assertEquals(Tier.COLLISION, at("11:52:57").tier)
        assertEquals("11:53:05 dipped to WARNING", Tier.COLLISION, at("11:53:05").tier)
    }

    @Test fun crossingReplay_collisionContinuousUntilThePass() {
        val n = run.outputs.filter { it.event.hex == DemoReplayFixture.HEX }
        val first = n.first { it.event.tier == Tier.COLLISION && it.event.phase == Phase.ESCALATION }.event.timeMs
        val lastCol = n.last { it.event.tier == Tier.COLLISION }.event.timeMs
        // every tick from the first COLLISION RISK to the last one is COLLISION RISK (no dip)
        val dips = run.views.filter { (t, _) -> t in first..lastCol }
            .mapNotNull { (t, vs) -> vs.first { it.hex == DemoReplayFixture.HEX }.takeIf { it.tier != Tier.COLLISION }?.let { ReplayTimeline.hms(t) + " " + it.tier } }
        assertTrue("dips: $dips", dips.isEmpty())
        // no DOWNGRADE event while it is a collision risk, and exactly one escalation to it
        assertTrue(n.none { it.event.phase == Phase.DOWNGRADE && it.event.timeMs in first..lastCol })
        assertEquals(1, n.count { it.event.tier == Tier.COLLISION && it.event.phase == Phase.ESCALATION })
        // it still ends: PASSING after the pass, and never COLLISION RISK after it
        val passing = n.first { it.event.kind == EventKind.PASSING }.event.timeMs
        assertTrue(lastCol < passing)
    }

    @Test fun realPass_stillNeverCollision() {
        val r = ReplayTimeline.run(ReplayTimeline.scenario())
        assertTrue(r.views.values.flatten().none { it.tier == Tier.COLLISION })
    }
}
