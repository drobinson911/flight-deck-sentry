package com.uasflightdeck.sentry.core

/**
 * Per-source link health with spoken transitions ("station link lost" /
 * "station link regained"). The state shown in the UI is computed from the
 * last SUCCESS time at query time, never from the last event.
 */
class HealthMonitor {
    enum class State { WAITING, OK, LOST, DISABLED }

    class Source(val key: String, val spoken: String, val lostAfterSec: Double) {
        var enabled = true
        var lastOkMs: Long? = null
        var enabledSinceMs: Long? = null
        var lastError: String? = null
        var announcedState = State.WAITING
        var detail: String = ""
    }

    private val sources = LinkedHashMap<String, Source>()

    fun register(key: String, spoken: String, lostAfterSec: Double): Source =
        sources.getOrPut(key) { Source(key, spoken, lostAfterSec) }

    fun get(key: String) = sources[key]
    fun all(): List<Source> = sources.values.toList()

    fun setEnabled(key: String, enabled: Boolean, nowMs: Long) {
        val s = sources[key] ?: return
        if (enabled && !s.enabled) { s.enabledSinceMs = nowMs; s.announcedState = State.WAITING; s.lastOkMs = null }
        if (!enabled) s.announcedState = State.DISABLED
        s.enabled = enabled
        if (enabled && s.enabledSinceMs == null) s.enabledSinceMs = nowMs
    }

    fun ok(key: String, nowMs: Long, detail: String = "") {
        val s = sources[key] ?: return
        s.lastOkMs = nowMs; s.lastError = null; s.detail = detail
    }

    fun fail(key: String, error: String) { sources[key]?.lastError = error }

    fun ageSec(key: String, nowMs: Long): Double? = sources[key]?.lastOkMs?.let { (nowMs - it) / 1000.0 }

    fun stateOf(s: Source, nowMs: Long): State {
        if (!s.enabled) return State.DISABLED
        val ok = s.lastOkMs
        if (ok != null && (nowMs - ok) / 1000.0 <= s.lostAfterSec) return State.OK
        if (ok == null && nowMs - (s.enabledSinceMs ?: nowMs) < s.lostAfterSec * 1000) return State.WAITING
        return State.LOST
    }

    /** Transition events since the last call. */
    fun step(nowMs: Long): List<AlertEvent> {
        val out = ArrayList<AlertEvent>()
        for (s in sources.values) {
            if (!s.enabled) continue
            val st = stateOf(s, nowMs)
            if (st == s.announcedState) continue
            when {
                st == State.LOST && s.announcedState == State.OK ->
                    out += AlertEvent(nowMs, EventKind.SOURCE_LOST, Severity.CAUTION, "${s.spoken} lost")
                st == State.LOST && s.announcedState == State.WAITING ->
                    out += AlertEvent(nowMs, EventKind.SOURCE_LOST, Severity.CAUTION, "${s.spoken} not reachable")
                st == State.OK && s.announcedState == State.LOST ->
                    out += AlertEvent(nowMs, EventKind.SOURCE_REGAINED, Severity.INFO, "${s.spoken} regained")
            }
            if (st != State.WAITING) s.announcedState = st
        }
        return out
    }
}
