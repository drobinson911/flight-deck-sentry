package com.uasflightdeck.sentry.core

/**
 * Internet up / down, stacked evidence with hysteresis (v0.4.0). Inputs, every tick:
 *  - [networkUp]: Android says there is a validated network (ConnectivityManager callback);
 *  - [lastReachOkMs]: the last time ANY HTTP response came back from the worker (a poll, or the 15 s reachability
 *    probe; a 401 or 404 still proves the internet works);
 *  - [lastReachFailMs]: the last time a request to it failed at the network level (no response).
 *
 * The observed state must hold for [hysteresisMs] (10 s) before it is adopted, so a single timeout never alerts.
 * The first state after arming is adopted silently if ONLINE; OFFLINE is alerted.
 */
class InternetMonitor(private val hysteresisMs: Long = 10_000, private val okWindowMs: Long = 20_000) {
    enum class State { UNKNOWN, ONLINE, OFFLINE }

    var state = State.UNKNOWN
        private set
    private var candidate = State.UNKNOWN
    private var candidateSinceMs = 0L
    var sinceMs: Long = 0
        private set

    fun reset() { state = State.UNKNOWN; candidate = State.UNKNOWN }

    fun observed(nowMs: Long, networkUp: Boolean, lastReachOkMs: Long?, lastReachFailMs: Long?): State = when {
        !networkUp -> State.OFFLINE
        lastReachOkMs != null && nowMs - lastReachOkMs <= okWindowMs && (lastReachFailMs == null || lastReachFailMs <= lastReachOkMs) -> State.ONLINE
        lastReachFailMs != null && (lastReachOkMs == null || lastReachFailMs > lastReachOkMs) -> State.OFFLINE
        lastReachOkMs != null && nowMs - lastReachOkMs <= okWindowMs -> State.ONLINE
        lastReachOkMs != null -> State.OFFLINE                  // nothing heard for longer than the probe window
        else -> State.UNKNOWN
    }

    /** One tick: the internet lost / regained event, if the state just changed. */
    fun step(nowMs: Long, networkUp: Boolean, lastReachOkMs: Long?, lastReachFailMs: Long?): AlertEvent? {
        val obs = observed(nowMs, networkUp, lastReachOkMs, lastReachFailMs)
        if (obs == State.UNKNOWN) return null
        if (obs != candidate) { candidate = obs; candidateSinceMs = nowMs }
        if (candidate == state || nowMs - candidateSinceMs < hysteresisMs) return null
        val prev = state
        state = candidate; sinceMs = nowMs
        return when {
            state == State.OFFLINE -> AlertEvent(nowMs, EventKind.INTERNET_LOST, Severity.CAUTION, SystemText.INTERNET_LOST)
            prev == State.OFFLINE -> AlertEvent(nowMs, EventKind.INTERNET_REGAINED, Severity.INFO, SystemText.INTERNET_REGAINED)
            else -> null                                         // UNKNOWN -> ONLINE at start-up: silent
        }
    }
}
