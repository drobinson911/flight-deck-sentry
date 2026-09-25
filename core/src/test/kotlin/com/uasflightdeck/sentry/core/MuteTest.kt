package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.Synthetic.sec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Got it / Ignore / Quiet 5 min, and their instant return. */
class MuteTest {
    private fun view(hex: String = "a1", tier: Tier = Tier.WARNING, miss: Double? = 0.4, trend: Trend = Trend.CONVERGING) =
        AlertEngine.TargetView(hex = hex, displayId = "N1", distNm = 1.5, bearingDeg = 0.0, dvFt = 0.0, altEstimated = false,
            trend = trend, tier = tier,
            prediction = miss?.let { Prediction.Result(40.0, it, 0.0, 1.5, -30.0, null) }
                ?: Prediction.Result(0.0, 1.5, 0.0, 1.5, 10.0, null),
            zones = emptyList(), ageSec = 1.0, sources = emptySet(), groundModeAirborne = false)

    private fun ev(t: Long, phase: Phase, tier: Tier = Tier.WARNING, cue: Cue = Cue.SHORT, hex: String = "a1", kind: EventKind = EventKind.TRAFFIC) =
        AlertEvent(t, kind, tier.severity, "x", hex = hex, tier = tier, phase = phase, cue = cue, popup = phase == Phase.ESCALATION,
            banner = BannerText("t", "2", "3"))

    private fun plan(m: MuteBook, e: AlertEvent, style: AlertStyle = AlertStyle.STANDARD) = OutputPlanner.plan(listOf(e), m, style, e.timeMs).single()

    @Test fun gotItMutesRepeatsFor60sThenReturns() {
        val m = MuteBook()
        assertEquals("Got it: N1 repeats muted 60 s", m.gotIt("a1", "N1", sec(0), 0.4))
        assertEquals("muted 47 s", m.label("a1", sec(13)))
        val r = plan(m, ev(sec(10), Phase.REPEAT))
        assertEquals(Cue.NONE, r.cue); assertEquals("muted", r.suppressed)
        assertEquals(OutputPlanner.BannerAction.SILENT, r.banner)       // banner still updates
        m.step(sec(61), listOf(view()), emptyList())
        assertTrue(plan(m, ev(sec(61), Phase.REPEAT)).sounds)
    }

    @Test fun everyMuteGivesWayToAnEscalationInstantly() {
        val m = MuteBook()
        m.gotIt("a1", "N1", sec(0), 0.4)
        m.quiet(sec(0))
        val esc = ev(sec(5), Phase.ESCALATION, Tier.COLLISION, Cue.FULL)
        val (log, _) = m.step(sec(5), listOf(view(tier = Tier.COLLISION)), listOf(esc))
        assertTrue(log.single(), log.single().startsWith("Sounds back for N1: escalated"))
        assertTrue(plan(m, esc).sounds)
        assertNull(m.aircraftMute("a1", sec(5)))
        // an escalation of ANOTHER aircraft sounds through Quiet too
        assertTrue(plan(m, ev(sec(6), Phase.ESCALATION, Tier.CAUTION, Cue.FULL, hex = "b2")).sounds)
    }

    @Test fun collisionRiskIsNeverMuted() {
        val m = MuteBook()
        m.quiet(sec(0))
        assertTrue(plan(m, ev(sec(3), Phase.REPEAT, Tier.COLLISION, Cue.FULL)).sounds)
        m.gotIt("a1", "N1", sec(0), 0.4)
        val (log, _) = m.step(sec(4), listOf(view(tier = Tier.COLLISION)), emptyList())
        assertEquals("Sounds back for N1: COLLISION RISK", log.single())
    }

    @Test fun turningTowardTheDroneEndsTheMute() {
        val m = MuteBook()
        m.gotIt("a1", "N1", sec(0), 0.8)
        assertTrue(m.step(sec(2), listOf(view(miss = 0.75)), emptyList()).first.isEmpty())     // small wobble: kept
        val (log, _) = m.step(sec(3), listOf(view(miss = 0.5)), emptyList())                   // shrank >= 0.2 nm
        assertEquals("Sounds back for N1: turning toward the drone", log.single())
        // muted while not converging; converging again = turned toward
        val m2 = MuteBook()
        m2.ignore("a1", "N1", sec(0), null)
        assertEquals("Sounds back for N1: turning toward the drone", m2.step(sec(1), listOf(view(miss = 1.2)), emptyList()).first.single())
    }

    @Test fun ignoreLastsUntilItClearsTheRings() {
        val m = MuteBook()
        m.ignore("a1", "N1", sec(0), 0.4)
        assertEquals("ignored", m.label("a1", sec(500)))
        assertEquals(Cue.NONE, plan(m, ev(sec(500), Phase.REPEAT)).cue)
        val clear = AlertEvent(sec(600), EventKind.CLEAR, Severity.INFO, "clear", hex = "a1")
        val (log, _) = m.step(sec(600), listOf(view(tier = Tier.NONE, miss = null, trend = Trend.DIVERGING)), listOf(clear))
        assertEquals("Sounds back for N1: cleared the rings", log.single())
    }

    @Test fun quietFiveMinutesThenSoundsOn() {
        val m = MuteBook()
        assertEquals("Quiet: traffic sounds off 5 min (banners still update)", m.quiet(sec(0)))
        val r = plan(m, ev(sec(100), Phase.REPEAT, hex = "zz"))
        assertEquals("quiet 5 min", r.suppressed)
        assertEquals(OutputPlanner.BannerAction.SILENT, r.banner)
        assertNull(m.step(sec(299), emptyList(), emptyList()).second)
        val on = m.step(sec(300), emptyList(), emptyList()).second
        assertNotNull(on)
        assertEquals(EventKind.SOUNDS_ON, on!!.kind)
        assertEquals(SoundLevel.PREFLIGHT, plan(m, on).level)
        assertTrue(plan(m, on).sounds)                                   // housekeeping alert
        assertTrue(plan(m, ev(sec(301), Phase.REPEAT, hex = "zz")).sounds)
    }

    @Test fun housekeepingIsNeverMutedByQuiet() {
        val m = MuteBook(); m.quiet(sec(0))
        val net = AlertEvent(sec(10), EventKind.INTERNET_LOST, Severity.CAUTION, "Internet offline")
        assertTrue(plan(m, net).sounds)
    }
}
