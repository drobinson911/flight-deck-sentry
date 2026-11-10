package com.uasflightdeck.sentry

import com.uasflightdeck.sentry.core.Parsers
import kotlinx.serialization.json.JsonObject

/**
 * Which station base URLs to try, in order. A bare IP or host (no port typed) could be a Pi
 * Overwatch (port 8080) or a Micro Bar (port 80), so both are tried. An explicit port is
 * never second-guessed.
 */
object StationCandidates {
    const val PI_PORT = 8080
    const val MICRO_BAR_PORT = 80

    /** Scheme + host part of a base URL, port stripped ("http://h:80" -> "http://h"). */
    private fun hostOf(base: String): String = base.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    /** True when the user typed no port (so we may try more than one). */
    fun isPortless(raw: String): Boolean {
        val s = raw.trim().trimEnd('/')
        if (s.isEmpty() || s.startsWith("https://")) return false
        val hostPart = s.removePrefix("http://").substringBefore('/')
        return !hostPart.contains(':')
    }

    /**
     * @param typed      the Settings text
     * @param beacon     last beacon-discovered base, if any
     * @param remembered the base that last answered with a valid aircraft.json, if any
     */
    fun order(typed: String, beacon: String?, remembered: String?): List<String> {
        val primary = Settings.normalizeStationBase(typed)
        val out = LinkedHashSet<String>()
        if (primary != null) {
            if (!isPortless(typed)) {
                out += primary
            } else {
                val host = hostOf(primary)
                val pi = "http://$host:$PI_PORT"
                val mb = "http://$host:$MICRO_BAR_PORT"
                // Beacon from the same host is authoritative for the port; then what worked last time.
                if (beacon != null && hostOf(beacon) == host) out += beacon
                if (remembered != null && hostOf(remembered) == host && remembered in listOf(pi, mb)) out += remembered
                out += pi
                out += mb
            }
        }
        if (beacon != null) out += beacon   // existing fallback: any discovered station
        return out.toList()
    }

    /** True if [text] is a readsb-shaped aircraft.json (an object with an aircraft/ac array; may be empty). */
    fun isReadsbShaped(text: String): Boolean {
        val o = runCatching { Parsers.parse(text) as? JsonObject }.getOrNull() ?: return false
        return o["aircraft"] is kotlinx.serialization.json.JsonArray || o["ac"] is kotlinx.serialization.json.JsonArray
    }

    /** Short "host:port" for the health line. */
    fun label(base: String): String = base.substringAfter("://").trimEnd('/')
}
