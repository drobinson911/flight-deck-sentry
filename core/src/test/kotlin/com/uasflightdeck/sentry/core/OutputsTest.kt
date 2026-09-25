package com.uasflightdeck.sentry.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sounds, vibration and banners: what the pilot actually gets. */
class OutputsTest {
    private val T = 1_790_000_000_000L
    private fun tr(tier: Tier, phase: Phase = Phase.ESCALATION, cue: Cue = Cue.FULL, hex: String = "a1", dist: Double = 1.0) =
        AlertEvent(T, EventKind.TRAFFIC, tier.severity, "x", hex = hex, distNm = dist, tier = tier, phase = phase, cue = cue,
            popup = phase == Phase.ESCALATION, banner = BannerText("t", "2", "3"))

    private fun plan(vararg e: AlertEvent, style: AlertStyle = AlertStyle.STANDARD) = OutputPlanner.plan(e.toList(), MuteBook(), style, T)

    @Test fun advisoryIsBannerOnlyByDefault() {
        val o = plan(tr(Tier.ADVISORY)).single()
        assertFalse(o.sounds); assertFalse(o.vibrates)
        assertEquals(OutputPlanner.BannerAction.POPUP, o.banner)                      // the banner still pops up
        assertEquals("advisory is banner-only", o.suppressed)
        assertTrue(plan(tr(Tier.ADVISORY), style = AlertStyle.LOUD).single().sounds)  // only the Loud style sounds it
        assertTrue(plan(tr(Tier.CAUTION)).single().sounds)                              // caution keeps its sound
        assertFalse(plan(tr(Tier.ADVISORY), style = AlertStyle.QUIET).single().sounds)
    }

    @Test fun quietStyleMakesCautionBannerOnly() {
        assertFalse(plan(tr(Tier.CAUTION), style = AlertStyle.QUIET).single().sounds)
        assertTrue(plan(tr(Tier.WARNING), style = AlertStyle.QUIET).single().sounds)
        assertEquals(2.0, AlertStyle.QUIET.cadenceScale, 0.0)
    }

    @Test fun oneSoundPerTickTheMostUrgent() {
        val out = plan(tr(Tier.CAUTION, hex = "c"), tr(Tier.COLLISION, hex = "x"), tr(Tier.WARNING, hex = "w"),
            AlertEvent(T, EventKind.INTERNET_LOST, Severity.CAUTION, "Internet offline"))
        assertEquals(listOf(SoundLevel.COLLISION), out.filter { it.sounds }.map { it.level })
        assertEquals(3, out.count { it.suppressed == "another sound this tick" })
        // every banner still posts
        assertEquals(4, out.count { it.banner == OutputPlanner.BannerAction.POPUP })
    }

    @Test fun bannerActions() {
        assertEquals(OutputPlanner.BannerAction.SILENT, plan(tr(Tier.WARNING, Phase.REPEAT, Cue.SHORT)).single().banner)
        assertEquals(OutputPlanner.BannerAction.SILENT, plan(tr(Tier.TRACK, Phase.UPDATE, Cue.NONE)).single().banner)
        val clear = AlertEvent(T, EventKind.CLEAR, Severity.INFO, "c", hex = "a1", banner = BannerText("○ CLEAR · N", "", ""))
        assertEquals(OutputPlanner.BannerAction.CANCEL, plan(clear).single().banner)
        val stale = AlertEvent(T, EventKind.TRAFFIC_STALE, Severity.CAUTION, "Traffic data stale")
        assertEquals(OutputPlanner.BannerAction.NONE, plan(stale).single().banner)     // screen only
        assertFalse(plan(stale).single().sounds)
    }

