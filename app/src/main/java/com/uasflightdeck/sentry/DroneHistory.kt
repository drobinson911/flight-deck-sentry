package com.uasflightdeck.sentry

import android.content.Context
import com.uasflightdeck.sentry.core.KnownDrones
import com.uasflightdeck.sentry.core.Ownship

/**
 * Callsigns / serials for the Settings autocomplete and the "pick a drone"
 * list: the union of what the live feed showed during THIS run (including a
 * replay's drone) and the persisted history of every drone Sentry has seen
 * live. Replay drones are kept in the session list only, never persisted.
 */
object DroneHistory {
    private var persisted: KnownDrones? = null
    private val session = KnownDrones()
    /** Callsigns (lower-case) present in the most recent live fleet poll. */
    @Volatile var liveNow: Set<String> = emptySet(); private set

    @Synchronized private fun loaded(ctx: Context): KnownDrones =
        persisted ?: KnownDrones.fromJson(Settings(ctx).knownDronesJson).also { persisted = it }

    /** A live fleet poll: remember everything (persisted when something new was learned). */
    @Synchronized fun observeLive(ctx: Context, drones: List<Ownship>, nowMs: Long) {
        session.observe(drones, nowMs)
        liveNow = drones.map { (it.callsign ?: it.name).lowercase() }.toSet()
        val k = loaded(ctx)
        if (k.observe(drones, nowMs) || nowMs - lastSaveMs > 5 * 60_000L) {
            Settings(ctx).knownDronesJson = k.toJson(); lastSaveMs = nowMs
        }
    }
    private var lastSaveMs = 0L

    /** A replay's drone: this run only. */
    @Synchronized fun observeSession(drones: List<Ownship>, nowMs: Long) { session.observe(drones, nowMs) }

    /** Newest first; session sightings win over older persisted ones. */
    @Synchronized fun entries(ctx: Context): List<KnownDrones.Entry> {
        val all = KnownDrones()
        all.merge(loaded(ctx)); all.merge(session)
        return all.entries()
    }
}
