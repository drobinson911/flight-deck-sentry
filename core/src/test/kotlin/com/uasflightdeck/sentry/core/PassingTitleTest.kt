package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.Synthetic.O
import com.uasflightdeck.sentry.core.Synthetic.own
import com.uasflightdeck.sentry.core.Synthetic.sec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.2: after a close pass the banner stays "● PASSING · <id>" (grey, silent, in place) while the aircraft diverges,
 * even inside the 0.5 nm ring; 0.4.1's per-second refresh re-titled it "⚠ WARNING · N388KM · Not closing".
 * Only turning back toward the drone re-triggers (by tier).
 */
class PassingTitleTest {

    /** Every banner the pilot can see for N388KM from the PASSING event to CLEAR: event banners + the per-second refresh. */
    private fun assertPassingHolds(label: String, r: ReplayTimeline.Run) {
        val hex = DemoReplayFixture.HEX
        val n = r.outputs.filter { it.event.hex == hex }
        val passing = n.first { it.event.kind == EventKind.PASSING }.event
        val clear = n.first { it.event.timeMs > passing.timeMs && (it.event.kind == EventKind.CLEAR || it.event.kind == EventKind.TRACK_LOST) }.event
        var diverging = 0; var insideWarningRing = 0
        for ((t, views) in r.views) {
            if (t < passing.timeMs || t >= clear.timeMs) continue
            val v = views.first { it.hex == hex }
            assertTrue("$label ${ReplayTimeline.hms(t)}: turned back?", v.trend != Trend.CONVERGING)
            diverging++
            if (v.tier == Tier.WARNING) insideWarningRing++
            val title = Banner.forView(v).title                      // what SentryService's refresh posts
            assertFalse("$label ${ReplayTimeline.hms(t)}: refresh shows '$title'", title.startsWith("⚠ WARNING"))
            assertEquals("$label ${ReplayTimeline.hms(t)}", "● PASSING · N388KM", title)
            assertTrue(v.passing)
        }
        assertTrue("$label: the ring backstop case is exercised ($insideWarningRing ticks inside 0.5 nm)", insideWarningRing >= 5)
        assertTrue(diverging > 60)
        for (o in n.filter { it.event.timeMs > passing.timeMs && it.event.timeMs < clear.timeMs }) {
            val b = o.event.banner!!
            assertFalse("$label ${ReplayTimeline.line(o)}", b.title.startsWith("⚠ WARNING"))
            assertEquals(ReplayTimeline.line(o), "● PASSING · N388KM", b.title)
            assertFalse("silent after PASSING: ${ReplayTimeline.line(o)}", o.sounds)
            assertTrue(o.banner != OutputPlanner.BannerAction.POPUP)
        }
        assertTrue(n.none { it.event.timeMs > passing.timeMs && it.event.phase == Phase.ESCALATION })
    }

    @Test fun demoReplay_noWarningTitleAfterPassing() =
        assertPassingHolds("demo", ReplayTimeline.run(ReplayTimeline.scenario()))

    @Test fun crossingReplay_noWarningTitleAfterPassing() =
        assertPassingHolds("crossing", ReplayTimeline.run(ReplayTimeline.scenario(crossing = true)))

    @Test fun publicFeedReplay_noWarningTitleAfterPassing() =
        assertPassingHolds("public feed", ReplayTimeline.run(ReplayTimeline.scenario(cloudView = true)))

    /** 60 kt northbound 0.3 nm east of the drone (CPA at t=60), turns back south at t=75 while still inside 0.5 nm. */
    private fun turnBack(t: Int): Target {
        val kt = 60.0; val nmPerSec = kt / 3600.0
        val (y, trk) = if (t <= 75) (-1.0 + nmPerSec * t) to 0.0 else (-1.0 + nmPerSec * 75 - nmPerSec * (t - 75)) to 180.0
        val p = Geo.fromEN(O, EN(Units.nmToM(0.3), Units.nmToM(y)))
        return Target(hex = "abc123", callsign = "N1234", lat = p.lat, lon = p.lon, altGeomFt = Synthetic.DRONE_FT, gsKt = kt,
            trackDeg = trk, posTimeMs = sec(t), sources = setOf("test"))
    }

    @Test fun turningBackReTriggersByTier() {
        val e = AlertEngine()
        val ev = ArrayList<AlertEvent>(); val views = HashMap<Int, AlertEngine.TargetView>()
        for (i in 0..95) {
            val res = e.step(sec(i), own(sec(i)), if (i == 0) emptyList() else listOf(turnBack(i)), emptyList(), 0.0)
            ev += res.events.filter { it.hex != null }
            res.targets.firstOrNull()?.let { views[i] = it }
        }
        val passing = ev.first { it.kind == EventKind.PASSING }
        val pSec = ((passing.timeMs - sec(0)) / 1000).toInt()
        assertTrue("passing at t=$pSec", pSec in 60..75)
        // Diverging inside the 0.5 nm ring: PASSING holds, no WARNING title.
        for (i in pSec..75) {
            val v = views.getValue(i)
            assertEquals("t=$i", Tier.WARNING, v.tier)
            assertTrue("t=$i", v.passing)
            assertEquals("● PASSING · N1234", Banner.forView(v).title)
        }
        assertTrue(ev.none { it.timeMs in (passing.timeMs + 1)..sec(75) && it.banner?.title?.startsWith("⚠ WARNING") == true })
        // Turning back toward the drone: an escalation at its tier (WARNING, full sound + popup), no longer PASSING.
        val back = ev.firstOrNull { it.timeMs > sec(75) && it.phase == Phase.ESCALATION }
        assertNotNull("turning back must re-trigger", back)
        assertTrue("re-trigger within 3 s of the turn", back!!.timeMs <= sec(78))
        assertEquals(Tier.WARNING, back.tier)
        assertEquals(Cue.FULL, back.cue); assertTrue(back.popup)
        assertEquals("⚠ WARNING · N1234", back.banner!!.title)
        val after = views.getValue(((back.timeMs - sec(0)) / 1000).toInt())
        assertFalse(after.passing)
        assertTrue(Banner.forView(after).title.startsWith("⚠ WARNING"))
    }
}
