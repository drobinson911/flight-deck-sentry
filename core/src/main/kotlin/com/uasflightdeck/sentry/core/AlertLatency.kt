package com.uasflightdeck.sentry.core

/**
 * Alert latency (v0.4.0): from the moment a ring / tier condition became true to the first sound.
 *
 *  latency = (tick time - crossing time) / replay speed   <- detection: at most one 1 s tick, interpolated
 *          + (sound start - tick start), wall clock       <- processing + audio start
 *
 * The crossing time is the engine's interpolation between the two ticks around it ([AlertEvent.crossedAtMs]);
 * events with no crossing (first seen already inside) have no latency.
 */
object AlertLatency {
    /** The owner's budget: every escalation sound starts within 2 s of the crossing. */
    const val BUDGET_MS = 2_000L

    fun ms(ev: AlertEvent, tickWallMs: Long, soundStartWallMs: Long, replaySpeed: Double = 1.0): Long? {
        val crossed = ev.crossedAtMs ?: return null
        val detect = ((ev.timeMs - crossed) / replaySpeed.coerceAtLeast(0.01)).toLong()
        return detect + (soundStartWallMs - tickWallMs).coerceAtLeast(0)
    }

    fun label(ms: Long): String = String.format(java.util.Locale.US, "alert latency %.1f s", ms / 1000.0)
}
