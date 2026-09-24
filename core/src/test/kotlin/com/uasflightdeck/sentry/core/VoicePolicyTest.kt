package com.uasflightdeck.sentry.core

import com.uasflightdeck.sentry.core.VoicePolicy.Engine
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePolicyTest {
    @Test fun rcPlusHasNoTtsSoTheBundledVoiceSpeaks() {
        assertEquals(Engine.BUNDLED, VoicePolicy().choose(0, ttsReady = false, bundledReady = true))
    }

    @Test fun ttsFirstWhenItWorks() {
        assertEquals(Engine.TTS, VoicePolicy().choose(0, ttsReady = true, bundledReady = true))
        assertEquals(Engine.TTS, VoicePolicy().choose(0, ttsReady = true, bundledReady = false))
    }

    @Test fun ttsFailureSwitchesAtOnceStaysStickyThenRetries() {
        val p = VoicePolicy(retryMs = 300_000)
        p.ttsFailed(1_000)
        assertEquals(Engine.BUNDLED, p.choose(1_000, ttsReady = true, bundledReady = true))
        assertEquals(Engine.BUNDLED, p.choose(300_999, ttsReady = true, bundledReady = true))
        assertEquals(Engine.TTS, p.choose(301_000, ttsReady = true, bundledReady = true))
    }

    @Test fun forcedBundledAndNothingAtAll() {
        assertEquals(Engine.BUNDLED, VoicePolicy().choose(0, ttsReady = true, bundledReady = true, forceBundled = true))
        assertEquals(Engine.NONE, VoicePolicy().choose(0, ttsReady = false, bundledReady = false))
        // a benched TTS with no bundled voice: nothing to speak with until the retry (tones + banners still work)
        val p = VoicePolicy(); p.ttsFailed(0)
        assertEquals(Engine.NONE, p.choose(1, ttsReady = true, bundledReady = false))
    }
}
