package com.uasflightdeck.sentry

import android.content.Context
import com.uasflightdeck.sentry.core.KnownDrones
import com.uasflightdeck.sentry.core.Ownship

/**
 * Serials / callsigns for the Settings serial autocomplete, plus the "aircraft in
 * feed now" list: the union of what the live feed showed during THIS run
 * (including a replay's drone) and the persisted history of every drone Sentry
 * has seen live. Replay drones are kept in the session list only, never persisted.
 */
object DroneHistory {
    private var persisted: KnownDrones? = null
    private val session = KnownDrones()
    /** The aircraft in the most recent fleet poll (live, or the replay's drone), one per id. */
    @Volatile var inFeedNow: List<Ownship> = emptyList(); private set
    /** Why the last fleet fetch shows what it shows ("no drones in feed", "NO TOKEN …"), for the Settings list. */
    @Volatile var feedStatus: String = ""

    @Synchronized private fun loaded(ctx: Context): KnownDrones =
        persisted ?: KnownDrones.fromJson(Settings(ctx).knownDronesJson).also { persisted = it }

    /** A live fleet poll: remember everything (persisted when something new was learned). */
    @Synchronized fun observeLive(ctx: Context, drones: List<Ownship>, nowMs: Long) {
        session.observe(drones, nowMs)
        inFeedNow = drones.groupBy { it.id }.map { (_, v) -> v.maxBy { it.posTimeMs } }
        val k = loaded(ctx)
        if (k.observe(drones, nowMs) || nowMs - lastSaveMs > 5 * 60_000L) {
            Settings(ctx).knownDronesJson = k.toJson(); lastSaveMs = nowMs
        }
    }
    private var lastSaveMs = 0L

    /** A replay's drone: this run only. */
    @Synchronized fun observeSession(drones: List<Ownship>, nowMs: Long) { session.observe(drones, nowMs); inFeedNow = drones }

    fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 90 -> "${s}s ago"
            s < 5400 -> "${s / 60} min ago"
            s < 48 * 3600 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    /** Newest first; session sightings win over older persisted ones. */
    @Synchronized fun entries(ctx: Context): List<KnownDrones.Entry> {
        val all = KnownDrones()
        all.merge(loaded(ctx)); all.merge(session)
        return all.entries()
    }
}
