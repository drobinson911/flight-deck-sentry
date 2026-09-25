package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.Synthetic.at
import com.uasflightdeck.sentry.core.Synthetic.mover
import com.uasflightdeck.sentry.core.Synthetic.offset
import com.uasflightdeck.sentry.core.Synthetic.run
import com.uasflightdeck.sentry.core.Synthetic.s
import com.uasflightdeck.sentry.core.Synthetic.sec
import com.uasflightdeck.sentry.core.Synthetic.tgt
import com.uasflightdeck.sentry.core.Synthetic.traffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-aircraft cadence (v0.4.0), table-driven, then through the engine. */
class CadenceTest {
    private val cfg = SentryConfig()

    private data class Row(val tier: Tier, val nm: Double, val tCpa: Double?, val conv: Boolean, val every: Double?, val cue: Cue?, val c: SentryConfig = SentryConfig())

    @Test fun table() {
        val quiet = SentryConfig(cadenceScale = 2.0)
        val loud = SentryConfig(advisorySound = true)
        val rows = listOf(
            Row(Tier.COLLISION, 0.3, 20.0, true, 3.0, Cue.FULL),
            Row(Tier.COLLISION, 0.3, null, false, 3.0, Cue.FULL),          // the tone runs until it stops being a risk
            Row(Tier.WARNING, 2.0, 55.0, true, 20.0, Cue.SHORT),           // 1-3 nm (and beyond)
            Row(Tier.WARNING, 3.5, 58.0, true, 20.0, Cue.SHORT),
            Row(Tier.WARNING, 0.8, 40.0, true, 12.0, Cue.SHORT),           // 0.5-1 nm
            Row(Tier.WARNING, 0.4, 10.0, true, 6.0, Cue.SHORT),            // inside 0.5 nm
            Row(Tier.WARNING, 2.0, 25.0, true, 6.0, Cue.SHORT),            // tCPA < 30 s
            Row(Tier.WARNING, 0.4, null, false, null, null),               // not converging: no repeats
            Row(Tier.CAUTION, 0.8, 30.0, true, 20.0, Cue.SHORT),
            Row(Tier.CAUTION, 0.8, null, false, null, null),
            Row(Tier.TRACK, 5.0, 100.0, true, 30.0, Cue.NONE),             // banner-only
            Row(Tier.ADVISORY, 2.5, 60.0, true, 30.0, Cue.NONE),           // banner-only by default
            Row(Tier.ADVISORY, 2.5, 60.0, true, 30.0, Cue.SHORT, loud),    // Loud style
            Row(Tier.NONE, 2.5, 60.0, true, null, null),
            // Quiet: every interval doubled, except the collision tone
            Row(Tier.WARNING, 0.4, 10.0, true, 12.0, Cue.SHORT, quiet),
            Row(Tier.WARNING, 2.0, 50.0, true, 40.0, Cue.SHORT, quiet),
            Row(Tier.COLLISION, 0.3, 20.0, true, 3.0, Cue.FULL, quiet),
            // never faster than 6 s
            Row(Tier.WARNING, 0.4, 10.0, true, 6.0, Cue.SHORT, SentryConfig(warnCloseSec = 2.0)),
        )
        for (r in rows) {
            val rep = Cadence.repeat(r.tier, r.nm, r.tCpa, r.conv, r.c)
            assertEquals("$r", r.every, rep?.everySec)
            assertEquals("$r", r.cue, rep?.cue)
        }
    }