    @Test fun housekeepingSoundLevels() {
        fun lv(k: EventKind, text: String = "x", repeat: Boolean = false) = OutputPlanner.levelFor(AlertEvent(T, k, Severity.INFO, text, repeat = repeat))
        assertEquals(SoundLevel.INTERNET, lv(EventKind.INTERNET_LOST)); assertEquals(SoundLevel.INTERNET, lv(EventKind.INTERNET_REGAINED))
        assertEquals(SoundLevel.BOUND, lv(EventKind.SELECTION, "Watching DEMO-1, this controller's aircraft."))
        assertNull(lv(EventKind.SELECTION, "Waiting for this controller's aircraft."))
        assertEquals(SoundLevel.BOUND, lv(EventKind.OWNSHIP_LOST)); assertNull(lv(EventKind.OWNSHIP_LOST, repeat = true))
        assertEquals(SoundLevel.PREFLIGHT, lv(EventKind.PREFLIGHT)); assertEquals(SoundLevel.PREFLIGHT, lv(EventKind.SOUNDS_ON))
        for (k in listOf(EventKind.SOURCE_LOST, EventKind.TRAFFIC_STALE, EventKind.CONTROLLER_GPS_LOST, EventKind.SYSTEM)) assertNull(lv(k))
    }

    @Test fun vibrationPatternsAsPlanned() {
        assertArrayEquals(longArrayOf(0, 150), SoundLevel.TRACK.vibration)
        assertArrayEquals(longArrayOf(0, 150, 100, 150), SoundLevel.CAUTION.vibration)
        assertArrayEquals(longArrayOf(0, 300, 150, 300, 150, 300), SoundLevel.WARNING.vibration)
        assertArrayEquals(longArrayOf(0, 500, 200, 500, 200, 500), SoundLevel.COLLISION.vibration)
        for (h in listOf(SoundLevel.INTERNET, SoundLevel.BOUND, SoundLevel.PREFLIGHT)) assertArrayEquals(longArrayOf(0, 100), h.vibration)
        assertTrue(plan(tr(Tier.WARNING)).single().vibrates)
        assertFalse(plan(tr(Tier.ADVISORY)).single().vibrates)                        // banner-only = no vibrate either
    }

    @Test fun streamsAndPlayback() {
        assertTrue(SoundLevel.WARNING.alarmStream); assertTrue(SoundLevel.COLLISION.alarmStream)
        assertFalse(SoundLevel.CAUTION.alarmStream); assertFalse(SoundLevel.INTERNET.alarmStream)
        assertEquals(Playback.SHORT_MAX_MS, Playback.spec(Cue.SHORT)!!.maxMs)
        assertTrue(Playback.spec(Cue.SHORT)!!.volumeScale < 1f)
        assertNull(Playback.spec(Cue.NONE))
        // every level's default is a distinct sound
        assertEquals(SoundLevel.entries.size, SoundLevel.entries.map { it.defaults.first() }.toSet().size)
    }

    @Test fun soundUriFallbackNeverSilence() {
        val lib = mapOf("Alarm_Buzzer.ogg" to "content://media/internal/audio/media/7", "Capella.ogg" to "content://media/internal/audio/media/9")
        val ok = setOf("content://picked/1", "content://media/internal/audio/media/7", "content://media/internal/audio/media/9", "content://alarm", "content://notif")
        fun r(level: SoundLevel, stored: String, libs: Map<String, String> = lib, alarm: String? = "content://alarm", notif: String? = "content://notif") =
            SoundChoice.resolve(level, stored, { it in ok }, { libs[it] }, alarm, notif)
        assertEquals(SoundChoice.Resolved("content://picked/1", SoundChoice.Source.PICKED), r(SoundLevel.CAUTION, "content://picked/1"))
        // a picked sound that no longer resolves falls back to the level default
        assertEquals(SoundChoice.Source.LEVEL_DEFAULT, r(SoundLevel.CAUTION, "content://gone/3").source)
        assertEquals("content://media/internal/audio/media/7", r(SoundLevel.COLLISION, "").uri)
        // not in the library: alarm levels -> default alarm; others -> default notification
        assertEquals(SoundChoice.Source.DEFAULT_ALARM, r(SoundLevel.WARNING, "", emptyMap()).source)
        assertEquals(SoundChoice.Source.DEFAULT_NOTIFICATION, r(SoundLevel.TRACK, "", emptyMap()).source)
        assertEquals(SoundChoice.Source.DEFAULT_NOTIFICATION, r(SoundLevel.WARNING, "", emptyMap(), alarm = null).source)
        // nothing at all: a generated tone, never silence
        assertEquals(SoundChoice.Resolved(null, SoundChoice.Source.TONE), r(SoundLevel.TRACK, "", emptyMap(), null, null))
    }
}
