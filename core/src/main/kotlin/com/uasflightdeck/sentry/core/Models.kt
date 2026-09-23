package com.uasflightdeck.sentry.core

/** Where the ownship (drone) position came from. Shown and spoken — never hidden. */
enum class OwnshipSource(val label: String) {
    FLEET_FDA("fleet feed (Flight Deck Air)"),
    FLEET_DRONESENSE("fleet feed (DroneSense)"),
    MANUAL_PINNED("manual position"),
    DEVICE_GPS("controller GPS"),
    REPLAY("replay"),
}

/**
 * The drone Sentry protects. [posTimeMs] is the wall-clock time the position
 * was TRUE (receive time minus the feed's own age), so age = now - posTimeMs.
 */
data class Ownship(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altMslFt: Double?,
    val altAglFt: Double?,
    val posTimeMs: Long,
    val source: OwnshipSource,
) {
    val pos get() = LatLon(lat, lon)
    val isManual get() = source == OwnshipSource.MANUAL_PINNED || source == OwnshipSource.DEVICE_GPS
}

/**
 * One aircraft, normalized from any feed. Altitudes in feet.
 * [altBaroFt] is PRESSURE altitude, [altGeomFt] GPS/geometric (MSL-ish).
 * [reportsGround] = the transponder said "ground". That is NOT trusted on its
 * own: N388KM reported "ground" at 160 kt through the whole 2026-09-23 pass.
 */
data class Target(
    val hex: String,
    val callsign: String? = null,
    val registration: String? = null,
    val type: String? = null,
    val lat: Double,
    val lon: Double,
    val altBaroFt: Double? = null,
    val altGeomFt: Double? = null,
    val reportsGround: Boolean = false,
    val gsKt: Double? = null,
    val trackDeg: Double? = null,
    val vsFpm: Double? = null,
    val posTimeMs: Long,
    val sources: Set<String> = emptySet(),
) {
    val pos get() = LatLon(lat, lon)

    /** What a human (and TTS) calls this aircraft. */
    val displayId: String
        get() = callsign?.trim()?.takeIf { it.isNotEmpty() }
            ?: registration?.trim()?.takeIf { it.isNotEmpty() }
            ?: "hex ${hex.uppercase()}"
}

data class Polygon(val outer: List<LatLon>, val holes: List<List<LatLon>> = emptyList())

enum class AltRef { MSL, AGL, UNKNOWN }

/** A vertical limit. value == null + ref UNKNOWN = "see NOTAM". */
data class AltLimit(val valueFt: Double?, val ref: AltRef) {
    companion object {
        val SURFACE = AltLimit(0.0, AltRef.AGL)
        val UNKNOWN = AltLimit(null, AltRef.UNKNOWN)
    }
}

enum class ZoneKind { TFR, GEOFENCE }

/**
 * A TFR or custom geofence. For a moving circle geofence (centred on the
 * drone) the service rebuilds the polygon each tick.
 */
data class Zone(
    val id: String,
    val name: String,
    val kind: ZoneKind,
    val polygons: List<Polygon>,
    val floor: AltLimit = AltLimit.SURFACE,
    val ceiling: AltLimit = AltLimit.UNKNOWN,
) {
    /** How the zone is spoken: "TFR 0 0000" (slash is read as a date otherwise). */
    val spokenName: String
        get() = when (kind) {
            ZoneKind.TFR -> "TFR " + name.replace("/", " ")
            ZoneKind.GEOFENCE -> "geofence $name"
        }
    val displayName: String
        get() = when (kind) {
            ZoneKind.TFR -> "TFR $name"
            ZoneKind.GEOFENCE -> "geofence $name"
        }
}

enum class Severity(val rank: Int, val label: String) {
    NONE(0, "none"),
    INFO(1, "info"),
    ADVISORY(2, "advisory"),
    CAUTION(3, "caution"),
    WARNING(4, "warning");
}

enum class EventKind {
    TFR_ENTRY, GEOFENCE_ENTRY, PROXIMITY, PREDICTIVE, CLEAR, TRACK_LOST,
    OWNSHIP_ACQUIRED, OWNSHIP_LOST, OWNSHIP_REGAINED, OWNSHIP_MANUAL,
    TRAFFIC_STALE, TRAFFIC_RESTORED,
    SOURCE_LOST, SOURCE_REGAINED,
    TEST, SYSTEM,
}

/**
 * One callout. [text] is what the screen shows, [speech] what TTS says (the
 * same content, with ids spelled so TTS reads them letter by letter).
 */
data class AlertEvent(
    val timeMs: Long,
    val kind: EventKind,
    val severity: Severity,
    val text: String,
    val speech: String = text,
    val hex: String? = null,
)

/** Every tunable in one place. Defaults are the spec's defaults. */
data class SentryConfig(
    val advisoryNm: Double = 3.0,
    val cautionNm: Double = 1.0,
    val warningNm: Double = 0.5,
    val verticalBandFt: Double = 2000.0,
    val baroCorrectionFt: Double = 300.0,
    val cpaHorizonSec: Double = 60.0,
    val reannounceSec: Double = 20.0,
    val advisoryReannounceSec: Double = 30.0,
    val staleTargetSec: Double = 30.0,
    val ownshipLostSec: Double = 15.0,
    val trafficStaleSec: Double = 30.0,
    /** Only TFRs whose edge is within this distance of the drone are watched. */
    val tfrRelevanceNm: Double = 10.0,
    /** Dead-reckon a target at most this far past its last position report. */
    val maxExtrapolationSec: Double = 10.0,
    /** A "ground" report faster than this is treated as AIRBORNE, altitude unknown. */
    val groundSpeedAirborneKt: Double = 50.0,
    /** TFR with no published ceiling: assume this MSL top so airliners at FL350 don't trip it. */
    val unknownTfrCeilingFt: Double = 18_000.0,
    /** Hysteresis on ring exits so a target on a ring edge doesn't flap. */
    val ringHysteresisNm: Double = 0.2,
    val bandHysteresisFt: Double = 200.0,
    /** Reminder cadence while the drone position stays lost. */
    val ownshipLostReminderSec: Double = 120.0,
) {
    fun ringsValid(): Boolean = warningNm > 0 && cautionNm >= warningNm && advisoryNm >= cautionNm
}