    /** Head-on, slow (60 kt) from 2.5 nm with a 0.3 nm miss: WARNING repeats tighten 20 -> 12 -> 6 s, never faster. */
    @Test fun warningRepeatsTightenAndNeverFasterThan6s() {
        val start = offset(0.0, 1.2, 0.3)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..70) { if (it == 0) emptyList() else listOf(mover(it, start, 60.0, 180.0)) })
        val esc = ev.first { it.phase == Phase.ESCALATION && it.tier == Tier.WARNING }
        val reps = ev.filter { it.tier == Tier.WARNING && it.phase == Phase.REPEAT && it.timeMs > esc.timeMs }.map { it.timeMs }
        val times = listOf(esc.timeMs) + reps
        assertTrue(times.size >= 4)
        assertTrue("gaps ${times.zipWithNext { a, b -> (b - a) / 1000 }}", times.zipWithNext().all { (a, b) -> b - a >= 6000 })
        assertTrue(ev.filter { it.phase == Phase.REPEAT }.all { it.cue == Cue.SHORT })
    }

    @Test fun divergingStopsRepeatsWithOnePassingSoundAfterAWarning() {
        // passes 0.2 nm abeam at 120 kt, then opens
        val start = offset(0.0, 1.0, 0.2)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..160) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) })
        val passing = ev.filter { it.kind == EventKind.PASSING }
        assertEquals(1, passing.size)
        assertEquals(Cue.FULL, passing.single().cue)
        assertEquals("● PASSING · N1234", passing.single().banner!!.title)
        assertTrue("no repeats while diverging", ev.none { it.timeMs > passing.single().timeMs && (it.phase == Phase.REPEAT || it.phase == Phase.ESCALATION) })
        val clear = ev.last()
        assertEquals(EventKind.CLEAR, clear.kind)
        assertEquals(Cue.NONE, clear.cue)
    }

    @Test fun passingAfterACautionOnlyPassIsSilent() {
        // 0.8 nm abeam: caution, never warning
        val start = offset(0.0, 1.5, 0.8)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..90) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) })
        assertTrue(ev.none { it.tier == Tier.WARNING })
        assertEquals(Cue.NONE, ev.single { it.kind == EventKind.PASSING }.cue)
    }

    @Test fun turningBackReTriggersByTier() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.8, gs = 5.0, trk = 180.0)))          // caution, converging
        val out = (2..20).flatMap { e.s(sec(it), listOf(tgt(sec(it), 0.0, 0.8, gs = 20.0, trk = 0.0))).events }   // opening
        assertEquals(listOf(EventKind.PASSING), out.map { it.kind })
        val back = e.s(sec(21), listOf(tgt(sec(21), 0.0, 0.8, gs = 20.0, trk = 180.0))).events.single()
        assertEquals(Phase.ESCALATION, back.phase)
        assertEquals(Tier.CAUTION, back.tier)
        assertEquals(Cue.FULL, back.cue)
    }

    @Test fun stepsDownAreSilent() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.4, gs = 1.0, trk = 180.0)))          // warning (ring)
        val d = e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.8, gs = 1.0, trk = 180.0))).events   // 0.8 > 0.5 + 0.2: caution
        assertEquals(Phase.DOWNGRADE, d.single().phase)
        assertEquals(Cue.NONE, d.single().cue)
        assertEquals(Tier.CAUTION, d.single().tier)
    }

    @Test fun quickStepBackUpIsNotReAlerted() {
        val e = AlertEngine()
        e.s(sec(0), emptyList())
        e.s(sec(1), listOf(tgt(sec(1), 0.0, 0.4, gs = 1.0, trk = 180.0)))          // warning
        e.s(sec(2), listOf(tgt(sec(2), 0.0, 0.8, gs = 1.0, trk = 180.0)))          // caution
        val up = e.s(sec(4), listOf(tgt(sec(4), 0.0, 0.4, gs = 1.0, trk = 180.0))).events.single()
        assertEquals(Tier.WARNING, up.tier)
        assertEquals(Cue.NONE, up.cue)                                             // within 15 s: silent
        e.s(sec(5), listOf(tgt(sec(5), 0.0, 0.8, gs = 1.0, trk = 180.0)))
        val later = e.s(sec(30), listOf(tgt(sec(30), 0.0, 0.4, gs = 1.0, trk = 180.0))).events.single()
        assertEquals(Phase.ESCALATION, later.phase)                                // 25 s later: alerted again
    }

    @Test fun trackAlertOneSoundThenBannerOnlyEvery30s() {
        val start = offset(0.0, 4.0, 0.8)
        val e = AlertEngine()
        val ev = traffic(run(e, 0..75) { if (it == 0) emptyList() else listOf(mover(it, start, 120.0, 180.0)) })
            .filter { it.tier == Tier.TRACK }
        assertEquals(Cue.FULL, ev.first().cue)
        val updates = ev.drop(1)
        assertTrue(updates.isNotEmpty())
        assertTrue(updates.all { it.phase == Phase.UPDATE && it.cue == Cue.NONE && !it.popup })
        assertTrue(updates.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == 30_000L })
    }

    @Test fun noRepeatsBeyondTheCadenceWhenAbeam() {
        // hovering helicopter at 0.4 nm: warning ring, not converging -> one alert, no repeats
        val e = AlertEngine()
        val ev = traffic(run(e, 0..60) { if (it == 0) emptyList() else listOf(tgt(Synthetic.sec(it), 90.0, 0.4)) })
        assertEquals(1, ev.size)
        assertNull(Cadence.repeat(Tier.WARNING, 0.4, null, false, SentryConfig()))
    }
}
