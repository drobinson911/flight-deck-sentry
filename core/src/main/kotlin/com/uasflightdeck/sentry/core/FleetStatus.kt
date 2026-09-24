package com.uasflightdeck.sentry.core

/**
 * The "Drone feed" row's reason line. Owner, 2026-09-24, after an hour lost to a missing token: when there are
 * no drones, say WHY: no token, token rejected, HTTP / network error, or genuinely no drones in the feed.
 */
object FleetStatus {
    const val NO_TOKEN = "NO TOKEN: paste the fleet token in Settings"

    /** One feed's fetch: [count] drones on success, else [error] (e.g. "HTTP 401", "timeout"). */
    data class Fetch(val count: Int? = null, val error: String? = null) {
        val ok get() = count != null
    }

    data class Status(val ok: Boolean, val detail: String)

    fun of(tokenMissing: Boolean, ourDrones: Fetch, droneSense: Fetch): Status {
        if (tokenMissing) return Status(false, NO_TOKEN)
        val fetches = listOf("our-drones" to ourDrones, "dronesense" to droneSense)
        if (fetches.none { it.second.ok }) {
            val errs = fetches.mapNotNull { it.second.error }
            val rejected = errs.firstOrNull { Regex("""\bHTTP (401|403)\b""").containsMatchIn(it) }
            return Status(false, when {
                rejected != null -> "TOKEN REJECTED (${Regex("""HTTP \d+""").find(rejected)!!.value}): check the fleet token"
                errs.isEmpty() -> "feed error"
                else -> "feed error: " + errs.distinct().joinToString("; ")
            })
        }
        val n = fetches.sumOf { it.second.count ?: 0 }
        val base = if (n == 0) "no drones in feed" else "$n drone${if (n == 1) "" else "s"} in feed"
        val failed = fetches.filter { !it.second.ok }.joinToString("; ") { "${it.first}: ${it.second.error ?: "error"}" }
        return Status(true, if (failed.isEmpty()) base else "$base ($failed)")
    }
}
