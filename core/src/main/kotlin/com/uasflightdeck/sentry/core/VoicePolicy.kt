package com.uasflightdeck.sentry.core

/**
 * Which voice speaks the next callout (v0.3.5). Pure, so the stacking rules are unit-tested:
 * TTS while it works; the bundled voice when TTS is missing, not ready, forced off, or failed recently;
 * after a TTS failure the bundled voice stays in charge for [retryMs] (sticky), then TTS is tried again.
 */
class VoicePolicy(private val retryMs: Long = 5 * 60_000L) {
    enum class Engine { TTS, BUNDLED, NONE }

    private var benchedUntilMs = Long.MIN_VALUE

    fun choose(nowMs: Long, ttsReady: Boolean, bundledReady: Boolean, forceBundled: Boolean = false): Engine = when {
        ttsReady && !forceBundled && nowMs >= benchedUntilMs -> Engine.TTS
        bundledReady -> Engine.BUNDLED
        else -> Engine.NONE
    }

    /** TTS failed on a callout at [nowMs]: the bundled voice speaks it and the next ones until [retryMs] later. */
    fun ttsFailed(nowMs: Long) { benchedUntilMs = nowMs + retryMs }

    fun ttsBenched(nowMs: Long) = nowMs < benchedUntilMs
}
